/* A1111 / Gradio 3.x. Serialize polls and verify native controls before live ack. */
(() => {
    "use strict";
    const fields = ["positive", "negative", "sampler_name", "styles", "width", "height"];
    const settingFields = fields.slice(2);
    const equal = (left, right) => JSON.stringify(left) === JSON.stringify(right);
    const same = (left, right) => fields.every(key => equal(left[key], right[key]));
    const settle = async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); };

    function setNativeValue(element, value) {
        const prototype = element.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
        Object.getOwnPropertyDescriptor(prototype, "value").set.call(element, String(value));
        element.dispatchEvent(new Event("input", {bubbles: true}));
        element.dispatchEvent(new Event("change", {bubbles: true}));
    }

    function createControls(root) {
        // Extensions can duplicate slider IDs; use the first main txt2img controls.
        const tab = root.querySelector("#tab_txt2img") || root;
        const nodes = {
            positive: tab.querySelector("#txt2img_prompt textarea"),
            negative: tab.querySelector("#txt2img_neg_prompt textarea"),
            sampler_name: tab.querySelector("#txt2img_sampling"),
            styles: tab.querySelector("#txt2img_styles"),
            width: tab.querySelector("#txt2img_width"),
            height: tab.querySelector("#txt2img_height")
        };
        if (Object.values(nodes).some(node => !node)) return null;

        function dropdownValue(node, multiple) {
            const select = node.querySelector("select");
            if (select) return multiple ? Array.from(select.selectedOptions, option => option.value) : select.value;
            const radio = node.querySelector('input[type="radio"]:checked');
            if (radio && !multiple) return radio.value;
            const input = node.querySelector("input");
            if (!input || input.disabled) throw new Error("Dropdown control is unavailable.");
            if (multiple) {
                // Search text is not a selected style.
                if (input.value) throw new Error("Style selection is being edited.");
                return Array.from(node.querySelectorAll(".token > span"), span => span.textContent);
            }
            if (!input.value || input.classList.contains("subdued")) throw new Error("Sampler selection is being edited.");
            return input.value;
        }

        async function choose(node, value) {
            const input = node.querySelector("input");
            if (!input || input.disabled) throw new Error("Dropdown is not interactive.");
            // Gradio 3.41 binds input.value to search text. Use the actual option
            // handler so Svelte and generation receive the selected value.
            input.dispatchEvent(new Event("focus"));
            await settle();
            const option = Array.from(node.querySelectorAll("li[data-value]")).find(item => item.dataset.value === value);
            if (!option) {
                input.dispatchEvent(new Event("blur"));
                throw new Error("WebUI dropdown option is unavailable: " + value);
            }
            option.dispatchEvent(new MouseEvent("mousedown", {bubbles: true, cancelable: true}));
            await settle();
            input.dispatchEvent(new Event("blur"));
            await settle();
        }

        async function setDropdown(node, value, multiple, unchanged) {
            const select = node.querySelector("select");
            if (select) {
                if (multiple) for (const option of select.options) option.selected = value.includes(option.value);
                else select.value = value;
                select.dispatchEvent(new Event("input", {bubbles: true}));
                select.dispatchEvent(new Event("change", {bubbles: true}));
                await settle();
                return;
            }
            const radios = Array.from(node.querySelectorAll('input[type="radio"]'));
            if (radios.length && !multiple) {
                const radio = radios.find(item => item.value === value);
                if (!radio) throw new Error("Sampling method is unavailable: " + value);
                radio.click();
                await settle();
                return;
            }
            if (multiple) {
                // Rebuild in order, because style order affects prompt expansion.
                const clear = node.querySelector(".remove-all");
                if (!clear) throw new Error("Style clear control is unavailable.");
                clear.click();
                await settle();
                for (const style of value) {
                    if (!unchanged()) throw new Error("Local edit interrupted remote style update.");
                    await choose(node, style);
                }
            } else await choose(node, value);
            if (!equal(dropdownValue(node, multiple), value)) throw new Error("Dropdown did not commit the requested selection.");
        }

        return {
            nodes,
            readStyles() { return dropdownValue(nodes.styles, true); },
            async styleChoices() {
                const select = nodes.styles.querySelector("select");
                if (select) return Array.from(select.options, option => option.value);
                const input = nodes.styles.querySelector("input");
                if (!input || input.disabled || input.value) throw new Error("Style selection is being edited or unavailable.");
                // Focusing asks Gradio to render its actual choices; this does not
                // invent options or alter the selected component value.
                input.dispatchEvent(new Event("focus"));
                await settle();
                const names = Array.from(nodes.styles.querySelectorAll("li[data-value]"), item => item.dataset.value);
                input.dispatchEvent(new Event("blur"));
                await settle();
                return names;
            },
            refreshStyles() {
                const button = tab.querySelector("#refresh_txt2img_styles") || root.querySelector("#refresh_txt2img_styles");
                if (!button || button.disabled) throw new Error("WebUI 缺少可用的 #refresh_txt2img_styles 按钮；请更新 WebUI 或刷新整个页面以加载新样式。");
                // A1111's callback reloads the style database and returns only
                // choices= updates. Its asynchronous Gradio response is awaited
                // by checking the real options on subsequent polling cycles.
                button.click();
            },
            async restoreStyles(names, unchanged = () => true) {
                if (!equal(this.readStyles(), names)) await setDropdown(nodes.styles, names, true, unchanged);
                if (!unchanged() || !equal(this.readStyles(), names)) throw new Error("Local style editing interrupted catalog refresh.");
            },
            read() {
                const result = {positive: nodes.positive.value, negative: nodes.negative.value,
                    sampler_name: dropdownValue(nodes.sampler_name, false), styles: dropdownValue(nodes.styles, true)};
                for (const key of ["width", "height"]) {
                    const input = nodes[key].querySelector('input[type="number"]') || nodes[key].querySelector('input[type="range"]');
                    const number = input ? Number(input.value) : NaN;
                    if (!Number.isInteger(number) || number < 64 || number > 2048 || number % 8) throw new Error(key + " is being edited or out of range.");
                    result[key] = number;
                }
                return result;
            },
            async apply(state, unchanged = () => true) {
                for (const key of fields) {
                    if (!unchanged()) throw new Error("Local editing interrupted synchronization.");
                    if (equal(this.read()[key], state[key])) continue;
                    if (key === "sampler_name" || key === "styles") await setDropdown(nodes[key], state[key], key === "styles", unchanged);
                    else if (key === "positive" || key === "negative") setNativeValue(nodes[key], state[key]);
                    else {
                        const input = nodes[key].querySelector('input[type="number"]') || nodes[key].querySelector('input[type="range"]');
                        setNativeValue(input, state[key]);
                    }
                    await settle();
                }
                if (!unchanged() || !same(this.read(), state)) throw new Error("Visible WebUI controls do not match the requested state.");
                // Both slider inputs must agree after the Svelte binding flushes.
                for (const key of ["width", "height"]) {
                    const inputs = nodes[key].querySelectorAll('input[type="number"], input[type="range"]');
                    if (Array.from(inputs).some(input => Number(input.value) !== state[key])) throw new Error("Slider model did not update: " + key);
                }
            }
        };
    }

    if (typeof module !== "undefined" && module.exports) module.exports = {createControls, fields, same};
    if (typeof document === "undefined") return;

    const endpoint = "/pixiko-bridge/v1";
    let started = false, applying = false, composing = false, dirty = false;
    let epoch = 0, lastInputAt = 0, base = null, controls = null, lastError = "";
    let catalogRevision = null, catalogRefresh = null;
    const bootstrapFields = new Set();
    const pendingFields = new Set();

    function touched(event, field) {
        // Real typing during asynchronous Svelte updates must abort our apply.
        if ((applying || (catalogRefresh && field === "styles")) && (!event || event.isTrusted !== true)) return;
        dirty = true;
        if (field) pendingFields.add(field);
        epoch += 1;
        lastInputAt = Date.now();
        if (catalogRefresh && field === "styles") {
            const refresh = catalogRefresh;
            // Gradio's option handler commits selection in the same event's
            // Svelte flush. Remember user edits before a delayed choices update.
            settle().then(() => {
                if (catalogRefresh !== refresh) return;
                try { refresh.selected = controls.readStyles(); refresh.editing = false; }
                catch (_) { refresh.editing = true; }
            });
        }
    }

    function attach() {
        const root = typeof gradioApp === "function" ? gradioApp() : document;
        const next = createControls(root);
        if (!next) return false;
        if (controls && fields.every(key => controls.nodes[key] === next.nodes[key])) return true;
        controls = next;
        for (const key of fields) {
            const node = controls.nodes[key];
            for (const event of ["input", "change", "click", "mousedown", "keydown"]) node.addEventListener(event, event => touched(event, key));
            if (key === "styles") node.addEventListener("focusout", event => touched(event, key));
            node.addEventListener("compositionstart", () => { composing = true; });
            node.addEventListener("compositionend", event => { composing = false; touched(event, key); });
        }
        if (base && !catalogRefresh) { for (const key of fields) pendingFields.add(key); touched(); }
        return true;
    }

    async function apply(state) {
        const beforeApply = epoch;
        applying = true;
        try { await controls.apply(state, () => epoch === beforeApply && !composing); }
        finally { applying = false; }
    }

    async function request(path, method = "GET", payload) {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), 4000);
        try {
            const response = await fetch(path.startsWith("/sdapi/") ? path : endpoint + path, {
                method, cache: "no-store", credentials: "same-origin", signal: controller.signal,
                headers: {"Content-Type": "application/json", "X-Pixiko-Bridge": "1"},
                body: payload === undefined ? undefined : JSON.stringify(payload)
            });
            const state = await response.json();
            if (response.status === 409) return {conflict: true, state};
            if (!response.ok) throw new Error(`Pixiko bridge ${response.status}: ${state.detail || response.statusText}`);
            return {conflict: false, state};
        } finally { clearTimeout(timer); }
    }

    const sameNames = (left, right) => equal([...new Set(left)].sort(), [...new Set(right)].sort());

    async function catalogNames() {
        const catalog = (await request("/sdapi/v1/prompt-styles")).state;
        if (!Array.isArray(catalog) || catalog.some(item => !item || typeof item.name !== "string"))
            throw new Error("WebUI 预设样式目录响应无效；请确认已启用 --api。");
        return catalog.map(item => item.name);
    }

    async function ensureStyleCatalog(remote) {
        if (remote.style_catalog_revision === undefined) return true; // Older bridges have no catalog protocol.
        if (typeof remote.style_catalog_revision !== "string") throw new Error("WebUI style_catalog_revision 字段无效，请更新桥接扩展。");
        if (!catalogRefresh && catalogRevision === remote.style_catalog_revision) return true;
        if (!catalogRefresh) {
            const names = await catalogNames();
            const present = await controls.styleChoices();
            if (catalogRevision === null && sameNames(present, names)) {
                catalogRevision = remote.style_catalog_revision;
                return true;
            }
            const refresh = {revision: remote.style_catalog_revision, names, requestedNames: names, startedAt: Date.now(),
                selected: controls.readStyles(), editing: false};
            catalogRefresh = refresh;
            try { controls.refreshStyles(); }
            catch (error) { catalogRefresh = null; throw error; }
            return false;
        }
        if (catalogRefresh.revision !== remote.style_catalog_revision) {
            // A second save may arrive while the original Gradio callback is
            // still running. Wait for the newest catalog without double-clicking.
            catalogRefresh.names = await catalogNames();
            catalogRefresh.revision = remote.style_catalog_revision;
        }
        const refresh = catalogRefresh;
        if (refresh.editing || composing) return false;
        const present = await controls.styleChoices();
        if (!sameNames(present, refresh.names)) {
            if (sameNames(present, refresh.requestedNames) && !sameNames(refresh.requestedNames, refresh.names)) {
                // The first callback completed with the catalog from before a
                // second save. Only now request one further native refresh.
                refresh.requestedNames = refresh.names;
                refresh.startedAt = Date.now();
                controls.refreshStyles();
                return false;
            }
            if (Date.now() - refresh.startedAt >= 10000)
                throw new Error("WebUI 样式目录刷新超时；请检查 WebUI 控制台，并手动刷新页面后重试选择新样式。");
            return false;
        }
        const beforeRestore = epoch;
        applying = true;
        try { await controls.restoreStyles(refresh.selected, () => epoch === beforeRestore && !composing); }
        finally { applying = false; }
        catalogRevision = refresh.revision;
        catalogRefresh = null;
        return true;
    }

    async function synchronize() {
        if (!attach() || composing) return;
        controls.read(); // Incomplete search or number input is not a setting.
        const beforeFetch = epoch;
        const remote = (await request("/prompts")).state;
        if (typeof remote.settings_initialized !== "boolean") throw new Error("Reload WebUI to activate the updated Pixiko bridge.");
        if (!await ensureStyleCatalog(remote)) return;
        if (!base) {
            if (!remote.initialized) for (const key of fields.slice(0, 2)) bootstrapFields.add(key);
            if (!remote.settings_initialized) for (const key of settingFields) bootstrapFields.add(key);
            for (const key of pendingFields) bootstrapFields.add(key);
            if (bootstrapFields.size || dirty || epoch !== beforeFetch) {
                // Only new or actually edited fields override persisted state.
                base = {...controls.read(), revision: remote.revision};
                dirty = true;
            } else {
                await apply(remote);
                base = remote;
            }
        }
        if (!dirty && !same(controls.read(), base)) touched();
        if (composing) return;
        if (dirty) {
            if (Date.now() - lastInputAt < 200) return;
            const local = controls.read();
            const merged = {expected_revision: remote.revision};
            for (const key of fields) merged[key] = bootstrapFields.has(key) || !equal(local[key], base[key]) ? local[key] : remote[key];
            const beforeWrite = epoch;
            const written = await request("/prompts", "PUT", merged);
            if (written.conflict || composing || epoch !== beforeWrite || !same(controls.read(), local)) return;
            await apply(written.state);
            base = written.state;
            dirty = false;
            bootstrapFields.clear();
            pendingFields.clear();
        } else if (epoch === beforeFetch && !composing) {
            await apply(remote);
            base = remote;
        } else return;
        if (!composing && !dirty && same(controls.read(), base)) {
            await request("/heartbeat", "POST", {revision: base.revision, ...controls.read(),
                ...(catalogRevision === null ? {} : {style_catalog_revision: catalogRevision})});
        }
    }

    async function tick() {
        try { await synchronize(); lastError = ""; }
        catch (error) {
            if (lastError !== String(error)) console.warn("[Pixiko prompt bridge]", error);
            lastError = String(error);
        } finally { window.setTimeout(tick, 700); }
    }

    function start() {
        if (started || !attach()) return;
        started = true;
        tick();
    }
    if (typeof onUiLoaded === "function") onUiLoaded(start);
    if (typeof onAfterUiUpdate === "function") onAfterUiUpdate(start);
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start);
    else start();
})();
