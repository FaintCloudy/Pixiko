/*
 * Pixiko 手机端 Web UI ·「更多」列表屏（M4）。
 *
 * 注册 id：'more'。App 习惯的“设置 / 更多”页：分组 + 行 + chevron，
 * 多点一下才多一屏——这里只负责导航与连接状态，不重复别的屏幕的功能。
 *
 * 依赖 M1 的核心契约（window.PixikoM）：register / go / current / toast / confirm /
 * api / token / scope / refreshStatus / el / clear / state。
 * 本文件不创建任何全局变量（整份包在一个 IIFE 里），样式只注入自己 .mo-* 前缀的那一份。
 */
(function () {
  'use strict';

  /* ---------------------------------------------------------------- 样式（只注入一次） */

  var STYLE_ID = 'mo-style';

  function injectStyle() {
    if (document.getElementById(STYLE_ID)) return;
    var style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = [
      '.mo-wrap{display:flex;flex-direction:column;gap:14px;padding:12px 12px 28px;box-sizing:border-box}',
      '.mo-card{background:#131c2e;border-radius:14px;overflow:hidden}',
      '.mo-sec-title{font-size:12px;color:#93a4c4;letter-spacing:.08em;padding:2px 6px 8px}',
      '.mo-row{display:flex;align-items:center;gap:12px;width:100%;box-sizing:border-box;min-height:48px;',
      'padding:11px 14px;background:transparent;border:0;color:#e8eefc;font-size:16px;text-align:left;',
      'text-decoration:none;-webkit-tap-highlight-color:transparent;font-family:inherit}',
      '.mo-row:active{background:rgba(90,162,255,.14)}',
      '.mo-row+.mo-row{border-top:1px solid rgba(147,164,196,.13)}',
      '.mo-row-label{flex:1 1 auto;min-width:0}',
      '.mo-row-sub{font-size:12.5px;color:#93a4c4;margin-top:3px;line-height:1.4;word-break:break-all}',
      '.mo-row-flag{font-size:12.5px;color:#5aa2ff;flex:0 0 auto;max-width:42%;text-align:right;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
      '.mo-chev{flex:0 0 18px;width:18px;height:18px;stroke:#93a4c4;fill:none;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}',
      '.mo-conn{padding:14px}',
      '.mo-conn-head{display:flex;align-items:center;gap:8px;font-size:16px;color:#e8eefc}',
      '.mo-dot{width:9px;height:9px;border-radius:50%;background:#93a4c4;flex:0 0 9px}',
      '.mo-dot.ok{background:#4ad991}.mo-dot.bad{background:#ff6b6b}',
      '.mo-kv{display:flex;gap:10px;font-size:13.5px;color:#93a4c4;margin-top:10px;line-height:1.5}',
      '.mo-kv b{color:#e8eefc;font-weight:500;word-break:break-all}',
      '.mo-kv-key{flex:0 0 62px;color:#93a4c4}',
      '.mo-btn{min-height:44px;padding:0 16px;border-radius:12px;border:0;background:#5aa2ff;color:#08101f;',
      'font-size:15px;font-weight:600;font-family:inherit;-webkit-tap-highlight-color:transparent}',
      '.mo-btn:active{opacity:.78}',
      '.mo-btn.ghost{background:rgba(90,162,255,.14);color:#5aa2ff}',
      '.mo-btn[disabled]{opacity:.5}',
      '.mo-actions{display:flex;gap:10px;margin-top:12px;flex-wrap:wrap}',
      '.mo-note{font-size:12.5px;color:#93a4c4;margin-top:10px;line-height:1.55}',
      '.mo-foot{font-size:12px;color:#93a4c4;line-height:1.6;padding:10px 14px 14px;font-family:Consolas,Menlo,monospace}',
      '.mo-note.bad{color:#ff6b6b}',
      '.mo-note.ok{color:#4ad991}',
      '.mo-loading{font-size:14px;color:#93a4c4;padding:14px}',
      '.mo-badge{flex:0 0 auto;min-width:20px;height:20px;line-height:20px;border-radius:10px;background:rgba(255,107,107,.18);',
      'color:#ff6b6b;font-size:12px;text-align:center;padding:0 6px;box-sizing:border-box}'
    ].join('');
    document.head.appendChild(style);
  }

  /* ---------------------------------------------------------------- 小工具（本文件私有） */

  var api = function () { return window.PixikoM; };

  /** 等 M1 的 app.js 落地（defer 顺序正常时这里是同步的，只是兜底）。 */
  function whenReady(fn) {
    if (window.PixikoM && typeof window.PixikoM.register === 'function') { fn(window.PixikoM); return; }
    var tries = 0;
    (function wait() {
      tries += 1;
      if (window.PixikoM && typeof window.PixikoM.register === 'function') { fn(window.PixikoM); return; }
      if (tries > 400) { console.warn('[more] PixikoM 未就绪，屏幕未注册'); return; }
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

  function toast(text) {
    var M = api();
    if (M && typeof M.toast === 'function') M.toast(text);
  }

  function chevron() {
    var span = document.createElement('span');
    span.className = 'mo-chev-wrap';
    span.innerHTML = '<svg class="mo-chev" viewBox="0 0 24 24" aria-hidden="true"><path d="M9 5l7 7-7 7"/></svg>';
    return span.firstChild;
  }

  /* ---------------------------------------------------------------- 结构 */

  /** 分组列表：与任务书一一对应；每一项点击后 PixikoM.go(id)。 */
  var GROUPS = [
    {
      title: '内容',
      rows: [
        { label: '提示词', sub: '正向 / 反向，词条增删与分类', go: 'prompt' },
        { label: 'LoRA', sub: '本机模型列表、权重、下载', go: 'loras' },
        { label: '样式', sub: '保存的样式与预览图', go: 'styles' },
        { label: '回执', sub: '指令结果、任务图片、未读', go: 'quest', badge: 'quest' }
      ]
    },
    {
      title: '机器人',
      rows: [
        { label: '聊天设置', sub: '开关、频率、性格、通知', go: 'chatcfg' },
        { label: '提示词集', sub: '保存 / 加载 / 改名 / 删除', go: 'functions' },
        { label: '参数预设', sub: '尺寸、采样、步数、CFG、种子', go: 'presets' },
        { label: '系统', sub: '运行状态、生成参数、SD 启动', go: 'system' },
        { label: '日志', sub: 'QQ 侧 / 网页侧日志', go: 'logs' },
        { label: '首次配置', sub: '机器人名字、owner、密钥通道', go: 'setup' }
      ]
    },
    {
      title: '关于',
      rows: [
        { label: '帮助', sub: '全部指令说明', go: 'help' },
        { label: '服务器信息', sub: '地址、令牌长度、scope', go: 'server' },
        { label: '关于本 App', sub: '版本与许可', go: 'about' }
      ]
    }
  ];

  /**
   * Android 外壳的 4 个原生桥（外壳在 /m 下把自己的 ActionBar 收起来了，
   * 这些入口得由手机界面提供）。**方法在才显示那一行**：纯浏览器打开 /m 时一行都不显示，
   * 不会出现点了没反应的按钮。「完整控制台」不需要桥，永远显示。
   */
  var APP_ROWS = [
    {
      method: 'openServerSettings', label: '服务器设置',
      sub: '连哪台机器人、局域网扫描、测试连接（Android App 的原生界面）'
    },
    {
      method: 'clearWebCache', label: '清空网页缓存',
      sub: '丢掉本地缓存的页面与样式，重新从机器人拉一遍',
      confirm: { title: '清空网页缓存', text: '清掉这台设备上缓存的页面与样式，然后重新加载？不会动令牌与 scope。', ok: '清空' }
    },
    {
      method: 'clearLoginState', label: '清除登录状态',
      sub: '会退出控制台登录，需要重新在设置里填访问令牌',
      confirm: {
        title: '清除登录状态',
        text: '清除本机保存的访问令牌？清掉之后会退出控制台登录，需要重新在服务器设置里填令牌（config.json 的 webui.access_token）。',
        ok: '清除', danger: true
      }
    },
    {
      method: 'openInBrowser', label: '在浏览器打开',
      sub: '用系统浏览器打开当前地址（方便复制链接、看完整控制台）'
    }
  ];

  var root = null;

  function render(target) {
    var M = api();
    if (!M) return;
    root = target || root;
    if (!root) return;
    clear(root);

    var wrap = make('div', 'mo-wrap');
    wrap.appendChild(connectionCard());
    GROUPS.forEach(function (group) { wrap.appendChild(groupCard(group)); });
    wrap.appendChild(appCard());
    root.appendChild(wrap);
  }

  /** 顶部一行：当前连接（服务器地址 + 令牌状态 + 重试连接）。 */
  function connectionCard() {
    var M = api();
    var card = make('div', 'mo-card');
    var box = make('div', 'mo-conn');

    var head = make('div', 'mo-conn-head');
    var dot = make('span', 'mo-dot');
    var title = make('span', null, '当前连接');
    head.appendChild(dot);
    head.appendChild(title);
    box.appendChild(head);

    var token = '';
    try { token = String(M.token() || ''); } catch (error) { token = ''; }
    var scope = '';
    try { scope = String(M.scope() || ''); } catch (error) { scope = ''; }

    box.appendChild(kv('服务器', location.origin));
    box.appendChild(kv('令牌', token ? ('已配置（' + token.length + ' 位）') : '未配置'));
    box.appendChild(kv('scope', scope || '（默认）'));

    var note = make('div', 'mo-note', '');
    var actions = make('div', 'mo-actions');
    var button = make('button', 'mo-btn ghost', '重试连接');
    button.type = 'button';
    button.id = 'mo-retry';
    actions.appendChild(button);
    box.appendChild(actions);
    box.appendChild(note);

    function setState(kind, text) {
      dot.className = 'mo-dot ' + (kind || '');
      note.className = 'mo-note' + (kind === 'ok' ? ' ok' : kind === 'bad' ? ' bad' : '');
      note.textContent = text;
    }

    // 初始状态：只看本地有没有令牌，真连不连得上要点「重试连接」才知道。
    if (token) setState('', '点「重试连接」确认服务器可用。');
    else setState('bad', '这台设备还没有配置访问令牌：请在 Android App 的服务器设置里填入。');

    button.addEventListener('click', function () {
      if (button.disabled) return;
      button.disabled = true;
      setState('', '正在连接…');
      Promise.resolve()
        .then(function () { return M.refreshStatus(); })
        .then(function () { setState('ok', '连接正常。'); toast('连接正常'); })
        .catch(function (error) { return classify(error).then(function (result) { setState('bad', result); toast(result); }); })
        .then(function () { button.disabled = false; }, function () { button.disabled = false; });
    });

    card.appendChild(box);
    return card;
  }

  /**
   * 失败分档：能连上服务器但 API 不认 → 令牌不对；连 healthz 都打不开 → 连不上。
   * 优先用 M1 的 api() 挂在 Error 上的分类码（network / timeout / http / unauthorized），
   * 没有分类码时才用 healthz（不需要令牌的健康检查）来分辨这两档。
   */
  function classify(error) {
    var message = String((error && error.message) || error || '未知错误');
    var code = String((error && error.code) || '');
    if (code === 'unauthorized') return Promise.resolve('令牌不对或已失效：' + message);
    if (code === 'network' || code === 'timeout') return Promise.resolve('连不上服务器：' + message);
    var authLike = /unauthor|令牌|token|401|403/i.test(message);
    return fetch('/healthz', { cache: 'no-store' })
      .then(function (response) {
        if (!response || !response.ok) return '连不上服务器：' + message;
        return authLike ? ('令牌不对或已失效：' + message) : ('服务器有响应，但这个请求失败了：' + message);
      })
      .catch(function () { return '连不上服务器：' + message; });
  }

  function kv(key, value) {
    var row = make('div', 'mo-kv');
    row.appendChild(make('span', 'mo-kv-key', key));
    var box = make('div');
    box.appendChild(make('b', null, value));
    row.appendChild(box);
    return row;
  }

  /** 一行的外壳（标签 + 说明 + 自定义 data-* 属性）；菜单行与原生行都用它。 */
  function rowShell(label, sub, attrs) {
    var button = make('button', 'mo-row');
    button.type = 'button';
    Object.keys(attrs || {}).forEach(function (key) { button.setAttribute(key, attrs[key]); });
    var box = make('div', 'mo-row-label');
    box.appendChild(make('div', null, label));
    if (sub) box.appendChild(make('div', 'mo-row-sub', sub));
    button.appendChild(box);
    return button;
  }

  function groupCard(group) {
    var M = api();
    var card = make('div', 'mo-card');
    card.appendChild(make('div', 'mo-sec-title', group.title));
    group.rows.forEach(function (row) {
      var button = rowShell(row.label, row.sub, { 'data-mo-go': row.go, 'data-mo-row': row.label });
      if (row.badge) {
        var badge = questBadge(row.badge);
        if (badge) button.appendChild(badge);
      }
      button.appendChild(chevron());
      button.addEventListener('click', function () {
        try { M.go(row.go); } catch (error) { toast('打不开这一屏：' + ((error && error.message) || error)); }
      });
      card.appendChild(button);
    });
    return card;
  }

  /** 回执未读角标：只有 /api/status 已经取到数据时才显示，取不到就不显示（不额外发请求）。 */
  function questBadge(kind) {
    var M = api();
    var status = M.state && M.state.status;
    if (!status || !status.quests) return null;
    var unread = Number(status.quests.unread || 0);
    if (!unread) return null;
    return make('span', 'mo-badge', String(unread));
  }

  /** 原生桥的安全调用器：这个方法在就返回一个函数，不在就返回 null（那一行不显示）。 */
  function bridge(method) {
    var native = window.PixikoNative;
    if (!native || typeof native[method] !== 'function') return null;
    return function () {
      try { native[method](); return true; }
      catch (error) { toast('原生接口调用失败：' + ((error && error.message) || error)); return false; }
    };
  }

  function confirmBox(title, text, ok, danger) {
    var M = api();
    if (M && typeof M.confirm === 'function') return M.confirm({ title: title, text: text, ok: ok || '确定', danger: !!danger });
    return Promise.resolve(window.confirm(text));
  }

  /** 「App」组：Android 外壳的入口（有桥才显示那几行）+ 完整控制台兜底 + 页脚说明。 */
  function appCard() {
    var card = make('div', 'mo-card');
    card.appendChild(make('div', 'mo-sec-title', 'App'));

    APP_ROWS.forEach(function (row) {
      var call = bridge(row.method);
      if (!call) return;                        // 纯浏览器里没有桥：这一行根本不建
      var button = rowShell(row.label, row.sub, { 'data-mo-native': row.method });
      button.appendChild(chevron());
      button.addEventListener('click', function () {
        if (!row.confirm) { call(); return; }
        confirmBox(row.confirm.title, row.confirm.text, row.confirm.ok, row.confirm.danger)
          .then(function (ok) { if (ok) call(); });
      });
      card.appendChild(button);
    });

    card.appendChild(fallbackRow());
    card.appendChild(make('div', 'mo-foot', '手机界面：/m（app 默认）；完整控制台：/'));
    return card;
  }

  /**
   * 兜底入口：同窗口跳到完整控制台（Android WebView 里 target=_blank 可能被吞掉，所以不放 _blank）。
   * 用真 <a href="/"> 是为了"地址可查、可被拦下"；在 Android 外壳里（有 PixikoNative）再显式走一次
   * location.href，让外壳跟着纠正它自己的界面选择与原生栏；纯浏览器里就交给 <a> 的默认行为，
   * 任何 preventDefault 都能拦住（不抢跳）。
   */
  function fallbackRow() {
    var link = document.createElement('a');
    link.className = 'mo-row';
    link.href = '/';
    link.rel = 'noopener';
    link.setAttribute('data-mo-fallback', 'web');
    var label = make('div', 'mo-row-label');
    label.appendChild(make('div', null, '完整控制台（网页版）'));
    label.appendChild(make('div', 'mo-row-sub', '功能与桌面控制台一致；同窗口打开，返回键可回到手机界面。'));
    link.appendChild(label);
    link.appendChild(chevron());
    link.addEventListener('click', function (event) {
      if (event.defaultPrevented) return;              // 被拦下（探针/外壳）就不抢跳
      if (!window.PixikoNative) return;                // 纯浏览器：走 <a href="/"> 默认行为
      event.preventDefault();
      location.href = '/';
    });
    return link;
  }

  /**
   * 深链兜底：app.js 的 boot() 在 defer 脚本执行时就跑了（那一刻 screen-*.js 还没注册），
   * 于是直开 #/more 会先画一张「还没做好」的占位并把 active 挂在它身上；注册完这里把 active 要回来。
   * M1 把启动顺序修好之后这段不会触发（条件不成立直接返回）。
   */
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
    M.register('more', {
      title: '更多',
      mount: function (target) { render(target); },
      // 重新进入这一屏时按 M1 的约定刷新一次（可能不带参数）。
      refresh: function (target) { render(target || root); }
    });
    claimRoute('more');
  });
})();
