/*
 * Pixiko 手机端 —— 「提示词」屏（M3）。
 *
 * 一套按 App 习惯重写的移动界面，和桌面控制台各写各的 DOM，但**共用同一套 /api 接口**
 * （/api/prompt、/api/prompt/edit、/api/tags、/api/meanings、/api/usage）。
 * 只依赖 M1 的核心契约 window.PixikoM.*，不自己造全局、不改 app.css（样式在文件顶部注入，选择器一律 .pr- 前缀）。
 *
 * 与桌面版的交互差异（移动端把「多动作/长文本」收进折叠与 sheet）：
 *   · 桌面是「两张直立卡片 + 底下一张『整段保存/回退』卡」：正向的保存/清空按钮在正向卡里，
 *     反向的在反向卡里，回退只在第三张卡上。手机上每块自带「保存 / 清空 / 撤销」，块头可折叠，
 *     收起后只留一行，正好一屏放下正反两段。
 *   · 桌面每个词条 chip **点整块**即删除（容易误触）；手机上 chip 本体只显示，删除是右侧独立的
 *     ×（44px 触控区），误触面小得多。
 *   · 桌面的 Tab 补全依赖物理键盘（Tab/↑↓/Esc）；手机没有 Tab 键，改成「输入即出候选列表 + 点一条即加入」。
 *   · 桌面用整段 textarea 承载释义显示；手机在词条下方就地补一行小字，另有「查释义」小块列出结果。
 *   · 词条多时桌面把 200 个 chip 一次性铺开；手机只铺前 40 个，其余折在「显示全部」后面。
 *   · 桌面 `prompt-remove-btn`（按词条移除）在手机上由 chip 的 × 覆盖，不另设按钮。
 *
 * 纪律：本文件只发读接口 + /api/prompt/edit，绝不拼指令、不碰指令通道（.progen 除外，见文件末尾，
 * 它是桌面提示词页的既有功能，单独折叠、用户主动点才发）。
 */
(function () {
  'use strict';

  /* ───────────────────────── 样式（一次性注入，全部 .pr- 前缀） ───────────────────────── */

  /* 排版尺度（与 app.css 的移动端令牌同值，令牌缺失时用兜底值，见任务书「统一排版规格」）：
       正文/参数值 15px · 说明 13px · 标签/胶囊 12px（下限） · 行高 1.5 / 1.45
       单行控件 ≥44px · 卡片内边距 14px · 圆角 14px · 卡片间距 12px · 行距 10px
     本轮重点（提示词屏）：**词条必须完整可读** —— 词条、LoRA 标签一律允许换行、绝不 ellipsis
     单行剪裁（`.pr-term` 原先 `max-width:38vw` + `nowrap` + `ellipsis`，49 字的 danbooru 长 tag
     会被剪掉 143px，等于看不到自己写了什么）。 */
  var CSS = [
    '.pr-wrap{display:flex;flex-direction:column;gap:12px;padding:0 0 96px;}',
    '.pr-note{font-size:13px;line-height:1.45;color:var(--muted,#93a4c4);padding:0 4px;}',
    '.pr-block{background:#131c2e;border-radius:14px;overflow:hidden;border:1px solid #1d2942;}',
    '.pr-head{display:flex;align-items:center;gap:8px;width:100%;min-height:44px;padding:10px 14px;',
    'background:none;border:0;color:#e8eefc;font-size:15px;font-weight:600;line-height:1.5;text-align:left;cursor:pointer;font-family:inherit;}',
    '.pr-head:active{background:#1a2540;}',
    '.pr-head .pr-chev{margin-left:auto;flex:none;color:#93a4c4;transition:transform .18s ease;}',
    '.pr-block.pr-collapsed .pr-chev{transform:rotate(-90deg);}',
    '.pr-block.pr-collapsed .pr-panel{display:none;}',
    '.pr-count{font-size:12px;font-weight:400;color:#93a4c4;white-space:nowrap;font-variant-numeric:tabular-nums;}',
    '.pr-panel{display:flex;flex-direction:column;gap:10px;padding:0 14px 14px;}',
    '.pr-input{width:100%;box-sizing:border-box;min-height:76px;max-height:40vh;padding:10px 12px;',
    'background:#0b1220;color:#e8eefc;border:1px solid #26324c;border-radius:10px;font-size:16px;',
    'line-height:1.5;font-family:inherit;resize:none;overflow-y:auto;}',
    '.pr-input:focus{outline:none;border-color:#5aa2ff;}',
    '.pr-actions{display:flex;flex-wrap:wrap;gap:8px;}',
    '.pr-btn{min-height:44px;min-width:44px;padding:0 14px;border-radius:10px;border:1px solid transparent;',
    'background:#5aa2ff;color:#08111f;font-size:15px;font-weight:600;font-family:inherit;cursor:pointer;',
    'display:inline-flex;align-items:center;justify-content:center;}',
    '.pr-btn:active{opacity:.72;}',
    '.pr-btn[disabled]{opacity:.45;}',
    '.pr-btn.pr-ghost{background:#1b2540;color:#cfe0ff;border-color:#2a3a5c;}',
    '.pr-btn.pr-danger{background:#3a1c22;color:#ff6b6b;border-color:#5c2a30;}',
    '.pr-btn.pr-primary{background:#5aa2ff;color:#08111f;}',
    '.pr-add{display:flex;gap:8px;}',
    '.pr-add-input{flex:1;min-width:0;box-sizing:border-box;height:44px;padding:0 12px;background:#0b1220;',
    'color:#e8eefc;border:1px solid #26324c;border-radius:10px;font-size:16px;font-family:inherit;}',
    '.pr-add-input:focus{outline:none;border-color:#5aa2ff;}',
    '.pr-sug{display:flex;flex-direction:column;background:#0b1220;border:1px solid #26324c;border-radius:10px;overflow:hidden;}',
    '.pr-sug-item{display:flex;align-items:center;flex-wrap:wrap;gap:2px 8px;min-height:44px;padding:6px 12px;background:none;',
    'border:0;border-top:1px solid #1a2338;color:#e8eefc;font-size:15px;line-height:1.5;font-family:inherit;text-align:left;cursor:pointer;}',
    '.pr-sug-item:first-child{border-top:0;}',
    '.pr-sug-item:active{background:#1a2540;}',
    '.pr-sug-item .pr-sug-tag{font-weight:600;min-width:0;overflow-wrap:anywhere;word-break:break-word;}',
    '.pr-sug-item .pr-sug-zh{color:#93a4c4;font-size:13px;line-height:1.45;min-width:0;overflow-wrap:anywhere;word-break:break-word;}',
    '.pr-sug-item .pr-sug-rank{margin-left:auto;color:#5aa2ff;font-size:12px;font-variant-numeric:tabular-nums;}',
    '.pr-meanbox{background:#0b1220;border:1px solid #26324c;border-radius:10px;padding:8px 12px;}',
    '.pr-meanbox .pr-mean-head{display:flex;align-items:center;gap:8px;color:#93a4c4;font-size:13px;line-height:1.45;margin-bottom:6px;}',
    '.pr-meanbox .pr-mean-row{display:flex;flex-wrap:wrap;gap:2px 8px;font-size:15px;line-height:1.5;color:#e8eefc;}',
    '.pr-meanbox .pr-mean-row .pr-mean-term{color:#cfe0ff;font-weight:600;min-width:0;max-width:100%;',
    'overflow-wrap:anywhere;word-break:break-word;}',
    '.pr-meanbox .pr-mean-row .pr-mean-zh{color:#93a4c4;font-size:13px;line-height:1.45;min-width:0;',
    'overflow-wrap:anywhere;word-break:break-word;}',
    '.pr-chips{display:flex;flex-wrap:wrap;gap:6px;}',
    /* 词条胶囊 = 2×2 网格：行1 = 序号 + 词条，行2 = 中文释义（占满第一列）。
       这样词条折几行都不会把释义挤到中间，释义永远在词条下面自成一行（原先是 inline-flex +
       `max-width:38vw` + `nowrap` + `ellipsis`，49 字的 danbooru 长 tag 会被剪掉 143px）。 */
    '.pr-chip{display:grid;grid-template-columns:auto minmax(0,1fr) auto;grid-template-areas:"num term x" ". mean x";',
    'align-items:center;column-gap:6px;row-gap:1px;max-width:100%;box-sizing:border-box;background:#1b2540;',
    'border:1px solid #2a3a5c;border-radius:10px;padding:5px 0 5px 10px;overflow:visible;}',
    '.pr-chip .pr-num{grid-area:num;color:#5aa2ff;font-size:12px;font-variant-numeric:tabular-nums;align-self:start;}',
    '.pr-chip .pr-term{grid-area:term;color:#e8eefc;font-size:15px;line-height:1.5;min-width:0;white-space:normal;',
    'overflow:visible;text-overflow:clip;overflow-wrap:anywhere;word-break:break-word;}',
    '.pr-chip .pr-mean{grid-area:mean;color:#93a4c4;font-size:13px;line-height:1.45;min-width:2ch;white-space:normal;',
    'overflow:visible;text-overflow:clip;overflow-wrap:anywhere;word-break:break-word;}',
    '.pr-chip .pr-mean.pr-pending{opacity:.45;}',
    '/* 释义还在路上时：空元素 + 2ch 最小宽（不塞 "…" 文本 —— 那个占位在窄屏会被压到 7px 宽，',
    '   自己变成一处横向裁切；也不画任何字符，免得词条下面留一行看得见的空点）。 */',
    '.pr-chip .pr-mean.pr-pending:empty{min-width:2ch;}',
    '.pr-x{grid-area:x;flex:none;width:44px;height:44px;display:inline-flex;align-items:center;justify-content:center;',
    'background:none;border:0;color:#93a4c4;font-size:20px;line-height:1;font-family:inherit;cursor:pointer;}',
    '.pr-x:active{background:#3a1c22;color:#ff6b6b;}',
    '.pr-empty{color:#93a4c4;font-size:15px;line-height:1.5;padding:8px 2px;}',
    '.pr-err{color:#ff6b6b;font-size:15px;line-height:1.5;padding:10px 2px;overflow-wrap:anywhere;word-break:break-word;}',
    '.pr-loading{color:#93a4c4;font-size:15px;line-height:1.5;padding:16px 4px;text-align:center;}',
    '.pr-pre{margin:0;padding:10px 12px;background:#0b1220;border:1px solid #26324c;border-radius:10px;',
    'color:#cfe0ff;font-size:13px;line-height:1.5;white-space:pre-wrap;word-break:break-word;max-height:44vh;overflow:auto;}',
    '.pr-hint{color:#93a4c4;font-size:13px;line-height:1.45;padding:0 2px;}',
    '/* 外壳把 body 设成 user-select:none；输入框必须能选中文本，否则没法改提示词。 */',
    '.pr-input,.pr-add-input{user-select:text;-webkit-user-select:text;}'
  ].join('');

  function injectStyle() {
    if (document.getElementById('pr-style')) return;
    var style = document.createElement('style');
    style.id = 'pr-style';
    style.textContent = CSS;
    document.head.appendChild(style);
  }

  /* ───────────────────────── 小工具 ───────────────────────── */

  var CHIP_PAGE = 40;          // 一屏最多铺 40 个 chip，其余折进「显示全部」
  var SUG_LIMIT = 8;           // 补全候选最多 8 条

  /**
   * 按逗号拆词条 —— 与后端 `PromptEditor.parts` 同规则：`(`/`[`/`<` 里的逗号不算分隔符，
   * `\` 转义下一个字符，空白词条丢弃。前端只在后端没给 positiveTerms/negativeTerms 时兜底用，
   * 但规则必须一致，否则 chips 数会和后端的词条数对不上账。
   */
  function splitTerms(text) {
    var result = [];
    var stack = [];
    var start = 0;
    var escaped = false;
    var value = text == null ? '' : String(text);
    for (var i = 0; i < value.length; i++) {
      var ch = value.charAt(i);
      if (escaped) { escaped = false; continue; }
      if (ch === '\\') { escaped = true; continue; }
      if (ch === '(' || ch === '[' || ch === '<') { stack.push(ch); continue; }
      if (ch === ')' || ch === ']' || ch === '>') { stack.pop(); continue; }
      if ((ch === ',' || ch === '，') && !stack.length) {
        pushTerm(result, value.slice(start, i));
        start = i + 1;
      }
    }
    pushTerm(result, value.slice(start));
    return result;
  }

  function pushTerm(list, raw) {
    var term = String(raw == null ? '' : raw).replace(/^\s+|\s+$/g, '');
    if (term) list.push(term);
  }

  /** 一份 /api/prompt 数据里某一侧的词条列表（优先用后端算好的，保证和后端同一个口径）。 */
  function termsOf(data, side) {
    var items = side === 'negative' ? data.negativeItems : data.positiveItems;
    if (items && items.length) {
      return items.map(function (entry, index) {
        return (entry && typeof entry === 'object')
          ? { term: String(entry.term == null ? '' : entry.term), meaning: String(entry.meaning == null ? '' : entry.meaning), number: entry.number || index + 1 }
          : { term: String(entry), meaning: '', number: index + 1 };
      }).filter(function (item) { return item.term; });
    }
    var plain = side === 'negative' ? data.negativeTerms : data.positiveTerms;
    if (plain && plain.length) {
      return plain.map(function (term, index) { return { term: String(term), meaning: '', number: index + 1 }; });
    }
    return splitTerms(data[side]).map(function (term, index) { return { term: term, meaning: '', number: index + 1 }; });
  }

  /** 热度：426664 → 43万（和桌面版一个口径）。 */
  function compactCount(value) {
    var number = Number(value) || 0;
    if (number >= 100000000) return (number / 100000000).toFixed(1) + '亿';
    if (number >= 10000) return (number / 10000).toFixed(1) + '万';
    return String(number);
  }

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

  /* ───────────────────────── 屏幕本体 ───────────────────────── */

  function setup(P) {
    var el = typeof P.el === 'function' ? P.el : localEl;
    var clear = typeof P.clear === 'function' ? P.clear : localClear;

    /** 屏幕状态：最近一次 /api/prompt 数据 + 每块是否折叠 + 词条是否铺满 + 每块的释义结果。 */
    var state = {
      data: null,
      collapsed: { positive: false, negative: true },   // 反向默认折叠：一进屏先看正向
      showAll: { positive: false, negative: false },
      meanings: { positive: null, negative: null },
      /**
       * 两段输入框里"用户敲了、还没按保存"的编辑（正/反向各一个标记）。
       *
       * <p>为什么必须有：render(side) 由每一次 load() 触发，而 load() 会把**两段**都重画一遍
       * （add / remove / clear / undo 之后都会重取）。用户在两段里都直接改了文本、只按了其中一侧的
       * 保存时，另一侧刚敲的文本会被服务端那份旧值抹掉，接着按它自己的「保存」存下去的就是旧文本 ——
       * 与控制台提示词面板同一处事故（2026-10-07「提示词直接修改不会应用」）。
       * 判据：**有未提交编辑的那一侧不重画**；只有这次改动落在的那一侧（undo 是两段）才重画。
       */
      edited: { positive: false, negative: false },
      sug: null,          // { side, seq, items }
      seq: 0,             // 请求序号：晚到的响应一律丢掉，别覆盖新的
      inflight: null,
      mounted: false,
      root: null,
      nodes: {}
    };

    /* ---------- DOM 骨架 ---------- */

    function buildBlock(side, title) {
      var block = el('section', 'pr-block');
      block.setAttribute('data-side', side);      // 探针/自测靠它区分正反两块
      var head = el('button', 'pr-head');
      head.type = 'button';
      head.appendChild(el('span', null, title));
      var count = el('span', 'pr-count', '');
      head.appendChild(count);
      head.appendChild(el('span', 'pr-chev', '⌄'));
      head.addEventListener('click', function () {
        state.collapsed[side] = !state.collapsed[side];
        block.classList.toggle('pr-collapsed', state.collapsed[side]);
      });
      block.appendChild(head);

      var panel = el('div', 'pr-panel');
      var input = el('textarea', 'pr-input');
      input.rows = side === 'positive' ? 5 : 3;
      input.spellcheck = false;
      input.placeholder = side === 'positive'
        ? '逐条输入（逗号分隔）'
        : '反向提示词（逗号分隔）';
      input.addEventListener('input', function () { autoGrow(input); state.edited[side] = true; });
      panel.appendChild(input);

      var actions = el('div', 'pr-actions');
      var save = el('button', 'pr-btn pr-primary pr-save', '保存');
      var clear = el('button', 'pr-btn pr-danger pr-clear', '清空');
      var undo = el('button', 'pr-btn pr-ghost pr-undo', '撤销');
      var meaning = el('button', 'pr-btn pr-ghost pr-mean', '查释义');
      [save, clear, undo, meaning].forEach(function (button) { button.type = 'button'; });
      save.addEventListener('click', function () { edit(side, 'set', input.value); });
      clear.addEventListener('click', function () { askClear(side); });
      undo.addEventListener('click', function () { edit(side, 'undo', ''); });
      meaning.addEventListener('click', function () { showMeanings(side); });
      [save, clear, undo, meaning].forEach(function (button) { actions.appendChild(button); });
      panel.appendChild(actions);

      var addRow = el('div', 'pr-add');
      var addInput = el('input', 'pr-add-input');
      addInput.type = 'text';
      addInput.setAttribute('autocomplete', 'off');
      addInput.setAttribute('autocapitalize', 'off');
      addInput.setAttribute('spellcheck', 'false');
      addInput.placeholder = side === 'positive' ? '加词条，输入出候选' : '加反向词条';
      var addBtn = el('button', 'pr-btn pr-ghost pr-add-btn', '加入');
      addBtn.type = 'button';
      addRow.appendChild(addInput);
      addRow.appendChild(addBtn);
      panel.appendChild(addRow);

      var sug = el('div', 'pr-sug');
      sug.hidden = true;
      panel.appendChild(sug);

      var meanBox = el('div', 'pr-meanbox');
      meanBox.hidden = true;
      panel.appendChild(meanBox);

      var chips = el('div', 'pr-chips');
      panel.appendChild(chips);

      var more = el('button', 'pr-btn pr-ghost pr-more');
      more.type = 'button';
      more.hidden = true;
      more.addEventListener('click', function () {
        state.showAll[side] = true;
        renderChips(side);
      });
      panel.appendChild(more);

      block.appendChild(panel);
      state.nodes[side] = {
        block: block, head: head, count: count, input: input, chips: chips,
        more: more, sug: sug, meanBox: meanBox, addInput: addInput, addBtn: addBtn
      };

      // 输入即出候选（手机没有 Tab 键，改成点选）
      addInput.addEventListener('input', function () { scheduleSuggest(side); });
      addInput.addEventListener('keydown', function (event) {
        if (event.key === 'Enter') { event.preventDefault(); submitAdd(side); }
        if (event.key === 'Escape') closeSuggest(side);
      });
      addBtn.addEventListener('click', function () { submitAdd(side); });
      addInput.addEventListener('blur', function () { setTimeout(function () { closeSuggest(side); }, 180); });

      return block;
    }

    function build() {
      var wrap = el('div', 'pr-wrap');
      var note = el('div', 'pr-note', '个人 prompt（本机 JSON）。点词条右侧 × 删除；「保存」整段替换该方向。');
      wrap.appendChild(note);
      var status = el('div', 'pr-status');
      wrap.appendChild(status);
      wrap.appendChild(buildBlock('positive', '正向提示词'));
      wrap.appendChild(buildBlock('negative', '反向提示词'));

      var tools = el('section', 'pr-block pr-collapsed');
      var toolsHead = el('button', 'pr-head');
      toolsHead.type = 'button';
      toolsHead.appendChild(el('span', null, '提示词生成 .progen'));
      toolsHead.appendChild(el('span', 'pr-chev', '⌄'));
      var toolsPanel = el('div', 'pr-panel');
      toolsHead.addEventListener('click', function () { tools.classList.toggle('pr-collapsed'); });
      var hint = el('div', 'pr-hint', '用一句话描述，让 DeepSeek 生成一段提示词。只展示，不自动应用。');
      var progInput = el('textarea', 'pr-input');
      progInput.rows = 2;
      progInput.placeholder = '例如：雨夜的城市街道，一辆红色自行车';
      progInput.addEventListener('input', function () { autoGrow(progInput); });
      var progBtn = el('button', 'pr-btn pr-primary', '生成提示词（只展示不应用）');
      progBtn.type = 'button';
      var progOut = el('pre', 'pr-pre');
      progOut.hidden = true;
      progBtn.addEventListener('click', function () { runProgen(progInput.value, progBtn, progOut); });
      toolsPanel.appendChild(hint);
      toolsPanel.appendChild(progInput);
      var progRow = el('div', 'pr-actions');
      progRow.appendChild(progBtn);
      toolsPanel.appendChild(progRow);
      toolsPanel.appendChild(progOut);
      tools.appendChild(toolsHead);
      tools.appendChild(toolsPanel);
      wrap.appendChild(tools);

      var usage = el('section', 'pr-block pr-collapsed');
      var usageHead = el('button', 'pr-head');
      usageHead.type = 'button';
      usageHead.appendChild(el('span', null, '提示词分类 usage'));
      usageHead.appendChild(el('span', 'pr-chev', '⌄'));
      var usagePanel = el('div', 'pr-panel');
      usageHead.addEventListener('click', function () { usage.classList.toggle('pr-collapsed'); });
      var usageHint = el('div', 'pr-hint', '填分类路径看词条，例如：服饰/上衣、场景、搜索 地铁（留空看顶层）。');
      var usageInput = el('input', 'pr-add-input');
      usageInput.type = 'text';
      usageInput.placeholder = '服饰/上衣、搜索 地铁…';
      var usageBtn = el('button', 'pr-btn pr-ghost', '浏览');
      usageBtn.type = 'button';
      var usageOut = el('pre', 'pr-pre');
      usageOut.hidden = true;
      var usageRow = el('div', 'pr-add');
      usageRow.appendChild(usageInput);
      usageRow.appendChild(usageBtn);
      usageBtn.addEventListener('click', function () { browseUsage(usageInput.value, usageBtn, usageOut); });
      usageInput.addEventListener('keydown', function (event) { if (event.key === 'Enter') browseUsage(usageInput.value, usageBtn, usageOut); });
      usagePanel.appendChild(usageHint);
      usagePanel.appendChild(usageRow);
      usagePanel.appendChild(usageOut);
      usage.appendChild(usageHead);
      usage.appendChild(usagePanel);
      wrap.appendChild(usage);

      state.nodes.status = status;
      return wrap;
    }

    /* ---------- 自动长高 ---------- */

    function autoGrow(area) {
      if (!area) return;
      area.style.height = 'auto';
      var cap = Math.round(window.innerHeight * 0.4);
      area.style.height = Math.min(area.scrollHeight + 2, cap) + 'px';
    }

    /* ---------- 状态条（加载 / 错误 / 空） ---------- */

    function showStatus(text, kind) {
      var box = state.nodes.status;
      clear(box);
      if (!text) { box.hidden = true; return; }
      box.hidden = false;
      box.appendChild(el('div', kind === 'error' ? 'pr-err' : 'pr-loading', text));
    }

    /* ---------- 读 /api/prompt ---------- */

    function load() {
      if (state.inflight) return state.inflight;
      var seq = ++state.seq;
      showStatus('正在读取提示词…');
      state.inflight = P.api('/api/prompt', { body: { scope: P.scope() } }).then(function (data) {
        state.inflight = null;
        if (seq !== state.seq) return data;
        state.data = data || {};
        for (var side in state.meanings) state.meanings[side] = null;
        showStatus('');
        render('positive');
        render('negative');
        return data;
      }, function (error) {
        state.inflight = null;
        if (seq !== state.seq) return null;
        showStatus('读取失败：' + message(error), 'error');
        return null;
      });
      return state.inflight;
    }

    function message(error) {
      return (error && error.message) ? String(error.message) : String(error || '未知错误');
    }

    /* ---------- 渲染一侧 ---------- */

    function render(side) {
      var data = state.data || {};
      var node = state.nodes[side];
      var terms = termsOf(data, side);

      // 用户在这一段里敲了还没保存就一个字都不写（见 state.edited）：load() 会把两段都重画一遍，
      // 抹掉另一段刚敲的文本就是「直接修改不会应用」那处事故。
      if (!state.edited[side]) node.input.value = data[side] == null ? '' : String(data[side]);
      autoGrow(node.input);
      node.count.textContent = terms.length + ' 个词条';
      node.block.classList.toggle('pr-collapsed', !!state.collapsed[side]);

      renderChips(side);

      // 释义只是锦上添花：只查词库与缓存（learn:false），绝不在这里问 DeepSeek 拖慢面板。
      var missing = terms.filter(function (item) { return !item.meaning; })
        .map(function (item) { return item.term; })
        .slice(0, 30);
      if (missing.length) fillMeanings(side, missing);
    }

    function renderChips(side) {
      var node = state.nodes[side];
      var terms = termsOf(state.data || {}, side);
      var stored = state.meanings[side] || {};
      clear(node.chips);

      if (!terms.length) {
        node.chips.appendChild(el('div', 'pr-empty', '（这一段还没有词条）'));
        node.more.hidden = true;
        return;
      }

      var shown = state.showAll[side] ? terms : terms.slice(0, CHIP_PAGE);
      shown.forEach(function (item) {
        var chip = el('span', 'pr-chip');
        chip.setAttribute('data-term', item.term);
        chip.appendChild(el('span', 'pr-num', '#' + item.number));
        chip.appendChild(el('span', 'pr-term', item.term));
        var meaning = item.meaning || stored[item.term] || '';
        // 释义没到之前**不塞 "…" 文本**：那个占位在窄屏会被 flex 压到 7px 宽、自己变成一处
        // 横向裁切（scrollW 10 / clientW 7）。改成空元素 + CSS :empty::before 画一个不参与
        // 布局测量的占位点，标签宽度就由 min-width 稳住。
        var zh = el('span', 'pr-mean' + (meaning ? '' : ' pr-pending'), meaning);
        zh.setAttribute('data-meaning', meaning || '');
        chip.appendChild(zh);
        var x = el('button', 'pr-x', '×');
        x.type = 'button';
        x.setAttribute('aria-label', '删除词条');
        x.addEventListener('click', function () { edit(side, 'remove', item.term); });
        chip.appendChild(x);
        node.chips.appendChild(chip);
      });

      var rest = terms.length - shown.length;
      if (rest > 0) {
        node.more.hidden = false;
        node.more.textContent = '显示全部（还有 ' + rest + ' 个）';
      } else {
        node.more.hidden = true;
      }
    }

    /* ---------- 释义 ---------- */

    function fillMeanings(side, terms) {
      var seq = state.seq;
      P.api('/api/meanings', { body: { terms: terms, learn: false } }).then(function (data) {
        if (seq !== state.seq) return;
        var found = (data && data.meanings) || {};
        var box = state.meanings[side] || (state.meanings[side] = {});
        var changed = false;
        for (var term in found) if (found[term]) { box[term] = found[term]; changed = true; }
        if (changed) renderChips(side);
      }, function () { /* 释义拿不到就算了，不影响面板 */ });
    }

    /** 「查释义」：用户主动要，这次允许问一次 DeepSeek（learn:true），结果列在小块里。 */
    function showMeanings(side) {
      var node = state.nodes[side];
      var terms = termsOf(state.data || {}, side).map(function (item) { return item.term; }).slice(0, 40);
      if (!terms.length) { P.toast('这一段还没有词条'); return; }
      node.meanBox.hidden = false;
      clear(node.meanBox);
      node.meanBox.appendChild(el('div', 'pr-mean-head', '正在查 ' + terms.length + ' 个词条的释义…'));
      var seq = state.seq;
      P.api('/api/meanings', { body: { terms: terms, learn: true } }).then(function (data) {
        if (seq !== state.seq) return;
        var found = (data && data.meanings) || {};
        if (!Object.keys(found).length) {
          node.meanBox.appendChild(el('div', 'pr-mean-row', '词库里没有这些词条的中文释义。'));
          return;
        }
        Object.keys(found).forEach(function (term) {
          var row = el('div', 'pr-mean-row');
          row.appendChild(el('span', 'pr-mean-term', term));
          row.appendChild(el('span', 'pr-mean-zh', found[term]));
          node.meanBox.appendChild(row);
        });
        var unresolved = (data && data.unresolved) || [];
        if (unresolved.length) {
          node.meanBox.appendChild(el('div', 'pr-mean-head', '另有 ' + unresolved.length + ' 条查不到中文。'));
        }
        var box = state.meanings[side] || (state.meanings[side] = {});
        for (var term in found) box[term] = found[term];
        renderChips(side);
      }, function (error) {
        clear(node.meanBox);
        node.meanBox.appendChild(el('div', 'pr-err', '查释义失败：' + message(error)));
      });
    }

    /* ---------- 补全候选（/api/tags） ---------- */

    function scheduleSuggest(side) {
      var node = state.nodes[side];
      var query = node.addInput.value.trim();
      if (!query) { closeSuggest(side); return; }
      if (state.sug && state.sug.timer) clearTimeout(state.sug.timer);
      state.sug = { side: side, timer: setTimeout(function () { querySuggest(side, query); }, 160), seq: 0 };
    }

    function querySuggest(side, query) {
      var node = state.nodes[side];
      var seq = ++state.seq;
      P.api('/api/tags', { body: { query: query, limit: SUG_LIMIT } }).then(function (data) {
        if (seq !== state.seq) return;
        // 后端给的是 {tags:[{tag,zh,category,rank}]}；也认 {terms:[…]}，两种都吃。
        var raw = (data && data.tags) || (data && data.terms) || [];
        var items = raw.map(function (entry) {
          if (entry && typeof entry === 'object') {
            return { tag: String(entry.tag || entry.term || ''), zh: String(entry.zh || entry.meaning || ''), rank: Number(entry.rank) || 0 };
          }
          return { tag: String(entry), zh: '', rank: 0 };
        }).filter(function (item) { return item.tag; }).slice(0, SUG_LIMIT);
        renderSuggest(side, items);
      }, function () { closeSuggest(side); });
    }

    function renderSuggest(side, items) {
      var node = state.nodes[side];
      clear(node.sug);
      if (!items.length) { node.sug.hidden = true; return; }
      items.forEach(function (item) {
        var button = el('button', 'pr-sug-item');
        button.type = 'button';
        button.appendChild(el('span', 'pr-sug-tag', item.tag));
        if (item.zh) button.appendChild(el('span', 'pr-sug-zh', item.zh));
        if (item.rank) button.appendChild(el('span', 'pr-sug-rank', compactCount(item.rank)));
        // 点一条即加入（手机没有 Tab 键，点选就是「采纳」）
        button.addEventListener('mousedown', function (event) { event.preventDefault(); });
        button.addEventListener('click', function () {
          closeSuggest(side);
          node.addInput.value = '';
          edit(side, 'add', item.tag);
        });
        node.sug.appendChild(button);
      });
      node.sug.hidden = false;
    }

    function closeSuggest(side) {
      var node = state.nodes[side];
      if (state.sug && state.sug.timer) clearTimeout(state.sug.timer);
      state.sug = null;
      clear(node.sug);
      node.sug.hidden = true;
    }

    /** 「加入」按钮 / 回车：把输入框里的内容按词条加入（支持一次输多个，逗号分隔）。 */
    function submitAdd(side) {
      var node = state.nodes[side];
      var value = node.addInput.value.trim();
      if (!value) { P.toast('请先输入词条'); return; }
      closeSuggest(side);
      node.addInput.value = '';
      edit(side, 'add', value);
    }

    /* ---------- 写操作（/api/prompt/edit） ---------- */

    /**
     * 这个动作要不要弹右下角提示：`add` / `remove` / `set` / `clear` / `undo` 一律不弹 ——
     * 提示词屏自己已经就地更新（词条、两段原文、可撤销步数），再弹一下只是打扰
     * （用户 2026-10-07 口径：「对话可以有，但是右下角不要弹出回执提示」）。
     * 认不出的动作照旧弹：宁可多弹，也不静默掉意外情况。
     */
    function quietEditToast(action) {
      var value = String(action == null ? '' : action).trim().toLowerCase();
      return value === 'add' || value === 'remove' || value === 'set' || value === 'clear' || value === 'undo';
    }

    function edit(side, action, value) {
      // 这一次改动落在哪一侧：add / remove / set / clear 只影响 side，undo 是两段一起回退。
      // 清掉标记，下面 load() 才会把服务端确认后的文本写回该侧；另一侧没提交的编辑留着不重画。
      if (side === 'positive' || side === 'negative') state.edited[side] = false;
      if (String(action == null ? '' : action).trim().toLowerCase() === 'undo')
        state.edited = { positive: false, negative: false };
      var body = { side: side, action: action, value: value == null ? '' : String(value), scope: P.scope() };
      return P.api('/api/prompt/edit', { body: body }).then(function (data) {
        if (data && data.message && !quietEditToast(action)) P.toast(data.message);
        // 局部刷新：重取 /api/prompt，只重画这两块，不动整页。
        return load();
      }, function (error) {
        P.toast(message(error));
        return load().catch(function () { return null; });
      });
    }

    function askClear(side) {
      P.confirm({
        title: side === 'positive' ? '清空正向提示词' : '清空反向提示词',
        text: '这一段的提示词会被整段清掉（可以「撤销」找回上一步）。',
        ok: '清空',
        danger: true
      }).then(function (yes) {
        if (yes) edit(side, 'clear', '');
      });
    }

    /* ---------- .progen（桌面提示词页的既有功能，走指令通道） ---------- */

    function runProgen(text, button, out) {
      var value = String(text == null ? '' : text).trim();
      if (!value) { P.toast('请先写一句描述'); return; }
      button.disabled = true;
      out.hidden = false;
      out.textContent = '正在生成…';
      P.api('/api/command', { body: { command: '.progen ' + value, scope: P.scope() } }).then(function (started) {
        var id = started && (started.captureId || started.id);
        if (!id) { out.textContent = started && started.message ? started.message : '已下达，结果见「回执」。'; button.disabled = false; return; }
        var stop = P.pollWhileVisible(function () {
          return P.api('/api/capture', { body: { id: id, scope: P.scope() } }).then(function (capture) {
            var texts = ((capture && capture.texts) || []).filter(function (line) { return line && !/正在通过 DeepSeek/.test(line); });
            if (texts.length) out.textContent = texts[texts.length - 1];
            if (capture && capture.done && !capture.busy) { stop(); button.disabled = false; }
            return capture;
          }, function () { stop(); button.disabled = false; });
        }, 800);
      }, function (error) {
        out.textContent = '生成失败：' + message(error);
        button.disabled = false;
      });
    }

    /* ---------- 提示词分类 usage ---------- */

    function browseUsage(query, button, out) {
      button.disabled = true;
      out.hidden = false;
      out.textContent = '正在读取…';
      P.api('/api/usage', { body: { query: String(query == null ? '' : query).trim(), scope: P.scope() } }).then(function (data) {
        out.textContent = (data && data.text) || '（没有内容）';
        button.disabled = false;
      }, function (error) {
        out.textContent = '读取失败：' + message(error);
        button.disabled = false;
      });
    }

    /* ---------- 契约入口 ---------- */

    /**
     * 深度链接兜底（核心的一个坑，交付报告里点了名）。
     *
     * app.js 虽然是 defer，但它在 IIFE 末尾就 boot()（defer 脚本执行时 readyState 已经是
     * 'interactive'，"loading" 判断不成立，所以 boot 立刻跑），**比 screen-*.js 的 register() 早**。
     * 于是 applyRoute() 先给 #/prompt 画了一张「还没做好」占位并把 .active 挂在占位上；等这一屏
     * register() 进来时，核心只 ensureMounted() 建了真屏，既没给真屏 .active、也没摘掉那个占位 ——
     * 真屏元素在、数据也拉了，却一直 display:none（尺寸为 0，用户看不见）。
     *
     * 这里只在"当前路由就是这一屏、而这一屏没 active"时才动手；核心补好之后它自动变成空操作。
     */
    function takeOverRoute(root) {
      if (typeof P.current !== 'function' || P.current() !== 'prompt') return;
      if (!root || root.classList.contains('active')) return;
      var stale = document.querySelector('#m-main .screen.active');
      if (stale && stale !== root) stale.classList.remove('active');
      root.classList.add('active');
    }

    return {
      title: '提示词',
      mount: function (root) {
        injectStyle();
        state.root = root;
        state.mounted = true;
        state.seq++;
        state.inflight = null;
        state.showAll = { positive: false, negative: false };
        // 新的一次挂载＝新的输入框：上一次留下的"未提交编辑"标记必须一起清掉，
        // 否则新框会一直不被服务端文本填上（见 state.edited）。
        state.edited = { positive: false, negative: false };
        clear(root);
        root.appendChild(build());
        load();
        takeOverRoute(root);
      },
      refresh: function () {
        if (!state.mounted) return null;
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
    P.register('prompt', setup(P));
    return true;
  }

  if (!boot()) {
    var tries = 0;
    var timer = setInterval(function () {
      if (boot() || ++tries > 120) clearInterval(timer);
    }, 50);
  }
})();
