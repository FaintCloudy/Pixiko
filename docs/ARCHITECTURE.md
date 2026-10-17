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