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
      '  font-size:12px;font-weight:700;line-height:22px;text-align:center}',
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
      '  color:#93a4c4;font-size:12.5px;transition:height .18s}',
      '.q-ptr.q-on{height:40px}',
      /* 正文容器（外壳负责滚动，这里只做宽度约束） */
      '.q-body{min-width:0}',
      '.q-body>*{max-width:100%}',
      /* 卡片 / 列表行 */
      '.q-card{background:#131c2e;border:1px solid #1e2a44;border-radius:14px;overflow:hidden}',
      '.q-row{display:flex;align-items:flex-start;gap:10px;width:100%;min-height:64px;padding:12px;margin:0 0 8px;',
      '  border:1px solid #1e2a44;border-radius:14px;background:#131c2e;color:inherit;font:inherit;text-align:left;',
      '  cursor:pointer;touch-action:manipulation;transition:transform .1s,background .12s,border-color .12s}',
      '.q-row:active{background:#18233a;transform:scale(.985)}',
      '.q-row.q-unread{background:linear-gradient(90deg,rgba(90,162,255,.16),rgba(19,28,46,0) 60%),#131c2e;',
      '  border-color:rgba(90,162,255,.5);box-shadow:inset 3px 0 0 #5aa2ff}',
      '.q-row-main{flex:1 1 auto;min-width:0}',
      '.q-row-top{display:flex;align-items:center;gap:8px;min-width:0}',
      '.q-no{flex:0 0 auto;font-weight:700;color:#5aa2ff;font-size:14px}',
      '.q-cmd{flex:1 1 auto;min-width:0;font-size:15px;font-weight:600;overflow:hidden;text-overflow:ellipsis;',
      '  white-space:nowrap}',
      '.q-when{flex:0 0 auto;color:#93a4c4;font-size:12px}',
      '.q-sum{margin-top:4px;color:#93a4c4;font-size:13px;line-height:1.35;overflow:hidden;text-overflow:ellipsis;',
      '  display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical}',
      '.q-tags{display:flex;flex-wrap:wrap;gap:6px;align-items:center;margin-top:7px}',
      '.q-tag{display:inline-flex;align-items:center;gap:4px;padding:2px 8px;border-radius:999px;background:#1b2740;',
      '  border:1px solid #24314e;color:#93a4c4;font-size:11.5px;line-height:1.5;white-space:nowrap}',
      '.q-tag.q-warn{background:rgba(255,107,107,.14);border-color:rgba(255,107,107,.45);color:#ff9b9b}',
      '.q-tag.q-live{background:rgba(90,162,255,.16);border-color:rgba(90,162,255,.45);color:#9cc7ff}',
      '.q-dot{flex:0 0 auto;display:inline-flex;align-items:center;justify-content:center;min-width:20px;height:20px;',
      '  padding:0 6px;border-radius:10px;background:#ff6b6b;color:#fff;font-size:12px;font-weight:700}',
      '.q-chev{flex:0 0 auto;align-self:center;color:#5d6f92;font-size:20px;line-height:1}',
      /* 三态 */
      '.q-state{margin:8px 0;padding:14px;border:1px dashed #24314e;border-radius:14px;background:#101a2b;',
      '  color:#93a4c4;font-size:13.5px;line-height:1.5;word-break:break-word}',
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
      '.q-head{padding:12px;margin-bottom:10px}',
      '.q-head .q-cmd{white-space:normal;font-size:15.5px;font-weight:600}',
      '.q-head-meta{margin-top:6px;color:#93a4c4;font-size:12.5px}',
      '.q-progress{margin-top:10px}',
      '.q-prog-track{height:6px;border-radius:3px;background:#1b2740;overflow:hidden}',
      '.q-prog-fill{display:block;height:100%;border-radius:3px;background:linear-gradient(90deg,#5aa2ff,#8fc0ff);',
      '  transition:width .3s}',
      '.q-progress-text{margin-top:6px;color:#93a4c4;font-size:12px}',
      /* 步骤卡 */
      '.q-step{background:#131c2e;border:1px solid #1e2a44;border-radius:14px;padding:12px;margin-bottom:10px}',
      '.q-step.q-err{border-color:rgba(255,107,107,.5)}',
      '.q-step-head{color:#93a4c4;font-size:11.5px;letter-spacing:.4px;margin-bottom:6px}',
      '.q-text{white-space:pre-wrap;word-break:break-word;font-size:14.5px;line-height:1.55;color:#dde7fb}',
      '.q-step.q-err .q-text{color:#ffc9c9}',
      /* 图集网格：一条回执里的图片合成一个网格 */
      '.q-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(96px,1fr));gap:8px;margin-top:4px}',
      '.q-tile{position:relative;display:block;width:100%;aspect-ratio:1/1;padding:0;margin:0;border:1px solid #24314e;',
      '  border-radius:12px;overflow:hidden;background:#0f1728;cursor:pointer;touch-action:manipulation}',
      '.q-tile:active{transform:scale(.97)}',
      '.q-tile img{width:100%;height:100%;object-fit:cover;display:block}',
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
  /** 图片地址：外链走原样，本地路径交给契约的 imageUrl（它会拼令牌与 /api/image）。 */
  function imageSrc(file) {
    var value = text(file);
    if (/^https?:\/\//i.test(value)) return value;
    var path = value.replace(/\\/g, '/').replace(/^.*?(data\/generated\/)/, '$1');
    if (typeof P.imageUrl === 'function') { try { return P.imageUrl(path); } catch (error) { /* 落下面 */ } }
    return path;
  }
  /** 路径 → 文件名（图集与查看器的说明文字用）。 */
  function shortName(file) { return text(file).replace(/^.*[\\/]/, '').split('?')[0]; }

  /**
   * 下拉刷新：**这一屏自己接管手势**（外壳那份是给没实现 refresh 的屏兜底的，只在 #m-main 上做过一次，
   * 我们不依赖它）。滚动交给外壳的 #m-main，所以只看它的 scrollTop；从顶部再往下拖超过 56px 就刷这一屏。
   * 提示条放在屏内最上面，展开时把正文推下去（不做回弹动画）。
   */
  function installPullToRefresh(tip, onFire) {
    var host = document.getElementById('m-main') || document.getElementById('m-app') || document;
    var startY = 0;
    var tracking = false;
    var armed = false;
    var busy = false;
    host.addEventListener('touchstart', function (event) {
      if (busy || event.touches.length !== 1 || (host.scrollTop || 0) > 2) { tracking = false; return; }
      startY = event.touches[0].clientY;
      tracking = true;
      armed = false;
    }, { passive: true });
    host.addEventListener('touchmove', function (event) {
      if (!tracking) return;
      if ((host.scrollTop || 0) > 2) { tracking = false; tip.classList.remove('q-on'); return; }
      var delta = event.touches[0].clientY - startY;
      if (delta > 12) {
        tip.classList.add('q-on');
        armed = delta > 56;
        tip.textContent = armed ? '松手刷新' : '下拉刷新';
      } else {
        tip.classList.remove('q-on');
        armed = false;
      }
    }, { passive: true });
    host.addEventListener('touchend', function () {
      tracking = false;
      tip.classList.remove('q-on');
      if (!armed || busy) return;
      busy = true;
      tip.textContent = '正在刷新…';
      Promise.resolve().then(onFire).catch(function () { /* 错误已经由屏内三态显示 */ }).then(function () {
        busy = false;
        tip.textContent = '下拉刷新';
      });
    }, { passive: true });
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
  var list = { root: null, body: null, refresh: null, sumBadge: null, sumText: null, quests: null,
    unread: 0, latest: 0, error: '', loading: false, timer: null, lastAt: 0 };

  function rowFor(quest) {
    var number = num(quest.number, 0);
    var unread = !!quest.unread;
    var row = el('button', 'q-row' + (unread ? ' q-unread' : ''));
    row.type = 'button';
    row.setAttribute('data-number', String(number));
    var main = el('div', 'q-row-main');
    var top = el('div', 'q-row-top');
    top.appendChild(el('span', 'q-no', '#' + number));
    top.appendChild(el('span', 'q-cmd', text(quest.command) || '（没有指令文本）'));
    top.appendChild(el('span', 'q-when', when(quest.startedAt, quest.ageMillis)));
    main.appendChild(top);
    main.appendChild(el('div', 'q-sum', text(quest.summary) || '（这条回执还没有输出）'));
    var tags = el('div', 'q-tags');
    if (unread) tags.appendChild(el('span', 'q-tag q-live', '未读'));
    var texts = num(quest.texts, 0);
    var images = num(quest.images, 0);
    if (texts) tags.appendChild(el('span', 'q-tag', texts + ' 段文字'));
    if (images) tags.appendChild(el('span', 'q-tag', images + ' 张图'));
    if (quest.busy || (quest.done === false && !quest.expired)) {
      tags.appendChild(el('span', 'q-tag q-live', '进行中…'));
    } else if (quest.error) {
      tags.appendChild(el('span', 'q-tag q-warn', '失败'));
    } else {
      tags.appendChild(el('span', 'q-tag', '已完成'));
    }
    if (quest.expired) tags.appendChild(el('span', 'q-tag q-warn', '正文不在了'));
    main.appendChild(tags);
    row.appendChild(main);
    if (unread) row.appendChild(el('span', 'q-dot', '1'));
    row.appendChild(el('span', 'q-chev', '›'));
    row.addEventListener('click', function () { openDetail(number); });
    return row;
  }

  function renderList() {
    if (!list.rows) return;
    var rows = list.rows;
    clear(rows);
    if (list.loading && !list.quests) {
      rows.appendChild(skeleton(5));
      return;
    }
    if (list.error) {
      rows.appendChild(stateBox('q-err', '回执列表读取失败：' + list.error, function () { loadList(true); }));
      if (list.quests && list.quests.length) {
        rows.appendChild(el('div', 'q-step-head', '下面是上一次读到的内容'));
        list.quests.forEach(function (quest) { rows.appendChild(rowFor(quest)); });
      }
      return;
    }
    var quests = list.quests || [];
    if (!quests.length) {
      rows.appendChild(emptyBox());
      return;
    }
    quests.forEach(function (quest) { rows.appendChild(rowFor(quest)); });
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
    /* 列表轻轮询：有新回执 / 有「进行中」的条目就刷一次，页面隐藏时自动停表。 */
    list.timer = P.pollWhileVisible(function () {
      if (list.loading) return;
      if (typeof P.current === 'function' && P.current() !== 'quest') return;
      var stale = Date.now() - list.lastAt > 15000;
      var running = (list.quests || []).some(function (item) { return item && (item.busy || (item.done === false && !item.expired)); });
      if (stale || running) loadList(false);
    }, 5000);
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
  var detail = { root: null, body: null, head: null, number: 0, payload: null, error: '',
    loading: false, files: [], extras: [], timer: null, progress: null };

  function piecesOf(payload) {
    var groups = payload && Array.isArray(payload.messages) && payload.messages.length ? payload.messages : null;
    if (groups) return groups;
    var texts = payload && Array.isArray(payload.texts) ? payload.texts : [];
    return texts.map(function (value) { return [{ type: 'text', text: value }]; });
  }
  function filesOf(payload, groups) {
    var files = [];
    if (groups) {
      groups.forEach(function (pieces) {
        (pieces || []).forEach(function (piece) { if (piece && piece.type === 'image' && piece.file) files.push(text(piece.file)); });
      });
    } else if (payload && Array.isArray(payload.images)) {
      payload.images.forEach(function (image) { if (image && image.file) files.push(text(image.file)); });
    }
    detail.extras.forEach(function (file) { if (files.indexOf(file) < 0) files.push(file); });
    return files;
  }

  function renderDetailHead() {
    if (!detail.head) return;
    clear(detail.head);
    var payload = detail.payload || {};
    var number = num(payload.quest, detail.number) || detail.number;
    var command = text(payload.command);
    var failed = !!payload.error || !!detail.error;
    var running = !failed && !(payload.done && !payload.busy);
    var card = el('div', 'q-card q-head');
    card.appendChild(el('div', 'q-no', '#' + number));
    card.appendChild(el('div', 'q-cmd', command || '（没有指令文本）'));
    var meta = el('div', 'q-head-meta');
    var whenText = when(payload.startedAt, payload.ageMillis);
    meta.textContent = (whenText ? whenText + ' · ' : '') + (failed ? '失败' : (running ? '执行中…' : '已完成'))
      + (payload.closed ? ' · 回执已关闭' : '')
      + (payload.latest ? ' · 最新一条 #' + num(payload.latest, 0) : '');
    card.appendChild(meta);
    var tags = el('div', 'q-tags');
    if (running) tags.appendChild(el('span', 'q-tag q-live q-run', '执行中'));
    if (failed) tags.appendChild(el('span', 'q-tag q-warn', '失败'));
    if (payload.fromDisk) tags.appendChild(el('span', 'q-tag', '从磁盘读回'));
    var texts = Array.isArray(payload.texts) ? payload.texts.length : 0;
    var images = detail.files.length;
    if (texts) tags.appendChild(el('span', 'q-tag', texts + ' 段文字'));
    if (images) tags.appendChild(el('span', 'q-tag', images + ' 张图'));
    card.appendChild(tags);
    detail.head.appendChild(card);
  }

  function renderProgress() {
    if (!detail.head) return;
    var old = detail.head.querySelector('.q-progress');
    if (old && detail.progress) { detail.progress.card = old; detail.progress.fill = old.querySelector('.q-prog-fill'); detail.progress.text = old.querySelector('.q-progress-text'); return; }
    if (old) old.remove();
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
      detail.progress.card = null;
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
  /** 生成期间提前到达的新图（把这条回执对应的最新生成图并进图集，按路径去重）。 */
  function pullLiveImages() {
    return Promise.resolve(P.api('/api/images', { body: { limit: 6 } })).then(function (data) {
      var images = (data && Array.isArray(data.images)) ? data.images : [];
      var before = detail.extras.length;
      images.forEach(function (image) {
        var path = text(image && image.path);
        if (!path || path.indexOf('data/generated/webui/') === 0) return;
        if (detail.files.indexOf(path) < 0 && detail.extras.indexOf(path) < 0) detail.extras.push(path);
      });
      if (detail.extras.length !== before) renderDetail();
      return images.length;
    }).catch(function () { return 0; });
  }

  function gallery(tiles) {
    var grid = el('div', 'q-grid');
    tiles.forEach(function (file, index) {
      var tile = el('div', 'q-tile');
      var src = imageSrc(file);
      var img = document.createElement('img');
      img.alt = '任务 #' + (detail.number || 0) + ' 第 ' + (index + 1) + ' 张：' + shortName(file);
      img.loading = 'lazy';
      img.decoding = 'async';
      img.addEventListener('error', function () {
        tile.classList.add('q-fail');
        clear(tile);
        tile.appendChild(el('div', '', '图取不到'));
      });
      img.src = src;
      tile.appendChild(img);
      tile.addEventListener('click', function () {
        if (typeof P.openViewer === 'function') {
          try {
            P.openViewer(detail.files.map(function (path, at) {
              return { src: imageSrc(path), caption: '任务 #' + (detail.number || 0) + ' 第 ' + (at + 1) + ' 张：' + shortName(path) };
            }), index);
          } catch (error) { toast('查看器打不开：' + (text(error && error.message) || String(error))); }
        }
      });
      /* 长按：优先交给原生桥（Android 外壳的保存/分享菜单），没有桥就退到底部 sheet。 */
      var holdTimer = null;
      var startAt = null;
      var cancelHold = function () { if (holdTimer) { clearTimeout(holdTimer); holdTimer = null; } };
      tile.addEventListener('touchstart', function (event) {
        if (event.touches.length !== 1) { cancelHold(); return; }
        startAt = { x: event.touches[0].clientX, y: event.touches[0].clientY };
        cancelHold();
        holdTimer = setTimeout(function () {
          holdTimer = null;
          showImageMenu(src, img.alt);
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
        showImageMenu(src, img.alt);
      });
      grid.appendChild(tile);
    });
    return grid;
  }
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
    var items = detail.files.map(function (path, index) {
      if (imageSrc(path) === src) at = index;
      return { src: imageSrc(path), caption: '任务 #' + (detail.number || 0) + ' 第 ' + (index + 1) + ' 张：' + shortName(path) };
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

  function renderDetail() {
    if (!detail.body) return;
    clear(detail.body);
    var payload = detail.payload || {};
    if (detail.loading && !detail.payload) {
      detail.body.appendChild(detail.head);
      detail.body.appendChild(skeleton(4));
      renderDetailHead();
      return;
    }
    detail.body.appendChild(detail.head);
    if (detail.error) {
      detail.body.appendChild(stateBox('q-err', detail.error, function () { loadDetail(detail.number, false); }));
      if (detail.payload) renderDetailBody(detail.payload);
      renderDetailHead();
      return;
    }
    renderDetailBody(payload);
    renderDetailHead();
  }

  function renderDetailBody(payload) {
    var groups = piecesOf(payload);
    var files = filesOf(payload, Array.isArray(payload.messages) && payload.messages.length ? payload.messages : null);
    detail.files = files;
    var steps = groups || [];
    if (!steps.length && !files.length) {
      detail.body.appendChild(el('div', 'q-state', payload.error ? text(payload.error) : '这条回执还没有任何输出。'));
      return;
    }
    var tiles = files.slice();
    var grid = tiles.length ? gallery(tiles) : null;
    var placed = false;
    steps.forEach(function (pieces, index) {
      var stepText = (pieces || []).filter(function (piece) { return piece && piece.type !== 'image' && piece.text; })
        .map(function (piece) { return text(piece.text); }).join('\n');
      var hasPicture = !!(Array.isArray(payload.messages) && payload.messages.length
        && (pieces || []).some(function (piece) { return piece && piece.type === 'image' && piece.file; }));
      var here = hasPicture && !placed && !!grid;
      if (!stepText && hasPicture && !here) return;
      var step = el('div', 'q-step');
      if (/(^|\n)[^\n]{0,16}(失败|错误|不正确|无效|超时|拒绝|找不到)[:：]/.test(stepText)) step.classList.add('q-err');
      step.appendChild(el('div', 'q-step-head', Array.isArray(payload.messages) && payload.messages.length ? '第 ' + (index + 1) + ' 步' : '输出'));
      if (stepText) step.appendChild(el('div', 'q-text', stepText));
      if (here) { placed = true; step.appendChild(grid); }
      detail.body.appendChild(step);
    });
    if (!placed && grid) detail.body.appendChild(grid);
    var running = !payload.error && !(payload.done && !payload.busy);
    if (running) {
      var line = el('div', 'q-state', '（还在跑，正在实时刷新…）');
      detail.body.appendChild(line);
    }
  }

  function loadDetail(number, quiet) {
    var target = num(number, 0);
    if (!target) { detail.number = 0; }
    detail.loading = true;
    if (!quiet) { detail.payload = null; detail.error = ''; renderDetail(); }
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
      detail.extras = [];
      renderDetail();
      var running = !detail.payload.error && !(detail.payload.done && !detail.payload.busy);
      return Promise.resolve(P.api('/api/progress', { body: {} })).then(function (live) {
        setProgress(live);
        renderProgress();
        if (running && live && (live.running || num(live.queue, 0) > 0)) pullLiveImages();
        return detail.payload;
      }).catch(function () { setProgress(null); renderProgress(); return detail.payload; });
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
