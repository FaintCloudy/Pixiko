from collections import namedtuple
import csv
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "pixiko_bridge.py"
spec = importlib.util.spec_from_file_location("pixiko_bridge", SCRIPT)
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)
StyleRecord = namedtuple("StyleRecord", "name prompt negative_prompt path", defaults=[None])


class FixtureStyleDatabase:
    """CSV fixture with the installed WebUI's old/new reader semantics."""
    def __init__(self, paths):
        self.default_path = Path(paths[0])
        self.all_styles_files = [Path(path) for path in paths]
        self.reload()

    def reload(self):
        self.styles = {}
        for path in self.all_styles_files:
            if len(self.all_styles_files) > 1:
                divider = f" {path.stem.upper()} ".center(40, "-")
                self.styles[divider] = StyleRecord(divider, None, None, "do_not_save")
            if not path.exists():
                continue
            with path.open(encoding="utf-8-sig", newline="") as stream:
                for row in csv.DictReader(stream, skipinitialspace=True):
                    if not row or row["name"].startswith("#"):
                        continue
                    self.styles[row["name"]] = StyleRecord(row["name"], row.get("prompt", row.get("text", "")),
                                                          row.get("negative_prompt", ""), str(path))


class StyleServiceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.path = self.root / "styles.csv"
        self.path.write_text('name,text,extra\n#comment,retain,metadata\nold,"old, prompt",keep\n', encoding="utf-8")
        self.other = self.root / "secondary.csv"
        self.other.write_text('name,prompt,negative_prompt\nsecond,other,negative\n', encoding="utf-8")
        self.database = FixtureStyleDatabase([self.path, self.other])
        self.service = bridge.StyleService(lambda: self.database, StyleRecord)

    def save(self, name="新的 风格", positive='quoted "portrait",\n深大', negative="blur", overwrite=False):
        return self.service.save({"name": name, "positive": positive, "negative": negative, "overwrite": overwrite})

    def test_save_real_csv_backup_legacy_columns_and_reload_without_touching_other_file(self):
        original = self.path.read_bytes()
        other_original = self.other.read_bytes()
        before_revision = self.service.catalog_revision()
        result = self.save()
        self.assertEqual(result, {"name": "新的 风格", "overwritten": False})
        self.assertNotEqual(before_revision, self.service.catalog_revision())
        self.assertEqual(self.path.with_name("styles.csv.pixiko.bak").read_bytes(), original)
        self.assertEqual(self.other.read_bytes(), other_original)
        with self.path.open(encoding="utf-8-sig", newline="") as stream:
            rows = list(csv.DictReader(stream))
        self.assertEqual(rows[0]["name"], "#comment")
        self.assertEqual(rows[0]["extra"], "metadata")
        self.assertEqual(rows[1]["extra"], "keep")
        self.assertEqual(rows[1]["prompt"], "old, prompt")
        self.assertEqual(rows[-1]["text"], 'quoted "portrait",\n深大')
        self.database.reload()
        self.assertEqual(self.database.styles["新的 风格"].prompt, 'quoted "portrait",\n深大')
        self.assertEqual(self.database.styles["新的 风格"].negative_prompt, "blur")
        self.assertEqual(self.database.styles["old"].prompt, "old, prompt")

    def test_duplicate_requires_overwrite_and_overwrite_does_not_change_catalog_names(self):
        original = self.path.read_bytes()
        with self.assertRaises(bridge.StyleConflict):
            self.save("old")
        self.assertEqual(self.path.read_bytes(), original)
        before_revision = self.service.catalog_revision()
        self.assertTrue(self.save("old", "new", "new bad", True)["overwritten"])
        self.assertEqual(before_revision, self.service.catalog_revision())
        self.database.reload()
        self.assertEqual(self.database.styles["old"].prompt, "new")
        self.assertEqual(self.database.styles["old"].negative_prompt, "new bad")

    def test_empty_prompts_are_valid_and_name_never_becomes_a_path(self):
        self.save("../literal.csv", "", "")
        self.assertFalse((self.root.parent / "literal.csv").exists())
        self.database.reload()
        self.assertEqual(self.database.styles["../literal.csv"].prompt, "")
        self.assertEqual(self.database.styles["../literal.csv"].negative_prompt, "")

    def test_invalid_names_dividers_prompt_types_and_reserved_csv_names(self):
        original = self.path.read_bytes()
        invalid = ["", " ", " leading", "trailing ", "#comment", "line\nbreak", "line\rbreak", "tab\tname",
                   "nul\0name", "del\x7fname", "control\x85name", "line\u2028name", "line\u2029name", "x" * 201,
                   "styles.csv", "SECONDARY.CSV"]
        for name in invalid:
            with self.subTest(name=repr(name)), self.assertRaises(ValueError):
                self.save(name)
        divider = next(name for name, style in self.database.styles.items() if style.path == "do_not_save")
        with self.assertRaises(ValueError):
            self.save(divider, overwrite=True)
        for payload in [{}, None, {"name": "valid", "positive": 2, "negative": ""},
                        {"name": "valid", "positive": "", "negative": "", "overwrite": "true"},
                        {"name": "valid", "positive": "", "negative": "x" * (bridge.MAX_PROMPT_LENGTH + 1)}]:
            with self.assertRaises(ValueError):
                self.service.save(payload)
        self.assertEqual(self.path.read_bytes(), original)

    def test_multi_csv_overwrite_only_touches_owning_file(self):
        original = self.path.read_bytes()
        other_original = self.other.read_bytes()
        self.save("second", "changed", "negative", True)
        self.assertEqual(self.path.read_bytes(), original)
        self.assertNotEqual(self.other.read_bytes(), other_original)
        self.assertEqual(self.other.with_name("secondary.csv.pixiko.bak").read_bytes(), other_original)
        self.database.reload()
        self.assertEqual(self.database.styles["second"].prompt, "changed")

    def test_failed_target_or_backup_commit_preserves_disk_and_memory(self):
        original = self.path.read_bytes()
        old_styles = dict(self.database.styles)
        real_replace = bridge.os.replace
        for failed_name in ["styles.csv", "styles.csv.pixiko.bak"]:
            def replace(source, target):
                if Path(target).name == failed_name:
                    raise OSError("simulated disk failure")
                return real_replace(source, target)
            with self.subTest(failed_name=failed_name), mock.patch.object(bridge.os, "replace", side_effect=replace):
                with self.assertRaises(OSError):
                    self.save("old", "replacement", "", True)
            self.assertEqual(self.path.read_bytes(), original)
            self.assertEqual(self.database.styles, old_styles)
            self.assertFalse(any(path.name.startswith("tmp") for path in self.root.iterdir()))

    def test_external_csv_edit_is_detected_before_commit(self):
        real_replace = bridge.os.replace
        external = b"name,prompt,negative_prompt\nexternal,outside,\n"
        def replace(source, target):
            result = real_replace(source, target)
            if Path(target).name == "styles.csv.pixiko.bak":
                self.path.write_bytes(external)
            return result
        with mock.patch.object(bridge.os, "replace", side_effect=replace), self.assertRaises(bridge.StyleConflict):
            self.save()
        self.assertEqual(self.path.read_bytes(), external)
        self.assertNotIn("新的 风格", self.database.styles)

    def test_unreadable_csv_does_not_get_replaced(self):
        self.path.write_bytes(b"unexpected,columns\nx,y\n")
        original = self.path.read_bytes()
        with self.assertRaises(OSError):
            self.save()
        self.assertEqual(self.path.read_bytes(), original)

    def test_new_missing_default_csv_is_created_and_reloads(self):
        target = self.root / "new-folder" / "styles.csv"
        database = FixtureStyleDatabase([target])
        service = bridge.StyleService(lambda: database, StyleRecord)
        service.save({"name": "fresh", "positive": "", "negative": ""})
        database.reload()
        self.assertEqual(database.styles["fresh"].prompt, "")
        self.assertFalse(target.with_name("styles.csv.pixiko.bak").exists())

    def test_actual_installed_webui_reader_and_native_save_round_trip(self):
        native_source = Path(sys.executable).resolve().parents[1] / "modules" / "styles.py"
        if not native_source.is_file():
            self.skipTest("Run with WebUI's bundled Python to test its actual StyleDatabase.")
        native_spec = importlib.util.spec_from_file_location("pixiko_test_native_styles", native_source)
        native = importlib.util.module_from_spec(native_spec)
        errors = types.SimpleNamespace(report=lambda *args, **kwargs: self.fail("Native WebUI reader failed"))
        with mock.patch.dict(sys.modules, {"modules": types.SimpleNamespace(errors=errors)}):
            native_spec.loader.exec_module(native)
        # Only temporary CSV paths reach the real implementation.
        database = native.StyleDatabase([str(self.path), str(self.other)])
        service = bridge.StyleService(lambda: database, native.PromptStyle)
        service.save({"name": "native round-trip", "positive": "{prompt}, watercolor", "negative": "blur"})
        database.reload()
        self.assertEqual(database.apply_styles_to_prompt("campus", ["native round-trip"]), "campus, watercolor")
        self.assertEqual(database.apply_negative_styles_to_prompt("noise", ["native round-trip"]), "noise, blur")
        database.save_styles()
        database.reload()
        self.assertIn("native round-trip", database.styles)


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / "data" / "prompts.json"
        self.now = 10.0
        self.store = bridge.PromptStore(self.path, lambda: self.now)

    def edit(self, positive="深大粤海, portrait", negative="blur", revision=0):
        return self.store.update({"positive": positive, "negative": negative,
                                  "sampler_name": "Euler a", "styles": ["水彩 风格"], "width": 640, "height": 768,
                                  "expected_revision": revision})

    def ack(self, revision):
        return self.store.acknowledge({**self.store.snapshot(), "revision": revision})

    def test_initial_and_unicode_persistence(self):
        self.assertFalse(self.store.snapshot()["initialized"])
        self.assertFalse(self.store.snapshot()["live"])
        state = self.edit()
        self.assertEqual(state["revision"], 1)
        restored = bridge.PromptStore(self.path).snapshot()
        self.assertEqual(restored["positive"], "深大粤海, portrait")
        self.assertEqual(restored["negative"], "blur")
        self.assertTrue(restored["initialized"])
        self.assertEqual(restored["source"], "webui-state")
        self.assertEqual(json.loads(self.path.read_text(encoding="utf-8"))["revision"], 1)

    def test_stale_edit_is_rejected_and_does_not_persist(self):
        self.edit()
        with self.assertRaises(bridge.RevisionConflict) as caught:
            self.edit("stale")
        self.assertEqual(caught.exception.state["revision"], 1)
        self.assertNotEqual(bridge.PromptStore(self.path).positive, "stale")

    def test_live_requires_current_revision_ack_and_expires(self):
        self.edit()
        self.ack(0)
        self.assertFalse(self.store.snapshot()["live"])
        self.ack(1)
        self.assertEqual(self.store.snapshot()["source"], "webui-live")
        self.now += 7
        self.assertFalse(self.store.snapshot()["live"])
        self.ack(1)
        self.edit("bot changed", revision=1)
        self.assertFalse(self.store.snapshot()["live"])
        self.ack(2)
        self.assertTrue(self.store.snapshot()["live"])

    def test_identical_edit_keeps_revision_and_live(self):
        self.edit()
        self.ack(1)
        self.assertEqual(self.edit(revision=1)["revision"], 1)
        self.assertTrue(self.store.snapshot()["live"])

    def test_failed_atomic_commit_keeps_original_state(self):
        self.edit()
        with mock.patch.object(bridge.os, "replace", side_effect=OSError("locked")):
            with self.assertRaises(OSError):
                self.edit("new", revision=1)
        self.assertEqual(self.store.snapshot()["revision"], 1)
        self.assertEqual(bridge.PromptStore(self.path).revision, 1)
        self.assertEqual(len(list(self.path.parent.iterdir())), 1)

    def test_invalid_prompts_and_revision(self):
        for payload in (None, {}, {"positive": 1, "negative": "", "expected_revision": 0},
                        {"positive": "", "negative": "", "expected_revision": True},
                        {"positive": "", "negative": "", "expected_revision": -1},
                        {"positive": "x" * (bridge.MAX_PROMPT_LENGTH + 1),
                         "negative": "", "expected_revision": 0}):
            with self.subTest(payload_type=type(payload)):
                with self.assertRaises(ValueError):
                    self.store.update(payload)

    def test_corrupt_existing_file_is_not_overwritten(self):
        self.path.parent.mkdir(parents=True)
        self.path.write_text("broken", encoding="utf-8")
        with self.assertRaises(ValueError):
            bridge.PromptStore(self.path)
        self.assertEqual(self.path.read_text(), "broken")

    def test_old_file_migrates_without_inventing_current_settings(self):
        self.path.parent.mkdir(parents=True)
        self.path.write_text(json.dumps({"positive": "saved", "negative": "bad", "revision": 8}), encoding="utf-8")
        migrated = bridge.PromptStore(self.path)
        self.assertFalse(migrated.snapshot()["settings_initialized"])
        state = migrated.update({"positive": "new", "expected_revision": 8})
        self.assertEqual(state["negative"], "bad")
        self.assertFalse(state["settings_initialized"])
        with self.assertRaises(ValueError):
            migrated.update({"width": 1024, "expected_revision": 9})
        state = migrated.update({"sampler_name": "Euler a", "styles": ["Soft Light"], "width": 640,
                                 "height": 768, "expected_revision": 9})
        self.assertTrue(state["settings_initialized"])
        self.assertEqual(state["positive"], "new")
        self.assertTrue(bridge.PromptStore(self.path).settings_initialized)

    def test_partial_prompt_and_setting_updates_preserve_every_other_field(self):
        initial = self.edit()
        changed = self.store.update({"width": 1024, "expected_revision": 1})
        self.assertEqual(changed["revision"], 2)
        for key in bridge.STATE_FIELDS:
            self.assertEqual(changed[key], 1024 if key == "width" else initial[key])
        changed = self.store.update({"positive": "only this", "expected_revision": 2})
        self.assertEqual(changed["styles"], ["水彩 风格"])
        self.assertEqual(changed["width"], 1024)
        self.assertEqual(changed["negative"], "blur")

    def test_dimensions_catalog_and_style_types_are_validated_atomically(self):
        self.store.catalog = lambda: {"samplers": {"Euler a", "DDIM"}, "styles": {"水彩 风格", "Soft Light"}}
        self.edit()
        invalid = [{"width": n} for n in [True, 0, 63, 65, 2056, "512", 512.0]]
        invalid += [{"sampler_name": "unknown"}, {"sampler_name": ""}, {"styles": ["unknown"]},
                    {"styles": "Soft Light"}, {"styles": ["Soft Light", "Soft Light"]}, {"styles": [[]]}]
        for patch in invalid:
            with self.subTest(patch=patch), self.assertRaises(ValueError):
                self.store.update({**patch, "expected_revision": 1})
            self.assertEqual(self.store.revision, 1)
        state = self.store.update({"styles": ["Soft Light", "水彩 风格"], "height": 2048, "expected_revision": 1})
        self.assertEqual(state["styles"], ["Soft Light", "水彩 风格"])
        self.assertEqual(bridge.PromptStore(self.path).styles, ["Soft Light", "水彩 风格"])

    def test_live_requires_full_visible_controls_not_legacy_revision_only(self):
        self.edit()
        self.store.acknowledge({"revision": 1})
        self.assertFalse(self.store.snapshot()["live"])
        self.store.acknowledge({**self.store.snapshot(), "width": 512})
        self.assertFalse(self.store.snapshot()["live"])
        self.ack(1)
        self.assertTrue(self.store.snapshot()["live"])

    def test_snapshots_and_input_style_arrays_do_not_mutate_store(self):
        self.edit()
        snapshot = self.store.snapshot()
        snapshot["styles"].append("external")
        self.assertEqual(self.store.styles, ["水彩 风格"])
        styles = ["Soft Light"]
        self.store.update({"styles": styles, "expected_revision": 1})
        styles.append("external")
        self.assertEqual(self.store.styles, ["Soft Light"])

    def test_catalog_change_revokes_live_until_matching_catalog_ack(self):
        catalog = ["first"]
        self.store.catalog_revision = lambda: catalog[0]
        self.edit()
        self.ack(1)
        self.assertTrue(self.store.snapshot()["live"])
        catalog[0] = "second"
        self.assertFalse(self.store.snapshot()["live"])
        self.store.acknowledge({**self.store.snapshot(), "style_catalog_revision": "first"})
        self.assertFalse(self.store.snapshot()["live"])
        self.ack(1)
        self.assertTrue(self.store.snapshot()["live"])


class AuthorizationTests(unittest.TestCase):
    def check(self, client="127.0.0.1", url="http://127.0.0.1:7860/pixiko-bridge/v1/prompts",
              origin=None, marker="1", mutation=True):
        class HttpError(Exception):
            def __init__(self, status, detail):
                self.status_code = status
                super().__init__(detail)

        headers = {"x-pixiko-bridge": marker}
        if origin is not None:
            headers["origin"] = origin
        request = types.SimpleNamespace(client=types.SimpleNamespace(host=client),
                                        url=url, headers=headers)
        with mock.patch.dict(sys.modules, {"fastapi": types.SimpleNamespace(HTTPException=HttpError)}):
            try:
                bridge.authorize(request, mutation)
                return 200
            except HttpError as error:
                return error.status_code

    def test_loopback_same_origin_allowed(self):
        self.assertEqual(self.check(), 200)
        self.assertEqual(self.check(origin="http://127.0.0.1:7860"), 200)
        self.assertEqual(self.check(client="::1", url="http://[::1]:7860/test",
                                    origin="http://[::1]:7860"), 200)

    def test_remote_client_dns_rebinding_and_cross_origin_rejected(self):
        self.assertEqual(self.check(client="192.168.1.20"), 403)
        self.assertEqual(self.check(url="http://evil.example:7860/test"), 403)
        for origin in ("http://evil.example", "null", "http://127.0.0.1:9999",
                       "https://127.0.0.1:7860", "http://localhost:7860"):
            self.assertEqual(self.check(origin=origin), 403)

    def test_mutations_require_custom_header(self):
        self.assertEqual(self.check(marker=None), 403)
        self.assertEqual(self.check(marker=None, mutation=False), 200)


try:
    import fastapi
    import httpx
except ImportError:
    fastapi = httpx = None


@unittest.skipIf(fastapi is None or httpx is None, "Optional WebUI HTTP runtime is unavailable")
class HttpTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        app = fastapi.FastAPI()
        self.style_path = Path(self.temporary.name) / "styles.csv"
        self.database = FixtureStyleDatabase([self.style_path])
        self.style_service = bridge.StyleService(lambda: self.database, StyleRecord)
        with mock.patch.object(bridge, "__file__", str(Path(self.temporary.name) / "scripts" / "bridge.py")):
            bridge.install_bridge(None, app, self.style_service)
        transport = httpx.ASGITransport(app=app, client=("127.0.0.1", 32100))
        self.client = httpx.AsyncClient(transport=transport, base_url="http://127.0.0.1:7860")
        self.addAsyncCleanup(self.client.aclose)

    async def test_actual_routes_persist_report_live_and_return_conflicts(self):
        initial = await self.client.get(bridge.API_PREFIX + "/prompts")
        self.assertEqual(initial.status_code, 200)
        self.assertFalse(initial.json()["initialized"])
        headers = {"X-Pixiko-Bridge": "1"}
        payload = {"positive": "丽湖, campus", "negative": "blur", "expected_revision": 0,
                   "sampler_name": "Euler a", "styles": [], "width": 512, "height": 768}
        saved = await self.client.put(bridge.API_PREFIX + "/prompts", json=payload, headers=headers)
        self.assertEqual(saved.status_code, 200)
        self.assertEqual(saved.json()["revision"], 1)
        self.assertFalse(saved.json()["live"])
        heartbeat = await self.client.post(bridge.API_PREFIX + "/heartbeat", json=saved.json(), headers=headers)
        self.assertTrue(heartbeat.json()["live"])
        stale = await self.client.put(bridge.API_PREFIX + "/prompts", json=payload, headers=headers)
        self.assertEqual(stale.status_code, 409)
        self.assertEqual(stale.json()["positive"], "丽湖, campus")
        self.assertEqual(stale.json()["revision"], 1)

    async def test_routes_reject_foreign_browser_writes_and_invalid_payloads(self):
        route = bridge.API_PREFIX + "/prompts"
        payload = {"positive": "foreign", "negative": "", "expected_revision": 0}
        self.assertEqual((await self.client.put(route, json=payload)).status_code, 403)
        self.assertEqual((await self.client.put(route, json=payload, headers={
            "X-Pixiko-Bridge": "1", "Origin": "https://foreign.example"})).status_code, 403)
        self.assertEqual((await self.client.put(route, json={}, headers={"X-Pixiko-Bridge": "1"})).status_code, 400)

    async def test_style_routes_save_conflict_overwrite_catalog_and_keep_current_prompts(self):
        headers = {"X-Pixiko-Bridge": "1"}
        before = (await self.client.get(bridge.API_PREFIX + "/prompts")).json()
        route = bridge.API_PREFIX + "/styles"
        payload = {"name": "HTTP 水彩", "positive": "campus", "negative": ""}
        saved = await self.client.post(route, json=payload, headers=headers)
        self.assertEqual(saved.status_code, 200)
        self.assertEqual(saved.json(), {"name": "HTTP 水彩", "overwritten": False})
        after = (await self.client.get(bridge.API_PREFIX + "/prompts")).json()
        self.assertNotEqual(after["style_catalog_revision"], before["style_catalog_revision"])
        for key in (*bridge.STATE_FIELDS, "revision", "initialized", "settings_initialized"):
            self.assertEqual(after[key], before[key])
        self.assertEqual((await self.client.post(route, json=payload, headers=headers)).status_code, 409)
        payload.update({"positive": "overwritten", "overwrite": True})
        replaced = await self.client.post(route, json=payload, headers=headers)
        self.assertEqual(replaced.status_code, 200)
        self.assertTrue(replaced.json()["overwritten"])
        self.database.reload()
        self.assertEqual(self.database.styles["HTTP 水彩"].prompt, "overwritten")

    async def test_style_route_authorization_validation_and_disk_failure(self):
        route = bridge.API_PREFIX + "/styles"
        payload = {"name": "safe", "positive": "", "negative": ""}
        headers = {"X-Pixiko-Bridge": "1"}
        self.assertEqual((await self.client.post(route, json=payload)).status_code, 403)
        self.assertEqual((await self.client.post(route, json=payload, headers={**headers, "Origin": "https://foreign.example"})).status_code, 403)
        self.assertEqual((await self.client.post(route, json=payload, headers={**headers, "Host": "foreign.example:7860"})).status_code, 403)
        self.assertEqual((await self.client.post(route, json={}, headers=headers)).status_code, 400)
        with mock.patch.object(bridge.os, "replace", side_effect=OSError("disk is full")):
            failed = await self.client.post(route, json=payload, headers=headers)
        self.assertEqual(failed.status_code, 503)
        self.assertNotIn("safe", self.database.styles)
        self.assertFalse(self.style_path.exists())


if __name__ == "__main__":
    unittest.main()
