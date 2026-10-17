# 架构（逐步补齐 · Java 17 为权威实现）

> 本文随 Phase 0→6 逐段补齐。Java 是当前唯一权威实现；根目录 Next.js/TypeScript 与 `nextjs-wip` 为未完成迁移残留，不作依据。

## 1. 模块边界（目标包结构）
| 包 | 职责 | 现状 |
|---|---|---|
| `cn.szu.bot.runtime` | `BotRuntime` / `LifecycleManager` / `TaskSupervisor`（执行器集中创建、命名、关闭） | 待建 |
| `cn.szu.bot.application.command` | `CommandDispatcher` / `CommandRegistry` / `CommandContext` / `CommandResult` / `CommandHandler` | 待建 |
| `cn.szu.bot.application.chat` | `ChatOrchestrator` / `ConversationScheduler` / `ChatCommandExecutor` / `SelectionSessionService` | 待建 |
| `cn.szu.bot.application.generation` | `GenerationQueueService` / `GenerationJob` / `GenerationJobState` / `GenerationSnapshot` / `GenerationStatus` | 待建 |
| `cn.szu.bot.application.prompt` | `PromptService` / `InfixService` / `StyleService` / `FunctionService` / `PromptVocabularyService` | 待建 |
| `cn.szu.bot.application.lora` | `LoraService` / `CivitaiSearchService` / `LoraDownloadCoordinator` | 待建 |
| `cn.szu.bot.application.delivery` | `ImageDeliveryService` / `ReceiptService` / `MapDeliveryService` | 待建 |
| `cn.szu.bot.domain` | `ConversationId` / `UserId` / `Scope` / `PromptPair` / `SelectionList` / 错误类型 | 待建 |
| `cn.szu.bot.infrastructure` | `onebot` / `deepseek` / `sd` / `civitai` / `persistence` | 部分已有（`web/`、`sd/`、`civitai/`） |
| 组合根 | `Bot` 只做：接收标准化事件 → 建 `CommandContext` → 普通聊天交给 `ChatOrchestrator` → 指令交给 `CommandDispatcher` → 组合依赖与生命周期 | 现状：`Bot.java` ≈1.06 万行（待缩减） |

## 2. 线程模型（目标）
- `TaskSupervisor` 集中创建/命名/关闭所有执行器。
- 同一 `conversation` 严格保序；不同 conversation 并行（conversation 串行队列 / striped executor）。
- 有界共享池；队列满、模型超时、服务繁忙 → 向该会话发明确提示。
- 禁止在消息分发线程、Web 请求线程、聊天工作线程上做无上限 `get()/join()`。
- 多步骤聊天指令 = `CompletionStage` 状态机，带 deadline，取消沿调用链传播。
- 不用 `ThreadLocal` 表示业务成败/回执/工作流状态，改用显式上下文对象。
- 关闭顺序：停止接收 → 停止新任务 → 有限等待 → 取消 → 持久化 → 关闭传输。

## 3. 生成任务状态机（目标）
`QUEUED → RUNNING → (SUCCEEDED | FAILED | CANCELLED)`，`HELD ⇄ QUEUED`。
- 唯一状态转换入口；计数从状态推导或只由该入口修改。
- 不变量：`waiting/suspended/running/remaining ≥ 0`；任务只结算一次。

## 4. 持久化提交点（目标）
- 每类数据一个 Repository；跨文件操作用轻量 journal：`PREPARING → COMMITTED → CLEANED`，启动时恢复未完成事务。
- 生成/领取使用稳定 idempotency key；图片只有在 QQ ACK 成功后才从 outbox 删除。
- LoRA 下载：临时文件 → 校验 → 原子移动 → 失败清理半成品。
- `ChatLogStore` / `EventQueueStore` / `QuestIndex` 共享经过测试的追加日志、压缩、损坏恢复与保留策略。

## 5. 阶段记录
| Phase | 范围 | 状态 |
|---|---|---|
| 0 | 基线、依赖图、特征测试（不改行为） | **进行中** |
| 1 | WebAuth 限流、DeepSeek transport/中断、cancel all 计数、白名单语义 | 待开始 |
| 2 | 领域类型、`CommandContext`/`CommandResult`/`Capability`，`Bot` 作代理 | 待开始 |
| 3 | 提取 `GenerationQueueService`、`ImageDeliveryService`、`ChatOrchestrator` | 待开始 |
| 4 | 提取 Prompt/LoRA/Style/Function/Civitai 服务 | 待开始 |
| 5 | 拆分 `SdClient`、`DeepSeekPrompts`、`WebApiController` | 待开始 |
| 6 | 构建（Gradle/Maven wrapper、JUnit5）、文档、观测、遗留文件归档 | 待开始 |
## 6. 现状盘点（2026-10-18，供逐阶段对照）
### 6.1 体量
| 类 | 行数 | 主要职责（待拆出） |
|---|---|---|
| `Bot` | 10,745 | 消息解析、权限、编号选择、聊天规划、提示词、LoRA、Civitai、生成队列、图片发送、Web API、婚配、日志、生命周期 |
| `SdClient` | 2,890 | SD HTTP、参数、VAE、预设、中断、图片落盘 |
| `DeepSeekPrompts` | 2,589（已抽 `chat/deepseek` 8 类） | 传输、模板、规划、改写、检查、解析、重试、人格守卫 |
| `CivitaiClient` | 1,220 | 搜索/下载/清单 |
| `WebApiController` | 647 | 63 个 case 的单方法 switch |
| `Settings` | 656 | 配置读写（含新加的 XFF 信任读口） |

### 6.2 线程模型（拆 `TaskSupervisor` 的依据）
- **11 个执行器**，其中 7 个单线程：`Bot` 的 generation / progenIO / chatWorkflows / outboxDelivery / imageIO(2) / loraIO(1,队列 1) / webIO(4)；`CivitaiClient` scheduled(1)；`QqClient` 两个单线程；**`ChatService` 唯一全局单线程 + 无界队列**。
- **阻塞点**：`Bot:1126 completion.get(2 HOURS)`；无参 `get()` 永久阻塞 6 处（`Bot:3998/4012/7322`、`ChatService:318/322/358`）。
- **ThreadLocal 业务状态 6 处**（`Bot:623/625/7868/10328`、`Log:33`、`DeepSeekPrompts` 的改写存档出口）。
- 目标：会话保序 + 跨会话并行（striped/conversation queue）、有界池、忙/超时明确提示、`CompletionStage` 状态机 + deadline、显式上下文取代 ThreadLocal、统一关闭顺序。

### 6.3 持久化（拆 Repository + journal 的依据）
- `Json.atomicWrite` **53 处 / 20 文件**；跨文件操作无事务（LoRA 清单、展示图、outbox、样式、触发词）。
- 两份聊天空档并存：`ChatLogStore` 与 `WebApiController.chatFile`（`data/webui/<scope>-chat.json`）。
- 目标：每类数据一个 Repository、`PREPARING → COMMITTED → CLEANED` 轻量 journal、启动恢复、稳定 idempotency key、图片仅 QQ ACK 后从 outbox 删除。

### 6.4 测试与构建
- **89 个既有 `*Test`（main() 断言式）+ 本轮新增**：`WebAuthFilterTest`(69)、`DeepSeekTransportTest`、`GenerationQueueInvariantTest`(174)、`IsolationTest`(5 条)；**0 JUnit、无 wrapper**；`build.ps1 -Test` 逐类 `java -ea`。
- 隔离（第十四节）已落地：`testsupport/TestEnvironment`+`TestRuntime`、`--Dbot.test.mode/home/web.port/onebot.port/sd.port`、假服务全绑 127.0.0.1 动态端口、5 条隔离回归。

### 6.5 已知既有红灯（非本轮引入，需单独收敛）
| 用例 | 症状 | 根因 | 处置 |
|---|---|---|---|
| `GenerationQueueTest.atomicFifoQueueWithCapturedSettings` | `dimension snapshot: expected=768, actual=512` | 2026-10-07「参数与 Forge 独立」后该 09-27 用例仍假设页面回写会改尺寸 | 优先改用**机器人侧**设置入口保留 FIFO 快照语义；不可行才改期望并注明新契约（**禁止删断言/恒真**） |

### 6.6 生产部署冻结
- **8787 冻结在 Phase 0 之前的版本**（jar 02:19:03）；已用 `df7e606` 源码重建并与 jar 内 `WebAuthFilter/Bot/DeepSeekPrompts/Settings/WebApiController` **逐字节一致**（5/5）证明。
- 解冻条件：用户对具体版本明确批准；届时记录前后 jar 时间与 PID。