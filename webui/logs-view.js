/* ============================================================================
 * 控制台的「日志文件」视图（webui/logs-view.js）—— 把 logs/ 下**真实存在**的日志同步到 pixiko://console。
 *
 * 用户原话：「这些日志要全部同步到 pixiko://console」—— 指的是磁盘上那些：
 *   bot-YYYYMMDD.log / qq-YYYYMMDD.log / web-YYYYMMDD.log /
 *   prompt-rewrite.log（infix 改写原文）/ prompt-review.log（画面检查原文）/
 *   bot-stdout.log / sd-autostart.log …
 * 它们以前只能去文件系统看，控制台里只有一个"按天按来源取最后 400 行"的摘要。
 *
 * ── 做什么 ──────────────────────────────────────────────────────────────
 *   · 「日志」下拉里除了原来的 全部/QQ 侧/网页侧，**多出一组"日志文件"**：每个真实文件一项
 *     （显示大小 + 修改时间），来自 `POST /api/logs` 的 `files`（服务端枚举 logs/ 目录）；
 *   · 选中一个文件 → **按字节 offset 增量跟随**（`POST /api/logs/tail {name,offset}`）：默认只取尾部
 *     一段（更早的内容在磁盘文件里，界面上有说明），之后每 1.5 秒只取**新增的字节**，追加到终端；
 *   · **滚动锚定**（与对话栏同一套判据，实现在 webui/m/pixiko-sync.js 的 grabAnchor/settleAnchor）：
 *     用户往上滚就**停止跟随**（`scrollTop` 一个像素都不动）并给「回到最新 ↓」，点它才回到最新；
 *   · **已加载的行绝不截断/不重写**：只追加完整行，半个多字节字符留在缓冲区等下一段（中文不会碎）；
 *   · 不做人为的"只取最近 N 行"：行数上限就是终端自己的 1500 行回滚（长跑不让页面卡死），
 *     取的是**字节尾部 256 KB**，界面上明说"更早的在磁盘文件里"。
 *
 * ── 为什么单独一个文件 ──────────────────────────────────────────────────
 * 终端那一套（提示符 + 日志同一股流）在 app.js 里；日志**文件**模式只是它的一个视图。
 * 独立成文件后：改日志视图不用再动 380KB 的 app.js，也不会和别的改动抢同一个文件。
 * 与 app.js 的接口只有 {@code window.__pixikoTerminal} 那三个方法（追加一行 / 量底部 / 滚到最新）。
 * ========================================================================== */
(function () {
  'use strict';

  /** 跟随间隔：1.5 秒（用户给的 1–2 秒区间）。 */
  var FOLLOW_MS = 1500;
  /** 首次选文件时请求的尾部字节数（不传 maxBytes 的兜底在服务端，这里是显式取值，便于界面说明）。 */
  var TAIL_BYTES = 256 * 1024;
  /** 过滤关键字输入后，最多等这么久再重拉（防抖）。 */
  var FILTER_DEBOUNCE_MS = 250;

  var state = {
    files: [],            // 服务端给的 logs/ 目录清单
    name: '',             // 当前跟随的文件
    offset: 0,            // 已经消费到的**字节**偏移
    size: 0,
    pending: '',          // 还没凑成整行的尾巴（半截多字节字符也在这里等）
    timer: null,
    inFlight: false,
    truncated: false,     // 首屏只拿到了尾部一段
    fallbackLines: [],    // 不选文件时（全部/QQ 侧/网页侧）的行，用于过滤重画
    mode: 'lines'         // 'lines' = 老的按来源取行；'file' = 跟随某个日志文件
  };

  function byId(id) { return document.getElementById(id); }
  function el(tag, cls, text) {
    var node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text !== undefined && text !== null && text !== '') node.textContent = String(text);
    return node;
  }
  function token() {
    try { return localStorage.getItem('kotori-webui-token') || ''; } catch (error) { return ''; }
  }
  /** 复用控制台的鉴权口径：同源 fetch + Bearer（与 app.js 的 api() 完全一致）。 */
  function api(path, body) {
    return fetch(path, {
      method: 'POST',
      headers: Object.assign({ 'Content-Type': 'application/json' }, token() ? { Authorization: 'Bearer ' + token() } : {}),
      body: JSON.stringify(body || {})
    }).then(function (response) {
      return response.text().then(function (text) {
        var payload = {};
        try { payload = text ? JSON.parse(text) : {}; } catch (error) { payload = { error: text }; }
        if (!response.ok) {
          var failure = new Error(payload.error || ('HTTP ' + response.status));
          failure.status = response.status;
          throw failure;
        }
        return payload;
      });
    });
  }

  function term() { return window.__pixikoTerminal || null; }
  function fmtBytes(n) {
    var value = Number(n) || 0;
    if (value < 1024) return value + ' B';
    if (value < 1024 * 1024) return (value / 1024).toFixed(1) + ' KB';
    if (value < 1024 * 1024 * 1024) return (value / 1024 / 1024).toFixed(1) + ' MB';
    return (value / 1024 / 1024 / 1024).toFixed(2) + ' GB';
  }
  function fmtWhen(iso) {
    if (!iso) return '';
    var at = new Date(iso);
    if (isNaN(at.getTime())) return '';
    var pad = function (n) { return (n < 10 ? '0' : '') + n; };
    return pad(at.getMonth() + 1) + '-' + pad(at.getDate()) + ' ' + pad(at.getHours()) + ':' + pad(at.getMinutes());
  }

  /* ── 「回到最新 ↓」：一个**常驻**的锚（不参与 1500 行回滚裁剪） ────────── */
  var jump = null;
  function ensureJump() {
    if (jump && jump.parentNode) return jump;
    jump = el('div', 'line sys pixiko-keep');
    jump.hidden = true;
    jump.style.cursor = 'pointer';
    jump.style.fontWeight = '600';
    jump.textContent = '回到最新 ↓';
    jump.addEventListener('click', function () {
      var t = term();
      if (t) t.scrollToBottom();
      jump.hidden = true;
    });
    var body = term() && term().body();
    if (body) body.appendChild(jump);
    return jump;
  }
  function hint(show) { ensureJump(); if (jump) jump.hidden = !show; }

  /** 追加一段**完整行**（半截行留在 state.pending 里，绝不截断已加载的内容）。 */
  function pushText(text) {
    var t = term();
    if (!t || !text) return 0;
    state.pending += text;
    var parts = state.pending.split('\n');
    state.pending = parts.pop();                 // 最后一段可能不完整：留着等下一段
    var filter = currentFilter();
    var count = 0;
    for (var i = 0; i < parts.length; i++) {
      if (filter && parts[i].indexOf(filter) < 0) continue;
      t.append('log', parts[i], { mark: 'file' });
      count++;
    }
    return count;
  }

  /**
   * 过滤关键字变了：**就地显示/隐藏已经加载的行**（不重读、不丢已加载内容、不截断行）。
   * 只碰 `data-log-line="file"` 的行 —— 提示符、系统提示、回显行一个都不动。
   */
  function applyFilter() {
    var body = term() && term().body();
    if (!body) return 0;
    var filter = currentFilter();
    var rows = body.querySelectorAll('[data-log-line="file"]');
    var shown = 0;
    for (var i = 0; i < rows.length; i++) {
      var match = !filter || String(rows[i].textContent || '').indexOf(filter) >= 0;
      rows[i].hidden = !match;
      if (match) shown++;
    }
    return shown;
  }

  function currentFilter() {
    var box = byId('logs-filter');
    return box ? String(box.value || '').trim() : '';
  }

  /* ── 文件清单 → 下拉里的一组选项 ─────────────────────────────────────── */
  function renderOptions() {
    var select = byId('logs-source');
    if (!select) return;
    var keep = select.value;
    // 先删掉上一次的"文件组"（`data-file` 选项），源组（全部/QQ/网页）原样保留。
    Array.prototype.slice.call(select.querySelectorAll('option[data-file]')).forEach(function (option) { option.remove(); });
    var group = null;
    if (state.files.length) {
      group = document.createElement('optgroup');
      group.label = '日志文件（logs/）';
      state.files.forEach(function (item) {
        var option = document.createElement('option');
        option.value = 'file:' + item.name;
        option.setAttribute('data-file', item.name);
        option.textContent = item.name + '　' + fmtBytes(item.size) + '　' + fmtWhen(item.modified);
        group.appendChild(option);
      });
      select.appendChild(group);
    }
    // 当前选中的文件还在就保持选中（例如刚刚刷新过清单）
    if (keep && Array.prototype.some.call(select.options, function (option) { return option.value === keep; })) select.value = keep;
  }

  function loadFiles() {
    return api('/api/logs', { lines: 1, source: 'all' }).then(function (data) {
      var files = Array.isArray(data.files) ? data.files : [];
      files.sort(function (a, b) { return String(b.modified || '').localeCompare(String(a.modified || '')); });
      state.files = files;
      renderOptions();
      return files;
    }).catch(function () { return []; });
  }

  /* ── 跟随一个日志文件：按字节 offset 增量取 ──────────────────────────── */
  function stopFollow() {
    if (state.timer) { clearTimeout(state.timer); state.timer = null; }
  }

  function scheduleFollow() {
    stopFollow();
    state.timer = setTimeout(function () { state.timer = null; tick(); }, FOLLOW_MS);
  }

  function tick() {
    if (!state.name) return;
    var follow = byId('logs-follow');
    if (follow && !follow.checked) return;                  // 用户关掉了「跟随」：停表，按刷新按钮才拉
    if (state.inFlight) { scheduleFollow(); return; }
    state.inFlight = true;
    api('/api/logs/tail', { name: state.name, offset: state.offset })
      .then(function (data) {
        state.inFlight = false;
        if (String(data.name || '') !== state.name) return;  // 期间换了文件：这一拍丢掉
        if (Number(data.size) < state.offset) {              // 文件被轮转/清空：从头来
          state.offset = 0; state.pending = '';
          return openFile(state.name, true);
        }
        state.size = Number(data.size) || state.size;
        var got = pushText(String(data.text || ''));
        state.offset = Number(data.offset) || state.offset;
        var t = term();
        // **滚动锚定**：只有本来就在底部才跟随；用户往上滚过就一次都不动（只提示"回到最新"）。
        if (t && got) {
          if (atBottom()) { t.scrollToBottom(); hint(false); }
          else hint(true);
        }
        scheduleFollow();
      })
      .catch(function (error) {
        state.inFlight = false;
        if (String(error && error.message) === 'unauthorized') return;
        // 出错只提示一次，不刷屏（例如文件刚被删掉）
        if (!state.warned) {
          state.warned = true;
          var t = term();
          if (t) t.append('err', '日志跟随失败：' + (error && error.message ? error.message : error), { keep: true });
        }
        scheduleFollow();
      });
  }

  function atBottom() {
    var t = term();
    return !t || t.atBottom();
  }

  /**
   * 打开一个日志文件：先取**尾部一段**（大文件不整读），再进入增量跟随。
   * `keepTail` = 由"文件被轮转"触发时也贴底（用户本来就在底部）。
   */
  function openFile(name, keepTail) {
    state.name = name;
    state.offset = 0;
    state.pending = '';
    state.warned = false;
    var t = term();
    if (t) {
      t.append('sys', '—— 跟随日志文件 ' + name + '（默认只加载尾部 ' + fmtBytes(TAIL_BYTES) + '；更早的内容在磁盘文件里）——', { keep: true });
    }
    return api('/api/logs/tail', { name: name, maxBytes: TAIL_BYTES }).then(function (data) {
      state.size = Number(data.size) || 0;
      state.offset = Number(data.offset) || 0;
      state.truncated = data.truncated === true;
      pushText(String(data.text || ''));
      // 切文件是用户自己的动作 → 贴到最新（之后一律按锚定判据）
      if (t) t.scrollToBottom();
      hint(false);
      if (keepTail !== false) scheduleFollow();
      return data;
    }).catch(function (error) {
      if (t) t.append('err', '读取日志文件失败：' + (error && error.message ? error.message : error), { keep: true });
      return null;
    });
  }

  /** 下拉变化：选了文件就进文件模式；选回 全部/QQ 侧/网页侧 就交还给 app.js 的行模式。 */
  function onSourceChange() {
    var select = byId('logs-source');
    if (!select) return;
    var value = String(select.value || '');
    stopFollow();
    hint(false);
    if (value.indexOf('file:') !== 0) {
      state.name = '';
      state.pending = '';
      state.mode = 'lines';
      if (term() && term().setFileMode) term().setFileMode(false);
      if (window.__pixikoConsoleReload) window.__pixikoConsoleReload();
      return;
    }
    state.mode = 'file';
    if (term() && term().setFileMode) term().setFileMode(true);
    openFile(value.slice('file:'.length));
  }

  /* ── 挂上去 ──────────────────────────────────────────────────────────── */
  function install() {
    var select = byId('logs-source');
    if (!select || select.getAttribute('data-logs-view') === '1') return false;   // 已经挂过（同一个 DOM）
    select.setAttribute('data-logs-view', '1');
    select.addEventListener('change', onSourceChange);

    var reload = byId('logs-reload');
    if (reload) {
      reload.addEventListener('click', function () {
        loadFiles();
        if (state.mode === 'file' && state.name) tick();     // 手动刷一拍增量
      });
    }
    var filter = byId('logs-filter');
    if (filter) {
      var timer = null;
      filter.addEventListener('input', function () {
        if (state.mode !== 'file') return;                   // 行模式的重画交给 app.js
        if (timer) clearTimeout(timer);
        timer = setTimeout(function () { timer = null; applyFilter(); }, FILTER_DEBOUNCE_MS);
      });
    }
    var follow = byId('logs-follow');
    if (follow) {
      follow.addEventListener('change', function () {
        if (state.mode !== 'file') return;
        if (follow.checked) { tick(); } else { stopFollow(); }
      });
    }
    // 用户滚到底部 → 自动恢复跟随（"滚动到底部时自动跟随"）
    var body = term() && term().body();
    if (body) {
      body.addEventListener('scroll', function () {
        if (state.mode !== 'file') return;
        if (atBottom()) hint(false);
      }, { passive: true });
    }
    loadFiles();
    return true;
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', install);
  } else {
    install();
  }
  // app.js 的 boot() 会重画面板（切栏目回来时 DOM 会重建）：兜一次
  setTimeout(install, 800);
  document.addEventListener('click', function () { setTimeout(install, 300); }, true);
}());
