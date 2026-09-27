# Pixiko v1.0.0 发行说明

- **版本**：v1.0.0
- **日期**：2026-09-27
- **作者**：loriko（deloriko@outlook.com）
- **当前实现**：Java 版（`src/`）。另有一次**未完成的** Next.js 重构，见 `nextjs-wip/`，**不可运行**。

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

### 开箱即用包 `pixiko-v1.0.0-runnable.zip`

1. 装好 **JDK 17+**。
2. 解压后，在解压目录里 `copy config.example.json config.json`，然后填好配置
   （**最少要改 `owner_user_id` 为你自己的 QQ**；或运行 `run.bat --setup` 走向导）。
3. **双击 `start.bat`** 直接启动（预编译 jar，**无需编译**）。

> `start.bat` 跑的是包内已编译好的 `build/pixiko.jar`；只有需要改代码时才用 `build.ps1` + `run.bat`。

### 源码包 `pixiko-v1.0.0.zip`

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
| `run.bat` | 源码包：先 `build.ps1` 构建再启动；`run.bat --setup` 进配置向导 |
| `build.ps1` | 编译 `src/main/java` 并打包 `build/pixiko.jar`；`-Test` 连测试一起跑 |
| `test.bat` | 等价于 `build.ps1 -Test`：编译并逐个运行全部 `*Test` |
| `stop-bot.ps1` | 停止正在运行的机器人 |
| `install-webui-bridge.ps1` | 安装 SD WebUI 桥接扩展（`-WebUiRoot` 指定 WebUI 根目录） |
| `eval-chain.ps1` / `eval-effect.ps1` / `eval-scale.ps1` / `eval-decompose.ps1` | 提示词链路评测脚本 |
| `node tools\webui-selfcheck.mjs --token <令牌>` | 网页控制台自检（令牌用 `webui.access_token`，或设环境变量 `PIXIKO_WEBUI_TOKEN`） |

其他启动参数：`run.bat --help`（指令总表）、`--check`（只测 SD 连通性）、`--setup`（配置向导）、
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
   `config.json` 与整个 `data/` 都不在包里（只有空的 `data/`、`logs/`、`maps/` 占位目录）。
   **第一次运行必须自己配置**，否则 owner 指令无法使用。
5. **默认没有 owner。** 公开代码里 `Settings.DEFAULT_OWNER` 是空字符串（原版本内置了作者 QQ，
   属于个人信息，已移除）。**务必在 `config.json` 里设置 `owner_user_id`**，否则所有 owner 专属指令都会被拒绝。
6. **`test.bat` 的行为依赖工作目录。** `WebUiTest` 通过「进程工作目录下的 `webui/`」找页面资源
   （见 `WebPageController` 的回退逻辑），所以请在**项目根目录**运行 `test.bat`，
   不要从别的目录调用。在项目根目录运行，44 个测试全部通过。
7. **Logback 两个 jar 内不含许可文本**（上游如此），其 EPL-1.0 / LGPL-2.1 全文需查
   `THIRD-PARTY-LICENSES.md` 里给出的官方链接；`gson` 与 `snakeyaml` 的 jar 内同样没有许可文件，
   但二者均为 Apache-2.0，文本已随包提供。

---

## 六、在 GitHub Releases 里发布这个 zip

1. 打开仓库 → 右侧 **Releases** → **Draft a new release**，Tag 填 `v1.0.0`（新建 tag），标题填 `Pixiko v1.0.0`。
2. 把 `pixiko-v1.0.0.zip` 与 `pixiko-v1.0.0-runnable.zip`（以及各自的 `.sha256`）拖进附件区，
   正文粘贴本文件内容后点 **Publish release**。

> 建仓库时 License 请选 **None**（本项目保留所有权利，不使用开源许可证）。
