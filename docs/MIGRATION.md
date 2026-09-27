# Pixiko 迁移记录：Java → Next.js + TypeScript

> **发布说明——本仓库的现状，请先读这段。** 这次 Next.js + TypeScript 重写**没有完成**。
> 后端 `lib/**`（约 60 个文件，机器人本体）已经丢失，所以 `nextjs-wip/` 里的 Next 代码
> **无法构建、无法运行**，`tests/**` 那批 TS 测试依赖它、也随之跑不起来，因此没有收进本仓库。
> **Java 版（`src/`）才是当前实现**，本仓库以它为准。
>
> 下面这份迁移记录写于重写进行时（当时 Java 源码被移出私有工作目录、只留了一份备份快照），
> 文中「迁移已完成」「Java 源码已从仓库删除」等说法**只对当时的那个私有工作区成立**；
> `Bot.java L#####` 这类行号引用指向那份已不在本仓库的备份。保留它的价值在于
> 接口与行为的逐条对照仍然有用——请当作**历史过程记录**读，不要当作当前状态。

> **状态（当时）：迁移已完成。** 机器人本体、全部 `/api/**`、10 个栏目的网页控制台都跑在
> Next.js 16 + React 19 + TypeScript 上（`server.mjs` 一个进程、两个模块图），
> Java 源码与构建脚本已从仓库删除，只保留在备份里：`backup/java-full-20260925-205448/`
> （含 `MANIFEST.txt` 逐文件 SHA-256 与同名 `.zip`）。本文件是**过程记录**，
> 代码里出现的 `Bot.java L#####` 行号引用都指向那份快照。
>
> 目标（已达成）：把 `pixiko` 的机器人从 Java（14,739 行主代码 + 10,000 行测试 + 2,600 行前端）
> **全面**重构为 Next.js + TypeScript，并保持 `config.json` 与 `data/**` 的数据格式**完全兼容**
> （用户的提示词、样式、预置、队列、中文释义、9.2 GB 生成图全部照旧可用）。

## 目录约定（最终形态）

```
server.mjs               入口：Node 原生 import 机器人本体 + 起 Next（两个模块图，见下文「进程边界」）
instrumentation.ts       Next 启动钩子：只做 bootstrap，**绝不 import 机器人**
app/                     Next.js App Router：每个栏目一个页面 + /api/** 路由
  api/[...path]/route.ts 对应 Java 的 WebApiController 那一个 switch（全部 /api）
  <栏目>/page.tsx        对应 WebPageController.PAGES 里的每个路径（10 个）
  civitai-login/ civitai-cookie/  一次性登录页与 Cookie 回填（书签小工具那条路径）
  globals.css            控制台样式入口（原 webui/app.css → app/console.css）
components/              面板与共用外壳的 React 组件（替代 webui/index.html + app.js）
  shell/                 Shell / PanelPage / 页签 / 弹窗 / 查看器 / 终端
  panels/                10 个面板
  client/                api.ts（接口客户端）/ store.tsx（令牌·状态·弹窗）/ capture.tsx（指令回执）/ viewer.tsx / lists.tsx
lib/                     机器人本体（对应 cn.szu.bot.*）
  util/                  json / fsx / log / mutex / errors / text / bigint
  config/settings.ts     Settings.java
  prompt/                TermCategories / PromptUsage / PromptFunctions / PromptEditor / UserPromptStore
  sd/                    SdClient / SdLauncher / LocalStyles / GenerationPreset / GenerationParameters / ImageOutbox
  civitai/               CivitaiClient / CivitaiStyles / CivitaiStyleSync / civitai-link
  chat/                  DeepSeekPrompts / ChatService / ChatActions / SceneDecomposer / WebSearch
  qq/                    QqClient（OneBot 正向 WebSocket）
  core/                  Bot.java 的拆分（指令、链路、生成队列、网页接口、CLI、锁、单例）
  web/                   网页层的服务端实现（状态、鉴权、日志、图片、回执、Civitai 封面）
tests/                   node:test 用例（替代 src/test/java 的 43 个类；740 条）
tools/                   自检（webui-selfcheck.mjs）、下线播报（offline-notice.mjs）、中文词库工具
public/                  站点图标
webui-extension/         SD WebUI 桥接扩展（与语言无关，**保留**：Next 版照样用它同步提示词）
backup/                  Java 版全量快照（源码、webui、脚本、MANIFEST + SHA-256）
data/ logs/ maps/ config.json   与 Java 版完全同一份数据
run.bat build.bat test.bat       启动 / 只构建 / 类型检查 + 测试
stop-bot.ps1                     先播报下线，再只结束本项目 server.mjs 的 node 进程
install-webui-bridge.ps1         安装/更新 SD 桥接扩展（保留）
```

## 关键等价关系（别写偏）

| Java | TypeScript | 说明 |
| --- | --- | --- |
| `Json.GSON`（pretty + 不转义 HTML，末尾换行） | `lib/util/json.ts` `stringify` | 逐字节兼容旧数据文件 |
| `Json.atomicWrite` + 文件锁 | `atomicWriteText` / `withFileLock` / `update` | 临时文件 + rename，按路径串行 |
| `Settings` 的 `synchronized` + `freshSnapshot` | `Settings.write()`（文件锁内重读-改-写） | 绝不用旧快照回写 |
| `Log` 的 `ThreadLocal` 网页标记 | `AsyncLocalStorage`（`runAsWeb` / `markWeb`） | route handler 内 await 出去都算网页侧 |
| `Bot.error(Throwable)` | `lib/util/errors.ts` `errorText` | 剥 cause，截断 600 字 |
| Spring `WebApiController` 的 switch | 各 `app/api/**/route.ts` | 路由分文件，行为与状态码一致 |
| `WebPageController.PAGES` | `app/<栏目>/page.tsx` | 一个栏目一个页面，只加载本栏目数据 |

## 阶段与进度

### Phase 0 — 备份与骨架 ✅
- [x] 源码全量备份（`backup/java-full-20260925-205448/` + zip + MANIFEST）
- [x] `package.json` / `tsconfig.json` / `next.config.ts`（next 16.3.6、react 19.3、ws 8.21）
- [x] `lib/util/{fsx,mutex,json,errors,log}.ts`、`lib/maps.ts`、`lib/config/settings.ts`
- [x] `lib/core/runtime.ts` + `instrumentation.ts`（启动时初始化日志/目录）
- [x] `app/layout.tsx`（复用 `webui/app.css`）、`app/page.tsx`、`/healthz`、`/api/**` catch-all
- [x] `tsc --noEmit` 通过；`next build` + 8788 端口冒烟通过（`/healthz` 200、`/api/status` 读到真实配置、
      `/api/nope` 404、首页 200）
- [x] `tests/settings.test.ts` 7 条对照用例全绿（序列化格式、并发写不丢键、freshSnapshot 语义、
      web scope 迁移、权限与概率边界）

**构建注意事项**：`next build` 必须走 webpack（`npm run build` 已经写好 `--webpack`）。
Turbopack 会对 `instrumentation` 端点做文件追踪，撞上被占用的 `data/bot.lock` 会直接 panic
（os error 33）；webpack 会尊重 `next.config.ts` 里的 `outputFileTracingExcludes`。

**跑起来**：`npm run build && npm start`（端口 8787；迁移验证期间用 `next start -p 8788`，
不动 Java 版占着的 8787）。`npm test` 跑 `tests/**/*.test.ts`。

**迁移期并行运行**：Java 版与 Next 版会同时开着（对照工具需要两边都活着）。Java 版握着
`data/bot.lock` 的 OS 文件锁，Node 侧删不掉这个文件，所以并行跑 Next 版时要加
`PIXIKO_LOCK=off`（PowerShell：`$env:PIXIKO_LOCK='off'; npm start`）跳过单实例抢占。
正式切换（Java 删除后）不需要这个环境变量：`lib/core/lock.ts` 会正常抢锁，
第二个实例会收到与 Java 版一致的「此目录已有机器人运行，请先关闭旧实例。」。

### Phase 1 — 数据层（提示词库）
- [x] `TermCategories`（230 行）→ `lib/prompt/term-categories.ts`（10 个用例）
- [x] `PromptEditor`（85）→ `lib/prompt/prompt-editor.ts`
- [x] `UserPromptStore`（204）→ `lib/sd/user-prompt-store.ts`（两者共 22 个用例）
- [x] `LocalStyles`（138 行）→ `lib/sd/local-styles.ts`
- [x] `lib/util/text.ts`：`javaStrip` / `javaIsBlank` / `JAVA_UNICODE_SPACE_RUN`（NBSP / BOM 语义对齐）
- [x] `lib/core/lock.ts`：`data/bot.lock` 单实例锁（原子占位 + pid 存活判断，文案与 Java 一致；8 个用例）
- [ ] `PromptUsage`（682，含 5000 次查询的索引守卫）→ `lib/prompt/prompt-usage.ts`
- [ ] `GenerationPreset`（73）/ `GenerationParameters`（34）→ `lib/sd/*`
- [ ] `PromptFunctions`（283）→ `lib/prompt/prompt-functions.ts`（依赖 SdClient，与 Phase 2 一起做）
- [ ] `CivitaiStyles`（57）→ `lib/civitai/civitai-styles.ts`（依赖 SdClient，放 Phase 2）
- [ ] 数据文件格式核对：`data/prompts/<scope>.json`、`data/local-styles.json`、`data/sd-parameters.json`、`data/prompt-*.json`

### Phase 2 — 外部客户端
- [x] `SdClient`（1,198）→ `lib/sd/sd-client.ts`（2,036 行，42 个用例；假 SD 用 `node:http` 真服务覆盖 bridge/`txt2img`/LoRA/样式/错误分支）
- [x] `SdLauncher`（333）→ `lib/sd/sd-launcher.ts`（23 个用例）
- [x] `ImageOutbox`（166）→ `lib/sd/image-outbox.ts`（`data/sd-outbox.json`，10 个用例）
- [x] `CivitaiClient`（825）→ `lib/civitai/civitai-client.ts`（2,113 行，36 个用例；含严格 JSON、续传、跳转安全、图床白名单，
      **以及配了 `proxy_url` 时真正走代理的流式 CONNECT 隧道**（真实假代理 + 自签 HTTPS 假服务验证，curl 交叉验证））
- [x] `WebSearch`（85）→ `lib/chat/web-search.ts`（含 Java `URLEncoder` 等价、代理 CONNECT 隧道、8 个用例）
- [x] `QqClient`（536）→ `lib/qq/qq-client.ts`（837 行，20 个用例；`ws` 起真实假 NapCat）
- [x] `DeepSeekPrompts`（1,407）→ `lib/chat/deepseek-prompts.ts`（38 个用例；提示词文本按 JLS 文本块规则机器提取并**逐字节比对**）
- [x] `CivitaiStyles`（57）/ `CivitaiStyleSync`（192）→ `lib/civitai/civitai-styles.ts` / `civitai-style-sync.ts`
- [x] `ChatActions`（150）/ `SceneDecomposer`（170）→ `lib/chat/chat-actions.ts` / `scene-decomposer.ts`
      （`deepseek-prompts.ts` 里临时的 `defaultPlanCodec` 已改成从 `chat-actions.ts` import，38 个既有用例复跑通过）
- [ ] `ChatService`（411）
- [x] `lib/core/instances.ts`：核心对象单例（`PromptUsage` 建索引 148 ms，必须复用；`sdClient()` / `sdLauncher()`）
- [x] `lib/core/lock.ts`：`data/bot.lock` 单实例锁

### Phase 3 — 业务核心（Bot.java 5,338 行的拆分）

`Bot.java` 有 297 个方法声明，不能整块照搬。按职责拆成下面这些模块，**依赖自下而上**，
每一块都要有对应的测试（Java 侧 `BotTest` / `ChatActionsTest` / `SdCommandsTest` / `LoraCommandsTest` /
`GenerationQueueTest` / `ChainRecordTest` / `MarriageTest` / `StyleBatchTest` / `InfixBackfillTest`…）：

| TS 模块 | 覆盖的 Java 区域 | 主要职责 |
| --- | --- | --- |
| `lib/core/selection.ts` | L91–L500 | 编号选择列表：`conversation` / `listTitle` / `listAction` / `resolveShownSelection` / `selectionNudge` / `speakableNumbers` / `ordinal` / `select` / `liveSelection` / `internalSelection` / `primeNumberedSelections` |
| `lib/core/commands.ts` | L993 `HELP`、L5262 `consoleCommand`、`publicCommands` | 指令表、帮助文本、控制台指令规范化（不能带 `.` / `/` 前缀也要认） |
| `lib/core/prompts.ts` | `effectivePrompts` / `promptTerms` / `auditAdditions` / `webMeanings` / `selfCheckPrompts` / `fixPromptConflicts` | 有效提示词合成、词条统计、改写审计、中文释义（含缓存与 DeepSeek 兜底） |
| `lib/core/generation.ts` | `GenerationJob` / 队列 / `webGeneration` / `webTasks` / `webTaskAction` / `webProgress` | 生成任务队列、暂停/恢复/取消、进度、领取 |
| `lib/core/lora.ts` | LoRA 搜索/下载/应用、`restoreLoraTags`、`lorasRequestedRemoved`、`loraStatus` | LoRA 标签增删与"按要求删除就不自动恢复"（`keepRemoved`） |
| `lib/core/images.ts` | `drainingImages` / outbox 投递 / `webImages` | 出图投递与图片列表 |
| `lib/core/chains.ts` | `accept`（入口 L1179）+ `dispatch`（L1248 的 if 链）+ `reply`/`messageText`/`duplicate`/`aimedAtAnother`/`quotedText`/`splitCommands` + 权限 + `/help`/`/imgcnt`/`/progress`/`/rg`/`/yh`/`/liv`/`/map`/`/admin` | 消息入口、指令分发骨架、回执与权限 |
| `lib/core/commands-sd.ts` | `sdCommand` + `/settings` `/sampler` `/size` `/cfg` `/seed` `/model` `/preset` `/style` `/function` `/lora` + `batchCommand` + `infix` + `progen` + `chatCommand` + `usage` + `map` | 各子指令族的具体行为（**拆成两个 agent 做，别塞进一个文件**） |
| `lib/core/web-console.ts` | L3890–L5000 | 网页指令与回执（`webEvent`/`webCommand`/`webCapture`/`webClose`/`webBusy`/`webChat`、`WebCapture` 类）、`webStatus`、`webPrompt*Edit`、`webFunctions*`/`webPresets*`/`webStyles*`、Civitai 一次性登录令牌 |
| `lib/core/setup.ts`、`lib/core/marriage.ts` | `Setup.java`、`Marriage.java` | 配置向导、结婚状态机 |
| `lib/core/notice.ts` | `Main.announce` / `Main.sendOnce` / `Bot.Sender` 的 QQ 实现 | 上/下线播报（关掉播报、群号非法则静默；上线只在"尚未连接"时重试 30×2s；下线只试一次）与 `qqSender` |
| `lib/core/bot.ts`（待写） | `Main.run` | 组装根：Settings/SdClient/QqClient/Chains/ChatService/生成队列/网页服务，QQ 事件 → `Chains.accept`，启动与停机流程 |

- [x] `selection.ts`（818 行 / 30 个用例；另用编译好的 Java 跑 169 条输入的纯静态方法对照，逐字节 0 diff）
- [x] `commands.ts`（`HELP` / `publicCommands` / `consoleCommand` / `internalCommand`）
      —— HELP 与 Java 版**逐行一致**（130 行、6394 字符、sha256 `b3acb0bd…a97b`，已作为测试指纹；
      用"源码文本块重建"和"直接 GET 运行中 Java 的 `/api/help`"两条独立路径核对过）
- [x] `prompts.ts`（677 行 / 41 个用例：effectivePrompts、promptTerms、auditAdditions、webMeanings（含缓存学习）、
      selfCheckPrompts、fixPromptConflicts）
- [x] `prompt-functions.ts`（499 行）/ `civitai-styles.ts`（105）/ `civitai-style-sync.ts`（405）——共 40 个用例
- [x] `marriage.ts`（392 行）/ `setup.ts`（697 行）——共 39 个用例
- [x] `notice.ts`（上/下线播报 + `qqSender`，8 个用例）
- [x] `cli.ts`（`--help` / `--check` / `--setup` / `--set-map`，7 个用例；对应 `Main.run` 的参数分支）
- [x] `generation.ts`（891 行 / 18 个用例：队列、挂起/继续/取消/置顶、结算、`tasksJson`/`taskActionJson`/
      `generationJson`/`progressJson`/`generationSummary`）
- [x] `lora.ts`（991 行）+ `images.ts`（323 行）——共 31 个用例（`.lora` 各子命令、Civitai 搜索编号、下载进度、出图投递与 ack）
- [x] `chat-service.ts`（993 行 / 28 个用例：会话键、角色分层、兴趣度与插话、冷却、上下文窗口、聊天频道失败降级）
- [x] `web-console.ts` 的纯本机那半（~940 行 / 22 个用例：`webPrompt`/`webMeanings`/`webPromptEdit`/`webFunctions*`/
      `webStyles*`/`webPresets*`/`webOptions`/`webUsage`/`webEvent`/Civitai 账号与一次性登录令牌）
- [x] `chains.ts`（1,715 行 / 26 个用例：入口、分发骨架、`ChainRecord` 多步回执合成一条聊天记录、
      `/`→`.` 改写、失败传播、权限、`handlers` 分发表、`hasChainRecord`/`routeReply`）
- [x] `commands-sd.ts`（约 2,300 行 / 35 个用例：`createHandlers` 28 个键，`/infix` 完整链路、
      `/sd` `/batch` `/gen` `/style` `/lora` `/function` `/preset` `/size` `/cfg` `/seed` `/model` `/prompt(R)`
      `/chat` `/admin` `/map` `/yh` `/liv` `/imgcnt` `/progress` `/rg` `/get` `/usage`）
- [x] `web-capture.ts`（539 行 / 25 个用例：`WebCapture` 收集器 + `WebCaptureStore` + 捕获感知 sender）
- [ ] **`/char` 尚未迁移**（Java 有：先查本机 LoRA/样式再交给用户确认）。它需要 `pendingCharacters`
      状态 + "按链跑多条指令"的入口（`chains` 的 `runChatCommands` 语义），落地后补进 `commands-sd` 的 handler 表。
- [x] `lib/core/bot.ts`（组装根，对应 `Main.run`）：把 QqClient / Chains / ChatService / GenerationQueue /
      LoraService / ImageDelivery / WebCaptureStore / WebConsole / Selections / Marriage / 28 个指令处理器
      接成一台机器人。互相依赖用"注入闭包 + 调用时读引用"解开（Java 用一个 5,000 行的类吃掉环）。
      `tests/bot.test.ts` 5 条端到端用例：**QQ 事件 → Chains.accept → 分发 → 回执发回传输**（`/help` 真发出
      130 行帮助）、`/imgcnt 5` 写盘、关掉聊天时非指令消息静默、上线/下线播报走 `send_group_msg`、
      未知指令给明确回执。
- [x] `instrumentation.ts`：服务启动时初始化日志/锁/数据目录；**`PIXIKO_BOT=on` 才连 QQ**
      （迁移期避免和 Java 版一起回两遍消息），并挂 SIGINT/SIGTERM 停机。
- [x] 词库惰性化：`PromptUsage`（3 万条索引）在组装根里不预先构造 —— 词库缺失/损坏不该让机器人起不来，
      与 Java 的 `usageCache` 惰性语义一致。
- [ ] `关键不变量`：见下
- [ ] 关键不变量必须有用例守住：`keepRemoved`（显式删除的 LoRA 不恢复）、回执静默才算跑完、
      链路串行不并发、`#编号` 选择列表与话题窗口分开计时
- [ ] 依赖**文案字面量**的判定（移植时不能改字）：`Main` 的上线播报循环靠
      `failure.contains("尚未连接")` 决定"还没连上、值得等"（30 次 × 2s），所以
      `lib/qq/qq-client.ts` 里「QQ 尚未连接，消息未发送」/「QQ 尚未连接，查询未发送」的子串必须保留；
      Java 测试另断言 `self_id`、`异步`、`超时`、`断开`、`未连接`、`retcode=100`、`（凭据已隐藏）`。

### Phase 4 — 全部 `/api/**` 与鉴权
- [x] `WebJson` 等价物 → `lib/web/response.ts`（pretty JSON、`no-store`、`contentTypeOf`）
- [x] `WebAuthFilter` 等价物 → `lib/web/auth.ts`（Bearer / `X-Webui-Token` / `?token=`，10 次失败冷却 30s → 429）
- [x] `/api/**` 统一入口 → `app/api/[...path]/route.ts`（保留 Java 一个 switch 的结构与状态码映射）
- [x] 已落地分支：`/api/status`、`/api/settings`、`/api/logs`、`/api/image`、`/api/help`、`/api/sd/status`、`/api/sd/start`、
      `/api/chat/history`、`/api/chat/reset`、`/api/prompt`、`/api/prompt/edit`、`/api/meanings`、`/api/functions`、
      `/api/functions/edit`、`/api/styles`、`/api/styles/edit`、`/api/presets`、`/api/presets/edit`、`/api/options`、
      `/api/usage`、`/api/civitai/status`、`/api/civitai/login-link`、`/api/civitai/cookie`、`/api/civitai/cookie/clear`
- [x] Civitai 一次性登录页：`GET /civitai-login?token=…`（书签小工具自动回填，**不用手动爬 Cookie**）+
      `POST /civitai-cookie`（无访问令牌、带 CORS，靠一次性令牌鉴权）。端到端验证 17/17：
      生成链接 → 打开页面 → 书签回填 → 状态变已登录 → 同令牌再用即失效(400) → 失效令牌 410；
      另确认线上 `config.json` 未被测试写入
- [ ] 其余分支（等组装根接线）：`/api/command`、`/api/capture`、`/api/capture/close`、`/api/chat`、
      `/api/generation`、`/api/tasks`、`/api/tasks/action`、`/api/progress`、`/api/loras`、`/api/images`、
      `/api/civitai/search`、`/api/civitai/thumb`
- [ ] `/api/status` 补齐 `generation` / `loraStatus`（`promptTerms` 已接上；其余字段与 Java 逐字段一致）

### 已知差异（经过评估、刻意保留）

1. **JSON 里的浮点写法**：Java 写 `"cfg_scale": 7.0`，TS 只能写 `7`（Gson 读回仍是 7.0）。
   纯字节级差异，语义兼容；`describe()` 里已复刻 Java 把整数显示成 `7.0` 的习惯。
2. **时间戳精度**：`updated_at` / `updatedAt` 用 `toISOString()`（3 位小数），Java `Instant.toString()`
   可能是 9 位。旧文件原样保留、不解析，老数据无损。
3. **`seed` 用 number**（Java long）：要求安全整数（≤ 2^53-1），超出会被判「生成参数记录无效」，
   而不是静默取整改数据。WebUI 的种子实际是 int32。
4. **record 的自动 `equals`** 没有对应实现：TS 侧用字段比较或 `json()` 深比较。
5. **`LocalStyles.importAll` 覆盖时留在原编号**（Java 是 remove+add 挪到末尾）：Java 那处会让
   `#1-#9` 的编号错位，而 `save()`/`rename()` 的注释明说编号依赖列表顺序稳定，按意图实现并加注释。
6. **不跨进程互斥**：JSON 写盘用的是同进程按路径串行 + 原子替换。Java 的锁同样是进程内的
   （`ConcurrentHashMap` 锁），所以行为一致；但 Java 版与 TS 版不要同时写同一份 `data/`。
7. **图片校验不解码像素**：Java 的 `ImageOutbox` 用 `ImageIO` 整张解码，Node 没有内置解码器、
   也不允许加依赖，改成结构级校验（PNG 签名 + IHDR 宽高、`decode` 时要求 IEND；JPEG 扫到 SOF 取宽高、
   `decode` 时要求 EOI）。改扩展名的文本、魔数不对、截断头、宽高 0 一律同样拒绝；
   但"结构完整、IDAT 像素损坏"的 PNG 这里会放过（Java 会拒）。要逐字节对齐只能引依赖。
8. **路径大小写**：Java 的 `WindowsPath.equals` 大小写不敏感，Node 比字符串，所以"磁盘上大小写不同"
   的路径在这里会被判为符号链接/跳转而拒绝（更严）。实际路径都来自程序自己推导，不会触发。
9. **`acknowledgeAll(null)`**：Java 会 NPE，这里当空批处理（no-op）；`append(null)` /
   `acknowledge(null)` 的「…不能为 null。」文案两边一致。
10. **`SdLauncher` 的几个方法是 async**：`fetch` 只有异步版（Java 用同步 `HttpClient`）。
    另外 `learnArgsFromRunningSd()` 在 Node 里改用平台只读命令（Windows `Get-CimInstance`，
    其它平台 `ps -eo args`），可注入 `CommandLineSource` 完全绕开。

## LoRA「保护机制」已按要求删除（Java 版与 Next 版同步）

用户明确要求删掉 Java 版所有 LoRA 保护机制，已执行并重新部署（8787 在跑新 jar）。删除清单：

| 删掉的东西 | 位置 |
| --- | --- |
| `restoreLoraTags(event, loadedBefore[, keepRemoved])`（改写后自动把丢掉的 LoRA 标签加回去 + 回执） | `Bot.java` |
| `lorasRequestedRemoved(request, tags)` 与 `REMOVAL_WORDS`（"用户点名要删的就不恢复"那套账） | `Bot.java` |
| `collectLoraTags(event, known)`（链路中途收集已加载标签） | `Bot.java` |
| `executeChatCommands` / `runChatCommands` 里 `knownLoraTags` / `keepRemoved` 的一整套参数与调用点 | `Bot.java` |
| 提示词里的「保留 LoRA 标签原样」与「LoRA/嵌入标签永不丢弃」两句 | `DeepSeekPrompts.java` → 同步到 `lib/chat/deepseek-prompts.ts` |

保留：`loraTagsIn()`（只读：列表/样式保存用）、`.lora` 的全部功能、释义里把 `<lora:…>` 说明成「LoRA 标签」、
以及提示词里「不要凭空编造模型名或 LoRA 标签」这句（防造假的，不是保护）。

证据：源码 grep 0 命中；`javap -p build\pixiko.jar cn.szu.bot.Bot` 只列出 `loraTagsIn`，
`restoreLoraTags` / `lorasRequestedRemoved` / `collectLoraTags` 已从产物中消失；
三段提示词（FREE_EDIT_RULES / EDIT_RULES / CATEGORY_RULE / VOCABULARY_RULE）与 Java 源码**逐字节一致**。

## 本轮修掉的两个 parity 缺陷（子代理发现、我改的）

1. **`SdClient.generate` 在 TS 里全程持锁**（Java 的 `generate` 没有 `synchronized`）：出图动辄几分钟，
   期间 `.gen`、`/api/generation`、`settings()`、`setSize()` 全部排队 —— 用户能直接感觉到的卡死。
   现在拆成「前处理（锁内，快）→ `txt2img` 请求（锁外，慢）→ 后处理（锁内，快）」，
   共享状态的读写仍有锁保护；`tests/sd-generate-lock.test.ts` 两条用例守住
   （出图 1.2s 期间 `parameters()` 必须立刻返回；并发出图各自发请求且两张图都进待发送队列）。
2. **校验类异常类型**：`lib/prompt/prompt-editor.ts` 原来抛裸 `Error`（→ 500），Java 是
   `IllegalArgumentException`（→ 400）—— 于是 `/api/prompt/edit` 传个括号不匹配的提示词会回 500。
   现已统一成 `BadInput`；`lib/maps.ts` 的 `localImages()` 也补上了 Java 那句
   「图片不存在或为空：<文件名>」（原来直接漏 `ENOENT`，QQ 与网页的失败回执会少一句中文说明），
   新增 `tests/maps.test.ts`（7 条）。

## ⚠️ 进程边界：机器人不能跑在 Next 的模块图里（重要发现）

**现象**：只要 `instrumentation.ts` 或 `/api/**` 路由 import 机器人本体（`lib/core/bot.ts`，含
`chains` / `commands-sd` / `generation` / `lora` / `civitai-client` 等），`next build` 就在
"Collecting page data" 阶段崩：

```
TypeError: Cannot mix BigInt and other types, use explicit conversions
```

**原因**：Next 构建期会**求值**每个路由所在的 chunk（页面数据收集 + `@vercel/nft` 的文件追踪），
其中包含一个静态求值器，它把 `BigInt(x)` 的折叠结果当成普通数字，于是 `BigInt(x) * 1000000000n`
这类表达式被折成「数字 ⊗ BigInt」而抛错。三种写法都试过、都会崩：instrumentation 静态 import、
路由静态 import、路由动态 `import()`（webpack 只被一处使用的动态 import 会并进同一个 chunk）。

**已做的缓解**（让 build 重新可用）：
- `instrumentation.ts` 只做基础设施初始化（日志/锁/数据目录），**不 import 机器人**；
- `lib/core/bot-holder.ts`（零依赖）保存机器人实例；
- `/api/**` 里需要机器人的分支通过持有器读取实例，取不到就回 **503**（文案说明"迁移中"）；
- `lib/civitai/civitai-client.ts` 里可被折叠的 BigInt 运算改成走 `lib/util/bigint.ts` 的助手函数
  （模块级的 `60n * NANOS_PER_SECOND` 这类也会被折叠）；
- `lib/core/{chains,commands-sd}.ts` 里 `count <= 0n` 同样改成助手函数。

**收尾阶段的方案**（**已实现**）：**自定义服务器 `server.mjs`** —— 同一个进程里跑两个模块图：
它先 `bootstrap()`，再按 `PIXIKO_BOT=on` **直接用 Node 的类型擦除 import `lib/core/bot.ts`**（绕开
webpack，因此不会触发那个求值器），把实例放进 `lib/core/bot-holder.ts`，最后把请求交给 Next 的
`getRequestHandler()`。`npm start` 已经指向它；`--port/--host` 可覆盖（默认取 `config.json` 的 `webui.port`）。
实测（scratch 根 + `PIXIKO_BOT=on` + QQ 指向死端口）：Web 正常服务，机器人起来了，`/api/tasks`→`[]`、
`/api/images`→`{"images":[]}`、`/api/progress`→真实队列文案（`生成队列：空闲，等待 0 个，合计 0 个。自动领取：开启。`）、
`/api/loras`→真实 LoRA 列表结构（含 SD 不可用的错误字段）全部返回**真实数据**（不再是 503）。

**同一条规则适用于任何未来的后台服务**：想跟 Web 同进程跑、又会被 Next 求值的模块，都必须由
`server.mjs`（而不是 `instrumentation.ts` / 路由）加载。

## Civitai 账号：两版都改成「粘一条一次性登录链接」

用户要登录 Civitai 时拿到的是邮件里的一次性链接（`https://auth.civitai.com/login/email/verify?token=…`）。
两版都做成**一个输入框**：粘贴链接 → 服务端跟随跳转链（verify → authorize → oauth/authorize → callback →
post-login → 首页，最多 10 跳）逐跳收 `Set-Cookie`，取 `civitai.red` 域上最后那份会话 Cookie
（civitai 自己的会话/设备两条，丢弃 `oauth_bridge`）→ 写 `config.json` 的
`civitai.session_cookie` → 带 Cookie 打 `/api/v1/models?limit=1&types=LORA` 验证。**凭据与一次性令牌都不进日志**
（只出现 `cookieHint`）。书签小工具、手抄 Cookie 与那个独立的 `/civitai-login` 页面都已删除：入口只有一个
输入框，放在控制台新增的 **「配置」栏目**（`/setup`，与首次配置同一页）；`/civitai-cookie` 接口保留不删。

- Java 版：`Bot.civitaiSaveFromLink` + `cn.szu.bot.civitai.CivitaiLinkLogin`（`follow` 跟随跳转链、
  `verify` 带 Cookie 验一次），接口 `POST /api/civitai/link`，
  前端 `webui/index.html` 的 `#civitai-link`/`#civitai-link-save`（Setup 栏目内）；`src/test/java/…/CivitaiLinkTest.java`（假 HTTP 服务整链）。
  实测（本机需走 `civitai.proxy_url` 代理）：跟随真实链接拿到凭据、写入配置、`/api/civitai/status` → `hasCookie=true`。
- Next 版：`lib/civitai/civitai-link.ts`（自包含，不牵 `civitai-client.ts`）+ `POST /api/civitai/link`（**动态 import**）
  + `components/panels/SystemPanel.tsx` 的 `#civitai-link`；`tests/civitai-link.test.ts`（10 条）。
- **已知语义**：注入自定义 transport 时**不校验** `proxy_url`（Java/TS 一致）；真实路径的代理校验是
  「只接受本机回环 http 代理」，文案 `civitai.proxy_url 仅支持明确的本机 HTTP 代理，例如 http://127.0.0.1:7890。`

## 迁移工具
- `tools/java-test.mjs`：**并行**跑 Java 测试（`build.ps1 -Test` 是 37 个类串行各起一个 JVM，约 5 分钟）。
  `node tools/java-test.mjs`（默认并发 = 核数，最多 8；20 核实测 **37/37 通过、总耗时 20 秒**）/
  `node tools/java-test.mjs CivitaiLinkTest WebUiTest`（只跑指定的）/ `--concurrency N` / `--timeout ms` / `--list`；
  `npm run test:java` 是别名。两个坑：`--timeout 240000` 这类 `--flag value` 的值不能被当成类名（已修）；
  有非 daemon 注入线程的测试类（例如注入的 `HttpClient`）`main` 结尾要 `System.exit(0)`，否则并行跑法会一直等进程退出
  （`CivitaiLinkTest` 就因为这个挂了 120s，修完 1.2s）。
- `tools/api-parity.mjs`：同一个接口分别打 Java（8787）与 Next（8788）并逐字段比对，
  输出 `OK / DIFF / PENDING / FAIL`（`--only /api/status` 可只比一个）。
  当前：`/api/logs`、`/api/help`、`/api/sd/status`、`/api/sd/start`、`/api/prompt`、`/api/meanings`、
  `/api/functions`、`/api/presets`、`/api/options`、`/api/usage`、`/api/styles`、`/api/civitai/status`、
  `/api/chat/history` 逐字段一致；`/api/status` 只差 `generation` 与 `loraStatus`
  （生成队列/LoRA 服务落地后补齐）。忽略项只有三类：进程启动时间/迁移标记、`path`、
  以及 `sd.text` 里「最近一次：…」那一行运行态历史。

### Phase 5 — 前端 React 化（10 个栏目）✅
- [x] 站点图标：`webui/{favicon.ico,favicon-32.png,apple-touch-icon.png,icon-192.png,icon-512.png}` 复制到 `public/`
      （Next 只从 `public/` 提供静态资源，`app/layout.tsx` 声明这些路径）。
- [x] 外壳与 10 个栏目的静态骨架：`components/shell/**`、`components/panels/**`、`app/**` 10 个路由页。
      验证：10 个路由 HTTP 200、各自面板恰好 1 个且不混入别栏目、10 个页签 href 正确且只有当前栏 active、
      面板尺寸非 0、控制台零报错；id/class 与可见文案与 Java 版逐条比对一致。
- [x] 外壳接线：`#health`/`#subtitle`（`/api/status` 8 秒轮询）、令牌流程（`#login*`/`#lock`，令牌只在 localStorage）、
      `#toast`、`#overlay`（同风格确认框 / 输入框 / 信息框，**不用原生 `confirm/prompt`**）、图片查看器。
- [x] 终端：日志增量跟随（`/api/logs` 4 秒、`#terminal-tail` 无限下拉）、提示符贴在最新日志后、
      输入行走指令通道与聊天频道、↑/↓ 历史 100 条、`Ctrl+L` 清屏、全屏。
- [x] 各栏接线（全部对着 `app.js` 的函数逐条搬）：
      - `components/client/capture.tsx` 统一了指令回执（`runCommands`/`pollCapture`/增量渲染/`follow` 跟图）；
      - prompt（`/api/prompt` + `/api/prompt/edit` + `/api/meanings` + `/api/usage` + `.progen`）；
      - styles（`/api/styles` + `/api/styles/edit` + `.style import webui`）；
      - functions（`/api/functions` + `/api/functions/edit`）；loras（`/api/loras` + `/api/civitai/search` + 封面代理）；
      - gen（`/api/status` + `/api/options` + `/api/presets(/edit)` + `/api/generation` + `/api/tasks(/action)` +
        `/api/progress` + `/api/images`）；chat（`/api/chat(/history/reset)` + 回执图片回流）；
      - chatcfg / system（`/api/settings`、`/api/sd/*`、`/api/civitai/*`、`#shortcuts` 常用指令）；
      - logs、help（`/api/help`）。
- [x] 提示词词条「英文 中文」同行、中文弱化；释义走 `/api/meanings`（词典优先，缺的带 SD 前提问 DeepSeek）。
- [x] Civitai 搜索在卡片内渲染（结构化成网格 + 封面代理），不发消息、不走回执。
- [x] 样式全部沿用原视觉：`app/globals.css` → `app/console.css`（原 `webui/app.css` 原样搬进来，class 一字未改）。
- [x] 增量列表：React 按 `key={item.name}` 复用行，等价于 Java 的 `syncRows`（删除/加载只动变化的那一行）。

### Phase 6 — 测试与工具 ✅
- [x] `src/test/java` 43 个类 → `tests/**/*.test.ts`（node:test）：**740 条**，全量约 24 秒（文件级并行）。
- [x] `tools/webui-selfcheck.mjs`：77 项检查（直连内部 `/api`，只读 + 幂等），
      并新增两条接线检查（10 栏目页签/页面/面板一一对应；`components/**` 调用的 `/api` ↔ `route.ts` 的 `case`）。
- [x] 浏览器探针：`work/panel-probe.mjs`（10 个栏目的控制台零报错、无失败请求、各栏数据与接口对账、若干交互）。
- [x] 评测脚本：Java 的 `eval-*.ps1`（ChainEffectEval / ChainHitRateEval / SceneDecomposeEval / ZhTagEval）
      随 Java 一起删除；中文词库那套 Node 工具（`tools/build-zh-tags.mjs` 等）保留。

### Phase 7 — 收尾 ✅
1. [x] `npm run build`（webpack）→ `tsc --noEmit` 0 错、`npm test` 740/740。
2. [x] 迁移期用 `tools/api-parity.mjs` 逐字段对照（工具随 Java 一起删除，最后结果：见「已知差异」）。
3. [x] 浏览器探针确认 10 个栏目：控制台零报错、无失败请求、登录/令牌流程、终端增量与 `#terminal-tail` 增长、
   帮助栏与 `/api/help` 同长、对话历史与 `/api/chat/history` 一致。
4. [x] 停 Java（先播报下线再结束进程）→ 8787 空闲、`data/bot.lock` 不再有 Java 进程持有
   （Node 版按 pid 存活判断接管残留锁）。
5. [x] 启动方式换成 Next：`run.bat`（`npm run build` + `PIXIKO_BOT=on node server.mjs`，
   `--help`/`--check`/`--setup`/`--set-map` 转给 `node lib\core\cli.ts`）、`build.bat`、`test.bat`；
   `stop-bot.ps1` 改成匹配本项目 `server.mjs` 的 node 进程（精确匹配，绝不误杀别的 node），
   并先用 `tools/offline-notice.mjs` 播报下线。
6. [x] 真实流量验证：QQ 侧指令、网页 10 个栏目、生成一张图与自动领取、Civitai 搜索、日志三路都在写。
7. [x] 删除 Java（保留备份）：`src/`、`lib/spring/`、`lib/gson-*.jar`、`build/`、`build.ps1`、`test.bat`（Java 语义）、
   `eval-*.ps1`、`webui/`、`tools/java-test.mjs`、`tools/api-parity.mjs`。
   **注意（与最初计划的偏差）**：`webui-extension/`（SD 桥接扩展）与 `install-webui-bridge.ps1` **保留** ——
   Next 版的 `lib/sd/sd-client.ts` 照样通过 `/pixiko-bridge/v1/…` 同步提示词，删掉它们会丢功能；
   `run.bat` **保留**但重写成 Node 启动器（不是 Java 构建脚本）。
8. [x] 重写 `README.md`（Next.js 版架构、启动/停止、`config.json` 与 `data/**` 格式、测试与自检、
   「从 Java 版迁过来的人需要知道什么」）。
9. [x] 全量回归（`npm test` + 自检 + 浏览器探针 + 真机 QQ/网页/生成），并把本文件标成完成态。

### 收尾这一轮修掉的 parity 缺陷（都是实测发现）
1. **指令回执的状态码**：Next 的路由是 webpack 打包的，机器人本体由 `server.mjs` 用 Node 原生加载，
   同名的 `BadInput`/`Conflict` 是两个类 → `instanceof` 不成立，机器人里抛的 400/409 到了路由会变成 500
   （实测 `/api/civitai/search` 空搜索词）。现在两个异常类各带一个稳定标记（`pixiko.bad_input` / `pixiko.conflict`），
   `isBadInput`/`isConflict` 同时认标记，跨模块图也能识别。
2. **`/api/civitai/thumb` 非白名单图床**：Java 只 catch `IOException` → 502，`IllegalArgumentException` → 400。
   Next 原来把所有异常都塞进 502；现在 `BadInput` → 400（文案也对齐成 Java 的「只允许抓取 Civitai 图床的封面图。」）。
3. **`/api/settings` 少字段**：Java 回的是整份 `bot.webStatus()`，Next 原来只回 `statusJson()`，
   网页改完设置会把 `generation`/`loraStatus` 从本地状态里抹掉。现在 `/api/status` 与 `/api/settings` 共用
   同一个 `fullStatus()`。
4. **`ImageNode` 只认 `data/generated/…`**：Civitai 封面（`/api/civitai/thumb?…`）会被重拼成 `/api/image?path=…`
   而必然 404。现在 `data:` / `http(s):` / `//` / `/api/` 开头的地址原样使用，并支持 `children`
   （出图网格的 `.meta` 要挂在 `<a class="image-link">` 里面，与 Java `loadImages` 同构）。
5. **`#set-notice` / `#set-logmirror`**：Java 的 `/api/status` 不报这两个值，网页勾选框永远从"未勾选"开始。
   Next 的 `/api/status` 补了 `noticeEnabled` / `logMirror`，勾选框如实回显并可直接改（`/api/settings`）。
6. **`#apply-image-model`**：Java 用 `status.imageChannel.model` 回填输入框，却把值当 SD 基础模型发给 `.model set`
   （自相矛盾，必然报"未知模型"）。Next 版走 `/api/settings {key:"imageModel"}`，与回填来源一致。
7. **指令失败不再静默**：终端用 `runCommands`，而 `runCommands` 原来只把错误 toast 掉——
   在终端里敲错指令会什么都不显示。现在失败时同时回执一条 + 抛一次 `text` 事件，终端会打成 `err` 行。
8. **回执图片去重**：Java 的 `state.seenImages` 从不按回执清理，新回执会把该回执里已有的图片整批重发；
   Next 按回执 id 只发新增。

9. **有意保留的一处偏差（`/style load` 的引号）**：Java 的 `/style load` / `/style prompt` 少了 `stripQuotes`
   （`save`/`overwrite`/`rename` 都有），于是 `/char apply` 生成的 `.style load "名称"` 在 Java 里**必然失败**，
   「确认后加载并出图」那条链路走不到 `.gen`。Next 版在这两条上也 `stripQuotes`，让链路真正成立；
   命令文案本身仍与 Java 逐字一致。想严格回到 Java 行为，删掉 `lib/core/commands-sd.ts` 里那两行 `stripQuotes` 即可。

### 上线后由真实使用发现并修掉的两个 bug（QQ 侧编号指代）

用户在群里发了「查看styles」→「选择第二个然后生成」→「选择#2」，机器人回了
「聊天指令未全部完成：查询编号在等待聊天回复时已变化，请重新查询后操作。」，编号指代在 QQ 侧完全不可用。

1. **规划层与编号守卫看到了两份不同的 `choices`（真 bug，我的移植引入）**
   - `ChatService.choicesOf` 只拼了 `{last_list:{kind,items}}` 喂给规划层；
   - 而 `Chains.runChatCommands` 的编号守卫按 `kind`（style/function/preset/sampler/model/prompt/usage/lora/civitai）
     取 `choices[kind]` 与当前 `selectionContext` 的明细逐值比对 → 守卫永远拿到 `undefined` → 一律拒绝。
   - 修法：`ChatService` 增加 `actions.selectionContext` 注入口，`bot.ts` 把**同一个**
     `webSelectionContext(selectionTable, …)` 同时给 `ChatService` 与 `Chains`（守卫两边同源，天然一致）。
   - 回归测试：`tests/bot.test.ts`「组装根：QQ 里「选择#2」按刚展示的样式列表执行（编号守卫不能误拦）」
     —— 真组装根（真 Chains + 真 ChatService + 真 Selections），修前能复现用户看到的原话。
2. **「选择第二个然后生成」被当成"编号与名字对不上"**
   - `resolveShownSelection` 把编号后面那截（`然后生成`）当成名字 → 反问澄清，用户的复合要求落空。
   - 修法：新增 `splitNumberedFollowUp`（`lib/core/selection.ts`）：把"编号 + 后续动作"切成两半，
     编号那半句照常由程序判定成 `.style load #N`，后半句交给 `pureGenerationPlan` 翻成 `.gen [N]`，
     程序把两步拼成一条链路（不花模型调用）。`tests/chat-service.test.ts` 有对应用例
     （「选择第二个然后生成」→ `[".style load #2", ".gen"]`；「用第二个再画两张」→ `[".style load #2", ".gen 2"]`）。

**这类 bug 说明**：只有"真组装根"级的测试才能挡住它们 —— 单模块测试里 `runCommands` 是桩，
守卫根本没跑；`tools/webui-selfcheck.mjs` 与浏览器探针走的是网页通道（网页的 `choices` 本来就是完整的）。
所以 `tests/bot.test.ts` 现在是**必跑项**：它用真 Chains 把 QQ 入口到指令执行的整条链路串起来。

## 已知坑（从 Java 版踩过的）

1. ~~**不要合并 fat jar 的 `META-INF`**~~（历史）：同类名资源互相覆盖会让 Spring Boot 静默退化成非 Web 应用。
   Java 版已经删掉，这条只解释备份快照里的 `run.bat` 为什么用 `-cp`。
2. ~~**PowerShell 5.1 读无 BOM 的 `.ps1` 按 ANSI 解析**~~（历史）：迁移期 `stop-bot.ps1` 因此保持纯 ASCII。
   现在的 `stop-bot.ps1` 是 UTF-8 且带中文输出 —— 用 PowerShell 7（`pwsh`）或让文件带 BOM 都可以。
   **另一条同样要命的经验：绝对不要用 PowerShell 的 `Get-Content`/`Set-Content` 批量改写源码文件**
   （按 GBK 读 UTF-8 会写坏中文，`[...]` 还会被当通配符）——本项目真的因此坏过一个路由文件。

3. **`bot.lock`**：Java 版用 OS 文件锁保证单实例。Node 版沿用同一路径（`data/bot.lock`），
   改成「pid 文件 + 原子创建」：里面是活着的 pid 就拒绝启动，pid 已死（含 Java 留下的空文件）就当残留接管。
   所以**切版本前必须先停掉另一个版本**，否则两边会同时连 QQ。
4. **面板 `active` 类**：必须是 `<section class="panel …" id="panel-xxx">` 的形式，否则样式算出来的尺寸是 0
   （终端那种按高度算的布局会直接塌掉）。
5. **词库查询必须是索引**：`/api/generation` 曾因为线性扫描几万条词库而要 600 ms。
6. **LoRA 删除要尊重用户**：显式要求删除的标签不自动恢复（`keepRemoved`），日志留一行说明。
7. **两个模块图**：机器人本体由 `server.mjs` 用 Node 原生加载，Next 路由是 webpack 打包的。
   跨图不能靠 `instanceof` 认异常类型（见「收尾这一轮修掉的 parity 缺陷」第 1 条），
   也**绝不能**从 `instrumentation.ts` 或任何路由里 import 机器人（构建期静态求值器会在 BigInt 上崩）。
8. **构建必须走 webpack**：`next build --webpack`。Turbopack 会在被占用的 `data/bot.lock` 上 panic（os error 33）。

---

## 最终验收记录（2026-09-26 凌晨）

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 类型检查 | `npm run typecheck` | 0 错误 |
| 单元/对照测试 | `npm test`（`node --test tests/**/*.test.ts`） | **742 / 742 通过**，~24 秒 |
| 生产构建 | `npm run build`（webpack） | 成功（12 个页面 + `/api/[...path]`） |
| 控制台自检（临时根 8791） | `node tools/webui-selfcheck.mjs --base http://127.0.0.1:8791` | **77 / 77 通过** |
| 控制台自检（真实根 8787，Next 在跑） | `node tools/webui-selfcheck.mjs --base http://127.0.0.1:8787` | **77 / 77 通过** |
| 浏览器探针（临时根，完整） | `node work/panel-probe.mjs --url http://127.0.0.1:8791` | **53 / 53 通过**（15 张截图） |
| 浏览器探针（真实根，只读） | `node work/panel-probe.mjs --url http://127.0.0.1:8787 --readonly` | **53 / 53 通过**（3 条改数据的交互按预期跳过） |
| `/api/chat` + `.char` 双版本对照 | `node work/chat-e2e-java-compare.mjs`（Java 8787 ↔ Next 8791，删 Java 前跑的） | 7 / 7 逐字相同 |
| Civitai 一次性登录链接 | `node work/civitai-login-flow.mjs` | 11 / 11 通过（登录页认令牌、一次性语义、回填落盘、脱敏、清除） |
| 真机出图 | 网页控制台 `.gen 1` | 任务 #1 排队 → SD 出图 → 自动领取「本次领取完成，共 1 张。」；`/api/image` 取图 HTTP 200 / image/png / 1.24 MB |
| 真机 QQ | 切换后 `logs/qq-*.log` | 群消息持续收到并处理；上线播报已发到群 100000000；单实例锁 `data/bot.lock` = 当前 node pid |
| 上线后修复 | 群里「查看styles → 选择#2 → 生成」暴露的编号守卫 bug | 见上节；`tests/bot.test.ts` 用真组装根复现并守住，已重新部署 |
| 删除量 | Java 源码与构建产物 | `src/` 79 文件、`build/` 21818 文件、`lib/spring/` 23 个 jar、`webui/` 8 文件等，共约 823 MB；备份仍在 `backup/java-full-20260925-205448/` |

删除 Java 之后的仓库只剩 4.8 MB 源码/配置/样式（不含 `data/`、`logs/`、`backup/`、`node_modules/`、`.next/`、`work/`）。
