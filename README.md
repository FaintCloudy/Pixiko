# Pixiko —— QQ + Stable Diffusion 出图机器人

Pixiko 是一个自用的 QQ 机器人：接 **NapCat** 收消息，接 **Stable Diffusion WebUI** 出图，
用 **DeepSeek** 做提示词改写与日常聊天。群友用 `.` 开头的指令排队生成、管理提示词与 LoRA、
领图；机器人自己把生成好的图片发回对话。

- **作者**：loriko（deloriko@outlook.com）
- **版本**：v1.0.10（发行说明见 [`RELEASE.md`](RELEASE.md)）
- **当前实现**：**Java 版**（`src/`）——这是线上一直在跑的那一份
- **网页控制台**：`webui/`（纯静态 HTML/CSS/JS，随机器人一起由内嵌 Spring Boot 提供）
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
| `webui` | `enabled` / `host` / `port` | 网页控制台开关与监听地址（默认 `0.0.0.0:8787`） |
| | `access_token` | **网页控制台访问令牌。示例里是 `"change-me"`，请务必改掉**；首次启动若为空会自动生成并写回 `config.json`，日志里有 |
| | `scope` | 控制台操作归属的会话作用域 |
| `progen` | `model` / `thinking` / `reasoning_effort` / `max_tokens` / `timeout_seconds` / `api_key_file` | **生图频道**的 DeepSeek 设置，密钥文件默认 `data/deepseek-api-key.txt` |
| `chat_api` | 同上 | **聊天频道**的 DeepSeek 设置，密钥文件默认 `data/deepseek-chat-api-key.txt`。两条通道完全独立：模型、思考开关、密钥各管各的 |
| `chat` | `personality` | 人格设定。**示例里只有 3～5 行占位文本**，真实人格由 owner 自己写（`.chat personality` 整段替换、`.chat add` 追加、`.chat infix` 让模型改） |
| | `enabled` / `frequency` / `reply_base_probability` / `reply_probability_scale` | 聊天总开关、回复频率上限、主动插话概率 |
| | `wake_probability` / `chime_cooldown_seconds` / `topic_gap_seconds` / `base_min_interest` / `chime_high_interest` | 主动插话的按会话权重与冷却（示例里是 `{}`） |
| | `corpus_replay` / `corpus_top_k` | 原作语料复现开关与召回条数，见下面的版权说明 |
| | `disabled_conversations` | 已关掉日常聊天的会话列表 |
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

群聊先 @机器人或叫她的名字唤醒，同一用户之后 **30 分钟内**可连续对话；私聊不用唤名。
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
| `.chat base [0\|1]` | 主动插话总开关：0 永不主动插话，1 由模型按上下文判断 |
| `.chat corpus on\|off` | 原作语料复现开关（仅 owner） |
| `.chat notice on\|off` | 开关上／下线播报是否发到主群（仅 owner/admin） |
| `.chat log on\|off` | 开关把 WARN/ERROR 同步到主群（仅 owner/admin） |
| `.affinity` / `.affinity <QQ号>` | 查看好感度与分档；查他人仅 owner |
| `.admin` / `.admin list` | 查看用法／查看 owner 与 admin 名单 |
| `.admin add <@成员\|QQ号\|群名片>` | 添加 admin（仅 owner） |
| `.admin remove <@成员\|QQ号\|群名片>` | 移除 admin（仅 owner） |
| `.batch <指令1> ; <指令2> ; …` | 一条消息顺序执行多条指令（最多 20 条） |

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

### 样式、参数与提示词集

| 指令 | 作用 |
|---|---|
| `.style` / `.style list` | 查看／列出机器人样式库 |
| `.style save <名称>` / `.style overwrite <名称>` | 保存／覆盖当前提示词为样式（含 LoRA 标签） |
| `.style prompt <名称\|#编号>` | 查看样式原文 |
| `.style load <名称\|#编号> [nolora]` | 用样式替换当前正反向 prompt |
| `.style rename [overwrite] <旧名称\|#编号\|#6-#9> <新名称或前缀>` | 改名 |
| `.style delete <名称\|#编号\|#6-#9>` | 删除 |
| `.style import webui [overwrite]` | 把 WebUI 预设样式一次性搬进机器人样式库 |
| `.settings` | 查看尺寸、采样方法、步数、CFG、种子、基础模型与来源 |
| `.sampler` / `.sampler list` / `.sampler set <完整名称>` | 采样方法 |
| `.size` / `.size set <宽> <高>` | 图片宽高（64–2048 且为 8 的倍数，也支持 `768x512`） |
| `.steps [set <步数>]` / `.cfg [set <数值>]` / `.seed [set <种子>]` | 步数／CFG／种子（种子 `-1` 为随机） |
| `.model` / `.model list` / `.model set <完整名称>` / `.model set auto` | 基础模型（`auto`＝每次提交时用 WebUI 当前模型） |
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
每条样式也标着自己的底模。

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
| `.lora query <模型搜索词>` | 搜索 Civitai，显示编号及封面 |
| `.lora download #编号 [权重]` / `.lora download <Civitai链接> [权重]` | 下载并启用，同时把展示图提示词存成样式 |
| `.lora status` | 查看最近下载状态 |
| `.lora list` | 列出 WebUI 本地 LoRA（每项标出底模，并在上方按底模分组） |
| `.lora detail <名称\|#编号>` | 查看一个本地 LoRA 的底模（含来源）、Civitai 记录与它的展示图样式 |
| `.lora load <完整本地名称> [权重]` | 重新加载或启用已有 LoRA |
| `.lora rename <旧本地名称> <新本地名称>` | 重命名本地 LoRA 并同步个人 prompt 标签 |
| `.lora delete <名称\|#编号>` | 删除本地 LoRA（只允许 LoRA 目录下的文件；**未列在 `--help` 输出里，但代码中可用**） |
| `.char <角色名或关键词>` | 在本机 LoRA 与 WebUI 样式里查角色候选 |

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

**每个 LoRA 都带底模，网页按底模分组**：`/lora list`、`/lora detail` 与网页「LoRA」面板都会给出
底模（基础模型），面板按底模分组显示（组头例如 `Anima（4）`），每项也有自己的底模标签。
底模按**优先级**识别：Civitai 下载记录里的 `base_model` → Forge 的 LoRA 元数据
（`/sdapi/v1/loras` 里每项的 `metadata.ss_base_model_version`，占位值 `model.safetensors` 不算）→
都没有时按**当前 Forge 预设栈**推断并标注「按当前预设推断」；实在没有就如实显示「未识别」。
`GET /api/loras` 的每一项带 `baseModel` / `baseModelSource`（`civitai` / `forge-metadata` /
`preset-inferred`）/ `groupKey`，并在 `groups` 里给出按底模分组的结果给网页直接用。

---

## 六、目录结构

```
pixiko\
├─ README.md                       本文件
├─ RELEASE.md                      v1.0.10 发行说明（含版权声明与已知限制）
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
│  └─ test\java\cn\szu\bot\        51 个测试/评测类（*Test.java 由 test.bat 逐个运行）
│
├─ webui\                          Java 版网页控制台（8 个文件，静态资源）
│  ├─ index.html                   外壳 + 10 个栏目
│  ├─ app.js / app.css             前端逻辑与样式
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
