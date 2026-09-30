# Pixiko v1.0.6 发行说明

- **版本**：v1.0.6
- **日期**：2026-09-27
- **作者**：loriko（deloriko@outlook.com）
- **当前实现**：Java 版（`src/`）。另有一次**未完成的** Next.js 重构，见 `nextjs-wip/`，**不可运行**。

---

## 〇、本版新增（v1.0.6）

**《Rewrite》原作对白语料随仓库与发行包分发**：`data/kotori-corpus.txt`（324 KB，45 个场景、
2,707 句小鸟台词 + 瑚太朗等角色 2,820 句，共 122,675 字符）进了仓库、源码包与开箱即用包，
下载后「原作语料复现」（`chat.corpus_replay`，默认开）直接生效，不用再自己准备语料：
命中相近的原作问答时会参考原句说话，同时注入 2,689 组问答、45 个场景的锚点素材
（启动日志会打印「原作语料已载入：2689 组问答（45 个场景）…」）。

> **版权声明（请务必读）**：这个文件**不是开源内容，也不是本项目创作**，版权属于
> **Key / VisualArts**；本项目与官方没有任何关系、未获授权或认可，该文件不附带任何许可证。
> 它由仓库所有者决定随包提供，使用者请自行判断合规风险；
> **权利人若提出异议，会立即删除该文件**（连同 `.gitignore` 里的白名单行），
> 机器人随后静默降级为只走口癖锚点，其余功能不受影响。
> 明细见 `THIRD-PARTY-LICENSES.md` 与 `README.md` 版权一节。

<details>
<summary>上一版（v1.0.5）</summary>

**控制台多一个「配置」栏目，Civitai 账号改成黏一条一次性登录链接**：原来独立的首次运行配置页
（`webui/setup.html`）并进控制台，导航栏新增「配置」（`/setup`，**URL 不变**）：机器人名字、owner QQ、
两条 DeepSeek 通道（带「测试连接」）、SD 与 NapCat 地址、控制台访问令牌都在这一页改。Civitai 账号也挪到这里，
并且不再需要手动抄 Cookie：把登录邮件里那条 `https://auth.civitai.com/login/email/verify?token=…`
**整条粘进去**，机器人替浏览器走完跳转链（最多 10 跳，逐跳收 `Set-Cookie`，只保留 civitai 域、丢弃
`oauth_bridge`），写入 `civitai.session_cookie` 后再带它验证一次。**链接里的一次性令牌与 Cookie 都不进日志**
（只出现脱敏提示）。随之删除 `/civitai-login` 页面与 `/api/civitai/login-link` 接口（`/civitai-cookie` 按设计保留）。

**提示词输入框的 tab 补全**：正向/反向提示词与「添加词条」输入框里敲几个字母或中文，按 **Tab / Enter** 补成标准
Danbooru 词条（↑↓ 选择、Esc 关闭；空词条上按 **Ctrl+Space** 列最热词条）。候选来自随包的
`data/prompt-tags.txt`（14 万条，二分前缀匹配）与 `data/prompt-zh-tags.json`（3.7 万条中文写法 + 分类 + 热度），
按热度排序、按文件 mtime 缓存，词库缺失时自动退化成另一侧可用；接口为 `POST /api/tags`。

**三处图片显示问题**：

- 回执与对话里的 Civitai 封面本身是图床 URL，却被当成 `data/generated` 下的本地图片去取，所以一直显示不出来；
  现在改走封面代理，代理失败会在图上直接显示 HTTP 状态（401/429/502），非 Civitai 域名退回原地址再试一次。
- 出图面板以前只列「待领取」，而任务完成后默认会自动领取，于是**刷新（或重启）后图片就消失**；
  现在列出「最近生成（扫 `data/generated`，重启后仍在）∪ 待领取」，并标出哪些还没领取。
- 回执与对话改成**按消息分条渲染**：一条 LoRA 搜索结果就是一条消息（自己的编号/名称/基础模型/链接 + 自己的封面），
  不再是「所有文字一堆、所有图片一堆」；发送失败时余下条目自动退化成纯文本列表。

**修掉提示词集面板打不开**：`fillSelect()` 会去填只存在于出图面板的采样方法/模型下拉框，提示词集页因此报
「加载失败：Cannot set properties of null (setting 'innerHTML')」；已补空值保护。

**新增 macOS 启动脚本**：`run-macos.sh`（等价 `run.bat`：找 JDK 17+、校验或下载 gson、编译、打包、启动）与
`start-macos.sh`（等价 `start.bat`：只启动已编译好的 jar）。用 macOS 自带 bash 3.2 语法写成，
`-Dbot.home` 与「Spring jar 只挂运行期 classpath、不合并进 jar」这两条约定与 Windows 版完全一致。

**`config.example.json` 去掉 Windows 示例路径**：`sd.root` 与 `civitai.lora_dir` 默认改为空字符串
（留空表示不用机器人拉起 SD、用不了 Civitai 下载），`README.md` 对应说明同步更新。

</details>


<details>
<summary>更早版本（v1.0.4）</summary>

**v1.0.4 —— 粤海 / 丽湖地图随包分发**：`maps/yh/`（2480×3367）与 `maps/liv/`（1280×1810）各带一张校园地图，
下载后 `.yh` / `.liv` 直接可用，不用再自己往 `maps/` 里放图。目录按文件名排序发送，
最多 10 张、每张最多 20MB，支持 PNG/JPG/JPEG/GIF/WEBP/BMP；换成自己的图覆盖文件即可
（或用 `.map set yh "路径"` 指到别处）。

- 这两张是作者自备的校园地图素材，版权归原制图方，本项目未声明授权，仅用于 `.yh`/`.liv` 发送地图；
  介意的话删掉这两个文件并重新打包即可（机器人会照常提示「还没有地图图片」）。见 `THIRD-PARTY-LICENSES.md`。
- `.gitignore` 不再忽略 `maps/**` 下的图片。

</details>

<details>
<summary>更早版本（v1.0.3 / v1.0.2 / v1.0.1）</summary>

**上游 danbooru 原始词表一并入库**（`data/danbooru/`，9.8 MB）——从此**不装 SD WebUI 扩展也能重建中文词库**：

| 文件 | 行数 | 体积 | 作用 |
|---|---|---|---|
| `data/danbooru/danbooru.main-140782.csv` | 140,782 | 3.4 MB | 上游 main 的 `tags/danbooru.csv`；`data/prompt-tags.txt`（140,779 条）取它的第 1 列导出 |
| `data/danbooru/danbooru.csv` | 121,034 | 3.0 MB | 本机扩展 2024-12-19 快照；`tools/build-zh-tags.mjs` 从它取分类与热度 |
| `data/danbooru/danbooru.zh_CN_SFW.csv` | 99,293 | 3.0 MB | 上游中文翻译表（SFW） |

- 三个文件的 `sha256` 与 `data/prompt-tags.source.json`、`data/prompt-zh-tags.json` 里记录的校验值**完全一致**
  （可据此确认拿到的是同一份），都是上游 **MIT**（© 2022 Dominik Reh）原样分发、未做修改。
- `tools/build-zh-tags.mjs` 的 `--tags-dir` 默认值从作者本机的 SD 扩展目录改为**仓库内的 `data/danbooru`**；
  `node tools\build-zh-tags.mjs --check` 现在能在仓库里直接跑通，输出「与现有 data/prompt-zh-tags.json 一致」——
  随包的词库与随包的词表互相可复现（34,211 条逐条比对，差异 0）。
- 顺手清掉了公开文件里的本机绝对路径：`prompt-zh-tags.json` 的 `sources[].file` 与 `prompt-usage.json` 的
  `source_file` 现在都写成仓库相对路径（此前是 `F:\sd\...`）。**词条内容一个字节没变**（逐条比对差异 0）。
- **打包口径**：`data/danbooru/`（原始词表）**只进源码包**；开箱即用包里不含它（运行时不需要，避免白涨 9.8 MB）。

**v1.0.3 —— 上游 danbooru 原始词表入库**（`data/danbooru/`，9.8 MB，只进源码包）：`danbooru.main-140782.csv`（`prompt-tags.txt` 的来源）、`danbooru.csv`（分类与热度）、`danbooru.zh_CN_SFW.csv`（中文翻译）；`tools/build-zh-tags.mjs` 的 `--tags-dir` 默认值改为仓库内 `data/danbooru`，`--check` 在仓库/源码包里输出「一致」；清掉了公开文件里的本机绝对路径。

**v1.0.2 —— 中文词库随包分发**：`data/` 下的提示词词库进了仓库与两个发行包，下载后开箱就有词库可用：
`data/prompt-tags.txt`（140,779 条标准词条）、`data/prompt-usage.json`（11 类分类词库）、
`data/prompt-zh-tags.json`（34,211 条中文↔标准词条 / 38,941 个中文写法）、`data/prompt-zh-extra.txt`（手工同义词）、
`data/prompt-zh-usage.md`（用法表）、`data/prompt-zh-use-notes.txt`、`data/prompt-tags.source.json`。
上游两个词表均为 MIT（a1111-sd-webui-tagcomplete © 2022 Dominik Reh；sd-webui-prompt-all-in-one © 2023 Physton）。

**v1.0.1**
1. **首次配置改到网页端**：第一次启动不再停在命令行等输入，而是自动打开
   `http://127.0.0.1:8787/setup`（缺 DeepSeek 密钥时本机免令牌），一页填完机器人名字、owner QQ、
   两条 DeepSeek 通道、SD 与 NapCat 地址。命令行向导保留为 `run.bat --setup`。
2. **网页端可改 DeepSeek 地址与密钥**：控制台「系统 → DeepSeek 通道」直接改两条通道的
   `api_base` 与密钥，带「测试连接」与「清空密钥」；**改完立刻生效，不用重启**。
   密钥只写进 `data/*-api-key.txt`，接口只回显掩码（`sk-6…222`），任何响应里都不出现原文。
3. **第一次运行自动生成 `config.json`**：解压后直接双击 `start.bat` 即可（包内没有 `config.json`，
   也没有任何真实凭据）；有 `config.example.json` 就照它起一份。
4. **识别群禁言，不再硬发**：全员禁言或机器人自己被禁言时不再尝试发送，日志里只记一次
   「禁言中，跳过发送」，不再刷一屏 `retcode=1200`；解除通知 / 到期 / 每分钟回查三条路自动恢复，
   禁言期间聊天不发起模型调用（省额度）。见 `README.md`「被禁言时不会硬发」。

</details>

---

## 一、版权声明

**代码版权归 loriko（deloriko@outlook.com）所有，保留所有权利（All Rights Reserved）。**

- 本仓库**未使用任何开源许可证**（GitHub 上 License 一栏为 **None**）。**没有** `LICENSE` 文件，
  也没有 MIT / GPL 之类的授权文本——这是所有者的明确选择，不是遗漏。
- 代码**仅供个人自用与研究**。未经作者书面许可，**不得再分发、不得商用、不得用于任何在线服务**
  （包括但不限于把它跑成对公众开放的服务、把源码或二进制重新打包发布）。
- 二次分发（在获得许可的前提下）请**先取得作者许可并完整保留作者信息**与本声明。
- **角色与作品版权归原作者**：机器人默认人设指向《**Rewrite**》的**神户小鸟**，
  该角色与作品版权属于 **Key / VisualArts**。本项目与官方**没有任何关系**，非官方作品，
  未获官方授权或认可。
- 随二进制包分发的第三方组件许可见 `THIRD-PARTY-LICENSES.md`。

---

## 二、运行环境要求

| 项目 | 要求 |
|---|---|
| 操作系统 | Windows 10 / 11；macOS 可用附带的 `run-macos.sh` / `start-macos.sh`（构建脚本 `build.ps1` 仍需要 PowerShell） |
| Java | **JDK 17 或更高**，`java`（开箱即用包）或 `java` + `javac` + `jar`（源码包）在 `PATH` 里 |
| Stable Diffusion WebUI | A1111 系，启动参数**必须含 `--api`**，默认地址 `http://127.0.0.1:7860` |
| NapCat | **可选**。开启正向 WebSocket 服务器；不开也能用网页控制台与出图 |
| DeepSeek API Key | **两条独立通道各一个**：生图频道（`progen`）与聊天频道（`chat_api`） |
| Node.js | **可选**，只有网页自检与中文词库工具需要（`tools/*.mjs`） |
| PowerShell 5.1 | Windows 自带。源码包构建需要它；开箱即用包不需要 |

---

## 三、安装与启动（三步）

### 开箱即用包 `pixiko-v1.0.6-runnable.zip`

1. 装好 **JDK 17+**。
2. **双击 `start.bat`**。第一次运行会自动生成 `config.json`（照 `config.example.json` 起一份），
   并在浏览器打开配置页 `http://127.0.0.1:8787/setup`；在那里填 owner QQ 与两条 DeepSeek 通道即可。
   没有浏览器时也可以先 `copy config.example.json config.json` 手动填，或运行 `run.bat --setup` 用命令行向导。
3. 配好即用；之后要改 DeepSeek 地址/密钥，直接在控制台「系统 → DeepSeek 通道」改。

> `start.bat` 跑的是包内已编译好的 `build/pixiko.jar`；只有需要改代码时才用 `build.ps1` + `run.bat`。

### 源码包 `pixiko-v1.0.6.zip`

1. 装好 **JDK 17+**。
2. 在项目根目录准备好依赖 jar：`lib/gson-2.13.1.jar` 由 `build.ps1` **自动下载并校验**，
   但 `lib/spring/` 下的 Spring Boot 运行时**不随包分发**，需要按 `README.md`「环境要求」一节自行放入。
3. `copy config.example.json config.json` 并填好，然后 `run.bat`（先构建再启动）。

```powershell
cd <解压目录>
copy config.example.json config.json
.\run.bat
```

---

## 四、常用脚本

| 脚本 | 作用 |
|---|---|
| `start.bat` | **开箱即用包专用**：跳过编译，直接用预编译 jar 启动 |
| `run.bat` | 源码包：先 `build.ps1` 构建再启动；`run.bat --setup` 进命令行配置向导（网页配置页 `/setup` 是默认入口） |
| `run-macos.sh` | **macOS**：等价 `run.bat`——找 JDK 17+ → 校验或下载 gson → 编译 → 打包 → 启动；参数原样转给机器人 |
| `start-macos.sh` | **macOS**：等价 `start.bat`——只启动已编译好的 `build/pixiko.jar` |
| `build.ps1` | 编译 `src/main/java` 并打包 `build/pixiko.jar`；`-Test` 连测试一起跑 |
| `test.bat` | 等价于 `build.ps1 -Test`：编译并逐个运行全部 `*Test` |
| `stop-bot.ps1` | 停止正在运行的机器人 |
| `install-webui-bridge.ps1` | 安装 SD WebUI 桥接扩展（`-WebUiRoot` 指定 WebUI 根目录） |
| `eval-chain.ps1` / `eval-effect.ps1` / `eval-scale.ps1` / `eval-decompose.ps1` | 提示词链路评测脚本 |
| `node tools\webui-selfcheck.mjs --token <令牌>` | 网页控制台自检（令牌用 `webui.access_token`，或设环境变量 `PIXIKO_WEBUI_TOKEN`） |

其他启动参数：`run.bat --help`（指令总表）、`--check`（只测 SD 连通性）、`--setup`（命令行配置向导）、
`--set-map yh "路径"`（命令行设地图）。这四个参数由 `Main` 解析，`start.bat` 同样透传。
完整指令总表见 `README.md` 第五节。

---

## 五、已知限制

1. **`nextjs-wip/` 未完成、不可运行。** 那次 Next.js + TypeScript 重构的后端 `lib/**`（约 60 个文件）已丢失，
   `app/**` 与 `components/**` 都 `import '@/lib/...'`，因此无法构建；依赖同一后端的 `tests/**` 也没有收进仓库。
   详见 `nextjs-wip/README.md`。**Java 版才是当前实现。**
2. **原作对白语料从 v1.0.6 起随包分发**（`data/kotori-corpus.txt`，见「本版新增」）。它是《Rewrite》的原创文本，
   **版权归 Key / VisualArts**，未获授权、不附带许可证，由仓库所有者决定随包提供，使用者自行判断合规风险；
   **权利人若提出异议会立即删除**——删掉该文件即可，语料复现功能（`chat.corpus_replay`）随后自动降级为只走口癖锚点，
   其余功能不受影响。
3. **第三方 jar 不随源码包分发。** `lib/spring/`（Spring Boot 运行时，23 个 jar / 约 17 MB）需自行获取，
   否则 `build.ps1` 会以 `Missing lib\spring ...` 退出；`lib/gson-2.13.1.jar` 由脚本自动下载并做 SHA-256 校验。
   **开箱即用包已包含这两者**，不需要你操心。
4. **本包不含任何真实凭据。** 没有 QQ 号、Civitai Cookie、网页访问令牌或 DeepSeek 密钥；
   `config.json` 与 `data/` 里的敏感内容（密钥、生成图、队列状态）都不在包里。
   `data/` 里随包分发中文词库与词表（`data/prompt-*`）；源码包里另有 `data/danbooru/` 上游原始词表，
   开箱即用包不含后者。另有空的 `logs/`、`maps/` 占位目录。**第一次运行必须自己配置 owner 与密钥**。
5. **默认没有 owner。** 公开代码里 `Settings.DEFAULT_OWNER` 是空字符串（原版本内置了作者 QQ，
   属于个人信息，已移除）。**务必在配置页或 `config.json` 里设置 `owner_user_id`**，否则所有 owner 专属指令都会被拒绝。
6. **`test.bat` 的行为依赖工作目录。** `WebUiTest` 通过「进程工作目录下的 `webui/`」找页面资源
   （见 `WebPageController` 的回退逻辑），所以请在**项目根目录**运行 `test.bat`，
   不要从别的目录调用。在项目根目录运行，48 个测试全部通过（含 CivitaiLinkTest、TagSuggestTest）。
7. **Logback 两个 jar 内不含许可文本**（上游如此），其 EPL-1.0 / LGPL-2.1 全文需查
   `THIRD-PARTY-LICENSES.md` 里给出的官方链接；`gson` 与 `snakeyaml` 的 jar 内同样没有许可文件，
   但二者均为 Apache-2.0，文本已随包提供。

---

## 六、在 GitHub Releases 里发布这个 zip

1. 打开仓库 → 右侧 **Releases** → **Draft a new release**，Tag 填 `v1.0.6`（新建 tag），标题填 `Pixiko v1.0.6`。
2. 把 `pixiko-v1.0.6.zip` 与 `pixiko-v1.0.6-runnable.zip`（以及各自的 `.sha256`）拖进附件区，
   正文粘贴本文件内容后点 **Publish release**。

> 建仓库时 License 请选 **None**（本项目保留所有权利，不使用开源许可证）。
