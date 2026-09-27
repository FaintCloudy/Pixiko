Pixiko v1.0.0 —— 第一次运行，三步走
====================================

【你下载的是哪个包？】

  pixiko-v1.0.0-runnable.zip   ← 开箱即用。里面已经带好了编译好的 jar 和全部依赖，
                                 只要有 JDK 17+，双击 start.bat 就能启动。
                                 （就选这个，除非你要改代码。）

  pixiko-v1.0.0.zip            ← 源码包。给要编译、要改代码的人用，
                                 需要自己补 lib\spring\ 里的 Spring Boot 依赖，
                                 再用 run.bat 构建并启动。


【开箱即用包：三步】

  1. 装 JDK 17 或更新版本（只有一个要求：java.exe 在 PATH 里）。
     检查方法：打开命令行，敲  java -version  能看到版本号就行。

  2. 双击 start.bat。
     第一次运行会自动生成 config.json 并进入配置向导，问你要填的东西：
       - owner_user_id ：你自己的 QQ 号（这一个是必填，其它可以留空）
       - DeepSeek API Key（生图通道 / 聊天通道，各一条）
       - SD WebUI 地址（默认 http://127.0.0.1:7860，记得给 WebUI 加 --api 启动参数）
       - NapCat 正向 WebSocket 地址与 Token（默认 ws://127.0.0.1:3001，不接 QQ 也能用网页控制台）
     配置向导走完，机器人就起来了。

  3. 打开网页控制台： http://127.0.0.1:8787
     默认访问令牌在 config.json 的 webui.access_token 里（首次启动会生成）。
     想让它连 QQ，先启动 NapCat 并开好正向 WebSocket，再双击 start.bat。


【不想用向导？】

  把 config.example.json 复制成 config.json，自己填好，再双击 start.bat 即可。


【出图相关的额外要求】

  生图必须有一个带 --api 启动的 Stable Diffusion WebUI（A1111 系）。
  没有 WebUI 时，机器人照样能启动：提示词管理、LoRA 管理、样式、队列都能用，
  只是出不了图。没有 DeepSeek Key 时同理，只有改写与日常聊天不可用。


【常见问题】

  Q: 双击 start.bat 一闪而过？
  A: 右键用「以管理员身份运行」试一次；或先开一个命令行窗口，在里面敲 start.bat，
     这样报错信息会留在屏幕上。最常见的原因是没装 JDK，或 java.exe 不在 PATH 里。

  Q: 提示 build\pixiko.jar not found？
  A: 你下的是源码包（pixiko-v1.0.0.zip）。源码包请用 run.bat（它会先编译再启动），
     或者改用开箱即用包。

  Q: 提示 lib\spring not found？
  A: 同上，源码包需要自行准备 lib\spring\ 下的 Spring Boot 运行时 jar，
     做法写在 README.md 的「环境要求」一节。开箱即用包里已经带好了，不该出现这个提示。

  Q: 端口 8787 被占用？
  A: config.json 里 webui.port 改一个数字，重新启动。


【版权与声明（请务必读一下）】

  · 代码版权归 loriko（deloriko@outlook.com）所有，保留所有权利（All Rights Reserved）。
    本仓库没有使用任何开源许可证，也没有 LICENSE 文件 —— 这是版权所有者的明确选择。
    代码仅供个人自用与研究；未经作者书面许可，不得再分发、不得商用、
    不得用于任何在线服务（包括把它跑成对公众开放的服务、把源码或二进制重新打包发布）。

  · 机器人默认人格指向《Rewrite》的「神户小鸟」，该角色与作品版权属于
    Key / VisualArts。本项目与官方没有任何关系，非官方作品，未获官方授权或认可。

  · 原作对白语料（data/kotori-corpus.txt）不随包分发，需要自备。
    该文件不存在时，语料复现功能会自动降级，其余功能不受影响。

  · 随二进制包分发的第三方组件（Spring Boot / Spring Framework / Tomcat / Gson 等）
    的许可证与全文，见 THIRD-PARTY-LICENSES.md 与 LICENSES\ 目录。

  完整发行说明见 RELEASE.md，完整文档见 README.md。
