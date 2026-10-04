# Pixiko —— QQ + Stable Diffusion 出图机器人

Pixiko 是一个自用的 QQ 机器人：接 **NapCat** 收消息，接 **Stable Diffusion WebUI** 出图，
用 **DeepSeek** 做提示词改写与日常聊天。群友用 `.` 开头的指令排队生成、管理提示词与 LoRA、
领图；机器人自己把生成好的图片发回对话。

- **作者**：loriko（deloriko@outlook.com）
- **版本**：v1.4.1（发行说明见 [`RELEASE.md`](RELEASE.md)）
- **版本号规则**：**大改动更新中间位**（`1.1.x` → `1.2.0`），**小修小补更新最后一位**（`1.1.1` → `1.1.2`）
- **当前实现**：**Java 版**（`src/`）——这是线上一直在跑的那一份
- **网页控制台**：`webui/`（纯静态 HTML/CSS/JS，随机器人一起由内嵌 Spring Boot 提供）
- **Android 客户端**：`android/`（**v1.4.0 起**；原生外壳 + WebView 承载完整控制台，见 [`android/README.md`](android/README.md)）
- **SD WebUI 桥接扩展**：`webui-extension/pixiko-bridge/`（把文生图页正在编辑的提示词同步给机器人）
- **另有一次未完成的 Next.js 重构**：见 [`nextjs-wip/`](nextjs-wip/README.md)，**不可运行，勿用于生产**

---

## 一、它是什么

三块是独立的，任意一块缺失都只影响对应的功能：

| 模块 | 依赖 | 缺了会怎样 |
|---|---|---|
| QQ 侧 | NapCat（OneBot v11 正向 WebSocket） | 收不到消息，但网页控制台仍可用 |
| 出图侧 | SD WebUI（必须带 `--api`） | 不能生成图片，提示词/样式/队列管理仍可用 |
| 聊天/改写 | DeepSeek API Key | `.infix`/`.progen`/日常聊天不可用，其余指令照常 |

生成完成的图片由机器人主动发回原对话，不需要手动去目录里翻。

---

## 二、环境要求

| 项目 | 要求 |
|---|---|
| 操作系统 | Windows 10 / 11 |
| 脚本宿主 | Windows PowerShell 5.1（系统自带；`build.ps1`/`run.bat` 按 5.1 写，**文件保持 ASCII**） |
| JDK | **JDK 17 或更高**，`javac` 与 `jar` 必须在 `PATH` 里（构建用 `javac --release 17`） |
| Stable Diffusion | **A1111 或 Forge / Forge Neo**，默认地址 `http://127.0.0.1:7860`；A1111 启动参数必须含 `--api`（Forge 默认开）。Forge 的「底模 + VAE + 文本编码器」按**预设栈**分，用 `.model preset` 或网页「模型预设」切换，别只换底模 |
| NapCat | 可选。开启**正向 WebSocket 服务器**，把端口与 Token 填进配置（默认 `ws://127.0.0.1:3001`） |
| DeepSeek API Key | **两条独立通道各一个**：生图频道（`progen`）与聊天频道（`chat_api`） |
| Node.js | **可选**，只有自检脚本与中文词库工具需要（`node tools\webui-selfcheck.mjs` 等） |

### 依赖的第三方 jar 不在本仓库里

`build.ps1` 需要两个东西：

- `lib/gson-2.13.1.jar` —— **脚本会自动从 Maven Central 下载并校验 SHA-256**，不用你管；
- `lib/spring/*.jar` —— 网页层的 Spring Boot 运行时，**本仓库不包含**（23 个 jar，约 17 MB）。
  缺了它 `build.ps1` 会直接以 `Missing lib\spring (Spring Boot jars for the web layer).` 退出。

Spring 那批 jar 请自行用 Maven / Gradle 解析 **Spring Boot 3.5.6** 的 `spring-boot-starter-web`
（会带出 Spring Framework 6.2.11、Tomcat 10.1.46、Logback 1.5.18、SLF4J 2.0.17、SnakeYAML 2.4、
Micrometer 1.14.11 等），把解析到的 jar 全部放进 `lib/spring/`。举例：

```powershell
# 用一个临时 Maven 工程把依赖抓下来（需要本机有 mvn）
mvn dependency:copy -Dartifact=org.springframework.boot:spring-boot-starter-web:3.5.6 -DoutputDirectory=lib\spring
mvn dependency:copy-dependencies -DoutputDirectory=lib\spring
```

一份实测可用的清单（版本号照抄即可）：

```
spring-boot-3.5.6.jar                     spring-boot-autoconfigure-3.5.6.jar
spring-web-6.2.11.jar                     spring-webmvc-6.2.11.jar
spring-context-6.2.11.jar                 spring-core-6.2.11.jar
spring-beans-6.2.11.jar                   spring-aop-6.2.11.jar
spring-expression-6.2.11.jar              spring-jcl-6.2.11.jar
tomcat-embed-core-10.1.46.jar             tomcat-embed-el-10.1.46.jar
tomcat-embed-websocket-10.1.46.jar        logback-classic-1.5.18.jar
logback-core-1.5.18.jar                   slf4j-api-2.0.17.jar
jul-to-slf4j-2.0.17.jar                   log4j-api-2.24.3.jar
log4j-to-slf4j-2.24.3.jar                 micrometer-commons-1.14.11.jar
micrometer-observation-1.14.11.jar        snakeyaml-2.4.jar
jakarta.annotation-api-2.1.1.jar
```

> `lib/**` 已在 `.gitignore` 里。这些是 Apache-2.0 的第三方产物，你自己下载即可，不必也不应提交进来。

---

## 三、快速开始

```powershell
# 1) 拿到代码后，在项目根目录（下称 <root>）
cd <root>

# 2) 准备配置：复制示例，然后照着改
copy config.example.json config.json

# 3) 补齐 lib\spring（见上一节），然后启动
.\run.bat
```

第一次启动如果检测到还没配好（缺 DeepSeek 密钥或网页令牌），会**自动在浏览器打开控制台的「配置」栏目**
`http://127.0.0.1:8787/setup`：填好机器人名字、owner QQ、两条 DeepSeek 通道（地址 + 密钥）、
SD 与 NapCat 地址即可，密钥只写进本机文件、页面只回显掩码。首次配置期间这个栏目对本机免令牌，
配好之后就和其它接口一样要令牌。Civitai 账号也在这一栏配：把邮件里那条**一次性登录链接**整条粘进去，
机器人替浏览器走完跳转链（最多 10 跳）并保存会话 Cookie，不用手动抄 Cookie。

命令行向导仍然保留，适合没有浏览器的环境：

```powershell
.\run.bat --setup
```

### 最少要改的几项

1. `owner_user_id` —— **你自己的 QQ 号**。本仓库**不含**任何默认 owner，
   留空时所有 owner 专属指令都会被拒绝（这是刻意为之：公开仓库不发布个人 QQ 号）。
2. `sd.root` —— 你的 SD WebUI 根目录（默认留空：不填就不用机器人自动拉起 SD，自己先启动好即可）。
3. `civitai.lora_dir` —— LoRA 目录（默认留空：不填就用不了 Civitai 下载与 LoRA 列表）。
4. 两条 DeepSeek 通道的地址与密钥 —— 配置页里填（`api_base` 留空即官方 `api.deepseek.com`）；
   也可以手动放 `data/deepseek-api-key.txt`（生图）与 `data/deepseek-chat-api-key.txt`（聊天）。
5. `qq.ws_url` —— NapCat 正向 WebSocket 地址。

> 这两条通道互相独立：地址、密钥、模型、额度都是分开的，改一条不影响另一条。
> DeepSeek 的地址与密钥改完**立刻生效**（不用重启）；SD 与 NapCat 的地址要重启机器人才生效。

### 被禁言时不会硬发

群禁言（全员禁言或机器人自己被禁言）会被识别出来：禁言期间**不再尝试发送**消息，
只在日志里记一次「禁言中，跳过发送」，不会刷一屏 `retcode=1200` 的报错。
解除通知、到期、以及每隔一分钟的回查三条路都能让它自己恢复；聊天在这期间也不发起模型调用。

### 常用脚本

| 脚本 | 作用 |
|---|---|
| `run.bat` | 先构建（`build.ps1`）再启动机器人；`run.bat --setup` 进命令行配置向导（网页配置页是默认入口） |
| `build.ps1` | 编译 `src/main/java` 并打包成 `build/pixiko.jar`；`-Test` 连测试一起编译并运行 |
| `test.bat` | 等价于 `build.ps1 -Test`：编译全部测试并逐个运行 `*Test` |
| `stop-bot.ps1` | 停止正在运行的机器人 |
| `install-webui-bridge.ps1` | 把 `webui-extension/pixiko-bridge` 装进 SD WebUI 的 `extensions/` |
| `eval-chain.ps1` / `eval-effect.ps1` / `eval-scale.ps1` / `eval-decompose.ps1` | 提示词链路的评测脚本（需要本机已跑起机器人） |
| `node tools\webui-selfcheck.mjs --token <令牌>` | 网页控制台自检（默认打 `http://127.0.0.1:8787`，可用 `--base` 改）。令牌请用 `config.json` 里的 `webui.access_token`，也可以放进环境变量 `PIXIKO_WEBUI_TOKEN`；仓库里不含任何真实令牌，所以**不给令牌会直接退出** |

其他启动参数（`run.bat` 会原样透传给 `Main`）：

```
run.bat --help                    # 打印指令总表
run.bat --check                   # 只检查 SD WebUI 连通性，不启动机器人
run.bat --setup                   # 命令行配置向导（网页配置页是默认入口；需先停止机器人）
run.bat --set-map yh "路径"       # 命令行设置地图（需先停止机器人）
```

---

## 四、配置项要点（`config.json`）

> **`config.json` 不进版本库；`data/` 只放行中文词库与词表。** 前者含真实 QQ 号、Civitai Cookie、
> 网页访问令牌；`data/` 里的 DeepSeek 密钥、生成图、队列状态同样都不进仓库。
> 随包分发的只有 `data/prompt-*.json|txt|md` 这几个词库文件（见下），`.gitignore` 里逐个白名单放行。

| 配置段 | 关键项 | 说明 |
|---|---|---|
| 顶层 | `bot_name` / `owner_user_id` / `startup_group_id` | 机器人名字、唯一 owner 的 QQ、上下线播报发到哪个群（留空则不播报） |
| | `allowed_group_ids` / `allowed_user_ids` | 白名单；**留空数组 = 不限制** |
| | `admin_user_ids` | admin 名单（可代 owner 操作，但不是 owner） |
| `qq` | `ws_url` / `access_token` / `timeout_seconds` / `reconnect_seconds` | NapCat 正向 WebSocket 地址与 Token |
| `sd` | `base_url` | SD WebUI 的 API 地址（A1111 / Forge / Forge Neo 都行） |
| | `root` / `start_command` | WebUI 根目录；自启动靠它找 `python/python.exe`+`launch.py` 或 `webui-user.bat`（Forge Neo 建议直接填 `start_command` = 它的 `webui-user.bat`）。**Forge 的预设也是从这里的 `config.json` 读的** |
| | `auto_start` / `start_on_boot` / `start_args` / `start_timeout_seconds` | 生成前自动拉起、随机器人启动、启动参数（**必须含 `--api`**） |
| | `api_username` / `api_password` | WebUI 若开了 `--gradio-auth` 才需要 |
| | `steps` / `width` / `height` / `cfg_scale` / `sampler_name` / `seed` / `styles` | 生成默认参数（`.settings` 看，`.steps`/`.size`/`.cfg`/`.seed`/`.model`/`.sampler` 改） |
| `civitai` | `session_cookie` | **Civitai 会话 Cookie。留空字符串即可**，推荐用一次登录链接自动写入（见下） |
| | `lora_dir` | LoRA 下载目录 |
| | `base_url` | 默认 `https://civitai.red`（镜像）；`api_token` 一般留空 |
| | `proxy_url` | 只接受本机 HTTP 代理，例如 `http://127.0.0.1:7890` |
| | `max_download_mb` / `timeout_seconds` / `admin_only` | 下载上限、超时、是否仅 owner/admin 可下载 |
| | `search_page_size` | 搜索**每页条数**（默认 `10`，范围 1–50）。Civitai 的关键词搜索用 cursor 分页，见下文 |
| | `showcase_limit` | **每个 LoRA 最多把前 N 张有提示词的展示图做成样式**（默认 `0` = 不限）。超出的按「超出展示图样式上限」计数并提示调大 |
| `webui` | `enabled` / `host` / `port` | 网页控制台开关与监听地址（默认 `0.0.0.0:8787`） |
| | `access_token` | **网页控制台访问令牌。示例里是 `"change-me"`，请务必改掉**；首次启动若为空会自动生成并写回 `config.json`，日志里有 |
| | `scope` | 控制台操作归属的会话作用域 |
| `progen` | `model` / `thinking` / `reasoning_effort` / `max_tokens` / `timeout_seconds` / `api_key_file` | **生图频道**的 DeepSeek 设置，密钥文件默认 `data/deepseek-api-key.txt` |
| `chat_api` | 同上 | **聊天频道**的 DeepSeek 设置，密钥文件默认 `data/deepseek-chat-api-key.txt`。两条通道完全独立：模型、思考开关、密钥各管各的 |
| `chat` | `personality` | 人格设定。**示例里只有 3～5 行占位文本**，真实人格由 owner 自己写（`.chat personality` 整段替换、`.chat add` 追加、`.chat infix` 让模型改） |
| | `enabled` / `frequency` / `context_seconds` / `corpus_replay` / `corpus_top_k` / `disabled_conversations` | 聊天总开关、回复频率上限（**`frequency: 0` 表示这个会话完全不回**）、30 分钟「对话窗口」的秒数（默认 `1800`）、原作语料复现开关与召回条数（见下面的版权说明）、已关掉日常聊天的会话列表。**主动插话相关的 7 个键（`wake_probability`、`reply_base_probability`、`reply_probability_scale`、`chime_cooldown_seconds`、`topic_gap_seconds`、`base_min_interest`、`chime_high_interest`）已删除，老配置里留着会被直接忽略**（不报错、不改写、不迁移） |
| `infix` | `filter_enabled_conversations` | 已开启「标准词库约束」的会话（默认空 = 全部自由改写） |

### Civitai 登录：用一次性链接，不要手抄 Cookie

网页控制台里点「Civitai 登录」会生成一条**一次性链接**（默认 10 分钟有效、用一次即废）。
在浏览器里打开它、走完 Civitai 官方登录，服务端跟着跳转链把 Cookie 收下来写进
`config.json` 的 `civitai.session_cookie`。**Cookie 只落在本机 `config.json`，既不进日志也不进版本库**，
控制台里只显示脱敏后的 `cookieHint`。

### DeepSeek 密钥怎么放

密钥**不是**写在 `config.json` 里的，而是放在 `progen.api_key_file` / `chat_api.api_key_file`
指向的文件里（默认 `data/deepseek-api-key.txt`、`data/deepseek-chat-api-key.txt`，相对项目根目录）。
`data/` 不进版本库。两条通道没分开放时，聊天频道会回退用共享密钥并打一条 WARN。

---

## 五、指令总表

群聊里**只有被 @ 或叫她的名字（小鸟・小鳥・ことり・kotori）才会回复**；被点名回了一次之后，
**要不要把你留在 30 分钟的「对话窗口」里由她按这次对话决定**——留在窗口里就可以不 @ 继续聊。
私聊不用唤名，每条都回。
以 `.` 开头的消息只走指令系统，不进入日常聊天。列表条目可以用 `#编号` 引用，
编号以**你本人在本会话最近一次看到的那份列表**为准。

下表来自 `run.bat --help` 实际打印的内容（`Bot.HELP`），并补上了**代码里实现了但没有打印在该帮助里**的
少数子命令（目前只有 `.lora delete`，见 `Bot.java` 的 `.lora` 分支）。

### 聊天与权限

| 指令 | 作用 |
|---|---|
| `.help` | 显示完整指令表 |
| `.chat` | 查看聊天设置（仅 owner/admin） |
| `.chat toggle` | 开关当前群／当前私聊的日常聊天（仅 owner/admin） |
| `.chat global on\|off` | 全局总开关（仅 owner） |
| `.chat model [名称]` | 查看／设置聊天频道模型（仅 owner） |
| `.chat personality <基础性格设定>` | 保存人格设定（仅 owner） |
| `.chat add <内容>` | 追加到当前人格设定末尾（仅 owner） |
| `.chat infix <修改要求>` | 让 DeepSeek 智能修改人格设定（仅 owner） |
| `.chat frequency <每分钟发言次数>` | 回复频率上限，0 为静默（仅 owner） |
| `.chat corpus on\|off` | 原作语料复现开关（仅 owner） |
| `.chat notice on\|off` | 开关上／下线播报是否发到主群（仅 owner/admin） |
| `.chat log on\|off` | 开关把 WARN/ERROR 同步到主群（仅 owner/admin） |
| `.affinity` / `.affinity <QQ号>` | 查看好感度与分档；查他人仅 owner |
| `.admin` / `.admin list` | 查看用法／查看 owner 与 admin 名单 |
| `.admin add <@成员\|QQ号\|群名片>` | 添加 admin（仅 owner） |
| `.admin remove <@成员\|QQ号\|群名片>` | 移除 admin（仅 owner） |
| `.batch <指令1> ; <指令2> ; …` | 一条消息顺序执行多条指令（最多 20 条） |

### 只回应 @／提及：插话功能已删除（v1.2.0）

**主动插话（群聊里没被 @ 时由模型判断要不要插一句）已经彻底删除。** 现在群聊里**只有被 @ 或叫她的名字
（小鸟・小鳥・ことり・kotori）才会回复**——其它群消息**不回复、不规划、不消耗模型额度、不进对话历史**；
私聊行为不变（每条都回）。

- **回不回由"这次对话"决定**：被 @ 之后机器人**一定回一次**，然后由模型按这次对话判断
  **要不要把你拉进 30 分钟的「对话窗口」**（`chat.context_seconds`，默认 1800 秒）。进了窗口，
  你在该会话里**不 @ 也能继续聊**；没进窗口就只回这一句，下一条不 @ 的消息不再回复。
  窗口内继续聊时，仍会按"话题是否变化"自然收尾（原行为保留）。
- **「下载 #编号」这类引导也要过同一道闸门（v1.2.3 起）**：群里没被 @ 也没叫名字时，
  **连"这次没有可用的搜索结果…"这种引导性回复也不会回**——以前群里随便谁说「下载 #3」
  （消息里同时含「下载/download」和「#编号」、而机器人当前没有活的 Civitai 搜索结果）都会收到它。
  现在这条引导**先过"会不会得到回复"的同一道闸门**：私聊照旧；群聊只在**被 @／叫名字
  （小鸟・小鳥・ことり・kotori）**或**该用户已经在这个会话的 30 分钟对话窗口里**时才回，
  否则**完全不回**（不回复、不规划、不消耗模型额度、不进对话历史）。
- **删掉 7 个配置键**：`chat.wake_probability`、`chat.reply_base_probability`、`chat.reply_probability_scale`、
  `chat.chime_cooldown_seconds`、`chat.topic_gap_seconds`、`chat.base_min_interest`、`chat.chime_high_interest`
  都不再被读取——`config.example.json` 里已删除这些键，**老 `config.json` 里如果还留着会被直接忽略**
  （不报错、不改写、不迁移）。想"这个会话完全不回"用已有的 **`chat.frequency: 0`**。
  保留的键：`personality`、`frequency`、`context_seconds`（窗口）、`corpus_replay`、`corpus_top_k`、
  `enabled`、`disabled_conversations`、`selection_gap_seconds`。
- **命令面**：`/chat wake …` 与 `/chat base …` 两条子命令已删除（`/chat` 面板里"主动插话总开关 /
  冷却 / 相关度下限"几行也一并去掉）；`/chat` 的其它子命令（开关、频率、人设）不变。
- **对话页输入框固定在窗口底部（v1.2.0）**：对话页现在铺满窗口高度，**对话内容自己在中间滚动，
  输入框（和「允许执行指令 / 清空对话」那行）始终贴在可见区域底部**——不用再滚到底才看得见输入框；
  按会话 scope 记住滚动位置的行为照旧。

### 提示词与中文词库

| 指令 | 作用 |
|---|---|
| `.prompt` / `.promptR` | 查看正向／反向 prompt 与数据来源 |
| `.prompt add <prompt>` / `.promptR add <prompt>` | 追加 |
| `.prompt remove <prompt>` / `.promptR remove <prompt>` | 按完整词项删除（支持唯一部分匹配、空格/下划线纠正） |
| `.prompt set <whole-prompt>` / `.promptR set <whole-prompt>` | 整段替换 |
| `.prompt clear` / `.promptR clear` | 清空 |
| `.prompt classify` | 把当前正向 prompt 每个词条按类别列出 |
| `.prompt keep <类别…>` | 只保留这些类别，其余清空（LoRA／嵌入标签始终保留） |
| `.prompt drop <类别…>` | 删掉这些类别 |
| `.prompt undo` | 正反向一起回退（最多 20 步） |
| `.infix <修改要求>` | 交给 DeepSeek 改写并应用（允许自然语言短语） |
| `.infix filter [on\|off]` | 查看／切换本会话的标准词库约束（默认关闭，仅 owner/admin） |
| `.progen <文字描述>` | 只生成提示词建议，不改当前 prompt |
| `.usage` | 浏览提示词中文分类目录 |
| `.usage <分类路径>` | 逐级浏览，例如 `.usage 服饰/上衣` |
| `.usage 词库 [分类] [起始条数]` | 浏览内置中文词库，每页 40 条 |
| `.usage 搜索 <中文或英文> [起始条数]` | 搜索词条 |
| `.usage 词条 <词条或中文>` | 看某词条的词意与使用注意 |

#### 中文词库随包分发（v1.0.2 起）

`data/` 下这几个文件**已经随仓库与发行包提供**，下载后 `.usage 词库`、`.usage 搜索`、
词条分类（`.prompt classify`）与 `.infix filter on`（标准词库约束）都能直接用，不需要自己造词库：

| 文件 | 体积 | 内容 |
|---|---|---|
| `data/prompt-tags.txt` | 2.3 MB | 140,779 条标准词条（Danbooru 词表，一行一个） |
| `data/prompt-usage.json` | 464 KB | 中文分类词库，11 类，供 `.usage` 逐级浏览 |
| `data/prompt-zh-tags.json` | 1.6 MB | 34,211 条中文↔标准词条、38,941 个中文写法，13 类 |
| `data/prompt-zh-extra.txt` | 188 KB | 手工维护的中文同义词表（生成词库的输入） |
| `data/prompt-zh-usage.md` | 4.0 MB | 3 万多条词条的人读用法表（生成物） |
| `data/prompt-zh-use-notes.txt` | 2.2 KB | 词条「什么需求下才该用」的人工说明 |
| `data/prompt-tags.source.json` | 362 B | 标准词表的来源与校验值 |

- 两个上游词表都是 **MIT**（[a1111-sd-webui-tagcomplete](https://github.com/DominikDoom/a1111-sd-webui-tagcomplete)、
  [sd-webui-prompt-all-in-one](https://github.com/Physton/sd-webui-prompt-all-in-one)），
  全文随包放在 `LICENSES/MIT-tagcomplete.txt` 与 `LICENSES/MIT-prompt-all-in-one.txt`，
  明细见 [`THIRD-PARTY-LICENSES.md`](THIRD-PARTY-LICENSES.md)。
- 中文含义里**含机器翻译**，可能不准确；词库只是把中文说法对到标准词条，最终由模型判断。
- **上游原始词表也在仓库里**（`data/danbooru/`，共 9.8 MB，随源码包分发、开箱即用包不含）：
  `danbooru.main-140782.csv`（`prompt-tags.txt` 的来源）、`danbooru.csv`（分类与热度）、
  `danbooru.zh_CN_SFW.csv`（中文翻译）。因此**不装 SD WebUI 扩展也能重建词库**：
  `node tools\build-zh-tags.mjs`，校验用 `node tools\build-zh-tags.mjs --check`
  （仓库里跑过，输出「与现有 data/prompt-zh-tags.json 一致」）。详见 `data/danbooru/README.md`。- 想改词库：直接编辑 `data/prompt-zh-extra.txt`（手工同义词，优先级最高）后跑
  `node tools/build-zh-tags.mjs` 重新生成 `data/prompt-zh-tags.json`；差异用
  `node tools/diff-zh-tags.mjs` 看。

#### 中文不会被写进提示词：交给改写（v1.1.2）

**SD（以及任何底模）只认标准英文 Danbooru 词条，中文词条等于废词条**。以前对机器人说「通过反向提示词禁止
不存在的手」时，它会照原话计划出 `.promptR add 不存在的手`，中文原样进了反向提示词；从这一版起
**机器人不会把中文写进正向／反向 prompt**。

- **不做中文词库替换**：`.prompt add/set`、`.promptR add/set` 的取值里含中文时，这条要求会被**转成 `.infix`**，
  交给改写模型产出标准英文词条。例如：
  - `.promptR add 不存在的手` → 实际按 `.infix 反向提示词里加上：不存在的手` 处理；
  - `.prompt add 微笑` → `.infix 正向提示词里加上：微笑`；
  - `.prompt remove 微笑` → `.infix …提示词里删掉：微笑`。
- **英文取值行为完全不变**：`extra_hands`、`from above`、`(smile:1.2)`、`<lora:…>` 照旧直接落地。
- **聊天计划层同样兜底**：模型给出的计划里凡 `.prompt` / `.promptR` 的 `add` / `set` 取值含中文，一样转成
  `.infix`；「用户要求加入 X 而计划没覆盖」的合成逻辑也照此办理，绝不产出含中文的 `.prompt add`。
- **想直接加词条就写标准英文**（`.promptR add extra_hands`）；想用中文描述，就用一句中文要求让机器人改写：
  `.infix 反向提示词里加上：不要出现多余的手`。

### 样式、参数与提示词集

| 指令 | 作用 |
|---|---|
| `.style` / `.style list` | 查看／列出机器人样式库 |
| `.style save <名称>` / `.style overwrite <名称>` | 保存／覆盖当前提示词为样式（含 LoRA 标签） |
| `.style prompt <名称\|#编号>` | 查看样式原文 |
| `.style load <名称\|#编号> [nolora]` | 用样式替换当前正反向 prompt |
| `.style rename [overwrite] <旧名称\|#编号\|#6-#9> <新名称或前缀>` | 改名 |
| `.style delete <名称\|#编号\|#6-#9>` | 删除 |
| `.style category <名称\|#编号\|#6-#9> [分类名]` | 查看／修改样式分类（批量支持区间；只给样式名＝查询；分类名给 `-`/`清除`＝清空手动分类、回到自动分类；手动分类优先于自动） |
| `.style import webui [overwrite]` | 把 WebUI 预设样式一次性搬进机器人样式库 |
| `.settings` | 查看尺寸、采样方法、步数、CFG、种子、基础模型与来源 |
| `.sampler` / `.sampler list` / `.sampler set <完整名称>` | 采样方法 |
| `.size` / `.size set <宽> <高>` | 图片宽高（64–2048 且为 8 的倍数，也支持 `768x512`） |
| `.steps [set <步数>]` / `.cfg [set <数值>]` / `.seed [set <种子>]` | 步数／CFG／种子（种子 `-1` 为随机） |
| `.model` / `.model list` / `.model set <完整名称>` / `.model set auto` | 基础模型（`list` 每项标出**归属栈**；`auto`＝每次提交时用 WebUI 当前模型；换到别的栈会给出防呆提示） |
| `.model preset` / `.model preset <名字>` | Forge／Forge Neo 的预设：底模 + VAE + 文本编码器按栈切换，并采纳该栈的采样方法／调度器／尺寸／步数／CFG（Anima 就是其中一栈） |
| `.imgcnt <数量>` | 每条聊天记录图片上限（默认 300，按任务分开发送） |
| `.preset list\|save\|overwrite\|show\|load\|remove` | 参数预设 |
| `.function list\|save\|overwrite\|prompt\|load\|active\|remove\|clear\|delete\|rename\|reset` | 提示词集 |

**样式会记下"它属于哪个底模"**：保存样式时如果读得到 SD 的模型参数，就把**底模 + 采样方法 + 调度器 +
步数 + CFG + Shift（蒸馏 CFG）+ 尺寸**一起存进 `data/local-styles.json` 的 `model` 字段。
下载 LoRA 时自动生成的**展示图样式**同样会带上这套参数——底模优先用这个 LoRA 自己的
（先看 Civitai 下载记录，再看 Forge 的 LoRA 元数据），采样参数用**当前 Forge 预设栈**的推荐值，
预设里没给（例如 Anima 不设尺寸）就回退到机器人当前设置。实在识别不出底模时按当前预设栈**推断**，
并在样式里标成「按当前预设推断」，绝不编一个底模名出来。`.style load` 会把这份参数套回机器人设置
（只要提示词就加 `noparams`）；网页「样式」页上方按底模汇总（例如「底模：Anima 12、NoobAI 5」），
每条样式也标着自己的底模与**归属栈**。

### 生成参数归谁：三个来源 + 每次改动都留痕（v1.0.15）

生成参数不是一个整体，它有**三个来源**，所以"我设的值怎么变了"要分开看：

1. **采样方法 / 尺寸 / 预设样式**：跟着 **WebUI（Forge）的 txt2img 页面**走——页面报了什么就用什么
   （`.settings` 里的「来源」会写「WebUI 当前页面（实时同步）」或「WebUI 桥接状态（页面未实时连接）」）。
   网页控制台与指令改这三项时，机器人会把新值**推回页面**（桥接 `PUT`），两边因此保持一致；
2. **步数 / CFG / 种子 / 底模**：只存在机器人自己的 `data/sd-parameters.json`，页面不管这几项；
   所以页面和机器人各改一半时，会出现「页面上的采样方法与尺寸 + 机器人里的步数与 CFG」这种组合；
3. **`.preset load` / `.model preset` / `.style load`**：一次改一整套（预设＝尺寸+采样+步数+CFG+底模，
   样式＝它自己记着的那套）。

每次**真的改了值**都会留一行日志：`生成参数变更（网页端·尺寸）：832×1216 → 768×512`。
来源标成 `网页端` / `QQ 侧`，入口名写明是尺寸、采样方法、预设样式、迭代步数、CFG、种子、底模、
调度器 / Shift，还是某个预设名（`预设 default`）；WebUI 页面把机器人记录顶掉时同样留痕
（`生成参数变更（网页端·跟随 WebUI 页面（…））：…`）。**值没变就不留痕**——网页端面板反复失焦不该刷屏。
网页控制台改完参数，提示信息云会回一句「已生效：…」并附「参数来源：…」。
历史在 `logs/bot-YYYYMMDD.log`（或网页「日志」页）。

### 任务回执：全部回执的列表与未读标记（v1.0.16）

控制台「回执」栏（`/quest`）左边是**所有任务回执的列表**，右边是选中那条的执行结果。
**列表就是唯一的入口**：顶部原来那张要手动输入任务号（「打开回执」/「最新一条」/「刷新」）的
「任务回执」卡片已经删掉，看最新一条就是点列表第一行。

- 每行显示 `#编号`、指令、相对时间与**第一段文字的摘要**；**没看过的回执用未读圆点 + 高亮**标出来，
  条目上再用**数字徽标**显示这条回执的未读消息条数（＝文字条数 + 图片条数，`span.quest-unread-count`，
  `title` 为「未读 N 条消息」），不再是「未读」两个字的文字徽标；「回执」页签上的徽标仍是
  未读**回执**条数。点一行就打开那条（地址变成 `/quest#N`，可以直接把链接发给自己），
  打开即标记已读；「全部标为已读」一次清空未读；列表卡片头部的「刷新」**一次刷两样**：
  回执列表 + 当前打开的那条回执。
- 选中那条的状态与指令显示在右侧**「执行结果」卡片头部**：状态（如 `#23（已完成）`）与
  「最新一条是 #N」，指令行（`指令：…`）在头部下方。
- 列表整体高度上限从 `min(56vh,560px)` 提到 `min(76vh,760px)`（窄屏 `min(64vh,620px)`）；
  **每条回执不再被压扁**（`min-height:68px`、摘要最多两行，不做高度裁剪）。
- **正文永久保留，不再过期**（v1.1.1 起）：每条回执的正文（文字 + 图片**引用**）会落到
  `data/quests/<任务号>.json`（UTF-8 无 BOM、原子写，**只写引用不写图片字节**），内存里最多保留
  最近 200 条、按任务号淘汰最旧的（**淘汰前先落盘**）；列表摘要与已读状态存在 `data/quests.json`，
  索引上限从 200 条提到 **2000 条**（被裁掉的最旧条目会连 `data/quests/<号>.json` 一起删）。
  所以重启后列表还在，`/api/quest` 现在**任何旧回执都能打开看完整正文**（从磁盘读回来，附
  `fromDisk:true`；`busy=false`、`expired=false`）；**只有磁盘上确实没有正文文件**时才会说打不开，
  页面文案是「这条回执的正文读不到了（磁盘上也没有）」。以前那种「正文只在内存留 30 分钟、
  超时后显示『内容已过期，只保留摘要』」的行为已经没有了；正在跑的回执仍会在列表里标「进行中…」。
- **对话页底部那个与回执页重复的图片回执区（`id="chat-receipts"`）已经删掉**；对话页里带图片的
  出站消息改为**内联进对话流**（点击仍可放大、可在同一条消息的图片间翻页）。
- 接口：`GET /api/quests?limit=50` → `quests[{number,command,startedAt,ageMillis,done,busy,unread,expired,texts,images,summary}]`
  加 `unread/latest/total/retainedMinutes`；其中每条 `expired` **恒为 false**、顶层 `retainedMinutes`
  **恒为 0**（＝不过期）。`POST /api/quests/read`（`{"numbers":[21,22]}` 或 `{"all":true}`）；
  `GET /api/status` 顶层带 `quests:{unread,latest}`（控制台靠它更新页签徽标）。
- 新回执到来时右下角仍会弹一条**回执云**，点它跳 `/quest#N`。

### 生成进度与图集预览（v1.1.1）

带生成指令的回执在生成期间会在回执页显示**进度条**，数据来自两处、每 **1.5 秒**刷新一次：

- **主条**（v1.2.0 起是**总进度**，不再只是图片级）：数据同时来自 `GET /api/tasks` 与
  `GET /api/progress`，公式是
  **`(已生成图片数 + 当前那张的采样百分比 ÷ 100) ÷ 总图片数 × 100`**——20 张里已出 9 张、
  当前那张采样到 41%，主条就是 `任务 #4 · 已生成 9/20 张 · 47%`；**单张任务同理**
  （0/1 + 41% = 41%）。拿不到任务列表或总数时退回只报采样进度；
- **副标题**：SD 单张图**内部**的采样进度与预计时间（数据 `GET /api/progress`），例如
  `采样中 8/20（41%）· 预计 14 秒`；
- **运行中的任务可以直接取消**（v1.2.0 起）：进度卡上有「取消」按钮（二次确认，会说明"已经生成的
  图片仍可领取"），走 `POST /api/tasks/action {action:"cancel", number:"<任务号>"}`；取消后按钮变
  「已取消」并禁用、进度条**不再跳回 100%**（正在下发的这一张会跑完）。出图页原有的取消/挂起/优先按钮不变；
- 空闲后进度条标**「已完成」并停止轮询**。

**图集按总张数铺格子（v1.2.1 起）**：以前是"出一张才多一格"，现在**正在跑的那个任务一上来就按
`GET /api/tasks` 的 `total` 把格子铺好**，每格按状态渲染：

- **已经出好的图** → 正常缩略图（点击放大、在图集里前后翻页，行为不变）；
- **正在生成的那一张** → 占位格，带**圆形进度条**，状态文字如「生成中 41%」
  （百分比 = `GET /api/progress` 里当前这张的采样进度）；
- **还没轮到的** → 占位格，状态「等待中」；
- **生成失败的**（`/api/tasks` 的 `failed` 计数）→ 占位格，状态「生成失败」。

任务跑完或被取消后：占位格收掉，只留真实缩略图（和失败格），表头变成「图集 · 共 M 张」；
如果 M 小于任务总张数，再补一句「（任务共 N 张，实际 M 张）」。

**占位格不会闪烁（v1.2.2 起）**：v1.2.1 加上占位格之后，回执页的占位格曾**一直若隐若现**，原因是两条：
回执页每 **900 毫秒**会把执行结果区（`#quest-body`）整块重画一次，图集卡（含占位格）每轮都被摘出文档
再挂回；`/api/tasks` 在**两张图之间的间隙**会短暂返回空或 `running:false`，占位格被立刻收掉、下一轮又铺回来。
现在**执行结果内容没变就完全不重画**（按渲染签名比较，只更新状态文字；真的来了新消息/新图才重画），
并且给"收掉占位格"加了**宽限期**（连续几轮都拿不到任务、或任务确实结束/被取消时才收；间隙里保持上一次的
占位格与进度环，节点复用不重建），所以占位格与环不再一闪一闪。

**回执页发现新回执就自动刷新左侧列表（v1.2.2 起）**：在回执页上把"有没有新回执"的检测加快到**约 5 秒一次**
（读 `GET /api/status` 的 `quests.unread / latest`），一旦发现**最新任务号变大**（或未读数变大）就立刻刷新
左栏列表；**不会**把右栏详情自动切到最新那条——你正在看哪条就继续看哪条。页面不可见时不发请求、
重新可见时立刻查一次；跨页面的 **30 秒**未读徽标轮询保留（在别的页面也能看到未读数涨）。

这一条**只对"最新那条生成类回执 + 确实有任务在跑"生效**（沿用 v1.1.1/v1.1.2 定下的严格判据）：
别的回执、`.help`、跑完的任务都不出现占位格。**单张任务同理**（一个占位格 + 圆形进度条，出图后变成缩略图）。

**图集能在生成过程中提前预览**：生成期间前端轮询 `GET /api/images`，把这条回执开始时间**之后**
新产出的图片**增量**追加进该回执的图集（标题显示「图集 · 已生成 N 张（生成中…）」），
按图片路径去重、**不重建回执、不打断滚动**。

**对话页多图合成一条图集（v1.2.4 起）**：对话页（`#chat-log`）里**一次发送多张图**，以前会画成
**好几条各自一张图的气泡**——机器人一次发多张走的是**一条合并转发**（每张图一个节点），而服务端的
网页回执采集器 `WebCapture.capture(...)` 是**按出站消息**记的，合并转发里的**每个节点各成一组**，
于是 `GET/POST /api/capture` 返回的是 `[image] [image] [image] …` 这样多条。现在对话页把**连续的、
只含图片的**新组攒成**一条**，渲染成**一张图集卡**（≥2 张走缩略图网格，点任意一张就用图片查看器翻整组，
和回执页一致）：**含文字**的组照旧一条一条，**不同 `captureId`（另一次发送）不会并进上一张图集**，
同一条回执里**后到**的图也并进同一张、不再叠新气泡；清空对话、切会话、切栏目时游标置空，
**回执页与 QQ 发送行为不变**。

**对话页单张图的两处顺手对齐（v1.2.4 起）**：对话页里机器人发来的**纯图片**消息不再先显示一段
「（一张图片）」占位文字，**只有图**（有文字的消息照旧文字在上、图在下；快照恢复或切栏目回来也一样）；
**单张图**的尺寸上限从 `700px` 收到和**回执页单张图一致**（`min(100%, 260px)` / `260px`），
而图集格仍是 **140px** 方形——对话页里「单张 vs 图集里一格」的大小关系与回执页完全对齐；
点开仍可看原图（查看器不变），窄屏仍按 `max-width: 100%` 自适应。

**前端资源版本升到 `?v=1.3.1`**：`webui/index.html` 的 `?v=` 由父代理设置（v1.2.4 那版是 `?v=1.2.4`，
v1.3.0 那版是 `?v=1.3.0`，本版因为底模归属栈与控制台铺满的改动重新设成 `?v=1.3.1`）。
**v1.4.0 与 v1.4.1 都没有改网页资源，所以 `?v=` 仍是 `1.3.1`**（v1.4.0 新增的是 Android 客户端 `android/`，
v1.4.1 新增的是它的网页预览页 `webui/android-preview.html`）。

### 对话栏全局化与对话历史持久化（v1.3.0）

**对话栏从「一个栏目」变成公共外壳的右 1/3（v1.3.0 起）**：以前「对话」是页签里的一个栏目，
只有切到那一页才能说话；现在页面本身分成两栏——**左边 2/3 是页签与当前栏目，右边 1/3 是常驻的
对话栏**（固定不动、自己滚动、输入区常驻底部，和以前对话页里的行为一致）。**「对话」页签已经删除**；
`/` 与 `/chat`（含 `/chat/`）现在都渲染**出图页 + 右侧对话栏**，所以**在任何一个栏目里都能直接对话**，
旧的 `/chat` 书签不会 404。**窄屏时右栏改为整宽堆叠**（具体断点以 CSS 为准）。

**控制台铺满窗口（v1.3.1 起）**：以前整个控制台与页脚被 **1120px** 的居中限宽框住
（`#app { max-width: 1120px; margin: 0 auto }`），宽屏上两边留一大片空白；现在 `#app` 与页脚都
**铺满可用宽度**（保留左右 12px 内边距、**不设上限**），**右栏仍是内容宽的 1/3**、左栏 2/3
（两栏比例与「固定」都不变），**窄屏（≤1000px）仍是单列堆叠**。实测（真 Chrome，视口宽 → 左 / 右）：
1440×900 → 928 / 464、1920 → 1248 / 624、2560 → 1674.7 / 837.3、3440 → 2261.3 / 1130.7。
因为对话栏保持「右 1/3」，屏幕特别宽时右栏本身也会很宽（3440px 下约 1130px）——这是按「右 1/3」
的要求来的；`/logs` 全屏终端与页脚／任务信息云的让位算式也跟着重算过，不会被固定右栏压住。

**对话历史由服务端落盘，本地快照只当缓存（v1.3.0 起）**：以前对话记录只存在这个浏览器里，
换个浏览器或清掉站点数据就没了。现在服务端多一份存档 `data/webui/<会话>-chat-log.json`，
存**完整正文**——每条是 `{role, text, images}`，文字、图片路径与指令回执都在：

- **写法**：原子写、UTF-8 无 BOM、纯 LF；
- **上限**：只留**最后 200 条**、整份 **≤ 512KB**（UTF-8 字节）、单条文字超 **20000 字**截断
  （保留开头并注明）、单条图片最多 **60 张**、单个图片路径超 **1024 字**截断；
- **会话名（scope）会净化**：只留 `[A-Za-z0-9_.-]`、超 **64** 截断、空则用 `default`——
  所以**不可能写到 `data/webui` 之外**；
- **打开对话栏的顺序**：**先用本地快照秒开，再以服务端为准**——服务端条数**多于**本地就采用服务端并重画，
  否则把本地推给服务端；两边**条数相等时以本地为准**；
- **本地变化约 800 毫秒防抖**回写一份完整记录，**页面隐藏／卸载时再补写一次**；
- **接口打不通就静默降级**成只用本地快照：不报错、不影响聊天；
- **「清空对话」两边一起清**：服务端那份完整正文会一起清掉（只给模型用的那份 40 条文字历史也照旧清）；
- **和「只给模型看的那份历史」是两份文件**：这份存档与既有的 `data/webui/<会话>-chat.json`
  （**只给模型用**的纯文字历史，40 条 user/assistant）互不影响，后者的行为一点没变。

### 切换栏目不丢内容（v1.1.1）

控制台每切换一个栏目都是一次**真实的页面加载**，所以现在用 `sessionStorage` 记住这几样东西：

- **回执页**：当前打开的任务号（回到 `/quest` 自动恢复那条）与 `#quest-body` 的滚动位置；
- **对话页**：渲染内容（文本 + 图片引用）与按会话 scope 记住的滚动位置（之前贴在底部就继续贴底，
  图片异步加载后也保持贴底）；
- **其它栏目**：窗口滚动位置。

所以切换回来不再需要手动滚到最新消息，也不再出现「回执消息与图片全没了」。

### 图片查看器与多图图集（v1.0.17）

- **查看器**：点任何一张图放大。**点图片周围的空白处**、按 `Esc`、或点右上角 `×` 都能退出；
  **鼠标滚轮缩放**（0.2×–8×，工具条显示当前倍率），原有的「1:1 原图 / 适应屏幕」按钮照旧可用；
  一组图片时左右两侧有**上一张 / 下一张**按钮，键盘 `←` / `→` 也能翻页，计数显示「3 / 12」。
- **多图任务渲染成一个图集**：一个任务出了多张图（`/gen N`、一次 `.get` 领多张、`.rg` 回溯多张），
  回执里只出现**一个图集卡片**（缩略图网格 + 「图集 · 共 N 张」），点任意一张就用上面的查看器
  在**整个图集**里翻；只有一张图时仍是普通的单张卡片。回执页与各面板底部的回执栏都是这个行为，
  生成过程中新图到达时图集卡片就地更新张数，不会整块闪一下。

### 样式分类：一个 LoRA 一个分类，其余按归属栈

样式库里每条样式都可以有分类（`data/local-styles.json` 的 `category` 字段）。**手动设过的分类优先**；
没手动设过的按下面的默认规则现算（这只影响显示与分组，**不会因为读一次列表就往你的数据文件里写东西**）：

1. **LoRA 附带的展示图样式按 LoRA 分开**：分类名就是**那个 LoRA 的显示名**（下载 LoRA / 补展示图时
   用 Civitai 展示图生成的那些，不再统统归到 `LoRA 附带` 一个大类）。判据按优先级来——
   样式里记着的 LoRA 名（展示图样式生成时写入的 `model.lora`）→
   `data/civitai-style-links.json` 的映射（老样式没有标注就靠它，只读不改）→ 样式名前缀；
   三者都判不出具体是哪个 LoRA 时，才退回 `LoRA 附带`；
2. 其它样式按**归属栈**：`Anima` / `SDXL 栈` / `SD 1.5 栈` / `Flux 栈` / `Qwen 栈`，以及 v1.3.1 起补齐的
   `Krea 栈` / `SD3 栈` / `Hunyuan 栈` 等（**任何底模都有栈**，见下文「底模归属栈」）；
3. 连栈都判不出来的就是 `未分类`。

改分类有两条路：命令 `.style category <名称|#编号|#6-#9> <分类名>`（只给样式名＝查询；分类名给 `-`/`清除`
＝清空手动分类、回到自动规则），以及网页「样式」页——列表按分类分组，**每个分类是一个可折叠条目**，
点组头（分类名 + 条数 + 箭头）就能展开或收起，工具栏另有「全部展开 / 全部折叠」；折叠状态存在浏览器的
localStorage 里，**刷新后保持**，默认全部展开。组里每行的分类徽标是**只读**的，换分类靠**把样式行拖到
目标分类的组头**上（拖到「未分类」＝回到自动规则）；工具栏有分类筛选，
批量区是「批量改名 / 批量改分类」两张卡片。
`.style list`、`.style prompt`、`.style load` 的回执里也都会显示分类。

接口上，`GET /api/styles` 新增 `categories: [{key,name,kind,count,lora}]`——`kind` 是
`lora`/`stack`/`manual`/`none`，`key` 形如 `lora:<LoRA名>`、`stack:xl`、`manual:<分类名>`、`none`；
每条样式带 `categoryKey`，排序为 LoRA 组 → stack → manual → 未分类。

**老文件（没有 `category` 字段）读成"未手动分类"**：不报错、也不会被读取过程重写；写盘只写用户手动设置的分类。

**改分类与改名在 v1.3.0 换了入口：拖动换分类，两个按钮删掉**：以前单条改分类要点分类徽标
（或「改分类」按钮）填输入框、改名要点「改名」按钮填输入框；现在：

- **样式行可以直接拖**（行首一个 `⋮⋮` 手势、鼠标是抓取形状）：拖到**某个分类组头**（或该组的列表）上
  会高亮，**松手即把这条样式改到那个分类**；
- **拖到「未分类」＝清空手动分类、回到按规则自动分类**；**拖回自己原来那一组不发请求**（不会白写一次盘）；
- **分类徽标改成只读**，行上原来的**「改名」「改分类」两个按钮已经删掉**；
- **底部两张批量卡片保留**（**批量改名 / 批量改分类**，填 `#编号` 区间）：想改单条名字、或新建一个
  分类名，仍然可以用它们，QQ 侧 `.style rename` / `.style category` 也照旧可用；
- **触屏上没有 HTML5 拖放**，所以**触屏上改单条分类要靠批量卡片**。

### 展示图样式会记住展示图自己的尺寸

下载 LoRA / 补展示图时生成的展示图样式，会读**这张展示图的实际像素**（`ImageSize` 只读 PNG/JPEG/GIF/BMP/WebP
的文件头，不加载整图），把它写进样式的 `model.width` / `model.height` 并标成 `model.sizeSource = "preview"`：

- 尺寸优先级：**展示图自己的像素** ＞ 当前 Forge 预设栈的 `width/height` ＞ 机器人当前设置
  （Anima 预设的 `anima_t2i_width/height` 是 0，等于"没给"，会逐项回退）；
- 同时记下展示图路径 `model.previewImage`（`data/style-previews/<哈希>.png`，**不复制大图**）与所属 LoRA
  的 `model.lora`（分类时靠它把样式归到这个 LoRA 的分类）；
- 「补展示图」这条重跑路径同样带尺寸：已存在但缺 `width/height` 的展示图样式会补写一次，回执报
  「补展示图尺寸 N 条」；重复同步是"复用"，不会反复重写；
- `.style load`（命令与网页）会把记着的尺寸一起套回机器人设置；分类来自展示图样式、而它记的栈与当前栈
  不一致时，仍然照旧提示"底模没有随之切换"，**不偷偷切栈**。

**保存／覆盖样式时可以顺手选封面，默认就是最近一次生成的图（v1.3.0 起）**：网页「样式」页的保存表单
旁边多了一个**封面选择**（由 JS 动态创建）：默认档「**默认（最近一次生成图）**」、另有「**不设封面**」、
以及**最近 8 张**生成图可选，旁边 **60×60** 预览；`保存样式` 与 `覆盖保存` 都会把这份选择发给后端。

- **默认档（不选）**：取**最近一次生成图**（按修改时间最新的那张）——**排除 `data/generated/webui/`
  里那种回执内嵌的临时图**（例如地图 / Civitai 封面），回复里写「封面：最近生成的 <文件名>」；
  **一张可用的都没有时不设封面**、回复里说明原因、**不报错**；
- **「不设封面」**：本次不设；**原有封面保留不删**，回复里说明；
- **指定某一张**：必须落在 `data/generated` 目录树内，否则明确报错，并且**一个字节都不写**
  （这项校验发生在保存样式之前）；成功回「已记下封面：<文件名>」；
- **封面文件仍在 `data/style-previews/<名字哈希>.png`**：**没有**往 `data/local-styles.json` 里加新字段，
  也不动 `previewImage` / `sizeSource`；控制台另有 `POST /api/styles/cover` 可以单独换一张封面；
- **QQ 侧 `.style save` 不设封面**——封面是控制台样式面板的功能。

### 展示图样式的「跳过」按原因分类，不做成的不骗你

下载 LoRA / 补展示图时，回执不再只给一个「跳过 9」，而是**按原因分类计数**再给下一步：

| 原因 | 什么情况 | 怎么办 |
|---|---|---|
| 该图没有提示词元数据 | 这张展示图在 Civitai 上**没有公开提示词**（A1111 参数与 ComfyUI 工作流都没有，`meta` 字段整个缺失） | 做不成样式，**绝不伪造提示词**；换一个有提示词的版本，或自己写一条样式 |
| 同名样式内容不同 | `模型名 N` 这个位置被一条内容不同的样式占着（多半是你手动改过它） | 用 `.lora cover <LoRA名>`（网页「本机 LoRA」卡片的「补展示图/样式」）重跑：有展示图映射的那条会按展示图**修正**、旧内容先备份到 `data/civitai-style-backups`，没有映射的会另挑一个空号新建；要让展示图直接顶掉同名样式，先 `.style rename <同名样式> <新名>` 腾出名字 |
| 该图没有对应样式且本次不新建 | 走的是「只补图、不新建样式」的重跑路径（离线计划/老迁移） | 用 `.lora cover <LoRA名>`（网页「补展示图/样式」）把它建出来 |
| 超出展示图样式上限 | 超过 `civitai.showcase_limit`（默认 `0` = 不限） | 调大该配置后重跑 `.lora cover <LoRA名>` |

- **原本就没有硬编码上限**：这次顺手加了可配置的 `civitai.showcase_limit`（默认不限），撞上限的那些
  **会计数并提示**，不再可能悄悄少做几张；
- 「复用」「修正」是原有语义：同名且提示词一致＝复用，同名但 LoRA 标签变了＝修正（旧内容先备份）；
  这次没有新造 `overwrite` 指令，`.style import webui overwrite` / `.style overwrite` 那套语义不变；
- 每类原因在第 2 行起都有一条 `提示（原因）：…`，逐条清单（`展示图 N → 样式名`）仍在日志与本机记录里。

### Civitai 搜索翻页：为什么用 cursor、页数为什么常常"不知道"

- 命令：`.lora search <关键词> [页码]`，`.lora query` 是同一个命令（语义不变，页码默认 1）；
- **必须用 cursor，不能用 `page`**：Civitai 的 `/api/v1/models` 在带 `query` 时只要出现 `page` 就直接报
  `Cannot use page param with query search. Use cursor-based pagination.`（实测 civitai.red 与 civitai.com
  行为一致）。关键词搜索的 `cursor` **就是偏移量**（第一页 `metadata.nextCursor` 是 `"10"`、下一页 `"20"`…，
  且 `limit=1&cursor=20` 命中的正是 `limit=10&cursor=20` 的第 1 条），所以第 N 页 = `cursor=(N-1)*每页条数`：
  既能一页页往后翻，也能直接跳到第 N 页；
- **`hasMore` 是探出来的**：非空页永远带 `nextCursor`（它就是 `offset+limit`），所以"有 nextCursor"不等于
  "还有下一页"；机器人在需要时用一个 `limit=1` + 下一页游标的请求探一下，下一页为空才算到底；
- **总页数常常不知道**：关键词搜索的响应里没有结果总数，所以 `/api/civitai/search` 的 `totalPages` 给 `-1`
  且 `totalPagesKnown=false`，网页只在知道时写「第 X/Y 页」，否则写「第 X 页」，能不能再翻由 `hasMore` 决定。
  宁可说"不知道"，也不编一个页数；
- **每页条数**：`config.json` 的 `civitai.search_page_size`（默认 10，范围 1–50）；
- **本页编号**：`.lora download #N` 指**当前这一页**的第 N 条（网页卡片上的编号同理）。翻页会整体换掉这份编号
  列表，和回执里的 `#N` 一一对应；翻过头（第 N 页为空）时**不会清掉**你手上的编号，只回报
  「第 N 页没有内容…回到第一页：`.lora search <词> 1`」。搜索词自己以数字结尾（例如 `milf 2`）时把整段用
  双引号包起来：`.lora search "milf 2"`；含空格的搜索词在"下一页"提示里也会自动加引号；
- 网页「Civitai 搜索」卡片下方有「上一页 / 下一页 / 第 X 页」控件，搜索一次后可用，翻页时搜索词保留；
- 接口：`POST /api/civitai/search` 的请求体接受 `page`，响应给出 `page`、`pageSize`、`count`、`hasMore`、
  `totalPages`、`totalPagesKnown`、`nextPage`（越界时另给 `note`，列表区直接显示这句）。

### 底模归属栈：它属于哪一栈、要切到哪个预设

Forge／Forge Neo 把「底模 + VAE + 文本编码器」按预设分成一栈一栈（`anima` / `xl` / `sd` / `flux` /
`qwen` …），**只换底模不换栈会出全灰废图**。所以光知道"底模叫什么"不够，还要知道"它属于哪一栈"。
机器人判据按可靠性排序，全部来自真实数据：

1. **safetensors 头部元数据（最可靠）**：读文件开头的头部 JSON（8 字节长度 + N 字节 JSON，只读前若干 KB，
   **不加载权重**），取 `__metadata__` 里的 `ss_base_model_version`（如 `anima`）、
   `modelspec.architecture`（如 `stable-diffusion-xl-v1-base`、`anima-preview/lora`）、`ss_sd_model_name`
   （sd-scripts 的 `model.safetensors` 是占位，不算）；
2. **头部张量名结构**（同一份头部）：SDXL 有第二个文本编码器 `conditioner.embedders.1.*`；SD1.5 只有
   `conditioner.embedders.0.transformer.*`；SD2 是 `conditioner.embedders.0.model.*`（OpenCLIP）；
   Anima 是 `net.llm_adapter.*` / `net.blocks.*`；Flux 是 `double_blocks.* + single_blocks.*`；
   LoRA 侧看 `lora_te_*`（单文本编码器＝SD1.5，`ss_v2=True` 则是 SD2）与 `lora_te1_*`/`lora_te2_*`（＝SDXL）；
3. **Civitai 下载记录**的 `base_model`；
4. **Forge 的 LoRA 元数据**，以及 **Forge 预设配置** `forge_checkpoint_<preset>`（哪个预设置的就是这个文件，
   它就属于那一栈——这是"归属"最直接的一条）；
5. 兜底才按**文件名关键词**猜（anima、illustrious、noob、pony、sdxl、xl、sd1.5、sd_v1、sd 2、flux、
   qwen、krea），结果一律标成推断。

**底模现在都能归栈（v1.3.1 起）**：以前只认 `anima` / `xl` / `sd` / `flux` / `qwen` 五种栈，关键词表也很短，
**凡是不认识的底模名一律返回空**、界面就显示「未识别底模」。现在改成**表驱动的 18 个已知族**，并且
**每个族各自一栈**——`sd` / `xl` / `flux` / `qwen` 照旧，**新增 `krea`（`Flux.1 Krea`，判定必须排在
`flux` 前面，否则「Flux.1 Krea」会被归成 flux 栈）**，SD3、Hunyuan、Wan Video、Chroma、Lumina、
Kolors、PixArt、Playground、Stable Cascade、Z-Image、Nitro-E、ODOR 等各自成一栈，`Pony V7` 也自己一栈
（它换了底模；Pony／V6 仍是 `xl`）。**连族都认不出的底模名**（例如 Civitai 的 `Other`）**用规范化 slug
自成一族**（`Other` → `other`、`Foo BarXL v2` → `foo-barxl-v2`），并且**标明这是推断出来的**
（「按文件名/当前预设推断」，不是实测；判据来源与优先级就是上面的 1–5）。顺带修掉一个旧 bug：
`Animagine XL` 因为名字里含 `anima`，一直被误判成 **anima 栈**，现在正确归 `xl`。
**只有真的没有底模信息时**才显示「未识别」。用法：

- `.model list` 每个底模后面标出栈：`waiIllustriousSDXL_v170.safetensors [SDXL 栈]`；
- 网页「基础模型」下拉同样带栈（`GET /api/options` 的 `modelOptions` 给出「底模 → 栈」映射），
  LoRA 面板按**栈**分组（组头 `Anima 栈（1）`、`SDXL 栈（1）`，组里保留底模名）；
- `.model set <底模>` 与网页改底模时的**防呆**：所选底模的栈 ≠ 当前栈就直说
  「`waiIllustriousSDXL_v170` 属于 SDXL 栈（预设 `xl`），当前是 anima 栈 —— 直接换会出全灰废图，
  请用 `.model preset xl` 或在 Forge 页面切到 `xl`」；真的没有底模信息时保留原来的通用警告；
- 样式里也记 `stack` 字段，`.style load` 时样式栈 ≠ 当前栈会如实提示（**不偷偷切栈**）；
- `GET /api/loras` 每项带 `stack` / `stackLabel` / `preset` / `stackSource` / `evidence`，
  `groups` 按栈分组（`groupKey` 就是栈键，底模另给 `baseModelGroupKey`）。

### 出图、LoRA 与图片

| 指令 | 作用 |
|---|---|
| `.gen [次数]` | 按当前完整参数排队生成，默认 1 次 |
| `.gen status\|list` | 查看队列：状态、进度、已生成张数与来源会话 |
| `.gen first #编号` / `.gen hold #编号` / `.gen resume #编号` | 置顶／挂起／继续 |
| `.gen cancel #编号\|all` | 取消某任务或整个队列（已生成的图片仍可领取） |
| `.gen toggle` | 开关「每个任务完成后自动领取」（默认开启，重启保留） |
| `.get` | 按命令任务与 `imgcnt` 分批领取；未完成任务仅预览不移除 |
| `.rg <数量>` | 回溯最近图片，按任务和图片上限分批，不改动待领取列表 |
| `.progress` | 查看 SD WebUI 当前生成进度与机器人队列状态 |
| `.sd` / `.sd start` | 查看 SD 与自启动状态／现在就拉起来并等到就绪 |
| `.sd auto on\|off` / `.sd boot on\|off` | 生成前自动启动（默认开）／随机器人启动（默认关） |
| `.lora search <关键词> [页码]`（= `.lora query`） | 搜索 Civitai 并**翻页**：回执给「第 X 页」「本页编号」与「下一页」；关键词以数字结尾时用双引号，如 `.lora search "milf 2"` |
| `.lora download #编号 [权重]` / `.lora download <Civitai链接> [权重]` | 下载并启用，同时把展示图提示词存成样式；`#编号` 指**当前这一页**的第 N 条 |
| `.lora status` | 查看最近下载状态 |
| `.lora list` | 列出 WebUI 本地 LoRA（每项标出底模与**归属栈**，并在上方按栈分组） |
| `.lora detail <名称\|#编号>` | 查看一个本地 LoRA 的底模（含来源与判据）、归属栈、Civitai 记录与它的展示图样式 |
| `.lora load <完整本地名称> [权重]` | 重新加载或启用已有 LoRA |
| `.lora rename <旧本地名称> <新本地名称>` | 重命名本地 LoRA 并同步个人 prompt 标签 |
| `.lora delete <名称\|#编号>` | 删除本地 LoRA（只允许 LoRA 目录下的文件；**未列在 `--help` 输出里，但代码中可用**） |
| `.char <角色名或关键词>` | 在本机 LoRA 与 WebUI 样式里查角色候选 |

**一批多图走一条合并转发，失败也不会丢图（v1.2.3 起）**：一批**多于 1 张**的图片走**一条合并转发**
（每张一个节点），日志会明确写 `图片发送方式（<会话>）：合并转发 N 张`；单张走普通发送，日志写
`…：单张普通发送`；地图（`.yh` / `.liv`）同理写 `地图发送方式（<会话>）：…`。
**合并转发失败会自动回退成普通发送**（例如 NapCat 不支持 `send_group_forward_msg`、或节点格式被拒），
并写一条 warn 日志说明原因；回退成功就照常确认已领取，**只有回退也失败才不确认并报错**——图片不会丢。

### 群互动与地图

| 指令 | 作用 |
|---|---|
| `.jrlp` | 今日老婆：随机抽一位群友（每群数据独立） |
| `.结婚 <@某人\|QQ号\|群名片>` | 求婚，对方须在 180 秒内 `.同意` 才成立 |
| `.同意` / `.拒绝` | 同意／拒绝最近一次向你的求婚 |
| `.强娶 <@某人\|QQ号\|群名片>` | 每人每天一次，只能强娶未婚配者 |
| `.离婚` | 每天一次，解除自己的婚配关系 |
| `.yh` / `.liv` | 发送粤海／丽湖地图 |
| `.map path` / `.map set yh\|liv <路径>` | 查看／设置地图路径（查看需 owner/admin，设置仅 owner） |

**地图随包分发**（v1.0.4 起）：`maps/yh/`（粤海）与 `maps/liv/`（丽湖）各带一张校园地图，
下载后 `.yh` / `.liv` 直接可用；目录按文件名排序发送，最多 10 张、每张最多 20MB，
支持 PNG/JPG/JPEG/GIF/WEBP/BMP。换成自己的图就覆盖这两个目录里的文件（或用 `.map set yh "路径"` 指到别处）。
这两张图是作者自备的校园地图素材，版权归原制图方，仅用于机器人发送地图这一功能，可随时替换或删除。

**通用规则**：宽高均为 64–2048 且为 8 的倍数；LoRA 权重默认 1、范围 0–2；
路径或名称含空格可以加双引号；目录按文件名发送，最多 10 张、每张最多 20MB；
生成任务按顺序逐个运行，采用**发出指令时**的完整参数。

**每个 LoRA 都带底模与归属栈，网页按栈分组**：`/lora list`、`/lora detail` 与网页「LoRA」面板都会给出
底模（基础模型）与它属于哪一栈，面板按**栈**分组显示（组头例如 `Anima 栈（4）`，旁边保留底模名），
每项也有自己的栈与底模标签。底模按**优先级**识别：safetensors 头部 `__metadata__` 声明的底模 →
Civitai 下载记录里的 `base_model` → Forge 的 LoRA 元数据（`/sdapi/v1/loras` 里每项的
`metadata.ss_base_model_version`，占位值 `model.safetensors` 不算）→ 头部张量名结构 →
都没有时按**当前 Forge 预设栈**推断并标注「按当前预设推断」；实在没有就如实显示「未识别」。
`GET /api/loras` 的每一项带 `baseModel` / `baseModelSource` / `stack` / `stackLabel` / `preset` /
`stackSource` / `evidence` / `groupKey`（栈键）/ `baseModelGroupKey`，并在 `groups` 里给出按**栈**分组的结果。

### Android 客户端（v1.4.0 起）

**是什么**：`android/` 是一个 **Android app**，把**整个网页控制台搬到手机上**。
做法是**原生外壳 + WebView 承载完整控制台**——app 里加载的就是你机器人发出来的那套网页，
所以**网页有的栏目 app 里一个都不少**：出图 / 提示词 / 样式 / LoRA / 提示词集 / 聊天配置 /
系统 / 首次配置 / 日志 / 回执 / 帮助（**11 个栏目**）+ **常驻右侧的对话栏**，共 **13 栏**。
也正因为它加载的是网页本体，**以后网页更新，app 自动跟着更新**，不用重新出包。

**原生增强（网页做不到或做不好的）**：

| 能力 | 说明 |
|---|---|
| 服务器管理 | 填地址与访问令牌，可存**多台服务器**一键切换；地址支持简写（`192.168.1.5:8787` 会自动补 `http://` 与默认端口 8787） |
| 测试连接 | 先打 `/healthz`，再打**带令牌的** `/api/status`，能**区分「连不上」和「令牌不对」** |
| 局域网扫描 | 按当前 Wi-Fi 网段扫 `:8787` 上的机器人，**找到就一键填入** |
| 令牌自动注入 | 不用在网页锁屏页里再敲一次令牌 |
| 图片保存 / 分享 | 在网页里**长按图片**即可存进相册（`Pictures/Pixiko`）或分享出去（网页版只能看大图） |
| 下拉刷新 + 进度条 + 原生错误页 | 连不上时列出可能原因（不在同一局域网 / 地址端口不对 / 机器人没开 / 令牌不对），并给「重试 / 去设置 / 用浏览器打开」 |

另外还有**返回键行为**（回退 → 回首页 → 再按一次退出）、**屏幕常亮**开关、**清网页缓存 / 清登录状态**、
「在浏览器打开」、关于页；文件选择、下载、新窗口、图片查看器都做了原生适配。

**三步用起来**：

1. 手机和电脑连**同一个 Wi-Fi**；
2. 打开 app，填「**电脑的局域网 IP:8787**」+「**访问令牌**」（令牌在电脑 `config.json` 的
   `webui.access_token`），或点「**扫描局域网**」；
3. 进去就是控制台，**长按图片**可存进手机相册。

**先看效果（不用装 APK，v1.4.1 起）**：控制台**每一页页脚**都有「安卓外观预览」链接（`href="/android"`），
也可以直接在浏览器里开 **`http://<电脑地址>:8787/android`**——机器人自己伺服的预览页，用**手机机身外框**
装下**同源的真控制台**（**11 个栏目 + 常驻对话栏一个不少**，手机宽度下自己变单列堆叠），并复刻顶部标题栏 /
进度条 / `⋮` 菜单（**9 项，与真机一致**）、服务器设置屏（含「测试连接」区分「连不上」与「令牌不对」）、
**长按 / 右键图片的原生菜单**与**原生错误页**。**与真机的差别**：「保存到相册」在浏览器里是**下载**、
**不能扫描局域网**、**屏幕常亮**依赖浏览器的 Wake Lock API。预览页与 APK 用的是**同一段长按脚本（逐字相同）**。

**怎么构建**：`android/` 是标准 Gradle 工程，仓库里带一键脚本 `android/build-apk.ps1`
（要 **JDK 21 + Android SDK**，跑 `assembleDebug` 并把 APK 复制到输出目录 `F:\Bot\android\dist\`）：

```powershell
powershell -File android\build-apk.ps1 -SdkRoot <Android SDK 路径> -JdkHome <JDK 21 路径>
```

工具链组件、参数、APK 产物路径与两种安装方式，见 [`android/README.md`](android/README.md)。

> **安全**：手机与电脑之间是局域网 **http 明文**传输，访问令牌也以**明文**存在 app 私有目录里，
> **只建议在家里或可信局域网用**；app 不向任何第三方服务器发数据，它只连你填的那台机器人。

---

## 六、目录结构

```
pixiko\
├─ README.md                       本文件
├─ RELEASE.md                      v1.4.1 发行说明（含版权声明与已知限制）
├─ THIRD-PARTY-LICENSES.md         随二进制包分发的第三方组件与许可
├─ config.example.json             脱敏配置模板（复制成 config.json 再改）
├─ .gitignore                      config.json / data / logs / lib jar 等一律不入库
│
├─ start.bat                       开箱即用包：跳过编译，直接启动预编译 jar
├─ run.bat                         构建 + 启动（--setup 走命令行配置向导）
├─ build.ps1                       编译打包到 build/pixiko.jar（-Test 连带跑测试）
├─ test.bat                        等价于 build.ps1 -Test
├─ stop-bot.ps1                    停止机器人
├─ install-webui-bridge.ps1        安装 SD WebUI 桥接扩展
├─ eval-chain.ps1                  提示词链路评测：多步指令链
├─ eval-effect.ps1                 提示词链路评测：改写效果
├─ eval-scale.ps1                  提示词链路评测：规模/抽样
├─ eval-decompose.ps1              提示词链路评测：场景拆解
│
├─ android/                        Android 客户端（原生外壳 + 完整控制台，v1.4.0 起）
│  ├─ README.md                    是什么、怎么用、怎么构建 APK、安全与已知限制
│  ├─ build-apk.ps1                一键构建：assembleDebug 并把 APK 复制到输出目录
│  ├─ build.gradle / settings.gradle / gradle.properties
│  └─ app/                         app 模块（Java 源码、资源、清单）
│
├─ src\
│  ├─ main\java\cn\szu\bot\        机器人本体（37 个 .java）
│  │  ├─ Main.java                 入口：--setup / --check / --set-map / --help
│  │  ├─ Bot.java                  指令分发、生成队列、Civitai 登录、网页接口聚合
│  │  ├─ Settings.java             config.json 读写与校验
│  │  ├─ Setup.java                命令行配置向导（网页配置页见 WebConfigController / WebSetup）
│  │  ├─ Json.java / Log.java / Maps.java / Marriage.java
│  │  ├─ chat\                     ChatService / DeepSeekPrompts / PersonaState / 原作语料索引
│  │  ├─ civitai\                  CivitaiClient / 样式同步
│  │  ├─ prompt\                   提示词编辑、分类、中文词库索引
│  │  ├─ qq\                       NapCat(OneBot v11) 客户端
│  │  ├─ sd\                       SD 客户端、启动器、队列、参数与预设、个人提示词存储
│  │  └─ web\                      Spring Boot 网页层（鉴权、API、页面）
│  └─ test\java\cn\szu\bot\        60 个测试套件（*Test.java，由 test.bat 逐个运行）+ 5 个评测类（*Eval.java，由 eval-*.ps1 运行）
│
├─ webui\                          Java 版网页控制台（9 个文件，静态资源）
│  ├─ index.html                   外壳 + 11 个栏目页签（另含全局右侧对话栏）
│  ├─ app.js / app.css             前端逻辑与样式
│  ├─ android-preview.html         Android 外壳的网页预览页（控制台 /android 伺服）
│  └─ favicon.ico / favicon-32.png / apple-touch-icon.png / icon-192.png / icon-512.png
│
├─ webui-extension\pixiko-bridge\  SD WebUI 桥接扩展
│  ├─ scripts\pixiko_bridge.py     后端：读写正在编辑的提示词/采样器/样式/宽高
│  ├─ javascript\pixiko_bridge.js  前端：同步 UI 控件
│  ├─ tests\                       自带测试（python unittest + node --test）
│  └─ README.md                    安装与本地 API 说明
│
├─ data\                           中文词库/词表（prompt-*）；danbooru\ 是上游原始词表（只在源码包）；其它 data 内容不进包
├─ maps\                           yh/（粤海）、liv/（丽湖）各一张校园地图，`.yh`/`.liv` 开箱可用
├─ tools\                          可选 Node 工具（词库与自检）
│  ├─ webui-selfcheck.mjs          网页控制台端到端自检
│  ├─ build-zh-tags.mjs            生成中文词库
│  ├─ annotate-zh-tags.mjs         生成用法表
│  ├─ validate-zh-extra.mjs        校验手工词表
│  ├─ audit-zh-translations.mjs    模型审查错误翻译
│  ├─ audit-zh-report.mjs          汇总纠正记录
│  ├─ diff-zh-tags.mjs             词库前后差异
│  └─ offline-notice.mjs           离线提示
│
├─ docs\                           重构过程记录（历史文档，见文首说明）
│  ├─ MIGRATION.md                 Java → Next.js 迁移记录
│  ├─ PORTING.md                   迁移期代码约定
│  └─ PANEL-PORT.md                控制台面板移植约定
│
└─ nextjs-wip\                     未完成的 Next.js 重构（不可运行，勿用于生产）
   ├─ README.md                    为什么不能跑、缺了什么
   ├─ app\ components\ public\     Next 路由、控制台组件、图标
   ├─ server.mjs / instrumentation.ts / next.config.ts / tsconfig.json
   └─ package.json / package-lock.json
```

---

## 七、许可与免责

### 版权声明

**代码版权归 loriko（deloriko@outlook.com）所有，保留所有权利（All Rights Reserved）。**
本仓库**未使用任何开源许可证**（GitHub 上 License 一栏就是 None）。

- 代码**仅供个人自用与研究**。未经作者书面许可，**不得再分发、不得商用、不得用于任何在线服务**
  （包括但不限于把它跑成对公众开放的服务、把二进制或源码重新打包发布）。
- 二次分发（在获得许可的前提下）请**先取得作者许可并完整保留作者信息**与本声明。
- 作者未授予任何商业使用许可，也未附带 OSI 认证的开源许可证文件。你要用就自己承担后果。

### 免责

- 使用本项目时请遵守所依赖服务的服务条款：**NapCat / OneBot**、**Stable Diffusion WebUI (A1111)**
  以及 **Civitai**，包括它们的速率限制与账号规则。用 `civitai.session_cookie` 抓取与下载模型
  属于自动化访问，风险自负。
- **角色与作品版权归原作者。** 机器人的默认人设指向《**Rewrite**》的**神户小鸟**，
  该角色与作品版权属于 **Key / VisualArts**。本项目与官方**没有任何关系**，
  不是官方作品，也未获官方授权或认可。
- **原作对白语料（`data/kotori-corpus.txt`）从 v1.0.6 起随仓库与发行包分发。**
  它是《Rewrite》的原创对白文本（45 个场景、2,707 句小鸟台词、122,675 字符），
  **版权属于 Key / VisualArts**，本项目**未获授权**、也不以开源许可分发；由仓库所有者决定随包提供，
  仅用于「原作语料复现」这一功能。**权利人若提出异议，这个文件会被立即删除**（删掉它即可，
  其余功能不受影响：文件不存在时 `chat.corpus_replay` 静默降级为只走口癖锚点）。
- 仓库中不包含任何真实凭据：Civitai Cookie、网页访问令牌、DeepSeek 密钥、真实 QQ 号
  与个人目录路径都已被移除或替换为占位符。
- **中文词库随包分发**（`data/prompt-*.json|txt|md`，来源与许可见 「提示词与中文词库」一节与
  `THIRD-PARTY-LICENSES.md`）；`data/` 里仍然不进包的是：DeepSeek 密钥、生成图、队列状态等运行期数据。

> 随二进制发行包一起分发的第三方组件（Spring Boot 系列、gson）均为 Apache-2.0，
> 清单见 [`THIRD-PARTY-LICENSES.md`](THIRD-PARTY-LICENSES.md)。

---

## 八、关于 `nextjs-wip/`

`nextjs-wip/` 里是一次**未完成的 Next.js + TypeScript 重构**的残留代码：后端 `lib/**`
（约 60 个文件，机器人本体）**已经丢失**，`app/**` 与 `components/**` 全都靠 `import '@/lib/...'`
拿后端，因此这份代码**无法构建、无法运行**；`tests/**` 那批 TS 测试依赖同一个丢失的后端，
所以也没有收进仓库。**请勿用于生产。** 保留它只是因为里面的接口形状、面板 id/class
与 Java 版逐条对得上，想重做网页层时可以抄；详细说明见 [`nextjs-wip/README.md`](nextjs-wip/README.md)。
同样的原因，`docs/` 下那三份文档也是那次重构的过程记录，**当前实现以 Java 版为准**。
