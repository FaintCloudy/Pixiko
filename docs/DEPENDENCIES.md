# 依赖图与拆分计划（Phase 0 交付物）

> 事实来源：2026-10-18 只读盘点（57 类 / 32,013 行）。**Java 是唯一权威实现**；根目录 Next.js/TypeScript 与 `nextjs-wip` 为未完成迁移残留。

## 1. 现状依赖图（朝向）
```
QqClient(OneBot)  ─┐
WebApiController ──┼─► Bot.java(10,495 行) ─┬─► ChatService(会话/限频/规划/执行/发送/Persona)
WebUiServer      ──┘   ▲                    ├─► DeepSeekPrompts(2,525) ─► HTTP(静态)
                       │                    ├─► SdClient(2,890) ─► SD WebUI
                       │                    ├─► CivitaiClient(1,220)
                       │                    ├─► PromptEditor / PromptUsage / TermCategories
                       │                    ├─► LocalStyles / GenerationPreset
                       │                    ├─► ChatLogStore / EventQueueStore / QuestIndex
                       │                    └─► Settings（config.json 读写）
                       └── 直接持有 11 个执行器 + 业务状态集合
```
问题：`Bot` 同时是**消息解析器、权限、编号选择、聊天规划、提示词、LoRA、Civitai、生成队列、图片发送、Web API、婚配、日志、生命周期**；**Gson 污染 42/57 类**（`accept(JsonObject)`、`conversationKey(JsonObject)`、`speaker(...)→JsonObject`）；`ChatLogStore` 与 `WebApiController` **两套聊天空档**并存。

## 2. 目标分层与拆出的类
| 层 | 类 | 从哪拆 |
|---|---|---|
| `runtime` | `BotRuntime`、`LifecycleManager`、`TaskSupervisor` | Bot 的 11 个执行器 + close 序列 |
| `application.command` | `CommandDispatcher`、`CommandRegistry`、`CommandContext`、`CommandResult`、`CommandHandler` | Bot.dispatch 的巨型 switch（1,519 起）+ requireOwner/requireStaff |
| `application.chat` | `ChatOrchestrator`、`ConversationScheduler`、`ChatCommandExecutor`、`SelectionSessionService` | ChatService + Bot.runChatCommands（`get(2 HOURS)`）+ resolveShownSelection |
| `application.generation` | `GenerationQueueService`、`GenerationJob`、`GenerationJobState`、`GenerationSnapshot`、`GenerationStatus` | Bot 的 generation 区段（6,790–7,260） |
| `application.prompt` | `PromptService`、`InfixService`、`StyleService`、`FunctionService`、`PromptVocabularyService` | Bot 的 infix/prompt 分支 + PromptEditor/PromptUsage/TermCategories |
| `application.lora` | `LoraService`、`CivitaiSearchService`、`LoraDownloadCoordinator` | Bot 的 lora 分支 + CivitaiClient |
| `application.delivery` | `ImageDeliveryService`、`ReceiptService`、`MapDeliveryService` | Bot 的 outboxDelivery/sendBatch/回执 + ImageOutbox |
| `domain` | `ConversationId`、`UserId`、`Scope`、`PromptPair`、`SelectionList`、错误类型 | 各处字符串/JsonObject 参数 |
| `infrastructure` | `onebot`、`deepseek`、`sd`、`civitai`、`persistence` | 现有 `qq/`、`sd/`、`civitai/`、`web/` + 新抽 `chat/deepseek/` |
| 组合根 | `Bot`（只剩：接事件 → 建 `CommandContext` → 聊天交 `ChatOrchestrator` → 指令交 `CommandDispatcher` → 组合依赖/生命周期） | 最终目标 <2,000 行 |

## 3. Phase 0 / Phase 1 文件清单
### Phase 0（不改行为）
| 文件 | 动作 | 状态 |
|---|---|---|
| `docs/BASELINE.md` | 新增（基线/回退/纪律） | ✅ |
| `docs/ARCHITECTURE.md` | 新增（边界/线程/状态机/持久化/关闭） | ✅ |
| `docs/DEPENDENCIES.md` | 新增（本文件） | ✅ |
| `.gitignore` | 追加忽略（只增） | ✅ |
| `docs/TEST-ISOLATION.md` + `testsupport/TestEnvironment` + `testsupport/TestRuntime` | 新增（§14 隔离） | 进行中 |
| `GenerationQueueInvariantTest.java` | 新增（**先钉当前 bug 行为**） | ✅ 已落盘 |
| `WhitelistSemanticsTest.java` | 新增（先钉当前行为） | 待写 |
| `build.ps1` | 注入隔离参数（`bot.test.mode/home/web.port/onebot.port/sd.port`） | 进行中 |

### Phase 1（每项独立改动 + 独立验证）
| # | 文件 | 状态 |
|---|---|---|
| 1 | `web/WebAuthFilter.java`（+ `WebAuthFilterTest.java`）、`Settings.java`（令牌日志） | 已改，测试已落盘，等验收 |
| 2 | `chat/DeepSeekPrompts.java` + 新 `chat/deepseek/{DeepSeekTransport,HttpDeepSeekTransport,DeepSeekRequest,DeepSeekResponse,DeepSeekCall,DeepSeekChannel,RetryPolicy,DeepSeekError}.java` | 已落盘，测试待落 |
| 3 | `Bot.java`(generation) + `application/generation/{GenerationJobState,GenerationSnapshot}.java` | 进行中 |
| 4 | `Bot.java`(accept/dispatch) + `Settings.java`(白名单写入口) + `application/command/WhitelistCommand.java` | 待开始（**等 3 完成后再动 Bot.java**，保证单写者） |

## 4. 计划新增的测试
| 测试类 | 覆盖 |
|---|---|
| `WebAuthFilterTest` | 同客户端第 10 次失败受限、不同客户端隔离、TTL 过期恢复、成功清理、20 线程并发精确、容量上限淘汰、XFF 默认不信任 |
| `DeepSeekTransportTest` | REVIEW 走独立 transport、取消不重试且**保留中断标记**、不可重试错误只调一次（JSON/401/402/400/404）、可重试错误按策略退避且有上限、尝试数/总时长封顶 |
| `GenerationQueueInvariantTest` | 先钉当前 bug（`等待 -1`、hold+cancel all 双扣、first 使 suspended 虚高）→ 修后改为非负契约 + 混合场景 + 8 线程并发 + close 取消 + 只结算一次 |
| `WhitelistSemanticsTest` | 群聊/私聊/普通聊天/手工指令/聊天自动执行指令 5 组；白名单关闭=全部可聊可用公共指令；开启=**仍可聊**、仅成员可执行指令；`webui` 事件不被拦；owner 隐式豁免；`.whitelist toggle/list/add/remove` 仅 owner |
| `TestIsolationTest` | 生产端口 PID 前后不变、生产 `config.json`/`data/**` 哈希与时间戳不变、两套测试并行不冲突、异常退出无残留端口/子进程、主进程持 `bot.lock` 时测试全通过 |

## 5. 阶段顺序（当前）
0 →（1：①②③④）→ 2 → 3 → 4 → 5 → 6。每阶段：先特征测试 → 单项改动 → `build.ps1 -Test` 全量 → 红灯先修不推进。