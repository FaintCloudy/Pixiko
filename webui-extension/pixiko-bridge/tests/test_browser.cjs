"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const script = fs.readFileSync(path.join(__dirname, "../javascript/pixiko_bridge.js"), "utf8");

async function flush() {
    await new Promise(resolve => setImmediate(resolve));
}

async function harness({positive = "", negative = "", stored = null, sampler_name = "DPM++ 2M",
                        styles = ["水彩 风格"], width = 640, height = 768, catalogRevision,
                        refreshButton = true} = {}) {
    class Element {
        constructor() { this.handlers = {}; this.parent = null; this.tagName = "DIV"; }
        addEventListener(name, callback) { (this.handlers[name] ||= []).push(callback); }
        dispatchEvent(event) {
            for (const callback of this.handlers[event.type] || []) callback(event);
            if (event.bubbles && this.parent) this.parent.dispatchEvent(event);
        }
        click() { this.dispatchEvent({type: "click", bubbles: true, isTrusted: false}); }
        querySelector() { return null; }
        querySelectorAll() { return []; }
    }
    class Input extends Element {
        constructor(value) { super(); this._value = String(value); this.tagName = "INPUT"; this.classList = {contains: () => false}; }
        get value() { return this._value; }
        set value(value) { this._value = String(value); }
    }
    class Textarea extends Input {
        constructor(value) { super(value); this.tagName = "TEXTAREA"; }
        get value() { return this._value; }
        set value(value) { this._value = String(value); }
    }
    const settings = {sampler_name, styles, width, height};
    const state = {positive: "", negative: "", ...settings, revision: 0, initialized: false,
                   settings_initialized: Boolean(stored), ...(stored || {})};
    if (catalogRevision !== undefined) state.style_catalog_revision = catalogRevision;
    const model = {...settings, styles: [...styles]};
    const ui = {positive: new Textarea(positive), negative: new Textarea(negative)};
    const selections = [];
    const styleChoices = ["水彩 风格", "Soft Light", " Soft Light "];
    let backendChoices = [...styleChoices];
    const refreshes = [];
    for (const [key, choices] of [["sampler_name", ["DPM++ 2M", "Euler a", "DDIM"]], ["styles", ["水彩 风格", "Soft Light", " Soft Light "]]]) {
        const multiple = key === "styles";
        const node = ui[key] = new Element();
        const input = node.input = new Input(multiple ? "" : model[key]);
        input.parent = node;
        node.open = false;
        input.addEventListener("focus", () => { node.open = true; });
        input.addEventListener("blur", () => { node.open = false; });
        const clear = new Element(); clear.parent = node;
        clear.addEventListener("click", () => { model.styles = []; input.value = ""; });
        node.querySelector = selector => selector === "input" ? input : selector === ".remove-all" && multiple ? clear : null;
        node.querySelectorAll = selector => {
            if (selector === ".token > span" && multiple) return model.styles.map(style => ({textContent: style}));
            if (selector !== "li[data-value]" || !node.open) return [];
            return (multiple ? styleChoices : choices).map(choice => {
                const option = new Element(); option.dataset = {value: choice}; option.parent = node;
                option.addEventListener("mousedown", () => {
                    selections.push({key, choice});
                    // Direct input.value changes intentionally do not affect this
                    // model. Only Gradio's real mousedown selection does.
                    if (multiple) model.styles = model.styles.includes(choice) ? model.styles.filter(style => style !== choice) : [...model.styles, choice];
                    else model.sampler_name = choice;
                    queueMicrotask(() => { input.value = multiple ? "" : model.sampler_name; });
                });
                return option;
            });
        };
    }
    for (const key of ["width", "height"]) {
        const node = ui[key] = new Element();
        node.number = new Input(model[key]); node.range = new Input(model[key]);
        node.number.parent = node; node.range.parent = node;
        node.querySelector = selector => selector === 'input[type="number"]' ? node.number : selector === 'input[type="range"]' ? node.range : null;
        node.querySelectorAll = () => [node.number, node.range];
        node.number.addEventListener("input", () => {
            if (node.failCommit) return;
            model[key] = Number(node.number.value);
            queueMicrotask(() => { node.range.value = model[key]; });
        });
    }
    const lookup = {"#txt2img_prompt textarea": ui.positive, "#txt2img_neg_prompt textarea": ui.negative,
                    "#txt2img_sampling": ui.sampler_name, "#txt2img_styles": ui.styles,
                    "#txt2img_width": ui.width, "#txt2img_height": ui.height};
    if (refreshButton) {
        const button = lookup["#refresh_txt2img_styles"] = new Element();
        button.addEventListener("click", () => refreshes.push([...backendChoices]));
    }
    const tab = {querySelector: selector => lookup[selector] || null};
    const ticks = [];
    const calls = [];
    const warnings = [];
    let clock = 1000;
    let pause = null;
    let release = null;
    const context = {
        document: {readyState: "complete", querySelector: selector => selector === "#tab_txt2img" ? tab : null},
        HTMLTextAreaElement: Textarea, HTMLInputElement: Input,
        Event: class { constructor(type, options) { this.type = type; Object.assign(this, options); } },
        MouseEvent: class { constructor(type, options) { this.type = type; Object.assign(this, options); } },
        AbortController, console: {warn: (...args) => warnings.push(args.map(String).join(" "))}, module: {exports: {}},
        Date: {now: () => clock},
        setTimeout: () => 0, clearTimeout: () => {},
        window: {setTimeout: callback => ticks.push(callback)},
        onUiLoaded: () => {}, onAfterUiUpdate: () => {},
        fetch: async (url, options) => {
            const payload = options.body ? JSON.parse(options.body) : undefined;
            calls.push({url, method: options.method, payload});
            if (url === "/sdapi/v1/prompt-styles") {
                const snapshot = backendChoices.map(name => ({name}));
                return {ok: true, status: 200, json: async () => snapshot};
            }
            let status = 200;
            if (options.method === "PUT") {
                if (payload.expected_revision !== state.revision) status = 409;
                else {
                    if (!state.initialized || !state.settings_initialized || Object.keys(payload).some(key => key !== "expected_revision" && JSON.stringify(state[key]) !== JSON.stringify(payload[key]))) {
                        state.revision += 1;
                    }
                    for (const key of Object.keys(payload)) if (key !== "expected_revision") state[key] = payload[key];
                    state.initialized = true;
                    state.settings_initialized = true;
                }
            }
            const snapshot = JSON.parse(JSON.stringify(state));
            if (pause === options.method) {
                pause = null;
                await new Promise(resolve => { release = resolve; });
            }
            return {ok: status === 200, status, json: async () => snapshot};
        }
    };
    vm.runInNewContext(script, context);
    await flush();
    return {
        ui, state, calls, model, selections, refreshes, styleChoices, warnings,
        adapter: context.module.exports.createControls(context.document),
        saveStyle(name, revision) { backendChoices.push(name); state.style_catalog_revision = revision; },
        async finishRefresh(index = refreshes.length - 1, {clearSelection = false} = {}) {
            assert.ok(refreshes[index], "native refresh was requested");
            styleChoices.splice(0, styleChoices.length, ...refreshes[index]);
            if (clearSelection) model.styles = [];
            // Gradio response events are synthetic, not user style edits.
            ui.styles.dispatchEvent({type: "change", bubbles: true, isTrusted: false});
            await flush();
        },
        edit(field, value) {
            const node = ui[field];
            if (field === "width" || field === "height") node.number.value = value;
            else if (field === "styles") model.styles = [...value];
            else if (field === "sampler_name") { model.sampler_name = value; node.input.value = value; }
            else node.value = value;
            (node.number || node).dispatchEvent({type: "input", bubbles: true, isTrusted: true});
        },
        advance(ms = 1000) { clock += ms; },
        pause(method) { pause = method; },
        async release() { const finish = release; release = null; finish(); await flush(); },
        async tick() { assert.ok(ticks.length, "loop scheduled another tick"); clock += 1000; ticks.shift()(); await flush(); },
        puts() { return calls.filter(call => call.method === "PUT"); }
    };
}

test("style names retain leading and trailing spaces in both directions", async () => {
    const h = await harness({styles: [" Soft Light "]});
    assert.equal(JSON.stringify(h.state.styles), JSON.stringify([" Soft Light "]));
    h.state.styles = ["Soft Light", " Soft Light "];
    h.state.revision += 1;
    await h.tick();
    assert.equal(JSON.stringify(h.model.styles), JSON.stringify(["Soft Light", " Soft Light "]));
    assert.equal(JSON.stringify(h.adapter.read().styles), JSON.stringify(["Soft Light", " Soft Light "]));
});

test("first bootstrap publishes visible WebUI text; polling does not create update loops", async () => {
    const h = await harness({positive: "粤海地图", negative: "blur"});
    assert.equal(h.state.positive, "粤海地图");
    assert.equal(h.state.negative, "blur");
    assert.equal(h.state.revision, 1);
    await h.tick();
    await h.tick();
    assert.equal(h.puts().length, 1);
});

test("persisted state restores to browser and subsequent bot edits appear", async () => {
    const h = await harness({stored: {positive: "saved", negative: "bad", revision: 4, initialized: true}});
    assert.equal(h.ui.positive.value, "saved");
    h.state.positive = "saved, bot addition";
    h.state.revision += 1;
    await h.tick();
    assert.equal(h.ui.positive.value, "saved, bot addition");
    assert.equal(h.puts().length, 0);
});

test("a delayed poll never overwrites typing and merges the other field", async () => {
    const h = await harness({positive: "base", negative: "old negative"});
    h.state.negative = "new negative from bot";
    h.state.revision += 1;
    h.pause("GET");
    await h.tick();
    h.edit("positive", "human typing");
    h.advance();
    await h.release();
    assert.equal(h.ui.positive.value, "human typing");
    assert.equal(h.state.positive, "human typing");
    assert.equal(h.ui.negative.value, "new negative from bot");
});

test("typing during a write response stays visible and is committed next cycle", async () => {
    const h = await harness({positive: "base"});
    h.edit("positive", "first edit");
    h.pause("PUT");
    await h.tick();
    h.edit("positive", "second edit while sending");
    h.advance();
    await h.release();
    assert.equal(h.ui.positive.value, "second edit while sending");
    await h.tick();
    assert.equal(h.state.positive, "second edit while sending");
});

test("composition holds remote edits until the user's completed text can merge", async () => {
    const h = await harness({positive: "start"});
    h.ui.positive.dispatchEvent({type: "compositionstart"});
    h.ui.positive.value = "丽";
    h.state.positive = "bot change";
    h.state.revision += 1;
    await h.tick();
    assert.equal(h.ui.positive.value, "丽");
    h.ui.positive.value = "丽湖地图";
    h.ui.positive.dispatchEvent({type: "compositionend"});
    await h.tick();
    assert.equal(h.state.positive, "丽湖地图");
});

test("programmatic UI changes without input events still synchronize", async () => {
    const h = await harness({positive: "start"});
    h.ui.positive.value = "changed by WebUI extension";
    await h.tick();
    await h.tick();
    assert.equal(h.state.positive, "changed by WebUI extension");
});

test("migration captures actual settings while restoring previously saved prompts", async () => {
    const h = await harness({positive: "reset by WebUI", stored: {positive: "saved prompt", negative: "saved negative",
        revision: 5, initialized: true, settings_initialized: false, sampler_name: "", styles: [], width: 512, height: 512}});
    assert.equal(h.state.sampler_name, "DPM++ 2M");
    assert.deepEqual(h.state.styles, ["水彩 风格"]);
    assert.equal(h.state.width, 640);
    assert.equal(h.state.height, 768);
    assert.equal(h.state.positive, "saved prompt");
    assert.equal(h.ui.positive.value, "saved prompt");
    assert.equal(h.state.settings_initialized, true);
});

test("bot edits use real dropdown selections and update both slider inputs", async () => {
    const h = await harness({positive: "keep me"});
    Object.assign(h.state, {sampler_name: "Euler a", styles: ["Soft Light", "水彩 风格"], width: 1024, height: 1536});
    h.state.revision += 1;
    await h.tick();
    assert.deepEqual(h.model, {sampler_name: "Euler a", styles: ["Soft Light", "水彩 风格"], width: 1024, height: 1536});
    assert.equal(h.ui.width.number.value, "1024");
    assert.equal(h.ui.width.range.value, "1024");
    assert.deepEqual(h.selections.map(item => item.choice), ["Euler a", "Soft Light", "水彩 风格"]);
    const heartbeat = h.calls.filter(call => call.url.endsWith("/heartbeat")).at(-1).payload;
    assert.deepEqual(heartbeat.styles, ["Soft Light", "水彩 风格"]);
    assert.equal(heartbeat.sampler_name, "Euler a");
    assert.equal(heartbeat.width, 1024);
    assert.equal(heartbeat.height, 1536);
    assert.equal(heartbeat.positive, "keep me");
});

test("style clearing and adding to an empty selection commit the model", async () => {
    const h = await harness();
    h.state.styles = [];
    h.state.revision += 1;
    await h.tick();
    assert.deepEqual(h.model.styles, []);
    h.state.styles = ["Soft Light"];
    h.state.revision += 1;
    await h.tick();
    assert.deepEqual(h.model.styles, ["Soft Light"]);
});

test("a browser width edit merges a concurrent bot sampler and prompt edit", async () => {
    const h = await harness({positive: "base"});
    Object.assign(h.state, {sampler_name: "DDIM", positive: "bot prompt"});
    h.state.revision += 1;
    h.pause("GET");
    await h.tick();
    h.edit("width", 896);
    h.advance();
    await h.release();
    assert.equal(h.state.width, 896);
    assert.equal(h.model.sampler_name, "DDIM");
    assert.equal(h.ui.positive.value, "bot prompt");
    assert.equal(h.state.height, 768);
});

test("editing dropdown search text is not uploaded as a selected value", async () => {
    const h = await harness();
    h.ui.sampler_name.input.value = "not a selection";
    h.ui.sampler_name.input.classList.contains = name => name === "subdued";
    const before = h.calls.length;
    await h.tick();
    assert.equal(h.calls.length, before);
    assert.equal(h.state.sampler_name, "DPM++ 2M");
    assert.equal(h.model.sampler_name, "DPM++ 2M");
});

test("a cosmetic slider change without a model commit is never acknowledged live", async () => {
    const h = await harness();
    h.ui.width.failCommit = true;
    h.state.width = 1024;
    h.state.revision += 1;
    const before = h.calls.filter(call => call.url.endsWith("/heartbeat")).length;
    await h.tick();
    assert.equal(h.ui.width.number.value, "1024");
    assert.equal(h.model.width, 640);
    assert.equal(h.ui.width.range.value, "640");
    assert.equal(h.calls.filter(call => call.url.endsWith("/heartbeat")).length, before);
});

test("new catalog waits for the asynchronous native callback before selecting a saved style", async () => {
    const h = await harness({positive: "keep prompt", negative: "keep negative", catalogRevision: "catalog-1"});
    assert.equal(h.refreshes.length, 0, "already current initial choices need no refresh");
    const initialHeartbeat = h.calls.filter(call => call.url.endsWith("/heartbeat")).at(-1).payload;
    assert.equal(initialHeartbeat.style_catalog_revision, "catalog-1");
    const puts = h.puts().length;
    const heartbeats = h.calls.filter(call => call.url.endsWith("/heartbeat")).length;
    h.saveStyle("新建 style", "catalog-2");
    h.state.styles = ["新建 style"];
    h.state.revision += 1;
    await h.tick();
    assert.equal(h.refreshes.length, 1, "native refresh button invokes callback once");
    await h.tick();
    assert.equal(h.refreshes.length, 1, "a slow callback is not repeatedly clicked");
    assert.deepEqual(h.model.styles, ["水彩 风格"], "new style not selected before model choices arrive");
    assert.equal(h.calls.filter(call => call.url.endsWith("/heartbeat")).length, heartbeats, "no live ack while catalog is stale");
    await h.finishRefresh(0, {clearSelection: true});
    await h.tick();
    assert.deepEqual(h.model.styles, ["新建 style"], "new style selected through actual option handler");
    assert.equal(h.ui.positive.value, "keep prompt");
    assert.equal(h.ui.negative.value, "keep negative");
    assert.equal(h.model.sampler_name, "DPM++ 2M");
    assert.equal(h.model.width, 640);
    assert.equal(h.model.height, 768);
    assert.equal(h.puts().length, puts, "choices callback is not mistaken for a user edit");
    assert.equal(h.calls.filter(call => call.url.endsWith("/heartbeat")).at(-1).payload.style_catalog_revision, "catalog-2");
});

test("catalog callback preserves the user's latest style, prompt and size edits", async () => {
    const h = await harness({positive: "base", negative: "base negative", catalogRevision: "catalog-1"});
    h.saveStyle("保存的新样式", "catalog-2");
    await h.tick();
    h.edit("positive", "typed while refreshing");
    h.edit("width", 896);
    h.edit("styles", ["Soft Light"]);
    await flush();
    h.state.negative = "bot negative";
    h.state.sampler_name = "DDIM";
    h.state.revision += 1;
    await h.finishRefresh(0, {clearSelection: true});
    await h.tick();
    assert.equal(h.ui.positive.value, "typed while refreshing");
    assert.equal(h.state.positive, "typed while refreshing");
    assert.equal(h.ui.negative.value, "bot negative");
    assert.equal(h.model.width, 896);
    assert.equal(h.model.sampler_name, "DDIM");
    assert.deepEqual(h.model.styles, ["Soft Light"]);
    assert.deepEqual(h.state.styles, ["Soft Light"], "late callback does not erase user selection");
});

test("refreshing only choices preserves the existing ordered multi-selection", async () => {
    const h = await harness({styles: ["Soft Light", "水彩 风格"], catalogRevision: "catalog-1"});
    h.saveStyle("Another", "catalog-2");
    await h.tick();
    await h.finishRefresh(0, {clearSelection: true});
    await h.tick();
    assert.deepEqual(h.model.styles, ["Soft Light", "水彩 风格"]);
    assert.deepEqual(h.state.styles, ["Soft Light", "水彩 风格"]);
});

test("typing a style search during refresh is preserved and never mistaken for a selection", async () => {
    const h = await harness({catalogRevision: "catalog-1"});
    const heartbeats = h.calls.filter(call => call.url.endsWith("/heartbeat")).length;
    h.saveStyle("A saved style", "catalog-2");
    await h.tick();
    h.ui.styles.input.value = "unfinished search";
    h.ui.styles.input.dispatchEvent({type: "input", bubbles: true, isTrusted: true});
    await flush();
    await h.finishRefresh(0);
    await h.tick();
    assert.equal(h.ui.styles.input.value, "unfinished search", "catalog probing never clears active search text");
    assert.deepEqual(h.state.styles, ["水彩 风格"]);
    assert.equal(h.calls.filter(call => call.url.endsWith("/heartbeat")).length, heartbeats);
    // Gradio clears search text on blur; the trusted focusout observes that
    // committed state after the component's own handler has completed.
    h.ui.styles.input.value = "";
    h.ui.styles.dispatchEvent({type: "focusout", bubbles: true, isTrusted: true});
    await flush();
    await h.tick();
    assert.deepEqual(h.model.styles, ["水彩 风格"]);
    assert.equal(h.calls.filter(call => call.url.endsWith("/heartbeat")).at(-1).payload.style_catalog_revision, "catalog-2");
});

test("a second save during refresh waits for the first callback before requesting another", async () => {
    const h = await harness({catalogRevision: "catalog-1"});
    h.saveStyle("First new style", "catalog-2");
    await h.tick();
    h.saveStyle("Second new style", "catalog-3");
    h.state.styles = ["Second new style"];
    h.state.revision += 1;
    await h.tick();
    assert.equal(h.refreshes.length, 1, "original callback is still pending");
    await h.finishRefresh(0);
    await h.tick();
    assert.equal(h.refreshes.length, 2, "finished stale callback triggers one follow-up refresh");
    await h.finishRefresh(1);
    await h.tick();
    assert.deepEqual(h.model.styles, ["Second new style"]);
    assert.equal(h.calls.filter(call => call.url.endsWith("/heartbeat")).at(-1).payload.style_catalog_revision, "catalog-3");
});

test("missing native refresh control reports an actionable error and never claims live", async () => {
    const h = await harness({catalogRevision: "catalog-1", refreshButton: false});
    const heartbeats = h.calls.filter(call => call.url.endsWith("/heartbeat")).length;
    h.saveStyle("Unavailable until reload", "catalog-2");
    await h.tick();
    assert.ok(h.warnings.some(warning => warning.includes("#refresh_txt2img_styles")));
    assert.equal(h.calls.filter(call => call.url.endsWith("/heartbeat")).length, heartbeats);
    assert.deepEqual(h.model.styles, ["水彩 风格"]);
});

test("a stalled native refresh times out clearly and does not loop clicks or acknowledge", async () => {
    const h = await harness({catalogRevision: "catalog-1"});
    const heartbeats = h.calls.filter(call => call.url.endsWith("/heartbeat")).length;
    h.saveStyle("Delayed style", "catalog-2");
    await h.tick();
    for (let i = 0; i < 12; i++) await h.tick();
    assert.equal(h.refreshes.length, 1);
    assert.ok(h.warnings.some(warning => warning.includes("刷新超时")));
    assert.equal(h.calls.filter(call => call.url.endsWith("/heartbeat")).length, heartbeats);
    await h.finishRefresh(0);
    await h.tick();
    assert.equal(h.calls.filter(call => call.url.endsWith("/heartbeat")).at(-1).payload.style_catalog_revision, "catalog-2",
        "a late genuine model update can recover safely");
});

test("a control reverted by the page never pushes the old size back over the bot's value", async () => {
    // 用户报的场景：机器人把尺寸改成 832×1216 之后，Gradio 把宽高控件自己渲染回旧值（没有受信任的
    // 用户事件）。旧版把这种漂移当成本地编辑，于是 960×1440 又被推回桥接，机器人下一次读就把用户的
    // 尺寸顶回去——"改了尺寸一生成又变回原尺寸"。
    const h = await harness({stored: {positive: "", negative: "", revision: 3, initialized: true,
        settings_initialized: true, sampler_name: "Euler a", styles: [], width: 960, height: 1440}});
    await h.tick();
    h.state.width = 832;
    h.state.height = 1216;
    h.state.revision += 1;
    await h.tick();
    assert.equal(h.ui.width.number.value, "832");
    h.ui.width.number.value = "960";
    h.ui.width.range.value = "1440";
    h.ui.height.number.value = "1440";
    h.ui.height.range.value = "1440";
    const puts = h.puts().length;
    await h.tick();      // 漂移这一拍先被 200ms 防抖挡下（与真实页面一致）
    await h.tick();
    assert.equal(h.state.width, 832, "the bot's width must survive a cosmetic control revert");
    assert.equal(h.state.height, 1216, "the bot's height must survive a cosmetic control revert");
    assert.equal(h.puts().length, puts, "no PUT while the user has not touched anything");
    assert.equal(h.ui.width.number.value, "832", "the page adopts the bot's value again");
});

test("a real width edit still publishes both dimensions", async () => {
    const h = await harness({stored: {positive: "", negative: "", revision: 3, initialized: true,
        settings_initialized: true, sampler_name: "Euler a", styles: [], width: 960, height: 1440}});
    await h.tick();
    h.edit("width", 1024);
    await h.tick();
    assert.equal(h.state.width, 1024, "user width edit is published");
    assert.equal(h.state.height, 1440, "the untouched height travels with it unchanged");
});

test("legacy bridge without catalog revision neither needs a refresh button nor probes catalog", async () => {
    const h = await harness({refreshButton: false});
    await h.tick();
    assert.equal(h.calls.some(call => call.url === "/sdapi/v1/prompt-styles"), false);
    assert.ok(h.calls.some(call => call.url.endsWith("/heartbeat")));
    assert.equal(h.warnings.length, 0);
});
