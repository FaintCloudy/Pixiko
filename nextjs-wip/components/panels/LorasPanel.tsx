"use client";

/**
 * LoRA 栏（`/loras`）：Civitai 搜索（结果直接渲染在这张卡片里，不发消息）+ 本机 LoRA。
 *
 * 静态骨架逐块照搬 `webui/index.html` 的 `<!--#panel:loras-->`，id/class 原样保留。
 *
 * 已接（对照 `webui/app.js`）：
 * - `loadLoras`(766) / `renderLoras`(799) / `loraRow`(813)：`GET /api/loras` 的本机列表；
 *   「载入 / 改名 / 删除」全部走指令通道（`.lora load #编号`、`.lora rename "旧" "新"`、
 *   `.lora delete #编号`），删除前弹同风格确认框；刷新失败时保留手上这份列表，不显示空列表；
 * - `renderCivitai`(860) + `coverUrl`(921)：`POST /api/civitai/search {query}` 的结构化结果渲染成
 *   `.civitai-card`（封面 = `/api/civitai/thumb?token=…&url=…` 代理，点图进查看器），
 *   「下载」发 `.lora download #编号 权重`，编号就是这次搜索结果的编号；
 * - `#lora-status-btn` → `.lora status`；`#lora-auto-get` → `POST /api/settings {key:"autoGet"}`；
 * - `#lora-receipts` ← `<Receipts>`（`/api/capture` 的文本与图片回执）。
 *
 * 说明：Civitai 封面**不能**用公共件 `<ImageNode>` —— 它会把非 `data:` 的 `file` 交给
 * `imageUrl()` 重拼成 `/api/image?path=…`，而封面是 `/api/civitai/thumb?…` 代理地址（不是本机
 * 文件），重拼后必然取不到图。下面的 `CoverImage` 照抄 `app.js imageNode`(70) 的 DOM 与点击行为，
 * 只是 src 直接用封面代理地址，点开仍然是同一个查看器。
 */
import { useCallback, useEffect, useRef, useState } from "react";
import { apiRequest, errorText, storedToken, Unauthorized } from "../client/api";
import { Receipts, useCapture } from "../client/capture";
import { InlineRename } from "../client/lists";
import { useConsole } from "../client/store";
import type { StatusJson } from "../client/types";
import { useViewer } from "../client/viewer";

/** `/api/loras` 每项（`lib/core/lora.ts` 的 `lorasJson`）：编号 / 名称 / 别名。 */
type LoraItem = { number?: number; name: string; alias?: string | null };

/** `/api/loras` 的整份返回（`app.js loadLoras` 读的就是这几个字段）。 */
type LoraPayload = {
  loras?: LoraItem[];
  count?: number;
  directory?: string;
  downloadStatus?: string;
  /** SD 没在跑时给的失败原因（有值时 `loras` 是空的，界面要说清原因）。 */
  error?: string;
  status?: string;
};

/** `/api/civitai/search` 每项（`lib/core/lora.ts` 的 `civitaiSearchJson`）。 */
type CivitaiItem = {
  number?: number;
  name?: string;
  baseModel?: string;
  url?: string;
  cover?: string;
  downloads?: number;
  sizeKb?: number;
  nsfw?: boolean;
  trainedWords?: string[];
};

type CivitaiPayload = { query?: string; results?: CivitaiItem[]; count?: number };

/**
 * 封面代取地址（`app.js coverUrl`(921)）：`<img src>` 只能带查询串，所以 token 也放在 query 上。
 * 顺序与 Java 一致（token 在前）。
 */
function coverUrl(url: string): string {
  return "/api/civitai/thumb?token=" + encodeURIComponent(storedToken()) + "&url=" + encodeURIComponent(url);
}

/** Civitai 封面：等价于 `app.js imageNode(coverUrl(item.cover), item.name, 'civitai-cover')`(879)。 */
function CoverImage({ url, caption }: { url: string; caption: string }) {
  const { open } = useViewer();
  return (
    <a
      className="image-link"
      href={url}
      title={(caption ? caption + " · " : "") + "点击放大（Esc 关闭）"}
      onClick={(event) => {
        // Ctrl / Shift / 中键仍然交给浏览器新标签打开
        if (event.metaKey || event.ctrlKey || event.shiftKey) return;
        event.preventDefault();
        open(url, caption);
      }}
    >
      <img className="civitai-cover" loading="lazy" src={url} alt={caption || "图片"} />
    </a>
  );
}

/**
 * 一张 Civitai 结果卡（`app.js renderCivitai`(860) 里的 `items.forEach` 那一段）。
 *
 * 权重输入框在 Java 里是每张卡各自 `document.createElement('input')`（初值 `1`、`step 0.05`），
 * 这里用卡片自己的 state 表示同一个东西；「下载」发 `.lora download #编号 权重`。
 */
function CivitaiCard({
  item,
  index,
  unlocked,
  onDownload,
}: {
  item: CivitaiItem;
  index: number;
  unlocked: boolean;
  onDownload: (number: number, weight: string) => void;
}) {
  const [weight, setWeight] = useState("1");
  const number = item.number || index + 1;
  const words = item.trainedWords ?? [];
  // 训练词有的模型会给整段示例 prompt：卡片上只显示前两条、每条截断，完整内容鼠标悬停可见。
  const shown = words.slice(0, 2).map((word) => (word.length > 80 ? word.slice(0, 80) + "…" : word));
  const more = words.length > 2 ? "（等 " + words.length + " 条）" : "";
  const facts = [
    item.baseModel ? "基础模型：" + item.baseModel : null,
    item.downloads ? "下载 " + item.downloads.toLocaleString("zh-CN") + " 次" : null,
    item.sizeKb ? "约 " + (item.sizeKb / 1024).toFixed(1) + " MB" : null,
  ].filter((fact): fact is string => Boolean(fact));

  return (
    <div className="civitai-card">
      <div className="civitai-thumb">
        {item.cover ? (
          <CoverImage url={coverUrl(item.cover)} caption={item.name || ""} />
        ) : (
          <div className="civitai-nocover">暂无封面</div>
        )}
      </div>
      <div className="civitai-meta">
        <div className="civitai-name">
          <b>#{number}</b>
          {" " + (item.name || "未命名")}
          {item.nsfw ? <span className="civitai-badge warn">NSFW</span> : null}
        </div>
        {facts.length ? <div className="civitai-facts">{facts.join(" · ")}</div> : null}
        {words.length ? (
          <div className="civitai-words" title={words.join("\n")}>
            {"训练词：" + shown.join("、") + more}
          </div>
        ) : null}
        <div className="civitai-acts">
          <input
            type="number"
            step="0.05"
            value={weight}
            title="下载时写入 prompt 的权重"
            disabled={!unlocked}
            onChange={(event) => setWeight(event.target.value)}
          />
          <button type="button" disabled={!unlocked} onClick={() => onDownload(number, weight)}>下载</button>
          {item.url ? (
            <a className="civitai-link" href={item.url} target="_blank" rel="noreferrer">打开模型页</a>
          ) : null}
        </div>
      </div>
    </div>
  );
}

/**
 * 本机 LoRA 的一行（`app.js loraRow`(813)）：`#编号` + 名称（点一下改名）+ 别名 + 三个按钮。
 *
 * Java 的「改名」按钮直接调 `inlineRename(...)` 打开输入框；公共件 `InlineRename` 只在点标签时
 * 进入编辑，所以这里点到那一行的 `.editable` 标签上，行为等价（`.editable` 就是改名标签本身）。
 */
function LoraRow({
  item,
  unlocked,
  onLoad,
  onRename,
  onRenameCancel,
  onDelete,
}: {
  item: LoraItem;
  unlocked: boolean;
  onLoad: (item: LoraItem) => void;
  onRename: (item: LoraItem, next: string) => void;
  onRenameCancel: () => void;
  onDelete: (item: LoraItem) => void;
}) {
  const row = useRef<HTMLLIElement | null>(null);
  const startRename = () => {
    row.current?.querySelector<HTMLElement>(".editable")?.click();
  };

  return (
    <li className="fresh" ref={row}>
      <span className="num">{"#" + (item.number ?? "")}</span>
      <span className="name">
        <InlineRename value={item.name} onCommit={(next) => onRename(item, next)} onCancel={onRenameCancel} />
        {item.alias ? <div className="sub">{"别名：" + item.alias}</div> : null}
      </span>
      <div className="acts">
        <button type="button" disabled={!unlocked} onClick={() => onLoad(item)}>加载</button>
        <button
          className="ghost"
          type="button"
          disabled={!unlocked}
          title="重命名本地 LoRA 文件（.safetensors），并同步你个人 prompt 里的 LoRA 标签"
          onClick={startRename}
        >
          改名
        </button>
        <button className="danger" type="button" disabled={!unlocked} onClick={() => onDelete(item)}>删除</button>
      </div>
    </li>
  );
}

export function LorasPanel() {
  const { unlocked, status, toast, askDialog, updateStatus } = useConsole();
  const { runCommands } = useCapture();

  /** 本机列表：`null` 表示这份页面还没读到过（显示「正在读取…」）；读到过就一直留着。 */
  const [items, setItems] = useState<LoraItem[] | null>(null);
  const [loadError, setLoadError] = useState("");
  const [filter, setFilter] = useState("");
  /** `#lora-status` / `#lora-count` 两格文字（Java 是直接改 DOM，这里用状态）。 */
  const [statusText, setStatusText] = useState("");
  const [countText, setCountText] = useState("");

  /** Civitai 搜索：`query` 输入、`results` 结果、`loading`/`empty` 两个占位文案（`renderCivitai` 的 options）。 */
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<CivitaiItem[]>([]);
  const [searching, setSearching] = useState("");
  const [emptyText, setEmptyText] = useState("");

  /** `app.js` 的 `scope()`(313)：`/api/civitai/search`、`/api/settings` 都带它；没有就不发这个字段（服务端用自己的默认值）。 */
  const scope = status?.scope;

  /**
   * `app.js loadLoras`(766)：先用手上这份列表渲染（state 里的 `items` 一直在，刷新失败也不清空），
   * 只有真正读到新的列表才整体替换。
   */
  const loadLoras = useCallback(async () => {
    try {
      const data = await apiRequest<LoraPayload>("/api/loras");
      if (data.error) {
        // SD 没在跑：说清楚原因，别显示成"本机没有 LoRA 文件"。
        setLoadError("读取本机 LoRA 失败：" + data.error);
        setStatusText(data.status || "读取失败");
        setCountText("");
        return;
      }
      const next = data.loras ?? [];
      setItems(next);
      setLoadError("");
      setStatusText(data.status || "本机 " + next.length + " 个 LoRA");
      setCountText("共 " + next.length + " 个" + (data.directory ? "（" + data.directory + "）" : ""));
    } catch (error) {
      // 令牌不对：外壳自己会切回登录框，这里保持手上的列表不动（`if (unauthorized) return`）。
      if (error instanceof Unauthorized) return;
      setLoadError("读取本机 LoRA 失败：" + errorText(error));
      setStatusText("读取失败");
    }
  }, []);

  /** 进页面就拉一次本机列表（`loadPage`(1739) 里 `PAGE === 'loras'` 那两行）；只有解锁后才发请求。 */
  useEffect(() => {
    if (!unlocked) return;
    void loadLoras();
  }, [unlocked, loadLoras]);

  /** `app.js loraRow` 的「加载」：`.lora load #编号`，成功后 toast（Java 也是不看出参就 toast）。 */
  const loadItem = useCallback(
    (item: LoraItem) => {
      void runCommands([".lora load #" + (item.number ?? "")]).then(() => toast("已加载 LoRA：" + item.name));
    },
    [runCommands, toast],
  );

  /** `app.js loraRow` 的改名：`.lora rename "旧" "新"`（磁盘上的 .safetensors 会被重命名）。 */
  const renameItem = useCallback(
    (item: LoraItem, next: string) => {
      void runCommands(['.lora rename "' + item.name + '" "' + next + '"']).then(() => {
        toast("LoRA 已改名：" + item.name + " → " + next);
        void loadLoras();
      });
    },
    [loadLoras, runCommands, toast],
  );

  /** `app.js loraRow` 的删除：先确认（文件真的会被删掉），再 `.lora delete #编号`，然后后台对齐编号。 */
  const deleteItem = useCallback(
    async (item: LoraItem) => {
      const confirmed = await askDialog({
        title: "删除 LoRA",
        text: "从磁盘删除 LoRA「" + item.name + "」？\n文件会被真的删掉，无法撤销。",
        confirmText: "删除",
        danger: true,
      });
      if (confirmed !== true) return;
      const capture = await runCommands([".lora delete #" + (item.number ?? "")]);
      if (!capture) return;
      // 先把那一行收掉，再重新读一次列表把编号对齐（`app.js` 是 row.remove() + loadLoras）。
      setItems((current) => (current === null ? current : current.filter((entry) => entry.name !== item.name)));
      toast("已删除 LoRA：" + item.name);
      void loadLoras();
    },
    [askDialog, loadLoras, runCommands, toast],
  );

  /**
   * `app.js` 的 `#lora-query-btn`(1595)：搜索结果直接渲染在这张卡片里，
   * **不发消息、不走回执**；失败时把 `搜索失败：…` 填进结果区的占位。
   */
  const searchCivitai = useCallback(async () => {
    const words = query.trim();
    if (!words) return;
    setSearching("正在搜索 Civitai：" + words + "…");
    try {
      const data = await apiRequest<CivitaiPayload>("/api/civitai/search", {
        method: "POST",
        // `scope()` 拿不到时 Java 的 JSON.stringify 会把 undefined 丢掉、由服务端用自己的默认值
        body: scope === undefined ? { query: words } : { query: words, scope },
      });
      setResults(data.results ?? []);
      setEmptyText("没有找到匹配的 LoRA。");
      setStatusText("「" + (data.query || words) + "」找到 " + (data.count || 0) + " 项（下载用卡片上的权重 + 下载）");
    } catch (error) {
      setResults([]);
      setEmptyText("搜索失败：" + errorText(error));
    } finally {
      setSearching("");
    }
  }, [query, scope]);

  /** 卡片上的「下载」：`.lora download #编号 权重`（编号是这次搜索结果的编号）。 */
  const downloadItem = useCallback(
    (number: number, weight: string) => {
      void runCommands([".lora download #" + number + " " + weight]).then(() => loadLoras());
    },
    [loadLoras, runCommands],
  );

  /** `#lora-status-btn`(1607)：`.lora status` 之后再刷新一次本机列表。 */
  const refreshStatus = useCallback(() => {
    void runCommands([".lora status"]).then(() => loadLoras());
  }, [loadLoras, runCommands]);

  /**
   * `#lora-auto-get`(1608) → `setOption('autoGet', …)`(1482)：`POST /api/settings` 就地更新状态。
   * 提示语与 Java 一致：`app.js` 的 `OPTION_LABELS`(1477) 里没有 `autoGet`，所以落到键名本身。
   */
  const setAutoGet = useCallback(
    async (on: boolean) => {
      try {
        const body =
          scope === undefined
            ? { key: "autoGet", value: on ? "on" : "off" }
            : { key: "autoGet", value: on ? "on" : "off", scope };
        const next = await apiRequest<StatusJson>("/api/settings", { method: "POST", body });
        updateStatus(next);
        toast("已保存：autoGet");
      } catch (error) {
        if (!(error instanceof Unauthorized)) toast(errorText(error));
      }
    },
    [scope, toast, updateStatus],
  );

  // `app.js renderLoras`(799)：筛选词同时匹配名称与别名；`filter` 是 trim + 小写后的样子（提示语里也用它）。
  const trimmed = filter.trim().toLowerCase();
  const all = items ?? [];
  const matched = all.filter(
    (item) =>
      !trimmed || item.name.toLowerCase().includes(trimmed) || String(item.alias || "").toLowerCase().includes(trimmed),
  );
  const listEmpty = all.length
    ? "没有匹配「" + trimmed + "」的 LoRA（本机共 " + all.length + " 个）。"
    : "本机 LoRA 目录里还没有 .safetensors 文件；用上面「Civitai 搜索」下载，或把模型放进 config.json 的 civitai.lora_dir。";

  return (
    <section className="panel active" id="panel-loras">
      <div className="card">
        <div className="card-head"><b>Civitai 搜索</b><span className="muted" id="lora-status">{statusText || status?.loraStatus || ""}</span></div>
        <div className="row wrap">
          <input
            id="lora-query"
            placeholder="模型搜索词，例如 角色名 / 画风"
            value={query}
            disabled={!unlocked}
            onChange={(event) => setQuery(event.target.value)}
          />
          <button type="button" id="lora-query-btn" disabled={!unlocked} onClick={() => void searchCivitai()}>搜索</button>
          <button className="ghost" type="button" id="lora-status-btn" disabled={!unlocked} onClick={() => refreshStatus()}>下载状态</button>
        </div>
        <div className="row wrap">
          <label className="check">
            <input
              type="checkbox"
              id="lora-auto-get"
              checked={!!status?.autoGet}
              disabled={!unlocked}
              onChange={(event) => void setAutoGet(event.target.checked)}
            />
            下载后自动领取展示图
          </label>
        </div>
        {/* 搜索结果直接显示在这张卡片里：封面图 + 信息，不再当成一条消息发出去 */}
        <div className="civitai-grid" id="civitai-list">
          {searching ? (
            <div className="civitai-empty">{searching}</div>
          ) : results.length ? (
            results.map((item, index) => (
              <CivitaiCard
                key={(item.number || index + 1) + ":" + (item.name || "")}
                item={item}
                index={index}
                unlocked={unlocked}
                onDownload={downloadItem}
              />
            ))
          ) : (
            <div className="civitai-empty">
              {emptyText || "搜索到的候选会显示在这里：输入关键词后点「搜索」。"}
            </div>
          )}
        </div>
      </div>

      <div className="card">
        <div className="card-head"><b>本机 LoRA</b><span className="muted" id="lora-count">{countText}</span></div>
        <div className="row wrap">
          <input
            id="lora-filter"
            placeholder="筛选本地 LoRA"
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
          />
          <button className="ghost" type="button" id="lora-reload" disabled={!unlocked} onClick={() => void loadLoras()}>刷新</button>
        </div>
        {/* 列表按名称做 key（React 按 key 复用 DOM，等价于 Java `syncRows` 的增量同步，删除时不会整表重画）。 */}
        <ul className="list" id="lora-list">
          {loadError ? (
            <li className="muted" key="load-error">{loadError}</li>
          ) : null}
          {loadError ? (
            <button className="ghost" type="button" key="load-retry" onClick={() => void loadLoras()}>重试</button>
          ) : null}
          {items === null && unlocked ? (
            <li className="muted" key="loading">正在读取本机 LoRA…</li>
          ) : null}
          {items !== null && matched.length === 0 ? (
            <li className="muted" key="empty">{listEmpty}</li>
          ) : null}
          {items !== null
            ? matched.map((item) => (
                <LoraRow
                  key={item.name}
                  item={item}
                  unlocked={unlocked}
                  onLoad={loadItem}
                  onRename={renameItem}
                  onRenameCancel={() => void loadLoras()}
                  onDelete={deleteItem}
                />
              ))
            : null}
        </ul>
      </div>

      <Receipts id="lora-receipts" />
    </section>
  );
}
