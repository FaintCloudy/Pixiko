# 验收条件跟踪（用户第 13 节，13 条）

> 状态随时间更新；「证据」列指向测试/文件，便于复核。生产 8787 全程**冻结**在重构前版本（`df7e606`，jar 02:19:03），未部署本轮任何改动。

| # | 验收条件 | 状态 | 证据 |
|---|---|---|---|
| 1 | 所有既有测试通过 | **进行中** | 89 个既有 `main()` 套件；本轮已修 `GenerationQueueTest`(344) 与 `AppUpdateTest` 版本漂移（隔离收尾代理在改）；每次只跑受影响面，**全量门等用户批准** |
| 2 | 新增并发/鉴权/取消/白名单/DeepSeek transport 测试通过 | **已达成（本轮范围）** | `WebAuthFilterTest` 69、`GenerationQueueInvariantTest` 174（含 8 线程并发）、`WhitelistSemanticsTest` 9 组、`DeepSeekTransportTest`（取消不重试/REVIEW 隔离）；取消 SD 的 8 条断言在 Phase 1 之前已完成 |
| 3 | 群聊/私聊/网页端使用同一应用服务，不再复制规则 | **未开始** | Phase 2：`CommandDispatcher` 权限上提 + Phase 3 `ChatOrchestrator` |
| 4 | 普通聊天不受指令白名单限制 | **已达成** | `WhitelistSemanticsTest`（关闭/开启两态下群+私普通聊天均放行） |
| 5 | 同一会话消息有序、不同会话可并行 | **未开始** | Phase 3 `ConversationScheduler`（现状 `ChatService` 全局单线程 + 无界队列） |
| 6 | 十条快速到达的聊天消息不无提示消失 | **未开始** | Phase 3：有界池 + 忙/超时明确提示 + 测试 |
| 7 | 中断标记不会被业务代码清除 | **已达成** | 传输层：带标记→0 次请求 CANCELLED、`InterruptedException` 恢复标记不重试；`DeepSeekTransportTest` |
| 8 | 生成队列计数永不为负、任务只结算一次 | **已达成** | `GenerationQueueInvariantTest` 174（cancel all / hold+cancel all / first 搬移 / 混合 / 8 线程 / close / 二次 settle） |
| 9 | 不再通过反射发现传输能力 | **未开始** | Phase 2：`Capabilities` 取代 `Bot:62 sender.supportsRecord()` 反射 |
| 10 | 领域层不依赖 Gson | **未开始** | Phase 2：`domain/**` 全部不可变 record + `DomainTypesTest`；现状 42/57 类引用 Gson |
| 11 | `Bot.java` 只承担路由与组合职责 | **未开始** | Phase 2 起逐步搬命令（目标 <2000 行，现 10,745） |
| 12 | 不泄露任何 API Key/Cookie/QQ 号/访问令牌 | **部分达成** | 已修：令牌不再全量入日志（只留前 4 位）、`.gitignore` 里字面量改 `<redacted>`、测试输出掩码；**待办**：`work/` 41 个脚本仍带旧令牌（轮换需用户批准 + 客户端同步） |
| 13 | `config.json`、`data/**` 与现有命令/API 保持兼容；最终交付 `ARCHITECTURE.md` | **进行中** | 数据/配置未改（隔离证据：`config.json` 42209B@10-14T14:37:41.849、`data/**` 10281 files/14,427,485,408B 前后一致）；`ARCHITECTURE.md` 已含边界/线程模型/状态机/持久化/关闭顺序/现状盘点，随阶段更新 |

## 里程碑
- **Phase 0**：基线（`F:\Bot-backup\baseline-20261018-021329` + zip）、`docs/{BASELINE,ARCHITECTURE,DEPENDENCIES,PHASE2-PLAN}.md`、§14 测试隔离（`IsolationTest` 86 checks，假服务 52210–52226 动态端口，生产 PID/config/data 前后一致）✅
- **Phase 1**：①WebAuth 69 ②DeepSeek transport（REVIEW 隔离/中断/重试分级）③生成队列（状态机 + 174）④白名单（9 组）✅ —— 详见 `docs/PHASE1-REPORT.md`
- **Phase 2**：进行中（领域类型 + 命令注册表骨架，先搬 `.help`/`.mode`/`.imgmode`，回执逐字不变）
- Phase 3–6：待开始