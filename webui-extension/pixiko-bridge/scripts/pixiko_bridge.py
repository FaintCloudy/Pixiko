"""Synchronize A1111 txt2img textareas with a local QQ client.

Only Python's standard library is needed by the state store and its tests.
FastAPI and the callback registry are supplied by Stable Diffusion WebUI.
"""

import csv
import hashlib
import io
import ipaddress
import json
import os
from pathlib import Path
import tempfile
import threading
import time
from urllib.parse import urlsplit


API_PREFIX = "/pixiko-bridge/v1"
MAX_PROMPT_LENGTH = 262144
LIVE_SECONDS = 6.0
PROMPT_FIELDS = ("positive", "negative")
SETTING_FIELDS = ("sampler_name", "styles", "width", "height")
STATE_FIELDS = PROMPT_FIELDS + SETTING_FIELDS
MAX_STYLE_NAME_LENGTH = 200


def webui_style_database():
    try:
        from modules import shared
    except ImportError:
        return None
    return getattr(shared, "prompt_styles", None)


def webui_style_factory(name, positive, negative, path):
    from modules.styles import PromptStyle
    return PromptStyle(name, positive, negative, path)


class StyleConflict(Exception):
    pass


class StyleService:
    """Persist just the relevant real WebUI CSV before publishing its new entry.

    Unlike StyleDatabase.save_styles(), this does not rewrite unrelated CSVs or
    mutate paths on every existing in-memory style. Dependency injection keeps
    unit tests and fixtures completely separate from the user's style database.
    """
    def __init__(self, database=webui_style_database, style_factory=webui_style_factory):
        self.database = database
        self.style_factory = style_factory
        self.lock = threading.RLock()

    @staticmethod
    def validate(value):
        if not isinstance(value, dict):
            raise ValueError("The request must be a JSON object.")
        name = value.get("name")
        if (not isinstance(name, str) or not name or name != name.strip()
                or len(name) > MAX_STYLE_NAME_LENGTH or name.startswith("#")
                or any(ord(char) < 32 or 127 <= ord(char) <= 159 or char in "\u2028\u2029" for char in name)):
            raise ValueError("name must be 1-200 characters, without surrounding whitespace, a leading #, or control characters.")
        for key in PROMPT_FIELDS:
            if not isinstance(value.get(key), str) or len(value[key]) > MAX_PROMPT_LENGTH:
                raise ValueError(key + " must be a string within the prompt length limit.")
        if type(value.get("overwrite", False)) is not bool:
            raise ValueError("overwrite must be a boolean.")
        try:
            for key in ("name", *PROMPT_FIELDS):
                value[key].encode("utf-8")
        except UnicodeError as error:
            raise ValueError("Style values must contain valid Unicode text.") from error

    def catalog_revision(self):
        with self.lock:
            database = self.database()
            if database is None:
                return ""
            names = list(database.styles)
            serialized = json.dumps(names, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            return hashlib.sha256(serialized).hexdigest()

    @staticmethod
    def _csv_rows(original):
        if not original:
            return ["name", "prompt", "negative_prompt"], []
        try:
            reader = csv.DictReader(io.StringIO(original.decode("utf-8-sig"), newline=""),
                                    skipinitialspace=True, strict=True)
            headers = list(reader.fieldnames or [])
            if "name" not in headers or not ("prompt" in headers or "text" in headers) or len(set(headers)) != len(headers):
                raise ValueError("Expected a WebUI name,prompt CSV or legacy name,text CSV.")
            rows = list(reader)
            if any(None in row or row.get("name") is None for row in rows):
                raise ValueError("The existing style CSV contains malformed rows.")
            if "prompt" not in headers:
                headers.append("prompt")
                for row in rows:
                    row["prompt"] = row.get("text", "")
            if "negative_prompt" not in headers:
                headers.append("negative_prompt")
            return headers, rows
        except (UnicodeError, csv.Error, ValueError) as error:
            raise OSError("The existing style CSV could not be safely read; it was left unchanged.") from error

    @staticmethod
    def _atomic_write(path, content, before_replace=None):
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(mode="wb", dir=str(path.parent), delete=False) as stream:
                temporary = stream.name
                stream.write(content)
                stream.flush()
                os.fsync(stream.fileno())
            if before_replace:
                before_replace()
            os.replace(temporary, path)
        finally:
            if temporary and os.path.exists(temporary):
                os.unlink(temporary)

    def save(self, value):
        self.validate(value)
        name = value["name"]
        overwrite = value.get("overwrite", False)
        with self.lock:
            database = self.database()
            if database is None:
                raise OSError("The WebUI style database is not available.")
            current = database.styles.get(name)
            known_paths = [database.default_path, *getattr(database, "all_styles_files", [])]
            known_paths.extend(getattr(style, "path", None) for style in database.styles.values())
            reserved = {Path(path).name.lower() for path in known_paths if path and path != "do_not_save"}
            if name.lower().strip("# ") in reserved:
                raise ValueError("A style name must not equal a configured CSV filename; WebUI would skip it when saving.")
            if current is not None and getattr(current, "path", None) == "do_not_save":
                raise ValueError("A style list divider cannot be overwritten.")
            if current is not None and not overwrite:
                raise StyleConflict("A preset style with this name already exists; use explicit overwrite.")
            configured_path = (getattr(current, "path", None) if current is not None else None) or str(database.default_path)
            target = Path(configured_path).resolve()
            existed = target.exists()
            original = target.read_bytes() if existed else None
            headers, rows = self._csv_rows(original)
            disk_exists = any(row["name"] == name for row in rows)
            if disk_exists and not overwrite:
                raise StyleConflict("A preset style with this name already exists in its CSV; use explicit overwrite.")
            replacement = {"name": name, "prompt": value["positive"], "negative_prompt": value["negative"]}
            if "text" in headers:
                replacement["text"] = value["positive"]
            if disk_exists:
                for row in rows:
                    if row["name"] == name:
                        row.update(replacement)
            else:
                rows.append(replacement)
            stream = io.StringIO(newline="")
            writer = csv.DictWriter(stream, fieldnames=headers, quoting=csv.QUOTE_ALL, lineterminator="\r\n")
            writer.writeheader()
            writer.writerows(rows)
            content = stream.getvalue().encode("utf-8-sig")
            # Construct the native record before committing. A factory or disk
            # failure must leave both the old CSV and in-memory catalog intact.
            style = self.style_factory(name, value["positive"], value["negative"], str(configured_path))
            target.parent.mkdir(parents=True, exist_ok=True)
            if existed:
                # Keep WebUI's own .bak untouched; this backup is bridge-specific.
                self._atomic_write(target.with_name(target.name + ".pixiko.bak"), original)

            def still_current():
                if target.exists() != existed or (existed and target.read_bytes() != original):
                    raise StyleConflict("The style CSV changed during saving; refresh and retry.")

            self._atomic_write(target, content, still_current)
            updated = dict(database.styles)
            updated[name] = style
            database.styles = updated
            return {"name": name, "overwritten": current is not None or disk_exists}

    def rename(self, value):
        """Rename one preset style inside its own CSV, keeping every other row untouched."""
        old = value["name"]
        new = value["new_name"]
        overwrite = value.get("overwrite", False)
        self.validate({"name": new, "positive": "", "negative": ""})
        with self.lock:
            database = self.database()
            if database is None:
                raise OSError("The WebUI style database is not available.")
            current = database.styles.get(old)
            if current is None:
                raise ValueError("The preset style to rename does not exist.")
            if old == new:
                return {"name": new, "renamed": False}
            if getattr(current, "path", None) == "do_not_save":
                raise ValueError("A style list divider cannot be renamed.")
            known_paths = [database.default_path, *getattr(database, "all_styles_files", [])]
            known_paths.extend(getattr(style, "path", None) for style in database.styles.values())
            reserved = {Path(path).name.lower() for path in known_paths if path and path != "do_not_save"}
            if new.lower().strip("# ") in reserved:
                raise ValueError("A style name must not equal a configured CSV filename; WebUI would skip it when saving.")
            if database.styles.get(new) is not None and not overwrite:
                raise StyleConflict("A preset style with this name already exists; use explicit overwrite.")
            configured_path = getattr(current, "path", None) or str(database.default_path)
            target = Path(configured_path).resolve()
            existed = target.exists()
            original = target.read_bytes() if existed else None
            headers, rows = self._csv_rows(original)
            matched = False
            for row in rows:
                if row["name"] == old:
                    row["name"] = new
                    if "text" in headers:
                        row["text"] = row.get("prompt", "")
                    matched = True
            if not matched:
                raise ValueError("The preset style is not in its own CSV any more; refresh and retry.")
            if sum(1 for row in rows if row["name"] == new) > 1 and not overwrite:
                raise StyleConflict("The CSV already contains the target name; use explicit overwrite.")
            stream = io.StringIO(newline="")
            writer = csv.DictWriter(stream, fieldnames=headers, quoting=csv.QUOTE_ALL, lineterminator="\r\n")
            writer.writeheader()
            writer.writerows(rows)
            content = stream.getvalue().encode("utf-8-sig")
            style = self.style_factory(new, getattr(current, "prompt", ""), getattr(current, "negative_prompt", ""), str(configured_path))
            if existed:
                self._atomic_write(target.with_name(target.name + ".pixiko.bak"), original)

            def still_current():
                if target.exists() != existed or (existed and target.read_bytes() != original):
                    raise StyleConflict("The style CSV changed during renaming; refresh and retry.")

            self._atomic_write(target, content, still_current)
            updated = dict(database.styles)
            updated.pop(old, None)
            updated[new] = style
            database.styles = updated
            return {"name": new, "renamed": True}

    def delete(self, value):
        """Remove one preset style from its own CSV, keeping every other row untouched."""
        name = value["name"]
        with self.lock:
            database = self.database()
            if database is None:
                raise OSError("The WebUI style database is not available.")
            current = database.styles.get(name)
            if current is None:
                raise ValueError("The preset style to delete does not exist.")
            if getattr(current, "path", None) == "do_not_save":
                raise ValueError("A style list divider cannot be deleted.")
            configured_path = getattr(current, "path", None) or str(database.default_path)
            target = Path(configured_path).resolve()
            existed = target.exists()
            original = target.read_bytes() if existed else None
            headers, rows = self._csv_rows(original)
            kept = [row for row in rows if row["name"] != name]
            if len(kept) == len(rows):
                raise ValueError("The preset style is not in its own CSV any more; refresh and retry.")
            stream = io.StringIO(newline="")
            writer = csv.DictWriter(stream, fieldnames=headers, quoting=csv.QUOTE_ALL, lineterminator="\r\n")
            writer.writeheader()
            writer.writerows(kept)
            content = stream.getvalue().encode("utf-8-sig")
            if existed:
                self._atomic_write(target.with_name(target.name + ".pixiko.bak"), original)

            def still_current():
                if target.exists() != existed or (existed and target.read_bytes() != original):
                    raise StyleConflict("The style CSV changed during deleting; refresh and retry.")

            self._atomic_write(target, content, still_current)
            updated = dict(database.styles)
            updated.pop(name, None)
            database.styles = updated
            return {"name": name, "deleted": True}


def webui_catalog():
    """Read the currently selectable catalog; standalone tests have no WebUI."""
    try:
        from modules import sd_samplers, shared
    except ImportError:
        return None
    return {"samplers": set(sd_samplers.visible_sampler_names()),
            "styles": set(shared.prompt_styles.styles)}


class RevisionConflict(Exception):
    def __init__(self, state):
        super().__init__("Prompt changed; read the current revision and retry.")
        self.state = state


class PromptStore:
    def __init__(self, path, clock=time.monotonic, catalog=webui_catalog, catalog_revision=lambda: ""):
        self.path = Path(path)
        self.clock = clock
        self.catalog = catalog
        self.catalog_revision = catalog_revision
        self.lock = threading.RLock()
        self.positive = ""
        self.negative = ""
        self.sampler_name = ""
        self.styles = []
        self.width = 512
        self.height = 512
        self.revision = 0
        self.initialized = False
        self.settings_initialized = False
        self.browser_seen = None
        self.browser_revision = -1
        self.browser_catalog_revision = ""
        if self.path.exists():
            value = json.loads(self.path.read_text(encoding="utf-8-sig"))
            # Older files contain only prompts. Capture actual browser settings on
            # first sync instead of restoring guessed defaults over the user's UI.
            self.validate(value, revision_key="revision", stored=True)
            for key in STATE_FIELDS:
                if key in value:
                    setattr(self, key, value[key])
            self.revision = value["revision"]
            self.initialized = value.get("initialized", True)
            self.settings_initialized = value.get("settings_initialized", False)

    @staticmethod
    def validate(value, revision_key="expected_revision", stored=False):
        if not isinstance(value, dict):
            raise ValueError("The request must be a JSON object.")
        if not stored and not any(key in value for key in STATE_FIELDS):
            raise ValueError("At least one prompt or setting is required.")
        for key in PROMPT_FIELDS:
            if key not in value and not stored:
                continue
            if not isinstance(value.get(key), str):
                raise ValueError(key + " must be a string.")
            if len(value[key]) > MAX_PROMPT_LENGTH:
                raise ValueError(key + " exceeds the maximum prompt length.")
        if "sampler_name" in value:
            sampler = value["sampler_name"]
            empty_initial = stored and not value.get("settings_initialized", False) and sampler == ""
            if not isinstance(sampler, str) or (not sampler.strip() and not empty_initial) or len(sampler) > 1024:
                raise ValueError("sampler_name must be a nonempty string.")
        if "styles" in value:
            styles = value["styles"]
            if (not isinstance(styles, list) or len(styles) > 256
                    or any(not isinstance(style, str) or not style.strip() or len(style) > 1024 for style in styles)
                    or len(set(styles)) != len(styles)):
                raise ValueError("styles must be an ordered array of unique nonempty style names.")
        for key in ("width", "height"):
            if key in value and (type(value[key]) is not int or not 64 <= value[key] <= 2048 or value[key] % 8):
                raise ValueError(key + " must be an integer from 64 to 2048, divisible by 8.")
        for key in ("initialized", "settings_initialized"):
            if key in value and type(value[key]) is not bool:
                raise ValueError(key + " must be a boolean.")
        if stored and value.get("settings_initialized", False) and not all(key in value for key in SETTING_FIELDS):
            raise ValueError("Initialized settings must contain all setting fields.")
        revision = value.get(revision_key)
        if type(revision) is not int or revision < 0:
            raise ValueError(revision_key + " must be a nonnegative integer.")

    def snapshot(self):
        with self.lock:
            catalog_revision = self.catalog_revision()
            live = (self.initialized and self.settings_initialized and self.browser_seen is not None
                    and self.clock() - self.browser_seen <= LIVE_SECONDS
                    and self.browser_revision == self.revision
                    and self.browser_catalog_revision == catalog_revision)
            return {**{key: getattr(self, key)[:] if key == "styles" else getattr(self, key) for key in STATE_FIELDS},
                    "revision": self.revision, "initialized": self.initialized,
                    "settings_initialized": self.settings_initialized,
                    "style_catalog_revision": catalog_revision,
                    "source": "webui-live" if live else "webui-state", "live": live}

    def update(self, value):
        self.validate(value)
        with self.lock:
            if value["expected_revision"] != self.revision:
                raise RevisionConflict(self.snapshot())
            supplied_settings = any(key in value for key in SETTING_FIELDS)
            if supplied_settings and not self.settings_initialized and not all(key in value for key in SETTING_FIELDS):
                raise ValueError("Settings are not initialized; first supply sampler_name, styles, width and height together.")
            catalog = self.catalog() if supplied_settings and self.catalog else None
            if catalog is not None:
                if "sampler_name" in value and value["sampler_name"] not in catalog["samplers"]:
                    raise ValueError("Unknown or hidden sampling method: " + value["sampler_name"])
                unknown = [style for style in value.get("styles", []) if style not in catalog["styles"]]
                if unknown:
                    raise ValueError("Unknown preset style(s): " + ", ".join(unknown))
            next_state = {key: value.get(key, getattr(self, key)) for key in STATE_FIELDS}
            next_state["initialized"] = self.initialized or any(key in value for key in PROMPT_FIELDS)
            next_state["settings_initialized"] = self.settings_initialized or supplied_settings
            changed = any(next_state[key] != getattr(self, key) for key in (*STATE_FIELDS, "initialized", "settings_initialized"))
            if changed:
                next_state["revision"] = self.revision + 1
                # Commit the file before advertising a successful update.
                self._persist(next_state)
                for key, content in next_state.items():
                    setattr(self, key, content[:] if key == "styles" else content)
            return self.snapshot()

    def acknowledge(self, value):
        revision = value.get("revision") if isinstance(value, dict) else None
        if type(revision) is not int or revision < 0:
            raise ValueError("revision must be a nonnegative integer.")
        with self.lock:
            # A legacy page reporting a revision alone cannot certify that the
            # sampler, selected styles and sliders reached Gradio's actual model.
            if (revision == self.revision and self.settings_initialized
                    and value.get("style_catalog_revision", "") == self.catalog_revision()
                    and all(key in value and value[key] == getattr(self, key) for key in STATE_FIELDS)):
                self.browser_revision = revision
                self.browser_catalog_revision = value.get("style_catalog_revision", "")
                self.browser_seen = self.clock()
            return self.snapshot()

    def _persist(self, value):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="\n",
                                             dir=str(self.path.parent), delete=False) as stream:
                temporary = stream.name
                json.dump(value, stream, ensure_ascii=False, indent=2)
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
        finally:
            if temporary and os.path.exists(temporary):
                os.unlink(temporary)


def loopback_name(host):
    if not host:
        return False
    if host.lower() == "localhost":
        return True
    try:
        address = ipaddress.ip_address(host)
        return address.is_loopback or (address.version == 6 and address.ipv4_mapped
                                       and address.ipv4_mapped.is_loopback)
    except ValueError:
        return False


def authorize(request, mutation=False):
    """Do not trust forwarded headers or a public hostname resolving to loopback."""
    from fastapi import HTTPException
    client = request.client.host if request.client else None
    target = urlsplit(str(request.url))
    if not loopback_name(client) or not loopback_name(target.hostname):
        raise HTTPException(403, "Pixiko bridge accepts loopback clients and hosts only.")
    origin = request.headers.get("origin")
    if origin:
        try:
            source = urlsplit(origin)
            default_port = lambda url: url.port or (443 if url.scheme == "https" else 80)
            matches = (source.scheme == target.scheme and source.hostname == target.hostname
                       and default_port(source) == default_port(target)
                       and source.username is None and source.password is None)
        except ValueError:
            matches = False
        if not matches:
            raise HTTPException(403, "Cross-origin requests are not allowed.")
    if mutation and request.headers.get("x-pixiko-bridge") != "1":
        raise HTTPException(403, "A write requires the X-Pixiko-Bridge: 1 header.")


def install_bridge(_demo, app, style_service=None):
    from fastapi import HTTPException, Request
    from fastapi.responses import JSONResponse

    if any(getattr(route, "path", None) == API_PREFIX + "/prompts" for route in app.routes):
        return
    style_service = style_service or StyleService()
    state_path = Path(__file__).resolve().parents[1] / "data" / "prompts.json"
    try:
        store = PromptStore(state_path, catalog_revision=style_service.catalog_revision)
        startup_error = None
    except (OSError, ValueError, KeyError, TypeError) as error:
        store = None
        startup_error = str(error)
        print("[Pixiko prompt bridge] Cannot read state; preserving the file:", error)

    def ready():
        if store is None:
            raise HTTPException(503, "Bridge state could not be read: " + startup_error)
        return store

    async def body(request):
        try:
            content_length = int(request.headers.get("content-length", "0"))
        except ValueError as error:
            raise HTTPException(400, "Invalid Content-Length.") from error
        if content_length > 4 * MAX_PROMPT_LENGTH:
            raise HTTPException(413, "Request is too large.")
        raw = await request.body()
        if len(raw) > 4 * MAX_PROMPT_LENGTH:
            raise HTTPException(413, "Request is too large.")
        try:
            return json.loads(raw)
        except (ValueError, UnicodeError) as error:
            raise HTTPException(400, "Invalid JSON request.") from error

    @app.get(API_PREFIX + "/prompts")
    async def get_prompts(request: Request):
        authorize(request)
        return JSONResponse(ready().snapshot(), headers={"Cache-Control": "no-store"})

    @app.put(API_PREFIX + "/prompts")
    async def put_prompts(request: Request):
        authorize(request, mutation=True)
        try:
            state = ready().update(await body(request))
        except RevisionConflict as error:
            return JSONResponse({"detail": str(error), **error.state}, status_code=409,
                                headers={"Cache-Control": "no-store"})
        except ValueError as error:
            raise HTTPException(400, str(error)) from error
        except OSError as error:
            raise HTTPException(503, "Could not persist bridge state.") from error
        return JSONResponse(state, headers={"Cache-Control": "no-store"})

    @app.post(API_PREFIX + "/heartbeat")
    async def heartbeat(request: Request):
        authorize(request, mutation=True)
        value = await body(request)
        try:
            state = ready().acknowledge(value)
        except ValueError as error:
            raise HTTPException(400, str(error)) from error
        return JSONResponse(state, headers={"Cache-Control": "no-store"})

    @app.post(API_PREFIX + "/styles")
    async def save_style(request: Request):
        authorize(request, mutation=True)
        try:
            saved = style_service.save(await body(request))
        except StyleConflict as error:
            raise HTTPException(409, str(error)) from error
        except ValueError as error:
            raise HTTPException(400, str(error)) from error
        except OSError as error:
            raise HTTPException(503, "Could not persist the WebUI preset style: " + str(error)) from error
        return JSONResponse(saved, headers={"Cache-Control": "no-store"})

    @app.post(API_PREFIX + "/styles/rename")
    async def rename_style(request: Request):
        authorize(request, mutation=True)
        try:
            renamed = style_service.rename(await body(request))
        except StyleConflict as error:
            raise HTTPException(409, str(error)) from error
        except ValueError as error:
            raise HTTPException(400, str(error)) from error
        except OSError as error:
            raise HTTPException(503, "Could not rename the WebUI preset style: " + str(error)) from error
        return JSONResponse(renamed, headers={"Cache-Control": "no-store"})
    @app.post(API_PREFIX + "/styles/delete")
    async def delete_style(request: Request):
        authorize(request, mutation=True)
        try:
            deleted = style_service.delete(await body(request))
        except StyleConflict as error:
            raise HTTPException(409, str(error)) from error
        except ValueError as error:
            raise HTTPException(400, str(error)) from error
        except OSError as error:
            raise HTTPException(503, "Could not delete the WebUI preset style: " + str(error)) from error
        return JSONResponse(deleted, headers={"Cache-Control": "no-store"})
    print("[Pixiko prompt bridge] Local prompt API ready:", API_PREFIX + "/prompts")


try:
    from modules import script_callbacks
except ModuleNotFoundError as error:
    # Allows standard-library unit tests outside an installed WebUI environment.
    if error.name != "modules":
        raise
else:
    script_callbacks.on_app_started(install_bridge)
