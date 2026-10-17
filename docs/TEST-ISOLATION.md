# 测试环境隔离（TEST ISOLATION）

> 适用版本：Pixiko（Java 17）。本文是 **Phase 0 测试隔离**的契约：测试怎么跑、能碰什么、不能碰什么、
> 违规时谁负责失败。实现落在 `build.ps1 -Test`、`src/test/java/cn/szu/bot/testsupport/`
> 与 `IsolationTest`；生产侧只多了两处**守卫**（`Main` 启动检查、`WebUiServer` 的可选端口参数），
> 业务逻辑与 `Bot` 的行为一个字都没改。

---

## 1. 铁律（不可协商）

| # | 铁律 | 落点 |
|---|---|---|
| 1 | 测试**不连、不监听**生产端口 `8787`（WebUI）、`3001`（NapCat）、`7860`、`7861`（SD/其它）以及 `config.json` 里配置的任何真实端口 | `TestEnvironment.PRODUCTION_PORTS` + `assertNotProductionPort` + `claimPort` + 配置生成时扫描 `:`+端口 |
| 2 | 测试**不读、不写**生产数据：`F:\Bot\config.json`、`data/**`、`logs/**`、`maps/**`、正在运行的 `build\pixiko.jar`、真实 LoRA / 模型 / 生成图目录 | 所有路径从 `TestEnvironment.home()` 派生；`assertIsolatedRoot` 硬失败；`requireInside` 运行时兜底 |
| 3 | 每个测试**进程**一个独立临时根 `F:\Bot\work\tests\<run-id>\`，里面有自己的 `config.json`、`data/`、`logs/`、`maps/`、`generated/`、`lora/`、`bot.lock` | `build.ps1 -Test` 逐类 New-TestProcessRoot；`TestEnvironment.createLayout()` |
| 4 | 所有假服务（HTTP / WebSocket / 假 OneBot / 假 SD / 假 DeepSeek / 假 Civitai）**只绑 `127.0.0.1`** | `FakeHttpService`（`InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)`）、`FakeOneBot`（`new ServerSocket(0, 16, 127.0.0.1)`） |
| 5 | **动态端口**：绑 `0`，启动后从实际监听地址读端口，再注入测试配置 | `FakeHttpService.port()`、`FakeOneBot.port()`、`WebUiServer#actualPort()`（Spring `server.port=0`） |
| 6 | **禁止**"先探测空闲端口 → 关掉 → 再启动" | 新代码没有 `freePort()`；`WebUiServer(settings, bot, 0)` 直接绑 0 |
| 7 | 并行测试**不共享**端口 / 临时目录 / 锁文件 / 静态缓存 / 系统属性；每个实例一个 **UUID run-id** | `-Dbot.test.run.id=<uuid>--<Class>`；`IsolationProbe` 每套一个 run-id |
| 8 | 自动化测试**不发**真实 NapCat 消息、**不调**真实 SD / DeepSeek / Civitai | 假 OneBot WebSocket、假 SD HTTP、注入 DeepSeek / Civitai transport |
| 9 | 不停止 / 不重启 / 不挂起 / 不查杀任何别人的进程 | 测试只关自己 `register(...)` 的资源；从不调用 `ProcessHandle.destroy*`（除了自己 `new ProcessBuilder` 起的子进程） |
| 10 | 不读写 `F:\Bot\config.json`、`data/**`、`logs/**`、`maps/**`、`build/pixiko.jar` | 见第 2 条；`-Test` **不再重打** `build\pixiko.jar` |
| 11 | 不打印 key / cookie / token / QQ 号 | 测试配置里全是 `offline-test-token` 这类假值；生产指纹只算元数据 |

> 说明：本隔离任务**只读**生产 `config.json` / `data/**` 的**元数据**（路径、大小、修改时间）用于
> "前后一致"证据，**从不打开、从不读取文件内容**，因此不可能把 key / cookie / token 带进测试进程或日志。

---

## 2. 目录与属性约定

### 2.1 目录布局

```
F:\Bot\
  build.ps1                      # -Test = 隔离跑测试（兼容入口，参数不变）
  work\
    tests\
      <run-id>\                  # 一次 build.ps1 -Test 的根（结束时整棵删除）
        <TestClass>\             # 一个测试 *进程* 的独立根（第 3 条）
          .pixiko-test-root      # 标记：生产 Main 见到它就拒绝启动（第 11 条）
          bot.lock               # 本进程的锁（内容 = pid），与生产 data\bot.lock 无关
          config.json            # 测试专用配置（端口全部 127.0.0.1 动态端口）
          data\  logs\  maps\  generated\  lora\  work\
```

`data\bot.lock` 也会写一份（内容同上），因为生产代码读的是 `data/bot.lock`；测试用的
`bot.lock` / `data\bot.lock` 都在本次 run 自己的根里，跟生产 `F:\Bot\data\bot.lock` 无关。

### 2.2 系统属性

| 属性 | 含义 | 默认 |
|---|---|---|
| `-Dbot.test.mode=true` | 测试模式开关。生产 `Main` 见到它直接拒绝启动 | 无 |
| `-Dbot.test.home=<dir>` | 本次 run 的临时根 | 不传则 `work\tests\<uuid>` |
| `-Dbot.test.work=<dir>` | **兼容**：既有 89 个测试读的老属性 | 被设成 `<home>\work` |
| `-Dbot.test.run.id=<uuid>` | run-id（`build.ps1` 用 `<uuid>--<TestClass>`，一个进程一个） | 生成 UUID |
| `-Dbot.test.production.root=F:\Bot` | 生产根，**只**用于断言"我们不是它" | 当前工作目录 |
| `-Dbot.test.web.port=0` | 假 WebUI 期望端口；`0` = 操作系统分配 | `0` |
| `-Dbot.test.onebot.port=0` | 假 OneBot 期望端口 | `0` |
| `-Dbot.test.sd.port=0` | 假 SD 期望端口 | `0` |

正数端口也支持（排障时想固定端口用），但**任何生产端口都会让测试立即失败**。

### 2.3 兼容性（第 10 条）

* `build.ps1 -Test` / `test.bat` 仍是入口，`-Test`、`-CompileTestsOnly`、`-OutputJar` 三个既有参数语义不变；
  新增可选 `-Only <通配符>`（只跑匹配的测试类，例：`-Only Isolation*`）。
* 既有 89 个测试**不用改**：它们读 `bot.test.work`（默认 `work`），现在被指到 `<home>\work`，
  于是自动落进本次 run 的临时目录。
* `-Test` / `-CompileTestsOnly` **不再重打** `build\pixiko.jar`：测试本来就用 `build\classes`
  （且 `build\classes` 现在排在 classpath 最前面，避免拿到过期的 jar），生产实例正在用那个 jar 时
  也能跑测试。正常构建（不带 `-Test`）行为不变。

---

## 3. 工具类

### 3.1 `TestEnvironment`（`cn.szu.bot.testsupport`）

* `create()` / `createAt(home)`：UUID run-id + 临时根 + 目录布局 + `config.json` + `bot.lock`；
  同时把 `bot.test.home` / `bot.test.work` / `bot.test.run.id` / `bot.test.production.root` 回写到系统属性，
  保证同一个进程里后续代码看到的是同一套值。
* `assertIsolatedRoot(home, production)`（第 11 条，**硬失败**）：
  * 根不能等于生产根；
  * 根不能落在 `config.json` / `data` / `logs` / `maps` / `build`（含 `build/pixiko.jar`）里；
  * 根必须在 `<生产根>\work\tests` 之下，且不能就是 `work\tests` 本身；
  * 生产根里若出现 `.pixiko-test-root` 标记，直接失败。
* `assertNotProductionPort` / `claimPort` / `requestedPort`：端口白名单与审计（第 1 条）。
* `productionFingerprint` / `productionStructureFingerprint` / `productionConfigStamp`：生产数据
  "前后一致"证据，**只读元数据**。
* `processSnapshot()` / `productionPixikoPids()`：java / node / python 进程集合与生产 Pixiko PID（第 a/e 条）。
* `cleanup()`：**先**反序关自己的资源，**再** `TestCleanup.awaitQuiet + deleteQuietly` 删掉自己的 run 根；
  幂等，且注册为 JVM shutdown hook（正常 / 异常 / 中断都跑，第 12 条）。

### 3.2 `TestRuntime`（`cn.szu.bot.testsupport`）

| 假服务 | 类型 | 绑定 | 端口来源 |
|---|---|---|---|
| `oneBot()` | 假 OneBot / NapCat（RFC 6455 WebSocket 服务端） | `127.0.0.1` | `new ServerSocket(0, 16, 127.0.0.1)` → `port()` |
| `sd()` | 假 SD WebUI HTTP API（`/sdapi/v1/*`、`/internal/ping`） | `127.0.0.1` | `HttpServer` bind 0 → `port()` |
| `deepSeek()` | 假 DeepSeek（`POST /chat/completions`） | `127.0.0.1` | 同上 |
| `civitai()` | 假 Civitai（`GET /api/v1/models*`） | `127.0.0.1` | 同上 |
| `deepSeekTransport()` | 假 DeepSeek **transport**：请求真打到本机假服务 | 走上面的 127.0.0.1 | — |
| `civitaiTransport()` | 假 Civitai **transport**：`https://civitai.com/...` 的 URI 被转到本机假站点 | 走上面的 127.0.0.1 | — |
| `civitaiResolver()` | 假 DNS：解析成公网地址（`CivitaiClient` 拒绝私有地址），但请求从不出网 | — | — |

`TestRuntime.config()` 生成整份测试配置：`qq.ws_url` → 假 OneBot、`sd.base_url` → 假 SD、
`progen.api_base` / `chat_api.api_base` → 假 DeepSeek、`civitai.base_url` 保持 `https://civitai.com`
（`CivitaiClient` 要求 https 且端口只能是 443；真实流量由注入的 transport 转到本机）、
`webui.host=127.0.0.1` 且 `webui.port=0`、`civitai.lora_dir` / `sd.root` 指向本次 run 的 `lora\`。

**为什么 Civitai 不直接写 `http://127.0.0.1:port`**：`CivitaiClient` 构造函数硬性要求 `base_url`
是 https 且端口为 443，所以配置层保留一个不会被真的访问的 https 地址，实际 IO 全部由
`civitaiTransport()` 转到 `127.0.0.1` 的假站点——仍然是"只绑回环"的假服务。

### 3.3 假 WebUI（Spring）

`WebUiServer` 新增了一个**测试用**构造器：

```java
new WebUiServer(settings, bot, 0)   // 0 = 由操作系统分配，start() 之后用 actualPort() 读实际端口
```

生产只走两参构造器，端口照旧来自 `config.json` 的 `webui.port`。`Settings#webPort()` 会把配置值夹到
`1..65535`（所以配置里写 0 会被当成 1），这正是测试必须走构造器参数、而**不能**靠"探测空闲端口"的原因。

---

## 4. 守卫（第 11 条）

| 位置 | 检查 | 违规后果 |
|---|---|---|
| `Main.guardAgainstTestRoot(root)`（在生产 `seedFirstRunConfig` / `Settings` **之前**） | 带 `bot.test.mode` / `bot.test.home` / `bot.test.run.id` 的 JVM；或根目录里有 `.pixiko-test-root` | `IllegalStateException`，**不加载**生产 `config.json`，进程退出 |
| `TestEnvironment.assertIsolatedRoot` | 根落在 `config.json` / `data` / `logs` / `maps` / `build` / 生产根本身；不在 `work\tests` 下；生产根被污染 | `IllegalStateException`，测试**直接失败**（不是警告） |
| `TestEnvironment.assertNotProductionPort` | 端口 ∈ {8787, 3001, 7860, 7861} | `IllegalStateException` |
| `TestEnvironment.writeConfig` | 生成的配置文本里出现 `:8787` / `:3001` / `:7860` / `:7861` | `IllegalStateException` |

生产正常启动（`run.bat` / `start.bat`，不带任何 `-D` 参数、根目录是 `F:\Bot`）时两个条件都不成立，
行为与改动前完全一致。

---

## 5. 隔离性回归测试：`cn.szu.bot.IsolationTest`

单独跑（不需要全量门）：

```powershell
java -ea -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 `
     -Dbot.test.mode=true -Dbot.test.production.root=F:\Bot `
     -cp "build\classes;build\test-classes;lib\gson-2.13.1.jar;lib\spring\*" `
     cn.szu.bot.IsolationTest
```

| 用例 | 断言 |
|---|---|
| **a** 生产端口 | 四个假服务端口互不相同、全部 `>1024`、**都不是生产端口**；每个端口在 `127.0.0.1` 上可连；生成的测试配置里没有 `:8787/:3001/:7860/:7861`，也没有 `api.deepseek.com` / `civitai.com/api`；假 OneBot 一来一回送达 `QqClient`；假 SD 返回模型/样式；假 DeepSeek 返回固定内容；假 Civitai 返回空搜索结果；Spring 绑 0 后读 `actualPort()` 并确认可连；**测试前后 java/node/python 进程集合一致**（生产端口监听者 PID 不变） |
| **b** 生产数据 | 生产 `config.json` 大小 + 修改时间前后一致；`data/**` 的"路径+大小"结构指纹前后一致；带 mtime 的指纹若漂移只提示（来自正在运行的生产实例）；本次 run 没往临时根之外写任何文件 |
| **c** 并行 | 两个子 JVM 各一个 run-id 并行跑完（exit 0），四类端口互不相同、都不是生产端口，临时目录互不相同且各自删干净 |
| **d** 异常退出 | 子进程绑端口后 `Runtime.halt(9)` 强杀：端口能被立刻重新绑定、进程确实消失、残留目录被清理 |
| **e** 主实例在跑 | 主 Pixiko（`bot.home=F:\Bot` + `pixiko.jar`）全程在跑且 PID 不变；本 run 用自己的 `bot.lock`（内容 = 本进程 PID），不碰生产 `data/bot.lock` |

> **a 为什么不 `connect` 生产端口**：连一下本身就是违规（第 1 条）。所以"监听者没变"用**进程集合
> + 生产 Pixiko PID** 证明，端口侧用**本次 run 自身的端口审计**证明——测试期真正打开过的每个端口都在
> `TestEnvironment.endpointAudit()` 里，全部是 `127.0.0.1:<高位端口>`。

---

## 6. 证据怎么采

```powershell
# 1) 生产端口的监听者（只查，不连）：测试前后各一次
Get-NetTCPConnection -State Listen | Where-Object LocalPort -in 8787,3001,7860,7861 |
    Select-Object LocalAddress,LocalPort,OwningProcess

# 2) 生产进程 PID（只查，不杀）
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Select-Object ProcessId,CreationDate,CommandLine

# 3) 隔离测试自己打印的端口（每个假服务一行 ISOLATION PORTS）
# 4) IsolationTest 打印的 PASS/NOTE 行：生产 config 时间戳、data 结构指纹、生产 PID
```

---

## 7. 已知取舍 / 后续（如实记录）

1. **既有用例仍有几处直接读生产数据（只读）**：
   * `InfixBackfillTest`、`CompoundCategoryTest`、`ChainEvalTest` 用 `Path.of(".")`（= `F:\Bot`）取
     生产中文词库（`data/prompt-zh-tags.json`），是有意为之的"真实词库"回归；
   * `AppUpdateEvidenceTest` 从 `F:\Bot` 硬链接真实 APK / `android/app/build.gradle` 做证据；
   * `PromptUsageTest` 原先用 `bot.test.work` 的父目录当生产根——隔离模式下父目录是本次 run 的根，
     那段"真实数据"分支会自动跳过（不会失败，但也不再校验真实数据）。
   这些**只读**、不改生产，但确实违反第 2 条的字面要求；彻底解决（给它们喂隔离 fixture）属于 Phase 1，
   本次 Phase 0 先把"新测试天生隔离 + 生产数据零改动"这条线立住。
2. **生产指纹不读文件内容**（第 11 条铁律），所以"哈希"是**元数据指纹**（路径 + 大小 [+ 修改时间]），
   不是 `config.json` 的内容 SHA-256。`data/**` 的结构指纹同样只含路径与大小。
3. **`Settings#webPort()` 把 0 夹成 1**（生产逻辑），测试要动态端口必须走 `WebUiServer(..., 0)`；
   配置里写 `"port": 0` 只对"测试根"内部有意义。
4. **`build.ps1 -Test` 不再重打 jar**：`-Test` 以前会顺带刷新 `build\pixiko.jar`，现在不刷新
   （为了让生产实例占用 jar 时也能跑测试）。发布构建请单独跑不带 `-Test` 的 `build.ps1`。
5. **Ctrl+C 中断**：PowerShell 的 `finally` 在硬中断时可能不执行；Java 侧有 shutdown hook 兜底
   （关资源 + 删 run 根），但若 PowerShell 被强杀，`work\tests\<run-id>` 可能残留，可手工删除。
6. **`-Dbot.test.*` 属性是"请求"而不是"命令"**：假服务端口由 `TestRuntime` 自己绑 0 决定，
   属性给正数只是让它换一个（仍然校验不是生产端口）。
