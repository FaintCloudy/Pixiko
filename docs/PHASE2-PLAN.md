# Phase 2 计划：领域类型 + 命令层（Bot 作代理，不改行为）

> 目标：让 `Bot` 从"什么都干"变成"组合根 + 适配器"，先**引入类型与注册表**并让既有实现挂在后面（零行为变化），再逐个搬命令。
> 原则：每步都可回退；每步只动一个子系统；每步跑 `build.ps1 -Test`（已自动带测试隔离参数）。

## 1. 要引入的类型（不可变 record，禁止 Gson）
| 类型 | 字段（草案） | 替换/收敛什么 |
|---|---|---|
| `domain.ConversationId` | `Scope scope, String id`（`Scope = GROUP / PRIVATE / WEB / DEVICE`） | 现在到处传 `message_type`/`group_id`/`user_id` 字符串 |
| `domain.UserId` | `String value`（+ `isOwner()/isStaff()` 的**判定在权限服务**，不在值对象） | `event.user_id` 字符串比较散落 |
| `domain.PromptPair` | `String positive, String negative` | `Result`/`PromptPair` 两处近似结构 |
| `domain.SelectionList` | `List<Item> items, String header, int revision` | `shownLists`/`resolveShownSelection` 的编号选择 |
| `domain.MessageEvent` | `ConversationId conversation, UserId sender, String text, boolean fromWeb, List<ImageRef> images` | **唯一允许**由 OneBot/Web 适配器构造；`accept(JsonObject)` 内部立刻转成它 |
| `domain.CommandError` | `Code code(USAGE/FORBIDDEN/NOT_FOUND/EXTERNAL/TIMEOUT), String message` | 现在靠抛 `IllegalStateException`+文案 |
| `command.CommandContext` | `MessageEvent event, List<String> args, String raw, CommandPermissions perms, ReplySink reply, Clock clock` | 现在各分支各自读 `event`/`settings` |
| `command.CommandResult` | `String text, ReceiptRank rank, List<ImageRef> images, boolean async, CommandError error` | 统一 `sendBatch`/回执窗口/失败语义 |
| `command.CommandHandler` | `CommandResult handle(CommandContext) throws CommandError` | 取代巨型 switch 的每个 case |
| `command.CommandRegistry` | `Map<String, Entry>`，`Entry = handler + 权限(ANY/WHITELIST/STAFF/OWNER) + 用法` | **权限/用法/别名只登记一处** |
| `capability.Capabilities` | `EnumSet<Cap> of(Sender)`（`RECORD`/`IMAGE`/`FORWARD`/`EDIT`） | 取代 `Sender.supportsRecord()` 的**反射**探测（`Bot:62`） |

## 2. 迁移步骤（strangler，零行为变化优先）
1. **加类型 + 注册表骨架**（不改任何行为）：`MessageEvent` 在 `accept()` 里由 `JsonObject` 转换；`CommandRegistry` 先**只登记 3 条最简单的命令**（`.help`、`.mode`、`.imgmode`），handler 内部**直接调用现有 Bot 方法**并包装成 `CommandResult`；其余命令**仍走原 switch**（注册表未命中 → 原路径）。
2. **权限集中**：把 `requireOwner/requireStaff/白名单` 的调用从各 case 提到 `CommandDispatcher`（按 `Entry.权限` 决定），**同一套判定**服务 QQ/网页/聊天计划。
3. **Capability 取代反射**：`Sender` 增加显式能力集合（默认实现按旧反射结果初始化，避免行为变化），删掉 `getMethod("sendRecord"…)`。
4. **逐个搬命令**（每批 3–5 条，按"低风险 → 高风险"）：编号选择 → 提示词类 → SD 参数类 → LoRA/Civitai 类 → 生成类。每批都补特征测试（输入/回执/副作用逐字 pin）。
5. **收尾**：`Bot.dispatch` 只剩"查表 → 构造 context → 调 handler → 统一回执"；`Bot` 目标 < 2,000 行。

## 3. Phase 2 的文件清单（预计）
新增：`src/main/java/cn/szu/bot/domain/{ConversationId,UserId,Scope,PromptPair,SelectionList,MessageEvent,CommandError}.java`
新增：`src/main/java/cn/szu/bot/application/command/{CommandContext,CommandResult,CommandHandler,CommandRegistry,CommandDispatcher,ReplySink,ReceiptRank}.java`
新增：`src/main/java/cn/szu/bot/capability/Capabilities.java`
修改：`Bot.java`（accept 转换 + dispatch 查表 + 权限上提）、`Sender`/`QqSender`（能力集合）、`Settings`（权限查询收敛）
测试：`DomainTypesTest`、`CommandRegistryTest`（注册表/权限/别名/用法）、`CommandDispatcherTest`（白名单×网页×聊天计划三条路径同判据）、`CapabilitiesTest`（不再反射）

## 4. Phase 2 的验收（不通过不进 Phase 3）
- 既有 89+ 测试全绿；新增 4 个套件全绿；
- `.help`/`.mode`/`.imgmode` 三条搬到注册表后，**回执逐字不变**（特征测试 pin）；
- 权限判定**只剩一处**（`CommandDispatcher`），QQ/网页/聊天计划三条入口共用；
- `Sender.supportsRecord()` 反射**已删除**，改由能力集合决定；
- `Bot.java` 行数下降（记录前后）；
- 生产 8787 **仍未部署**（保持冻结，等用户批准）。