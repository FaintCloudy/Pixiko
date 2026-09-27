"use client";

/**
 * 提示词栏（`/prompt`）：正/反向提示词、整段保存回退、提示词生成、词库分类浏览。
 *
 * 静态骨架逐块照搬 `webui/index.html` 的 `<!--#panel:prompt-->`，id/class 原样保留。
 *
 * 逻辑逐段对应 `webui/app.js`：
 * - `loadPrompt`(524) / `renderPrompt`(529)：进页面拉一次 `/api/prompt`，拿一份数据刷整块面板；
 * - `editPrompt`(550)：增 / 删 / 整段替换 / 清空 / 回退统一走 `/api/prompt/edit`
 *   （本机 JSON，不拼 `.prompt` 指令、不轮询回执）；
 * - `chips`(567) + `fillMeanings`(594)：两排词条 chip 由公共件 `Terms` 实现
 *   （词库查不到的释义自动问一次 `/api/meanings`，回来就地补上，不整块重画）；
 * - `#progen-btn`（app.js 1559）：走指令通道 `.progen …`（只展示不应用），回执落在 `#prompt-receipts`；
 * - `loadUsage`(1463)：`#usage-btn` 与编号 chip → `/api/usage`。
 *
 * 输入框一律交给 `ref` 读当前值（等价于 app.js 的 `$('…').value`）：整段文本、添加词条、
 * `.progen` 描述、词库查询都是"用时再读"，改接口返回后也像 app.js 那样直接写回 `textarea.value`。
 */
import { Fragment, useCallback, useEffect, useRef, useState } from "react";
import { apiRequest, errorText, Unauthorized } from "../client/api";
import { Receipts, useCapture } from "../client/capture";
import { Terms, type Term } from "../client/lists";
import { useConsole } from "../client/store";

/** 一条词条（`lib/core/prompts.ts` 的 `TermItem`：编号 + 原文 + 词库里的中文释义）。 */
type PromptItem = { number?: number; term?: string; meaning?: string };

/** `/api/prompt` 与 `/api/prompt/edit` 的返回（服务端 `webPrompt`，两份结构完全一样）。 */
type PromptData = {
  scope?: string;
  positive?: string;
  negative?: string;
  positiveTerms?: string[];
  negativeTerms?: string[];
  positiveItems?: PromptItem[];
  negativeItems?: PromptItem[];
  canUndo?: number;
  /** 只有 `/api/prompt/edit` 会带：一句人话回执（`toast` 用）。 */
  message?: string;
};

/** `/api/usage` 的返回（服务端 `webUsage`）。 */
type UsageData = { text?: string; choices?: string[]; query?: string };

/**
 * 是不是"令牌不对"（401）：调用方据此回登录框（app.js `api()` L185 在 401 时 `lock()`）。
 *
 * `apiRequest` 把 401 和 503 都抛成 `Unauthorized`，但 app.js 只对 401 调 `lock()`——
 * 503（服务端没配令牌）在那边只是一条普通错误文案，所以这里按 `status` 区分。
 */
function isTokenFailure(error: unknown): boolean {
  return error instanceof Unauthorized && error.status === 401;
}

/**
 * `renderPrompt` 里的 `prompt.positiveItems || prompt.positiveTerms`：
 * 带释义的 items 优先（存在就用，哪怕是空数组），整个缺失时才退回纯词条数组。
 */
function toTerms(items: PromptItem[] | undefined, terms: string[] | undefined): Term[] {
  if (items !== undefined) {
    return items.map((item, index) => ({
      number: item.number ?? index + 1,
      term: item.term ?? "",
      meaning: item.meaning ?? "",
    }));
  }
  return (terms ?? []).map((term, index) => ({ number: index + 1, term, meaning: "" }));
}

export function PromptPanel() {
  const { unlocked, status, toast, showBanner, askDialog, lock } = useConsole();
  const { runCommands } = useCapture();

  // `renderPrompt` 填的三处 muted 文案（`#prompt-scope` / `#prompt-meta` / `#undo-depth`）
  const [scopeText, setScopeText] = useState("");
  const [metaText, setMetaText] = useState("");
  const [undoText, setUndoText] = useState("");
  // 两排词条 chip（`positiveItems` / `negativeItems`）
  const [positiveItems, setPositiveItems] = useState<Term[]>([]);
  const [negativeItems, setNegativeItems] = useState<Term[]>([]);
  // `#usage-body`（等宽 pre 的文本）与 `#usage-choices`（可点的编号）
  const [usageBody, setUsageBody] = useState("");
  const [usageChoices, setUsageChoices] = useState<string[]>([]);

  const positiveRef = useRef<HTMLTextAreaElement>(null);
  const negativeRef = useRef<HTMLTextAreaElement>(null);
  const addRef = useRef<HTMLInputElement>(null);
  const negativeAddRef = useRef<HTMLInputElement>(null);
  const progenRef = useRef<HTMLTextAreaElement>(null);
  const usageRef = useRef<HTMLInputElement>(null);

  // app.js 的 `scope()`（L313）= `state.status?.scope`。存进 ref：顶栏 8 秒轮一次状态，
  // 读 ref 就不会因为 status 变化把回调重建、把面板重新拉一遍。
  const scopeRef = useRef<string | undefined>(status?.scope);
  scopeRef.current = status?.scope;

  /** app.js `renderPrompt`(529)：用一份 `/api/prompt` 数据刷新面板（读接口和改接口返回同一份结构）。 */
  const renderPrompt = useCallback((prompt: PromptData) => {
    setScopeText("归属 " + (prompt.scope ?? ""));
    if (positiveRef.current) positiveRef.current.value = prompt.positive ?? "";
    if (negativeRef.current) negativeRef.current.value = prompt.negative ?? "";
    // 点词条即移除：按**词条原文**删（不拼 `.prompt remove`，也不轮询回执）。
    setPositiveItems(toTerms(prompt.positiveItems, prompt.positiveTerms));
    setNegativeItems(toTerms(prompt.negativeItems, prompt.negativeTerms));
    // 词库里没有的释义由 `Terms` 自己去问一次 DeepSeek（对应 `fillMeanings`，拿不到不打扰用户）。
    setMetaText("样式只作模板：载入即替换，之后 prompt 就是你自己的文本");
    setUndoText("可回退 " + (prompt.canUndo ?? 0) + " 步");
  }, []);

  /** `GET /api/prompt` 的实际请求（app.js 里带 body 所以走 POST，与 `api()` 的判定一致）。 */
  const fetchPrompt = useCallback(async (): Promise<PromptData> => {
    const data = await apiRequest<PromptData>("/api/prompt", {
      method: "POST",
      body: { scope: scopeRef.current },
    });
    renderPrompt(data);
    return data;
  }, [renderPrompt]);

  /** app.js `loadPrompt`(524) + `loadPage` 的 catch(1763)：失败时打「prompt」面板横幅。 */
  const loadPrompt = useCallback(async (): Promise<PromptData | null> => {
    try {
      return await fetchPrompt();
    } catch (error) {
      if (isTokenFailure(error)) {
        lock("令牌不正确，请重新输入。");
        return null;
      }
      showBanner("「prompt」面板加载失败：" + errorText(error));
      return null;
    }
  }, [fetchPrompt, lock, showBanner]);

  /** 进页面拉一次（app.js `loadPage` L1744：`PAGE === 'prompt'` 才拉）；未登录不发请求。 */
  useEffect(() => {
    if (!unlocked) return;
    void loadPrompt();
  }, [unlocked, loadPrompt]);

  /**
   * app.js `editPrompt`(550)：改接口返回刷新后的整份面板数据，一次往返就够，
   * 不用再 loadPrompt()，也没有回执要轮询；失败时把人话提示打到 toast，再用手上这份数据兜底刷新。
   */
  const editPrompt = useCallback(
    async (side: "positive" | "negative", action: string, value?: string): Promise<PromptData | null> => {
      try {
        const data = await apiRequest<PromptData>("/api/prompt/edit", {
          method: "POST",
          // app.js：`value: value == null ? '' : String(value)`
          body: { side, action, value: value == null ? "" : String(value), scope: scopeRef.current },
        });
        renderPrompt(data);
        if (data.message) toast(data.message);
        return data;
      } catch (error) {
        if (isTokenFailure(error)) {
          lock("令牌不正确，请重新输入。");
          return null;
        }
        toast(errorText(error));
        // app.js 是 `await loadPrompt().catch(() => {})`：这里同样静默重拉，不覆盖刚弹的 toast。
        await fetchPrompt().catch(() => null);
        return null;
      }
    },
    [fetchPrompt, lock, renderPrompt, toast],
  );

  /** app.js 1531：添加（读完就把输入框清空）。 */
  const addPositive = () => {
    const value = addRef.current?.value.trim() ?? "";
    if (value) void editPrompt("positive", "add", value);
    if (addRef.current) addRef.current.value = "";
  };

  /** app.js 1532：按词条移除（输入框保留，方便改一改再删）。 */
  const removePositive = () => {
    const value = addRef.current?.value.trim() ?? "";
    if (value) void editPrompt("positive", "remove", value);
  };

  /** app.js 1537 / 1538：反向的同两个按钮。 */
  const addNegative = () => {
    const value = negativeAddRef.current?.value.trim() ?? "";
    if (value) void editPrompt("negative", "add", value);
    if (negativeAddRef.current) negativeAddRef.current.value = "";
  };

  const removeNegative = () => {
    const value = negativeAddRef.current?.value.trim() ?? "";
    if (value) void editPrompt("negative", "remove", value);
  };

  /** app.js 1533 / 1539：清空要确认（同风格弹窗，danger）。 */
  const clearSide = async (side: "positive" | "negative") => {
    const label = side === "negative" ? "反向" : "正向";
    const confirmed = await askDialog({
      title: "清空" + label + "提示词",
      text: "清空" + label + "提示词？",
      confirmText: "清空",
      danger: true,
    });
    if (confirmed) void editPrompt(side, "clear");
  };

  /** app.js 1543：保存两段——先正向再反向，两次 set 都读**当下**输入框里的整段文本。 */
  const saveBoth = async () => {
    await editPrompt("positive", "set", positiveRef.current?.value ?? "");
    await editPrompt("negative", "set", negativeRef.current?.value ?? "");
  };

  /** app.js 1547：回退一步（`side` 只是接口的必填位，回退对正反向一起生效）。 */
  const undo = () => void editPrompt("positive", "undo");

  /** app.js 1559：`.progen` 走指令通道，只展示不应用；回执由 `<Receipts>` 显示。 */
  const progen = () => {
    const value = progenRef.current?.value.trim() ?? "";
    if (value) void runCommands([".progen " + value]);
  };

  /** app.js `loadUsage`(1463)：`text` 填 `#usage-body`，`choices` 变成可点的 `#编号`。 */
  const loadUsage = useCallback(
    async (query: string) => {
      try {
        const data = await apiRequest<UsageData>("/api/usage", { method: "POST", body: { query: query || "" } });
        setUsageBody(data.text ?? "");
        setUsageChoices(Array.isArray(data.choices) ? data.choices : []);
      } catch (error) {
        // app.js 这里没有 try：失败就是一条未处理的 rejection。Next 版统一给一句 toast。
        if (isTokenFailure(error)) {
          lock("令牌不正确，请重新输入。");
          return;
        }
        toast(errorText(error));
      }
    },
    [lock, toast],
  );

  /** app.js 1470：`tag:` 开头的是分类项，点它相当于「搜索 分类名」。 */
  const usageChoice = (choice: string) => {
    void loadUsage(choice.startsWith("tag:") ? "搜索 " + choice.slice(4).split(" — ")[0] : choice);
  };

  return (
    <section className="panel active" id="panel-prompt">
      <div className="card">
        <div className="card-head"><b>正向提示词</b><span className="muted" id="prompt-scope">{scopeText}</span></div>
        <textarea id="prompt-positive" rows={5} ref={positiveRef} />
        <Terms id="prompt-positive-terms" list={positiveItems} onRemove={(_number, term) => void editPrompt("positive", "remove", term)} />
        <div className="row wrap">
          <input id="prompt-add" placeholder="添加词条，逗号分隔" ref={addRef} />
          <button type="button" id="prompt-add-btn" onClick={addPositive}>添加</button>
          <button className="ghost" type="button" id="prompt-remove-btn" onClick={removePositive}>按词条移除</button>
          <button className="danger" type="button" id="prompt-clear-btn" onClick={() => void clearSide("positive")}>清空正向</button>
        </div>
      </div>

      <div className="card">
        <div className="card-head"><b>反向提示词</b></div>
        <textarea id="prompt-negative" rows={4} ref={negativeRef} />
        <Terms id="prompt-negative-terms" list={negativeItems} onRemove={(_number, term) => void editPrompt("negative", "remove", term)} />
        <div className="row wrap">
          <input id="prompt-r-add" placeholder="添加反向词条" ref={negativeAddRef} />
          <button type="button" id="prompt-r-add-btn" onClick={addNegative}>添加</button>
          <button className="ghost" type="button" id="prompt-r-remove-btn" onClick={removeNegative}>按词条移除</button>
          <button className="danger" type="button" id="prompt-r-clear-btn" onClick={() => void clearSide("negative")}>清空反向</button>
        </div>
      </div>

      <div className="card">
        <div className="card-head"><b>整段保存 / 回退</b><span className="muted" id="prompt-meta">{metaText}</span></div>
        <div className="row wrap">
          <button type="button" id="prompt-save-btn" onClick={() => void saveBoth()}>保存两段</button>
          <button className="ghost" type="button" id="undo-btn" onClick={undo}>回退一步</button>
          <span className="muted" id="undo-depth">{undoText}</span>
        </div>
      </div>

      <div className="card">
        <div className="card-head"><b>提示词生成 .progen</b></div>
        <textarea id="progen-input" rows={2} placeholder="文字描述，例如：雨夜的城市街道，一辆红色自行车" ref={progenRef} />
        <div className="row wrap"><button type="button" id="progen-btn" onClick={progen}>生成提示词（只展示不应用）</button></div>
      </div>

      <div className="card">
        <div className="card-head"><b>提示词分类 usage</b><span className="muted">内置中文词库 37,000+ 条</span></div>
        <div className="row wrap">
          <input id="usage-query" placeholder="服饰/上衣、词库 场景、搜索 地铁（留空看顶层）" ref={usageRef} />
          <button type="button" id="usage-btn" onClick={() => void loadUsage(usageRef.current?.value.trim() ?? "")}>浏览</button>
        </div>
        <pre className="usage" id="usage-body">{usageBody}</pre>
        <div className="terms" id="usage-choices">
          {usageChoices.map((choice, index) => (
            <Fragment key={index + ":" + choice}>
              <button className="ghost" type="button" onClick={() => usageChoice(choice)}>#{index + 1}</button>{" "}
            </Fragment>
          ))}
        </div>
      </div>

      {/* 指令回执（`.progen` 等）：Java 版按「当前哪个 .panel 是 active」决定写进哪个盒子，
          这里由 PanelPage 把本栏目的盒子 id 交给 CaptureProvider，等价。 */}
      <Receipts id="prompt-receipts" />
    </section>
  );
}
