# Phase 1 报告（WebAuth / DeepSeek / 生成队列；④白名单待补）

> 生产 8787 **全程未部署**（冻结在 `df7e606`，jar 02:19:03；已用同源码重建得到 5/5 个 class 字节一致）。本阶段所有验证都在**隔离实例/纯单元**上完成。

## 1. 修改文件与行为变化
| # | 文件 | 行为变化 |
|---|---|---|
| ① | `web/WebAuthFilter.java`、`Settings.java`（+只在日志打令牌前 4 位、新增 `webui.trust_forwarded_for`/`webui.trusted_proxies` 只读键）、新增 `WebAuthFilterTest` | 失败计数**按客户端地址**（`getRemoteAddr`）；XFF 仅显式开启+受信白名单时采信（头>256 字符/跳>8/非法地址 → 整条不信）；不可变 `FailState` + `compute` 原子累加；窗口 15 分钟、连续 10 次失败后 30 秒冷却、表上限 4096 按最旧淘汰、成功即清；**日志不再出现完整令牌**。既有阈值语义与状态码不变 |
| ② | `chat/DeepSeekPrompts.java`、新增 `chat/deepseek/{DeepSeekChannel,DeepSeekTransport,DeepSeekRequest,DeepSeekResponse,DeepSeekError,RetryPolicy,DeepSeekCall,HttpDeepSeekTransport}.java`、新增 `DeepSeekTransportTest` | **REVIEW 通道真正独立**（删掉 `exchange(body,HttpClient)` 无效参数路径；`REVIEW → reviewTransport`，生产为两个不同 `HttpClient`）；**中断标记不再被清除**（带标记→0 次请求直接 CANCELLED；`InterruptedException`→恢复标记且不重试）；重试只覆盖 连接错误/超时/**408/429/500/502/503/504**，**401/402/JSON/业务错误不重试**；3 次尝试、300ms 起指数翻倍、单次封顶 3s、抖动 ±25%、总时长 20s。公开签名与 JSON 契约不变 |
| ③ | `Bot.java`（generation 区段）、新增 `application/generation/{GenerationJobState,GenerationSnapshot}.java`、新增 `GenerationQueueInvariantTest` | `transition()` 成**唯一状态转换入口与计数点**（HEAD 里 11 处 16 条赋值 → 4 条）；`removeFromQueue()` 唯一出队；`generationSnapshot()` **非负不变量**（违规记日志并抛 `IllegalStateException`，不 clamp）；`settle` 幂等守卫；挂起任务 `first` 做 `HELD→QUEUED` 计数搬移；`/api/tasks` 格式逐字未变（唯一取值变化：已取消的在飞任务不再同时报 `suspended:true`） |
| — | `GenerationQueueTest`（**既有红灯**） | 走方案 A：用例改用**机器人侧**参数入口（`client.setSize/setSampler/setStyles`）拍快照，恢复 FIFO 快照语义；`promptReads` 绝对计数→提交前后增量。**原断言原文未动** |

## 2. 测试结果（我复跑，隔离 root + 桩，未连 8787/3001/7860/7861）
| 套件 | 结果 |
|---|---|
| `WebAuthFilterTest` | **exit 0 / 69 assertions** |
| `DeepSeekTransportTest` | exit 0 |
| `DeepSeekPromptsTest`（回归） | exit 0 |
| `DeepSeekChannelsTest`（装配回归） | exit 0 / 22 |
| `InfixLogArchiveTest`（review 计数回归） | exit 0 / 44 |
| `GenerationQueueInvariantTest` | **exit 0 / 174 assertions** |
| `GenerationQueueTest`（既有红灯已修） | **exit 0 / 344 assertions** |
| `IsolationTest`（§14 隔离回归） | exit 0（5 条 PASS，见日志：生产 PID 不变、生产 config/data 指纹不变、并行端口不冲突、异常退出无残留） |
| 全量测试源码编译 | exit 0（106 源） |

## 3. 剩余风险 / 未做
1. **DeepSeek 中断标记卫生（交给 Bot 侧，未改）**：传输层不再清标记；若 Bot 有"取消/`shutdownNow` 打断过的线程又被拿去发 DeepSeek 请求"的路径，会在该线程上如实按"已取消"失败 → Phase 3 引入 `TaskSupervisor`/显式上下文时必须一并解决。
2. **重试叠加**：HTTP 层对 429/5xx 重试到 3 次，规划层 `retryableChatFailure` 仍允许再重试（极端 4×3 次，受 20s/次预算约束）→ Phase 2 权限/调度统一时收敛。
3. **令牌轮换未做**：`work/` 下 41 个脚本仍含已泄露的旧令牌；`.gitignore` 里的字面量已替换为 `<redacted>`（`c9e102e`）。轮换需**用户批准 + 客户端同步更新**（上次我擅自轮换导致用户进不去，已恢复原状）。
4. **生产部署冻结**：Phase 1 的代码**尚未**上 8787（按用户指令），需用户对具体版本批准后才做一次"停→构建→起"，并记录前后 jar 时间与 PID。

## 4. 下一步
- 补完 ④ 白名单（`.whitelist toggle/list/add/remove`、聊天不被拦、网页事件豁免、owner 豁免、持久化、5 组测试）→ **Phase 1 收口**；
- 进 Phase 2（`docs/PHASE2-PLAN.md`）：领域类型 + `CommandRegistry`/`CommandDispatcher` + `Capability` 取代反射，`Bot` 作代理，每次只搬 3–5 条命令并逐字 pin 回执。