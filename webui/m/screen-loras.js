/*
 * Pixiko 手机端 —— 「LoRA」屏（M3）。
 *
 * 同样是按 App 习惯重写的一套移动 UI（不是给桌面控制台注入 CSS），复用桌面那套 /api 接口：
 * /api/loras、/api/lora/download、/api/lora/progress、/api/lora/cover、/api/lora/preview，
 * 改名/删除沿用桌面做法发指令（/api/command 的 `.lora rename …` / `.lora delete …`）。
 *
 * 分组口径与后端一致：`/api/loras` 的 `groups[].key` 就是**归属栈**（Forge 预设栈：anima / xl / …），
 * 组头文字取 `groups[].label`（"Anima 栈"）；判不出栈的组 label 为空，这里显示成「未识别底模」，
 * 并由后端排序放在最后。每一行仍显示它自己的底模标签（同一个栈下可以有多个底模）。
 *
 * 与桌面版的交互差异：
 *   · 桌面一行四个常驻按钮（加载/改名/删除/…）；手机一行只留一个「⋯」，四个动作全收进底部 sheet
 *     （触摸目标 ≥44px，不会误触）。
 *   · 桌面「筛选本地 LoRA」是行内输入框 + 整表重画；手机用同一套过滤逻辑，但结果实时重画并显示条数。
 *   · 桌面把「Civitai 搜索/下载」铺成一张大卡片常驻在列表上面；手机改成**顶部「本地 / Civitai」分段**：
 *     默认「本地」（列表 + 折叠的下载表单），切到「Civitai」才是搜索框 + 结果列表；两块内容各自保留，
 *     来回切不丢状态（搜索词、页码、结果都留着）。
 *   · 桌面的下载进度条挂在卡片里；手机同源数据，进度块固定在**分段之上**（两种模式下都看得见），
 *     结束后自动收起来。进度卡上比桌面多出的东西：**「暂停 / 继续」「取消」**（取消前二次确认）。
 *
 * 新建的两个接口（`/api/lora/cancel`、`/api/lora/pause`、`/api/lora/resume`）**可能还没部署**：
 * 这里一律"先乐观、撞上 404 再置灰"，并把一句人话写在进度卡下面 —— 不白屏、不弹错误框。
 *
 * 纪律：本文件不写任何用户数据（不改 LoRA 目录、不下载），下载/补图/改名/删除都由用户主动点。
 * 全程只用 PixikoM 的 sheet/confirm/prompt/toast，**不碰原生 alert/confirm/prompt**（Android WebView 里不可靠）。
 */
(function () {
  'use strict';

  /* ───────────────────────── 样式（一次性注入，全部 .lo- 前缀） ───────────────────────── */

  /* 排版尺度（与 app.css 的移动端令牌同值，令牌缺失时用兜底值，见任务书「统一排版规格」）：
       正文 15px · 说明/参数名 13px · 标签/胶囊 12px（下限） · 行高 1.5 / 1.45；行距 10px。
     本轮重点：**LoRA 文件名、底模/栈、说明一律完整显示** —— `.lo-name` / `.lo-sub` 原先
     `nowrap + ellipsis`，长文件名（`Naruse_Shiroha_-_Summer_po…`）被剪掉 20px；现在改成换行。 */
  var CSS = [
    '.lo-wrap{display:flex;flex-direction:column;gap:12px;padding:0 0 96px;}',
    '.lo-note{font-size:13px;line-height:1.45;color:var(--muted,#93a4c4);padding:0 4px;',
    'overflow-wrap:anywhere;word-break:break-word;white-space:pre-line;}',
    '.lo-toolbar{display:flex;gap:8px;}',
    '.lo-search{flex:1;min-width:0;box-sizing:border-box;height:44px;padding:0 12px;background:#131c2e;',
    'color:#e8eefc;border:1px solid #26324c;border-radius:10px;font-size:16px;font-family:inherit;}',
    '.lo-search:focus{outline:none;border-color:#5aa2ff;}',
    '.lo-btn{min-height:44px;min-width:44px;padding:0 14px;border-radius:10px;border:1px solid transparent;',
    'background:#5aa2ff;color:#08111f;font-size:15px;font-weight:600;font-family:inherit;cursor:pointer;',
    'display:inline-flex;align-items:center;justify-content:center;}',
    '.lo-btn:active{opacity:.72;}',
    '.lo-btn[disabled]{opacity:.45;}',
    '.lo-btn.lo-ghost{background:#131c2e;color:#cfe0ff;border-color:#2a3a5c;}',
    '.lo-btn.lo-danger{background:#3a1c22;color:#ff6b6b;border-color:#5c2a30;}',
    '.lo-block{background:#131c2e;border-radius:14px;overflow:hidden;border:1px solid #1d2942;}',
    '.lo-block.lo-collapsed .lo-panel{display:none;}',
    '.lo-block.lo-collapsed .lo-chev{transform:rotate(-90deg);}',
    '.lo-head{display:flex;align-items:center;gap:8px;width:100%;min-height:44px;padding:10px 14px;',
    'background:none;border:0;color:#e8eefc;font-size:15px;font-weight:600;line-height:1.5;text-align:left;cursor:pointer;font-family:inherit;}',
    '.lo-head:active{background:#1a2540;}',
    '.lo-chev{margin-left:auto;flex:none;color:#93a4c4;transition:transform .18s ease;}',
    '.lo-panel{display:flex;flex-direction:column;gap:10px;padding:0 14px 14px;}',
    '.lo-field{display:flex;flex-direction:column;gap:6px;}',
    '.lo-label{font-size:13px;line-height:1.45;color:#93a4c4;}',
    '.lo-input{width:100%;box-sizing:border-box;height:44px;padding:0 12px;background:#0b1220;color:#e8eefc;',
    'border:1px solid #26324c;border-radius:10px;font-size:16px;font-family:inherit;}',
    '.lo-input:focus{outline:none;border-color:#5aa2ff;}',
    '.lo-progress{background:#131c2e;border-radius:14px;border:1px solid #1d2942;padding:14px;}',
    '.lo-track{height:6px;border-radius:4px;background:#1b2540;overflow:hidden;}',
    '.lo-fill{height:100%;width:0;background:#5aa2ff;border-radius:4px;transition:width .3s ease;}',
    '.lo-progress.lo-indeterminate .lo-track{opacity:.5;}',
    '.lo-progress.lo-indeterminate .lo-fill{width:36%!important;animation:lo-slide 1.1s ease-in-out infinite;}',
    '@keyframes lo-slide{0%{margin-left:0}50%{margin-left:64%}100%{margin-left:0}}',
    '.lo-ptext{margin-top:8px;font-size:13px;color:#93a4c4;line-height:1.45;font-variant-numeric:tabular-nums;',
    'overflow-wrap:anywhere;word-break:break-word;}',
    '.lo-group{background:#131c2e;border-radius:14px;border:1px solid #1d2942;overflow:hidden;}',
    '.lo-ghead{display:flex;align-items:center;flex-wrap:wrap;gap:6px;padding:12px 14px 8px;}',
    '.lo-gname{font-size:15px;font-weight:600;line-height:1.5;color:#e8eefc;min-width:0;',
    'overflow-wrap:anywhere;word-break:break-word;}',
    '.lo-tag{font-size:12px;line-height:1.45;color:#93a4c4;background:#1b2540;border:1px solid #2a3a5c;border-radius:8px;',
    'padding:2px 8px;max-width:100%;overflow-wrap:anywhere;word-break:break-word;}',
    '.lo-rows{display:flex;flex-direction:column;}',
    '.lo-row{display:flex;align-items:center;gap:10px;width:100%;min-height:64px;padding:8px 10px 8px 14px;',
    'background:none;border:0;border-top:1px solid #1a2338;color:#e8eefc;font-family:inherit;text-align:left;cursor:pointer;}',
    '.lo-row:active{background:#1a2540;}',
    '.lo-thumb{flex:none;width:44px;height:44px;border-radius:10px;overflow:hidden;background:#0b1220;',
    'display:flex;align-items:center;justify-content:center;border:1px solid #26324c;}',
    '.lo-thumb img{width:100%;height:100%;object-fit:cover;display:block;}',
    '.lo-nocover{font-size:12px;line-height:1.45;color:#93a4c4;}',
    '.lo-info{flex:1;min-width:0;display:flex;flex-direction:column;gap:2px;}',
    /* 文件名 / 说明：长名字就折行，不再单行 ellipsis。 */
    '.lo-name{font-size:15px;line-height:1.5;color:#e8eefc;white-space:normal;overflow:visible;text-overflow:clip;',
    'overflow-wrap:anywhere;word-break:break-word;}',
    '.lo-sub{font-size:13px;line-height:1.45;color:#93a4c4;white-space:normal;overflow:visible;text-overflow:clip;',
    'overflow-wrap:anywhere;word-break:break-word;}',
    '.lo-more{flex:none;width:44px;height:44px;display:inline-flex;align-items:center;justify-content:center;',
    'background:none;border:0;color:#93a4c4;font-size:20px;line-height:1;font-family:inherit;cursor:pointer;}',
    '.lo-more:active{background:#26324c;color:#5aa2ff;}',
    '.lo-empty{color:#93a4c4;font-size:15px;line-height:1.5;padding:14px;background:#131c2e;border-radius:14px;',
    'overflow-wrap:anywhere;word-break:break-word;}',
    '.lo-err{color:#ff6b6b;font-size:15px;line-height:1.5;padding:14px;background:#131c2e;border-radius:14px;',
    'overflow-wrap:anywhere;word-break:break-word;}',
    '.lo-loading{color:#93a4c4;font-size:15px;line-height:1.5;padding:20px 4px;text-align:center;}',
    '.lo-count{font-size:13px;line-height:1.45;color:#93a4c4;padding:0 4px;font-variant-numeric:tabular-nums;}',
    '/* ── 本地 / Civitai 分段（本轮新增）：分段控件、Civitai 结果卡、进度卡上的控制键 ── */',
    '.lo-seg{display:flex;gap:6px;background:#131c2e;border:1px solid #1d2942;border-radius:12px;padding:4px;}',
    '.lo-seg-btn{flex:1;min-height:44px;min-width:44px;padding:0 10px;border:0;border-radius:9px;background:none;',
    'color:#93a4c4;font-size:15px;font-weight:600;font-family:inherit;cursor:pointer;}',
    '.lo-seg-btn.lo-on{background:#26324c;color:#e8eefc;}',
    '.lo-seg-btn:active{color:#5aa2ff;}',
    /* 分段切换的两块内容：hidden 必须显式吃掉 display（.lo-local 自己是 flex，UA 的 [hidden] 赢不了） */
    '.lo-local,.lo-civ{display:flex;flex-direction:column;gap:12px;}',
    '.lo-local[hidden],.lo-civ[hidden],.lo-ctrl[hidden],.lo-stat[hidden]{display:none;}',
    '.lo-card{display:flex;gap:10px;width:100%;min-height:88px;padding:10px;align-items:flex-start;text-align:left;',
    'background:#131c2e;border:1px solid #1d2942;border-radius:14px;color:#e8eefc;font-family:inherit;cursor:pointer;}',
    '.lo-card:active{background:#1a2540;}',
    '.lo-cthumb{flex:none;width:64px;height:64px;border-radius:10px;overflow:hidden;background:#0b1220;',
    'display:flex;align-items:center;justify-content:center;border:1px solid #26324c;}',
    '.lo-cthumb img{width:100%;height:100%;object-fit:cover;display:block;}',
    '.lo-cmeta{flex:1;min-width:0;display:flex;flex-direction:column;gap:3px;}',
    '.lo-cname{font-size:15px;line-height:1.5;font-weight:600;overflow-wrap:anywhere;word-break:break-word;}',
    '.lo-cfacts{font-size:13px;line-height:1.45;color:#93a4c4;overflow-wrap:anywhere;word-break:break-word;}',
    '.lo-words{font-size:12px;line-height:1.45;color:#7f8fb0;overflow-wrap:anywhere;word-break:break-word;}',
    '.lo-facts{display:flex;flex-wrap:wrap;gap:6px;}',
    '.lo-badge{font-size:12px;line-height:1.45;padding:2px 8px;border-radius:8px;background:#1b2540;color:#93a4c4;',
    'border:1px solid #2a3a5c;overflow-wrap:anywhere;word-break:break-word;}',
    '.lo-badge.lo-warn{background:#3a1c22;color:#ff8f8f;border-color:#5c2a30;}',
    /* 进度卡上的「暂停 / 继续」「取消」：各占一半，触摸目标 ≥44px */
    '.lo-ctrl{display:flex;gap:8px;margin-top:10px;}',
    '.lo-ctrl .lo-btn{flex:1;}',
    '.lo-stat{font-size:13px;line-height:1.45;color:#93a4c4;padding:4px 0 0;overflow-wrap:anywhere;word-break:break-word;}',
    '/* 外壳把 body 设成 user-select:none；输入框必须能选中文本。 */',
    '.lo-search,.lo-input{user-select:text;-webkit-user-select:text;}'
  ].join('');

  function injectStyle() {
    if (document.getElementById('lo-style')) return;
    var style = document.createElement('style');
    style.id = 'lo-style';
    style.textContent = CSS;
    document.head.appendChild(style);
  }

  /** Civitai 封面缩略图的长边（与 `/m/app.js` 的 IMAGE_THUMB_W=320 同档：列表里只要小图）。 */
  var CIVITAI_THUMB_W = 320;

  /* 契约里写了 PixikoM.el / clear / esc / fmtTime / fmtBytes，但核心这一版只挂了
     register/go/current/api/token/scope/toast/sheet/confirm/prompt/imageUrl/openViewer/
     pollWhileVisible/state/on/refreshStatus —— 这几个小工具没往 PixikoM 上放。
     这里一律「核心有就用核心的，没有就自带一份等价的」，等核心补齐后自动切回去，调用点不用改。 */
  function localEl(tag, cls, text) {
    var node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text !== null && text !== undefined && text !== '') node.textContent = String(text);
    return node;
  }
  function localClear(node) {
    if (!node) return node;
    while (node.firstChild) node.removeChild(node.firstChild);
    return node;
  }
  function localFmtBytes(n) {
    var value = Number(n);
    if (!isFinite(value) || value < 0) return '—';
    if (value < 1024) return Math.round(value) + ' B';
    if (value < 1024 * 1024) return (value / 1024).toFixed(1) + ' KB';
    if (value < 1024 * 1024 * 1024) return (value / 1024 / 1024).toFixed(1) + ' MB';
    return (value / 1024 / 1024 / 1024).toFixed(2) + ' GB';
  }

  /* ───────────────────────── 屏幕本体 ───────────────────────── */

  function setup(P) {
    var el = typeof P.el === 'function' ? P.el : localEl;
    var clear = typeof P.clear === 'function' ? P.clear : localClear;
    var fmtBytes = typeof P.fmtBytes === 'function' ? P.fmtBytes : localFmtBytes;

    var state = {
      data: null,
      filter: '',
      mounted: false,
      seq: 0,
      inflight: null,
      progress: null,      // pollWhileVisible 的 stop()
      hideTimer: null,     // 下载结束后把进度块收起来用的定时器
      nodes: {},
      /* —— 分段与 Civitai 搜索 —— */
      mode: 'local',       // 'local' | 'civitai'
      civQuery: '',        // 上一次的搜索词（翻页时不用重敲）
      civPage: 0,          // 已经画出来的最后一页（0 = 还没搜过）
      civHasMore: false,
      civItems: [],        // 已加载的结果（翻页是 append，不是 replace）
      civBusy: false,      // 搜索在飞
      civSeq: 0,
      civStatus: null,     // /api/civitai/status 的读数
      civStatusLoaded: false,
      scrollBound: false,
      /* —— 进度卡上的控制键 —— */
      lastData: null,
      lastRunning: false,
      ctrlDead: false      // 撞上 404：这个机器人还没有暂停/取消接口
    };

    /* ---------- DOM 骨架 ---------- */

    function build() {
      var wrap = el('div', 'lo-wrap');

      var note = el('div', 'lo-note');
      state.nodes.note = note;
      wrap.appendChild(note);

      var status = el('div', 'lo-status');
      state.nodes.status = status;
      wrap.appendChild(status);

      // 进度卡在分段**之上**：两种模式下都看得见（Civitai 里点了下载，不会一切分段就"进度消失了"）。
      wrap.appendChild(buildProgress());

      // 「本地 / Civitai」分段：默认本地，切过去不重画、不丢状态。
      wrap.appendChild(buildSegments());

      var local = el('div', 'lo-local');
      state.nodes.local = local;

      // 搜索 + 刷新
      var toolbar = el('div', 'lo-toolbar');
      var search = el('input', 'lo-search');
      search.type = 'search';
      search.placeholder = '搜索名称 / 底模 / 栈';
      search.setAttribute('autocomplete', 'off');
      search.addEventListener('input', function () {
        state.filter = search.value.trim().toLowerCase();
        renderGroups();
      });
      var reload = el('button', 'lo-btn lo-ghost lo-reload', '刷新');
      reload.type = 'button';
      reload.addEventListener('click', function () { load(); });
      toolbar.appendChild(search);
      toolbar.appendChild(reload);
      local.appendChild(toolbar);
      state.nodes.search = search;
      state.nodes.reload = reload;

      // 下载 LoRA（折叠）
      local.appendChild(buildDownload());

      var count = el('div', 'lo-count');
      state.nodes.count = count;
      local.appendChild(count);

      var groups = el('div', 'lo-groups');
      state.nodes.groups = groups;
      local.appendChild(groups);

      wrap.appendChild(local);
      wrap.appendChild(buildCivitai());

      return wrap;
    }

    /**
     * 进度卡（M3 原样 + 两个控制键）。
     *
     * <p>「暂停 / 继续」「取消」是**动态建**在这张卡里的 —— `webui/m/app.css` 与 `index.html` 都不归本文件改。
     * 取消按钮点下去先过 `PixikoM.confirm`（说明会删掉已下载的部分），确认了才发请求。
     */
    function buildProgress() {
      var progress = el('div', 'lo-progress');
      progress.hidden = true;
      var track = el('div', 'lo-track');
      var fill = el('div', 'lo-fill');
      track.appendChild(fill);
      progress.appendChild(track);
      var ptext = el('div', 'lo-ptext', '');
      progress.appendChild(ptext);
      var hint = el('div', 'lo-stat lo-ctrlhint');
      hint.hidden = true;
      progress.appendChild(hint);

      var ctrl = el('div', 'lo-ctrl');
      ctrl.hidden = true;
      var pause = el('button', 'lo-btn lo-ghost lo-pause', '暂停');
      pause.type = 'button';
      pause.addEventListener('click', function () { togglePause(pause); });
      var cancel = el('button', 'lo-btn lo-danger lo-cancel', '取消');
      cancel.type = 'button';
      cancel.addEventListener('click', function () { cancelDownload(cancel); });
      ctrl.appendChild(pause);
      ctrl.appendChild(cancel);
      progress.appendChild(ctrl);

      state.nodes.progress = progress;
      state.nodes.fill = fill;
      state.nodes.ptext = ptext;
      state.nodes.ctrl = ctrl;
      state.nodes.pause = pause;
      state.nodes.cancel = cancel;
      state.nodes.ctrlHint = hint;
      return progress;
    }

    /** 顶部「本地 / Civitai」两个分段（等价于 tab，两个按钮各自 ≥44px）。 */
    function buildSegments() {
      var seg = el('div', 'lo-seg');
      seg.setAttribute('role', 'tablist');
      var buttons = {};
      [['local', '本地'], ['civitai', 'Civitai']].forEach(function (pair) {
        var button = el('button', 'lo-seg-btn', pair[1]);
        button.type = 'button';
        button.setAttribute('role', 'tab');
        button.setAttribute('data-seg', pair[0]);
        button.addEventListener('click', function () { setMode(pair[0]); });
        seg.appendChild(button);
        buttons[pair[0]] = button;
      });
      state.nodes.seg = seg;
      state.nodes.segButtons = buttons;
      return seg;
    }

    /** Civitai 一块：搜索行 + Cookie 提示 + 结果区 + 「加载更多」。 */
    function buildCivitai() {
      var box = el('div', 'lo-civ');
      box.hidden = true;
      state.nodes.civ = box;

      var toolbar = el('div', 'lo-toolbar');
      var input = el('input', 'lo-search lo-civq');
      input.type = 'search';
      input.placeholder = '搜索 Civitai 上的 LoRA';
      input.setAttribute('autocomplete', 'off');
      input.addEventListener('keydown', function (event) {
        if (event.key === 'Enter') { event.preventDefault(); searchCivitai(1); }
      });
      var go = el('button', 'lo-btn lo-civgo', '搜索');
      go.type = 'button';
      go.addEventListener('click', function () { searchCivitai(1); });
      toolbar.appendChild(input);
      toolbar.appendChild(go);
      box.appendChild(toolbar);
      state.nodes.civQuery = input;
      state.nodes.civGo = go;

      var stat = el('div', 'lo-stat lo-civstat');
      box.appendChild(stat);
      state.nodes.civStat = stat;

      // 翻页/条数另起一行：**不能和上面的 Cookie 提示共用一个节点** —— 一搜索就把提示冲掉了
      // （第一版就是这么写的，探针 H2 当场抓到：搜完之后 Cookie 提示不见了）。
      var pageLine = el('div', 'lo-stat lo-civpage');
      box.appendChild(pageLine);
      state.nodes.civPageLine = pageLine;

      var list = el('div', 'lo-civlist');
      box.appendChild(list);
      state.nodes.civList = list;

      var more = el('button', 'lo-btn lo-ghost lo-civmore', '加载更多');
      more.type = 'button';
      more.hidden = true;
      more.addEventListener('click', function () { searchCivitai(state.civPage + 1); });
      box.appendChild(more);
      state.nodes.civMore = more;
      return box;
    }

    function buildDownload() {
      var block = el('section', 'lo-block lo-collapsed');
      var head = el('button', 'lo-head');
      head.type = 'button';
      head.appendChild(el('span', null, '下载 LoRA'));
      head.appendChild(el('span', 'lo-chev', '⌄'));
      head.addEventListener('click', function () { block.classList.toggle('lo-collapsed'); });
      block.appendChild(head);

      var panel = el('div', 'lo-panel');
      var hint = el('div', 'lo-label', 'Civitai 模型页链接或直链；权重 0.1–1.5，默认 1.0。');
      panel.appendChild(hint);

      var urlField = el('div', 'lo-field');
      urlField.appendChild(el('span', 'lo-label', '链接'));
      var url = el('input', 'lo-input lo-url');
      url.type = 'text';
      url.setAttribute('autocomplete', 'off');
      url.placeholder = 'https://civitai.com/models/…';
      urlField.appendChild(url);
      panel.appendChild(urlField);

      var weightField = el('div', 'lo-field');
      weightField.appendChild(el('span', 'lo-label', '权重'));
      var weight = el('input', 'lo-input lo-weight');
      weight.type = 'number';
      weight.min = '0.1';
      weight.max = '1.5';
      weight.step = '0.05';
      weight.value = '1.0';
      weight.inputMode = 'decimal';
      weightField.appendChild(weight);
      panel.appendChild(weightField);

      var row = el('div', 'lo-toolbar');
      var go = el('button', 'lo-btn lo-download', '开始下载');
      go.type = 'button';
      go.addEventListener('click', function () { download(url, weight, go); });
      row.appendChild(go);
      panel.appendChild(row);

      block.appendChild(panel);
      state.nodes.dlBlock = block;
      state.nodes.dlUrl = url;
      state.nodes.dlWeight = weight;
      state.nodes.dlGo = go;
      return block;
    }

    /* ---------- 分段：本地 / Civitai ---------- */

    /**
     * 切分段。**不重画**任何一块：两块 DOM 都在，只是 `hidden` 切换 —— 搜索词、页码、结果、
     * 本地筛选都留着，来回切是"零成本"的（也顺带保证 hash 路由与返回键完全不受影响）。
     */
    function setMode(mode) {
      state.mode = mode === 'civitai' ? 'civitai' : 'local';
      var nodes = state.nodes;
      if (nodes.local) nodes.local.hidden = state.mode !== 'local';
      if (nodes.civ) nodes.civ.hidden = state.mode !== 'civitai';
      if (nodes.segButtons) {
        for (var key in nodes.segButtons) {
          if (!Object.prototype.hasOwnProperty.call(nodes.segButtons, key)) continue;
          var on = key === state.mode;
          nodes.segButtons[key].classList.toggle('lo-on', on);
          nodes.segButtons[key].setAttribute('aria-selected', on ? 'true' : 'false');
        }
      }
      if (state.mode === 'civitai') {
        bindCivScroll();
        loadCivitaiStatus();
      }
    }

    /** 滚到底自动加载下一页：监听的是滚动容器 `#m-main`（不是 window）。 */
    function bindCivScroll() {
      if (state.scrollBound) return;
      var scroller = document.getElementById('m-main');
      if (!scroller) return;
      state.scrollBound = true;
      state.scroller = scroller;
      scroller.addEventListener('scroll', onCivScroll, { passive: true });
    }

    function onCivScroll() {
      if (state.mode !== 'civitai' || !state.civHasMore || state.civBusy) return;
      var box = state.scroller;
      if (!box) return;
      if (box.scrollTop + box.clientHeight >= box.scrollHeight - 200) searchCivitai(state.civPage + 1);
    }

    /* ---------- Civitai：Cookie 状态 ---------- */

    /**
     * `/api/civitai/status` → 一句人话。
     *
     * <p>**host 用服务端给的**（线上是镜像站 civitai.red），不在界面里写死 civitai.com：
     * 写死就会把人指到一个实际没在用的域名上。没配 Cookie 时按契约提示"成人内容可能不可见"，
     * 并指路桌面控制台 —— 手机端不做 Cookie 输入（服务端没有这个接口，不自己造）。
     */
    function loadCivitaiStatus(force) {
      if (state.civStatusLoaded && !force) { renderCivitaiStatus(); return; }
      state.civStatusLoaded = true;
      P.api('/api/civitai/status', { body: {} }).then(function (data) {
        state.civStatus = data || {};
        renderCivitaiStatus();
      }, function (error) {
        state.civStatus = { error: message(error) };
        renderCivitaiStatus();
      });
    }

    function renderCivitaiStatus() {
      var node = state.nodes.civStat;
      if (!node) return;
      var data = state.civStatus || {};
      if (data.error) {
        node.textContent = '读不到 Civitai 状态：' + data.error;
        return;
      }
      var host = String(data.host || '').replace(/^https?:\/\//, '') || 'civitai.com';
      if (data.hasCookie) {
        node.textContent = 'Civitai：' + host + '（已保存登录 Cookie'
          + (data.cookieHint ? ' ' + data.cookieHint : '') + '）。搜索与下载都走这个站。';
        return;
      }
      node.textContent = 'Civitai：' + host + '。未保存登录 Cookie：搜索与下载会用 '
        + host + '，成人内容与部分模型不可见。到桌面控制台「LoRA → Civitai 账号」粘一条登录链接即可，'
        + '手机端不填 Cookie。';
    }

    /* ---------- Civitai：搜索 ---------- */

    /**
     * 搜一页。`page > 1` 是**追加**（滚动到底 /「加载更多」），`page === 1` 是重来。
     *
     * <p>响应契约（照桌面控制台 `searchCivitai` 的调用抄）：
     * `{query, results[{number,name,baseModel,url,cover,downloads,sizeKb,nsfw,trainedWords[]}],
     *   count, page, pageSize, hasMore, totalPages, totalPagesKnown, nextPage, note?}`
     */
    function searchCivitai(page) {
      if (state.civBusy) return null;
      var input = state.nodes.civQuery;
      var typed = input ? String(input.value || '').trim() : '';
      var query = typed || state.civQuery;
      if (!query) { P.toast('请先填搜索词'); return null; }
      var wanted = page > 0 ? page : 1;
      state.civQuery = query;
      state.civBusy = true;
      var seq = ++state.civSeq;
      if (state.nodes.civGo) state.nodes.civGo.disabled = true;
      if (wanted === 1) renderCivitai([], { loading: '正在搜索 Civitai：' + query + '…' });
      else if (state.nodes.civMore) state.nodes.civMore.textContent = '正在加载…';

      return P.api('/api/civitai/search', { body: { query: query, page: wanted, scope: P.scope() } })
        .then(function (data) {
          if (seq !== state.civSeq) return data;
          state.civBusy = false;
          if (state.nodes.civGo) state.nodes.civGo.disabled = false;
          data = data || {};
          state.civQuery = data.query || query;
          state.civPage = Number(data.page) || wanted;
          state.civHasMore = !!data.hasMore;
          var items = Array.isArray(data.results) ? data.results : [];
          if (state.civPage > 1) state.civItems = state.civItems.concat(items);
          else state.civItems = items.slice();
          renderCivitai(state.civItems, items.length ? {} : {
            empty: data.note || ('没有找到「' + state.civQuery + '」的 LoRA。')
          });
          renderCivitaiPager(data, items.length);
          return data;
        }, function (error) {
          if (seq !== state.civSeq) return null;
          state.civBusy = false;
          if (state.nodes.civGo) state.nodes.civGo.disabled = false;
          if (state.nodes.civMore) state.nodes.civMore.textContent = '加载更多';
          renderCivitai([], { empty: '搜索失败：' + message(error) + hintForCivitai(error) });
          if (state.nodes.civMore) state.nodes.civMore.hidden = true;
          return null;
        });
    }

    /** 接口没就绪（404 / 未知接口）时补一句人话，别让人以为是网络问题。 */
    function hintForCivitai(error) {
      return isMissingEndpoint(error) ? '\n（这个机器人还没有 Civitai 搜索接口，需要更新服务端。）' : '';
    }

    /** 结果区三态：加载中 / 空（或错误）/ 卡片列表。 */
    function renderCivitai(items, options) {
      var box = state.nodes.civList;
      if (!box) return;
      clear(box);
      var list = Array.isArray(items) ? items : [];
      if (options && options.loading) {
        box.appendChild(el('div', 'lo-loading', options.loading));
        return;
      }
      if (!list.length) {
        box.appendChild(el('div', 'lo-empty', (options && options.empty) || '输入关键词后点「搜索」。'));
        return;
      }
      list.forEach(function (item) { box.appendChild(civitaiCard(item)); });
    }

    function renderCivitaiPager(data, added) {
      var more = state.nodes.civMore;
      var line = state.nodes.civPageLine;
      if (more) {
        more.hidden = !state.civHasMore;
        more.textContent = '加载更多';
        // 已经加载过一页但这次是空的（翻过头）：按钮收起来更诚实。
        if (!added) more.hidden = true;
      }
      if (line && state.civQuery) {
        var total = Number(data && data.totalPages);
        var page = Number((data && data.page) || state.civPage) || 1;
        var pages = (data && data.totalPagesKnown && total > 0) ? ' / 共 ' + total + ' 页' : '';
        line.textContent = '「' + state.civQuery + '」第 ' + page + ' 页' + pages + '：本页 '
          + (Number(data && data.count) || 0) + ' 项，已加载 ' + state.civItems.length + ' 项'
          + (state.civHasMore ? '（还有下一页，滚到底会自动加载）' : '（已经是最后一页）');
      }
    }

    /**
     * 一张结果卡。封面走 `/api/civitai/thumb`（机器人带登录态代取，浏览器不直连图床），
     * 并**在 Civitai 图床地址上带缩略图参数 `width`** —— 和 `/m` 其它图片"列表只拿小图"的约定一致
     * （`PixikoM.imageUrl` 对 `/api/...` 是直通的，所以这里自己拼绝对路径）。
     */
    function civitaiCard(item) {
      var card = el('button', 'lo-card lo-civcard');
      card.type = 'button';
      card.setAttribute('data-civ-name', String(item.name || ''));

      var thumb = el('div', 'lo-cthumb');
      var cover = civitaiThumbUrl(item.cover);
      if (cover) {
        var image = document.createElement('img');
        image.alt = '';
        image.loading = 'lazy';
        image.src = cover;
        image.addEventListener('error', function () {
          clear(thumb);
          thumb.appendChild(el('span', 'lo-nocover', '无图'));
        });
        thumb.appendChild(image);
      } else {
        thumb.appendChild(el('span', 'lo-nocover', '无图'));
      }
      card.appendChild(thumb);

      var meta = el('div', 'lo-cmeta');
      meta.appendChild(el('div', 'lo-cname', String(item.name || '未命名')));
      var facts = [];
      if (item.baseModel) facts.push(String(item.baseModel));
      facts.push('LoRA');
      var downloads = Number(item.downloads);
      if (isFinite(downloads) && downloads > 0) facts.push('下载 ' + formatCount(downloads));
      var kb = Number(item.sizeKb);
      if (isFinite(kb) && kb > 0) facts.push(fmtBytes(kb * 1024));
      meta.appendChild(el('div', 'lo-cfacts', facts.join(' · ')));
      if (item.nsfw) {
        var badges = el('div', 'lo-facts');
        badges.appendChild(el('span', 'lo-badge lo-warn', 'NSFW'));
        meta.appendChild(badges);
      }
      var words = Array.isArray(item.trainedWords) ? item.trainedWords : [];
      if (words.length) {
        meta.appendChild(el('div', 'lo-words', '触发词：' + clip(words.join('、'), 90)));
      }
      card.appendChild(meta);

      card.addEventListener('click', function () { civitaiDetail(item); });
      return card;
    }

    /**
     * 点结果卡 → 详情 sheet（沿用 `/m` 既有的底部 sheet 语言，不自己造二级屏）。
     *
     * <p>合同里没有作者字段，所以这里只给：底模 / 类型 / 文件大小 / NSFW / 触发词。
     * 每一行点一下就是"复制这一行的值"（信息行也不是死按钮）。
     */
    function civitaiDetail(item) {
      var words = Array.isArray(item.trainedWords) ? item.trainedWords : [];
      var kb = Number(item.sizeKb);
      var downloads = Number(item.downloads);
      var host = String((state.civStatus && state.civStatus.host) || 'civitai.com').replace(/^https?:\/\//, '');
      var items = [
        {
          text: '触发词', sub: words.length ? clip(words.join('、'), 120) : '这个模型没给触发词',
          onSelect: function () {
            if (!words.length) { P.toast('这个模型没给触发词'); return; }
            copyText(words.join('、'), '已复制触发词');
          }
        },
        {
          text: '底模 / 类型', sub: (item.baseModel || '底模未标注') + ' · LoRA',
          onSelect: function () { copyText(String(item.baseModel || ''), '已复制底模'); }
        },
        {
          text: '文件大小 / 分级',
          sub: (isFinite(kb) && kb > 0 ? '约 ' + fmtBytes(kb * 1024) : '大小未知')
            + ' · ' + (item.nsfw ? '标注为 NSFW' : '未标注为 NSFW')
            + (isFinite(downloads) && downloads > 0 ? ' · 下载 ' + formatCount(downloads) + '次' : ''),
          onSelect: function () { copyText(String(item.name || ''), '已复制模型名'); }
        },
        {
          text: '下载到本机', sub: '权重 1.0（要别的权重下载后在列表里调）',
          onSelect: function () { downloadItem(item); }
        },
        {
          text: '打开模型页', sub: host + ' 上的模型页（在浏览器里打开）',
          onSelect: function () {
            if (!item.url) { P.toast('这条结果没有模型页地址'); return; }
            try { window.open(String(item.url), '_blank', 'noopener'); }
            catch (error) { P.toast(String(item.url)); }
          }
        }
      ];
      P.sheet({ title: String(item.name || '未命名') + (item.nsfw ? '（NSFW）' : ''), items: items });
    }

    /** 从结果卡直接下载：和桌面控制台一致，权重固定 1.0，发完立刻挂进度轮询。 */
    function downloadItem(item) {
      if (!item || !item.url) { P.toast('这条结果没有下载地址'); return; }
      P.api('/api/lora/download', { body: { url: String(item.url), weight: 1, scope: P.scope() } })
        .then(function (started) {
          if (started && started.quest) P.toast('任务 #' + started.quest + ' 已下达');
          else P.toast('已开始下载：' + String(item.name || ''));
          watchProgress(true);
        }, function (error) { P.toast('下载失败：' + message(error)); });
    }

    /**
     * 封面代取地址。
     *
     * <p>`width` 只加在 **Civitai 图床**的地址上（机器人那侧原样转发这次 GET，图床按参数回缩略图）；
     * 不是图床的地址一个参数都不动，免得把别人的签名 URL 搞坏。
     */
    function civitaiThumbUrl(cover) {
      var url = String(cover == null ? '' : cover).trim();
      if (!url || !/^https?:\/\//i.test(url)) return '';
      var host = '';
      try { host = new URL(url).hostname.toLowerCase(); } catch (error) { return ''; }
      if (!/(^|\.)civitai\.(com|red|net)$/.test(host)) return '';
      var thumb = url + (url.indexOf('?') >= 0 ? '&' : '?') + 'width=' + CIVITAI_THUMB_W;
      var token = typeof P.token === 'function' ? P.token() : '';
      return location.origin + '/api/civitai/thumb?url=' + encodeURIComponent(thumb)
        + (token ? '&token=' + encodeURIComponent(token) : '');
    }

    /** 复制一段文本（触发词 / 底模）：优先 clipboard，退回 execCommand，失败就把原文 toast 出来。 */
    function copyText(text, okText) {
      var value = String(text == null ? '' : text);
      if (!value) { P.toast('这条没有可复制的内容'); return; }
      var fallback = function () {
        try {
          var area = document.createElement('textarea');
          area.value = value;
          area.setAttribute('readonly', 'readonly');
          area.style.position = 'fixed';
          area.style.opacity = '0';
          document.body.appendChild(area);
          area.select();
          var ok = document.execCommand('copy');
          document.body.removeChild(area);
          P.toast(ok ? (okText || '已复制') : value);
        } catch (error) { P.toast(value); }
      };
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(value).then(function () { P.toast(okText || '已复制'); }, fallback);
          return;
        }
      } catch (error) { /* 落到兜底 */ }
      fallback();
    }

    /** 长文本截断（卡片/详情用；触发词可能是一整段示例 prompt）。 */
    function clip(text, max) {
      var value = String(text == null ? '' : text);
      return value.length > max ? value.slice(0, max) + '…' : value;
    }

    /** 下载数按中文习惯加千分位（1,234,567 → 123.5 万）。 */
    function formatCount(value) {
      var number = Number(value) || 0;
      if (number >= 10000) return (number / 10000).toFixed(1).replace(/\.0$/, '') + ' 万';
      return number.toLocaleString('zh-CN');
    }

    /* ---------- 状态 ---------- */

    function message(error) {
      return (error && error.message) ? String(error.message) : String(error || '未知错误');
    }

    function showStatus(text, kind) {
      var box = state.nodes.status;
      clear(box);
      if (!text) { box.hidden = true; return; }
      box.hidden = false;
      box.appendChild(el('div', kind === 'error' ? 'lo-err' : 'lo-loading', text));
    }

    /* ---------- 读 /api/loras ---------- */

    function load() {
      if (state.inflight) return state.inflight;
      var seq = ++state.seq;
      showStatus('正在读取本机 LoRA…');
      state.inflight = P.api('/api/loras', { body: { scope: P.scope() } }).then(function (data) {
        state.inflight = null;
        if (seq !== state.seq) return data;
        state.data = data || {};
        showStatus('');
        renderGroups();
        // 进屏时后台可能正在下载（甚至刚刷新过页面）：接着把进度条挂上，不然进度就"看不见了"。
        var download = state.data.download;
        if (jobActive(download)) watchProgress(false);
        return data;
      }, function (error) {
        state.inflight = null;
        if (seq !== state.seq) return null;
        state.data = null;
        showStatus('读取本机 LoRA 失败：' + message(error) + '（下拉可重试）', 'error');
        return null;
      });
      return state.inflight;
    }

    /* ---------- 分组（与后端 groups 口径一致） ---------- */

    function buckets() {
      var data = state.data || {};
      var items = data.loras || [];
      var byKey = {};
      (data.groups || []).forEach(function (group, index) {
        var key = String(group.key == null ? '' : group.key);
        byKey[key] = { group: group, order: index };
      });
      var order = [];
      var map = {};
      items.forEach(function (item) {
        var key = String(item.groupKey == null ? '' : item.groupKey);
        if (!map[key]) {
          var known = byKey[key];
          var group = (known && known.group) || {};
          var title = group.label || item.stackLabel
            || (item.baseModel ? '底模 ' + item.baseModel : '') || '未识别底模';
          map[key] = {
            key: key,
            order: known ? known.order : 100000,
            title: title,
            base: ((group.baseModels || []).filter(Boolean).join('、')) || item.baseModel || '',
            source: sourceLabel(group.stackSource || item.stackSource),
            items: []
          };
          order.push(map[key]);
        }
        map[key].items.push(item);
      });
      order.sort(function (left, right) {
        if (!!left.key !== !!right.key) return left.key ? -1 : 1;   // 判不出栈的排最后
        return left.order - right.order || left.title.localeCompare(right.title, 'zh');
      });
      return order;
    }

    function sourceLabel(source) {
      var value = String(source == null ? '' : source);
      var table = {
        'safetensors-header': 'safetensors 头部元数据',
        'safetensors-keys': 'safetensors 张量结构',
        'civitai': 'Civitai 记录',
        'forge-metadata': 'Forge 元数据',
        'forge-preset': 'Forge 预设配置',
        'preset-inferred': '按当前预设推断',
        'inferred': '按文件名/当前预设推断'
      };
      return table[value] || '';
    }

    function matches(item, filter) {
      if (!filter) return true;
      var hay = [item.name, item.alias, item.baseModel, item.baseModelLabel, item.stack, item.stackLabel]
        .map(function (value) { return String(value == null ? '' : value).toLowerCase(); }).join(' ');
      return hay.indexOf(filter) >= 0;
    }

    /* ---------- 渲染列表 ---------- */

    function renderGroups() {
      var box = state.nodes.groups;
      clear(box);
      var data = state.data;
      if (!data) { state.nodes.count.textContent = ''; return; }

      var directory = data.directory || '';
      var lines = [];
      if (directory) lines.push('LoRA 目录：' + directory);
      if (data.status) lines.push(String(data.status));
      state.nodes.note.textContent = lines.join('\n');

      // SD 没在跑：/api/loras 会带 error，但不能显示成"一个 LoRA 都没有"。
      if (data.error) {
        var failure = el('div', 'lo-err', '读取本机 LoRA 失败：' + data.error);
        box.appendChild(failure);
        state.nodes.count.textContent = data.status || '';
        return;
      }

      var items = data.loras || [];
      var matched = items.filter(function (item) { return matches(item, state.filter); });
      state.nodes.count.textContent = '共 ' + items.length + ' 个'
        + (state.filter ? '，匹配 ' + matched.length + ' 个' : '')
        + (data.status ? '' : '');

      if (!matched.length) {
        var text = items.length
          ? '没有匹配「' + state.filter + '」的 LoRA（本机共 ' + items.length + ' 个）。'
          : 'LoRA 目录：' + (directory || '（未配置）') + '（还没有 LoRA）';
        var empty = el('div', 'lo-empty', text);
        box.appendChild(empty);
        return;
      }

      // 查看器用的封面清单：只含有图的项，顺序与屏幕一致。
      var covers = [];
      buckets().forEach(function (bucket) {
        var rows = bucket.items.filter(function (item) { return matches(item, state.filter); });
        if (!rows.length) return;
        var groupBox = el('section', 'lo-group');
        var head = el('div', 'lo-ghead');
        head.appendChild(el('span', 'lo-gname', bucket.title + '（' + rows.length + '）'));
        if (bucket.base && bucket.base !== bucket.title) head.appendChild(el('span', 'lo-tag', '底模 ' + bucket.base));
        if (bucket.source) head.appendChild(el('span', 'lo-tag', bucket.source));
        groupBox.appendChild(head);

        var list = el('div', 'lo-rows');
        rows.forEach(function (item) {
          if (item.preview) covers.push({ src: coverUrl(item.name), caption: item.name });
          list.appendChild(row(item));
        });
        groupBox.appendChild(list);
        box.appendChild(groupBox);
      });
      state.covers = covers;
    }

    function row(item) {
      var button = el('button', 'lo-row');
      button.type = 'button';
      button.setAttribute('data-name', item.name);
      button.appendChild(thumb(item));

      var info = el('div', 'lo-info');
      info.appendChild(el('div', 'lo-name', item.name));
      info.appendChild(el('div', 'lo-sub', baseText(item)));
      var size = item.size || item.sizeBytes || item.bytes;
      if (size) info.appendChild(el('div', 'lo-sub', P.fmtBytes ? fmtBytes(size) : String(size)));
      button.appendChild(info);

      var more = el('span', 'lo-more', '⋯');
      button.appendChild(more);

      button.addEventListener('click', function () { actions(item); });
      return button;
    }

    function baseText(item) {
      var stack = item.stackLabel || '';
      var base = item.baseModelLabel || (item.baseModel ? '底模 ' + item.baseModel : '');
      if (stack && base) return stack + ' · ' + base;
      return stack || base || '底模未识别';
    }

    /**
     * LoRA 展示图地址。
     *
     * <p>/api/lora/preview 只认查询串（见 WebApiController.loraNameQuery），令牌也挂在查询串上
     * （和桌面版 loraPreviewUrl 同款）。这里**不能**过 PixikoM.imageUrl：那一支只把
     * `data/generated/…` 形态的相对路径拼成 `/api/image?path=…`，遇到 `/api/...` 会当成相对路径错拼。
     * 用绝对地址还顺带保证了查看器里那同一份地址能原样直通（imageUrl 对 http(s): 是直通的）。
     */
    function coverUrl(name) {
      var token = typeof P.token === 'function' ? P.token() : '';
      return location.origin + '/api/lora/preview?name=' + encodeURIComponent(name)
        + (token ? '&token=' + encodeURIComponent(token) : '');
    }

    function thumb(item) {
      var box = el('div', 'lo-thumb');
      if (!item.preview) {
        box.appendChild(el('span', 'lo-nocover', '无图'));
        return box;
      }
      var image = document.createElement('img');
      image.alt = '';
      image.loading = 'lazy';
      image.src = coverUrl(item.name);
      image.addEventListener('error', function () {
        clear(box);
        box.appendChild(el('span', 'lo-nocover', '无图'));
      });
      box.appendChild(image);
      return box;
    }

    /* ---------- 行动作：底部 sheet，五个动作 ────────── */

    function actions(item) {
      var items = [
        { text: '查看封面', onSelect: function () { viewCover(item); } },
        { text: '补抓封面', onSelect: function () { cover(item); } },
        { text: '改名', onSelect: function () { rename(item); } },
        { text: '删除', danger: true, onSelect: function () { remove(item); } },
        { text: '复制名字', onSelect: function () { copyName(item); } }
      ];
      P.sheet({ title: item.name, items: items });
    }

    /** 查看封面：把本屏所有有图的 LoRA 一起交给查看器（左右翻页就是在封面之间翻）。 */
    function viewCover(item) {
      if (!item.preview) { P.toast('这个 LoRA 还没有展示图，可以先「补抓封面」'); return; }
      var entry = { src: coverUrl(item.name), caption: item.name };
      var list = (state.covers || []).slice();
      var index = list.map(function (row) { return row.src; }).indexOf(entry.src);
      if (index < 0) { list = [entry]; index = 0; }
      P.openViewer(list, index);
    }

    /** 补抓展示图：接口只负责启动，进度靠 /api/lora/progress。 */
    function cover(item) {
      P.api('/api/lora/cover', { body: { name: item.name, scope: P.scope() } }).then(function (started) {
        if (started && started.quest) P.toast('任务 #' + started.quest + ' 已下达');
        watchProgress(true);
      }, function (error) {
        P.toast(message(error));
      });
    }

    /** 改名：先把新名字问出来，再发桌面同款指令（会同步个人 prompt 里的 LoRA 标签）。 */
    function rename(item) {
      P.prompt({
        title: '重命名 LoRA',
        value: item.name,
        placeholder: '新的 LoRA 名字（不含扩展名）',
        ok: '改名'
      }).then(function (next) {
        var value = String(next == null ? '' : next).trim();
        if (!value || value === item.name) return;
        var command = '.lora rename "' + item.name + '" "' + value + '"';
        P.api('/api/command', { body: { command: command, scope: P.scope() } }).then(function () {
          P.toast('已下达改名：' + item.name + ' → ' + value);
          load();
        }, function (error) { P.toast(message(error)); });
      });
    }

    /** 删除：必须过一道 confirm（默认焦点不在"删除"上），确认后才真的发指令。 */
    function remove(item) {
      P.confirm({
        title: '删除 LoRA',
        text: '从磁盘删除「' + item.name + '」？文件会被真的删掉，无法撤销。',
        ok: '删除',
        danger: true
      }).then(function (yes) {
        if (!yes) return false;
        var target = item.number ? '#' + item.number : '"' + item.name + '"';
        var command = '.lora delete ' + target;
        return P.api('/api/command', { body: { command: command, scope: P.scope() } }).then(function () {
          P.toast('已下达删除：' + item.name);
          load();
          return true;
        }, function (error) {
          P.toast(message(error));
          return false;
        });
      });
    }

    function copyName(item) {
      var text = String(item.name || '');
      var done = function () { P.toast('已复制名字'); };
      var fail = function () { P.toast('复制失败，请长按列表项手动复制'); };
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text).then(done, fail);
          return;
        }
      } catch (error) { /* 落到下面的兜底 */ }
      try {
        var area = document.createElement('textarea');
        area.value = text;
        area.setAttribute('readonly', 'readonly');
        area.style.position = 'fixed';
        area.style.opacity = '0';
        document.body.appendChild(area);
        area.select();
        var ok = document.execCommand('copy');
        document.body.removeChild(area);
        if (ok) done(); else fail();
      } catch (error) { fail(); }
    }

    /* ---------- 下载 + 进度 ---------- */

    function download(urlNode, weightNode, button) {
      var url = String(urlNode.value || '').trim();
      if (!url) { P.toast('请填写 Civitai 链接'); return; }
      var weight = Number(weightNode.value);
      if (!isFinite(weight) || weight <= 0) weight = 1.0;
      weight = Math.min(1.5, Math.max(0.1, weight));      // 界面上就按 0.1–1.5 夹住
      weightNode.value = String(weight);
      button.disabled = true;
      P.api('/api/lora/download', { body: { url: url, weight: weight, scope: P.scope() } }).then(function (started) {
        button.disabled = false;
        if (started && started.quest) P.toast('任务 #' + started.quest + ' 已下达');
        watchProgress(true);
      }, function (error) {
        button.disabled = false;
        P.toast(message(error));
      });
    }

    /**
     * 这次任务算不算"正在进行"。
     *
     * <p>新字段 `active` **有就用它**（服务端代理本轮追加的），没有就退回 M3 就在用的
     * `busy || downloading` —— 老机器人上照样跑，新机器人上语义更准（暂停时 active 仍是 true）。
     */
    function jobActive(data) {
      if (!data) return false;
      if (typeof data.active === 'boolean') return data.active;
      return !!(data.busy || data.downloading);
    }

    /** 服务端还没这个接口：404（`error.status`）或回一段"未知接口"。 */
    function isMissingEndpoint(error) {
      if (!error) return false;
      if (Number(error.status) === 404) return true;
      return /未知接口|未知的接口|HTTP 404|not found/i.test(String(error.message || ''));
    }

    /**
     * 轮询 /api/lora/progress 画进度：有 metered/percent 就画百分比，没有（正在读模型信息/正在加载）
     * 就退回不确定态——不能显示成 0%，那会让人以为卡死了。
     */
    function watchProgress(force) {
      if (state.progress) { if (!force) return; state.progress(); state.progress = null; }
      var misses = 0, grace = 0, seenRunning = false;
      state.progress = P.pollWhileVisible(function () {
        return P.api('/api/lora/progress', { body: { scope: P.scope() } }).then(function (data) {
          misses = 0;
          var running = jobActive(data);
          if (running) { seenRunning = true; grace = 0; }
          else if (!seenRunning && ++grace <= 20) return data;   // 空窗期：先不画也不收手
          paint(data, running);
          if (!running) {
            if (state.progress) { state.progress(); state.progress = null; }
            load();
          }
          return data;
        }, function () {
          if (++misses >= 3 && state.progress) { state.progress(); state.progress = null; }
          return null;
        });
      }, 1000);
    }

    function paint(data, running) {
      var box = state.nodes.progress;
      if (state.hideTimer) { clearTimeout(state.hideTimer); state.hideTimer = null; }
      var hasPercent = data && typeof data.percent === 'number' && isFinite(data.percent);
      var paused = !!(data && data.paused);
      box.hidden = false;
      if (hasPercent) {
        box.classList.remove('lo-indeterminate');
        state.nodes.fill.style.width = Math.max(0, Math.min(100, data.percent)).toFixed(1) + '%';
      } else {
        box.classList.add('lo-indeterminate');
        state.nodes.fill.style.width = '';
      }
      var line = '';
      if (hasPercent) {
        line = data.percent.toFixed(1) + '%';
        if (data.done && data.total) line += '（' + fmtBytes(data.done) + ' / ' + fmtBytes(data.total) + '）';
        if (data.speed > 0) line += ' · ' + fmtBytes(data.speed) + '/s';
        if (data.etaSeconds > 0) line += ' · 剩余约 ' + Math.round(data.etaSeconds) + ' 秒';
      }
      var stage = String((data && (data.stage || data.status)) || (running ? '正在处理…' : '已结束')).trim();
      var first = stage.split('\n')[0].trim();
      state.nodes.ptext.textContent = (line ? line + ' · ' : '') + first + (paused ? '（已暂停）' : '');
      renderControls(data, running);
      // 结束后让最后一行（"下载成功…"）停一会儿再收起来，别一闪而过（和桌面版同一套脾气）。
      if (!running) {
        state.hideTimer = setTimeout(function () {
          state.hideTimer = null;
          box.hidden = true;
          box.classList.remove('lo-indeterminate');
          if (state.nodes.ctrl) state.nodes.ctrl.hidden = true;
        }, 6000);
      }
    }

    /**
     * 「暂停 / 继续」「取消」的可用性。三档：
     *   ① 撞过 404（`state.ctrlDead`）→ 全部置灰 + 一句人话（服务端没就绪）；
     *   ② 这次 progress 里**一个新字段都没有**（老机器人）→ 同样置灰，但话不一样（不敢乱猜状态）；
     *   ③ 有新字段 → 按钮可用；`cancellable === false` 时单独灰掉「取消」（例如取消会拖很久）。
     * 只有真的在跑（`running`）时才露出来，跑完就收 —— 免得用户对着一个"能点的取消"发呆。
     */
    function renderControls(data, running) {
      var nodes = state.nodes;
      if (!nodes.ctrl) return;
      state.lastData = data;
      state.lastRunning = !!running;
      if (!running) {
        nodes.ctrl.hidden = true;
        if (nodes.ctrlHint) nodes.ctrlHint.hidden = true;
        return;
      }
      nodes.ctrl.hidden = false;
      var paused = !!(data && data.paused);
      nodes.pause.textContent = paused ? '继续' : '暂停';
      var knowsFields = !!(data && (typeof data.active === 'boolean' || typeof data.paused === 'boolean'
        || typeof data.cancellable === 'boolean'));
      var usable = !state.ctrlDead && knowsFields;
      nodes.pause.disabled = !usable;
      nodes.cancel.disabled = !usable || (data && data.cancellable === false);
      var hint = '';
      if (state.ctrlDead) hint = '这个机器人还没有暂停/取消接口（HTTP 404），按钮先置灰；下载照常跑完。';
      else if (!knowsFields) hint = '当前进度里没有「暂停/取消」信息（服务端未就绪），按钮先置灰。';
      else if (data && data.cancellable === false) hint = '这次任务报的是不可取消（可能已经写进 LoRA 目录了）。';
      if (nodes.ctrlHint) {
        nodes.ctrlHint.hidden = !hint;
        nodes.ctrlHint.textContent = hint;
      }
    }

    /** 暂停 / 继续：发 `/api/lora/pause` 或 `/api/lora/resume`，两边都回一份 progress 形状的 JSON。 */
    function togglePause(button) {
      var paused = !!(state.lastData && state.lastData.paused);
      var path = paused ? '/api/lora/resume' : '/api/lora/pause';
      button.disabled = true;
      P.api(path, { body: { scope: P.scope() } }).then(function (data) {
        button.disabled = false;
        if (data && data.message) P.toast(String(data.message));
        else P.toast(paused ? '已继续下载' : '已暂停下载');
        if (data) paint(data, jobActive(data));
        watchProgress(true);
      }, function (error) {
        button.disabled = false;
        controlFailed(error, paused ? '继续' : '暂停');
      });
    }

    /**
     * 取消：**先二次确认**（说清楚"会删掉已下载的部分"），确认了才发请求。
     * 取消前一个字节都不发 —— 和本地删除/清空是同一套纪律。
     */
    function cancelDownload(button) {
      P.confirm({
        title: '取消这次下载',
        text: '取消这次 LoRA 下载？已经下载的部分会被删掉，下次要从头再来。',
        ok: '取消下载',
        danger: true
      }).then(function (yes) {
        if (!yes) return;
        button.disabled = true;
        P.api('/api/lora/cancel', { body: { scope: P.scope() } }).then(function (data) {
          button.disabled = false;
          P.toast(data && data.message ? String(data.message) : '已取消下载');
          if (data) paint(data, jobActive(data));
          watchProgress(true);
        }, function (error) {
          button.disabled = false;
          controlFailed(error, '取消');
        });
      });
    }

    /** 控制键失败：404 就置灰 + 说人话（不弹错误框、不白屏），别的错照实报。 */
    function controlFailed(error, what) {
      if (isMissingEndpoint(error)) {
        state.ctrlDead = true;
        P.toast('这个机器人还没有「' + what + '」接口（服务端未就绪），按钮先置灰。');
        renderControls(state.lastData, state.lastRunning);
        return;
      }
      P.toast(what + '失败：' + message(error));
    }

    /* ---------- 契约入口 ---------- */

    /**
     * 深度链接兜底（核心的一个坑，交付报告里点了名）。
     *
     * app.js 虽然是 defer，但它在 IIFE 末尾就 boot()（defer 脚本执行时 readyState 已经是
     * 'interactive'，"loading" 判断不成立，所以 boot 立刻跑），**比 screen-*.js 的 register() 早**。
     * 于是 applyRoute() 先给 #/loras 画了一张「还没做好」占位并把 .active 挂在占位上；等这一屏
     * register() 进来时，核心只 ensureMounted() 建了真屏，既没给真屏 .active、也没摘掉那个占位 ——
     * 真屏元素在、数据也拉了，却一直 display:none（尺寸为 0，用户看不见）。
     *
     * 这里只在"当前路由就是这一屏、而这一屏没 active"时才动手；核心补好之后它自动变成空操作。
     */
    function takeOverRoute(root) {
      if (typeof P.current !== 'function' || P.current() !== 'loras') return;
      if (!root || root.classList.contains('active')) return;
      var stale = document.querySelector('#m-main .screen.active');
      if (stale && stale !== root) stale.classList.remove('active');
      root.classList.add('active');
    }

    return {
      title: 'LoRA',
      mount: function (root) {
        injectStyle();
        state.mounted = true;
        state.seq++;
        state.inflight = null;
        state.filter = '';
        state.civSeq++;                       // 作废在飞的搜索
        state.civBusy = false;
        state.civItems = [];
        state.civPage = 0;
        state.civHasMore = false;
        state.scrollBound = false;
        state.ctrlDead = false;               // 重新挂载时再给服务端一次机会
        if (state.hideTimer) { clearTimeout(state.hideTimer); state.hideTimer = null; }
        clear(root);
        root.appendChild(build());
        setMode('local');                     // 默认本地：M3 的老路径一点没动
        load();
        takeOverRoute(root);
      },
      refresh: function () {
        if (!state.mounted) return null;
        if (state.mode === 'civitai' && state.civQuery) {
          loadCivitaiStatus(true);            // 刷新时把 Cookie 状态也重读一遍（这是实时读数，不该缓存）
          return searchCivitai(1);
        }
        return load();
      }
    };
  }

  /* ───────────────────────── 注册（等核心就绪） ───────────────────────── */

  var done = false;
  function boot() {
    if (done) return true;
    var P = window.PixikoM;
    if (!P || typeof P.register !== 'function') return false;
    done = true;
    P.register('loras', setup(P));
    return true;
  }

  if (!boot()) {
    var tries = 0;
    var timer = setInterval(function () {
      if (boot() || ++tries > 120) clearInterval(timer);
    }, 50);
  }
})();
