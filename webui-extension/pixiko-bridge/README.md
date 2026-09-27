# Pixiko Prompt Bridge

这是随 Java QQ 机器人提供的本地 WebUI 扩展，用于同步文生图页的正向与反向提示词、采样方法、已选预设样式（保留顺序）、宽度和高度，并把当前提示词保存到 WebUI 的预设样式库。标准 `/sdapi/v1/options` 和 Gradio `/config` 无法读取浏览器中正在编辑的当前值，因此需要此桥接。

## 安装

在 `pixiko` 执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\install-webui-bridge.ps1
```

默认目标为本机正在使用的 WebUI 根目录：`F:\sd\sd-webui`。其他安装可传入 `-WebUiRoot '实际 WebUI 根目录'`。

安装只复制扩展，不自动重启。先保存现有提示词，并记录当前采样方法、样式和尺寸，等待生成完成，再在 WebUI 设置点击 **Reload UI / 重载界面**，刷新浏览器页面；也可用启动器重启。首次没有存档时会采用页面已经显示的内容。由旧版升级时，已保存提示词继续恢复，新增参数以新页面首次显示的实际值初始化，不用默认值覆盖页面；WebUI 自身重载可能重置未保存控件，必要时按先前记录恢复。安装完成后保持至少一个文生图页面打开。

修改会在下一次同步周期（通常一秒内）显示于页面；键入结束后约 0.2 至 1 秒上传。页面关闭、断联或尚未显示最新修改时，机器人只能使用扩展持久化的状态；`live` 会返回 `false`。关闭页面后最长 6 秒内心跳超时。多页以最近一次已提交编辑为准，建议只开一页编辑。六个字段的并发更改分别合并；同一字段同时编辑时，页面中尚未提交的编辑优先，以免抹掉正在输入的内容。样式选择是一个有顺序的字段。

数据保存在已安装扩展的 `data/prompts.json`，采用临时文件加原子替换提交，重新安装脚本会保留它。重启后将持久化提示词和已初始化的参数恢复到 WebUI 页面。旧版文件自动迁移为 `settings_initialized: false`，在首次参数同步成功后持久化完整状态。文件损坏时 API 返回 503，并保留原文件，不会静默清空。

## 本地 API

所有请求仅接受本机回环地址和 `127.0.0.1`、`localhost` 或 `[::1]` 等回环 Host。浏览器必须同源；写操作要求 `X-Pixiko-Bridge: 1`，防止外部网页使用普通表单写入。不要把此扩展通过反向代理暴露到外部；它不提供独立账户系统。WebUI 自带的身份验证中间件如有启用仍可能要求登录。

- `GET /pixiko-bridge/v1/prompts`：`{positive, negative, sampler_name, styles, width, height, revision, initialized, settings_initialized, source, live}`。`source` 为 `webui-live` 或 `webui-state`。`initialized` 表示提示词已初始化；`settings_initialized` 表示参数已初始化，未初始化时新增参数字段仅是占位值。
- `PUT /pixiko-bridge/v1/prompts`：提交任意一个或多个可编辑字段，以及 `expected_revision`。例如 `{width: 768, height: 1024, expected_revision: 12}`；未提交字段完整保留。参数尚未初始化时第一次参数写入必须同时提供 `sampler_name, styles, width, height`。成功返回完整新状态。revision 不匹配返回 HTTP 409 与当前状态，调用方应重新读取并重算编辑操作。旧版只提交正负提示词的客户端不会清空新增参数。
- `POST /pixiko-bridge/v1/heartbeat`：浏览器在实际控件模型、显示和样式目录都更新后提交 `{revision, positive, negative, sampler_name, styles, width, height, style_catalog_revision}`。全部值与当前版本一致才标记为实时；旧版心跳不能认证新增状态。普通 Java 客户端不要伪造心跳。
- `POST /pixiko-bridge/v1/styles/delete`：提交 `{name}`，在样式自己所在的 CSV 内原子删除该行：只删目标行、保留其他样式，原文件另存为 `原文件名.pixiko.bak`；样式不存在返回 HTTP 400，分隔标题拒绝删除。
- `POST /pixiko-bridge/v1/styles/rename`：提交 `{name, new_name, overwrite: false}`，在样式自己所在的 CSV 内原子改名：只改目标行、保留其他样式，原文件另存为 `原文件名.pixiko.bak`；目标名已存在时返回 HTTP 409，样式不存在返回 400。
- `POST /pixiko-bridge/v1/styles`：提交 `{name, positive, negative, overwrite: false}`，将两栏提示词保存为 WebUI 样式；成功返回 `{name, overwritten}`。同名且没有显式允许覆盖时返回 HTTP 409。目标 CSV 原子替换后才更新内存样式库，原文件另存为 `原文件名.pixiko.bak`；不更改其他样式文件。
- `GET /pixiko-bridge/v1/prompts` 还提供 `style_catalog_revision`，浏览器检测到名称目录变化时触发 WebUI 原生样式刷新按钮，使新名称可直接选择。

宽度和高度必须是 64 至 2048 范围内的整数且能被 8 整除。采样方法根据 WebUI 当前可见采样器名称验证；预设样式根据当前样式库验证。`styles` 必须是有序、无重复的名称数组，`[]` 表示清空选择。保存样式仅写入给定正反向原文，保留当前输入和选中样式。样式 CSV 由 WebUI 的 `shared.prompt_styles` 决定，不会把用户提供的样式名当成文件路径。

聊天指令统一使用单数：`/sd style`、`/sd style list`、`/sd style set <名称>`、`/sd style clear`、`/sd style save <名称>`、`/sd style overwrite <名称>`。保存需要新版扩展已重载；更新源码而没有重载服务时，客户端会提示需要更新。

只同步 `txt2img` 的上述六个字段；生成仍调用标准 `/sdapi/v1/txt2img`。不读取或覆盖 img2img、模型选择或其他页面内容。

扩展使用 `script_callbacks.on_app_started`、`onUiLoaded`、`onAfterUiUpdate`。目标元素为 `#txt2img_prompt textarea`、`#txt2img_neg_prompt textarea`、`#txt2img_sampling`、`#txt2img_styles`、`#txt2img_width` 和 `#txt2img_height`，优先限定在 `#tab_txt2img` 的首个主控件，避开其他扩展重复使用的尺寸 ID。Gradio 下拉框通过真实选项的 mousedown 选择，确保生成时读取到 Svelte 模型中的值，而不是只改搜索框外观；尺寸同时核对数字框与滑块值。兼容原生 select，以及采样器 radio 控件。针对当前 A1111 / Gradio 3.41.2 验证，无额外 pip/npm 依赖。

## 测试

```powershell
python -m unittest discover -s .\tests -p test_bridge.py -v
node --test .\tests\test_browser.cjs
```

Python 30 项测试覆盖迁移、原子持久化、局部更新、名称校验、尺寸验证、版本冲突、完整状态心跳、样式保存与覆盖、磁盘故障和实际 A1111 CSV 重载。浏览器 21 项测试使用区分搜索文本与真正选中值的 DOM 模型，覆盖样式顺序、清空、参数并发合并、输入法、异步刷新期间的用户编辑，以及错误控件禁止实时确认。隔离的 Gradio 3.41.2 页面通过后端回读验证新样式自动出现、选择后的组件实际值，以及服务重启后样式持久化。
