# 第三方组件与许可 / Third-Party Notices

Pixiko 本身的代码版权归 **loriko** 所有，**保留所有权利**（见 `RELEASE.md` 与 `README.md`）。
本文件只说明 **Pixiko 依赖的第三方组件**。

适用范围：
- **源码包 `pixiko-v1.0.6.zip`**：不含任何第三方 jar，因此下面「随包分发的二进制组件」一节的**二进制不在源码包里**；
  但**中文词库与词表（第〇节）在源码包里全部都有**，包括 `data/danbooru/` 下的上游原始词表。
- **开箱即用包 `pixiko-v1.0.6-runnable.zip`**：随包分发 `lib/gson-2.13.1.jar` 与 `lib/spring/` 下 23 个 jar，
  即下表全部内容；中文词库（`data/prompt-*`）也有，但**不含** `data/danbooru/` 下的上游原始词表
  （运行时不需要它，需要重建词库时请用源码包）。这些 jar **未做任何修改**，原样来自 Maven Central。

---

## 〇、随包分发的中文词库与词表（两个包都有）

`data/` 下的提示词词库随包分发（其余 `data/` 内容——密钥、原作语料、生成图、队列状态——仍然不进包）：

| 文件 | 体积 | 内容 | 来源与许可 |
|---|---|---|---|
| `data/prompt-tags.txt` | 2.3 MB | 140,779 条标准词条（Danbooru 词表，一行一个，未导入别名列） | 由 [DominikDoom/a1111-sd-webui-tagcomplete](https://github.com/DominikDoom/a1111-sd-webui-tagcomplete) 的 `tags/danbooru.csv` 导出，上游 **MIT**（© 2022 Dominik Reh），全文见 `LICENSES/MIT-tagcomplete.txt` |
| `data/prompt-usage.json` | 464 KB | 中文分类词库（11 类：人物/服饰/表情动作/画面/环境/场景/物品/镜头/汉服/魔法系/反向提示词） | 由 [Physton/sd-webui-prompt-all-in-one](https://github.com/Physton/sd-webui-prompt-all-in-one) 的 `group_tags/zh_CN.yaml` 整理而来，上游 **MIT**（© 2023 Physton），全文见 `LICENSES/MIT-prompt-all-in-one.txt` |
| `data/prompt-zh-tags.json` | 1.6 MB | 34,211 条中文↔标准词条对照 + 38,941 个中文写法（13 类） | 本项目生成物：`prompt-usage.json` + 上表的 `danbooru.zh_CN_SFW.csv` 中文翻译 + 本仓库 `data/prompt-zh-extra.txt` 手工同义词 |
| `data/prompt-zh-extra.txt` | 188 KB | 手工维护的中文同义词表（生成 `prompt-zh-tags.json` 的输入） | 本项目原创 |
| `data/prompt-zh-usage.md` | 4.0 MB | 上面 34,211 条词条的用法表（`node tools/annotate-zh-tags.mjs` 生成的人读版） | 本项目生成物 |
| `data/prompt-zh-use-notes.txt` | 2.2 KB | 词条「什么需求下才该用」的人工说明 | 本项目原创 |
| `data/prompt-tags.source.json` | 362 B | 上面 `prompt-tags.txt` 的来源与校验值（仓库地址、blob sha、sha256、条数） | 本项目生成物 |

说明：
- 词条名本身是 Danbooru 的标签标识符（事实性数据）；中文翻译与分类里**含机器翻译**，可能不准确，
  只用于把中文说法对应到标准词条。
- 上游两个项目都是 **MIT**：再分发时请一并保留 `LICENSES/MIT-tagcomplete.txt`、
  `LICENSES/MIT-prompt-all-in-one.txt` 与本说明。Pixiko 自身的代码仍是**保留所有权利**（见 `RELEASE.md`）。
- 生成脚本：`tools/build-zh-tags.mjs`（生成中文词库）、`tools/annotate-zh-tags.mjs`（生成用法表）、
  `tools/diff-zh-tags.mjs`（对比前后差异）。

### 上游原始 danbooru 词表（`data/danbooru/`，**只在源码包与仓库里**）
机器人运行时**不读**这个目录；放在仓库里是为了「不装 SD WebUI 扩展也能重建词库」。
同样来自上游 **MIT** 项目，一个字节未改，`sha256` 与上面几个 JSON 里记录的校验值完全一致：

| 文件 | 行数 | 体积 | 作用 |
|---|---|---|---|
| `data/danbooru/danbooru.main-140782.csv` | 140,782 | 3.4 MB | 上游 main 分支的 `tags/danbooru.csv`；`data/prompt-tags.txt` 就是取它的第 1 列导出的 |
| `data/danbooru/danbooru.csv` | 121,034 | 3.0 MB | 本机扩展里的 2024-12-19 快照；`tools/build-zh-tags.mjs` 从它取分类与热度 |
| `data/danbooru/danbooru.zh_CN_SFW.csv` | 99,293 | 3.0 MB | 上游中文翻译表（SFW）；中文词库的中文写法主要来自它 |

重建方式见 `data/danbooru/README.md`（`node tools/build-zh-tags.mjs --check` 可校验
随包的词库与随包的词表是否一致——本项目在仓库里跑过，输出「与现有 data/prompt-zh-tags.json 一致」）。

---

### 《Rewrite》原作对白语料（`data/kotori-corpus.txt`，两个包都有）

| 文件 | 体积 | 内容 |
|---|---|---|
| `data/kotori-corpus.txt` | 324 KB | 《Rewrite》神户小鸟线对白：45 个场景、2,707 句小鸟台词（另有瑚太朗等角色 2,820 句），共 122,675 字符；格式 `===== seenXXXXX =====` + `【说话人】「台词」` |

- **这不是开源内容，也不是本项目创作。** 版权属于 **Key / VisualArts**；本项目与官方没有任何关系，
  未获授权或认可，此文件**没有附带任何许可证**。
- 它由**仓库所有者决定**随仓库与发行包一起提供，只用于机器人「原作语料复现」
  （`chat.corpus_replay`：命中原作问答时参考原句说话）。使用者请自行判断合规风险。
- **权利人若要求删除，会立即移除**：删掉该文件即可（`.gitignore` 里的白名单行一并删掉），
  机器人随后降级为只走口癖锚点，其余功能不受影响。

### 地图图片（`maps/`，两个包都有）

| 文件 | 尺寸 | 用途 |
|---|---|---|
| `maps/yh/ecd668e0bc8b663ca33d47e93f335cfb.jpg` | 2480×3367 | 深大**粤海**校区地图，`.yh` 发送 |
| `maps/liv/ba9ed4301fe044a45e4b7be7c2834823_720.jpg` | 1280×1810 | 深大**丽湖**校区地图，`.liv` 发送 |

- 这两张是**作者自备的校园地图素材**（非本项目创作），版权归原制图方所有；本项目只把它们放在
  `maps/` 里给 `.yh` / `.liv` 当图片来源，**未声明任何授权**。若你是权利人或认为不宜分发，
  删掉这两个文件即可（机器人随后会提示「还没有地图图片」），也可以换成自己的图。
- `maps/*/放*地图到这里.txt` 是这两个目录的说明文件（本项目原创），会一起分发。

---

## 一、随包分发的二进制组件（仅开箱即用包）

| 组件 | 版本 | 坐标 | 许可 |
|---|---|---|---|
| Spring Boot | 3.5.6 | `org.springframework.boot:spring-boot`、`spring-boot-autoconfigure` | Apache-2.0 |
| Spring Framework | 6.2.11 | `org.springframework:spring-aop` `spring-beans` `spring-context` `spring-core` `spring-expression` `spring-jcl` `spring-web` `spring-webmvc` | Apache-2.0 |
| Apache Tomcat (embed) | 10.1.46 | `org.apache.tomcat.embed:tomcat-embed-core` `tomcat-embed-el` `tomcat-embed-websocket` | Apache-2.0 |
| Micrometer | 1.14.11 | `io.micrometer:micrometer-commons` `micrometer-observation` | Apache-2.0 |
| Apache Log4j (API / bridge) | 2.24.3 | `org.apache.logging.log4j:log4j-api` `log4j-to-slf4j` | Apache-2.0 |
| SnakeYAML | 2.4 | `org.yaml:snakeyaml` | Apache-2.0 |
| Gson | 2.13.1 | `com.google.code.gson:gson` | Apache-2.0 |
| SLF4J API | 2.0.17 | `org.slf4j:slf4j-api` | MIT |
| JUL to SLF4J | 2.0.17 | `org.slf4j:jul-to-slf4j` | MIT |
| Logback | 1.5.18 | `ch.qos.logback:logback-classic` `logback-core` | **EPL-1.0 或 LGPL-2.1（双许可，二选一）** |
| Jakarta Annotations API | 2.1.1 | `jakarta.annotation:jakarta.annotation-api` | **EPL-2.0**（或 GPL-2.0 with Classpath Exception） |

合计 23 个 jar（Apache-2.0 ×19、MIT ×2、EPL/LGPL ×2、EPL-2.0 ×1；Logback 两个 jar 同属一条）。

### 许可文本在哪

- **Apache-2.0**：`LICENSES/Apache-2.0.txt`（与 `spring-core` 等 jar 内 `META-INF/license.txt` 一致）。
- **SLF4J 的 MIT 文本**：`LICENSES/SLF4J-MIT.txt`（与 `slf4j-api` jar 内 `META-INF/LICENSE.txt` 一致）。
- **EPL-2.0 文本**：`LICENSES/EPL-2.0.md`（取自 `jakarta.annotation-api` jar 内 `META-INF/LICENSE.md`）。
- 打包的 jar **均未改动**，各自 `META-INF/` 下的 `LICENSE` / `NOTICE` 文件原样保留
  （23 个 jar 中 20 个自带 LICENSE、18 个自带 NOTICE）。
- **Logback 的两个 jar 内部没有携带许可文本**（上游如此），其许可为 EPL-1.0 / LGPL-2.1 双许可，全文见：
  <https://www.eclipse.org/legal/epl-v10.html> 与 <https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html>。
  同样地，`snakeyaml` 与 `gson` 的 jar 内也没有许可文件，二者均为 Apache-2.0，
  文本即本包 `LICENSES/Apache-2.0.txt`。

---

## 二、**不**随包分发的第三方（需自行获取并遵守其条款）

| 组件 | 说明 |
|---|---|
| **NapCat**（或其它 OneBot v11 实现） | 可选。QQ 接入端，本项目只通过正向 WebSocket 与它通信，不分发其任何代码。请遵守其许可与服务条款。 |
| **Stable Diffusion WebUI (A1111)** | 出图后端，需自行安装并以 `--api` 启动。不分发其代码、模型或权重。 |
| **Stable Diffusion 模型 / LoRA / 嵌入权重** | 一律不分发。各模型的许可差异很大，请逐个阅读其页面上的许可再使用。 |
| **Civitai 内容** | 不分发。搜索与下载走 Civitai 官方 API，请遵守其服务条款与速率限制。 |
| **DeepSeek API** | 不分发。需要你自己的 API Key，使用受 DeepSeek 服务条款约束。 |
| **JDK（Java 运行时/开发工具包）** | 不分发。请自行安装 JDK 17+，遵守其提供方许可。 |
| **Node.js** | 可选，仅自检与中文词库工具需要。不分发。 |

> 《Rewrite》原作对白语料（`data/kotori-corpus.txt`）**过去在这一节里（明确不分发），
> 从 v1.0.6 起改为随包分发**，版权与删除方式见上面第〇节末尾那一小节。

---

## 三、如果你要再分发

本包已按 Apache-2.0 与 MIT 的要求保留了各 jar 内的许可与 NOTICE 文件，并附带上述许可文本；
中文词库/词表的两个上游（均为 MIT）文本见 `LICENSES/MIT-tagcomplete.txt` 与 `LICENSES/MIT-prompt-all-in-one.txt`。
你若再次分发本包，请**一并保留** `THIRD-PARTY-LICENSES.md` 与 `LICENSES/` 目录；
同时注意 Pixiko 自身代码是**保留所有权利**的，再分发前需先取得作者许可。
