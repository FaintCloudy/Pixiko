# 第三方组件与许可 / Third-Party Notices

Pixiko 本身的代码版权归 **loriko** 所有，**保留所有权利**（见 `RELEASE.md` 与 `README.md`）。
本文件只说明 **Pixiko 依赖的第三方组件**。

适用范围：
- **源码包 `pixiko-v1.0.0.zip`**：不含任何第三方 jar，因此下面「随包分发」一节的组件**不在源码包里**。
- **开箱即用包 `pixiko-v1.0.0-runnable.zip`**：随包分发 `lib/gson-2.13.1.jar` 与 `lib/spring/` 下 23 个 jar，
  即下表全部内容。这些 jar **未做任何修改**，原样来自 Maven Central。

---

## 一、随包分发的组件（仅开箱即用包）

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
| **《Rewrite》原作对白语料**（`data/kotori-corpus.txt`） | **明确不分发**。有版权，需自备。见 `README.md` 第七节。 |
| **JDK（Java 运行时/开发工具包）** | 不分发。请自行安装 JDK 17+，遵守其提供方许可。 |
| **Node.js** | 可选，仅自检与中文词库工具需要。不分发。 |

---

## 三、如果你要再分发

本包已按 Apache-2.0 的要求保留了各 jar 内的许可与 NOTICE 文件，并附带上述许可文本。
你若再次分发本包，请**一并保留** `THIRD-PARTY-LICENSES.md` 与 `LICENSES/` 目录；
同时注意 Pixiko 自身代码是**保留所有权利**的，再分发前需先取得作者许可。
