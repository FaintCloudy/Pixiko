/*
 * Pixiko 手机端 Web UI ·「帮助」「日志」「关于」（M4）。
 *
 * 注册 id：'help'、'logs'、'about'。
 *
 *   POST /api/help   → WebApiController 的 `/api/help` 分支：{"help": Bot.HELP}
 *                      （一大段纯文本，指令行以 `.` 开头——Bot.publicCommands 把 `/x` 换成了 `.x`）
 *   POST /api/logs   → body {source, lines}，source ∈ all|qq|web，lines 默认 200（钳到 1..2000）；
 *                      返回 {lines:[原始日志行], source}。原始行格式见 Log.java：
 *                      `[yyyy-MM-dd HH:mm:ss.SSS] [LEVEL] 正文`，LEVEL ∈ INFO|WARN|ERROR|THINK；
 *                      THINK 是多行输出，续行没有时间戳前缀（这里按上一条的级别接着显示）。
 *                      **Java 没有 sinceSeq 增量拉取**，所以这里是每次都拉最近 N 条再整体比对。
 *
 * 本文件不创建任何全局变量；样式只注入自己 .hp-* 前缀的那一份。
 */
(function () {
  'use strict';

  var STYLE_ID = 'hp-style';

  function injectStyle() {
    if (document.getElementById(STYLE_ID)) return;
    var style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = [
      '.hp-wrap{display:flex;flex-direction:column;gap:14px;padding:12px 12px 28px;box-sizing:border-box}',
      '.hp-card{background:#131c2e;border-radius:14px;padding:14px}',
      '.hp-head{display:flex;align-items:center;gap:10px;margin-bottom:10px}',
      '.hp-head b{font-size:15px;color:#e8eefc;font-weight:600}',
      '.hp-head .hp-hint{font-size:12.5px;color:#93a4c4;margin-left:auto;text-align:right;max-width:52%}',
      '.hp-input{width:100%;box-sizing:border-box;min-height:44px;padding:10px 12px;border-radius:12px;',
      'border:1px solid rgba(147,164,196,.24);background:#0e1626;color:#e8eefc;font-size:16px;font-family:inherit}',
      '.hp-input:focus{outline:none;border-color:#5aa2ff}',
      '.hp-btn{min-height:44px;padding:0 14px;border-radius:12px;border:0;background:#5aa2ff;color:#08101f;',
      'font-size:15px;font-weight:600;font-family:inherit;-webkit-tap-highlight-color:transparent}',
      '.hp-btn:active{opacity:.78}',
      '.hp-btn.ghost{background:rgba(90,162,255,.14);color:#5aa2ff}',
      '.hp-btn.on{background:#5aa2ff;color:#08101f}',
      '.hp-btn[disabled]{opacity:.5}',
      '.hp-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}',
      '.hp-chips{display:flex;gap:8px;flex-wrap:wrap}',
      '.hp-note{font-size:12.5px;color:#93a4c4;line-height:1.6;margin-top:10px;white-space:pre-wrap;word-break:break-word}',
      '.hp-note.bad{color:#ff6b6b}',
      '.hp-sec{border-top:1px solid rgba(147,164,196,.13)}',
      '.hp-sec:first-child{border-top:0}',
      '.hp-sec-head{display:flex;align-items:center;gap:10px;width:100%;box-sizing:border-box;min-height:48px;',
      'padding:10px 2px;background:transparent;border:0;color:#e8eefc;font-size:15.5px;text-align:left;font-family:inherit}',
      '.hp-sec-head:active{background:rgba(90,162,255,.12)}',
      '.hp-sec-title{flex:1 1 auto;min-width:0;font-weight:600;word-break:break-word}',
      '.hp-sec-count{font-size:12px;color:#93a4c4;flex:0 0 auto}',
      '.hp-caret{flex:0 0 16px;width:16px;height:16px;stroke:#93a4c4;fill:none;stroke-width:2;stroke-linecap:round;stroke-linejoin:round;transition:transform .15s}',
      '.hp-sec.open .hp-caret{transform:rotate(90deg)}',
      '.hp-sec-body{display:none;padding:0 2px 12px;font-size:14px;line-height:1.65;color:#dfe8fa;',
      'font-family:Consolas,Menlo,monospace;white-space:pre-wrap;word-break:break-word;overflow-wrap:anywhere}',
      '.hp-sec.open .hp-sec-body{display:block}',
      '.hp-sec-body mark{background:rgba(90,162,255,.32);color:#e8eefc;border-radius:3px}',
      '.hp-state{font-size:13.5px;color:#93a4c4;padding:12px 2px;line-height:1.6}',
      '.hp-state.bad{color:#ff6b6b}',
      '.hp-logbar{display:flex;flex-direction:column;gap:10px}',
      '.hp-logscroll{max-height:58vh;overflow:auto;-webkit-overflow-scrolling:touch;border-radius:10px;background:#0e1626;',
      'padding:8px 6px;margin-top:10px}',
      '.hp-log-line{display:flex;gap:8px;align-items:baseline;white-space:nowrap;overflow-x:auto;',
      'font-family:Consolas,Menlo,monospace;font-size:12.5px;line-height:1.75;padding:1px 2px;border-radius:6px}',
      '.hp-log-time{color:#93a4c4;flex:0 0 auto}',
      '.hp-log-level{flex:0 0 auto;font-weight:700;padding:0 6px;border-radius:6px;background:rgba(147,164,196,.16);color:#93a4c4;font-size:11.5px}',
      '.hp-log-level.lv-INFO{color:#6fb3ff;background:rgba(90,162,255,.16)}',
      '.hp-log-level.lv-WARN{color:#ffcc66;background:rgba(255,204,102,.16)}',
      '.hp-log-level.lv-ERROR{color:#ff6b6b;background:rgba(255,107,107,.18)}',
      '.hp-log-level.lv-THINK{color:#b98cff;background:rgba(185,140,255,.16)}',
      '.hp-log-text{flex:0 0 auto;color:#dfe8fa}',
      '.hp-log-line.cont .hp-log-text{color:#b9c6e0}',
      '.hp-link{display:flex;align-items:center;min-height:48px;color:#5aa2ff;font-size:15px;text-decoration:none}',
      '.hp-link:active{opacity:.7}',
      '.hp-kv{display:flex;gap:10px;font-size:14px;padding:7px 0;border-top:1px solid rgba(147,164,196,.11);line-height:1.5}',
      '.hp-kv:first-child{border-top:0}',
      '.hp-kv-key{flex:0 0 84px;color:#93a4c4}',
      '.hp-kv-val{flex:1 1 auto;color:#e8eefc;word-break:break-all}'
    ].join('');
    document.head.appendChild(style);
  }

  /* ---------------------------------------------------------------- 私有小工具 */

  var api = function () { return window.PixikoM; };

  function whenReady(fn) {
    if (window.PixikoM && typeof window.PixikoM.register === 'function') { fn(window.PixikoM); return; }
    var tries = 0;
    (function wait() {
      tries += 1;
      if (window.PixikoM && typeof window.PixikoM.register === 'function') { fn(window.PixikoM); return; }
      if (tries > 400) { console.warn('[help] PixikoM 未就绪，屏幕未注册'); return; }
      setTimeout(wait, 50);
    })();
  }

  function make(tag, cls, text) {
    var M = api();
    if (M && typeof M.el === 'function') return M.el(tag, cls, text);
    var node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function clear(node) {
    var M = api();
    if (M && typeof M.clear === 'function') { M.clear(node); return; }
    while (node.firstChild) node.removeChild(node.firstChild);
  }

  function toast(text) { var M = api(); if (M && M.toast) M.toast(text); }

  function messageOf(error) { return error ? String(error.message || error) : '未知错误'; }

  function byId(id) { return document.getElementById(id); }

  function button(cls, text, id) {
    var node = make('button', cls, text);
    node.type = 'button';
    if (id) node.id = id;
    return node;
  }

  function card(title, hint) {
    var node = make('div', 'hp-card');
    var head = make('div', 'hp-head');
    head.appendChild(make('b', null, title));
    if (hint) head.appendChild(make('span', 'hp-hint', hint));
    node.appendChild(head);
    return node;
  }

  function copyText(text, label) {
    var value = String(text == null ? '' : text);
    var done = function (ok) { toast(ok ? (label || '已复制') : '复制失败：请长按选择文本'); };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(value).then(function () { done(true); }, function () { done(false); });
      return;
    }
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
      done(ok);
    } catch (error) { done(false); }
  }

  /* ================================================================ 帮助 */

  var hpRoot = null;
  var hpRaw = '';
  var hpSections = [];
  var hpQuery = '';

  function renderHelp(target) {
    hpRoot = target || hpRoot;
    if (!hpRoot) return;
    clear(hpRoot);
    var wrap = make('div', 'hp-wrap');
    wrap.appendChild(helpToolbar());
    var body = make('div', 'hp-card');
    body.id = 'hp-help-body';
    body.appendChild(make('div', 'hp-state', '正在读取帮助…'));
    wrap.appendChild(body);
    hpRoot.appendChild(wrap);
    loadHelp();
  }

  function helpToolbar() {
    var node = card('指令帮助', 'POST /api/help');
    var input = make('input', 'hp-input');
    input.type = 'search';
    input.id = 'hp-help-search';
    input.placeholder = '搜索指令或关键词';
    node.appendChild(input);
    var actions = make('div', 'hp-actions');
    var expand = button('hp-btn ghost', '展开全部', 'hp-help-expand');
    var collapse = button('hp-btn ghost', '收起全部', 'hp-help-collapse');
    var copy = button('hp-btn ghost', '复制全部', 'hp-help-copy');
    var reload = button('hp-btn ghost', '重新读取', 'hp-help-reload');
    expand.addEventListener('click', function () { setAllOpen(true); });
    collapse.addEventListener('click', function () { setAllOpen(false); });
    copy.addEventListener('click', function () { copyText(hpRaw, '帮助全文已复制'); });
    reload.addEventListener('click', function () { loadHelp(); });
    actions.appendChild(expand);
    actions.appendChild(collapse);
    actions.appendChild(copy);
    actions.appendChild(reload);
    node.appendChild(actions);
    var count = make('div', 'hp-note', '');
    count.id = 'hp-help-count';
    node.appendChild(count);
    input.addEventListener('input', function () {
      hpQuery = String(input.value || '').trim();
      renderSections();
    });
    return node;
  }

  function loadHelp() {
    return api().api('/api/help', { method: 'POST', body: {} }).then(function (data) {
      hpRaw = String((data && data.help) || '');
      hpSections = parseHelp(hpRaw);
      renderSections();
      return data;
    }, function (error) {
      var body = byId('hp-help-body');
      if (body) {
        clear(body);
        body.appendChild(make('div', 'hp-state bad', '读取失败：' + messageOf(error)));
        var retry = button('hp-btn ghost', '重试');
        retry.addEventListener('click', function () { loadHelp(); });
        body.appendChild(retry);
      }
      return null;
    });
  }

  /**
   * 帮助文本 → 分节。
   * 1) Markdown 标题（`#`～`######`）优先；
   * 2) 没有标题就看空行分段（段数 > 1 时每段一节）；
   * 3) 真正的 Bot.HELP 两者都没有：按「指令族」（行首 `.chat` / `/prompt` 的第一个词）分组，
   *    非指令行归到「总览」或紧邻的那一节；
   * 4) 全都匹配不上就整段一节（不猜）。
   */
  function parseHelp(text) {
    var raw = String(text || '').replace(/\r\n?/g, '\n');
    if (!raw.trim()) return [{ title: '帮助', lines: [] }];
    var lines = raw.split('\n');

    if (lines.some(function (line) { return /^#{1,6}\s+\S/.test(line); })) {
      var markdown = [];
      var current = null;
      lines.forEach(function (line) {
        var match = line.match(/^(#{1,6})\s+(.*)$/);
        if (match) {
          current = { title: match[2].trim(), level: match[1].length, lines: [] };
          markdown.push(current);
        } else {
          if (!current) { current = { title: '总览', level: 0, lines: [] }; markdown.push(current); }
          current.lines.push(line);
        }
      });
      return markdown.map(trimSection);
    }

    var blocks = raw.split(/\n[ \t]*\n+/).map(function (block) { return block.replace(/\s+$/, ''); })
      .filter(function (block) { return block.trim() !== ''; });
    if (blocks.length > 1) {
      return blocks.map(function (block) {
        var blockLines = block.split('\n');
        var first = String(blockLines[0] || '').trim();
        var head = first.length > 0 && first.length <= 40 ? first : '（续）';
        return trimSection({ title: head, level: 0, lines: head === first ? blockLines.slice(1) : blockLines });
      });
    }

    var groups = [];
    var commandFamily = /^\s*[.\/]([A-Za-z\u4e00-\u9fa5]{1,12})/;
    lines.forEach(function (line) {
      if (!line.trim()) return;
      var match = line.match(commandFamily);
      var family = match ? match[1].toLowerCase() : '';
      var last = groups[groups.length - 1];
      if (family && (!last || last.family !== family)) {
        groups.push({ family: family, title: '.' + family, lines: [line] });
      } else if (last) {
        last.lines.push(line);
      } else {
        groups.push({ family: '', title: '总览', lines: [line] });
      }
    });
    if (!groups.length) return [{ title: '帮助', lines: lines }];
    return groups.map(trimSection);
  }

  function trimSection(section) {
    var lines = section.lines.slice();
    while (lines.length && !String(lines[0]).trim()) lines.shift();
    while (lines.length && !String(lines[lines.length - 1]).trim()) lines.pop();
    return { title: section.title || '（无标题）', level: section.level || 0, lines: lines };
  }

  function renderSections() {
    var body = byId('hp-help-body');
    if (!body) return;
    clear(body);
    var query = hpQuery.toLowerCase();
    var shown = 0;
    var total = 0;
    hpSections.forEach(function (section, index) {
      var titleHit = query && section.title.toLowerCase().indexOf(query) >= 0;
      var lines = section.lines;
      if (query && !titleHit) {
        lines = lines.filter(function (line) { return String(line).toLowerCase().indexOf(query) >= 0; });
      }
      var count = lines.length;
      total += count;
      if (query && !titleHit && !count) return;                 // 整节都没命中：不显示
      shown += 1;
      body.appendChild(sectionNode(section, lines, index, titleHit, !!query));
    });
    if (!shown) body.appendChild(make('div', 'hp-state', '没有匹配「' + hpQuery + '」的内容。'));
    var counter = byId('hp-help-count');
    if (counter) counter.textContent = '共 ' + hpSections.length + ' 节' + (query ? '（命中 ' + shown + ' 节 / ' + total + ' 行）' : '') + '。点标题展开或收起。';
  }

  function sectionNode(section, lines, index, titleHit, forceOpen) {
    var node = make('div', 'hp-sec');
    var open = index === 0 || !!forceOpen;
    if (open) node.className = 'hp-sec open';
    var head = button('hp-sec-head');
    head.setAttribute('data-hp-section', section.title);
    head.setAttribute('aria-expanded', open ? 'true' : 'false');
    var caret = document.createElement('span');
    caret.innerHTML = '<svg class="hp-caret" viewBox="0 0 24 24" aria-hidden="true"><path d="M9 5l7 7-7 7"/></svg>';
    head.appendChild(caret.firstChild);
    var title = make('span', 'hp-sec-title');
    if (titleHit) { highlight(title, section.title, hpQuery); } else title.textContent = section.title;
    head.appendChild(title);
    head.appendChild(make('span', 'hp-sec-count', lines.length + ' 行'));
    head.addEventListener('click', function () {
      var isOpen = node.className.indexOf('open') >= 0;
      node.className = isOpen ? 'hp-sec' : 'hp-sec open';
      head.setAttribute('aria-expanded', isOpen ? 'false' : 'true');
    });
    node.appendChild(head);
    var body = make('div', 'hp-sec-body');
    body.setAttribute('data-hp-lines', String(lines.length));
    if (!lines.length) {
      body.textContent = '（这一节没有正文）';
    } else {
      lines.forEach(function (line, position) {
        if (position) body.appendChild(document.createTextNode('\n'));
        if (hpQuery) highlight(body, String(line), hpQuery); else body.appendChild(document.createTextNode(String(line)));
      });
    }
    node.appendChild(body);
    return node;
  }

  /** 把 line 里的 term 高亮（大小写不敏感）；文本一律走 textContent，不用 innerHTML。 */
  function highlight(host, line, term) {
    var lower = line.toLowerCase();
    var wanted = String(term || '').toLowerCase();
    if (!wanted) { host.appendChild(document.createTextNode(line)); return; }
    var at = 0;
    while (at < line.length) {
      var found = lower.indexOf(wanted, at);
      if (found < 0) { host.appendChild(document.createTextNode(line.slice(at))); break; }
      if (found > at) host.appendChild(document.createTextNode(line.slice(at, found)));
      var mark = document.createElement('mark');
      mark.textContent = line.slice(found, found + wanted.length);
      host.appendChild(mark);
      at = found + wanted.length;
    }
  }

  function setAllOpen(open) {
    var body = byId('hp-help-body');
    if (!body) return;
    var sections = body.querySelectorAll('.hp-sec');
    for (var index = 0; index < sections.length; index++) {
      sections[index].className = open ? 'hp-sec open' : 'hp-sec';
      var head = sections[index].querySelector('.hp-sec-head');
      if (head) head.setAttribute('aria-expanded', open ? 'true' : 'false');
    }
  }

  /* ================================================================ 日志 */

  var lgRoot = null;
  var lgLines = [];
  var lgLevel = '';
  var lgSource = 'all';
  var lgAuto = true;
  var lgStop = null;
  var lgAtBottom = true;
  var lgSignature = '';

  var LOG_LIMIT = 300;

  function renderLogs(target) {
    lgRoot = target || lgRoot;
    if (!lgRoot) return;
    stopLogPoll();
    clear(lgRoot);
    // 重进这一屏时 DOM 是新的：签名要清掉，否则"内容没变"的短路会让新容器一直停在「正在读取…」。
    lgSignature = '';
    var wrap = make('div', 'hp-wrap');
    wrap.appendChild(logToolbar());
    var body = make('div', 'hp-card');
    body.appendChild(logScroll());
    body.appendChild(logFooter());
    wrap.appendChild(body);
    lgRoot.appendChild(wrap);
    if (lgLines.length) renderLines();       // 先把上次的行铺上，网络回来再对齐
    loadLogs();
    startLogPoll();
  }

  function logToolbar() {
    var node = card('日志', 'POST /api/logs');
    var levels = make('div', 'hp-chips');
    [['', '全部'], ['INFO', 'INFO'], ['WARN', 'WARN'], ['ERROR', 'ERROR'], ['THINK', 'THINK']].forEach(function (pair) {
      var chip = button('hp-btn ghost', pair[1], 'hp-log-level-' + (pair[0] || 'all'));
      chip.setAttribute('data-hp-level', pair[0]);
      if (pair[0] === lgLevel) chip.className = 'hp-btn on';
      chip.addEventListener('click', function () {
        lgLevel = pair[0];
        var chips = document.querySelectorAll('#hp-log-levels .hp-btn');
        for (var index = 0; index < chips.length; index++) {
          chips[index].className = chips[index].getAttribute('data-hp-level') === lgLevel ? 'hp-btn on' : 'hp-btn ghost';
        }
        renderLines();
      });
      levels.appendChild(chip);
    });
    levels.id = 'hp-log-levels';
    node.appendChild(levels);

    var row = make('div', 'hp-actions');
    var source = make('select', 'hp-input');
    source.id = 'hp-log-source';
    [['all', '全部日志'], ['qq', 'QQ 侧'], ['web', '网页侧']].forEach(function (pair) {
      var option = document.createElement('option');
      option.value = pair[0];
      option.textContent = pair[1];
      source.appendChild(option);
    });
    source.value = lgSource;
    source.addEventListener('change', function () { lgSource = String(source.value || 'all'); lgSignature = ''; loadLogs(); });
    row.appendChild(source);

    var auto = make('label', 'hp-note');
    auto.style.display = 'flex';
    auto.style.alignItems = 'center';
    auto.style.gap = '8px';
    auto.style.minHeight = '44px';
    auto.style.marginTop = '0';
    var box = make('input');
    box.type = 'checkbox';
    box.id = 'hp-log-auto';
    box.checked = lgAuto;
    box.style.width = '20px';
    box.style.height = '20px';
    box.style.accentColor = '#5aa2ff';
    box.addEventListener('change', function () {
      lgAuto = !!box.checked;
      if (lgAuto) { startLogPoll(); toast('自动刷新已开启（2 秒）'); }
      else { stopLogPoll(); toast('自动刷新已关闭'); }
      updateFooter();
    });
    auto.appendChild(box);
    auto.appendChild(make('span', null, '自动刷新（2 秒）'));
    row.appendChild(auto);

    var copy = button('hp-btn ghost', '复制全部', 'hp-log-copy');
    copy.addEventListener('click', function () { copyText(lgLines.join('\n'), '日志已复制'); });
    var reload = button('hp-btn ghost', '刷新', 'hp-log-reload');
    reload.addEventListener('click', function () { lgSignature = ''; loadLogs(); });
    row.appendChild(copy);
    row.appendChild(reload);
    node.appendChild(row);
    return node;
  }

  function logScroll() {
    var box = make('div', 'hp-logscroll');
    box.id = 'hp-log-body';
    box.appendChild(make('div', 'hp-state', '正在读取日志…'));
    box.addEventListener('scroll', function () {
      lgAtBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 24;
    });
    return box;
  }

  function logFooter() {
    var node = make('div', 'hp-note', '');
    node.id = 'hp-log-footer';
    return node;
  }

  function updateFooter() {
    var footer = byId('hp-log-footer');
    if (!footer) return;
    var levels = {};
    lgLines.forEach(function (line) {
      var level = levelOf(line).level || 'INFO';
      levels[level] = (levels[level] || 0) + 1;
    });
    var parts = Object.keys(levels).map(function (key) { return key + ' ' + levels[key]; });
    footer.textContent = '共 ' + lgLines.length + ' 行（最多保留 ' + LOG_LIMIT + ' 行）' +
      (parts.length ? '：' + parts.join(' · ') : '') + (lgAuto ? '　自动刷新中' : '　自动刷新已关闭');
  }

  function stopLogPoll() {
    if (!lgStop) return;
    try { lgStop(); } catch (error) { /* 忽略 */ }
    lgStop = null;
  }

  function startLogPoll() {
    var M = api();
    stopLogPoll();
    if (!lgAuto) return;
    lgStop = M.pollWhileVisible(function () {
      if (M.current && M.current() !== 'logs') { stopLogPoll(); return; }
      loadLogs(true);
    }, 2000);
  }

  function loadLogs(silent) {
    return api().api('/api/logs', { method: 'POST', body: { lines: LOG_LIMIT, source: lgSource } }).then(function (data) {
      var lines = (data && data.lines) || [];
      if (!Array.isArray(lines)) lines = [];
      if (lines.length > LOG_LIMIT) lines = lines.slice(lines.length - LOG_LIMIT);
      var signature = lgSource + '\u0000' + lines.join('\u0001');
      if (signature === lgSignature) { updateFooter(); return data; }        // 没有新内容：不重画，滚动位置不动
      lgSignature = signature;
      lgLines = lines;
      renderLines();
      return data;
    }, function (error) {
      if (silent) return null;
      var body = byId('hp-log-body');
      if (body) {
        clear(body);
        body.appendChild(make('div', 'hp-state bad', '读取失败：' + messageOf(error)));
        var retry = button('hp-btn ghost', '重试');
        retry.addEventListener('click', function () { lgSignature = ''; loadLogs(); });
        body.appendChild(retry);
      }
      return null;
    });
  }

  /** `[时间] [级别] 正文`；THINK 的续行没有前缀，沿用上一条的级别。 */
  function levelOf(line) {
    var match = String(line).match(/^\[([^\]]+)\]\s*\[([A-Za-z]+)\]\s?([\s\S]*)$/);
    if (!match) return { time: '', level: '', text: String(line), cont: true };
    return { time: match[1], level: match[2].toUpperCase(), text: match[3], cont: false };
  }

  function renderLines() {
    var body = byId('hp-log-body');
    if (!body) return;
    var keepBottom = lgAtBottom;
    var previousTop = body.scrollTop;
    clear(body);
    var lastLevel = '';
    var shown = 0;
    lgLines.forEach(function (raw) {
      var parsed = levelOf(raw);
      if (parsed.cont && lastLevel) parsed.level = lastLevel; else if (parsed.level) lastLevel = parsed.level;
      if (lgLevel && parsed.level !== lgLevel) return;
      shown += 1;
      var row = make('div', 'hp-log-line' + (parsed.cont ? ' cont' : ''));
      row.setAttribute('data-hp-log-level', parsed.level || '');
      if (parsed.time) {
        var time = make('span', 'hp-log-time', parsed.time.replace(/^\d{4}-\d{2}-\d{2}\s*/, ''));
        row.appendChild(time);
      }
      if (parsed.level) row.appendChild(make('span', 'hp-log-level lv-' + parsed.level, parsed.level));
      row.appendChild(make('span', 'hp-log-text', parsed.text));
      body.appendChild(row);
    });
    if (!shown) {
      body.appendChild(make('div', 'hp-state', lgLines.length ? ('这一级别没有日志（当前共 ' + lgLines.length + ' 行）。') : '（这一路还没有日志）'));
    }
    if (keepBottom) body.scrollTop = body.scrollHeight; else body.scrollTop = previousTop;
    updateFooter();
  }

  /* ================================================================ 关于 */

  function renderAbout(target) {
    var root = target || hpRoot;
    if (!root) return;
    hpRoot = root;
    clear(root);
    var wrap = make('div', 'hp-wrap');

    var node = card('Pixiko', '手机端界面');
    var kv1 = make('div', 'hp-kv');
    kv1.appendChild(make('span', 'hp-kv-key', '名称'));
    kv1.appendChild(make('span', 'hp-kv-val', 'Pixiko'));
    node.appendChild(kv1);
    var kv2 = make('div', 'hp-kv');
    kv2.appendChild(make('span', 'hp-kv-key', '版本'));
    kv2.appendChild(make('span', 'hp-kv-val', 'v1.5.0'));
    node.appendChild(kv2);
    var kv3 = make('div', 'hp-kv');
    kv3.appendChild(make('span', 'hp-kv-key', '界面'));
    kv3.appendChild(make('span', 'hp-kv-val', '这是 Pixiko 的手机端界面，功能与桌面控制台一致（同一套 /api 接口，同一份权限与回执）。'));
    node.appendChild(kv3);
    node.appendChild(make('div', 'hp-note', '作者：loriko（deloriko@outlook.com）。许可见仓库根目录的 LICENSES/ 与 THIRD-PARTY-LICENSES.md。'));
    wrap.appendChild(node);

    var fallback = card('备用入口', '');
    fallback.appendChild(make('div', 'hp-note', '手机端界面缺哪一块功能时，可以打开完整网页版控制台：功能与桌面一致。'));
    var link = document.createElement('a');
    link.className = 'hp-link';
    link.href = '/';
    link.rel = 'noopener';
    link.textContent = '打开网页版完整控制台';
    fallback.appendChild(link);
    wrap.appendChild(fallback);

    root.appendChild(wrap);
  }

  /** 深链兜底：boot() 在 defer 脚本里就跑了，直开 #/logs 之类会先落在「还没做好」占位上。 */
  function claimRoute(id) {
    var M = api();
    if (!M || typeof M.current !== 'function' || M.current() !== id) return;
    var root = document.getElementById('screen-' + id);
    if (!root || (' ' + root.className + ' ').indexOf(' active ') >= 0) return;
    var actives = document.querySelectorAll('#m-main .screen.active');
    for (var index = 0; index < actives.length; index++) actives[index].classList.remove('active');
    root.classList.add('active');
    var missing = document.getElementById('screen-missing-' + id);
    if (missing) missing.classList.remove('active');
    var main = document.getElementById('m-main');
    if (main) main.scrollTop = 0;
  }

  /* ---------------------------------------------------------------- 注册 */

  injectStyle();
  whenReady(function (M) {
    M.register('help', {
      title: '帮助',
      mount: function (target) { renderHelp(target); },
      refresh: function (target) { renderHelp(target || hpRoot); }
    });
    M.register('logs', {
      title: '日志',
      mount: function (target) { renderLogs(target); },
      refresh: function (target) { renderLogs(target || lgRoot); }
    });
    M.register('about', {
      title: '关于',
      mount: function (target) { renderAbout(target); },
      refresh: function (target) { renderAbout(target || hpRoot); }
    });
    claimRoute('help');
    claimRoute('logs');
    claimRoute('about');
  });
})();
