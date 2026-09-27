# Pixiko v1.0.4 发行说明

- **版本**：v1.0.4
- **日期**：2026-09-27
- **作者**：loriko（deloriko@outlook.com）
- **当前实现**：Java 版（`src/`）。另有一次**未完成的** Next.js 重构，见 `nextjs-wip/`，**不可运行**。

---

## 〇、本版新增（v1.0.4）

**粤海 / 丽湖地图随包分发**：`maps/yh/`（2480×3367）与 `maps/liv/`（1280×1810）各带一张校园地图，
下载后 `.yh` / `.liv` 直接可用，不用再自己往 `maps/` 里放图。目录按文件名排序发送，
最多 10 张、每张最多 20MB，支持 PNG/JPG/JPEG/GIF/WEBP/BMP；换成自己的图覆盖文件即可
（或用 `.map set yh "路径"` 指到别处）。

- 这两张是作者自备的校园地图素材，版权归原制图方，本项目未声明授权，仅用于 `.yh`/`.liv` 发送地图；
  介意的话删掉这两个文件并重新打包即可（机器人会照常提示「还没有地图图片」）。见 `THIRD-PARTY-LICENSES.md`。
- `.gitignore` 不再忽略 `maps/**` 下的图片。

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

<details>
<summary>更早版本（v1.0.2 / v1.0.1）</summary>

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
| 操作系统 | Windows 10 / 11 |
| Java | **JDK 17 或更高**，`java`（开箱即用包）或 `java` + `javac` + `jar`（源码包）在 `PATH` 里 |
| Stable Diffusion WebUI | A1111 系，启动参数**必须含 `--api`**，默认地址 `http://127.0.0.1:7860` |
| NapCat | **可选**。开启正向 WebSocket 服务器；不开也能用网页控制台与出图 |
| DeepSeek API Key | **两条独立通道各一个**：生图频道（`progen`）与聊天频道（`chat_api`） |
| Node.js | **可选**，只有网页自检与中文词库工具需要（`tools/*.mjs`） |
| PowerShell 5.1 | Windows 自带。源码包构建需要它；开箱即用包不需要 |

---

## 三、安装与启动（三步）

### 开箱即用包 `pixiko-v1.0.4-runnable.zip`

1. 装好 **JDK 17+**。
2. **双击 `start.bat`**。第一次运行会自动生成 `config.json`（照 `config.example.json` 起一份），
   并在浏览器打开配置页 `http://127.0.0.1:8787/setup`；在那里填 owner QQ 与两条 DeepSeek 通道即可。
   没有浏览器时也可以先 `copy config.example.json config.json` 手动填，或运行 `run.bat --setup` 用命令行向导。
3. 配好即用；之后要改 DeepSeek 地址/密钥，直接在控制台「系统 → DeepSeek 通道」改。

> `start.bat` 跑的是包内已编译好的 `build/pixiko.jar`；只有需要改代码时才用 `build.ps1` + `run.bat`。

### 源码包 `pixiko-v1.0.4.zip`

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
2. **原作对白语料不随包分发。** `data/kotori-corpus.txt` 是《Rewrite》的原创文本，有版权，需**自备**。
   该文件不存在时，语料复现功能（`chat.corpus_replay`）**自动降级**——静默失效，其余功能不受影响。
3. **第三方 jar 不随源码包分发。** `lib/spring/`（Spring Boot 运行时，23 个 jar / 约 17 MB）需自行获取，
   否则 `build.ps1` 会以 `Missing lib\spring ...` 退出；`lib/gson-2.13.1.jar` 由脚本自动下载并做 SHA-256 校验。
   **开箱即用包已包含这两者**，不需要你操心。
4. **本包不含任何真实凭据。** 没有 QQ 号、Civitai Cookie、网页访问令牌或 DeepSeek 密钥；
   `config.json` 与 `data/` 里的敏感内容（密钥、生成图、队列状态、原作语料）都不在包里。
   `data/` 里随包分发中文词库与词表（`data/prompt-*`）；源码包里另有 `data/danbooru/` 上游原始词表，
   开箱即用包不含后者。另有空的 `logs/`、`maps/` 占位目录。**第一次运行必须自己配置 owner 与密钥**。
5. **默认没有 owner。** 公开代码里 `Settings.DEFAULT_OWNER` 是空字符串（原版本内置了作者 QQ，
   属于个人信息，已移除）。**务必在配置页或 `config.json` 里设置 `owner_user_id`**，否则所有 owner 专属指令都会被拒绝。
6. **`test.bat` 的行为依赖工作目录。** `WebUiTest` 通过「进程工作目录下的 `webui/`」找页面资源
   （见 `WebPageController` 的回退逻辑），所以请在**项目根目录**运行 `test.bat`，
   不要从别的目录调用。在项目根目录运行，46 个测试全部通过。
7. **Logback 两个 jar 内不含许可文本**（上游如此），其 EPL-1.0 / LGPL-2.1 全文需查
   `THIRD-PARTY-LICENSES.md` 里给出的官方链接；`gson` 与 `snakeyaml` 的 jar 内同样没有许可文件，
   但二者均为 Apache-2.0，文本已随包提供。

---

## 六、在 GitHub Releases 里发布这个 zip

1. 打开仓库 → 右侧 **Releases** → **Draft a new release**，Tag 填 `v1.0.4`（新建 tag），标题填 `Pixiko v1.0.4`。
2. 把 `pixiko-v1.0.4.zip` 与 `pixiko-v1.0.4-runnable.zip`（以及各自的 `.sha256`）拖进附件区，
   正文粘贴本文件内容后点 **Publish release**。

> 建仓库时 License 请选 **None**（本项目保留所有权利，不使用开源许可证）。
