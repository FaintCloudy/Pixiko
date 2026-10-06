/*
 * Pixiko 手机端 Web UI —— 屏幕「任务回执」（M2）。
 *
 * 职责：`quest`（回执列表）与 `quest-detail`（回执正文）两块屏，全部走现有 `/api/*`：
 *   POST /api/quests      {limit}                        → {quests:[…],unread,latest,total}
 *   POST /api/quest       {id}                           → {messages,texts,images,error?,busy,closed,command,quest,latest}
 *   POST /api/quests/read {numbers:[n]} | {all:true}     → {unread,marked}
 *   POST /api/progress    {}                             → {running,percent,step,steps,etaSeconds,text,queue}（生成中进度条）
 *   POST /api/images      {limit}                        → {images:[{path,name,…}]}（生成期间提前到达的新图）
 *
 * 契约：只使用 window.PixikoM.*（register/go/current/api/toast/sheet/confirm/prompt/imageUrl/
 * openViewer/el/clear/esc/fmtTime/fmtBytes/pollWhileVisible/state/refreshStatus），不另造全局；
 * 样式在本文件顶部注入（前缀 .q-*），一个字节都不改 app.css / index.html / app.js。
 */
(function () {
  'use strict';
  if (typeof window === 'undefined' || !window.PixikoM) return;
  var P = window.PixikoM;

  /* ───────────────────────── 1. 样式（内联注入，前缀 .q-*） ───────────────────────── */
  var STYLE_ID = 'pixiko-m-quest-style';
  if (!document.getElementById(STYLE_ID)) {
    var style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = [
      /* 屏幕骨架：外壳已经有了 app bar（#m-bar）与底部 tab，这一屏只管正文。
         滚动交给外壳的 #m-main（同一条原生滚动），所以这里不自己滚，也不做 padding-top。
         底部多留一点：既有 tab 栏，也有这一屏右下角的操作按钮。 */
      '.q-screen{background:transparent;color:#e9efff;-webkit-user-select:none;user-select:none;',
      '  overflow-x:hidden;padding-bottom:26px}',
      '.q-screen input,.q-screen textarea{-webkit-user-select:text;user-select:text;font-size:16px}',
      /* 屏内摘要行（app bar 是外壳的，这里补未读总数与溢出菜单；刷新由外壳的刷新按钮 + 下拉手势负责） */
      '.q-toprow{display:flex;align-items:center;gap:8px;min-height:44px;margin-bottom:8px}',
      '.q-toprow .q-row-main{flex:1 1 auto;min-width:0}',
      '.q-title{flex:1 1 auto;min-width:0;font-size:18px;font-weight:700;line-height:1.2;overflow:hidden;',
      '  text-overflow:ellipsis;white-space:nowrap}',
      '.q-badge{flex:0 0 auto;min-width:22px;height:22px;padding:0 7px;border-radius:11px;background:#ff6b6b;color:#fff;',
      '  font-size:12px;font-weight:700;line-height:22px;text-align:center;font-variant-numeric:tabular-nums}',
      '.q-badge[hidden]{display:none}',
      '.q-icon{flex:0 0 auto;display:inline-flex;align-items:center;justify-content:center;width:44px;height:44px;',
      '  margin:0;padding:0;border:0;border-radius:12px;background:transparent;color:#93a4c4;font-size:20px;',
      '  font-family:inherit;line-height:1;cursor:pointer;touch-action:manipulation;transition:background .12s,transform .12s}',
      '.q-icon:active{background:#1b2740;color:#5aa2ff;transform:scale(.94)}',
      '@keyframes q-spin{to{transform:rotate(360deg)}}',
      /* 执行中的小转圈（回执详情头部的「⟳ 执行中」标签） */
      '.q-tag.q-run:before{content:"";display:inline-block;width:11px;height:11px;border-radius:50%;',
      '  border:2px solid rgba(90,162,255,.3);border-top-color:#5aa2ff;animation:q-spin .9s linear infinite}',
      /* 下拉刷新提示（手势由本屏接管：滚到顶再下拖就刷这一屏） */
      '.q-ptr{height:0;overflow:hidden;display:flex;align-items:center;justify-content:center;gap:8px;',
      '  color:#93a4c4;font-size:var(--m-fs-sm,13px);line-height:1.45;transition:height .18s}',
      '.q-ptr.q-on{height:40px}',
      /* 正文容器（外壳负责滚动，这里只做宽度约束） */
      '.q-body{min-width:0}',
      '.q-body>*{max-width:100%}',
      /* 卡片 / 列表行 */
      '.q-card{background:#131c2e;border:1px solid #1e2a44;border-radius:14px;overflow:hidden}',
      '.q-rows{padding:0 2px}',
      '.q-row{display:flex;align-items:flex-start;gap:10px;width:100%;min-height:64px;padding:14px;margin:0 0 12px;',
      '  border:1px solid #1e2a44;border-radius:14px;background:#131c2e;color:inherit;font:inherit;text-align:left;',
      '  cursor:pointer;touch-action:manipulation;transition:transform .1s,background .12s,border-color .12s}',
      '.q-row:active{background:#18233a;transform:scale(.985)}',
      '.q-row.q-unread{background:linear-gradient(90deg,rgba(90,162,255,.16),rgba(19,28,46,0) 60%),#131c2e;',
      '  border-color:rgba(90,162,255,.5);box-shadow:inset 3px 0 0 #5aa2ff}',
      '.q-row-main{flex:1 1 auto;min-width:0}',
      '.q-row-top{display:flex;align-items:baseline;gap:8px;min-width:0}',
      '.q-no{flex:0 0 auto;font-weight:700;color:#5aa2ff;font-size:var(--m-fs-body,15px);',
      '  font-variant-numeric:tabular-nums}',
      '.q-cmd{flex:1 1 auto;min-width:0;font-size:var(--m-fs-body,15px);font-weight:600;line-height:1.5;',
      '  white-space:normal;overflow:visible;text-overflow:clip;overflow-wrap:anywhere;word-break:break-word}',
      '.q-when{flex:0 0 auto;color:#93a4c4;font-size:12.5px;line-height:1.45;',
      '  font-variant-numeric:tabular-nums}',
      '.q-sum{margin-top:6px;color:#93a4c4;font-size:var(--m-fs-sm,13px);line-height:1.45;',
      '  white-space:normal;overflow:visible;text-overflow:clip;overflow-wrap:anywhere;word-break:break-word}',
      '.q-tags{display:flex;flex-wrap:wrap;gap:6px;align-items:center;margin-top:7px}',
      '.q-tag{display:inline-flex;align-items:center;gap:4px;padding:3px 8px;border-radius:999px;background:#1b2740;',
      '  border:1px solid #24314e;color:#93a4c4;font-size:var(--m-fs-tag,12px);line-height:1.5;',
      '  max-width:100%;overflow-wrap:anywhere}',
      '.q-tag.q-warn{background:rgba(255,107,107,.14);border-color:rgba(255,107,107,.45);color:#ff9b9b}',
      '.q-tag.q-live{background:rgba(90,162,255,.16);border-color:rgba(90,162,255,.45);color:#9cc7ff}',
      '.q-dot{flex:0 0 auto;display:inline-flex;align-items:center;justify-content:center;min-width:20px;height:20px;',
      '  padding:0 6px;border-radius:10px;background:#ff6b6b;color:#fff;font-size:12px;font-weight:700;',
      '  font-variant-numeric:tabular-nums}',
      '.q-chev{flex:0 0 auto;align-self:center;color:#5d6f92;font-size:20px;line-height:1}',
      /* 三态 */
      '.q-state{margin:8px 0;padding:14px;border:1px dashed #24314e;border-radius:14px;background:#101a2b;',
      '  color:#93a4c4;font-size:var(--m-fs-sm,13px);line-height:1.5;overflow-wrap:anywhere;word-break:break-word}',
      '.q-state.q-err{border-style:solid;border-color:rgba(255,107,107,.45);background:rgba(255,107,107,.08);color:#ffc9c9}',
      '.q-empty{padding:46px 18px;text-align:center;color:#93a4c4}',
      '.q-empty .q-empty-icon{font-size:40px;opacity:.5}',
      '.q-empty .q-empty-text{margin-top:10px;font-size:15px;color:#c9d6ee}',
      '.q-empty .q-empty-sub{margin-top:6px;font-size:12.5px}',
      '.q-retry{display:inline-flex;align-items:center;justify-content:center;min-height:44px;margin-top:14px;padding:0 18px;',
      '  border:1px solid rgba(90,162,255,.5);border-radius:12px;background:rgba(90,162,255,.14);color:#9cc7ff;',
      '  font:inherit;font-size:14.5px;cursor:pointer}',
      '.q-retry:active{transform:scale(.97)}',
      /* 骨架屏 */
      '.q-sk{height:64px;margin-bottom:8px;border-radius:14px;background:linear-gradient(90deg,#141e32,#1b2740,#141e32);',
      '  background-size:200% 100%;animation:q-sk 1.1s linear infinite}',
      '@keyframes q-sk{0%{background-position:0 0}100%{background-position:-200% 0}}',
      /* 详情头部 */
      '.q-head{padding:14px;margin-bottom:12px}',
      '.q-head .q-cmd{font-weight:600}',
      '.q-head-meta{margin-top:6px;color:#93a4c4;font-size:var(--m-fs-sm,13px);line-height:1.45;',
      '  overflow-wrap:anywhere}',
      '.q-progress{margin-top:10px}',
      '.q-prog-track{height:6px;border-radius:3px;background:#1b2740;overflow:hidden}',
      '.q-prog-fill{display:block;height:100%;border-radius:3px;background:linear-gradient(90deg,#5aa2ff,#8fc0ff);',
      '  transition:width .3s}',
      '.q-progress-text{margin-top:6px;color:#93a4c4;font-size:12.5px;line-height:1.45;',
      '  font-variant-numeric:tabular-nums}',
      /* 步骤卡 */
      '.q-step{background:#131c2e;border:1px solid #1e2a44;border-radius:14px;padding:14px;margin-bottom:12px}',
      '.q-step.q-err{border-color:rgba(255,107,107,.5)}',
      '.q-step-head{color:#93a4c4;font-size:var(--m-fs-tag,12px);letter-spacing:.4px;margin-bottom:6px;',
      '  font-variant-numeric:tabular-nums}',
      '.q-text{white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;font-size:var(--m-fs-body,15px);',
      '  line-height:1.5;color:#dde7fb}',
      '.q-step.q-err .q-text{color:#ffc9c9}',
      /* 图集网格：一条回执里的图片合成一个网格。
         修「图片在预览界面被异常压缩」：旧写法是 `.q-tile{aspect-ratio:1/1}` +
         `img{width:100%;height:100%;object-fit:cover}` —— 1:1 方块里塞非方形图，横图两头被裁，
         换成 contain 之后又变成四周一大片空白。现在**格子跟着图片自身的宽高比走**：
         .q-tile 先给中性 `aspect-ratio:4/3` 占位（图没到也不塌成 0 高），图片 load 之后由
         app.js 的 `P.applyNaturalRatio()` 把 aspect-ratio 换成 naturalWidth/naturalHeight；
         `<img>` 只写 width:100% + height:auto。行高由图片自己撑开（grid-auto-rows:auto）。 */
      '.q-grid{display:grid;grid-template-columns:repeat(2,1fr);grid-auto-rows:auto;gap:8px;margin-top:4px;align-items:start}',
      '.q-grid:has(.q-tile:only-child){grid-template-columns:1fr}',   /* 只有一张图 → 占满整行，不留半边空列 */
      /* 尺寸上限（用户报的「回执的图片预览不能过大」）：与对话气泡同一条口径 ——
         按原图比例撑满列宽时竖图会顶满整屏，所以给宿主加 34vh 上限 + contain 居中。
         横图够不到上限，行为照旧；查看器里仍是原图。 */
      '.q-tile{position:relative;display:flex;align-items:center;justify-content:center;width:100%;aspect-ratio:4/3;',
      '  max-height:34vh;padding:0;margin:0;border:1px solid #24314e;',
      '  border-radius:12px;overflow:hidden;background:#0f1728;cursor:pointer;touch-action:manipulation}',
      '.q-tile:active{transform:scale(.97)}',
      '.q-tile img{width:100%;height:auto;max-height:100%;object-fit:contain;display:block}',
      '.q-tile.q-fail{display:flex;align-items:center;justify-content:center;color:#93a4c4;font-size:11px;text-align:center}'
    ].join('');
    document.head.appendChild(style);
  }

  /* ───────────────────────── 2. 小工具（全部建立在 PixikoM 之上） ───────────────────────── */
  function el(tag, cls, text) {
    if (typeof P.el === 'function') {
      try { return P.el(tag, cls, text); } catch (error) { /* 落到下面自己造 */ }
    }
    var node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
  }
  function clear(node) {
    if (!node) return;
    if (typeof P.clear === 'function') { try { P.clear(node); return; } catch (error) { /* 落下面 */ } }
    while (node.firstChild) node.removeChild(node.firstChild);
  }
  function toast(text) { if (text && typeof P.toast === 'function') { try { P.toast(String(text)); } catch (error) { /* 提示失败不影响功能 */ } } }
  /**
   * 一张卡片的小标题：**按这一条消息自己的内容**取名，不再一律写「第 N 步」。
   *
   * <p>用户报的「回执条目正文内容混乱」根因就在这里：一条回执的 messages 里既有
   * 「多步执行完成：2/2 条（全部成功）」这种汇总、也有【1】.infix…【2】.gen 这种真正的步骤、
   * 还有「任务 #2 已完成…」「已生成的图片将自动领取。」这种状态，甚至一条纯图片消息。
   * 老写法把它们**逐条编号成"第 1 步 / 第 2 步…"**，于是"步骤 1"是汇总、
   * "*步骤 3"是收图状态，用户看到的就是对不上的编号 + 一堆碎块。
   *
   * <p>判据（从最有信息量的开始）：
   * <ol>
   *   <li>以 `【N】…` 开头 → 用方括号里那截当标题（`.infix 加入…` / `.gen`）；</li>
   *   <li>首行短（≤24 字）且不是正文句 → 直接用它（例如「任务 #2 已完成：1/1…」）；</li>
   *   <li>纯图片 → 「生成的图片」；</li>
   *   <li>都没有 → 回退成「第 N 步」（保留老行为，不再乱编号的场合才用）。</li>
   * </ol>
   */
  function stepTitle(stepText, index, hasPic) {
    var body = text(stepText);
    var lines = body.split('\n');
    var first = (lines[0] || '').trim();
    var marker = /^【\s*(\d+)\s*】\s*([^\n]*)$/.exec(first);
    if (marker && marker[2].trim()) {
      var label = marker[2].trim();
      return (label.length > 30 ? label.slice(0, 30) + '…' : label);
    }
    if (first && first.length <= 24 && !/[。！？]$/.test(first)) return first;
    if (hasPic) return '生成的图片';
    if (!first) return '第 ' + (index + 1) + ' 步';
    // 长首行：**优先在标点处断开**，别把话切成半句（"已加入生成队列，任务 #3，共…"）。
    var head = first.slice(0, 24);
    var cut = Math.max(head.lastIndexOf('：'), head.lastIndexOf('，'), head.lastIndexOf('、'));
    if (cut >= 6) head = head.slice(0, cut);
    return head + '…';
  }
  /**
   * 任意值 → 字符串（`null`/`undefined` 变空串）。
   *
   * <p><b>必须在本文件里定义</b>：`screen-quest.js` 有 15 处 `text(...)`（步骤标题、正文、
   * 摘要、错误文案都靠它），而 `text()` 只定义在 **screen-styles.js 自己的 IIFE 里、不是全局**。
   * 缺了它，`stepTitle()` 与 `fillRow()` 一调用就抛 `ReferenceError: text is not defined` ——
   * 于是列表卡在骨架屏、详情屏直接变成"这一屏加载失败"，用户看到的就是**回执条目正文错乱/不出来**。
   */
  function text(value) { return value === undefined || value === null ? '' : String(value); }
  function num(value, fallback) {
    var n = Number(value);
    return Number.isFinite(n) ? n : (fallback === undefined ? 0 : fallback);
  }
  /** 时间：优先用契约里的 fmtTime，拿不到就自己算相对时间。 */
  function when(iso, ageMillis) {
    if (typeof P.fmtTime === 'function') {
      try { var got = P.fmtTime(iso); if (got) return String(got); } catch (error) { /* 落下面 */ }
    }
    var ms = num(ageMillis, NaN);
    if (!Number.isFinite(ms) && iso) {
      var at = Date.parse(String(iso));
      if (Number.isFinite(at)) ms = Date.now() - at;
    }
    if (!Number.isFinite(ms) || ms < 0) return '';
    if (ms < 60000) return '刚刚';
    var minutes = Math.floor(ms / 60000);
    if (minutes < 60) return minutes + ' 分钟前';
    var hours = Math.floor(minutes / 60);
    if (hours < 24) return hours + ' 小时前';
    var days = Math.floor(hours / 24);
    if (days < 7) return days + ' 天前';
    if (iso) { var date = new Date(String(iso)); if (!Number.isNaN(date.getTime())) return date.toLocaleString('zh-CN'); }
    return days + ' 天前';
  }
  /** 缩略图长边（契约 `&w=`；格子约 110 CSS px，320 够 2–3 倍屏）。查看器/桥一律不用它。 */
  var THUMB_W = 320;

  /**
   * 服务端存档里的图片字段有三种写法（见 app.js 的同名说明）：
   *   · 纯字符串 —— `/api/chat/log` 的 `images:["file:///…"]`；
   *   · `{file}` —— `/api/quest` 的 `images` 与 `messages` 里的 `{type:'image', file:"…"}`；
   *   · `{path}` / `{url}` —— `/api/images` 的 `{path:"data/generated/…"}`。
   * 直接 `String(对象)` 会拼出 `path=%5Bobject%20Object%5D`（服务端 400）—— 这里先取出字符串。
   */
  function imageOf(value) {
    if (value === null || value === undefined) return '';
    if (typeof value === 'string') return value;
    if (typeof value === 'object') {
      var keys = ['file', 'path', 'url', 'src'];
      for (var i = 0; i < keys.length; i++) {
        var got = value[keys[i]];
        if (typeof got === 'string' && got) return got;
      }
      return '';
    }
    return String(value);
  }
  /** 路径归一：任何含 `data/generated/` 的写法都截成从它开始的相对路径；其余原样。 */
  function normalizePath(value) {
    var path = imageOf(value).replace(/\\/g, '/');
    if (!path) return '';
    var at = path.indexOf('data/generated/');
    return at >= 0 ? path.slice(at) : path;
  }
  /**
   * 图片地址：外链走原样，本地路径交给契约的 imageUrl（它会拼令牌与 /api/image）。
   * `thumb=true`（默认）加 `&w=` 拿缩略图；查看器/原生桥要原图，必须 `thumb=false`。
   */
  function imageSrc(file, thumb) {
    var path = normalizePath(file);
    if (/^https?:\/\//i.test(path)) return path;
    if (typeof P.imageUrl === 'function') {
      try { return P.imageUrl(path, thumb === false ? undefined : { w: THUMB_W }); } catch (error) { /* 落下面 */ }
    }
    return path;
  }
  /** 原图地址（查看器、原生桥保存/分享、复制地址专用）。 */
  function imageFullSrc(file) { return imageSrc(file, false); }
  /** 路径 → 文件名（图集与查看器的说明文字用）。 */
  function shortName(file) { return text(file).replace(/^.*[\\/]/, '').split('?')[0]; }

  /**
   * 下拉刷新：这一屏自己接管手势（提示条放在屏内最上面，展开时把正文推下去，不做回弹动画）。
   *
   * <p>判据**不在这里**：用外壳那份共用实现 `P.ptrInstall()`（认"手指起点所属的可滚动容器"是否真的
   * 在顶部、过程中滚过一次就作废、只认下拖），原因见 app.js 里 `PixikoM.ptrInstall` 的长注释。
   * 这里只负责提示条的文案与 class。
   */
  function installPullToRefresh(tip, onFire) {
    var host = document.getElementById('m-main') || document.getElementById('m-app') || document;
    P.ptrInstall(host, {
      screen: 'quest',
      progress: function (armed) { tip.classList.add('q-on'); tip.textContent = armed ? '松手刷新' : '下拉刷新'; },
      disarm: function () { tip.classList.remove('q-on'); tip.textContent = '下拉刷新'; },
      fire: function () {
        tip.textContent = '正在刷新…';
        return Promise.resolve().then(onFire).catch(function () { /* 错误已经由屏内三态显示 */ })
          .then(function () { tip.textContent = '下拉刷新'; });
      }
    });
  }

  /** 同步契约里的未读总数 / 列表：写进 state.quests.unread 后，也让屏内的未读摘要条跟着变。 */
  function syncCore(rows, unread, latest) {
    var state = P.state;
    if (state && typeof state === 'object') {
      if (Array.isArray(rows)) state.questList = rows;
      if (!state.quests || typeof state.quests !== 'object') state.quests = { unread: 0, latest: 0 };
      if (Number.isFinite(Number(unread))) state.quests.unread = Math.max(0, Number(unread));
      if (Number.isFinite(Number(latest))) state.quests.latest = Math.max(0, Number(latest));
    }
    if (list.sumBadge) {
      list.sumBadge.textContent = String(Math.max(0, Number(unread) || 0));
      list.sumBadge.hidden = !(Number(unread) > 0);
    }
    if (list.sumText) {
      var total = Array.isArray(rows) ? rows.length : (Array.isArray(state && state.questList) ? state.questList.length : 0);
      list.sumText.textContent = '共 ' + total + ' 条回执 · '
        + (Number(unread) > 0 ? '未读 ' + Math.max(0, Number(unread)) + ' 条' : '全部已读')
        + (Number(latest) > 0 ? ' · 最新 #' + Math.max(0, Number(latest)) : '');
    }
  }

  function stateBox(cls, message, retry) {
    var box = el('div', 'q-state' + (cls ? ' ' + cls : ''), message);
    if (typeof retry === 'function') {
      var button = el('button', 'q-retry', '重试');
      button.type = 'button';
      button.addEventListener('click', retry);
      box.appendChild(document.createElement('br'));
      box.appendChild(button);
    }
    return box;
  }
  function emptyBox(sub) {
    var box = el('div', 'q-empty');
    box.appendChild(el('div', 'q-empty-icon', '📭'));
    box.appendChild(el('div', 'q-empty-text', '还没有回执'));
    box.appendChild(el('div', 'q-empty-sub', sub || '在电脑或对话里发一条指令，这里就会出现它的执行回执。'));
    return box;
  }
  function skeleton(count) {
    var box = el('div');
    for (var i = 0; i < (count || 5); i++) box.appendChild(el('div', 'q-sk'));
    return box;
  }
  /** app bar 上的 44px 图标按钮（用 SVG 画，不引任何外部资源）。 */
  var ICONS = {
    more: 'M6 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm6 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm6 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4z'
  };
  function iconButton(kind, label, onTap) {
    var button = el('button', 'q-icon');
    button.type = 'button';
    button.setAttribute('aria-label', label);
    button.title = label;
    button.innerHTML = '<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">'
      + '<path d="' + ICONS[kind] + '" fill="currentColor"/></svg>';
    button.addEventListener('click', function (event) {
      if (event && event.preventDefault) event.preventDefault();
      onTap();
    });
    return button;
  }

  /* ───────────────────────── 3. 列表屏 id = 'quest' ───────────────────────── */
  var LIMIT = 60;
  var LIST_POLL_MS = 3000;          // 列表实时刷新：与出图屏同频（可见时每 3 秒一次）
  var list = { root: null, body: null, refresh: null, sumBadge: null, sumText: null, quests: null,
    unread: 0, latest: 0, error: '', loading: false, timer: null, lastAt: 0,
    rowNodes: Object.create(null), percent: 0 };

  /** 建一行（只建骨架 + 绑一次点击；内容统统由 {@link fillRow} 改）。 */
  function rowFor(quest) {
    var row = el('button', 'q-row');
    row.type = 'button';
    row.setAttribute('data-number', String(num(quest.number, 0)));
    var main = el('div', 'q-row-main');
    var top = el('div', 'q-row-top');
    top.appendChild(el('span', 'q-no', ''));
    top.appendChild(el('span', 'q-cmd', ''));
    top.appendChild(el('span', 'q-when', ''));
    main.appendChild(top);
    main.appendChild(el('div', 'q-sum', ''));
    main.appendChild(el('div', 'q-tags'));
    row.appendChild(main);
    row.appendChild(el('span', 'q-dot', '1'));
    row.appendChild(el('span', 'q-chev', '›'));
    row.addEventListener('click', function () { openDetail(num(row.getAttribute('data-number'), 0)); });
    return row;
  }

  /**
   * 把一条回执的内容写进**已有的行**（未读、摘要、标签、生成中进度都只改这一行）。
   *
   * <p>为什么必须原地改：列表每 3 秒轮询一次，`clear(rows)` 整块重画会把所有行节点换掉 ——
   * 滚动位置会跳、点击目标会失效、整屏闪一下（用户要的是"实时刷新"，不是"每 3 秒重画一次"）。
   */
  function fillRow(row, quest) {
    var unread = !!quest.unread;
    if (row.classList.contains('q-unread') !== unread) row.classList.toggle('q-unread', unread);
    var main = row.firstChild;
    var top = main && main.firstChild;
    var no = top && top.children[0], cmd = top && top.children[1], whenNode = top && top.children[2];
    var sum = main && main.children[1];
    var tags = main && main.children[2];
    if (no) no.textContent = '#' + num(quest.number, 0);
    if (cmd) {
      var wantCmd = text(quest.command) || '（没有指令文本）';
      if (cmd.textContent !== wantCmd) cmd.textContent = wantCmd;
    }
    if (whenNode) {
      var wantWhen = when(quest.startedAt, quest.ageMillis);
      if (whenNode.textContent !== wantWhen) whenNode.textContent = wantWhen;
    }
    if (sum) {
      var wantSum = text(quest.summary) || '（这条回执还没有输出）';
      if (sum.textContent !== wantSum) sum.textContent = wantSum;
    }
    if (tags) fillTags(tags, quest);
    var dot = row.lastChild && row.lastChild.previousSibling;
    if (dot && dot.classList && dot.classList.contains('q-dot')) dot.style.display = unread ? '' : 'none';
  }

  /** 标签行：未读 / 段数 / 张数 / 进行中（带实时百分比）/ 失败 / 已完成 / 正文不在了。 */
  function fillTags(tags, quest) {
    var unread = !!quest.unread;
    var texts = num(quest.texts, 0);
    var images = num(quest.images, 0);
    var busy = !!(quest.busy || (quest.done === false && !quest.expired));
    var percent = busy && list.percent > 0 ? '生成中 ' + Math.round(list.percent) + '%' : '进行中…';
    var wanted = [];
    if (unread) wanted.push(['未读', 'q-tag q-live']);
    if (texts) wanted.push([texts + ' 段文字', 'q-tag']);
    if (images) wanted.push([images + ' 张图', 'q-tag']);
    if (busy) wanted.push([percent, 'q-tag q-live']);
    else if (quest.error) wanted.push(['失败', 'q-tag q-warn']);
    else wanted.push(['已完成', 'q-tag']);
    if (quest.expired) wanted.push(['正文不在了', 'q-tag q-warn']);
    var same = tags.children.length === wanted.length;
    for (var i = 0; same && i < wanted.length; i++) {
      var node = tags.children[i];
      if (node.textContent !== wanted[i][0] || node.className !== wanted[i][1]) same = false;
    }
    if (same) return;
    clear(tags);
    wanted.forEach(function (pair) { tags.appendChild(el('span', pair[1], pair[0])); });
  }

  /**
   * 列表增量同步：按 `data-number` 复用行节点 —— 新回执只 append 一行、消失的才摘掉、
   * 其余的行原地更新（未读数、摘要、进度）。顺序按新数据排（`insertBefore` 只移动节点）。
   */
  function syncRows(quests) {
    var rows = list.rows;
    if (!rows) return;
    var store = list.rowNodes;
    var keep = Object.create(null);
    quests.forEach(function (quest) {
      var key = String(num(quest.number, 0));
      keep[key] = true;
      var row = store[key];
      if (!row) { row = rowFor(quest); store[key] = row; }
      fillRow(row, quest);
    });
    Object.keys(store).forEach(function (key) {
      if (keep[key]) return;
      var dead = store[key];
      if (dead.parentNode === rows) rows.removeChild(dead);
      delete store[key];
    });
    var previous = null;
    quests.forEach(function (quest) {
      var row = store[String(num(quest.number, 0))];
      if (!row) return;
      if (previous ? previous.nextSibling !== row : rows.firstChild !== row) {
        rows.insertBefore(row, previous ? previous.nextSibling : rows.firstChild);
      }
      previous = row;
    });
  }

  function renderList() {
    if (!list.rows) return;
    var rows = list.rows;
    if (list.loading && !list.quests) {
      clear(rows);
      list.rowNodes = Object.create(null);
      rows.appendChild(skeleton(5));
      return;
    }
    if (list.error) {
      clear(rows);
      list.rowNodes = Object.create(null);
      rows.appendChild(stateBox('q-err', '回执列表读取失败：' + list.error, function () { loadList(true); }));
      if (list.quests && list.quests.length) {
        rows.appendChild(el('div', 'q-step-head', '下面是上一次读到的内容'));
        list.quests.forEach(function (quest) { rows.appendChild(rowFor(quest)); });
      }
      return;
    }
    var quests = list.quests || [];
    if (!quests.length) {
      clear(rows);
      list.rowNodes = Object.create(null);
      rows.appendChild(emptyBox());
      return;
    }
    syncRows(quests);              // ← 增量：只动变化的那几行
  }

  function loadList(showSpinner) {
    if (list.loading) return Promise.resolve(null);
    list.loading = true;
    list.lastAt = Date.now();
    if (!list.quests) renderList();
    var pending = P.api('/api/quests', { body: { limit: LIMIT } });
    return Promise.resolve(pending).then(function (data) {
      var quests = data && Array.isArray(data.quests) ? data.quests : [];
      list.quests = quests;
      list.unread = Number.isFinite(Number(data && data.unread))
        ? Math.max(0, Number(data.unread))
        : quests.filter(function (item) { return item && item.unread; }).length;
      list.latest = Number.isFinite(Number(data && data.latest)) ? Number(data.latest) : 0;
      list.error = '';
      syncCore(quests, list.unread, list.latest);
      renderList();
      return data;
    }).catch(function (error) {
      list.error = text(error && error.message) || String(error);
      renderList();
      if (showSpinner) toast('回执列表读取失败：' + list.error);
      return null;
    }).then(function (data) {
      list.loading = false;
      return data;
    });
  }

  /** 本地先减未读（徽标立刻掉），再让后端确认；失败就把数字补回去。 */
  function markRead(numbers) {
    var targets = (numbers || []).map(function (value) { return num(value, 0); }).filter(function (value) { return value > 0; });
    if (!targets.length) return Promise.resolve(null);
    var before = list.unread;
    targets.forEach(function (number) {
      if (Array.isArray(list.quests)) {
        list.quests.forEach(function (item) { if (item && num(item.number, 0) === number) item.unread = false; });
      }
    });
    list.unread = Math.max(0, list.unread - targets.length);
    syncCore(list.quests, list.unread, list.latest);
    renderList();
    return Promise.resolve(P.api('/api/quests/read', { body: { numbers: targets } })).then(function (data) {
      var value = data && Number(data.unread);
      if (Number.isFinite(value)) { list.unread = Math.max(0, value); syncCore(list.quests, list.unread, list.latest); }
      renderList();
      return data;
    }).catch(function (error) {
      list.unread = before;
      targets.forEach(function (number) {
        if (Array.isArray(list.quests)) {
          list.quests.forEach(function (item) { if (item && num(item.number, 0) === number) item.unread = true; });
        }
      });
      syncCore(list.quests, list.unread, list.latest);
      renderList();
      toast('标记已读失败：' + (text(error && error.message) || String(error)));
      return null;
    });
  }

  function markAllRead() {
    return Promise.resolve(P.confirm({
      title: '全部标为已读',
      text: '回执列表里的未读标记会全部清掉（不影响回执内容）。',
      ok: '全部已读'
    })).then(function (yes) {
      if (!yes) return null;
      (list.quests || []).forEach(function (item) { if (item) item.unread = false; });
      list.unread = 0;
      syncCore(list.quests, 0, list.latest);
      renderList();
      return Promise.resolve(P.api('/api/quests/read', { body: { all: true } })).then(function (data) {
        var value = data && Number(data.unread);
        if (Number.isFinite(value)) { list.unread = Math.max(0, value); syncCore(list.quests, list.unread, list.latest); }
        renderList();
        toast('已全部标为已读');
        return data;
      }).catch(function (error) {
        toast('标记失败：' + (text(error && error.message) || String(error)));
        loadList(false);
        return null;
      });
    });
  }

  /** 跳到最新：刷新列表 + 打开最新那条回执。 */
  function jumpLatest() {
    return loadList(false).then(function (data) {
      var latest = num(data && data.latest, list.latest);
      var newest = list.quests && list.quests.length ? num(list.quests[0].number, 0) : 0;
      var target = latest > 0 ? latest : newest;
      if (!target) { toast('还没有回执'); return null; }
      openDetail(target);
      return target;
    });
  }

  function listOverflow() {
    return Promise.resolve(P.sheet({
      title: '回执列表',
      items: [
        { text: '全部标为已读', onSelect: function () { markAllRead(); } },
        { text: '跳到最新（#' + (list.latest || 0) + '）', onSelect: function () { jumpLatest(); } },
        { text: '刷新列表', onSelect: function () { loadList(true); } }
      ]
    }));
  }

  function openDetail(number) {
    detail.number = num(number, 0);
    detail.payload = null;
    detail.error = '';
    detail.files = [];
    detail.extras = [];
    detail.owned = [];                          // 换条目：这条回执自己的图与"本次开始时刻"全部作废
    detail.ownedDirs = Object.create(null);
    detail.pullSince = 0;
    detail.stepNodes = [];      // 新条目：DOM 会被整屏重挂，增量记账跟着清
    detail.stepCount = 0;
    if (typeof P.go === 'function') P.go('quest-detail');
  }

  function mountList(root) {
    list.root = root;
    root.classList.add('q-screen');
    var ptr = el('div', 'q-ptr', '下拉刷新');
    root.appendChild(ptr);

    var body = el('div', 'q-body');
    list.body = body;
    root.appendChild(body);

    /* app bar 是外壳的（标题/刷新/更多都在那里）；这一屏只补一行摘要：未读徽标 + 条数 + 溢出菜单。
       「全部标为已读」「跳到最新」都在这个溢出菜单里（也同时挂给外壳的「更多操作」）。 */
    var bar = el('div', 'q-toprow');
    list.sumBadge = el('span', 'q-badge', '0');
    list.sumBadge.hidden = true;
    bar.appendChild(list.sumBadge);
    var sum = el('div', 'q-row-main');
    list.sumText = el('div', 'q-sum', '正在读取回执列表…');
    sum.appendChild(list.sumText);
    bar.appendChild(sum);
    bar.appendChild(iconButton('more', '更多操作', function () { listOverflow(); }));
    body.appendChild(bar);

    var rows = el('div', 'q-rows');
    body.appendChild(rows);
    list.rows = rows;

    renderList();
    installPullToRefresh(ptr, function () { return loadList(true); });
    /* 列表**实时**刷新：可见时每 3 秒拉一次（与出图屏同频），只增量改行（见 syncRows）。
       三层让路：不在这一屏 / 有浮层开着（查看器、sheet、对话框）/ 上一轮还没回来 —— 都直接跳过，
       绝不打扰用户看图或选东西。 */
    list.timer = P.pollWhileVisible(function () {
      if (list.loading) return;
      if (typeof P.current === 'function' && P.current() !== 'quest') return;
      if (typeof P.overlayBusy === 'function' && P.overlayBusy()) return;
      var running = (list.quests || []).some(function (item) { return item && (item.busy || (item.done === false && !item.expired)); });
      if (!running) { list.percent = 0; return loadList(false); }
      /* 有正在生成的：顺手把实时百分比拿回来（/api/progress 是只读，一次请求），只影响那一行的标签。 */
      return Promise.resolve(P.api('/api/progress', { body: {} })).then(function (live) {
        list.percent = num(live && live.percent, 0);
        return loadList(false);
      }).catch(function () { list.percent = 0; return loadList(false); });
    }, LIST_POLL_MS);
  }

  function refreshList() {
    if (!list.root) return Promise.resolve(null);
    /* 先问一次 /api/status（未读数的权威来源），再刷列表；列表返回里的 unread 会再覆盖一次。 */
    var sync = typeof P.refreshStatus === 'function' ? P.refreshStatus() : Promise.resolve(null);
    return Promise.resolve(sync).catch(function () { return null; }).then(function () {
      return loadList(false);
    });
  }

  /** 外壳「更多操作」里的这一屏动作（与屏内溢出菜单同一套）。 */
  function listMenu() {
    return [
      { text: '全部标为已读', onSelect: function () { markAllRead(); } },
      { text: '跳到最新（#' + (list.latest || 0) + '）', onSelect: function () { jumpLatest(); } }
    ];
  }

  P.register('quest', { title: '回执', mount: mountList, refresh: refreshList, menu: listMenu });

  /* ───────────────────────── 4. 详情屏 id = 'quest-detail' ───────────────────────── */
  /* `stepNodes` / `stepCount` 是增量渲染的记账：已画好的步骤行节点与它们的数量。
     换条目（openDetail）或整屏重挂（mountDetail）时必须清掉 —— 那时 DOM 已经不在，留着会串页。 */
  var detail = { root: null, body: null, head: null, headNodes: null, number: 0, payload: null, error: '',
    loading: false, files: [], extras: [], timer: null, progress: null, stepNodes: [], stepCount: 0,
    owned: [], ownedDirs: Object.create(null), pullSince: 0, window: null };

  /**
   * 这条回执**自己**的图片（只认 `/api/quest` 的 `messages`/`images` —— 服务端权威，不含"提前拉进来的" extras）。
   * 用来得出"这条回执的生成目录"，见 {@link belongsToRun}。
   */
  function ownedFiles(payload) {
    var files = [];
    function push(value) {
      var path = normalizePath(value);
      if (path && files.indexOf(path) < 0) files.push(path);
    }
    var groups = payload && Array.isArray(payload.messages) && payload.messages.length ? payload.messages : null;
    if (groups) {
      groups.forEach(function (pieces) {
        (pieces || []).forEach(function (piece) { if (piece && piece.type === 'image' && piece.file) push(piece.file); });
      });
    } else if (payload && Array.isArray(payload.images)) {
      payload.images.forEach(function (image) { push(image); });
    }
    return files;
  }

  /** `data/generated/task-…/<20261005-034042>-…/image-01.png` → 它所在的那一轮生成目录（到最后一个 `/` 为止）。 */
  function imageDirOf(path) {
    var value = String(path || '');
    var at = value.lastIndexOf('/');
    return at > 0 ? value.slice(0, at + 1) : '';
  }

  /** 一张图的时间：优先用服务端给的 `modified`（ISO），缺失/非法才回退路径里的 `20261005-034042`（本地时间）。 */
  function imageTime(item) {
    if (item && typeof item === 'object' && item.modified) {
      var parsed = Date.parse(item.modified);
      if (isFinite(parsed)) return parsed;
    }
    var path = typeof item === 'string' ? item : (item && item.path) || '';
    var match = /(\d{8})-(\d{6})/.exec(String(path));
    if (!match) return 0;
    return new Date(+match[1].slice(0, 4), +match[1].slice(4, 6) - 1, +match[1].slice(6, 8),
      +match[2].slice(0, 2), +match[2].slice(2, 4), +match[2].slice(4, 6)).getTime();
  }

  /**
   * 「本次生成开始时刻」。优先用服务端给的 `ageMillis` 反推 —— 同一条回执每轮算出来**都是同一个值**，
   * 不会跟着轮询往后跑；拿不到才退回"第一次看到它 running 的那一刻"（只记一次）。
   */
  function runStartMillis(payload) {
    var now = Date.now();
    var age = num(payload && payload.ageMillis, 0);
    if (age > 0 && age < 86400000) return Math.min(detail.pullSince || now, now - age);
    return detail.pullSince || now;
  }

  /* 生成目录 → 回执号。会话内每读到一条回执就把它的图目录登记下来：
     别的回执（尤其**并发/交错**的那条）在挑"提前到达的图"时，先看这个目录是不是已经名花有主。 */
  var dirOwner = Object.create(null);
  var RUN_SLACK = 5000;        // 目录时间戳只精确到秒 + 回执开始时刻是反推的，给 5 秒余量

  /** 生成目录名里的时间戳（`…/20261005-034042-hash/image-01.png`，本地时间）→ 毫秒；没有给 0。 */
  function dirStampMillis(path) {
    var dir = imageDirOf(path);
    var match = /(\d{8})-(\d{6})/.exec(dir);
    if (!match) return 0;
    return new Date(+match[1].slice(0, 4), +match[1].slice(4, 6) - 1, +match[1].slice(6, 8),
      +match[2].slice(0, 2), +match[2].slice(2, 4), +match[2].slice(4, 6)).getTime();
  }

  /**
   * 这张图**是不是这条回执"这一次生成"的产物**（用户报的「多个生成任务时要区分图片归属，不能一股脑
   * 放进正在生成的那条回执」）。
   *
   * <p><b>服务端没有任务身份字段</b>（实测：`/api/quests` 行只有 number/startedAt/command/…；`/api/quest`
   * 只有 id/quest/command/texts/images/messages/busy/done/closed/ageMillis；`/api/tasks` 只有
   * slot/number/done/total/images/failed/running/…；`/api/progress` 只有 percent/step/text/queue —— 四处
   * 都**没有** task id / 目录 / 回执号的对应关系）。所以只能靠**图片路径自带的运行目录** + **回执自己的
   * 开始-结束区间**这两条硬证据：
   *   ① 目录 == 这条回执正文里已有图片的目录（`ownedDirs`）→ 就是它的；
   *   ② 这个目录已经登记在**别的**回执名下 → 一律不要（并发/交错时最关键的一条）；
   *   ③ 目录时间戳（拿不到才用 `modified`）落在本回执的窗口 `[本次开始 - 5s, 下一条回执开始 + 5s)` 内 →
   *      是它这一次新开目录里的图（`.gen 5` 分多批就是这种）。
   * 三条都不成立就不并进来 —— 宁可少并：下一轮 `renderDetail()` 会从回执正文（服务端权威）里补回来。
   */
  function belongsToRun(item) {
    var path = typeof item === 'string' ? item : (item && item.path);
    path = normalizePath(path);
    if (!path) return false;
    var dir = imageDirOf(path);
    if (dir && detail.ownedDirs[dir]) return true;
    var owner = dir ? dirOwner[dir] : 0;
    if (owner && owner !== detail.number) return false;
    var when = dirStampMillis(path) || imageTime(item);
    if (!when) return false;
    var window_ = detail.window;
    if (!window_) return when >= detail.pullSince;
    return when >= window_.from && when < window_.until;
  }

  /**
   * 算出这条回执的"生成时间窗"，顺便把它的图目录登记进 {@link dirOwner}。
   * 上界 = **下一条回执的开始时刻**（从 `/api/quests` 里取比它大的最小 startedAt）：这条之后开始的
   * 任务，图一定不属于它 —— 这就是"两个任务交错"时把两边分开的那把尺子。
   */
  function loadRunWindow(payload) {
    var number = detail.number;
    detail.window = { from: runStartMillis(payload) - RUN_SLACK, until: Infinity };
    detail.owned.forEach(function (file) {
      var dir = imageDirOf(file);
      if (dir) dirOwner[dir] = number;
    });
    if (!(payload && (payload.busy || payload.done === false))) return Promise.resolve(detail.window);
    return Promise.resolve(P.api('/api/quests', { body: { limit: 8 } })).then(function (data) {
      var rows = (data && data.quests) || [];
      var next = 0;
      rows.forEach(function (row) {
        var rowNumber = num(row.number, 0);
        if (!(rowNumber > number)) return;
        var age = num(row.ageMillis, 0);
        var start = age > 0 ? Date.now() - age : Date.parse(row.startedAt);
        /* 只认"确实晚于本次开始"的那些（号更大却更早开始的数据不一致 / 时钟偏移就跳过），
           在它们里取最早的 → 那就是把两个交错任务分开的那把尺子。 */
        if (isFinite(start) && start > detail.window.from && (!next || start < next)) next = start;
      });
      /* 上界**不能往后放宽**：目录名的时间戳只精确到秒，下一条回执开始前的最后一秒可能落在它的号上，
         所以往**前**留 1 秒容差；再往后就会把"下一条任务刚出的图"吃进来（用户报的正是这个）。 */
      if (next) detail.window.until = Math.max(detail.window.from + 1, next - 1000);
      return detail.window;
    }).catch(function () { return detail.window; });
  }

  function piecesOf(payload) {
    var groups = payload && Array.isArray(payload.messages) && payload.messages.length ? payload.messages : null;
    if (groups) return groups;
    var texts = payload && Array.isArray(payload.texts) ? payload.texts : [];
    return texts.map(function (value) { return [{ type: 'text', text: value }]; });
  }
  function filesOf(payload, groups) {
    var files = [];
    function push(value) {
      var path = normalizePath(value);      // 对象（{file}/{path}）与字符串都收，统一成相对路径
      if (path && files.indexOf(path) < 0) files.push(path);
    }
    if (groups) {
      groups.forEach(function (pieces) {
        (pieces || []).forEach(function (piece) { if (piece && piece.type === 'image' && piece.file) push(piece.file); });
      });
    } else if (payload && Array.isArray(payload.images)) {
      payload.images.forEach(function (image) { push(image); });   // 元素本身就是 {file:"…"}
    }
    detail.extras.forEach(push);
    return files;
  }

  /**
   * 详情头部：**结构建一次，之后只改文本**。
   *
   * <p>原来每轮 `renderDetail()` 都 `clear(detail.head)` 重建整张卡片 —— 头部一重建，
   * 挂在它下面的进度条也一起没了，进度条于是每 3 秒从 0 重新长一遍；标签行重建还会让文字闪。
   * 现在节点认一次，`#号 / 指令 / 元信息` 只改 `textContent`，标签行最多重建几个 `<span>`（不涉及图片解码）。
   */
  function renderDetailHead() {
    if (!detail.head) return;
    var payload = detail.payload || {};
    if (!detail.headNodes) {
      clear(detail.head);
      var card = el('div', 'q-card q-head');
      var no = el('div', 'q-no', '');
      var cmd = el('div', 'q-cmd', '');
      var meta = el('div', 'q-head-meta', '');
      var tags = el('div', 'q-tags');
      card.appendChild(no);
      card.appendChild(cmd);
      card.appendChild(meta);
      card.appendChild(tags);
      detail.head.appendChild(card);
      detail.headNodes = { card: card, no: no, cmd: cmd, meta: meta, tags: tags };
    }
    var nodes = detail.headNodes;
    var number = num(payload.quest, detail.number) || detail.number;
    var command = text(payload.command);
    var failed = !!payload.error || !!detail.error;
    var running = !failed && !(payload.done && !payload.busy);
    nodes.no.textContent = '#' + number;
    nodes.cmd.textContent = command || '（没有指令文本）';
    var whenText = when(payload.startedAt, payload.ageMillis);
    nodes.meta.textContent = (whenText ? whenText + ' · ' : '') + (failed ? '失败' : (running ? '执行中…' : '已完成'))
      + (payload.closed ? ' · 回执已关闭' : '')
      + (payload.latest ? ' · 最新一条 #' + num(payload.latest, 0) : '');
    clear(nodes.tags);
    if (running) nodes.tags.appendChild(el('span', 'q-tag q-live q-run', '执行中'));
    if (failed) nodes.tags.appendChild(el('span', 'q-tag q-warn', '失败'));
    if (payload.fromDisk) nodes.tags.appendChild(el('span', 'q-tag', '从磁盘读回'));
    var texts = Array.isArray(payload.texts) ? payload.texts.length : 0;
    var images = detail.files.length;
    if (texts) nodes.tags.appendChild(el('span', 'q-tag', texts + ' 段文字'));
    if (images) nodes.tags.appendChild(el('span', 'q-tag', images + ' 张图'));
  }

  function renderProgress() {
    if (!detail.head) return;
    var old = detail.head.querySelector('.q-progress');
    if (old && detail.progress) { detail.progress.card = old; detail.progress.fill = old.querySelector('.q-prog-fill'); detail.progress.text = old.querySelector('.q-progress-text'); return; }
    if (old) old.remove();
    if (detail.progress) { detail.progress.card = null; detail.progress.fill = null; detail.progress.text = null; }
    var payload = detail.payload || {};
    if (!detail.progress || !detail.progress.visible) return;
    var box = el('div', 'q-progress');
    var track = el('div', 'q-prog-track');
    var fill = el('i', 'q-prog-fill');
    track.appendChild(fill);
    box.appendChild(track);
    var line = el('div', 'q-progress-text', '');
    box.appendChild(line);
    detail.head.appendChild(box);
    detail.progress.card = box;
    detail.progress.fill = fill;
    detail.progress.text = line;
    tickProgress();
  }
  function tickProgress() {
    if (!detail.progress || !detail.progress.fill) return;
    if (!detail.progress.visible) {
      if (detail.progress.card && detail.progress.card.parentNode) detail.progress.card.remove();
      // 节点一起放掉：留着的话下次 renderProgress() 会以为"进度条还在"而不再建，进度条就再也不出现了
      detail.progress.card = null;
      detail.progress.fill = null;
      detail.progress.text = null;
      return;
    }
    var percent = Math.max(0, Math.min(100, num(detail.progress.percent, 0)));
    detail.progress.fill.style.width = percent + '%';
    if (detail.progress.text) {
      detail.progress.text.textContent = text(detail.progress.label) || (percent + '%');
    }
  }
  function setProgress(live) {
    var running = !!(live && live.running);
    var queue = num(live && live.queue, 0);
    detail.progress = detail.progress || { visible: false, percent: 0, label: '', card: null, fill: null, text: null };
    detail.progress.visible = running || queue > 0;
    detail.progress.percent = num(live && live.percent, 0);
    var parts = [];
    if (running) parts.push(num(live && live.percent, 0) + '%');
    if (live && num(live.steps, 0) > 0) parts.push('第 ' + num(live.step, 0) + '/' + num(live.steps, 0) + ' 步');
    if (live && num(live.etaSeconds, 0) > 0) parts.push('约 ' + num(live.etaSeconds, 0) + ' 秒');
    if (queue > 0) parts.push('队列 ' + queue);
    if (live && live.text) parts.push(text(live.text));
    detail.progress.label = parts.join(' · ');
    tickProgress();
  }
  /**
   * 生成期间提前到达的新图（把这条回执对应的最新生成图并进图集，按路径去重）。
   *
   * <p><b>只并"确实属于这一次生成"的图</b>：`/api/images` 给的是**全站**最近几张，老写法只按"这条回执里
   * 还没有它"去重，于是别的任务/别的回执的图会被并进来（用户报的"回执界面生成图片时会加入不属于该任务的
   * 先前图片"）。现在每张都过一遍 {@link belongsToRun}：同一轮生成目录、或时间晚于本次开始 —— 两者都不
   * 满足就丢掉，等下一轮 `renderDetail()` 从回执正文（服务端权威）里补。
   */
  function pullLiveImages() {
    return Promise.resolve(P.api('/api/images', { body: { limit: 6 } })).then(function (data) {
      var images = (data && Array.isArray(data.images)) ? data.images : [];
      var before = detail.extras.length;
      images.forEach(function (image) {
        var path = normalizePath(image);            // {path:"data/generated/…"} 或裸字符串都收
        if (!path || path.indexOf('data/generated/webui/') === 0) return;
        if (detail.files.indexOf(path) >= 0 || detail.extras.indexOf(path) >= 0) return;
        if (!belongsToRun(image)) return;           // 不是这一次生成的 → 一张都不并
        detail.extras.push(path);
      });
      if (detail.extras.length !== before) renderDetail();
      return images.length;
    }).catch(function () { return 0; });
  }

  /**
   * 回执详情的图集：**增量**——已画过的格子（连同已加载好的 `<img>`）原样不动，只 append 新出现的图。
   *
   * <p>为什么必须增量：进度轮询每 3 秒走一次 `renderDetail()`，原来整屏 `clear(detail.body)` +
   * 重新 append，`<img>` 被反复销毁重建 —— 浏览器要重新解码、`loading=lazy` 的图还会被重新判定成
   * 屏外，这就是用户说的"每次加载都有延迟"。格子按 `data-path` 认，顺序变了也不重画。
   */
  function gallery(tiles) {
    var grid = el('div', 'q-grid');
    syncGallery(grid, tiles);
    return grid;
  }

  /** 把 `tiles` 同步进已有的 `.q-grid`：只补新格子、只摘掉不再需要的格子（已有的绝不动）。 */
  function syncGallery(grid, tiles) {
    var keep = Object.create(null);
    tiles.forEach(function (file) { keep[file] = true; });
    // 1) 先摘掉"这次不该有"的格子（**只摘这一格**，别的格子连同已解码的 <img> 原样留着）。
    //    回执在跑的时候图集是**一轮一轮长出来**的，也会因为重读而变短 —— 不加这条就会留孤儿格子。
    var painted = grid.querySelectorAll('.q-tile');
    for (var i = 0; i < painted.length; i++) {
      if (!keep[painted[i].getAttribute('data-path')]) grid.removeChild(painted[i]);
    }
    // 2) 再补缺（顺序按 tiles；已存在的格子只做必要的移动，insertBefore 移动节点不会重新解码图片）
    var have = Object.create(null);
    var now = grid.querySelectorAll('.q-tile');
    for (var j = 0; j < now.length; j++) have[now[j].getAttribute('data-path')] = now[j];
    var previous = null;
    tiles.forEach(function (file, index) {
      var tile = have[file];
      if (!tile) { tile = tileFor(file, index); have[file] = tile; }
      if (previous ? previous.nextSibling !== tile : grid.firstChild !== tile) {
        grid.insertBefore(tile, previous ? previous.nextSibling : grid.firstChild);
      }
      previous = tile;
    });
    return grid;
  }

  /**
   * 一个图集格子：按图片自身宽高比撑开 + 缩略图 + 失败占位（失败只换这一格）。
   *
   * <p>**会话级缓存**（见 app.js 的 `P.imageCache`）：同一路径在本会话里只请求一次。
   * 已经加载过的图，重画/换屏时连节点都还是同一个（搬过去不请求、不重新解码），
   * 比例直接取缓存里的真实值 —— 所以**永不回到"加载中"**；失败过的不再重试。
   */
  function tileFor(file, index) {
    var tile = el('div', 'q-tile');
    tile.setAttribute('data-path', file);
    var cache = P.imageCache;
    var src = imageSrc(file, true);
    var cachedRatio = cache && typeof cache.ratio === 'function' ? cache.ratio(file) : '';
    if (cachedRatio) tile.style.aspectRatio = cachedRatio;     // 已知真实比例：连占位都不用
    if (cache && typeof cache.failed === 'function' && cache.failed(file)) {
      markTileFail(tile, file, true);                          // 失败终态：不再造 <img>、不再重试
      return tile;
    }
    var img = cache && typeof cache.node === 'function' ? cache.node(file, src) : document.createElement('img');
    img.alt = '任务 #' + (detail.number || 0) + ' ' + shortName(file);
    var reuse = !!(cachedRatio && img.getAttribute('src') === src);
    if (reuse) {
      tile.appendChild(img);                                   // 同一个节点搬过来：0 请求、0 解码、不闪
    } else {
      img.loading = index < 4 ? 'eager' : 'lazy';              // 首屏可见的前几张先加载，别让用户看到空白格
      img.decoding = 'async';
      img.addEventListener('error', function () {
        if (cache && typeof cache.mark === 'function') cache.mark(file, false, img);
        markTileFail(tile, file);
      }, { once: true });
      function judge() {
        // 图片按自身宽高比显示：把格子的 aspect-ratio 换成真实比例（格子默认先按 4:3 占位）。
        var w = img.naturalWidth, h = img.naturalHeight;
        if (!(w > 0 && h > 0)) return;
        if (cache && typeof cache.mark === 'function') cache.mark(file, true, img);
        if (typeof P.applyNaturalRatio === 'function') { P.applyNaturalRatio(img); return; }
        if (tile.style.aspectRatio !== w + ' / ' + h) tile.style.aspectRatio = w + ' / ' + h;   // 兜底（app.js 没这套 API 时）
      }
      img.addEventListener('load', judge, { once: true });
      img.src = src;      // 格子只是小尺寸展示 → 缩略图
      if (img.complete && img.naturalWidth > 0) judge();
      tile.appendChild(img);
    }
    tile.addEventListener('click', function () {
      if (tile.classList.contains('q-fail')) return;   // 失败态点击 = 复制路径（见 markTileFail）
      if (typeof P.openViewer !== 'function') return;
      // 索引**在点击这一刻**按当前 detail.files 算（异步轮询会让图集增长，创建时记下的下标会过期）
      var at = detail.files.indexOf(file);
      try { P.openViewer(viewerItems(), at < 0 ? 0 : at); } catch (error) { toast('查看器打不开：' + (text(error && error.message) || String(error))); }
    });
    /* 长按：优先交给原生桥（Android 外壳的保存/分享菜单），没有桥就退到底部 sheet。 */
    var holdTimer = null;
    var startAt = null;
    var cancelHold = function () { if (holdTimer) { clearTimeout(holdTimer); holdTimer = null; } };
    tile.addEventListener('touchstart', function (event) {
      if (event.touches.length !== 1) { cancelHold(); return; }
      if (tile.classList.contains('q-fail')) return;   // 失败态由点击处理复制路径
      startAt = { x: event.touches[0].clientX, y: event.touches[0].clientY };
      cancelHold();
      holdTimer = setTimeout(function () {
        holdTimer = null;
        showImageMenu(imageFullSrc(file), img.alt);     // 桥拿**原图**
      }, 550);
    }, { passive: true });
    tile.addEventListener('touchmove', function (event) {
      if (!holdTimer || event.touches.length !== 1) return;
      if (Math.abs(event.touches[0].clientX - startAt.x) > 10 || Math.abs(event.touches[0].clientY - startAt.y) > 10) cancelHold();
    }, { passive: true });
    tile.addEventListener('touchend', cancelHold, { passive: true });
    tile.addEventListener('touchcancel', cancelHold, { passive: true });
    tile.addEventListener('contextmenu', function (event) {
      if (event && event.preventDefault) event.preventDefault();
      if (tile.classList.contains('q-fail')) return;
      showImageMenu(imageFullSrc(file), img.alt);
    });
    return tile;
  }

  /** 查看器的一份条目：**原图地址**（不带 w），能和 detail.files 一一对上。 */
  function viewerItems() {
    return detail.files.map(function (path, at) {
      return { src: path, caption: '任务 #' + (detail.number || 0) + ' 第 ' + (at + 1) + ' 张：' + shortName(path) };
    });
  }

  /** 图取不到：把这一格换成「图取不到」占位（带上文件名，点一下复制完整路径）。 */
  function markTileFail(tile, file, noRetry) {
    if (!tile || tile.getAttribute('data-img-failed') === '1') return;
    tile.setAttribute('data-img-failed', '1');
    tile.classList.add('q-fail');
    if (noRetry) tile.setAttribute('data-img-noretry', '1');
    clear(tile);
    var box = el('div', '', '图取不到');
    box.title = file;
    box.addEventListener('click', function (event) {
      if (event && event.stopPropagation) event.stopPropagation();
      copyText(file);
    });
    tile.appendChild(box);
    var small = el('div', '', shortName(file));
    small.style.fontSize = '10px';
    small.style.opacity = '.75';
    small.style.overflowWrap = 'anywhere';
    tile.appendChild(small);
    rememberedFails[file] = true;      // 按路径去重：同一张图多处失败只记一次
  }
  var rememberedFails = Object.create(null);
  /** 长按图片：有 PixikoNative 桥就用桥，没有就用底部 sheet 兜底（两端都能用）。 */
  function showImageMenu(src, label) {
    var bridge = window.PixikoNative;
    if (bridge && typeof bridge.showImageMenu === 'function') {
      try { bridge.showImageMenu(String(src), String(label || '')); return; } catch (error) { /* 桥炸了就走 sheet */ }
    }
    var items = [
      { text: '查看大图', onSelect: function () { openViewerFor(src, label); } },
      { text: '复制图片地址', onSelect: function () { copyText(src); } }
    ];
    if (bridge && typeof bridge.saveImage === 'function') items.push({ text: '保存到相册', onSelect: function () { try { bridge.saveImage(String(src), String(label || '')); } catch (error) { toast('保存失败'); } } });
    if (bridge && typeof bridge.shareImage === 'function') items.push({ text: '分享', onSelect: function () { try { bridge.shareImage(String(src), String(label || '')); } catch (error) { toast('分享失败'); } } });
    try { P.sheet({ title: label || '图片', items: items }); } catch (error) { toast(String(label || '图片')); }
  }
  function openViewerFor(src, label) {
    if (typeof P.openViewer !== 'function') return;
    var at = 0;
    // 用**原图地址**跟传进来的 src 比对（`src` 是 imageFullSrc 给的，不带 w）
    var items = detail.files.map(function (path, index) {
      if (imageFullSrc(path) === src) at = index;
      return { src: path, caption: '任务 #' + (detail.number || 0) + ' 第 ' + (index + 1) + ' 张：' + shortName(path) };
    });
    try { P.openViewer(items, at); } catch (error) { /* 查看器不可用就什么也不做 */ }
  }
  function copyText(value) {
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(String(value)).then(function () { toast('图片地址已复制'); }, function () { toast('复制失败'); });
        return;
      }
    } catch (error) { /* 落下面 */ }
    var input = el('input');
    input.value = String(value);
    document.body.appendChild(input);
    input.select();
    try { document.execCommand('copy'); toast('图片地址已复制'); } catch (error) { toast('复制失败'); }
    document.body.removeChild(input);
  }

  /**
   * 整屏同步（**增量**）。
   *
   * <p>顺序固定为：`head → 状态/错误 → 步骤 → 图集 → 还在跑`，每次进来只**补缺**：
   * 该有的节点已经在位就只改文本，绝不 `clear(detail.body)` —— 图集格子里的 `<img>` 一旦画好就留住，
   * 进度轮询（3 秒一次）不会再让它们重新解码。
   */
  function renderDetail() {
    if (!detail.body) return;
    var payload = detail.payload || {};
    ensureEmpty();

    var skeletonNode = detail.body.querySelector('[data-q-skeleton]');
    if (detail.loading && !detail.payload) {
      if (skeletonNode) skeletonNode.remove();
      if (detail.head.parentNode !== detail.body) detail.body.insertBefore(detail.head, detail.body.firstChild);
      var sk = skeleton(4);
      sk.setAttribute('data-q-skeleton', '1');
      detail.body.appendChild(sk);
      renderDetailHead();
      return;
    }
    if (skeletonNode) skeletonNode.remove();

    if (detail.head.parentNode !== detail.body) detail.body.insertBefore(detail.head, detail.body.firstChild);

    var errNode = detail.body.querySelector('[data-q-error]');
    if (detail.error) {
      if (!errNode) {
        errNode = stateBox('q-err', detail.error, function () { loadDetail(detail.number, false); });
        errNode.setAttribute('data-q-error', '1');
        detail.body.appendChild(errNode);
      } else {
        clear(errNode);
        errNode.appendChild(document.createTextNode(detail.error));
        var retry = el('button', 'q-retry', '重试');
        retry.type = 'button';
        retry.addEventListener('click', function () { loadDetail(detail.number, false); });
        errNode.appendChild(document.createElement('br'));
        errNode.appendChild(retry);
      }
    } else if (errNode) {
      errNode.remove();
    }
    if (detail.payload) renderDetailBody(detail.payload);
    renderDetailHead();
  }

  /** 空态占位：只在"真的没有任何输出"时存在，其它时候必定摘掉。 */
  function ensureEmpty() {
    var node = detail.body.querySelector('[data-q-empty]');
    var payload = detail.payload || {};
    var groups = piecesOf(payload);
    var files = filesOf(payload, Array.isArray(payload.messages) && payload.messages.length ? payload.messages : null);
    detail.files = files;
    var need = !groups.length && !files.length && !detail.error;
    if (!need) {
      if (node) node.remove();
      return;
    }
    var wanted = payload.error ? text(payload.error) : '这条回执还没有任何输出。';
    if (!node) { node = el('div', 'q-state', wanted); node.setAttribute('data-q-empty', '1'); detail.body.appendChild(node); }
    else if (node.textContent !== wanted) node.textContent = wanted;
  }

  /**
   * 正文：步骤行与图集都按**已有节点**复用。
   *
   * <p>步骤行：`steps[i]` ↔ 第 i 个 `.q-step`，只改标题与正文文本，绝不重建。
   * <p>图集：整屏只有一个 `.q-grid`，格子按路径增量补（见 `syncGallery`）；
   * 位置用 `insertBefore` 放到第 `stepWithoutGrid` 个步骤之前 —— 原地移动已有节点不会重新解码图片。
   */
  function renderDetailBody(payload) {
    var groups = piecesOf(payload);
    var isMessages = Array.isArray(payload.messages) && payload.messages.length > 0;
    var files = filesOf(payload, isMessages ? payload.messages : null);
    detail.files = files;
    var steps = groups || [];
    var tiles = files.slice();
    syncSteps(payload, isMessages, steps, tiles.length > 0);
    syncGrid(payload, tiles);
    pruneGridTiles(tiles);      // 摘完步骤行再收一次：图集"变短"时不留孤儿格子
    syncRunningLine(payload);
  }

  /**
   * 步骤行增量化：只补新出现的行，已在位的只改文本。
   *
   * <p><b>「DOM 里已有的 `.q-step`」是唯一账本</b>，不是 `detail.stepNodes.length`。
   * `renderDetail()` 在一次打开里会跑两遍（路由进入 + `/api/quest` 回来各一次），用数组长度当
   * 起始下标就会**第二次把同样 4 条消息又追加 4 张卡**：打开 #361 看到 8 张卡、打开 #360 看到
   * 14 张、而且上一条回执留下的孤儿卡也永远清不掉 —— 用户报的「手机端回执条目正文混乱」正是这个。
   * 现在每次进来先把还挂在 `detail.body` 上的 `.q-step` 按 DOM 顺序收回本数组、**按位复用**，
   * 收尾再把超出 `used` 的多余卡片全部摘掉，于是「卡片数 = 消息条数」恒成立。
   */
  function syncSteps(payload, isMessages, steps, hasTiles) {
    var live = detail.body.querySelectorAll('.q-step');
    detail.stepNodes.length = 0;
    for (var j = 0; j < live.length; j++) detail.stepNodes[j] = live[j];
    var used = 0;              // 真正用到的卡片数（纯图片片段不占卡片）
    var placed = false;
    var lastNew = null;        // 本轮新插进去的最后一张卡（新卡都排它后面，保持正序）
    for (var index = 0; index < steps.length; index++) {
      var group = steps[index] || [];
      var stepText = group.filter(function (piece) { return piece && piece.type !== 'image' && piece.text; })
        .map(function (piece) { return text(piece.text); }).join('\n');
      var hasPic = !!(isMessages && group.some(function (piece) { return piece && piece.type === 'image' && piece.file; }));
      var here = hasPic && !placed && hasTiles;
      var skip = !stepText && hasPic && !here;   // 纯图片片段：内容已经由图集负责，不再单开一行
      if (skip) continue;                        // 这一条不占卡片：收尾按 used 清掉多余节点
      var node = detail.stepNodes[used];
      if (!node) {
        node = el('div', 'q-step');
        node.appendChild(el('div', 'q-step-head', ''));
        node.appendChild(el('div', 'q-text', ''));
      }
      detail.stepNodes[used] = node;
      /* 新卡片插在**上一张新卡片之后**：都往 `bodyAnchor()`（第一张卡）前面塞的话，
         同一轮里建出来的多张卡会**倒序**（第一次渲染出来是"本次领取完成…"在最上面，
         第二遍才被摆正）。没有前一张时（本回执的第一张卡）才用锚点。 */
      if (node.parentNode !== detail.body) {
        if (lastNew && lastNew.parentNode === detail.body) detail.body.insertBefore(node, lastNew.nextSibling);
        else detail.body.insertBefore(node, bodyAnchor());
      }
      lastNew = node;
      if (here) placed = true;
      node.classList.toggle('q-err', /(^|\n)[^\n]{0,16}(失败|错误|不正确|无效|超时|拒绝|找不到)[:：]/.test(stepText));
      var head = node.firstChild;
      var body = node.lastChild;
      var title = stepTitle(stepText, index, hasPic);
      if (head && head.textContent !== title) head.textContent = title;
      if (body && body.textContent !== stepText) body.textContent = stepText;
      if (body) body.style.display = stepText ? '' : 'none';
      used++;
    }
    /* 收尾：只保留用到的前 `used` 张卡，其余（本回执多余的 / 上一条回执留下的孤儿）一并摘掉。
       倒着遍历是因为 `live` 是活的 HTMLCollection。 */
    for (var k = live.length - 1; k >= used; k--) {
      var stale = live[k];
      if (stale && stale.parentNode) stale.remove();
    }
    detail.stepNodes.length = used;
    detail.stepCount = used;
  }

  /**
   * 图集：把步骤行前面的那个 `.q-grid` 与 `tiles` 对齐（格子按路径增量补）。
   * 已经在 DOM 里的格子**原样不动**（`insertBefore` 移动节点不会触发重新解码）。
   *
   * <p>这里对"整屏所有 `.q-grid`"做一次收口：只保留第一个（`renderDetail` 整屏重挂、或条目切换后
   * 残留的旧网格会被摘掉），并在摘掉步骤行之后**再收一次**孤儿格子 —— 否则图集"变短"时
   * 多余的格子会挂在一个已经不在文档里的网格上，DOM 里看不到但节点还在。
   */
  function syncGrid(payload, tiles) {
    var grids = detail.body.querySelectorAll('.q-grid');
    var grid = null;
    for (var g = 0; g < grids.length; g++) {
      if (grid) { if (grids[g].parentNode) grids[g].parentNode.removeChild(grids[g]); }
      else grid = grids[g];
    }
    if (!tiles.length) {
      if (grid && grid.parentNode) grid.parentNode.removeChild(grid);
      return;
    }
    if (!grid) {
      grid = el('div', 'q-grid');
      detail.body.insertBefore(grid, bodyAnchor());
    }
    syncGallery(grid, tiles);
    var anchor = bodyAnchor();
    if (grid.nextSibling !== anchor && grid.parentNode === detail.body) detail.body.insertBefore(grid, anchor);
  }

  /** 收尾：把图集里"这次不该有"的格子摘掉（HTMLCollection 是活的，倒着删）。 */
  function pruneGridTiles(tiles) {
    var grid = detail.body.querySelector('.q-grid');
    if (!grid) return;
    var keep = Object.create(null);
    tiles.forEach(function (file) { keep[file] = true; });
    var painted = grid.querySelectorAll('.q-tile');
    for (var i = painted.length - 1; i >= 0; i--) {
      if (!keep[painted[i].getAttribute('data-path')]) grid.removeChild(painted[i]);
    }
  }

  /** 图集该插到哪儿：第一个".q-step"（它上面是 head / 错误行）。 */
  function bodyAnchor() { return detail.body.querySelector('.q-step'); }

  /** 「还在跑，正在实时刷新…」一行：按需增删，删了就不再重建。 */
  function syncRunningLine(payload) {
    var node = detail.body.querySelector('[data-q-running]');
    var running = !payload.error && !(payload.done && !payload.busy);
    if (!running) { if (node) node.remove(); return; }
    if (!node) {
      node = el('div', 'q-state', '（还在跑，正在实时刷新…）');
      node.setAttribute('data-q-running', '1');
      detail.body.appendChild(node);
    }
  }

  function loadDetail(number, quiet) {
    var target = num(number, 0);
    if (!target) { detail.number = 0; }
    detail.loading = true;
    /* 同一条回执的"再看一次"（刷新/下拉/进入已看过的详情）**不清空、不出骨架**：
       已经画好的图集与步骤行留在屏幕上原地更新 —— 否则用户看到的就是"图明明已经加载好了，
       一刷新又回到加载中/骨架"（这正是本轮要修的那条）。只有换到另一条回执才整屏重来。 */
    var sameEntry = !!detail.payload && num(detail.quest, 0) === target;
    if (!quiet && !sameEntry) { detail.payload = null; detail.error = ''; renderDetail(); }
    else if (detail.body && !detail.body.childNodes.length) renderDetail();
    var pending = P.api('/api/quest', { body: { id: target } });
    return Promise.resolve(pending).then(function (data) {
      detail.payload = data || {};
      detail.loading = false;
      detail.error = '';
      var got = num(detail.payload.quest, target);
      if (got > 0) detail.number = got;
      /* 后端在磁盘上也没有正文时，回的是 {error, summary, number} 这种**索引摘要**：
         这种响应里一条正文都没有，按"正文读不到"处理，而不是当成一条空回执。 */
      var hasBody = (Array.isArray(detail.payload.messages) && detail.payload.messages.length > 0)
        || (Array.isArray(detail.payload.texts) && detail.payload.texts.length > 0)
        || (Array.isArray(detail.payload.images) && detail.payload.images.length > 0);
      if (detail.payload.error && !hasBody) detail.error = text(detail.payload.error);
      if (got > 0 && num(detail.quest, 0) !== got) {   // 换到另一条回执了：这一次的记账全部作废
        detail.extras = [];
        detail.owned = [];
        detail.ownedDirs = Object.create(null);
        detail.pullSince = 0;
      }
      detail.quest = got;
      detail.owned = ownedFiles(detail.payload);
      detail.ownedDirs = Object.create(null);
      detail.owned.forEach(function (file) {
        var dir = imageDirOf(file);
        if (dir) detail.ownedDirs[dir] = true;
      });
      renderDetail();
      var running = !detail.payload.error && !(detail.payload.done && !detail.payload.busy);
      /* 先把"生成时间窗 + 目录归属"算好，再决定要不要并提前到达的图 —— 并发/交错的任务就靠它分开。 */
      return Promise.resolve(loadRunWindow(detail.payload)).then(function () {
        return Promise.resolve(P.api('/api/progress', { body: {} })).then(function (live) {
          setProgress(live);
          renderProgress();
          if (running && live && (live.running || num(live.queue, 0) > 0)) {
            detail.pullSince = runStartMillis(detail.payload);     // 本次生成开始时刻（服务端 ageMillis 反推）
            pullLiveImages();
          }
          return detail.payload;
        }).catch(function () { setProgress(null); renderProgress(); return detail.payload; });
      });
    }).catch(function (error) {
      detail.loading = false;
      detail.error = text(error && error.message) || String(error);
      renderDetail();
      return null;
    });
  }

  function mountDetail(root) {
    detail.root = root;
    root.classList.add('q-screen');
    var body = el('div', 'q-body');
    detail.body = body;
    detail.head = el('div');
    detail.headNodes = null;      // 新的一屏：头部结构重新认一次
    detail.stepNodes = [];        // 旧的步骤行节点属于上一屏的 DOM，全部作废
    detail.stepCount = 0;
    root.appendChild(body);
    /* 详情**不再自己画 app bar**：外壳的顶栏已经给了「← 返回 / 回执详情 / 刷新 / 更多操作」，
       二级屏的返回按钮由外壳按 currentId 自动显示（见 app.js renderBar 的 isTab 判断）。 */

    if (!detail.number) {
      /* 直接深链到详情：列表里最新的一条就是它。 */
      var listed = list.quests && list.quests.length ? num(list.quests[0].number, 0) : 0;
      detail.number = listed || list.latest || 0;
    }
    detail.progress = { visible: false, percent: 0, label: '', card: null, fill: null, text: null };
    detail.loading = true;
    renderDetail();
    var main = document.getElementById('m-main');
    if (main) main.scrollTop = 0;
    if (!detail.number) {
      loadList(false).then(function () {
        var listed = list.quests && list.quests.length ? num(list.quests[0].number, 0) : 0;
        detail.number = listed || list.latest || 0;
        if (!detail.number) { detail.loading = false; detail.error = '还没有任何任务回执。'; renderDetail(); return; }
        startDetail();
      });
      return;
    }
    startDetail();

    function startDetail() {
      if (!detail.number) return;
      /* 打开就调 /api/quest（服务端会顺手标已读），再显式 POST /api/quests/read 让未读总数立刻掉。 */
      loadDetail(detail.number, false).then(function () { markRead([detail.number]); });
      detail.timer = P.pollWhileVisible(function () {
        if (typeof P.current === 'function' && P.current() !== 'quest-detail') return;
        var payload = detail.payload;
        if (!payload) return;
        var running = !payload.error && !(payload.done && !payload.busy);
        if (!running) return;
        loadDetail(detail.number, true);
      }, 3000);
    }
  }

  function refreshDetail() {
    if (!detail.root || !detail.number) return Promise.resolve(null);
    return Promise.resolve(loadDetail(detail.number, true));
  }

  /** 外壳「更多操作」里的这一屏动作。 */
  function detailMenu() {
    return [
      { text: '跳到最新回执', onSelect: function () { jumpLatest(); } },
      { text: '全部标为已读', onSelect: function () { markAllRead(); } },
      { text: '返回回执列表', onSelect: function () { if (typeof P.go === 'function') P.go('quest'); } }
    ];
  }

  P.register('quest-detail', { title: '回执详情', mount: mountDetail, refresh: refreshDetail, menu: detailMenu });
})();
