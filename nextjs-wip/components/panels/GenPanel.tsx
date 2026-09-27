"use client";

/**
 * 出图栏（`/gen`）：生成 / 任务队列 / 生成参数 / 智能改写 / 参数预设 / 回溯 / 图片墙。
 *
 * 静态骨架逐块照搬 `webui/index.html` 的 `<!--#panel:gen-->`，id/class 原样保留；
 * 这一轮把 `webui/app.js` 里出图栏相关的逻辑接上，每段都有中文注释标出对应的函数/行号：
 * - 生成参数：`generationPayload`(1033) / `applyGeneration`(1046) / `applyGenerationSummary`(1063)
 *   —— 没有「应用」按钮，改完立刻 `POST /api/generation`，返回的就是新状态（`updateStatus` 就地更新）；
 * - 下拉选项：`loadOptions`(495) / `fillSelect`(504)（保留当前值，目录里没有就补一条「…（当前）」）；
 * - 任务队列：`updateQueueSummary`(1152) / `taskKind`(1161) / `loadTasks`(1172) / `taskAction`(1211)；
 * - SD 进度：`loadProgress`(1077) / `renderProgress`(1087) / `startProgressPolling`(1116) / `stopProgressPolling`(1123)；
 * - 图片墙：`loadImages`(1130)；
 * - 参数预设：`editPresets`(976) / `loadPresets`(990) / `renderPresets`(994)；
 * - 指令通道（`bind()` 1516–1558）：`.gen N` / `.gen status` / `.get` / `.rg N` / `.infix …` / `.preset load …`，
 *   回执进 `#gen-receipts`（`<Receipts>`），并登记 `tasks` / `images` / `progress-start` 刷新器。
 *
 * 与 Java 版的结构性差别：Java 一个页面有 10 个面板块，所以它的 `loadXxx()` 都要先 `$('id')` 探一下
 * 元素在不在；Next 版每个栏目一个页面，面板里的元素必定存在，探元素那一步就省掉了，行为不变。
 */
import { useEffect, useRef, useState, type ChangeEvent, type KeyboardEvent as ReactKeyboardEvent } from "react";
import { apiRequest, errorText, Unauthorized } from "../client/api";
import { Receipts, useCapture } from "../client/capture";
import { useConsole, usePoll } from "../client/store";
import type { StatusJson } from "../client/types";
import { ImageNode } from "../client/viewer";

// ── 类型（字段名与服务端 `lib/core/generation.ts` / `lib/core/web-console.ts` 一致）────────

/** 生成参数面板里的 8 个字段（值统一按字符串存，与 DOM 的 `node.value` 同形）。 */
type GenForm = {
  width: string;
  height: string;
  sampler: string;
  model: string;
  steps: string;
  cfg: string;
  seed: string;
  imageCount: string;
};

/** `app.js` GEN_FIELDS（L14）：字段 → `/api/generation` 的键名。 */
const GEN_FIELDS: [keyof GenForm, string][] = [
  ["width", "width"],
  ["height", "height"],
  ["sampler", "sampler"],
  ["model", "model"],
  ["steps", "steps"],
  ["cfg", "cfg"],
  ["seed", "seed"],
  ["imageCount", "imageCount"],
];

const EMPTY_FORM: GenForm = {
  width: "",
  height: "",
  sampler: "",
  model: "",
  steps: "",
  cfg: "",
  seed: "",
  imageCount: "",
};

/** `app.js` OPTION_LABELS（L1477）：提示语里别把 `autoGet` 这种键名弹给用户看。 */
const OPTION_LABELS: Record<string, string> = { infixFilter: "词库约束" };

type GenerationInfo = NonNullable<StatusJson["generation"]>;
/** `/api/generation` 返回整份 status + `changed` / `message`（路由层合并，见 route.ts L290）。 */
type GenerationResponse = StatusJson & { changed?: string[]; message?: string };
type OptionsPayload = { samplers?: string[]; models?: string[]; functions?: string[]; error?: string };

/** `/api/tasks` 直接返回数组（`tasksJson()`），次数都是 BigInt 的字符串形式。 */
type TaskItem = {
  slot?: number;
  number: string;
  done: string;
  total: string;
  images: string;
  failed: string;
  running?: boolean;
  suspended?: boolean;
  cancelled?: boolean;
  percent?: number;
  status?: string;
};

/** 参数预设：新格式字段是平铺的，`parameters` 是旧格式（`renderPresets` L1001）。 */
type PresetJson = {
  width?: number | string;
  height?: number | string;
  sampler_name?: string;
  sampler?: string;
  steps?: number | string;
  cfg_scale?: number | string;
  seed?: number | string;
  checkpoint?: string;
  parameters?: PresetJson;
};
type PresetItem = { name?: string; preset?: PresetJson };
type PresetsPayload = { presets?: PresetItem[]; current?: Record<string, unknown>; message?: string };

type ImageItem = { path: string; name: string; size: number; modified?: string };

/** `/api/progress`（`progressJson()`）：进度来自 SD，`queue` 是机器人自己的队列状态文本。 */
type ProgressPayload = {
  reachable?: boolean;
  running?: boolean;
  percent?: number;
  step?: number;
  steps?: number;
  etaSeconds?: number;
  job?: string;
  text?: string;
  queue?: string;
};

// ── 纯函数小工具（与 app.js 的同名逻辑一一对应）────────────────────────────────────

/** Java/JS 的字符串拼接：`undefined` / `null` 也按字面量接上去（`app.js` 的 `+` 就是这样）。 */
function jtext(value: unknown): string {
  return String(value);
}

/** 面板输入框的值：`gen.width ?? ''`（loadStatus L428）。 */
function fieldText(value: unknown): string {
  return value === null || value === undefined ? "" : String(value);
}

/** `app.js updateQueueSummary`(1152)：只看队列状态的第一行。 */
function firstLine(text: unknown): string {
  return String(text ?? "")
    .split("\n")[0]
    .trim();
}

/**
 * 队列里是不是还有活。
 *
 * Java 用 `state.status.generation.status` 的真值判断（而 `generationSummary()` 的 `status` 就是
 * 整段队列文字、恒为真，等于「一直轮询」）；这里按队列文字里的「运行中 / 等待 N 个 / 合计 N 个」判断，
 * 空闲就停，进度条该收起来的时候能收起来。
 */
function isQueueActive(queue: unknown): boolean {
  const line = firstLine(queue);
  if (/运行中/.test(line)) return true;
  const waiting = /等待\s*(\d+)\s*个/.exec(line);
  const total = /合计\s*(\d+)\s*个/.exec(line);
  return Number(waiting?.[1] ?? 0) > 0 || Number(total?.[1] ?? 0) > 0;
}

/** `app.js fillSelect`(504)：选项列表 + 实际选中的值（当前值不在目录里就补一条「…（当前）」）。 */
function selectField(values: string[] | undefined, current: string): { items: { value: string; label: string }[]; value: string } {
  const list = values && values.length ? values : current ? [current] : [];
  const items = list.map((value) => ({ value, label: value }));
  if (current && !list.includes(current)) items.push({ value: current, label: current + "（当前）" });
  const value = current && items.some((item) => item.value === current) ? current : items[0]?.value ?? "";
  return { items, value };
}

/** `app.js generationPayload`(1033)：空着的字段不发，表示这次不动它。 */
function generationPayload(form: GenForm): Record<string, string | number> {
  const payload: Record<string, string | number> = {};
  for (const [field, key] of GEN_FIELDS) {
    const value = String(form[field] ?? "").trim();
    if (value === "") continue;
    payload[key] = key === "sampler" || key === "model" ? value : Number(value);
  }
  return payload;
}

/** 面板值 ← `/api/status`（`app.js loadStatus` L427–433：`gen.width ?? ''` 那些）。 */
function formFromStatus(status: StatusJson | null): GenForm {
  const gen = status?.generation;
  return {
    width: fieldText(gen?.width),
    height: fieldText(gen?.height),
    sampler: fieldText(gen?.sampler),
    model: fieldText(gen?.model),
    steps: fieldText(gen?.steps),
    cfg: fieldText(gen?.cfg),
    seed: fieldText(gen?.seed),
    imageCount: fieldText(status?.imageCount),
  };
}

/** `app.js taskKind`(1161)：任务状态 → 任务卡的配色 class。 */
function taskKind(task: TaskItem): string {
  const status = String(task.status ?? "");
  if (status.includes("取消")) return "cancelled";
  if (status.includes("挂起") || task.suspended) return "held";
  if (status.includes("生成中")) return "running";
  if (status.includes("失败") || Number(task.failed)) return "failed";
  if (status.includes("完成")) return "done";
  return "waiting";
}

/** `app.js renderProgress`(1087)：把一份 /api/progress 数据变成进度条的显隐 / 宽度 / 文字。 */
function progressView(data: ProgressPayload | null, busy: boolean): { hidden: boolean; done: boolean; width: string; text: string } {
  if (data === null) return { hidden: true, done: false, width: "0%", text: "" };
  if (data.reachable === false) {
    // SD 没在跑 / 地址不对，和「读到了但空闲」不是一回事：单独说清楚。
    return { hidden: false, done: false, width: "0%", text: data.text || "读不到 SD 进度（SD WebUI 未启动？）" };
  }
  if (!data.running && !busy) return { hidden: true, done: false, width: "0%", text: "" };
  const percent = Number(data.percent ?? 0);
  return {
    hidden: false,
    done: !data.running && busy,
    width: (data.running ? Math.max(3, Math.min(100, percent)) : busy ? 100 : 0) + "%",
    text: data.running ? data.text ?? "" : "SD 还没开始（排在队列里等）",
  };
}

/** `app.js renderPresets`(994)：预设的 `preset` 可能还是旧的 `parameters` 格式。 */
function presetBody(preset: PresetJson): { body: PresetJson; sampler: string } {
  const body = preset.parameters ?? preset;
  return { body, sampler: preset.sampler_name || preset.sampler || body.sampler_name || "" };
}

// ── 面板 ──────────────────────────────────────────────────────────────────────────

export function GenPanel() {
  const { unlocked, status, toast, showBanner, refresh, updateStatus, askDialog, showInfo } = useConsole();
  const { runCommands, register } = useCapture();

  // 本面板的数据（对应 app.js 的 state.status / state.options；null = 还没读过，与 Java 的空 DOM 同形）
  const [form, setForm] = useState<GenForm>(EMPTY_FORM);
  const [options, setOptions] = useState<OptionsPayload | null>(null);
  const [tasks, setTasks] = useState<TaskItem[] | null>(null);
  const [presets, setPresets] = useState<PresetItem[] | null>(null);
  const [images, setImages] = useState<ImageItem[] | null>(null);
  const [queueText, setQueueText] = useState("");
  const [progress, setProgress] = useState<ProgressPayload | null>(null);
  const [progressOn, setProgressOn] = useState(false);
  const [genCount, setGenCount] = useState("1");
  const [rgCount, setRgCount] = useState("4");
  const [presetName, setPresetName] = useState("");
  const [infixInput, setInfixInput] = useState("");

  // 只给回调读的「最新值」引用（避免 usePoll / register 里抓到旧闭包）
  const unlockedRef = useRef(unlocked);
  unlockedRef.current = unlocked;
  const statusRef = useRef(status);
  statusRef.current = status;
  const formRef = useRef(form);
  formRef.current = form;
  const optionsRef = useRef(options);
  optionsRef.current = options;
  const tasksRef = useRef(tasks);
  tasksRef.current = tasks;
  /** `app.js` 的 `appliedGeneration`（L16）：同一份值不重复发。 */
  const appliedRef = useRef("");
  /** `applyGeneration` 失败后强制回到机器人里真正在用的值（Java：`await loadStatus()`）。 */
  const forceSyncRef = useRef(false);
  /** `loadTasks` 的「本来就在底部就跟着滚」（L1177），在改 DOM 之前先判断。 */
  const scrollAfterRef = useRef(false);

  const gen: GenerationInfo | undefined = status?.generation;
  const samplerField = selectField(options?.samplers, form.sampler || gen?.sampler || "");
  const modelField = selectField(options?.models, form.model || gen?.model || "");
  const queueLine = firstLine(queueText);
  /** `app.js updateQueueSummary`(1157)：徽章只看「运行中」。 */
  const queueBusy = /运行中/.test(queueLine);
  const queueActive = isQueueActive(queueText);
  const taskCount = tasks?.length ?? 0;
  const autoGet = Boolean(status?.autoGet);
  const infixFilter = Boolean(status?.infixFilter);
  const view = progressView(progress, queueBusy);

  /**
   * `app.js applyGenerationSummary`(1063)：`#gen-applied` 那一行。
   * 注意 filter(Boolean) 的语义：只有 `gen.sampler` / `gen.model` 是原值（可能被丢掉），
   * 其余都是拼好的字符串（恒为真），所以这里用 jtext 逐段拼。
   */
  const appliedText = status
    ? "当前生效：" +
      [
        jtext(gen?.width) + " × " + jtext(gen?.height) + " 像素",
        gen?.sampler,
        "步数 " + jtext(gen?.steps),
        "CFG " + jtext(gen?.cfg),
        "种子 " + jtext(gen?.seed),
        "图片上限 " + jtext(status.imageCount ?? "-") + " 张/条",
        gen?.model ? "模型 " + gen.model : null,
      ]
        .filter(Boolean)
        .join(" · ")
    : "当前生效参数会显示在这里。";

  // ── 生成参数（改完即生效）─────────────────────────────────────────────────────────

  /** 面板当前值（含下拉的实际选中项，等价于 Java 直接读 DOM 的 `node.value`）。 */
  function payloadFor(next: GenForm): Record<string, string | number> {
    const sampler = selectField(optionsRef.current?.samplers, next.sampler || statusRef.current?.generation?.sampler || "").value;
    const model = selectField(optionsRef.current?.models, next.model || statusRef.current?.generation?.model || "").value;
    return generationPayload({ ...next, sampler, model });
  }

  /** `app.js applyGeneration`(1046)：把面板里的值立刻发回机器人生效。 */
  async function applyGeneration(quiet = false, override?: Partial<GenForm>): Promise<GenerationResponse | null> {
    if (!unlockedRef.current) return null;
    const payload = payloadFor({ ...formRef.current, ...override });
    const signature = JSON.stringify(payload);
    if (Object.keys(payload).length === 0 || signature === appliedRef.current) return null;
    try {
      const next = await apiRequest<GenerationResponse>("/api/generation", { method: "POST", body: payload });
      appliedRef.current = signature;
      // 接口返回的就是新状态 → 就地更新（顶栏、`#gen-applied`、队列摘要都跟着走）。
      updateStatus(next);
      if (!quiet && next.message) toast(next.message);
      return next;
    } catch (error) {
      if (!(error instanceof Unauthorized)) toast("没能生效：" + errorText(error));
      forceSyncRef.current = true; // 回到机器人里真正在用的值
      await refresh();
      return null;
    }
  }

  /** 数字输入框的提交时机与 Java 的 `change` 一致：失焦或回车（React 的 onChange 等价 input，每敲一下都发）。 */
  function commitOnEnter(event: ReactKeyboardEvent<HTMLInputElement>): void {
    if (event.key !== "Enter") return;
    event.preventDefault();
    void applyGeneration();
  }

  /** 数字输入框的 onChange：只改面板上的值，提交交给失焦 / 回车。 */
  function changeField(field: keyof GenForm) {
    return (event: ChangeEvent<HTMLInputElement>): void => {
      const value = event.target.value;
      setForm((current) => ({ ...current, [field]: value }));
    };
  }

  /** `app.js loadOptions`(495)：/api/options 填两个下拉，保留当前值。 */
  async function loadOptions(): Promise<void> {
    const data = await apiRequest<OptionsPayload>("/api/options");
    setOptions(data);
    optionsRef.current = data;
    // Java：填完下拉后把面板当前值记为「已应用」，免得同步回来的值又被提交一次（L500）。
    // 基线直接按 /api/status 算：此时 React 可能还没把状态回填到 formRef，按状态算才稳定。
    appliedRef.current = JSON.stringify(payloadFor(formFromStatus(statusRef.current)));
  }

  // ── 下拉 / 开关 ─────────────────────────────────────────────────────────────────

  /** `app.js setOption`(1482)：改本机开关，然后整份刷新状态（Java 的 applyStatus → loadStatus）。 */
  async function setOption(key: string, value: string): Promise<void> {
    if (!unlockedRef.current) return;
    try {
      // /api/settings 的返回**没有** generation 段，所以不能拿它 updateStatus，要重读 /api/status。
      await apiRequest<StatusJson>("/api/settings", {
        method: "POST",
        body: { key, value, scope: statusRef.current?.scope },
      });
      toast("已保存：" + (OPTION_LABELS[key] || key));
      await refresh();
    } catch (error) {
      if (!(error instanceof Unauthorized)) toast(errorText(error));
    }
  }

  // ── SD 生成进度（app.js 1077–1128）──────────────────────────────────────────────

  /** `app.js loadProgress`(1077)：进度条数据来自 SD，队列状态来自机器人。 */
  async function loadProgress(): Promise<void> {
    let data: ProgressPayload | null = null;
    try {
      data = await apiRequest<ProgressPayload>("/api/progress");
    } catch {
      /* 读不到进度不影响其它功能（app.js 同样吞掉） */
    }
    if (data === null) return;
    setProgress(data);
    const queue = String(data.queue ?? "").trim();
    setQueueText(queue); // updateQueueSummary(queue)
    if (isQueueActive(queue)) void loadTasksRef.current(); // 队列有任务时顺带刷新任务队列
    // Java stopProgressPolling()：没有任务就停。手上还有任务卡时不停，免得刚好撞上一次旧响应就断掉轮询。
    else if ((tasksRef.current?.length ?? 0) === 0) setProgressOn(false);
  }

  // ── 图片与任务队列（app.js 1130–1219）──────────────────────────────────────────

  /** `app.js loadImages`(1130)：待领取图片网格（倒序，最新的在前）。 */
  async function loadImages(): Promise<void> {
    const data = await apiRequest<{ images?: ImageItem[] }>("/api/images", { method: "POST", body: { limit: 60 } });
    setImages((data.images ?? []).slice().reverse());
  }

  /** `app.js loadTasks`(1172)：任务队列的徽章 + 进度条 + 图片/失败统计。 */
  async function loadTasks(): Promise<void> {
    let list: TaskItem[];
    try {
      list = await apiRequest<TaskItem[]>("/api/tasks");
    } catch {
      return; // 刷新失败就用手上这份数据继续渲染（app.js 直接 return，不清空列表）
    }
    scrollAfterRef.current = window.scrollY + window.innerHeight > document.body.scrollHeight - 80;
    setTasks(Array.isArray(list) ? list : []);
  }

  /** `app.js taskAction`(1211)：置顶 / 挂起 / 继续 / 取消。 */
  async function taskAction(action: string, number: string): Promise<void> {
    if (!unlockedRef.current) return;
    try {
      const result = await apiRequest<{ message?: string; tasks?: TaskItem[] }>("/api/tasks/action", {
        method: "POST",
        body: { action, number },
      });
      toast(String(result.message ?? "").split("\n")[0]);
      await loadTasksRef.current();
      await loadImagesRef.current().catch(() => {});
      await refresh(); // 队列摘要与徽章跟着动作走（Java: loadStatus）
    } catch (error) {
      if (!(error instanceof Unauthorized)) toast(errorText(error));
    }
  }

  // ── 参数预设（app.js 976–1028）─────────────────────────────────────────────────

  /** `app.js renderPresets`(994)：预设列表（名称 + 尺寸/采样/步数/CFG/模型）。 */
  function renderPresets(data: PresetsPayload | null): PresetsPayload | null {
    setPresets(data?.presets ?? []);
    return data;
  }

  /** `app.js loadPresets`(990)。 */
  async function loadPresets(): Promise<PresetsPayload | null> {
    return renderPresets(await apiRequest<PresetsPayload>("/api/presets"));
  }

  /** `app.js editPresets`(976)：保存 / 覆盖 / 删除走 /api/presets/edit（「加载」会改 SD 参数，仍走指令）。 */
  async function editPresets(action: string, name: string): Promise<PresetsPayload | null> {
    if (!unlockedRef.current) return null;
    if (!String(name || "").trim()) {
      toast("请填写预设名称。");
      return null;
    }
    try {
      const data = await apiRequest<PresetsPayload>("/api/presets/edit", {
        method: "POST",
        body: { action, name: String(name).trim() },
      });
      renderPresets(data);
      if (data.message) toast(data.message);
      return data;
    } catch (error) {
      if (!(error instanceof Unauthorized)) toast(errorText(error));
      await loadPresets().catch(() => {});
      return null;
    }
  }

  /** `renderPresets`(1012)：「加载」走指令通道（名称直接跟在指令后面，不加引号）。 */
  async function loadPreset(name: string): Promise<void> {
    if (!unlockedRef.current) return;
    await runCommands([".preset load " + name]);
    await refresh(); // Java: `.then(loadStatus)`
  }

  /** `renderPresets`(1021)：删除先确认。 */
  async function removePreset(name: string): Promise<void> {
    const confirmed = await askDialog({
      title: "删除参数预设",
      text: "删除参数预设「" + name + "」？",
      confirmText: "删除",
      danger: true,
    });
    if (!confirmed) return;
    await editPresets("remove", name);
  }

  /** `renderPresets`(1013)：查看原文（信息框，不改数据）。 */
  async function showPreset(item: PresetItem): Promise<void> {
    const preset = item.preset ?? {};
    const { body, sampler } = presetBody(preset);
    await showInfo(
      "参数预设：" + (item.name ?? ""),
      [
        "图片尺寸：" + jtext(preset.width) + " × " + jtext(preset.height) + " 像素",
        "采样方法：" + (sampler || "-"),
        "步数：" + jtext(body.steps ?? "-"),
        "CFG：" + jtext(body.cfg_scale ?? "-"),
        "种子：" + jtext(body.seed ?? "-"),
        "基础模型：" + (body.checkpoint || "跟随 WebUI 当前模型"),
      ].join("\n"),
      { text: "加载后只影响之后提交的生成任务；当前提示词保持不变。" },
    );
  }

  // ── 指令通道（app.js bind() 1516–1558）────────────────────────────────────────

  /** `bind()` 1519：先把手上的参数生效，再下达 `.gen N`（follow：跟到图片回来，并起进度轮询）。 */
  async function submitGenerate(): Promise<void> {
    if (!unlockedRef.current) return;
    await applyGeneration(true);
    void runCommands([".gen " + (genCount || "1")], { follow: true });
  }

  /** `bind()` 1523：`.gen status` 跑完刷任务队列与进度。 */
  async function showQueueStatus(): Promise<void> {
    if (!unlockedRef.current) return;
    await runCommands([".gen status"], { after: () => loadTasksRef.current() });
    await loadProgressRef.current();
  }

  /** `bind()` 1524：`.get` 领取图片（follow 等图片回来）。 */
  function claimImages(): void {
    if (!unlockedRef.current) return;
    void runCommands([".get"], { follow: true });
  }

  /** `bind()` 1528：`.rg N` 回溯最近 N 张（Java 这里没有 follow）。 */
  function recentImages(): void {
    if (!unlockedRef.current) return;
    void runCommands([".rg " + (rgCount || "4")]);
  }

  /** `bind()` 1550：`.infix` 要等 DeepSeek（几秒到几十秒），输入框先清空。 */
  async function submitInfix(): Promise<void> {
    const instruction = infixInput.trim();
    setInfixInput("");
    if (!instruction || !unlockedRef.current) return;
    // Java 跑完还会 loadPrompt() 刷新提示词面板；Next 版每个栏目一个页面，提示词页进入时自己重读。
    await runCommands([".infix " + instruction], { follow: true });
  }

  /** `bind()` 1692：取消队列里的全部任务（先确认）。 */
  async function cancelAllTasks(): Promise<void> {
    if (!unlockedRef.current) return;
    const confirmed = await askDialog({
      title: "取消全部任务",
      text: "取消队列里的全部任务？\n已经生成的图片仍可领取。",
      confirmText: "全部取消",
      danger: true,
    });
    if (!confirmed) return;
    await taskAction("cancel", "all");
  }

  // ── 引用（给 usePoll / register 这类「只登记一次」的回调用最新实现）──────────────
  const loadOptionsRef = useRef(loadOptions);
  loadOptionsRef.current = loadOptions;
  const loadProgressRef = useRef(loadProgress);
  loadProgressRef.current = loadProgress;
  const loadTasksRef = useRef(loadTasks);
  loadTasksRef.current = loadTasks;
  const loadImagesRef = useRef(loadImages);
  loadImagesRef.current = loadImages;
  const loadPresetsRef = useRef(loadPresets);
  loadPresetsRef.current = loadPresets;

  // ── 效果 ────────────────────────────────────────────────────────────────────────

  /**
   * `app.js loadStatus`(L418/L427–442) 里与出图栏有关的那几行：面板值 ← 机器人里的当前生效值。
   *
   * 只有「面板值就是已生效值」时才回填：顶栏状态每 8 秒轮询一次，用户正在输入、
   * 或有一次没生效的值时，不能拿轮询回来的旧状态把他手上的值盖掉。
   */
  useEffect(() => {
    if (!unlocked || !status) return;
    const clean = appliedRef.current === "" || JSON.stringify(payloadFor(formRef.current)) === appliedRef.current;
    if (!clean && !forceSyncRef.current) return;
    forceSyncRef.current = false;
    const next = formFromStatus(status);
    setForm(next);
    appliedRef.current = JSON.stringify(payloadFor(next));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [unlocked, status]);

  /** `app.js updateQueueSummary`(1152) + loadStatus L442：队列摘要、徽章，队列有任务就把进度轮询接上。 */
  useEffect(() => {
    if (!unlocked || !status) return;
    const queue = status.generation?.status ?? "";
    setQueueText(queue);
    if (isQueueActive(queue)) setProgressOn(true);
  }, [unlocked, status]);

  /** `app.js loadPage`(1743) 的 gen 分支：进这一栏时把选项 / 预设 / 图片 / 队列一次拉齐。 */
  useEffect(() => {
    if (!unlocked) return;
    void (async () => {
      try {
        await Promise.all([loadOptionsRef.current(), loadPresetsRef.current(), loadImagesRef.current(), loadTasksRef.current()]);
      } catch (error) {
        if (!(error instanceof Unauthorized)) showBanner("「gen」面板加载失败：" + errorText(error));
      }
    })();
  }, [unlocked, showBanner]);

  // 指令跑完自动刷新（capture 的 register）：出图/领取后刷任务与图片，follow 时开始进度轮询。
  useEffect(() => register("tasks", () => loadTasksRef.current()), [register]);
  useEffect(() => register("images", () => loadImagesRef.current().catch(() => {})), [register]);
  useEffect(
    () =>
      register("progress-start", () => {
        setProgressOn(true); // `app.js startProgressPolling`(1116)：先把进度条露出来，再立刻读一次
        void loadProgressRef.current();
      }),
    [register],
  );

  /** `app.js startProgressPolling`(1116) / `stopProgressPolling`(1123)：每 1.5 秒读一次 SD 进度。 */
  usePoll(() => loadProgressRef.current(), 1500, unlocked && progressOn);
  /** 生成期间的任务队列：`loadProgress` 里「有任务就刷任务队列」那一步（L1083）。 */
  usePoll(() => loadTasksRef.current(), 1500, unlocked && (queueActive || taskCount > 0));

  /** `app.js loadTasks`(1208)：本来就在页面底部时，刷新完跟着滚到底。 */
  useEffect(() => {
    if (!scrollAfterRef.current) return;
    scrollAfterRef.current = false;
    window.scrollTo({ top: document.body.scrollHeight });
  }, [tasks]);

  return (
    <section className="panel active" id="panel-gen">
      <div className="card">
        <div className="card-head"><b>生成</b><span className="muted">当前任务在下面的「任务队列」里看</span></div>
        <div className="row wrap">
          <input
            id="gen-count"
            type="number"
            min="1"
            inputMode="numeric"
            value={genCount}
            onChange={(event) => setGenCount(event.target.value)}
          />
          <button type="button" id="gen-btn" onClick={() => void submitGenerate()}>开始生成</button>
          <button className="ghost" type="button" id="gen-status-btn" onClick={() => void showQueueStatus()}>队列状态</button>
          <button className="ghost" type="button" id="get-btn" onClick={claimImages}>领取图片</button>
        </div>
        <div className={"progress" + (view.done ? " done" : "")} id="gen-progress-box" hidden={view.hidden}>
          <div className="progress-track"><div className="progress-bar" id="gen-progress-bar" style={{ width: view.width }} /></div>
          <div className="muted" id="gen-progress-text">{view.text}</div>
        </div>
      </div>

      <div className="card">
        <div className="card-head"><b>任务队列</b><span className="muted" id="queue-badge">{status ? (queueBusy ? "队列有任务" : "空闲") : null}</span></div>
        <div className="muted" id="task-summary">{status ? queueLine || "队列是空的。" : null}</div>
        <div className="kv" id="task-list">
          {tasks === null ? null : tasks.length === 0 ? (
            <div className="task-empty">队列是空的（去「生成」里点开始生成，或直接说「生成三张」）</div>
          ) : (
            tasks.map((task) => {
              const kind = taskKind(task);
              const percent = Number.isFinite(Number(task.percent))
                ? Number(task.percent)
                : Number(task.total)
                  ? Math.round((100 * Number(task.done)) / Number(task.total))
                  : 0;
              return (
                <div className={"task " + kind} key={task.number}>
                  <div className="task-head">
                    <span className="task-num">#{task.number}</span>
                    <span className="task-badge">{String(task.status ?? "")}</span>
                    <span className="task-count">{task.done}/{task.total} 次</span>
                    {Number(task.images) ? <span className="task-chip">已生成 {task.images} 张</span> : null}
                    {Number(task.failed) ? <span className="task-chip bad">失败 {task.failed} 次</span> : null}
                    {task.suspended ? <span className="task-chip warn">已挂起</span> : null}
                  </div>
                  <div className="task-track">
                    <div className="task-bar" style={{ width: Math.max(0, Math.min(100, percent)) + "%" }} />
                  </div>
                  <div className="task-acts">
                    <button className="ghost" type="button" onClick={() => void taskAction("first", task.number)}>置顶</button>
                    <button className="ghost" type="button" onClick={() => void taskAction(task.suspended ? "resume" : "hold", task.number)}>
                      {task.suspended ? "继续" : "挂起"}
                    </button>
                    <button className="danger" type="button" onClick={() => void taskAction("cancel", task.number)}>取消</button>
                  </div>
                </div>
              );
            })
          )}
        </div>
        <div className="row wrap">
          <button className="ghost" type="button" id="tasks-reload" onClick={() => void loadTasksRef.current()}>刷新队列</button>
          <button className="danger" type="button" id="tasks-cancel-all" onClick={() => void cancelAllTasks()}>取消全部任务</button>
        </div>
        <div className="muted">默认按提交时间顺序生成；「置顶」把某个任务提到第一优先级，下一位就生成它。挂起/取消只影响还没下发的那几次，已经生成的图片照常领取。</div>
      </div>

      <div className="card">
        <div className="card-head"><b>生成参数</b><span className="muted">没有「应用」按钮：改完立刻生效，只影响之后提交的任务</span></div>
        <div className="grid-2">
          <label className="field"><span>宽（8 的倍数）</span>
            <input
              id="set-width"
              type="number"
              min="64"
              max="2048"
              step="8"
              inputMode="numeric"
              value={form.width}
              onChange={changeField("width")}
              onBlur={() => void applyGeneration()}
              onKeyDown={commitOnEnter}
            />
          </label>
          <label className="field"><span>高（8 的倍数）</span>
            <input
              id="set-height"
              type="number"
              min="64"
              max="2048"
              step="8"
              inputMode="numeric"
              value={form.height}
              onChange={changeField("height")}
              onBlur={() => void applyGeneration()}
              onKeyDown={commitOnEnter}
            />
          </label>
          <label className="field"><span>采样方法</span>
            <select
              id="set-sampler"
              value={samplerField.value}
              onChange={(event) => {
                const value = event.target.value;
                setForm((current) => ({ ...current, sampler: value }));
                void applyGeneration(false, { sampler: value });
              }}
            >
              {samplerField.items.map((item) => (
                <option key={item.value} value={item.value}>{item.label}</option>
              ))}
            </select>
          </label>
          <label className="field"><span>基础模型</span>
            <select
              id="set-model"
              value={modelField.value}
              onChange={(event) => {
                const value = event.target.value;
                setForm((current) => ({ ...current, model: value }));
                void applyGeneration(false, { model: value });
              }}
            >
              {modelField.items.map((item) => (
                <option key={item.value} value={item.value}>{item.label}</option>
              ))}
            </select>
          </label>
          <label className="field"><span>步数</span>
            <input
              id="set-steps"
              type="number"
              min="1"
              inputMode="numeric"
              value={form.steps}
              onChange={changeField("steps")}
              onBlur={() => void applyGeneration()}
              onKeyDown={commitOnEnter}
            />
          </label>
          <label className="field"><span>CFG</span>
            <input
              id="set-cfg"
              type="number"
              min="0.1"
              step="0.1"
              inputMode="decimal"
              value={form.cfg}
              onChange={changeField("cfg")}
              onBlur={() => void applyGeneration()}
              onKeyDown={commitOnEnter}
            />
          </label>
          <label className="field"><span>种子（-1 随机）</span>
            <input
              id="set-seed"
              type="number"
              inputMode="numeric"
              value={form.seed}
              onChange={changeField("seed")}
              onBlur={() => void applyGeneration()}
              onKeyDown={commitOnEnter}
            />
          </label>
          <label className="field"><span>图片上限/条</span>
            <input
              id="set-imgcnt"
              type="number"
              min="1"
              inputMode="numeric"
              value={form.imageCount}
              onChange={changeField("imageCount")}
              onBlur={() => void applyGeneration()}
              onKeyDown={commitOnEnter}
            />
          </label>
        </div>
        {/* /api/status 的 generation（当前生效参数）+ /api/options 的 samplers / models 已接进上面 8 个控件 */}
        <div className="muted" id="gen-applied">{appliedText}</div>
      </div>

      <div className="card">
        <div className="card-head"><b>智能改写 .infix</b><span className="muted">默认自由改写，允许自然语言</span></div>
        <input
          id="infix-input"
          placeholder="修改要求，例如：把地点换成草地，服饰换成军装"
          value={infixInput}
          onChange={(event) => setInfixInput(event.target.value)}
        />
        <div className="row wrap">
          <button type="button" id="infix-btn" onClick={() => void submitInfix()}>改写并应用</button>
          <label className="check">
            <input
              type="checkbox"
              id="infix-filter"
              checked={infixFilter}
              onChange={(event) => void setOption("infixFilter", event.target.checked ? "on" : "off")}
            />{" "}
            标准词库约束
          </label>
        </div>
        <div className="muted">改写的是你个人的正反向提示词（只列出增删变化），不改动 WebUI 页面那一栏；改完直接用于之后的出图。</div>
      </div>

      <div className="card">
        <div className="card-head"><b>参数预设 preset</b></div>
        <div className="row wrap">
          <label className="check">
            <input
              type="checkbox"
              id="gen-autoget"
              checked={autoGet}
              onChange={(event) => void setOption("autoGet", event.target.checked ? "on" : "off")}
            />{" "}
            任务完成后自动领取
          </label>
          <button className="ghost" type="button" id="gen-toggle-btn" onClick={() => void setOption("autoGet", autoGet ? "off" : "on")}>切换自动领取</button>
        </div>
        <div className="row wrap">
          <input id="preset-name" placeholder="预设名称" value={presetName} onChange={(event) => setPresetName(event.target.value)} />
          <button type="button" id="preset-save" onClick={() => void editPresets("save", presetName)}>保存当前</button>
          <button className="ghost" type="button" id="preset-overwrite" onClick={() => void editPresets("overwrite", presetName)}>覆盖</button>
        </div>
        <ul className="list" id="preset-list">
          {presets === null ? null : presets.length === 0 ? (
            <li className="muted">还没有保存过参数预设。</li>
          ) : (
            presets.map((item) => {
              const name = item.name ?? "";
              const preset = item.preset ?? {};
              const { body, sampler } = presetBody(preset);
              return (
                <li key={name}>
                  <div className="name">
                    {name}
                    <div className="sub">
                      {[
                        jtext(preset.width) + "×" + jtext(preset.height),
                        sampler,
                        "steps " + jtext(body.steps ?? "-"),
                        "cfg " + jtext(body.cfg_scale ?? "-"),
                        body.checkpoint || "跟随 WebUI",
                      ]
                        .filter(Boolean)
                        .join(" · ")}
                    </div>
                  </div>
                  <div className="acts">
                    <button type="button" onClick={() => void loadPreset(name)}>加载</button>
                    <button className="ghost" type="button" onClick={() => void showPreset(item)}>查看</button>
                    <button className="danger" type="button" onClick={() => void removePreset(name)}>删除</button>
                  </div>
                </li>
              );
            })
          )}
        </ul>
      </div>

      <div className="card">
        <div className="card-head"><b>回溯 recent</b></div>
        <div className="row wrap">
          <input
            id="rg-count"
            type="number"
            min="1"
            inputMode="numeric"
            value={rgCount}
            onChange={(event) => setRgCount(event.target.value)}
          />
          <button className="ghost" type="button" id="rg-btn" onClick={recentImages}>
            回溯最近 N 张
          </button>
        </div>
      </div>

      <div className="grid" id="image-grid">
        {images === null ? null : images.length === 0 ? (
          <div className="muted">暂无待领取图片。</div>
        ) : (
          images.map((image) => (
            // Java `loadImages`(1139–1142)：`a.image-link > img + .meta`，`.meta` 就挂在链接里
            // （`ImageNode` 的 children 会渲染在 `<a>` 内部）。
            <ImageNode
              key={image.path}
              file={image.path}
              caption={image.name + "（" + Math.round(image.size / 1024) + " KB）"}
            >
              <div className="meta">{image.name + "\n" + Math.round(image.size / 1024) + " KB"}</div>
            </ImageNode>
          ))
        )}
      </div>
      <Receipts id="gen-receipts" />
    </section>
  );
}
