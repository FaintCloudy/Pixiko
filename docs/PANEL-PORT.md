# 网页控制台面板移植约定（React）

> **发布说明：** 本文记录把 Java 版控制台移植到 Next.js + React 的约定，属于那次**未完成**的重写：
> 后端 `lib/**` 已丢失，`nextjs-wip/` 里的代码不可运行。本仓库**当前实现是 Java 版**——
> 真正在用的控制台是 `webui/index.html` + `webui/app.js`。

Java 版的控制台是 `webui/index.html`（外壳 + 10 个面板块）+ `webui/app.js`（1787 行逻辑）。
Next 版已经把这些拆成了：

| 位置 | 内容 |
|---|---|
| `app/<栏目>/page.tsx` | 栏目页（`PanelPage id="…"`），只有本栏目的面板 |
| `components/shell/*` | 外壳、页签、页脚、弹窗、图片查看器、终端 |
| `components/panels/*.tsx` | 10 个面板 |
| `components/client/*` | 令牌/状态/弹窗（`store.tsx`）、接口客户端（`api.ts`）、回执（`capture.tsx`）、查看器（`viewer.tsx`）、通用小件（`lists.tsx`） |

**`webui/index.html` 里每个面板的静态骨架已经逐块搬进 `components/panels/*.tsx`（id/class 原样保留），
带 `{/* Phase N 后续接入：… */}` 注释的地方就是这一轮要补的逻辑。**
样式不用管：`app/globals.css` 直接 `@import "../webui/app.css"`，class 对上了样式就对了。

## 一、必须遵守的约定

1. **id / class 一个都不许改、不许删**。控制台自检（`tools/webui-selfcheck.mjs`）、DOM 探针和
   `webui/app.css` 都按它们取元素。要加新元素可以，但不要动既有的。
2. **只改你负责的文件**。公共文件（`components/client/*`、`components/shell/*`）已由主线写好；
   需要新公共件时在报告里说，不要自己去改（并发编辑会互相覆盖）。
3. **不要在子任务里跑 `npm run build`**（多个任务同时构建会互相踩 `.next`）。
   只跑 `cmd /c npx.cmd tsc --noEmit`，**必须 exit 0**。
4. 所有网络请求走 `components/client/api.ts` 的 `apiRequest<T>(path, {method, body})`：
   401/503 抛 `Unauthorized`、429 抛 `RateLimited`、其余非 2xx 抛 `ApiError`（`message` 就是服务端 `{error}`）。
   失败提示统一用 `useConsole().toast(第一行)` 或 `showBanner(整句)`，**不要用 `alert`/`confirm`/`prompt`**。
5. 所有"要不要删除/覆盖"的确认走 `useConsole().askDialog({title, text, confirmText, danger})`（返回 `true`/`null`），
   `askDialog({value})` 是输入框模式（返回文本/`null`）。信息框（查看原文）用 `showInfo(title, pre文本, {text})`。
6. 图片一律用 `<ImageNode file={...} className="…" caption={...} />`（点开查看器，`Ctrl`/中键仍可新标签打开），
   **不要**自己写 `<img src>`。
7. 列表用 `items.map(item => <Row key={item.name} …/>)`：React 按 key 复用 DOM，
   这已经等价于 Java `syncRows` 的"只动变化的那一行"，**不要用 index 当 key**（会让删除时整表重画）。
8. 轮询一律用 `usePoll(callback, intervalMs, enabled)`（页面隐藏时自动停）。
   只有登录后（`unlocked`）才发请求。
9. 文案逐字照抄 `app.js`（含错误提示、按钮 title、空列表提示）。行为也逐条对齐，
   包括"刷新失败时先用手上这份数据渲染，不要显示空列表"这类细节。
10. 源码里写中文注释，说明这一段对应 `app.js` 的哪个函数 / 哪一行，方便以后对照。

## 二、公共件（主线已实现，直接用）

```ts
// components/client/store.tsx
const { unlocked, status, health, banner, authNotice, busy, setBusy, toast, showBanner,
        unlock, lock, refresh, updateStatus, dialog, askDialog, showInfo } = useConsole();
// status 是 /api/status 的返回（每次 refresh 后变），updateStatus(next) 用接口返回就地更新，不整页刷新。

// components/client/capture.tsx —— 网页指令通道与回执
const { box, receipts, pending, runCommands, followCapture, clearReceipts, subscribe, register, refresh } = useCapture();
// runCommands(commands: string|string[], {after?, follow?}) → 跑指令 + 轮询回执（忙 900ms / 等外部 2500ms，最多 2400 次）
//   follow=true 用于 .gen/.get/.rg 这类要等图的指令：20 分钟内跟到图片回来，并启动 SD 进度轮询。
//   指令里含 gen/get/rg 时会自动触发登记过的 "tasks" 刷新器。
// followCapture(captureId, follow) → 只跟随一个已有回执（`/api/chat` 返回的 captureId 用它）。
// subscribe(handler) → 事件流 {type:"text"|"images"|"busy"}（终端、对话页、出图页订阅自己关心的部分）。
// register("status"|"tasks"|"images"|"progress-start", fn) → 在本面板 useEffect 里登记刷新器，返回取消函数。
//   runCommands 会在合适的时候调用它们（每 4 次轮询刷 status；gen/get/rg 前后刷 tasks；follow 时触发 progress-start）。
// <Receipts id="…-receipts" /> → 本栏目的回执区（文本卡片 + 图片卡片 + 执行中）。

// components/client/viewer.tsx
import { ImageNode, imageUrl, useViewer } from "../client/viewer";

// components/client/lists.tsx
import { Terms, InlineRename } from "../client/lists";
// <Terms id="prompt-positive-terms" list={[{term, meaning, number}]} onRemove={(number, term) => …} />
//   —— 词条 chip：`#编号 英文 中文`；词库里没有的释义会自动问一次 /api/meanings（learn=true）并就地补上，
//      拿不到就把省略号去掉。list 里带 meaning 的不会再问。
// <InlineRename value={name} prefix=" " onCommit={(next) => …} onCancel={() => …} />
//   —— 就地改名：回车提交、Esc 取消、失焦提交。
```

## 三、每个面板做什么（对照 `webui/app.js`）

### PromptPanel（`components/panels/PromptPanel.tsx`）
`/api/prompt`、`/api/prompt/edit`、`/api/meanings`、`/api/usage`，加上 `.progen` 指令。
- `loadPrompt`(524) / `renderPrompt`(529)：`#prompt-positive`、`#prompt-negative` 两段文本，
  `#prompt-positive-terms`、`#prompt-negative-terms` 两排 chip（`positiveTerms`/`negativeTerms`），
  `#prompt-scope`、`#prompt-meta`、`#undo-depth`。
- `editPrompt`(550)：`#prompt-add-btn`（追加）、`#prompt-remove-btn`（按词条移除）、`#prompt-clear-btn`、
  `#prompt-r-*`、`#prompt-save-btn`（保存两段）、`#undo-btn`（回退）。
- chip 点击 = 按词条原文移除（`/api/prompt/edit {action:"remove", term}`）。
- `#progen-btn` + `#progen-input` → `runCommands([".progen " + 文本])`（只展示不应用），回执进 `prompt-receipts`。
- `loadUsage`(1463)：`#usage-query` + `#usage-btn` → `/api/usage {query}`，`text` 填 `#usage-body`（`<pre>`），
  `choices` 渲染成 `#usage-choices` 里的 `<button>`（点了把 `#usage-query` 换成它的 `query`/编号再查一次）。
- `<Receipts id="prompt-receipts" />` 替换注释。

### StylesPanel + FunctionsPanel
- 样式（`app.js` 621–762）：`/api/styles`（列表 + `library` 计数 + 每条 `positive`/`negative` 原文）、
  `/api/styles/edit`（`save`/`overwrite`/`load`/`rename`/`delete`）、`#style-filter` 前端筛选、
  `#style-reload`、`#style-prompt-btn`（查看原文 → `showInfo`）、`#style-save`、`#style-overwrite`、
  `#style-import`（**走指令通道** `.style import webui`，用 `runCommands`）、`#style-load-nolora`、
  `#style-range` + `#style-batch-rename`/`#style-batch-delete`（区间 `#12-#16`/`#12,#15`，后端 `range` 参数见
  `app.js editStyles` 与 `lib/core/web-console.ts`）、每行按钮：载入/改名/查看原文/删除。
  `#style-loaded`、`#style-pageselected` 两句文案照抄 `renderStyles`(649)。
- 提示词集（`app.js` 927–975）：`/api/functions`、`/api/functions/edit`
  （`save`/`overwrite`/`load`/`remove`/`clear`/`reset`/`rename`/`delete`），`#function-*` 那一组控件。
- 两个面板各自的 `<Receipts id="style-receipts" />` / `<Receipts id="function-receipts" />`。

### LorasPanel
`/api/loras`、`/api/civitai/search`、封面走 `<ImageNode>`（URL 是 `cover`，用 `/api/civitai/thumb?url=…&token=…`，
见 `app.js coverUrl`(921)），下载/加载/改名/删除走指令通道（`.lora download #n`、`.lora load …` 等，
`app.js loadLoras`(766)/`renderLoras`(799)/`loraRow`(813)/`renderCivitai`(860)）。
- 本地列表：`#lora-list`（编号、名称可点改名、载入/改名/删除按钮），`#lora-search` 之类的控件见现有骨架。
- Civitai 搜索卡片：`#civitai-results` 里的 `.civitai-card`（封面 + 名称/基础模型/下载数/文件大小/训练词 +
  权重输入 + 下载按钮）；下载按钮发 `.lora download #编号 [权重]`（编号是这次搜索结果的编号）。
- 记住"先用手上这份列表渲染，切页签/刷新失败时不要出现空列表"。

### GenPanel
`/api/status`（`generation` 段）、`/api/options`、`/api/presets` + `/api/presets/edit`、`/api/generation`、
`/api/tasks` + `/api/tasks/action`、`/api/progress`、`/api/images`。
- 生成参数（`app.js 1033–1076`）：`#set-width`/`#set-height`/`#set-sampler`/`#set-model`/`#set-steps`/`#set-cfg`/
  `#set-seed`/`#set-imgcnt` 任何一个改变就 `applyGeneration()`（`POST /api/generation`，**没有「应用」按钮**），
  结果就地 `updateStatus`；`#apply-hint` 之类的文案照抄。
- 采样器/模型下拉：`/api/options` 的 `samplers`/`models`（`fillSelect`(504)，保留当前值）。
- 队列（`app.js 1152–1237`）：`/api/tasks` 的任务卡（`.task`/`.task-head`/`.task-track`/`.task-acts`，
  状态徽章文字与配色 class 照抄 `taskKind`(1161)），置顶/挂起/取消按钮 → `/api/tasks/action {action, number}`，
  生成期间每 1.5 秒刷新（用 `usePoll`），`register("tasks", …)` 让指令跑完自动刷。
- 进度条（`app.js 1077–1128`）：`/api/progress`，`register("progress-start", …)` 启动轮询，空闲时隐藏；
  读不到 SD 时显示「读不到 SD 进度」。
- 预设（`app.js 976–1032`）：`/api/presets` 列表 + `/api/presets/edit`（save/overwrite/delete）。
- 图片（`app.js 1130–1151`）：`/api/images {limit}` 网格（`a.image-link > img` + `.meta`），
  `register("images", …)` 让出图后自动刷新。
- `<Receipts id="gen-receipts" />`。

### ChatPanel + HelpPanel
- `sendChat`(332)：`#chat-form`/`#chat-input`/`#chat-send`（回车发送、`#chat-execute` 复选框 →
  `POST /api/chat {message, execute}`），回复填进「正在思考…」那条；
  `commands` 非空时补一条 `执行指令：…` 的系统消息，`captureId` 存在就 `followCapture(captureId, true)`
  （图片直接回到对话里）；`#chat-interest` 显示 `相关度 N`。
- `appendMessage`(318)：`.msg.user` 靠右、`.msg.bot`/`.msg.sys` 靠左，图片用 `<ImageNode className="msg-image">`，
  追加后滚到底。订阅 `useCapture().subscribe`：`images` 事件 → 追加一条 `图片好了，直接发在这里：` + 图片。
- 历史：`/api/chat/history`（`loadChatHistory`(365)），`#chat-reset` 清空（`/api/chat/reset`）。
- HelpPanel：`/api/help` 的 `help` 填进 `#help-body`（等宽 `<pre>`）。
- `<Receipts id="chat-receipts" />`。

### Terminal + SystemPanel/ChatCfgPanel 回执
- `Terminal.tsx` 的输入行（现在只回一句「指令通道待接入」）：接上 `runCommands`，
  按 `app.js runTerminal`(1362)/`followTerminal`(1422)/`terminalHistoryMove`(1452) 的行为——
  `.`/`/` 开头走指令通道并把回执文本/图片打进终端；其它文字当作 `POST /api/chat`（对话回复也打进终端）；
  ↑/↓ 翻历史（最近 100 条）；执行后焦点留在输入行。终端订阅 `useCapture().subscribe` 把回执打进日志流。
- `SystemPanel.tsx`：把 `#system-receipts` 的注释换成 `<Receipts id="system-receipts" />`，
  高级指令控制台（`#console-command`/`#console-run`）改走 `runCommands`；`#command-*` 常用指令按钮同样。
- `ChatCfgPanel.tsx`：`#chatcfg-receipts` 换成 `<Receipts id="chatcfg-receipts" />`，性格改写按钮走 `runCommands`。

## 四、验收

1. `cmd /c npx.cmd tsc --noEmit` → exit 0（必须）。
2. 自查：你负责的面板里不再有 `Phase N 后续接入` 注释；所有按钮/输入都有真实行为（不是 `disabled` 占位）；
   id 与 `webui/index.html` 对应面板块逐一对得上（可以 diff 一遍）。
3. 报告里写：改了哪些文件、每个 `app.js` 函数对应到哪个组件函数、tsc 结果、
   以及任何你**没能**做到 parity 的地方（例如接口缺字段）。
