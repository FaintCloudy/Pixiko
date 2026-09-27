"use client";

/**
 * 样式栏（`/styles`）：样式＝固定模板、保存当前提示词为样式、批量操作、样式列表。
 *
 * 静态骨架逐块照搬 `webui/index.html` 的 `<!--#panel:styles-->`，id/class 原样保留。
 * 业务逻辑对应 `webui/app.js` 621–762 行（`loadStyles` / `editStyles` / `renderStyles` /
 * `syncRows` / `styleRow` / `inlineRename` / `actionButton`）与 `bind()` 1562–1592 行。
 *
 * 与 Java 版的两处结构性差别（语义等价）：
 * - Java 用 `syncRows` 手工增量复用行 DOM；React 用 `key={item.name}` 复用，效果一样
 *   （同名行原地更新，不会整表重画）；
 * - Java 把行数据放进 `styleItems` 这个 Map 供「查看原文」查；React 直接查 `itemsRef`（同一份缓存）。
 */
import { useCallback, useEffect, useRef, useState } from "react";
import { apiRequest, errorText, Unauthorized } from "../client/api";
import { Receipts, useCapture } from "../client/capture";
import { InlineRename } from "../client/lists";
import { useConsole } from "../client/store";

/** `/api/styles`（`lib/core/web-console.ts webStyles`）与 `/api/styles/edit` 的返回结构。 */
type StyleItem = {
  number?: number;
  name: string;
  source?: string;
  local?: boolean;
  positive?: string;
  negative?: string;
};

type StylesPayload = {
  styles?: StyleItem[];
  library?: number;
  message?: string;
};

/**
 * 一行样式（`app.js styleRow` 682）：编号、名称（可点改名）、载入/改名/查看原文/删除。
 *
 * 「改名」按钮与点名字是同一件事：Java 里两者都调用 `startRename`，而 `InlineRename`
 * 自己管编辑态，所以这里用一层 `display:contents` 的包裹节点拿到 `.editable` 再点它一下。
 * 包裹节点不产生盒子，不会影响 `.name` 里的排版，也不动任何既有 id/class。
 */
function StyleRow({
  item,
  onLoad,
  onRename,
  onShow,
  onDelete,
}: {
  item: StyleItem;
  onLoad: (name: string) => void;
  onRename: (current: string, next: string) => void;
  onShow: (item: StyleItem) => void;
  onDelete: (name: string) => void;
}) {
  const label = useRef<HTMLSpanElement | null>(null);
  const startRename = () => label.current?.querySelector<HTMLElement>(".editable")?.click();

  return (
    <li className="fresh">
      <span className="num">{"#" + (item.number ?? "")}</span>
      <span className="name">
        <span className="tag on">样式</span>
        <span ref={label} style={{ display: "contents" }}>
          <InlineRename value={item.name} prefix=" " onCommit={(next) => onRename(item.name, next)} />
        </span>
        <div className="sub">载入时替换你的个人提示词，之后 prompt 就是你自己的文本</div>
      </span>
      <div className="acts">
        <button type="button" onClick={() => onLoad(item.name)}>载入</button>
        <button
          className="ghost"
          type="button"
          title="重命名这条样式（只改样式库里的名字，已生成的图片不受影响）"
          onClick={startRename}
        >
          改名
        </button>
        <button className="ghost" type="button" onClick={() => onShow(item)}>查看原文</button>
        <button className="danger" type="button" onClick={() => void onDelete(item.name)}>删除</button>
      </div>
    </li>
  );
}

export function StylesPanel() {
  const { unlocked, status, toast, showBanner, askDialog, showInfo } = useConsole();
  const { runCommands } = useCapture();

  const [items, setItems] = useState<StyleItem[]>([]);
  /** `#style-load-nolora` 之外的表单控件都按 Java 的做法"点的时候读 DOM"（不受控，用 ref）。 */
  const [filter, setFilter] = useState("");
  /** 首次成功读到列表前，`#style-loaded` / `#style-pageselected` 与列表都保持空白（同 Java）。 */
  const [loaded, setLoaded] = useState(false);
  const [libraryText, setLibraryText] = useState("");

  const promptName = useRef<HTMLInputElement | null>(null);
  const saveName = useRef<HTMLInputElement | null>(null);
  const range = useRef<HTMLInputElement | null>(null);
  const rangeName = useRef<HTMLInputElement | null>(null);
  const noLora = useRef<HTMLInputElement | null>(null);
  /** 样式数据缓存（`app.js styleItems` 626）：行只负责显示，「查看原文」要的是数据。 */
  const itemsRef = useRef(new Map<string, StyleItem>());
  const scope = status?.scope;

  /** `app.js renderStyles`(649)：两句文案 + 按 `#style-filter` 的前端筛选重建列表。 */
  const renderStyles = useCallback((data: StylesPayload) => {
    setLoaded(true);
    setLibraryText(
      "样式库 " +
        (data.library ?? (data.styles || []).length) +
        " 个（只属于机器人，与 WebUI 的样式互不影响）",
    );
    const all = data.styles || [];
    const cache = itemsRef.current;
    cache.clear();
    for (const item of all) cache.set(item.name, item);
    setItems(all);
  }, []);

  /** `app.js loadStyles`(628)：读整份样式库。刷新失败时保留手上这份列表（不清空、不显示空列表）。 */
  const loadStyles = useCallback(async () => {
    try {
      renderStyles(await apiRequest<StylesPayload>("/api/styles"));
    } catch (error) {
      if (error instanceof Unauthorized) return;
      // 同 `app.js loadPage`(1763)：「styles」面板加载失败：…
      showBanner("「styles」面板加载失败：" + errorText(error));
    }
  }, [renderStyles, showBanner]);

  /**
   * `app.js editStyles`(636)：保存/覆盖/改名/删除/载入都走 `/api/styles/edit`，
   * 返回的就是刷新后的整份列表（直接拿来重渲染）。只有「导入 WebUI 预设样式」走指令通道。
   */
  const editStyles = useCallback(
    async (action: string, name: string | null, extra?: Record<string, unknown>) => {
      try {
        const data = await apiRequest<StylesPayload>("/api/styles/edit", {
          method: "POST",
          body: Object.assign(
            { action, name: name == null ? "" : String(name), scope },
            extra || {},
          ),
        });
        renderStyles(data);
        if (data.message) toast(data.message.split("\n")[0]);
        return data;
      } catch (error) {
        if (!(error instanceof Unauthorized)) toast(errorText(error));
        await loadStyles().catch(() => {});
        return null;
      }
    },
    [loadStyles, renderStyles, scope, toast],
  );

  // 载入即把样式文本写进个人提示词（行内「载入」按钮）。
  const loadStyle = useCallback(
    (name: string) => {
      void editStyles("load", name, { noLora: Boolean(noLora.current?.checked) });
    },
    [editStyles],
  );

  /** `app.js styleRow`(691)：就地改名提交。 */
  const renameStyle = useCallback(
    (current: string, next: string) => {
      void editStyles("rename", current, { newName: next });
    },
    [editStyles],
  );

  /** `app.js styleRow`(706)：查看原文 —— 数据取自 `styleItems`（这里用 itemsRef），拿不到才退回这一行。 */
  const showStyleText = useCallback(
    (item: StyleItem) => {
      const data = itemsRef.current.get(item.name) || item;
      void showInfo(
        "样式原文：" + item.name,
        "正向：\n" + (data.positive || "（空）") + "\n\n反向：\n" + (data.negative || "（空）"),
        { text: "这是一份固定模板：载入会把这两段原样写进你个人的提示词。" },
      );
    },
    [showInfo],
  );

  /** `app.js styleRow`(713)：确认后删除单条样式，成功了后台再对齐一次编号。 */
  const deleteStyle = useCallback(
    async (name: string) => {
      if (
        !(await askDialog({
          title: "删除样式",
          text: "删除样式「" + name + "」？\n样式库里的这一条会被移除，已生成的图片不受影响。",
          confirmText: "删除",
          danger: true,
        }))
      )
        return;
      const data = await editStyles("delete", name);
      if (!data) return;
      toast("样式已删除：" + name);
      void loadStyles().catch(() => {});
    },
    [askDialog, editStyles, loadStyles, toast],
  );

  // 进栏目先读一次（`app.js loadPage`(1745)）；只有登录后才发请求。
  useEffect(() => {
    if (!unlocked) return;
    void loadStyles();
  }, [unlocked, loadStyles]);

  /** `app.js renderStyles`(652)：筛选词按 `value.trim().toLowerCase()` 用，输入框里保留原文。 */
  const needle = filter.trim().toLowerCase();
  const visible = items.filter((item) => !needle || item.name.toLowerCase().includes(needle));

  // app.js bind() 1562–1592
  return (
    <section className="panel active" id="panel-styles">
      <div className="card">
        <div className="card-head">
          <b>样式 = 固定模板</b>
          <span className="muted" id="style-loaded">
            {loaded ? "载入即替换，之后 prompt 由你自己改" : ""}
          </span>
        </div>
        <div className="muted" id="style-pageselected">{libraryText}</div>
        <div className="row wrap">
          <input id="style-prompt-name" placeholder="样式名称或 #编号" ref={promptName} />
          <button
            className="ghost"
            type="button"
            id="style-prompt-btn"
            onClick={() => {
              // app.js 1563：不带名称就是 `.style list`，带名称就是 `.style prompt <名称>`（走指令通道）。
              const name = (promptName.current?.value ?? "").trim();
              void runCommands([name ? ".style prompt " + name : ".style list"]);
            }}
          >
            查看样式原文
          </button>
        </div>
        <div className="muted">不记录「上一次载入哪个样式」：载入即把样式文本写进你的个人提示词，之后 prompt 就是你自己的内容，不会被样式自动覆盖。</div>
      </div>

      <div className="card">
        <div className="card-head"><b>保存当前提示词为样式</b><span className="muted">只属于机器人，保存在 data/local-styles.json</span></div>
        <div className="row wrap">
          <input id="style-save-name" placeholder="新样式名称" ref={saveName} />
          {/* app.js 1567 / 1571：保存与覆盖同名样式 */}
          <button
            type="button"
            id="style-save"
            onClick={() => {
              const name = (saveName.current?.value ?? "").trim();
              if (name) void editStyles("save", name);
            }}
          >
            保存样式
          </button>
          <button
            className="ghost"
            type="button"
            id="style-overwrite"
            onClick={() => {
              const name = (saveName.current?.value ?? "").trim();
              if (name) void editStyles("overwrite", name);
            }}
          >
            覆盖同名样式
          </button>
          {/* app.js 1575：这是一次性搬家，Java 版就是走指令通道的（`.style import webui`）。 */}
          <button
            className="ghost"
            type="button"
            id="style-import"
            onClick={() => {
              void (async () => {
                const confirmed = await askDialog({
                  title: "导入 WebUI 样式",
                  text: "把 WebUI 里已有的预设样式一次性搬进机器人样式库？\n同名默认跳过，不会覆盖机器人已有的样式。",
                  confirmText: "导入",
                });
                if (!confirmed) return;
                await runCommands([".style import webui"]).then(() => loadStyles());
              })();
            }}
          >
            导入 WebUI 预设样式
          </button>
          <label className="check"><input type="checkbox" id="style-load-nolora" ref={noLora} /> 载入时不加载 LoRA</label>
        </div>
        <div className="muted">样式完全独立在机器人这边：载入时替换你的个人提示词，不需要 WebUI 在线，也不会改动 WebUI。「导入 WebUI 预设样式」是一次性搬家，之后机器人不再读 WebUI 的样式列表。</div>
      </div>

      <div className="card">
        <div className="card-head"><b>批量操作</b><span className="muted">支持 #12-#16 这类区间；样式只属于机器人</span></div>
        <div className="row wrap">
          <input id="style-range" placeholder="#12-#16 或 #12,#15" ref={range} />
          <input id="style-range-name" placeholder="改名时的新名称/前缀" ref={rangeName} />
          {/* app.js 1581：区间/编号展开由后端 `styleTargets` 负责，这里原样把 range 当 name 传。 */}
          <button
            type="button"
            id="style-batch-rename"
            onClick={() => {
              const value = (range.current?.value ?? "").trim();
              const name = (rangeName.current?.value ?? "").trim();
              if (value && name) void editStyles("rename", value, { newName: name });
            }}
          >
            批量改名
          </button>
          {/* app.js 1585：批量删除要确认 */}
          <button
            className="danger"
            type="button"
            id="style-batch-delete"
            onClick={() => {
              void (async () => {
                const value = (range.current?.value ?? "").trim();
                if (!value) return;
                const confirmed = await askDialog({
                  title: "批量删除样式",
                  text: "删除 " + value + "？",
                  confirmText: "删除",
                  danger: true,
                });
                if (!confirmed) return;
                void editStyles("delete", value);
              })();
            }}
          >
            批量删除
          </button>
        </div>
      </div>

      <div className="row wrap">
        {/* app.js 1591：Java 版在输入时重读接口再前端筛选；这里直接对已读到的列表做前端筛选
            （name.toLowerCase().includes），少一次请求，结果一致。 */}
        <input
          id="style-filter"
          placeholder="筛选样式"
          value={filter}
          onChange={(event) => setFilter(event.target.value)}
        />
        <button className="ghost" type="button" id="style-reload" onClick={() => void loadStyles()}>刷新列表</button>
      </div>
      <ul className="list" id="style-list">
        {/* app.js syncRows(656)：按名称复用（key=name），空列表提示「没有匹配的样式。」 */}
        {visible.map((item) => (
          <StyleRow
            key={item.name}
            item={item}
            onLoad={loadStyle}
            onRename={renameStyle}
            onShow={showStyleText}
            onDelete={deleteStyle}
          />
        ))}
        {loaded && visible.length === 0 ? <li className="muted">没有匹配的样式。</li> : null}
      </ul>
      <Receipts id="style-receipts" />
    </section>
  );
}
