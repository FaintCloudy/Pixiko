# Pixiko v1.1.1 发行说明

- **版本**：v1.1.1
- **日期**：2026-10-04
- **作者**：loriko（deloriko@outlook.com）
- **当前实现**：Java 版（`src/`）。另有一次**未完成的** Next.js 重构，见 `nextjs-wip/`，**不可运行**。

---

## 〇、本版新增（v1.1.1）

**回执不再过期——正文落盘永久保留；生成期间回执显示进度条、图集提前预览；列表上的未读数变成数字徽标。**

- **回执正文永久保留**：以前正文只在内存里留 30 分钟，超时后点开只能看到摘要（「内容已过期，只保留摘要」）。
  现在每条回执的正文（文字 + 图片**引用**）都会落到 `data/quests/<任务号>.json`（UTF-8 无 BOM、原子写、
  **只写引用不写图片字节**）；内存里最多保留最近 **200** 条、按任务号淘汰最旧的（**淘汰前先落盘**）；
  索引 `data/quests.json` 的上限从 200 条提到 **2000** 条（被裁掉的最旧条目会连 `data/quests/<号>.json`
  一起删）。因此：
  - `/api/quest` 现在**任何旧回执都能打开看完整正文**（从磁盘读回来，附 `fromDisk:true`；
    `busy=false`、`expired=false`）；
  - `/api/quests` 里每条 `expired` **恒为 false**，顶层 `retainedMinutes` **恒为 0**（＝不过期）；
  - **只有磁盘上确实没有正文文件**时才会说打不开，页面文案是「这条回执的正文读不到了（磁盘上也没有）」。
- **带生成指令的回执在生成期间显示进度条**（回执页）：主条来自任务队列的图片级进度
  （`任务 #4 · 已生成 9/20 张 · 45%`，数据 `GET /api/tasks`），副标题来自 SD 单张图内部的采样进度与
  预计时间（`采样中 8/20（41%）· 预计 14 秒`，数据 `GET /api/progress`）；每 **1.5 秒**刷新一次，
  空闲后标「已完成」并停止轮询。**多图任务会显示「已生成 N/总数 张」**。
- **图集能在生成过程中提前预览**：生成期间前端轮询 `GET /api/images`，把这条回执开始时间之后新产出的
  图片**增量**追加进该回执的图集（标题显示「图集 · 已生成 N 张（生成中…）」，完成后变
  「图集 · 共 N 张」），按图片路径去重、**不重建回执、不打断滚动**。
- **回执列表**：每条未读回执在条目上用**数字徽标**显示未读消息条数（＝文字条数 + 图片条数，
  `span.quest-unread-count`，`title` 为「未读 N 条消息」），不再用「未读」文字徽标；页签徽标仍是
  未读**回执**条数。列表整体高度上限从 `min(56vh,560px)` 提到 `min(76vh,760px)`（窄屏
  `min(64vh,620px)`）；**每条回执不再被压扁**（`min-height:68px`、摘要最多两行，不做高度裁剪）。
- **对话页底部那个与回执页重复的图片回执区（`id="chat-receipts"`）已删除**；对话页里带图片的出站消息
  改为**内联进对话流**（点击仍可放大、可在同一条消息的图片间翻页）。
- **切页面不丢东西**：控制台每切换一个栏目都是一次真实的页面加载，现在用 `sessionStorage` 记住
  回执页当前打开的任务号（回到 `/quest` 自动恢复那条）与 `#quest-body` 的滚动位置、对话页的渲染内容
  （文本 + 图片引用）与按会话 scope 记住的滚动位置（之前贴在底部就继续贴底，图片异步加载后也保持贴底）、
  以及其它栏目的窗口滚动位置。切换回来不再需要手动滚到最新消息，也不再出现「回执消息与图片全没了」。
- **前端**：`webui/index.html` 资源版本升到 **`?v=1.1.1`**。
- **测试**：新增 `QuestPersistenceTest`（53 条断言）、`QuestListTest` 更新到 82 条断言；
  `build.ps1 -Test` **60 套全绿**。

<details>
<summary>上一版（v1.0.18）</summary>

## 〇、本版新增（v1.0.18）

**回执页那张要手动输入任务号的卡片删掉了，左边的列表成了唯一入口；状态与指令搬到右侧「执行结果」卡片头部。**

- **删掉的手动入口**：`/quest` 顶部原来那张「任务回执」卡片整块去掉——手动输入任务号的输入框，以及
  「打开回执」「最新一条」「刷新」三个按钮都不在了。左边「全部回执」列表本来就能点一行打开、未读有标记，
  那张卡片只是重复的入口。
- **状态与指令搬家**：选中那条的状态（如 `#23（已完成）`）与「最新一条是 #N」显示在右侧**「执行结果」
  卡片头部**，指令行（`指令：…`）就在头部下方。
- **「刷新」一次刷两样**：列表卡片头部仍是「全部标为已读」+「刷新」，后者现在同时重读**回执列表**
  与**当前打开的那条回执**。
- **`/quest#N` 仍可直接打开**：列表点一行就是跳到这个地址，所以链接照旧可分享，打开照旧标记已读。
- **前端**：`webui/index.html` 资源版本 `?v=1.0.22`。

</details>

<details>
<summary>上一版（v1.0.17）</summary>

## 〇、本版新增（v1.0.17）

**图片查看器能滚轮缩放、点空白退出、左右翻页；一个任务出的多张图在回执里合成一个图集**。

- **查看器**：点图片周围的空白处、按 `Esc` 或点 `×` 退出；**鼠标滚轮缩放**（0.2×–8×，
  工具条显示当前倍率），原「1:1 原图 / 适应屏幕」按钮照旧可用；一组图片时左右两侧出现
  **上一张 / 下一张**按钮，键盘 `←` / `→` 同样能翻，并有「3 / 12」这样的计数。
- **多图任务 = 一个图集**：`/gen N`、一次 `.get` 领多张、`.rg` 回溯多张时，回执不再一张一张列，
  而是**一个图集卡片**（缩略图网格 + 「图集 · 共 N 张」）；点任意一张就用查看器在整个图集里翻页。
  只有一张图时仍是普通的单张卡片。回执页与各面板底部的回执栏都这样渲染，生成中新图到达时
  就地更新张数（不会整块闪一下）。
- **前端**：`webui/index.html` 资源版本 `?v=1.0.21`。

</details>

<details>
<summary>上一版（v1.0.16）</summary>

## 〇、本版新增（v1.0.16）

**控制台「回执」栏多了一份全部回执的列表，没看过的回执有未读标记**。以前回执只能一条一条按任务号看
（`/quest#N`），右下角弹过的回执云一关就找不回来了；现在列表把它们摊开，未读的看得见、点一下就打开。

- **列表**：`/quest` 页左侧列出所有回执（最新在前），每行是 `#编号` + 指令 + 相对时间 + 第一段文字的摘要；
  正在跑的标「进行中…」，正文已被回收（重启后或超过 30 分钟）的标「已过期（只保留摘要）」。
- **未读标记**：没打开过的回执带未读圆点 + 高亮，「回执」页签上显示未读条数；点一行即打开
  （地址变成 `/quest#N`，可分享）并标记已读；「全部标为已读」一次清空。
- **持久化**：正文仍只在内存留 30 分钟，但**摘要与已读状态**写进 `data/quests.json`（最新 200 条），
  重启后列表还在，未读也不会丢。
- **新接口**：`GET /api/quests?limit=50`、`POST /api/quests/read`（`{"numbers":[…]}`
  或 `{"all":true}`）；`GET /api/status` 顶层新增 `quests: {unread, latest}` 供页签徽标使用。
  现有 `GET /api/quest`、`/api/command`、`/api/status` 的其余字段与行为不变。
- **前端**：`webui/index.html` 资源版本 `?v=1.0.20`；旧后端（没有 `/api/quests`）时列表显示一行提示，
  其余回执功能照旧。

</details>

<details>
<summary>上一版（v1.0.15）</summary>

## 〇、本版新增（v1.0.15）

**生成参数的每一次改动都留痕**。上一版出现过"我的生成参数莫名其妙被改了"却查不出来的情况：网页端
`/api/generation`（面板没有「应用」按钮，任何字段失焦就整组发回）与"跟随 WebUI 页面"这两条路**都不写日志**，
事后无从追溯。

- **每个入口都写一行**：`生成参数变更（网页端·尺寸）：832×1216 → 768×512`。来源标成 `网页端` / `QQ 侧`，
  入口名写明是尺寸、采样方法、预设样式、迭代步数、CFG、种子、底模、调度器 / Shift，还是某个预设名
  （`预设 default`）。覆盖的入口：网页端生成参数卡、网页指令与 QQ 指令、`.preset load`、
  `.style load`（套用样式记着的参数）、`.model preset` 之后的调度器 / Shift、以及**WebUI 页面把机器人
  记录顶掉**时（`跟随 WebUI 页面（…）`）。
- **值没变就不留痕**：面板反复失焦、重复提交同一份值不会刷屏。
- **网页端提示信息云**：改完参数回一句「已生效：…」并附「参数来源：…」（`.toast` 允许换行显示）。
- **说明**：采样方法 / 尺寸 / 预设样式跟着 WebUI 页面走，步数 / CFG / 种子 / 底模只存在
  `data/sd-parameters.json`，预设与样式载入一次改一整套——三条来源各自的日志会把"谁改的"讲清楚。
- **前端**：`webui/index.html` 资源版本 `?v=1.0.19`。

</details>

<details>
<summary>上一版（v1.0.14）</summary>

## 〇、本版新增（v1.0.14）

**样式分类从「一个跟着 LoRA 走的大类」改成一个 LoRA 一个分类，网页端的分类可以折起来**。上一版所有
展示图样式都堆在 `LoRA 附带` 里，LoRA 一多就分不清哪条样式属于哪个 LoRA；这一版还让样式面板的每个
分类都能折叠。

- **一个 LoRA 一个分类**（分类名 = 该 LoRA 的显示名）：判据按优先级来——
  样式里记录的 LoRA 名（展示图样式生成时写入的 `model.lora`）→ `data/civitai-style-links.json` 的映射
  （老样式没有标注就靠它，**只读不改**）→ 样式名前缀；三者都判不出具体是哪个 LoRA 时，才退回
  `LoRA 附带` 这个兜底分类。非 LoRA 样式按**归属栈**分类（`Anima` / `SDXL 栈` / `SD 1.5 栈` / …），
  连栈都判不出来的才是 `未分类`。
- **手动分类优先于自动分类**，命令与写法不变：`.style category <名称|#编号|#起-#止> <分类名>`
  （批量与 `.style rename` 同一套区间写法）；只给样式名＝查询；分类名给 `-` 或 `清除`＝清空手动分类，
  清空后回到自动分类。
- **`GET /api/styles` 的分类形状**：新增 `categories: [{key,name,kind,count,lora}]`——`kind` 是
  `lora`/`stack`/`manual`/`none`，`key` 形如 `lora:<LoRA名>`、`stack:xl`、`manual:<分类名>`、`none`；
  每条样式带 `categoryKey`；排序为 LoRA 组 → stack → manual → 未分类。
- **网页端分类可折叠**：样式面板的每个分类是一个可折叠条目——组头显示分类名 + 条数 + 箭头，点组头
  展开／收起（键盘 Enter/空格也行），工具栏另有「全部展开 / 全部折叠」。折叠状态存在浏览器 localStorage 里
  （键 `pixiko-style-collapsed`），**刷新后保持**，默认全部展开。
- **前端**：`webui/index.html` 资源版本 `?v=1.0.18`；样式面板按分类 key 分组（同名不同类不会串组），
  筛选下拉按分类 key 过滤；顶部汇总行在分类很多时只列前 8 个并标出总类数。
- **读取不改写数据文件**：读样式列表**不会改写** `data/local-styles.json`；写盘只写用户手动设置的分类。

</details>

<details>
<summary>上一版（v1.0.13）</summary>

## 〇、本版新增（v1.0.13）

**「跳过 9」不再是一个黑盒，搜索到的 LoRA 能翻页了**。这一版先把上一版留下的两个实际问题查清楚：
下载 LoRA 时那个「展示图样式：…跳过 9…」到底跳过了什么；以及"搜索到的 LoRA 只能看第一页"。

- **「跳过」按原因分类计数（先查清、再如实说）**：
  - 真实原因：用户那次的 9 张展示图**在 Civitai 上根本没有提示词元数据**（`metadata` 里连 `meta`
    字段都没有，作者没传 A1111 参数也没传 ComfyUI 工作流），所以一条样式都建不出来。真机核对：
    `GET /api/v1/model-versions/29367` 的 9 张图全部没有 `meta`，本地记录
    `data/civitai/tsumugiANY2.0.safetensors.json` 的 9 条 `showcase_prompts` 也全都带跳过原因。
  - 回执改成按原因分类：`展示图样式：新增 0，修正 0，复用 0，跳过 9（该图没有提示词元数据 9），失败 0。`
    后面再接一句可操作说明（`跳过原因：这些图在 Civitai 上就没有提示词，做不成样式。`），第 2 行起按原因
    给"提示（原因）：怎么办"。**不伪造提示词**——这类图就是做不成样式，如实说。
  - 跳过原因共四类，都对应一条现有可走的路径：`该图没有提示词元数据`、`同名样式内容不同`、
    `该图没有对应样式且本次不新建`（只补图不建样式的重跑路径）、`超出展示图样式上限`。
    「同名已存在」用的是**已有的「修正」语义**（`.lora cover <LoRA名>` / 网页「补展示图/样式」；
    旧内容先备份到 `data/civitai-style-backups`），没有另造一套 `overwrite`。
  - **硬编码上限清理**：展示图样式新增可配置上限 `config.json` 的 `civitai.showcase_limit`
    （默认 `0` = 不限；以前没有上限，也就没有静默丢图）。撞上限的那些按「超出展示图样式上限」计数并提示调大。
- **Civitai 搜索翻页（真机确认过分页协议）**：
  - **不能用 `page`**：带 `query` 的搜索一旦出现 `page`，Civitai 直接返回
    `{"error":"Cannot use page param with query search. Use cursor-based pagination."}`
    （`civitai.red` 与 `civitai.com` 实测都一样）。关键词搜索的 `cursor` **就是偏移量**
    （第一页 `metadata.nextCursor` = `"10"`、下一页 `"20"`…，`limit=1&cursor=20` 命中的正是
    `limit=10&cursor=20` 的第 1 条），所以第 N 页 = `cursor=(N-1)*每页条数`，可顺序翻也可直达。
  - **`hasMore` 是探出来的**：非空页永远带 `nextCursor`（＝`offset+limit`），"有 nextCursor"≠"还有下一页"，
    所以用一次 `limit=1` + 下一页游标的请求探一下。**总页数常常不知道**：关键词搜索的响应里没有结果总数，
    于是 `totalPages` 给 `-1` 且 `totalPagesKnown=false`，网页只在知道时写「第 X/Y 页」，**不编页数**。
  - 命令：`.lora search <关键词> [页码]`（`.lora query` 是同一个命令，语义不变）；
    回执给「第 X 页，本次返回 N 项（每页 10 条）」「本页编号：.lora download #N 指本页第 N 条」
    与「下一页：.lora search <词> <X+1>」。搜索词以数字结尾时用双引号：`.lora search "milf 2"`。
  - **本页编号语义**：`.lora download #N` 只认**当前这一页**的编号（`select()` / `selectionContext` 的
    编号列表与规划层看到的 `civitai` 列表都只放本页；上下文另给 `civitai_page`{page,hasMore,hint}）。
    翻过头（第 N 页为空）时**不动**已登记的编号，只回报「第 N 页没有内容…回到第一页：…」。
  - 网页：「Civitai 搜索」卡片加了「上一页 / 下一页 / 第 X 页」控件（搜索一次后可用，翻页保留搜索词），
    `/api/civitai/search` 请求体接受 `page`，响应给出 `page`、`pageSize`、`count`、`hasMore`、
    `totalPages`、`totalPagesKnown`、`nextPage`（越界时另给 `note`）。
  - 每页条数可配：`config.json` 的 `civitai.search_page_size`（默认 10，范围 1–50）。
- **前端**：`webui/index.html` 资源版本 `?v=1.0.17`；LoRA 搜索卡片的翻页控件与页数标签。
- **测试**：新增 `LoraPaginationTest`（68 条断言：cursor 分页、`hasMore` 探针、可配置每页条数、
  页码解析、越界文案、本页编号语义）与 `ShowcaseSkipTest`（42 条断言：真机形状的 9 张无提示词图按原因
  计数、同名 vs 无匹配分开计数、修正覆盖并备份、上限可配置）。全量 `build.ps1 -Test` 56 个 suite 全绿。

</details>

<details>
<summary>上一版（v1.0.12）</summary>

## 〇、本版新增（v1.0.12）

**样式会分类了，展示图样式还记住了展示图自己的尺寸**。上一版回答了"这份样式属于哪一栈"，
这一版回答"它归在哪一类、这张展示图有多大"——LoRA 下载时批量生成的展示图样式以前混在样式库里
和手写样式分不开，而且尺寸一律按预设/当前设置写死。

- **样式分类**（`data/local-styles.json` 每条多一个 `category` 字段）：
  - **LoRA 附带的展示图样式一律归到同一个大类「LoRA 附带」**。判据：样式记着 `model.sizeSource=preview`
    或 `model.lora`，或者它在 `data/civitai-style-links.json` 的映射里（老样式没有标注就靠这份映射，
    **只读它、不改它**）；
  - 其它样式按其**归属栈**给默认分类：`Anima` / `SDXL` / `SD 1.5` / `Flux` / `Qwen`；判不出栈就是 `未分类`；
  - **用户手动设过的分类优先**，默认规则只在"没手动设过"时生效；覆盖保存/同步/改名都不会把用户设的分类改回去；
  - 老文件没有 `category` 字段时读成空（＝未手动分类），**不报错、也不在读取时重写文件**；写盘时只写非空分类。
- **手动改分类两条路**：
  - 命令 `.style category <名称|#编号|#起-#止> <分类名>`（批量区间与 `.style rename` 同一套写法）、
    `.style category <名称>` 只查不改、分类名给 `-`/`清除`/`清空` 表示恢复默认规则；
    `.style list` 每行带 `［分类］`、`.style prompt` 与 `.style load` 回执也报分类，`.style`/`.style list`
    顶部给一行「分类：LoRA 附带 12、Anima 3、未分类 1」；
  - 网页「样式」页：列表**按分类分组**（组头 `LoRA 附带（12）`、`Anima（3）`），每行显示分类徽标与尺寸，
    点徽标或「改分类」按钮就地改（输入框带已有分类的候选，留空或 `-`＝恢复自动）；工具栏有分类筛选下拉，
    批量区加了「批量改分类」。**复用现有 `/api/styles/edit`**（`action: "category"` + `category` 参数），
    `/api/styles` 新增 `categories` 数组，每条带 `category` / `categoryStored` / `categoryAuto` /
    `width` / `height` / `sizeSource` / `previewImage` / `loraName`。
- **展示图样式读取展示图的实际像素尺寸**：新增 `cn.szu.bot.sd.ImageSize`，按 PNG / JPEG / GIF / BMP / WebP
  **文件头**取宽高（只读前 256 KB，不解码整图；认不出的格式才问 ImageIO，且只取 reader 的宽高），
  读不出来返回 null、**绝不编一个尺寸**。
  - 尺寸优先级：**展示图自己的像素**（`model.sizeSource = "preview"`）＞ 预设栈的 `width/height`
    ＞ 机器人当前设置（后两条沿用上一版的逐项回退：Anima 的 `anima_t2i_width/height` 是 0，会回退到当前设置）；
  - 顺带把展示图的相对路径记进 `model.previewImage`（`data/style-previews/<哈希>.png`，**不复制大图**），
    把所属 LoRA 的文件名记进 `model.lora`（`LoRA 附带` 大类的细分标签）；
  - 「补展示图」这条重跑路径也带尺寸：已存在但缺 `width/height` 的展示图样式会**补写一次**，
    回执里报「补展示图尺寸 N 条」（`CivitaiStyleSync.Outcome` 带计数）；重复同步仍是"复用"，不会反复重写。
  - `.style load`（命令与网页）照旧把记着的尺寸一起套用于机器人设置（`SdClient.applyModelParams`），
    回执里点明 `96×64` 来自展示图；分类是 `LoRA 附带` 而栈与当前不一致时，仍然沿用上一版的**栈不一致提示**，
    **不偷偷切栈**。
- **前端**：`webui/index.html` 资源版本 `?v=1.0.16`；样式面板按分类分组、分类徽标可在行内编辑、
  显示尺寸标签（`96×64`）、分类筛选下拉与「批量改分类」按钮。
- **测试**：新增 `StyleCategoryTest`（76 条断言）——默认分类规则、LoRA 附带统一大类（`sizeSource` / `lora` /
  `civitai-style-links.json` 三种判据）、手动/批量/清空改分类（命令 + 网页接口）、老文件没有 `category`
  字段时不报错也不被重写、**用临时目录里现生成的真 96×64 PNG/JPEG 与手写 WebP 头验证尺寸读取**、
  尺寸优先级（展示图盖掉预设的 1024×1024）与「补尺寸」回执、载入样式后尺寸真的套回机器人设置。
  全量 `build.ps1 -Test` 54 个 suite 全绿。

</details>

<details>
<summary>上一版（v1.0.11）</summary>

## 〇、本版新增（v1.0.11）

**自动识别底模的「归属栈」，而不是只认底模名字**。Forge／Forge Neo 把「底模 + VAE + 文本编码器」按预设
分成一栈一栈（`anima` / `xl` / `sd` / `flux` / `qwen` …），同一个底模文件在错的栈里跑出来就是全灰废图。
上一版只知道"这个 LoRA 的底模叫什么"，这一版回答"**它属于哪一栈、要切到哪个预设**"。

- **新类 `cn.szu.bot.sd.StackClassifier`**：`baseModelOf(Path)`（读 safetensors 头部，只读开头若干 KB，
  不加载权重）/ `stackOf(底模名, 文件名)` / `presetFor(栈, 预设列表)` / `stackLabel(栈)` / `sourceLabel(来源)`。
  栈关键词表覆盖 anima、illustrious、noobai、noob、pony、sdxl、xl、sd 1.5、sd1.5、sd_v1、sd 2、flux、qwen。
- **判据按可靠性排序，全部来自真实数据**（判不出来返回空串，**绝不编造**）：
  1. `safetensors` 头部 `__metadata__`：`ss_base_model_version`（如 `anima`）、`modelspec.architecture`
     （如 `stable-diffusion-xl-v1-base`、`anima-preview/lora`）、`ss_sd_model_name`（sd-scripts 的
     `model.safetensors` 是占位，不算）；
  2. 同一份头部的**张量名结构**：SDXL 有第二个文本编码器 `conditioner.embedders.1.*`，SD1.5 只有
     `conditioner.embedders.0.transformer.*`，SD2 是 `conditioner.embedders.0.model.*`（OpenCLIP），
     Anima 是 `net.llm_adapter.*` / `net.blocks.*`，Flux 是 `double_blocks.* + single_blocks.*`；
     LoRA 侧看 `lora_te_*`（单文本编码器＝SD1.5，`ss_v2=True` 则是 SD2）与 `lora_te1_*`/`lora_te2_*`（＝SDXL）；
  3. Civitai 下载记录的 `base_model`；
  4. Forge 的 LoRA 元数据；**Forge 预设配置**（`forge_checkpoint_<preset>`：哪个预设置的就是这个文件，
     它就属于那一栈，这是"归属"最直接的一条）；
  5. 兜底才按文件名关键词猜，结果一律标成推断。
  来源标记：`safetensors-header` / `safetensors-keys` / `civitai` / `forge-metadata` / `forge-preset` / `inferred`。
- **`/api/loras` 每项加 `stack` / `stackLabel` / `preset` / `stackSource` / `stackSourceLabel` / `evidence`，
  `groups` 改成按栈分组**（组头 `Anima 栈（1）`、`SDXL 栈（1）`，组里保留底模名 `baseModels`），
  `groupKey` 现在是栈键（前端按它分桶），底模单独给 `baseModelGroupKey`。
- **底模列表带栈**：`.model list` 每项标出 `waiIllustriousSDXL_v170 [SDXL 栈]`；
  `GET /api/options` 新增 `modelOptions`（title/name/stack/stackLabel/preset/stackSource/label/evidence），
  网页「基础模型」下拉直接显示栈，悬停给出判定依据。**没有另造接口**，扩的就是原有的下拉数据源。
- **防呆升级**：`forgeStackWarning` 改用栈判定——所选底模的栈 ≠ 当前栈就明说
  「`waiIllustriousSDXL_v170` 属于 SDXL 栈（预设 `xl`），当前是 anima 栈 —— 直接换会出全灰废图，
  请用 `.model preset xl` 或在 Forge 页面切到 `xl`」；判不出栈时保留原来的通用警告（按各预设的检查点名字比对）。
- **样式记 `stack` 字段**：写 `data/local-styles.json` 时一并记下归属栈；`.style load` 时样式栈 ≠ 当前栈
  会**如实提示**（"底模没有随之切换，请先 `.model preset xl`"），**不偷偷切栈**。
  `GET /api/styles` 每条带 `stack` / `stackLabel`，网页样式行显示栈。
- **前端**：`webui/index.html` 资源版本 `?v=1.0.15`；LoRA 面板按栈分组（组头栈名 + 底模 + 来源），
  每行显示「Anima 栈 · 底模 Anima」；基础模型下拉显示 `名字 [SDXL 栈]`；样式行显示栈。
- **测试**：新增 `StackClassifierTest`（用临时目录造最小合法 safetensors 头，覆盖 anima / sdxl / sd1.5 /
  sd2 / flux / qwen / 未知 / 占位元数据 / 截断头部 / 普通文件，以及关键词表、栈→预设映射、
  `modelInfos`、桩 Forge 下的 `/api/loras` 栈字段与分组）；`SdClientTest` 补了预设配置归属与
  `checkpointInfo` 的断言；`WebUiTest` 补了 `stack`/`modelOptions` 字段的断言。

</details>

<details>
<summary>上一版（v1.0.10）</summary>

**每个 LoRA 都有底模了，样式也按底模归类**。以前 LoRA 列表只有文件名与别名，样式里只有提示词——
这台机器上 Anima / NoobAI / SD1.5 的 LoRA 混在一堆，换上错的底模出的是全灰废图。

- **底模识别**（`.lora list`、`.lora detail`、网页「LoRA」面板、`GET /api/loras`）：优先级是
  **Civitai 下载记录**（`data/civitai/<文件名>.json` 的 `base_model`）→ **Forge 的 LoRA 元数据**
  （`/sdapi/v1/loras` 每项的 `metadata.ss_base_model_version`；sd-scripts 的占位
  `ss_sd_model_name=model.safetensors` 不算）→ **当前 Forge 预设栈**（标注「按当前预设推断」）。
  三层都取不到就如实显示「未识别」，**不编造底模名**。返回值带 `baseModel` / `baseModelSource`
  （`civitai` / `forge-metadata` / `preset-inferred`）/ `groupKey`，并在 `groups` 里给出按底模分组的结果。
- **网页 LoRA 面板按底模分组**：组头写底模名与来源（`Anima（4）· Civitai 记录`），每项也带自己的底模标签。
- **展示图样式带上模型参数**：下载 LoRA（以及「补展示图/样式」）时生成的样式，会把自己的底模 +
  **采样方法 / 调度器 / 步数 / CFG / Shift（蒸馏 CFG）/ 尺寸**一起写进 `data/local-styles.json` 的
  `model` 字段。采样参数取**当前 Forge 预设栈**的推荐值，预设里没给（例如 Anima 的尺寸是 0）就逐项
  回退到机器人当前设置；底模只有和当前栈对得上时才写成可加载的 `checkpoint`（写别的栈的检查点会出全灰废图）。
  老样式（v1.0.10 之前只有提示词的那些）在下次补展示图时会被补上模型参数，回执里报「补模型参数 N」。
- **样式面板标注底模**：`GET /api/styles` 每条样式带 `model` / `modelSummary` / `baseModel`，
  网页「样式」页上方汇总「底模：Anima 12、NoobAI 5」（列表**不重排**——编号是 `.style load #N`
  与批量 `#6-#9` 的依据），每条样式行显示它记着的底模与参数摘要。
- **测试**：`LoraBaseModelTest` 覆盖来源优先级（civitai → forge 元数据 → 预设推断）、占位元数据不当底模、
  展示图样式写盘带 `model`、样式载入套用参数、以及"都识别不出来时不编造底模"；`WebUiTest` 补了
  `/api/loras` 新字段与分组的断言。

</details>

<details>
<summary>上一版（v1.0.9）</summary>

**聊天提示词自动落盘**：每次聊天都把**实际发给 DeepSeek 的那份请求**导出成可读文本
`data/chat-prompt.txt`——`## system` 段就是系统提示词（硬规则 + 可用指令表 + 基础性格），
`## user` 段是本轮交给模型的输入（本轮消息、说话人、列表上下文、原文锚点、要求输出的 JSON 形状）。
原样导出，不做任何遮蔽，方便对照「机器人到底被交代了什么」以及跟着仓库同步。
默认**不含对话历史**（历史是逐轮累积的对话正文，不是提示词的一部分）；要连历史一起导出，
把 `config.json` 里 `chat.export_history` 设为 `true`；整个开关是 `chat.export_prompt`（默认开）。
导出失败只记日志，不影响聊天本身。

</details>

<details>
<summary>上一版（v1.0.8）</summary>

**适配 Forge／Forge Neo 的预设体系（尤其是 Anima）**。Forge Neo 把「底模 + VAE + 文本编码器」按**预设**
分成一栈一栈（`sd` / `xl` / `flux` / `qwen` / `anima` …），而机器人以前只知道 A1111 的 `sd_model_checkpoint`，
于是很容易变成「预设是 anima、检查点却加载着 SDXL」——实测这种错配**出的是 3.5 KB 的废图**，
预设与检查点对上之后才是正常的 0.9 MB 出图。

- `.model preset` — 列出全部预设：每个栈的底模、采样方法 / 调度器 / 步数 / CFG / 蒸馏 CFG / 尺寸 / 模块数。
- `.model preset <名字>` — 切到某个预设，并把该预设自己的参数**采纳**成机器人设置
  （Anima 就是「ER SDE + beta + 32 步 + CFG 4 + Shift 3 + 它自己的尺寸」），底模也对齐到这一栈。
- `.model set auto` — 不固定底模，每次提交任务时用 WebUI 当前模型。Forge 的预设栈就该这么用：固定成
  某个检查点的话，出图时会用 `override_settings` 把上一个预设的模型塞回来，盖掉刚切的预设。
- 生成请求新增 `scheduler` 与 `distilled_cfg_scale`（界面上叫 **Shift**）——Anima／Flux 这类流匹配模型的
  关键参数；`.sampler`／`.settings` 现在也会把它们显示出来。生成的图里能核对到
  `Schedule type: Beta` 与 `Shift: 3.0`。
- 底模写成**没有哈希后缀**的文件名（Forge 预设里存的就是这种写法，如 `animaCatTower_v11-full.safetensors`）
  也能对上模型列表里带 ` [哈希]` 的标题。

**人设以文件为准**：`data/chat-personality-kotori.txt` 现在**直接生效**。v1.0.6 起这份文件就随仓库同步，
但代码并不读它——实际生效的一直是 `config.json` 里那份被 gitignore 掉的短卡片，换台机器同步不过去。
现在：优先读这个文件（按 mtime 缓存，改完立刻生效），`config.json` 的 `chat.personality` 只是文件不存在时的退路；
`.chat personality`／`.chat infix` 与网页「聊天」页的修改都**写回文件**（同目录原子替换），人设才会跟着仓库走。

</details>

<details>
<summary>上一版（v1.0.7）</summary>

**任务回执：每条任务一个回执页 `/quest/#N`，配下方弹出的信息云**。任务一下达，屏幕下方就冒出一条信息云，
带「查看回执 #N →」链接；`/quest` 页面按任务号实时轮询（`POST /api/quest`，`id=0` 取最新），
逐步显示这条任务每一步的指令结果，**任务里如果有生成，图片就附在同一条回执里**；出图完成后再冒一条信息云。
一条回执对应一次任务（可以是 `.infix … → .prompt add … → .gen` 这样的多步链路），任务号由程序发号，
网页按钮、终端、对话三条入口走的是同一条回执通道。回执在内存里保留 30 分钟；
`/api/capture/close` 只停止收集、**不再删除回执**——否则分享出去的 `/quest/#22` 链接会失效。
出图队列计入回执的「还在跑」状态：生成一张图要几十秒，只看 DeepSeek/LoRA 会让页面提前停表、永远等不到那张图。

**控制台底部不再堆回执卡**：指令成功的结果由面板自己刷新 + 右上角 toast 呈现，完整输出在控制台（系统页）
或终端里看；面板底部只保留**报错卡**与**图片卡**（失败不能没声，出图/领取的图本来就在那儿看）。
「查看样式原文」「提示词生成」「聊天模型」与 `.help` / `.style list` 这类快捷指令改走信息框。

**LoRA 这一串**（都走控制台自己的接口，不再借道指令通道）：

- 新接口：`POST /api/lora/download`、`POST /api/lora/cover`、`GET /api/lora/progress`、`GET /api/lora/preview`。
- 下载时顺带抓 Civitai 展示图，存成 `<模型名>.preview.png`（WebUI 认这个命名）；LoRA 列表显示竖版缩略图（832:1216）。
- **下载不再改动任何提示词**：去掉自动写入的 `<lora:…>` 标签，也不再走会改写提示词的加载流程，
  只刷新 WebUI 的 LoRA 目录、解析出本机标签；要用就点列表里的「加载」。
- 下载回执从 20+ 行压到 4 行：触发词逐条原文、展示图逐条映射只进日志与 `data/civitai` 记录。
- 「补展示图」按本地 Civitai 记录补齐封面、缺的展示图样式与样式预览图（不重新下载模型文件）。

**样式预览图（仅网页端）**：展示图样式现在带配图，在「样式」页显示缩略图。图按样式名的 SHA-256 前 16 位
存成 `data/style-previews/<哈希>.png`——样式名带 `/`、空格、中文，不能直接当文件名，用哈希就不需要索引文件；
`.style rename` / `.style delete` 会跟着搬走或清掉；接口 `GET /api/style/preview`。

**修掉「展示图样式整批被跳过」**：原来只认 A1111 的 `meta.prompt`，凡是 ComfyUI 出图的模型
（提示词只存在于 `meta.comfy` 的工作流里）会被整批判成「没有提示词」——实测一个模型 4/4、另一个 3/4 被跳过。
现在解析 `meta.comfy` 并按可信度打分挑正反向（`positive` / `user prompt` > 标准文本编码节点 > `提示词`），
跳过给大模型看的「系统提示词」节点，并容错 Civitai 自己写出的非法 `"workflow": undefined`。

**小鸟说话风格按原作语料拟合**：新增 [`docs/KOTORI-STYLE.md`](docs/KOTORI-STYLE.md)（结论）、
[`docs/KOTORI-STYLE-STATS.md`](docs/KOTORI-STYLE-STATS.md)（数据，脚本生成）与
[`tools/kotori-style-fit.mjs`](tools/kotori-style-fit.mjs)（拿 `data/kotori-corpus.txt` 重新量一遍）。
按 2,660 条台词修正了提示词里的风格规则：终助词以「呢」为先（10.1%）、句号是默认收尾（50.2%）、
含 `！` 的台词只有 8.4%（旧规则要求「每 3～5 轮一个」，明显超标）、称呼默认「瑚太朗君」；
并修掉 `withAssertionMark` 给每条日常短回复强塞感叹号的问题（现在只兜底「几乎没有内容」的回复）。

</details>

<details>
<summary>上一版（v1.0.6）</summary>

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

</details>

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

### 开箱即用包 `pixiko-v1.1.1-runnable.zip`

1. 装好 **JDK 17+**。
2. **双击 `start.bat`**。第一次运行会自动生成 `config.json`（照 `config.example.json` 起一份），
   并在浏览器打开配置页 `http://127.0.0.1:8787/setup`；在那里填 owner QQ 与两条 DeepSeek 通道即可。
   没有浏览器时也可以先 `copy config.example.json config.json` 手动填，或运行 `run.bat --setup` 用命令行向导。
3. 配好即用；之后要改 DeepSeek 地址/密钥，直接在控制台「系统 → DeepSeek 通道」改。

> `start.bat` 跑的是包内已编译好的 `build/pixiko.jar`；只有需要改代码时才用 `build.ps1` + `run.bat`。

### 源码包 `pixiko-v1.1.1.zip`

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
   不要从别的目录调用。在项目根目录运行，60 个测试套件全部通过（`build.ps1 -Test`）。
7. **Logback 两个 jar 内不含许可文本**（上游如此），其 EPL-1.0 / LGPL-2.1 全文需查
   `THIRD-PARTY-LICENSES.md` 里给出的官方链接；`gson` 与 `snakeyaml` 的 jar 内同样没有许可文件，
   但二者均为 Apache-2.0，文本已随包提供。

---

## 六、在 GitHub Releases 里发布这个 zip

1. 打开仓库 → 右侧 **Releases** → **Draft a new release**，Tag 填 `v1.1.1`（新建 tag），标题填 `Pixiko v1.1.1`。
2. 把 `pixiko-v1.1.1.zip` 与 `pixiko-v1.1.1-runnable.zip`（以及各自的 `.sha256`）拖进附件区，
   正文粘贴本文件内容后点 **Publish release**。

> 建仓库时 License 请选 **None**（本项目保留所有权利，不使用开源许可证）。
