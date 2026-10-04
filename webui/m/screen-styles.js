/*
 * Pixiko 手机端 Web UI —— 屏幕「样式」（M2）。id = 'styles'。
 *
 * 全部走现有 `/api/*`（字段名以 Java 为准，见 WebApiController 的 case 与 Bot.webStyles*）：
 *   POST /api/styles        {}                        → {styles:[{number,name,preview,positive,negative,category,
 *                                                        categoryKey,categoryAuto,categorySource,baseModel,stack,
 *                                                        stackLabel,modelSummary,width,height,…}],
 *                                                       categories:[{key,name,kind,count,lora}],
 *                                                       baseModelGroups:[{baseModel,count}], library}
 *   POST /api/styles/edit   {action,name,newName,overwrite,noLora,category,cover,scope}
 *                            action = load | save | overwrite | delete | category | rename
 *   POST /api/styles/cover  {name,path}               （path = data/generated 里的相对路径）
 *   POST /api/images        {limit}                   → {images:[{path,name,…}]}（选封面用）
 *   GET/POST /api/style/preview?name=…&token=…         （封面缩略图的地址，令牌只能挂查询串）
 *
 * 手机端与桌面版的差别（为什么换成 sheet）：桌面用「拖到分类组头」改分类、下拉框选封面；
 * 触屏上拖拽会和外层滚动打架，所以在手机上一律走**底部动作面板**：点行 → 6 个动作 →
 * 需要参数的动作（换分类 / 换封面）再弹一层只读选择面板。保存样式用底部表单面板（输入框字号 16px，
 * 避免 iOS 自动缩放）。样式正文（正向/反向）只在只读面板里显示，**不进任何日志/报表**。
 *
 * 样式在本文件顶部注入（前缀 .st-*），不改 app.css / index.html / app.js。
 */
(function () {
  'use strict';
  if (typeof window === 'undefined' || !window.PixikoM) return;
  var P = window.PixikoM;

  /* ───────────────────────── 1. 样式（内联注入，前缀 .st-*） ───────────────────────── */
  var STYLE_ID = 'pixiko-m-styles-style';
  if (!document.getElementById(STYLE_ID)) {
    var style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = [
      /* 滚动与 app bar 都归外壳（#m-main / #m-bar）：这一屏只做内容，底部给悬浮按钮留位置。 */
      '.st-screen{background:transparent;color:#e9efff;-webkit-user-select:none;user-select:none;',
      '  overflow-x:hidden;padding-bottom:76px;position:relative}',
      '.st-screen input,.st-screen textarea{-webkit-user-select:text;user-select:text;font-size:16px}',
      '.st-title{flex:1 1 auto;min-width:0;font-size:18px;font-weight:700;overflow:hidden;text-overflow:ellipsis;',
      '  white-space:nowrap}',
      '.st-icon{flex:0 0 auto;display:inline-flex;align-items:center;justify-content:center;width:44px;height:44px;',
      '  margin:0;padding:0;border:0;border-radius:12px;background:transparent;color:#93a4c4;font-size:20px;',
      '  font-family:inherit;line-height:1;cursor:pointer;touch-action:manipulation;transition:background .12s,transform .12s}',
      '.st-icon:active{background:#1b2740;color:#5aa2ff;transform:scale(.94)}',
      '.st-icon.st-spin{animation:st-spin .9s linear infinite}',
      '@keyframes st-spin{to{transform:rotate(360deg)}}',
      '@keyframes st-sk{0%{background-position:0 0}100%{background-position:-200% 0}}',
      '.st-body{min-width:0}',
      '.st-body>*{max-width:100%}',
      /* 下拉刷新提示（手势由本屏接管） */
      '.st-ptr{height:0;overflow:hidden;display:flex;align-items:center;justify-content:center;gap:8px;',
      '  color:#93a4c4;font-size:12.5px;transition:height .18s}',
      '.st-ptr.st-on{height:40px}',
      '.st-ptr .st-spin-dot{width:14px;height:14px;border-radius:50%;border:2px solid rgba(90,162,255,.35);',
      '  border-top-color:#5aa2ff;animation:st-spin .9s linear infinite}',
      /* 顶部摘要 + 搜索 + 选项 */
      '.st-summary{display:flex;flex-wrap:wrap;gap:6px;align-items:center;padding:10px 12px;border:1px solid #1e2a44;',
      '  border-radius:14px;background:#131c2e;color:#93a4c4;font-size:13px;line-height:1.4}',
      '.st-search{display:flex;align-items:center;gap:8px;min-height:44px;margin-top:8px;padding:0 12px;',
      '  border:1px solid #1e2a44;border-radius:14px;background:#131c2e}',
      '.st-search input{flex:1 1 auto;min-width:0;height:42px;border:0;background:transparent;color:#e9efff;',
      '  font:inherit;font-size:16px;outline:none}',
      '.st-search .st-clear{flex:0 0 auto;width:32px;height:32px;border:0;border-radius:50%;background:#1b2740;',
      '  color:#93a4c4;font-size:16px;line-height:1;cursor:pointer}',
      '.st-toggle{display:flex;align-items:center;gap:10px;min-height:44px;margin-top:8px;padding:0 12px;border-radius:14px;',
      '  background:#101a2b;border:1px solid #1e2a44;color:#c9d6ee;font-size:13.5px}',
      '.st-toggle .st-switch{flex:0 0 auto;width:44px;height:26px;border-radius:13px;background:#26314c;position:relative;',
      '  transition:background .15s}',
      '.st-toggle .st-switch:after{content:"";position:absolute;top:3px;left:3px;width:20px;height:20px;border-radius:50%;',
      '  background:#93a4c4;transition:transform .15s,background .15s}',
      '.st-toggle.st-on .st-switch{background:rgba(90,162,255,.35)}',
      '.st-toggle.st-on .st-switch:after{transform:translateX(18px);background:#5aa2ff}',
      '.st-toggle .st-toggle-text{flex:1 1 auto;min-width:0}',
      /* 分类组 */
      '.st-group{margin-top:14px}',
      '.st-group-head{display:flex;align-items:center;gap:8px;min-height:44px;padding:0 4px;background:transparent;',
      '  border:0;color:#93a4c4;font:inherit;font-size:13px;font-weight:600;cursor:pointer;width:100%;text-align:left}',
      '.st-group-head:active{color:#5aa2ff}',
      '.st-arrow{display:inline-block;width:14px;color:#5d6f92;transition:transform .15s}',
      '.st-group.st-fold .st-arrow{transform:rotate(-90deg)}',
      '.st-group.st-fold .st-group-rows{display:none}',
      '.st-group-name{flex:1 1 auto;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
      '.st-count{padding:1px 8px;border-radius:999px;background:#1b2740;border:1px solid #24314e;font-size:11.5px;color:#93a4c4}',
      /* 样式行 */
      '.st-row{display:flex;align-items:center;gap:10px;width:100%;min-height:64px;padding:10px;margin:0 0 8px;',
      '  border:1px solid #1e2a44;border-radius:14px;background:#131c2e;color:inherit;font:inherit;text-align:left;',
      '  cursor:pointer;touch-action:manipulation;transition:transform .1s,background .12s}',
      '.st-row:active{background:#18233a;transform:scale(.985)}',
      '.st-thumb{flex:0 0 auto;width:52px;height:52px;border-radius:12px;overflow:hidden;background:#0f1728;',
      '  border:1px solid #24314e;display:flex;align-items:center;justify-content:center;color:#5d6f92;font-size:18px}',
      '.st-thumb img{width:100%;height:100%;object-fit:cover;display:block}',
      '.st-row-main{flex:1 1 auto;min-width:0}',
      '.st-row-top{display:flex;align-items:center;gap:6px;min-width:0}',
      '.st-no{flex:0 0 auto;color:#5aa2ff;font-size:12.5px;font-weight:700}',
      '.st-name{flex:1 1 auto;min-width:0;font-size:15px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
      '.st-meta{display:flex;flex-wrap:wrap;gap:6px;align-items:center;margin-top:5px}',
      '.st-tag{display:inline-flex;align-items:center;gap:4px;padding:2px 8px;border-radius:999px;background:#1b2740;',
      '  border:1px solid #24314e;color:#93a4c4;font-size:11.5px;line-height:1.5;white-space:nowrap;max-width:100%;',
      '  overflow:hidden;text-overflow:ellipsis}',
      '.st-tag.st-on{background:rgba(90,162,255,.16);border-color:rgba(90,162,255,.45);color:#9cc7ff}',
      '.st-tag.st-auto{border-style:dashed}',
      '.st-sub{margin-top:4px;color:#93a4c4;font-size:12px;line-height:1.35;overflow:hidden;text-overflow:ellipsis;',
      '  display:-webkit-box;-webkit-line-clamp:1;-webkit-box-orient:vertical}',
      '.st-chev{flex:0 0 auto;color:#5d6f92;font-size:20px;line-height:1}',
      /* 三态 */
      '.st-state{margin:8px 0;padding:14px;border:1px dashed #24314e;border-radius:14px;background:#101a2b;',
      '  color:#93a4c4;font-size:13.5px;line-height:1.5;word-break:break-word}',
      '.st-state.st-err{border-style:solid;border-color:rgba(255,107,107,.45);background:rgba(255,107,107,.08);color:#ffc9c9}',
      '.st-empty{padding:46px 18px;text-align:center;color:#93a4c4}',
      '.st-empty .st-empty-icon{font-size:40px;opacity:.5}',
      '.st-empty .st-empty-text{margin-top:10px;font-size:15px;color:#c9d6ee}',
      '.st-empty .st-empty-sub{margin-top:6px;font-size:12.5px}',
      '.st-retry{display:inline-flex;align-items:center;justify-content:center;min-height:44px;margin-top:14px;padding:0 18px;',
      '  border:1px solid rgba(90,162,255,.5);border-radius:12px;background:rgba(90,162,255,.14);color:#9cc7ff;',
      '  font:inherit;font-size:14.5px;cursor:pointer}',
      '.st-retry:active{transform:scale(.97)}',
      '.st-sk{height:64px;margin-bottom:8px;border-radius:14px;background:linear-gradient(90deg,#141e32,#1b2740,#141e32);',
      '  background-size:200% 100%;animation:st-sk 1.1s linear infinite}',
      /* 悬浮「保存当前提示词为样式」 */
      '.st-fab{position:fixed;right:14px;bottom:calc(env(safe-area-inset-bottom,0px) + var(--tabs-h, 58px) + 14px);z-index:6;',
      '  display:inline-flex;align-items:center;gap:6px;height:44px;padding:0 16px;border:0;border-radius:22px;',
      '  background:#5aa2ff;color:#08101f;font:inherit;font-size:14.5px;font-weight:700;cursor:pointer;',
      '  box-shadow:0 8px 22px rgba(90,162,255,.34);touch-action:manipulation}',
      '.st-fab:active{transform:scale(.96)}',
      /* 只读原文面板 / 底部表单面板 */
      '.st-panel{position:fixed;left:0;right:0;bottom:0;z-index:60;background:#131c2e;border-top:1px solid #24314e;',
      '  border-radius:18px 18px 0 0;max-height:86vh;display:flex;flex-direction:column;',
      '  padding-bottom:calc(env(safe-area-inset-bottom,0px) + 8px);animation:st-up .18s ease-out}',
      '@keyframes st-up{from{transform:translateY(22px);opacity:.6}to{transform:translateY(0);opacity:1}}',
      '.st-panel-head{flex:0 0 auto;display:flex;align-items:center;gap:8px;min-height:52px;padding:0 12px;',
      '  border-bottom:1px solid #1e2a44}',
      '.st-panel-title{flex:1 1 auto;min-width:0;font-size:16px;font-weight:700;overflow:hidden;text-overflow:ellipsis;',
      '  white-space:nowrap}',
      '.st-panel-body{flex:1 1 auto;min-height:0;overflow-y:auto;padding:12px;font-size:14.5px;line-height:1.55;color:#dde7fb}',
      '.st-panel-body .st-pre{white-space:pre-wrap;word-break:break-word;padding:10px;border-radius:12px;background:#0f1728;',
      '  border:1px solid #1e2a44;margin-bottom:10px}',
      '.st-panel-foot{flex:0 0 auto;display:flex;gap:8px;padding:10px 12px 4px;border-top:1px solid #1e2a44}',
      '.st-btn{flex:1 1 0;display:inline-flex;align-items:center;justify-content:center;min-height:44px;padding:0 14px;',
      '  border:1px solid #24314e;border-radius:12px;background:#1b2740;color:#e9efff;font:inherit;font-size:15px;',
      '  cursor:pointer;touch-action:manipulation}',
      '.st-btn:active{transform:scale(.98)}',
      '.st-btn.st-primary{background:#5aa2ff;border-color:#5aa2ff;color:#08101f;font-weight:700}',
      '.st-btn.st-danger{background:rgba(255,107,107,.16);border-color:rgba(255,107,107,.5);color:#ff9b9b}',
      '.st-btn[disabled]{opacity:.5}',
      /* 表单 */
      '.st-field{margin-bottom:12px}',
      '.st-field label{display:block;margin-bottom:6px;color:#93a4c4;font-size:12.5px}',
      '.st-field input[type=text]{width:100%;height:46px;padding:0 12px;border:1px solid #24314e;border-radius:12px;',
      '  background:#0f1728;color:#e9efff;font-size:16px;outline:none;box-sizing:border-box}',
      '.st-field input[type=text]:focus{border-color:#5aa2ff}',
      '.st-cover-row{display:flex;align-items:center;gap:10px}',
      '.st-cover-prev{flex:0 0 auto;width:56px;height:56px;border-radius:12px;border:1px solid #24314e;background:#0f1728;',
      '  overflow:hidden;display:flex;align-items:center;justify-content:center;color:#5d6f92;font-size:11px}',
      '.st-cover-prev img{width:100%;height:100%;object-fit:cover;display:block}',
      '.st-cover-value{flex:1 1 auto;min-width:0;color:#c9d6ee;font-size:13px;overflow:hidden;text-overflow:ellipsis;',
      '  white-space:nowrap}',
      '.st-cover-btn{flex:0 0 auto;min-height:44px;padding:0 14px;border-radius:12px;border:1px solid rgba(90,162,255,.5);',
      '  background:rgba(90,162,255,.14);color:#9cc7ff;font:inherit;font-size:14px;cursor:pointer}',
      '.st-check{display:flex;align-items:center;gap:10px;min-height:44px;padding:0 12px;border:1px solid #24314e;',
      '  border-radius:12px;background:#0f1728;color:#c9d6ee;font-size:13.5px;cursor:pointer}',
      '.st-check input{width:20px;height:20px;accent-color:#5aa2ff;margin:0}'
    ].join('');
    document.head.appendChild(style);
  }

  /* ───────────────────────── 2. 小工具 ───────────────────────── */
  function el(tag, cls, text) {
    if (typeof P.el === 'function') { try { return P.el(tag, cls, text); } catch (error) { /* 落下面 */ } }
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
  function shortName(file) { return text(file).replace(/^.*[\\/]/, '').split('?')[0]; }
  function truncate(value, max) {
    var s = text(value);
    return s.length > max ? s.slice(0, max) + '…' : s;
  }
  function coverUrlFor(name) {
    return '/api/style/preview?token=' + encodeURIComponent(text(P.token && P.token())) + '&name=' + encodeURIComponent(text(name));
  }
  function localImageSrc(path) {
    var value = text(path);
    if (/^https?:\/\//i.test(value)) return value;
    var normalized = value.replace(/\\/g, '/').replace(/^.*?(data\/generated\/)/, '$1');
    if (typeof P.imageUrl === 'function') { try { return P.imageUrl(normalized); } catch (error) { /* 落下面 */ } }
    return normalized;
  }
  function scopeValue() {
    if (typeof P.scope === 'function') { try { return text(P.scope()); } catch (error) { /* 落下面 */ } }
    return '';
  }
  function foldKey(item) {
    if (item && item.categoryKey) return 'k' + text(item.categoryKey);
    if (item && item.category) return 'n' + text(item.category);
    return 'none';
  }
  function colorFor(name) {
    var value = text(name);
    var sum = 0;
    for (var i = 0; i < value.length; i++) sum = (sum * 31 + value.charCodeAt(i)) % 360;
    return 'hsl(' + sum + ',52%,42%)';
  }
  /**
   * 下拉刷新：**这一屏自己接管手势**（外壳那份是给没实现 refresh 的屏兜底的）。
   * 滚动交给外壳的 #m-main，只看它的 scrollTop；从顶部再下拖超过 56px 松手就刷这一屏。
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
      if ((host.scrollTop || 0) > 2) { tracking = false; tip.classList.remove('st-on'); return; }
      var delta = event.touches[0].clientY - startY;
      if (delta > 12) {
        tip.classList.add('st-on');
        armed = delta > 56;
        tip.textContent = armed ? '松手刷新' : '下拉刷新';
      } else {
        tip.classList.remove('st-on');
        armed = false;
      }
    }, { passive: true });
    host.addEventListener('touchend', function () {
      tracking = false;
      tip.classList.remove('st-on');
      if (!armed || busy) return;
      busy = true;
      tip.textContent = '正在刷新…';
      Promise.resolve().then(onFire).catch(function () { /* 错误已经由屏内三态显示 */ }).then(function () {
        busy = false;
        tip.textContent = '下拉刷新';
      });
    }, { passive: true });
  }
  var ICONS = {
    refresh: 'M12 5V1L7 6l5 5V7a5 5 0 1 1-5 5H5a7 7 0 1 0 7-7z',
    more: 'M6 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm6 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm6 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4z',
    close: 'M18.3 5.71 12 12l6.3 6.29-1.41 1.42L10.59 13.4 4.3 19.71 2.89 18.3 9.17 12 2.89 5.71 4.3 4.29l6.29 6.3 6.3-6.3z'
  };
  function iconButton(kind, label, onTap) {
    var button = el('button', 'st-icon');
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
  /** 一层底部面板（只读原文 / 保存表单共用）：标题 + 可滚内容 + 底部按钮。 */
  function openPanel(head, buildBody, buildFoot) {
    var backdrop = el('div');
    backdrop.style.cssText = 'position:fixed;inset:0;z-index:59;background:rgba(4,8,16,.6)';
    var panel = el('div', 'st-panel');
    var bar = el('div', 'st-panel-head');
    bar.appendChild(el('div', 'st-panel-title', head));
    var closeBtn = iconButton('close', '关闭', function () { closePanel(); });
    bar.appendChild(closeBtn);
    panel.appendChild(bar);
    var body = el('div', 'st-panel-body');
    panel.appendChild(body);
    var foot = el('div', 'st-panel-foot');
    panel.appendChild(foot);
    function closePanel() {
      if (backdrop.parentNode) backdrop.parentNode.removeChild(backdrop);
      if (panel.parentNode) panel.parentNode.removeChild(panel);
    }
    backdrop.addEventListener('click', closePanel);
    document.body.appendChild(backdrop);
    document.body.appendChild(panel);
    buildBody(body, closePanel);
    if (buildFoot) buildFoot(foot, closePanel);
    return { panel: panel, body: body, foot: foot, close: closePanel };
  }

  /* ───────────────────────── 3. 状态 ───────────────────────── */
  var STYLE_COVER_RECENT = 8;
  var S = { root: null, body: null, groups: null, summary: null, searchRow: null, toggleRow: null,
    styles: null, categories: null, baseModelGroups: null, library: 0, query: '', noLora: false,
    folded: {}, error: '', loading: false, lastAt: 0, timer: null, busy: false };

  /* ───────────────────────── 4. 数据 ───────────────────────── */
  function loadStyles(showSpinner) {
    S.loading = true;
    S.lastAt = Date.now();
    if (!S.styles) render();
    var pending = P.api('/api/styles');
    return Promise.resolve(pending).then(function (data) {
      S.styles = data && Array.isArray(data.styles) ? data.styles : [];
      S.categories = data && Array.isArray(data.categories) ? data.categories : [];
      S.baseModelGroups = data && Array.isArray(data.baseModelGroups) ? data.baseModelGroups : [];
      S.library = num(data && data.library, S.styles.length);
      S.error = '';
      render();
      return data;
    }).catch(function (error) {
      S.error = text(error && error.message) || String(error);
      render();
      if (showSpinner) toast('样式库读取失败：' + S.error);
      return null;
    }).then(function (data) {
      S.loading = false;
      return data;
    });
  }

  /** 所有写操作都走这一条：成功后就地重取 /api/styles（局部刷新，不整页刷新）。 */
  function editStyles(action, name, extra) {
    var body = { action: action, name: name === undefined || name === null ? '' : String(name), scope: scopeValue() };
    if (extra) Object.keys(extra).forEach(function (key) { body[key] = extra[key]; });
    S.busy = true;
    return Promise.resolve(P.api('/api/styles/edit', { body: body })).then(function (data) {
      S.busy = false;
      if (data) applyStylesPayload(data);
      var message = data && data.message ? text(data.message) : '';
      toast(message || '完成');
      return data;
    }).catch(function (error) {
      S.busy = false;
      toast('操作失败：' + (text(error && error.message) || String(error)));
      return null;
    });
  }
  /** 写接口回的就是整份 /api/styles，直接用，省一次往返；缺字段就退回重取。 */
  function applyStylesPayload(data) {
    if (!data || !Array.isArray(data.styles)) return Promise.resolve(loadStyles(false));
    S.styles = data.styles;
    if (Array.isArray(data.categories)) S.categories = data.categories;
    if (Array.isArray(data.baseModelGroups)) S.baseModelGroups = data.baseModelGroups;
    S.library = num(data.library, S.styles.length);
    S.error = '';
    render();
    return Promise.resolve(data);
  }

  /* ───────────────────────── 5. 渲染 ───────────────────────── */
  function matches(item) {
    if (!S.query) return true;
    var needle = S.query.toLowerCase();
    var hay = [item && item.name, item && item.category, item && item.baseModel, item && item.stackLabel,
      item && item.modelSummary].map(text).join(' ').toLowerCase();
    return hay.indexOf(needle) >= 0;
  }
  /**
   * 分组顺序（**跟后端 categories 一致**）：先把 categories 按顺序变成分组桶，再把列表里
   * 「有分类但 categories 里没有」（老后端 / 竞态）的项按分类名补在后面。
   *
   * 关键：桶的 key 必须与 {@link foldKey} 完全同构（都是 `k`/`n` 前缀 + 原值），否则
   * 分组过滤会一条都匹配不上，整张列表会全掉进「其他」兜底组。
   */
  function groupOrder() {
    var order = [];
    var seen = {};
    (S.categories || []).forEach(function (entry) {
      var key = text(entry && entry.key);
      if (!key || seen[key]) return;
      seen[key] = true;
      order.push({ key: 'k' + key, name: text(entry && entry.name) || '未分类', count: num(entry && entry.count, 0), kind: text(entry && entry.kind) });
    });
    (S.styles || []).forEach(function (item) {
      var key = text(item && item.categoryKey);
      if (key && seen[key]) return;
      var name = text(item && item.category) || '未分类';
      var fallback = 'n' + name;
      if (key) { seen[key] = true; }
      if (seen[fallback]) return;
      seen[fallback] = true;
      order.push({ key: fallback, name: name, count: 0, kind: '' });
    });
    return order;
  }
  function styleRow(item, index) {
    var row = el('button', 'st-row');
    row.type = 'button';
    row.setAttribute('data-style', text(item && item.name));
    var thumb = el('div', 'st-thumb');
    if (item && item.preview) {
      var img = document.createElement('img');
      img.alt = '样式封面：' + text(item.name);
      img.loading = 'lazy';
      img.src = coverUrlFor(item.name);
      img.addEventListener('error', function () { clear(thumb); thumb.appendChild(el('span', '', '无图')); });
      thumb.appendChild(img);
    } else {
      var letter = el('span', '', text((item && item.name || '?').slice(0, 1)));
      letter.style.cssText = 'color:#0b1220;font-weight:700;width:100%;height:100%;display:flex;'
        + 'align-items:center;justify-content:center;background:' + colorFor(item && item.name);
      thumb.appendChild(letter);
    }
    row.appendChild(thumb);
    var main = el('div', 'st-row-main');
    var top = el('div', 'st-row-top');
    top.appendChild(el('span', 'st-no', '#' + num(item && item.number, index + 1)));
    top.appendChild(el('span', 'st-name', text(item && item.name)));
    main.appendChild(top);
    var meta = el('div', 'st-meta');
    var category = el('span', 'st-tag st-on' + (item && item.categoryAuto ? ' st-auto' : ''),
      '分类：' + (text(item && item.category) || '未分类') + (item && item.categoryAuto ? '（自动）' : ''));
    meta.appendChild(category);
    var base = text(item && item.baseModel);
    var stack = text(item && item.stackLabel);
    if (base) meta.appendChild(el('span', 'st-tag', base + (text(item && item.baseModelSource) ? '（推断）' : '')));
    else if (stack) meta.appendChild(el('span', 'st-tag', stack));
    var width = num(item && item.width, 0);
    var height = num(item && item.height, 0);
    if (width > 0 && height > 0) meta.appendChild(el('span', 'st-tag', width + '×' + height));
    main.appendChild(meta);
    if (item && item.modelSummary) main.appendChild(el('div', 'st-sub', truncate(item.modelSummary, 60)));
    row.appendChild(main);
    row.appendChild(el('span', 'st-chev', '›'));
    row.addEventListener('click', function () { rowSheet(item); });
    return row;
  }
  function summaryLine() {
    var box = el('div', 'st-summary');
    box.appendChild(el('span', '', '样式库 ' + num(S.library, (S.styles || []).length) + ' 个'));
    var groups = (S.baseModelGroups || []).slice(0, 4);
    if (groups.length) {
      box.appendChild(el('span', '', '底模：' + groups.map(function (group) {
        return text(group && group.baseModel) + ' ' + num(group && group.count, 0);
      }).join('、')));
    }
    if (S.query) box.appendChild(el('span', 'st-tag st-on', '筛选：' + S.query));
    return box;
  }
  function render() {
    if (!S.groups) return;
    var host = S.groups;
    clear(host);
    if (S.summary) clear(S.summary);
    if (S.summary) S.summary.appendChild(summaryLine());
    if (S.loading && !S.styles) {
      for (var i = 0; i < 4; i++) host.appendChild(el('div', 'st-sk'));
      return;
    }
    if (S.error) {
      var box = el('div', 'st-state st-err', '样式库读取失败：' + S.error);
      var retry = el('button', 'st-retry', '重试');
      retry.type = 'button';
      retry.addEventListener('click', function () { loadStyles(true); });
      box.appendChild(document.createElement('br'));
      box.appendChild(retry);
      host.appendChild(box);
      if (!S.styles || !S.styles.length) return;
    }
    var styles = (S.styles || []).filter(matches);
    var all = (S.styles || []);
    if (!all.length) {
      var empty = el('div', 'st-empty');
      empty.appendChild(el('div', 'st-empty-icon', '🎨'));
      empty.appendChild(el('div', 'st-empty-text', '样式库还是空的'));
      empty.appendChild(el('div', 'st-empty-sub', '点右下角的「＋ 保存样式」把当前提示词存成一条样式。'));
      host.appendChild(empty);
      return;
    }
    if (!styles.length) {
      host.appendChild(el('div', 'st-state', '没有匹配「' + S.query + '」的样式（共 ' + all.length + ' 条）。'));
      return;
    }
    var buckets = groupOrder();
    var placed = {};
    var visibleTotal = 0;
    buckets.forEach(function (bucket) {
      var rows = styles.filter(function (item) { return foldKey(item) === bucket.key && !placed[text(item && item.name)]; });
      if (!rows.length) return;
      rows.forEach(function (item) { placed[text(item && item.name)] = true; });
      visibleTotal += rows.length;
      var group = el('div', 'st-group' + (S.folded[bucket.key] ? ' st-fold' : ''));
      group.setAttribute('data-category', bucket.name);
      var head = el('button', 'st-group-head');
      head.type = 'button';
      head.appendChild(el('span', 'st-arrow', '▾'));
      head.appendChild(el('span', 'st-group-name', bucket.name));
      head.appendChild(el('span', 'st-count', rows.length + ' 条'));
      head.addEventListener('click', function () {
        S.folded[bucket.key] = !S.folded[bucket.key];
        group.classList.toggle('st-fold');
      });
      group.appendChild(head);
      var rowsBox = el('div', 'st-group-rows');
      rows.forEach(function (item) { rowsBox.appendChild(styleRow(item, num(item && item.number, 1) - 1)); });
      group.appendChild(rowsBox);
      host.appendChild(group);
    });
    var leftovers = styles.filter(function (item) { return !placed[text(item && item.name)]; });
    if (leftovers.length) {
      var other = el('div', 'st-group');
      other.setAttribute('data-category', '其他');
      var otherHead = el('div', 'st-group-head');
      otherHead.appendChild(el('span', 'st-arrow', '▾'));
      otherHead.appendChild(el('span', 'st-group-name', '其他'));
      otherHead.appendChild(el('span', 'st-count', leftovers.length + ' 条'));
      other.appendChild(otherHead);
      var otherRows = el('div', 'st-group-rows');
      leftovers.forEach(function (item) { otherRows.appendChild(styleRow(item, num(item && item.number, 1) - 1)); });
      other.appendChild(otherRows);
      host.appendChild(other);
    }
    if (S.query) host.appendChild(el('div', 'st-state', '匹配 ' + visibleTotal + ' / 共 ' + all.length + ' 条。'));
  }

  /* ───────────────────────── 6. 选择面板（分类 / 封面） ───────────────────────── */
  /** 用契约的底部动作面板做选择：返回 Promise<值>。 */
  function askChoice(title, options) {
    return new Promise(function (resolve) {
      var taken = false;
      var items = options.map(function (option) {
        return {
          text: option.text,
          danger: !!option.danger,
          onSelect: function () { if (!taken) { taken = true; resolve(option.value); } }
        };
      });
      try { P.sheet({ title: title, items: items }); } catch (error) { toast(title + '打不开：' + (text(error && error.message) || error)); }
      /* 面板被关掉（没选）：给一个 null，调用方当作"什么都没做"。 */
      window.setTimeout(function () { if (!taken) { taken = true; resolve(null); } }, 180000);
    });
  }
  function categoryChoices(name) {
    var current = '';
    (S.styles || []).forEach(function (item) { if (text(item && item.name) === name) current = text(item && item.category); });
    var options = [];
    (S.categories || []).forEach(function (entry) {
      var label = text(entry && entry.name) || '未分类';
      options.push({
        text: (label === current ? '● ' : '') + label + '（' + num(entry && entry.count, 0) + ' 条）',
        value: (text(entry && entry.kind) === 'none' || label === '未分类') ? '' : label
      });
    });
    options.push({ text: '未分类（回自动规则）', value: '' });
    if (current) options.push({ text: '保持原分类：' + current, value: current });
    return options;
  }
  /** 封面选择：默认（最近一次生成图）/ 不设封面 / 最近 8 张生成图。返回 Promise<值|undefined>。 */
  function pickCover() {
    return askChoice('选择封面', [
      { text: '默认（最近一次生成图）', value: '' },
      { text: '不设封面', value: '-' },
      { text: '从最近生成的图里选…', value: '__recent__' }
    ]).then(function (choice) {
      if (choice === null || choice === undefined) return undefined;
      if (choice !== '__recent__') return choice;
      return Promise.resolve(P.api('/api/images', { body: { limit: STYLE_COVER_RECENT } })).then(function (data) {
        var images = (data && Array.isArray(data.images) ? data.images : []).slice(0, STYLE_COVER_RECENT);
        if (!images.length) { toast('还没有生成过的图片'); return undefined; }
        var options = images.map(function (image, index) {
          return { text: (index + 1) + '. ' + text(image && (image.name || shortName(image.path))), value: text(image && image.path) };
        });
        options.push({ text: '返回（不改封面）', value: undefined });
        return askChoice('最近生成的图', options);
      }).catch(function (error) {
        toast('读取最近生成图失败：' + (text(error && error.message) || String(error)));
        return undefined;
      });
    });
  }
  function chooseAndSetCover(name) {
    return pickCover().then(function (cover) {
      if (cover === undefined) return null;
      var remove = /^(?:-|none|清除|不设)$/i.test(text(cover).trim());
      if (remove) {
        /* 只有外层的「不设封面」才是 '-'；具体图片路径走下面那条。 */
        return setCoverValue(name, '-', '不设封面');
      }
      if (!cover) {
        /* 默认档：先用 /api/images 把最近那张图找出来，再走 /api/styles/cover 显式设置。 */
        return Promise.resolve(P.api('/api/images', { body: { limit: 1 } })).then(function (data) {
          var path = text(data && data.images && data.images[0] && data.images[0].path);
          if (!path) { toast('还没有生成过的图片，没法设成默认封面'); return null; }
          return setCoverValue(name, path, '默认（最近一次生成图）');
        }).catch(function (error) {
          toast('读取最近生成图失败：' + (text(error && error.message) || String(error)));
          return null;
        });
      }
      return setCoverValue(name, cover, shortName(cover));
    });
  }
  function setCoverValue(name, path, label) {
    S.busy = true;
    return Promise.resolve(P.api('/api/styles/cover', { body: { name: name, path: path } })).then(function (data) {
      S.busy = false;
      if (data) applyStylesPayload(data);
      toast(data && data.message ? text(data.message) : ('样式封面已更新：' + text(label)));
      return data;
    }).catch(function (error) {
      S.busy = false;
      toast('换封面失败：' + (text(error && error.message) || String(error)));
      return null;
    });
  }

  /* ───────────────────────── 7. 每行的动作面板（6 项，全部走底部 sheet） ───────────────────────── */
  function rowSheet(item) {
    var name = text(item && item.name);
    return Promise.resolve(P.sheet({
      title: '样式：' + name,
      items: [
        {
          text: '载入（写入你的个人提示词）',
          onSelect: function () {
            editStyles('load', name, { noLora: !!S.noLora }).then(function (data) {
              if (data) toast('已写入你的个人提示词');
            });
          }
        },
        {
          text: '覆盖保存（用当前提示词覆盖这一条）',
          onSelect: function () { openSaveForm(name); }
        },
        {
          text: '换分类…',
          onSelect: function () {
            askChoice('把「' + name + '」换到哪个分类', categoryChoices(name)).then(function (category) {
              if (category === null || category === undefined) return null;
              return editStyles('category', name, { category: category });
            });
          }
        },
        {
          text: '换封面…',
          onSelect: function () { chooseAndSetCover(name); }
        },
        {
          text: '查看原文（只读）',
          onSelect: function () { openTextViewer(item); }
        },
        {
          text: '删除样式',
          danger: true,
          onSelect: function () {
            Promise.resolve(P.confirm({
              title: '删除样式',
              text: '删除「' + name + '」？样式库里的这一条会被移除，已生成的图片不受影响。',
              ok: '删除',
              danger: true
            })).then(function (yes) { if (yes) editStyles('delete', name); });
          }
        }
      ]
    }));
  }

  /** 只读原文面板：显示分类 / 尺寸 / 底模这些元信息与正向反向正文。正文只在面板里出现。 */
  function openTextViewer(item) {
    var data = item || {};
    var name = text(data.name);
    var running = false;
    (S.styles || []).forEach(function (row) { if (text(row && row.name) === name) data = row; });
    openPanel('样式原文：' + name, function (body) {
      var meta = el('div', 'st-meta');
      meta.appendChild(el('span', 'st-tag st-on', '分类：' + (text(data.category) || '未分类')
        + (data.categoryAuto ? '（自动）' : '（手动）')));
      if (num(data.width, 0) > 0 && num(data.height, 0) > 0) meta.appendChild(el('span', 'st-tag', num(data.width, 0) + '×' + num(data.height, 0)));
      if (data.baseModel) meta.appendChild(el('span', 'st-tag', text(data.baseModel)));
      if (data.stackLabel) meta.appendChild(el('span', 'st-tag', text(data.stackLabel)));
      body.appendChild(meta);
      var posTitle = el('div', 'st-sub', '正向提示词');
      body.appendChild(posTitle);
      body.appendChild(el('div', 'st-pre', text(data.positive) || '（空）'));
      body.appendChild(el('div', 'st-sub', '反向提示词'));
      body.appendChild(el('div', 'st-pre', text(data.negative) || '（空）'));
      if (data.modelSummary) {
        body.appendChild(el('div', 'st-sub', '模型参数（载入时一并套用）'));
        body.appendChild(el('div', 'st-pre', text(data.modelSummary)));
      }
      body.appendChild(el('div', 'st-sub', '这是一份固定模板：载入会把上面两段原样写进你个人的提示词。'));
    }, function (foot, close) {
      var ok = el('button', 'st-btn st-primary', '关闭');
      ok.type = 'button';
      ok.addEventListener('click', close);
      foot.appendChild(ok);
    });
  }

  /* ───────────────────────── 8. 保存 / 覆盖表单（底部面板） ───────────────────────── */
  function openSaveForm(prefill) {
    var name = text(prefill);
    var cover = '';
    openPanel(prefill ? '覆盖保存样式' : '保存当前提示词为样式', function (body) {
      var nameField = el('div', 'st-field');
      nameField.appendChild(el('label', '', '样式名称'));
      var input = el('input');
      input.type = 'text';
      input.placeholder = '给这一条起个名字';
      input.value = name;
      input.setAttribute('data-field', 'style-name');
      input.addEventListener('input', function () { name = text(input.value); });
      nameField.appendChild(input);
      body.appendChild(nameField);

      var coverField = el('div', 'st-field');
      coverField.appendChild(el('label', '', '封面（保存/覆盖时一起写入）'));
      var coverRow = el('div', 'st-cover-row');
      var preview = el('div', 'st-cover-prev', '默认');
      var label = el('div', 'st-cover-value', '默认（最近一次生成图）');
      var pick = el('button', 'st-cover-btn', '选择封面');
      pick.type = 'button';
      pick.addEventListener('click', function () {
        pickCover().then(function (value) {
          if (value === undefined) return;
          cover = value;
          clear(preview);
          if (value === '-') { preview.appendChild(el('span', '', '不设')); label.textContent = '不设封面'; return; }
          if (!value) { preview.appendChild(el('span', '', '默认')); label.textContent = '默认（最近一次生成图）'; return; }
          var img = document.createElement('img');
          img.alt = '封面预览';
          img.src = localImageSrc(value);
          img.addEventListener('error', function () { clear(preview); preview.appendChild(el('span', '', '无图')); });
          clear(preview);
          preview.appendChild(img);
          label.textContent = shortName(value);
        });
      });
      coverRow.appendChild(preview);
      coverRow.appendChild(label);
      coverRow.appendChild(pick);
      coverField.appendChild(coverRow);
      body.appendChild(coverField);

      var noLoraField = el('label', 'st-check');
      var check = el('input');
      check.type = 'checkbox';
      check.checked = !!S.noLora;
      check.addEventListener('change', function () { S.noLora = !!check.checked; renderToggle(); });
      noLoraField.appendChild(check);
      noLoraField.appendChild(el('span', '', '保存/载入时不套用 LoRA 标签'));
      body.appendChild(noLoraField);
      body.appendChild(el('div', 'st-sub', '名称留空就只覆盖同名的样式；「保存」遇到同名会提示，「覆盖保存」直接覆盖。'));

      window.setTimeout(function () { try { input.focus(); } catch (error) { /* 自动聚焦失败无所谓 */ } }, 220);
    }, function (foot, close) {
      var save = el('button', 'st-btn', '保存');
      save.type = 'button';
      save.addEventListener('click', function () {
        var target = text(name).trim();
        if (!target) { toast('先给样式起个名字'); return; }
        close();
        editStyles('save', target, { overwrite: false, noLora: !!S.noLora, cover: cover });
      });
      var overwrite = el('button', 'st-btn st-primary', '覆盖保存');
      overwrite.type = 'button';
      overwrite.addEventListener('click', function () {
        var target = text(name).trim();
        if (!target) { toast('先给样式起个名字'); return; }
        close();
        editStyles('overwrite', target, { overwrite: true, noLora: !!S.noLora, cover: cover });
      });
      foot.appendChild(save);
      foot.appendChild(overwrite);
    });
  }

  /* ───────────────────────── 9. 入口（app bar 是外壳的，这里只给溢出菜单与刷新） ───────────────────────── */
  function renderToggle() {
    if (!S.toggleRow) return;
    S.toggleRow.classList.toggle('st-on', !!S.noLora);
    var box = S.toggleRow.querySelector('.st-toggle-text');
    if (box) box.textContent = S.noLora ? '载入时不套用 LoRA 标签（已开）' : '载入时套用样式里的 LoRA 标签';
  }
  function toggleNoLora() {
    S.noLora = !S.noLora;
    renderToggle();
    toast(S.noLora ? '载入样式时将跳过 LoRA 标签' : '载入样式时会带上 LoRA 标签');
  }
  function stylesOverflow() {
    return Promise.resolve(P.sheet({
      title: '样式库',
      items: [
        { text: '保存当前提示词为样式', onSelect: function () { openSaveForm(''); } },
        { text: (S.noLora ? '✓ ' : '') + '载入时不套用 LoRA 标签', onSelect: toggleNoLora },
        { text: '刷新样式库', onSelect: function () { loadStyles(true); } },
        { text: '回到顶部（底模摘要）', onSelect: function () { var main = document.getElementById('m-main'); if (main) main.scrollTop = 0; } }
      ]
    }));
  }
  /** 外壳「更多操作」里的这一屏动作。 */
  function stylesMenu() {
    return [
      { text: '保存当前提示词为样式', onSelect: function () { openSaveForm(''); } },
      { text: (S.noLora ? '✓ ' : '') + '载入时不套用 LoRA 标签', onSelect: toggleNoLora },
      { text: '刷新样式库', onSelect: function () { loadStyles(true); } }
    ];
  }

  function mount(root) {
    S.root = root;
    root.classList.add('st-screen');
    var ptr = el('div', 'st-ptr', '下拉刷新');
    root.appendChild(ptr);

    var body = el('div', 'st-body');
    S.body = body;

    /* 搜索框（16px 字号，避免手机自动缩放） */
    S.searchRow = el('div', 'st-search');
    var search = el('input');
    search.type = 'search';
    search.placeholder = '搜索名称 / 分类 / 底模';
    search.value = S.query;
    search.setAttribute('data-field', 'style-search');
    var clearBtn = el('button', 'st-clear', '×');
    clearBtn.type = 'button';
    clearBtn.hidden = !S.query;
    search.addEventListener('input', function () {
      S.query = text(search.value).trim();
      clearBtn.hidden = !S.query;
      render();
    });
    clearBtn.addEventListener('click', function () {
      search.value = '';
      S.query = '';
      clearBtn.hidden = true;
      render();
    });
    S.searchRow.appendChild(search);
    S.searchRow.appendChild(clearBtn);
    body.appendChild(S.searchRow);

    /* LoRA 开关（每行「载入」按这个决定要不要跳过 LoRA 标签） */
    S.toggleRow = el('button', 'st-toggle');
    S.toggleRow.type = 'button';
    S.toggleRow.appendChild(el('span', 'st-switch', ''));
    S.toggleRow.appendChild(el('span', 'st-toggle-text', ''));
    S.toggleRow.addEventListener('click', toggleNoLora);
    body.appendChild(S.toggleRow);
    renderToggle();

    var groups = el('div', 'st-groups');
    S.groups = groups;
    S.summary = el('div', 'st-summary-host');
    body.appendChild(S.summary);
    body.appendChild(groups);
    root.appendChild(body);

    /* 悬浮「保存样式」（44px 高，浮在底部 tab 上方） */
    var fab = el('button', 'st-fab', '＋ 保存样式');
    fab.type = 'button';
    fab.setAttribute('data-field', 'style-save');
    fab.addEventListener('click', function () { openSaveForm(''); });
    root.appendChild(fab);

    render();
    loadStyles(false);
    installPullToRefresh(ptr, function () { return loadStyles(false); });
    S.timer = P.pollWhileVisible(function () {
      if (typeof P.current === 'function' && P.current() !== 'styles') return;
      if (S.busy || S.loading) return;
      if (Date.now() - S.lastAt < 20000) return;
      loadStyles(false);
    }, 6000);
  }

  function refresh() {
    if (!S.root) return Promise.resolve(null);
    return Promise.resolve(loadStyles(false));
  }

  P.register('styles', { title: '样式', mount: mount, refresh: refresh, menu: stylesMenu });
})();
