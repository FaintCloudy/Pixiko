# Pixiko Android 外壳 · 设计说明

> 这份文件是**代码旁的设计笔记**，不是给用户看的 README（`android/README.md` 由文档代理负责，本文件刻意不叫那个名字）。
> 内容：哪些是「网页本来就有」、哪些是「原生新增」、关键实现点与踩过的 Android 坑。

## 0. 一句话

这是一个**纯 Java 的 WebView 外壳**：把网页控制台（Spring Boot + `webui/` 静态页）整站装进 WebView，
再补上浏览器给不了的东西（服务器管理、局域网扫描、图片保存到相册、下拉刷新、原生错误页、屏幕常亮）。

## 1. 网页负责什么（= 全部业务功能，一条都不重写）

| 栏目 | 路由 | 在 app 里怎么用 |
| --- | --- | --- |
| 手机端界面（**默认入口**） | `/m`（`/m/` 同） | 启动就加载它：App 风格的手机页面，复用同一套 `/api/*` |
| 出图（右栏常驻对话） | `/`（`/chat` 同构） | 在 `/m` 里点「更多 → 完整控制台（网页版）」切过去；在 `/` 里用菜单「切换到手机界面（/m）」切回。就是「全部功能」的入口 |
| 生成 / 提示词 / 风格 / LoRA | `/gen` `/prompt` `/styles` `/loras` | 网页顶部导航点进去，WebView 内正常跳转 |
| 功能 / 聊天设置 / 系统 / 首次配置 | `/functions` `/chatcfg` `/system` `/setup` | 同上 |
| 日志 / 回执 / 帮助 | `/logs` `/quest` `/help` | 同上 |
| 健康检查 | `/healthz`（免令牌） | 设置页「测试连接」第一步打的就是它 |
| 令牌校验 | `/api/**` 带 `Authorization: Bearer …` | 由网页自己发；原生只在「测试连接」时打一次 `/api/status` |

依据：`webui/app.js` 顶部 `TOKEN_KEY = 'kotori-webui-token'`、`api()` 用 `Authorization` 头、
`src/main/java/cn/szu/bot/web/WebPageController.java` 的路由表与 `WebAuthFilter` 的三种令牌给法。

**响应式是这次的底气**：`webui/app.css` 在 ≤1000px 把右栏变成单列堆叠，390×844 已经被真机浏览器验收过
（无横向滚动）。所以「把网页装进 WebView」＝拿到全部功能。**v1.5.0 起默认入口改成另写的手机端界面
`/m`**（见上表），完整控制台 `/` 照旧保留、两边互相独立。

## 2. 原生新增什么（网页做不到 / 手机上体验必要的）

| 能力 | 在哪 | 为什么必须原生 |
| --- | --- | --- |
| 服务器配置（多台、名称/地址/令牌） | `SettingsActivity` + `ServerRepository` | 浏览器里地址就是地址栏；app 需要一个可切换的列表 |
| 测试连接（区分「连不上」与「令牌不对」） | `Probe` | 先用 `HttpURLConnection` 打 `/healthz`（免令牌）确认服务在，再带 Bearer 打 `/api/status` 验令牌 |
| 扫描局域网 | `LanScanner` | 不知道机器人 IP 时唯一的出路：并发 32、单个 400ms、总 10s、可取消 |
| 长按图片 → 保存到相册 / 分享 | `NativeHook` + `PixikoBridge` + `ImageStore` | 浏览器只能「另存为」，Android 上要落到 `Pictures/Pixiko` 或走系统分享 |
| 下拉刷新 + 顶部细进度条 | `SwipeRefreshLayout` + `onProgressChanged` | 手机上刷新的肌肉记忆 |
| 原生错误页 | `activity_main.xml` 的 `error_page` | WebView 自带错误页只有一行英文，给不出「你该去检查什么」 |
| 屏幕常亮 | `MainActivity.applyKeepScreenOnState()` | 出图要等，屏幕灭掉很烦 |
| 令牌自动注入 | `NativeHook.tokenScript()` | 免得在网页锁屏里再敲一次令牌 |
| App 级入口（服务器设置 / 清空网页缓存 / 清除登录状态 / 在浏览器打开） | `PixikoBridge` 的 4 个桥方法 + `/m` 的「更多 → App」分组 | **`/m` 下 ActionBar 是隐藏的**（`applyActionBarVisibility()`），原生菜单点不到，入口改由网页提供；这 4 行只在方法存在时才建，纯浏览器打开 `/m` 时不建；「完整控制台（网页版）」一行常显 |
| `<input type=file>` / 下载 / 新窗口 / 控制台日志 | `PixikoChromeClient` / `PixikoDownloadListener` | WebView 默认不处理，不接管就会「点了没反应」 |

## 3. 关键实现点（按文件/方法）

### 3.1 地址规范化 —— `UrlHelper.normalizeBase`

`192.168.1.5`、`192.168.1.5:8787`、`http://192.168.1.5:8787/`、`https://host/sub/` 全部收口成
`scheme://host[:port][/path]`（无尾斜杠）。规则：默认补 `http://`（局域网机器人是 http），
http 且没写端口时补 **8787**（与 `config.json → webui.port` 默认值一致）。

坑：IPv6 字面量从 `URI.getHost()` 拿回来是不带方括号的，直接拼会得到非法地址 —— 拼回去时补 `[]`。

### 3.2 令牌注入且只 reload 一次 —— `MainActivity.maybeInjectToken`

* 在 `onPageStarted` 与 `onPageFinished` 各尝试一次（有些 ROM 在 started 时 localStorage 还没就绪）；
* 脚本值一样就返回 `'same'`，不一样才写并返回 `'written'`，只有 `written` 才 `reload()`；
* `tokenInjected` 是「本轮已处理」标志，**不是**「值已正确」——网页自己也可能改 localStorage
  （用户在网页锁屏里手敲令牌），那时值不一致但我们不再 reload，避免和网页互相刷成死循环；
* 菜单里的「清除登录状态」会置 `suppressTokenInjection`，本轮不再注入，让网页老老实实回到锁屏
  （`/m` 下这个入口在网页的「更多 → App」里，完整控制台下才在菜单里）。

### 3.3 图片长按/右键 —— `NativeHook.hookScript()` + `PixikoBridge`

* 注入脚本带 `window.__pixikoNativeHooked` 幂等标志，页面内导航/重复注入不会叠两层监听；
* 桌面/鼠标设备走 `contextmenu`（`preventDefault` 有效）；触屏走 `touchstart` + **550ms**（移动超过 10px
  或抬手即取消）。触屏这条路上 `preventDefault` 已经太晚，所以真正的「菜单」由原生弹：
  `PixikoBridge.showImageMenu()` → `AlertDialog`（保存到相册 / 分享…）；网页自带的图片查看器仍然可用；
* 只对 `<img>` 且 `currentSrc/src` 非 `data:`/`blob:`、显示尺寸 ≥40×40 的图生效，避免把头像、图标也hook上；
* **桥只挂在配置的那台服务器上**（`NativeHook.isTrustedHost` 比对 host:port）：
  用户在网页里点开外链（图床、Civitai）后 `removeJavascriptInterface`，第三方站点拿不到 `PixikoNative`。

### 3.4 图片落盘 —— `ImageStore`

* 下载：`HttpURLConnection`，20s 超时、**40MB 上限**（先看 `Content-Length`，再按实际读到的字节兜底）、
  只接受 `Content-Type: image/*`；整体先读进内存再落盘，避免「下到一半发现不是图片」留垃圾文件；
* Android 10+：`MediaStore.Images` 插 `Pictures/Pixiko`，写入期间 `IS_PENDING=1`，写完清 0
  （否则相册可能看到写了一半的图）；`insert` 成功但写流失败要 `delete` 掉占位记录；
* Android 9 及以下：`Environment.getExternalStoragePublicDirectory(DIRECTORY_PICTURES)/Pixiko` +
  `MediaScannerConnection.scanFile`（不扫描的话相册看不到）；
* 相对地址 `/api/image?token=…&path=…` 用 `UrlHelper.absolutize` 拼成绝对地址；
  **令牌就留在查询串里**——服务端 `/api/**` 的三种令牌给法里只有 `?token=` 对 `<img>` 有效，照抄浏览器行为；
* 文件名：优先网页给的 `alt`，否则从 `path` 参数抠，再否则时间戳；扩展名按真实 MIME 决定（不能信 URL）；
  净化掉路径分隔符与 `\/:*?"<>|`，去掉前导点（否则在相册里是隐藏文件）。

### 3.5 分享 —— `ImageStore.share` + FileProvider

先落到 `cacheDir/share/`，再用 `FileProvider` 换成 `content://` URI。
**不能直接塞 `file://`**：Android 7+ 会 `FileUriExposedException`。
`res/xml/file_paths.xml` 只白名单 `cache-path share/`，不暴露 `files/` 与外部存储。

### 3.6 局域网扫描 —— `LanScanner`

* 网段来源：`NetworkInterface` 枚举所有 `isUp && !isLoopback && isSiteLocalAddress` 的 IPv4，
  取 `/24` 前缀（不是只信 WifiManager：有线/热点也算，而且 Android 10+ 拿 SSID 还要定位权限）；
* 并发 32、单个 **400ms** 超时打 `http://<ip>:8787/healthz`，总预算 **10s**；
* 不在线的 IP 往往要等到超时，一轮扫不完就**只补扫没响应的**那一批；预算到了立刻收工；
* 每个探测的 `Future.get` 也带超时，避免个别卡死的连接把总预算拖穿；
* `cancel()` 会 `shutdownNow()` + 中断工作线程，`SettingsActivity.onDestroy` 必须调，
  否则退出页面后它还在往 254 个地址发包；
* 结果做成「填入 xxx」按钮：比让用户手抄 IP 靠谱。

### 3.7 WebView 配置里容易踩的几个

| 配置 | 不配会发生什么 |
| --- | --- |
| `usesCleartextTraffic="true"` | Android 9+ 直接 `ERR_CLEARTEXT_NOT_PERMITTED`，局域网 http 打不开 |
| `setDomStorageEnabled(true)` | 网页读不到 localStorage，永远停在锁屏 |
| `setMixedContentMode(MIXED_CONTENT_COMPATIBILITY_MODE)` | http 页面里的 https 图床资源可能被拦 |
| `hardwareAccelerated="true"`（默认就是 true） | 图片多的页面滚动发卡 |
| `setAllowFileAccess(false)` | 安全项：网页不需要读本地文件，关掉减少攻击面 |
| `onShowFileChooser` | 网页里「选参考图 / 选 LoRA」的按钮点了没反应 |
| `setDownloadListener` | 下载链接点了没反应（WebView 自身不下载） |
| `onReceivedError` 只处理 `isForMainFrame` | 一个图标 404 就糊住整屏，属于过度反应 |
| `onConsoleMessage` 转 logcat | 网页报错在 app 里完全看不见，排查只能靠猜（**不打令牌**） |

### 3.7b 两个「写了中文注释就报错」的坑（真踩过）

1. **`gradlew.bat` 必须是纯 ASCII**。`cmd.exe` 用 OEM/ANSI 代码页解码 `.bat`，
   UTF-8 的中文注释会被解成乱码，cmd 再拿乱码当命令执行 —— 现象是启动时冒出
   `'apper；' is not recognized as an internal or external command`，脚本行为也变得不确定。
   修法：`gradlew.bat` 里**只写英文注释**（中文说明放 `gradlew`、Java 与文档里）。
   同一个原因也让 `android/build-apk.ps1` 在 **Windows PowerShell 5.1** 下解析失败
   （该脚本 UTF-8 无 BOM + 含中文；`powershell -File` 按 GBK 解码 → 报
   `Unexpected token` / `The string is missing the terminator`）。那个文件不归本代理改，
   两个修法记在这里：存成 **UTF-8 带 BOM**，或者只用 `pwsh`（PowerShell 7）跑。

2. **自适应图标前景要收进安全区**。`icon-192.png` 是「满幅带底色」的方块图，
   直接当 `foreground` 会被厂商遮罩裁掉四边。用 `drawable/ic_launcher_foreground.xml`
   包一层 `<inset android:inset="18%">` 就落在安全区内了，且不需要重新画图。


### 3.8 状态保持

* manifest 里 `configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden|uiMode|density|fontScale|locale|layoutDirection"`
  → 旋转/分屏/字体缩放**不重建 Activity**，WebView 原地保留，滚动位置不丢；
* 另外 `onSaveInstanceState` 存当前 URL，进程被回收后恢复时回到刚才那一页（而不是回首页）。

### 3.9 返回键三级 —— `MainActivity.handleBack`

`webView.canGoBack()` → `goBack()`；否则「不在首页」→ 回首页；已经在首页 → 2 秒内双击退出
（第一次 Toast「再按一次退出」）。

## 4. 权限与安全

| 权限 | 用途 | 说明 |
| --- | --- | --- |
| `INTERNET` | 加载控制台、下载图片 | 必需 |
| `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | 扫描局域网、判断网络 | 不需要定位权限 |
| `WRITE_EXTERNAL_STORAGE`（`maxSdkVersion="28"`） | Android 9 及以下存图 | 10+ 走 MediaStore，不再申请 |

* 令牌**明文**存在应用私有 `SharedPreferences`（`pixiko_android` / `servers_json`）：
  卸载即清除，不写外部存储、不做备份（`allowBackup="false"`）。root 设备上该文件可读，属已知取舍。
* 日志里**从不打印令牌**：需要时用 `Log.mask()` 只打首尾字符与长度。
* 原生桥只在配置的服务器域名下挂载（见 3.3）。
* `FileProvider` 只暴露 `cacheDir/share/`。
* 网页令牌默认注进去是为了省事；想换账号/令牌，用「清除登录状态」即可回到网页锁屏
  （`/m` 走网页的「更多 → App」，完整控制台下走菜单）。

## 5. 工程结构

```
android/
├─ build.gradle / settings.gradle / gradle.properties / gradlew(.bat) / gradle/wrapper/
├─ build-apk.ps1                  ← 另一个代理写的构建脚本（本代理未改动）
└─ app/
   ├─ build.gradle                ← compileSdk 34 / minSdk 26 / applicationId cn.szu.bot.app / 1.5.0(150)
   ├─ proguard-rules.pro          ← 保留 @JavascriptInterface 方法名（万一以后开混淆）
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ assets/DESIGN.md         ← 本文件的副本说明（见该文件）
      ├─ java/cn/szu/bot/app/*.java
      └─ res/{layout,menu,values,values-night,drawable,mipmap-*,xml}/
```

## 6. 没做 / 做不到

* 没有真机与模拟器：所有交互（长按菜单、MediaStore 落盘、扫描结果）只经过**编译期**验证，没有跑过 UI。
  **v1.5.0 的手机端界面 `/m` 与「更多 → App」这套入口同样没有在真机 / 模拟器上跑过**
  （用户决定不跑虚拟机）。
* 没有自动化测试（无 instrumented test 依赖，避免多拉依赖）。
* 合入方式未做：release 变体没配签名（要发到手机用 `assembleDebug` 的 debug 包即可）。
* `webui/app.js` 里的图片查看器在 WebView 里是网页自己在跑，原生只加长按菜单；两者手势可能互相影响，
  真机需要实测一次（若冲突，把 `NativeHook.LONG_PRESS_MS` 调大即可）。
