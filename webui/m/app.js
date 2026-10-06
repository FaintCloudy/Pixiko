/* ============================================================================
 * Pixiko 手机端 App 界面 —— 核心
 *
 * 这一份文件是**整套移动 UI 的地基**：外壳（app bar / 底部 tab / hash 路由）、
 * 浮层（sheet / confirm / prompt / toast / 图片查看器）、以及两个核心屏幕
 * （对话 chat、出图 gen）。其余屏幕由 screen-*.js 通过 PixikoM.register() 挂进来。
 *
 * 与桌面控制台（webui/app.js）的关系：**一份代码都不共用**，但复用同一套 /api 接口。
 * 所有接口的形状都是照 src/main/java/cn/szu/bot/web/WebApiController.java 的
 * route() 分支与 Bot.java 的 web*() 实现逐个核对过的（字段名见各函数注释）。
 *
 * 目录（按行号段）：
 *   1. 常量与工具
 *   2. PixikoM：契约实现（api/token/scope/toast/sheet/confirm/prompt/…）
 *   3. 状态：state + refreshStatus（含未读角标广播）
 *   4. 外壳：app bar / 底部 tab / hash 路由 / 下拉刷新 / 键盘
 *   5. 屏幕：chat（对话）
 *   6. 屏幕：gen（出图）
 *   7. 启动
 * ========================================================================== */
(function () {
  'use strict';

  /* ── 1. 常量与工具 ───────────────────────────────────────────────────── */

  var TOKEN_KEY = 'kotori-webui-token';        // 同源 localStorage，Android 外壳会自动写
  var SCOPE_KEY = 'pixiko-scope';              // 控制台/浏览器的 scope 键，本地，默认 web
  var DEFAULT_SCOPE = 'web';
  /**
   * 本机（这一台手机）的设备 scope 在 localStorage 里的镜像键。
   *
   * <p>**刻意不复用 pixiko-scope**：两套界面同源（同一台服务器的 localStorage 是同一个），
   * 共用键会让设备 scope 漏进桌面控制台、或者控制台手改的 scope 覆盖掉设备身份。
   * 键名与 Android 侧的 NativeHook.DEVICE_SCOPE_KEY 必须完全一致。
   */
  var DEVICE_SCOPE_KEY = 'pixiko-device-scope';
  /**
   * 设备 scope 的形状：dev- 加 12 位小写十六进制。
   *
   * <p>为什么由 Android 外壳给而不是网页自己随机生成：scope 就是「这台设备是谁」（卸载重装才会变），
   * 必须落在 SharedPreferences 里 —— 网页的 localStorage 会被「清除网页缓存」之类的操作清掉。
   * 只认这个形状是为了不被 URL 上随手塞的字符串牵着走（页面的 scope 决定它读写哪一份对话）。
   */
  var DEVICE_SCOPE_PATTERN = /^dev-[0-9a-f]{12}$/;
  var REQUEST_TIMEOUT = 20000;                 // 普通请求超时（3G/局域网抖动留点余量）
  var LONG_REQUEST_TIMEOUT = 120000;           // /api/chat 要等模型，给足两分钟
  var CAPTURE_POLL_MS = 1200;                  // 回执轮询间隔
  /**
   * 跟一条回执的**时长上限**：出图可能要几分钟（`.gen 5`），所以给得比较宽；它只是防"异常残留"，
   * 正常路径由"生成队列空了 + 静默两轮"收工（与 Bot#webActive 的 15 分钟残留判定同口径）。
   */
  var CAPTURE_FOLLOW_MAX_MS = 15 * 60 * 1000;
  var PROGRESS_POLL_MS = 1500;                 // 生成进度轮询间隔（与桌面版一致）
  /**
   * 「我正在跟哪条回执」在 localStorage 里的键。
   *
   * <p><b>为什么必须落盘</b>：跟单现场（captureId / 任务号）原先**只在内存里**（`chat.captureStop`
   * 那个闭包）。安卓在后台回收渲染进程、或系统把 App 收掉再打开时，页面是**整页重来**的 ——
   * 内存里的跟单现场一起没了，于是"服务端早就投递好的图，回到前台再也没人接"，
   * 而对话正文（`data/webui/&lt;scope&gt;-chat-log.json`）是客户端存上去的，缺的那几张就永远缺着
   * （用户报的「手机端挂起（在浏览其他应用）也收不到图」里，属于"页面被重建"的那一半）。
   * 落盘之后，重建立刻能从服务端把这条回执补回来，并把跟单续上（见 {@link catchUpReceipts}）。
   */
  var FOLLOW_KEY = 'pixiko-follow-capture';
  /**
   * **跟单那条**回执的补拉窗口：只补最近这么久的；更老的留在回执屏里看，不往对话正文里灌历史。
   *
   * <p>注意它**只**管"正在跟的那条"（跟单本身另有 {@link CAPTURE_FOLLOW_MAX_MS} 兜底）。
   * "这次页面会话错过的那些"由 {@link catchUpCutoff} 那个闸门管，**不能**用这个 6 小时窗口 ——
   * 6 小时内的老回执全都会满足它，那正是「对话框莫名其妙插入历史对话」的来源。
   */
  var CATCHUP_WINDOW_MS = 6 * 60 * 60 * 1000;
  /** 一次补拉最多补几条（"正在跟的那条"另算）。防止冷启动把历史回执整片灌进对话。 */
  var CATCHUP_MAX_QUESTS = 3;
  /** 两次补拉之间的最小间隔：用户手势会频繁唤醒，别把请求打爆（回到前台那条路不受此限）。 */
  var CATCHUP_MIN_GAP_MS = 10000;

  var TABS = [
    { id: 'chat', title: '对话' },
    { id: 'gen', title: '出图' },
    { id: 'quest', title: '回执' },
    { id: 'styles', title: '样式' },
    { id: 'more', title: '更多' }
  ];
  /** 屏幕标题（app bar 与二级屏共用；screen-*.js 注册时会给 title，这里是没注册时的兜底）。 */
  var TITLES = {
    chat: '对话', gen: '出图', quest: '回执', styles: '样式', more: '更多', logs: '日志',
    prompt: '提示词', loras: 'LoRA', functions: '提示词集', system: '系统', help: '帮助'
  };
  /**
   * "二级屏"：不在底部 5 个 tab 里的屏（提示词 / LoRA / 提示词集 / 系统 / 帮助 / 日志 / 回执详情…）。
   * 只有它们才显示 app bar 的返回按钮 —— 底部 tab 上再放个返回按钮是多余且让人困惑的。
   * 新增二级屏时记得把 id 加进来，否则那一屏拿不到返回键。
   */
  var SECONDARY = ['prompt', 'loras', 'functions', 'system', 'help', 'logs', 'setup', 'chatcfg', 'quest-detail'];

  function $(id) { return document.getElementById(id); }

  function esc(value) {
    return String(value === null || value === undefined ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function el(tag, cls, text) {
    var node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text !== null && text !== undefined && text !== '') node.textContent = String(text);
    return node;
  }

  function clear(node) {
    if (!node) return node;
    while (node.firstChild) node.removeChild(node.firstChild);
    return node;
  }

  /** ISO 时间 → 「刚刚 / 12 分钟前 / 今天 14:03 / 10-04 14:03」。认不出就给原文。 */
  function fmtTime(iso) {
    if (!iso) return '';
    var date = iso instanceof Date ? iso : new Date(iso);
    if (isNaN(date.getTime())) return String(iso);
    var now = Date.now();
    var diff = Math.max(0, now - date.getTime());
    if (diff < 60000) return '刚刚';
    if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前';
    var pad = function (n) { return (n < 10 ? '0' : '') + n; };
    var hm = pad(date.getHours()) + ':' + pad(date.getMinutes());
    var today = new Date();
    if (date.toDateString() === today.toDateString()) return '今天 ' + hm;
    return pad(date.getMonth() + 1) + '-' + pad(date.getDate()) + ' ' + hm;
  }

  /** 字节 → 「812 B / 1.4 KB / 3.2 MB」。 */
  function fmtBytes(n) {
    var value = Number(n);
    if (!isFinite(value) || value < 0) return '—';
    if (value < 1024) return Math.round(value) + ' B';
    if (value < 1024 * 1024) return (value / 1024).toFixed(1) + ' KB';
    if (value < 1024 * 1024 * 1024) return (value / 1024 / 1024).toFixed(1) + ' MB';
    return (value / 1024 / 1024 / 1024).toFixed(2) + ' GB';
  }

  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

  /** 防抖：最后一次调用之后 wait 毫秒才真跑。 */
  function debounce(fn, wait) {
    var timer = null;
    return function () {
      var args = arguments, self = this;
      if (timer) clearTimeout(timer);
      timer = setTimeout(function () { timer = null; fn.apply(self, args); }, wait);
    };
  }

  function fmtNum(value, fallback) {
    var n = Number(value);
    return isFinite(n) ? n : (fallback === undefined ? 0 : fallback);
  }

  /* ── 1b. 设备 scope：这一台手机自己的对话身份 ─────────────────────────── */

  /**
   * 从地址上取设备 scope：/m?scope=dev-xxxxxxxxxxxx。
   *
   * <p>只读 location.search（查询串），**不读 hash 里那份**：hash 是页面自己的路由
   * （#/chat、#/quest/12…），改 hash 不会动查询串，所以查询串上的 scope 天然扛得住
   * 页面内跳转、深链接与刷新（Android 的下拉刷新是整页 reload，重新请求的还是这个地址）。
   */
  function scopeFromUrl() {
    try {
      var search = String(location.search || '');
      if (!search) return '';
      var match = /[?&]scope=([^&#]*)/.exec(search);
      if (!match) return '';
      var value = String(decodeURIComponent(match[1])).trim();
      return DEVICE_SCOPE_PATTERN.test(value) ? value : '';
    } catch (error) { return ''; }
  }

  /**
   * 外壳直接注入的备用通道（window.__PIXIKO_SCOPE，见 Android 的 NativeHook.deviceScopeScript）。
   * 只在「地址上没写」时兜底。
   */
  function scopeFromWindow() {
    try {
      var value = window.__PIXIKO_SCOPE;
      if (typeof value !== 'string') return '';
      var text = value.trim();
      return DEVICE_SCOPE_PATTERN.test(text) ? text : '';
    } catch (error) { return ''; }
  }

  function readStore(key) {
    try { return localStorage.getItem(key) || ''; } catch (error) { return ''; }
  }

  function writeStore(key, value) {
    try { localStorage.setItem(key, String(value)); } catch (error) { /* 隐私模式写不进去就只在本次会话里生效 */ }
  }

  /** 本机镜像里记着的设备 scope（外壳在上一页写过，或者启动时从地址上抄下来的）。 */
  function scopeFromStore() {
    var value = readStore(DEVICE_SCOPE_KEY).trim();
    return DEVICE_SCOPE_PATTERN.test(value) ? value : '';
  }

  /** 启动时就把地址上的 scope 定下来：这样第一次 PixikoM.scope() 调用（boot 里的状态轮询）已经拿到正确值。 */
  var resolvedScope = scopeFromUrl() || scopeFromWindow() || scopeFromStore();

  /**
   * 把权威 scope 写进本机镜像（只在真的不一样时写）。
   *
   * <p>为什么必须**无条件**同步、而不是只在"和内存里的 resolvedScope 不同"时才写：
   * 模块初始化时 resolvedScope 已经等于地址上的值了，之后再调用 resolveScope 就永远看不到差异 ——
   * 镜像键会一直空着，于是整页重载（Android 下拉刷新）时万一地址少了参数就没得兜。
   */
  function syncMirror(value) {
    if (scopeFromStore() !== value) writeStore(DEVICE_SCOPE_KEY, value);
  }

  /**
   * 解析当前该用的 scope，并顺手把结果固化到本机镜像。
   *
   * <p>优先级（**外壳给的设备 scope 永远压过页面自己存的东西**，否则「每台设备独立」就是假的）：
   * <ol>
   *   <li>地址上的 ?scope=dev-… —— Android 外壳每次加载 /m 都带（权威，扛得住整页重载）；</li>
   *   <li>window.__PIXIKO_SCOPE —— 外壳注入的备用通道；</li>
   *   <li>本机镜像 pixiko-device-scope —— 上面两条都没有时（页面内刷新、外壳没来得及注入）；</li>
   *   <li>控制台/浏览器用的 pixiko-scope —— 只是**降级**路径：从手机浏览器直接打开
   *       http://主机:8787/m 时没有外壳、也没有镜像，此时沿用老行为（默认 web），
   *       页面照常能用、不报错、不白屏；</li>
   *   <li>DEFAULT_SCOPE（web）。</li>
   * </ol>
   *
   * <p>**只认 dev-… 形状**：地址上被人手塞一个 ?scope=alice 不会被当成设备 scope，
   * 免得页面被随手拼的查询串牵着去读写别人的对话。
   */
  function resolveScope() {
    var fromUrl = scopeFromUrl();
    if (fromUrl) { syncMirror(fromUrl); resolvedScope = fromUrl; return resolvedScope; }
    var fromWindow = scopeFromWindow();
    if (fromWindow) { syncMirror(fromWindow); resolvedScope = fromWindow; return resolvedScope; }
    if (resolvedScope && scopeFromStore() === resolvedScope) return resolvedScope;
    var stored = scopeFromStore();
    if (stored) { resolvedScope = stored; return resolvedScope; }
    // 降级：没有外壳给的设备 scope。沿用老行为（pixiko-scope，默认 web），页面照常可用。
    var fallback = readStore(SCOPE_KEY).trim() || DEFAULT_SCOPE;
    resolvedScope = fallback;
    return resolvedScope;
  }

  /* ── 2. PixikoM：契约实现 ────────────────────────────────────────────── */
  /**
   * 全局契约对象。**签名与行为对四个并行开发的代理是冻结的**，改动必须同步所有人。
   *
   * 下面这些是"看起来像工具函数"的契约成员，与模块内的私有同名函数是**同一个实现**：
   *   PixikoM.el / PixikoM.clear / PixikoM.esc / PixikoM.fmtTime / PixikoM.fmtBytes
   * 注意不能只把它们留成模块私有的 el()/clear()/esc()…，那样别的 screen-*.js 就调不到。
   */
  var PixikoM = {};
  window.PixikoM = PixikoM;

  /* 工具函数挂到契约上：screen-*.js 通过 PixikoM.* 拿它们（不能只留模块私有的同名函数）。 */
  PixikoM.el = el;                 // el(tag, cls, text)
  PixikoM.clear = clear;           // clear(node) → node
  PixikoM.esc = esc;               // esc(s) → 转义后的字符串
  PixikoM.fmtTime = fmtTime;       // fmtTime(iso) → 「刚刚 / 12 分钟前 / 今天 14:03」
  PixikoM.fmtBytes = fmtBytes;     // fmtBytes(n) → 「1.5 KB」

  /** 屏幕注册表：id → {id, title, mount, refresh}。别的代理只用 register() 往里加。 */
  var screens = Object.create(null);
  /** 已经 mount 过的屏幕 id（mount 只在首次进入时调一次）。 */
  var mounted = Object.create(null);
  var currentId = null;

  /**
   * 上一次路由时生效的 scope，以及「守卫是否已经武装」。
   *
   * <p>武装的时机见 boot()：首次路由**之前**才记下当时的 scope，否则首屏那一次 applyRoute
   * 会被当成「scope 变了」，白白把页面刷一遍。
   */
  var routedScope = null;
  var scopeGuardArmed = false;

  /**
   * 注册一个屏幕。
   * @param {string} id     屏幕 id（也是 hash：#/id）
   * @param {{title:string, mount:function(HTMLElement), refresh?:function()}} spec
   *   mount(root) 只在首次进入时调用一次，root 是一张干净的 <section class="screen">。
   *   refresh() 可选：app bar 的刷新按钮与下拉刷新会调它。
   */
  PixikoM.register = function (id, spec) {
    if (!id || typeof id !== 'string') throw new Error('register 需要一个字符串 id');
    if (!spec || typeof spec.mount !== 'function') throw new Error('register 的 mount 必须是函数');
    screens[id] = { id: id, title: spec.title || TITLES[id] || id, mount: spec.mount, refresh: spec.refresh || null };
    if (spec.title) TITLES[id] = spec.title;
    // 已经停在这一屏（深链接首次加载时必然如此：boot 的首次路由比 screen-*.js 的 register 早）：
    // 立刻挂上真屏，并**把路由从"还没做好"占位屏接管过来**——否则真屏挂载了却一直 display:none。
    if (currentId === id && !mounted[id]) { ensureMounted(id); takeOverRoute(id); renderBar(); renderTabs(); }
    return PixikoM;
  };

  /**
   * 把 .active 从占位屏（或别的屏）摘掉、交给刚挂上来的真屏。
   * 深链接场景下 hash 不会再变，所以这一步必须在 register() 里主动做，不能等路由。
   */
  function takeOverRoute(id) {
    var main = $('m-main');
    if (!main) return;
    var active = main.querySelector('.screen.active');
    var target = document.getElementById('screen-' + id);
    if (active && active !== target) active.classList.remove('active');
    if (target && !target.classList.contains('active')) target.classList.add('active');
    // 占位屏已经没用了：直接摘掉，免得后面再被 .active 选中
    var placeholder = document.getElementById('screen-missing-' + id);
    if (placeholder && placeholder.parentNode) placeholder.parentNode.removeChild(placeholder);
  }

  /** 切屏（hash 路由；每一屏都是一条历史，Android 返回键可用）。 */
  PixikoM.go = function (id) {
    if (!id) return;
    var want = '#/' + String(id).replace(/^#?\/?/, '');
    if (location.hash === want) { applyRoute(); return; }
    location.hash = want;            // 交给 hashchange / applyRoute
  };

  /** 当前屏幕 id。 */
  PixikoM.current = function () { return currentId; };

  /**
   * 调 /api/**。
   * @param {string} path  接口路径（'/api/chat'）
   * @param {{method?:string, body?:object, query?:object, timeout?:number}} [options]
   *   默认 POST；自动带 Authorization: Bearer <令牌>，body 里自动补 scope。
   * @returns {Promise<object>} 解析后的 JSON
   * @throws {Error} message 可直接展示；error.code 是 'network' / 'timeout' / 'http' / 'unauthorized'
   */
  PixikoM.api = async function (path, options) {
    options = options || {};
    var method = (options.method || 'POST').toUpperCase();
    var url = path;
    var query = options.query;
    if (query) {
      var parts = [];
      for (var key in query) {
        if (!Object.prototype.hasOwnProperty.call(query, key)) continue;
        var value = query[key];
        if (value === null || value === undefined) continue;
        parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(String(value)));
      }
      if (parts.length) url += (url.indexOf('?') >= 0 ? '&' : '?') + parts.join('&');
    }

    var headers = { 'Accept': 'application/json' };
    var token = PixikoM.token();
    if (token) headers['Authorization'] = 'Bearer ' + token;

    // 这次请求实际用的 scope（调用方显式给了就用它）：回执账本要按它记账。
    var sentScope = PixikoM.scope();

    var body;
    if (options.body !== undefined && options.body !== null) {
      var payload = options.body;
      // scope 统一在这里补：所有 /api 接口都从 body.scope 取（见 WebApiController.api()）。
      if (typeof payload === 'object' && !Array.isArray(payload) && !('scope' in payload)) {
        payload = Object.assign({}, payload, { scope: sentScope });
      }
      if (payload && typeof payload === 'object' && typeof payload.scope === 'string') sentScope = payload.scope;
      body = JSON.stringify(payload);
      headers['Content-Type'] = 'application/json; charset=utf-8';
    } else if (method === 'POST') {
      // 有些接口 requirePost，且要 scope；空体也给一个合法 JSON。
      body = JSON.stringify({ scope: sentScope });
      headers['Content-Type'] = 'application/json; charset=utf-8';
    }

    var controller = typeof AbortController === 'function' ? new AbortController() : null;
    var timeoutMs = options.timeout || REQUEST_TIMEOUT;
    var timer = setTimeout(function () { if (controller) controller.abort(); }, timeoutMs);
    var response;
    try {
      response = await fetch(url, { method: method, headers: headers, body: body, cache: 'no-store', signal: controller ? controller.signal : undefined });
    } catch (error) {
      clearTimeout(timer);
      if (error && (error.name === 'AbortError' || error.name === 'TimeoutError')) {
        throw tagged('请求超时：机器人没有在 ' + Math.round(timeoutMs / 1000) + " 秒内回应，检查它是否还在跑。", 'timeout');
      }
      throw tagged('连不上机器人：' + (error && error.message ? error.message : '网络错误'), 'network');
    }
    clearTimeout(timer);

    var text = '';
    try { text = await response.text(); } catch (error) { text = ''; }
    var data = null;
    if (text) { try { data = JSON.parse(text); } catch (error) { data = null; } }

    if (!response.ok) {
      var message = (data && (data.error || data.message)) || ('HTTP ' + response.status);
      if (response.status === 401) throw tagged('访问令牌不正确：请在「更多 → 访问令牌」里改成 config.json 里的 webui.access_token。', 'unauthorized', 401);
      if (response.status === 429) throw tagged('尝试次数过多：' + message, 'http', 429);
      if (response.status === 503) throw tagged(message || 'WebUI 还没配置访问令牌。', 'http', 503);
      throw tagged(message, 'http', response.status);
    }
    if (data === null) throw tagged('机器人回了一段不是 JSON 的内容。', 'http', response.status);
    /* 本机发起的指令：它产生的回执号记进账本（回执屏据此区分"这台手机的"与"控制台/别的设备的"）。
       只认这两个真正"发起指令"的入口：/api/quest、/api/capture 只是读，读了别人的不能算自己的。 */
    if ((path === '/api/command' || path === '/api/chat') && data && Number(data.quest) > 0) {
      PixikoM.questLedger.note(sentScope, data.quest);
    }
    return data;
  };

  /** 给 Error 挂上分类码（错误态文案靠它区分"连不上"与"令牌不对"）。 */
  function tagged(message, code, status) {
    var error = new Error(message);
    error.code = code;
    if (status) error.status = status;
    return error;
  }

  PixikoM.token = function () {
    try { return localStorage.getItem(TOKEN_KEY) || ''; } catch (error) { return ''; }
  };
  PixikoM.setToken = function (value) {
    try {
      if (value === null || value === undefined || value === '') localStorage.removeItem(TOKEN_KEY);
      else localStorage.setItem(TOKEN_KEY, String(value));
    } catch (error) { /* 隐私模式下写不进去就算了 */ }
  };
  /**
   * 当前这一屏该用的 scope —— **所有对话相关请求都走它**（PixikoM.api 统一往 body 里补 scope）。
   *
   * <p>解析规则见 resolveScope：外壳给的设备 scope（?scope=dev-… / window.__PIXIKO_SCOPE）
   * 永远优先，其次本机镜像，最后才降级到老的 pixiko-scope（默认 web）。
   * 每次调用都重算：Android 外壳可以在页面已经开着的时候改地址栏，或者注入 window.__PIXIKO_SCOPE。
   */
  PixikoM.scope = function () { return resolveScope(); };

  /** 本机设备 scope（dev-…）；没有外壳、走了降级路径时返回空串。诊断与「关于」面板用。 */
  PixikoM.deviceScope = function () {
    var value = resolveScope();
    return DEVICE_SCOPE_PATTERN.test(value) ? value : '';
  };

  /** 现在这个 scope 是不是外壳给的设备 scope（界面用它决定「能否手改 scope」）。 */
  PixikoM.scopeManaged = function () { return !!PixikoM.deviceScope(); };

  /**
   * 改 scope。**只有「降级模式」（没有设备 scope）才真的改**：
   * 一旦外壳给了设备 scope，这台设备的对话身份就由外壳（SharedPreferences 里的 UUID）说了算，
   * 网页里手改会立刻被下一次 resolveScope() 覆盖回去 —— 所以这里返回**实际生效**的值，
   * 免得界面显示一个假的 scope。
   *
   * @returns {string} 实际生效的 scope
   */
  PixikoM.setScope = function (value) {
    var clean = String(value === null || value === undefined ? '' : value).trim() || DEFAULT_SCOPE;
    if (PixikoM.deviceScope()) return PixikoM.scope();
    try { localStorage.setItem(SCOPE_KEY, clean); } catch (error) { /* ignore */ }
    resolvedScope = clean;
    return clean;
  };

  /* ── 1e. 本机回执账本：这台手机自己发起过的回执 ───────────────────────────────
   * 为什么需要：回执/任务类接口（/api/quests、/api/quest、/api/capture）在服务端一直是**全局**的，
   * 回执行里也没有任何归属字段 —— 于是手机端的回执列表会把控制台、别的设备的回执一起列出来
   * （用户报的「回执重复杂糅」）。
   *
   * 服务端已经补上了按 scope 过滤（`/api/quests` 的响应里 `scoped:true` 表示这次真的按会话筛过，
   * 见 Bot#webQuests），但那份改动要**重启机器人**才生效；在那之前，客户端先用这份账本把
   * "不是这台手机发起的"回执挡在列表外（见 screen-quest.js 的 loadList）。
   *
   * 只在**本机发起指令**的两个入口写入（/api/command 与 /api/chat 的成功响应），别的接口一律不写 ——
   * 否则"看一眼别人的回执"就把它记成自己的了。按 scope 分账，与对话正文一个口径。
   */
  var QUEST_LEDGER_KEY = 'pixiko-quest-ledger';
  var QUEST_LEDGER_CAP = 500;                  // 每个 scope 最多记多少条（够翻历史，也不让 localStorage 无限长）
  var questLedger = null;
  function ledgerBook() {
    if (questLedger) return questLedger;
    try {
      var parsed = JSON.parse(localStorage.getItem(QUEST_LEDGER_KEY) || '{}');
      questLedger = parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
    } catch (error) { questLedger = {}; }
    return questLedger;
  }
  function ledgerWrite() {
    try { localStorage.setItem(QUEST_LEDGER_KEY, JSON.stringify(ledgerBook())); }
    catch (error) { /* 隐私模式/localStorage 满：账本记不住只是少了这层兜底，不影响别的 */ }
  }
  function ledgerList(scope) {
    var list = ledgerBook()[String(scope === null || scope === undefined ? '' : scope)];
    return Array.isArray(list) ? list : [];
  }
  /** 本机回执账本（供回执屏过滤；服务端按 scope 过滤生效后它自动退居二线）。 */
  PixikoM.questLedger = {
    /** 记下一条"这台手机发起的"回执号（幂等）。 */
    note: function (scope, quest) {
      var number = Number(quest);
      if (!(number > 0)) return;
      var key = String(scope === null || scope === undefined ? '' : scope);
      var book = ledgerBook();
      var list = Array.isArray(book[key]) ? book[key] : [];
      if (list.indexOf(number) >= 0) return;
      list.push(number);
      while (list.length > QUEST_LEDGER_CAP) list.shift();
      book[key] = list;
      ledgerWrite();
    },
    has: function (scope, quest) { return ledgerList(scope).indexOf(Number(quest)) >= 0; },
    size: function (scope) { return ledgerList(scope).length; },
    clear: function (scope) {
      var book = ledgerBook();
      delete book[String(scope === null || scope === undefined ? '' : scope)];
      ledgerWrite();
    }
  };

  /** 轻提示（自动消失，可叠多条）。 */
  PixikoM.toast = function (text) {
    if (text === null || text === undefined || text === '') return;
    var host = $('m-toast-host');
    if (!host) { host = el('div', 'toast-host'); host.id = 'm-toast-host'; document.body.appendChild(host); }
    var node = el('div', 'toast', String(text));
    if (/失败|错误|连不上|超时|不正确|不能|无法/.test(String(text))) node.className = 'toast error';
    host.appendChild(node);
    requestAnimationFrame(function () { node.classList.add('in'); });
    setTimeout(function () {
      node.classList.remove('in');
      setTimeout(function () { if (node.parentNode) node.parentNode.removeChild(node); }, 240);
    }, 2600);
    return node;
  };

  /**
   * 先用真实布局画一帧，再改类名触发 CSS 过渡。
   *
   * <p>**不能用 requestAnimationFrame**：headless Chrome / 后台标签页下 rAF 会被无限期节流，
   * 回调不跑就意味着 overlay() 里的那句"下一帧再加 .in"永远不执行；一旦调用方在 await 它，
   * 整条链路都会卡住（实测第二个 confirm() 就这么挂住过）。setTimeout 一样能等一帧、且不会被节流。
   */
  function nextFrame(fn) { setTimeout(fn, 16); }

  /**
   * 当前"活着"的浮层（按打开顺序）。为什么要登记：{@link drainOverlays} 光删 DOM 是不够的 ——
   * 每个浮层都在 `document` 上挂了 keydown 监听，只删节点的话监听会一直泄漏，
   * 而且旧浮层的 `onDismiss` 以后还可能被一次 Escape 意外触发。
   */
  var liveOverlays = [];

  /**
   * 开新浮层之前把旧浮层收干。
   *
   * <p>为什么必须做：{@link overlay} 的关闭是"先褪色、240ms 后再 remove"。如果用户（或探针）
   * 在这 240ms 内又点开另一个浮层，旧的 `.scrim` 还在 DOM 里，它会盖在新面板上面 —— 表现就是
   * **"点了没反应"**（命中点被 `DIV.scrim` 抢走）。这里在每次开新浮层时同步扫掉残留。
   *
   * <p>两条路径都要走：① 登记的浮层逐个 `close(true)`（同步摘掉节点 + 摘掉 keydown 监听，
   * 不留定时器）；② 再兜底扫一遍 DOM —— `screen-*.js` 里如果有自己 append 到 `#m-overlay`
   * 的面板（不是通过 overlay() 建的），只能靠这一步收掉。
   */
  function drainOverlays() {
    var pending = liveOverlays.slice();
    liveOverlays.length = 0;
    for (var i = 0; i < pending.length; i++) {
      try { pending[i].close(true); } catch (error) { /* 收不干净也不能把新浮层带崩 */ }
    }
    var root = $('m-overlay');
    if (!root) return;
    var stale = root.querySelectorAll('.scrim, .sheet, .dialog, .viewer');
    for (var j = 0; j < stale.length; j++) {
      var node = stale[j];
      if (node.parentNode) node.parentNode.removeChild(node);
    }
  }

  /** 有没有浮层开着（查看器 / 对话框 / sheet）。列表轮询据此停下来，别在用户看图/选东西时打扰。 */
  PixikoM.overlayBusy = function () {
    if (liveOverlays.length) return true;
    var root = $('m-overlay');
    return !!(root && root.querySelector('.scrim, .sheet, .dialog, .viewer'));
  };

  /**
   * 浮层公共件：遮罩 + 关闭。返回 {scrim, close, panel}。
   * @param {boolean} [immediate] close(true)：同步摘节点（drainOverlays 用），不走 240ms 过渡。
   */
  function overlay(panel, onDismiss) {
    drainOverlays();
    var root = $('m-overlay') || document.body;
    var scrim = el('div', 'scrim');
    root.appendChild(scrim);
    root.appendChild(panel);
    nextFrame(function () { scrim.classList.add('in'); if (panel.classList) panel.classList.add('in'); });
    var closed = false;
    /**
     * @param {boolean} [immediate] 只认**严格的 true**（drainOverlays 用）。
     *   注意不能写成 `if (immediate)`：`addEventListener('click', handle.close)` 会把 MouseEvent
     *   当第一个实参传进来，事件对象是真值 —— 那样"取消"按钮会跳过 240ms 的滑出动画、面板瞬间消失。
     */
    function close(immediate) {
      if (closed) return;
      closed = true;
      var at = liveOverlays.indexOf(handle);
      if (at >= 0) liveOverlays.splice(at, 1);
      scrim.classList.remove('in');
      if (panel.classList) { panel.classList.remove('in'); panel.classList.add('out'); }
      document.removeEventListener('keydown', onKey);
      // 兜底扫残留的定时器：即使这次是 immediate，也别留一个 240ms 后再动的回调
      if (closeTimer) { clearTimeout(closeTimer); closeTimer = null; }
      if (immediate === true) {
        if (scrim.parentNode) scrim.parentNode.removeChild(scrim);
        if (panel.parentNode) panel.parentNode.removeChild(panel);
        return;
      }
      closeTimer = setTimeout(function () {
        closeTimer = null;
        if (scrim.parentNode) scrim.parentNode.removeChild(scrim);
        if (panel.parentNode) panel.parentNode.removeChild(panel);
      }, 240);
    }
    var closeTimer = null;
    function onKey(event) { if (event.key === 'Escape') { close(); if (onDismiss) onDismiss(); } }
    scrim.addEventListener('click', function () { close(); if (onDismiss) onDismiss(); });
    document.addEventListener('keydown', onKey);
    var handle = { scrim: scrim, close: close, panel: panel };
    liveOverlays.push(handle);
    return handle;
  }

  /**
   * 底部动作面板。items: [{text, danger, sub, onSelect}]
   * @returns {{close:function}} 方便调用方在动作后自己收面板。
   */
  PixikoM.sheet = function (spec) {
    spec = spec || {};
    var panel = el('div', 'sheet');
    panel.setAttribute('role', 'dialog');
    panel.appendChild(el('div', 'sheet-grip'));
    if (spec.title) panel.appendChild(el('div', 'sheet-title', spec.title));
    var list = el('div', 'sheet-list');
    var items = spec.items || [];
    var holder = { close: null };
    items.forEach(function (item) {
      if (!item) return;
      var row = el('button', 'sheet-item' + (item.danger ? ' danger' : ''));
      row.type = 'button';
      row.setAttribute('data-sheet-item', item.text || '');
      var main = el('div', 'row-main');
      main.appendChild(el('div', 'row-title', item.text || ''));
      if (item.sub) main.appendChild(el('div', 'row-sub', item.sub));
      row.appendChild(main);
      row.addEventListener('click', function () {
        if (holder.close) holder.close();
        if (typeof item.onSelect === 'function') { try { item.onSelect(); } catch (error) { PixikoM.toast(String(error.message || error)); } }
      });
      list.appendChild(row);
    });
    panel.appendChild(list);
    var cancel = el('button', 'sheet-cancel', '取消');
    cancel.type = 'button';
    cancel.setAttribute('data-sheet-cancel', '1');
    panel.appendChild(cancel);
    var handle = overlay(panel, spec.onDismiss);
    holder.close = handle.close;
    cancel.addEventListener('click', handle.close);
    return holder;
  };

  /**
   * 确认框。
   * @returns {Promise<boolean>} 确定 true / 取消 false
   */
  PixikoM.confirm = function (spec) {
    spec = spec || {};
    return new Promise(function (resolve) {
      var panel = el('div', 'dialog');
      panel.setAttribute('role', 'alertdialog');
      panel.appendChild(el('div', 'dialog-title', spec.title || '确认'));
      if (spec.text) panel.appendChild(el('div', 'dialog-text', spec.text));
      var actions = el('div', 'dialog-actions');
      var no = el('button', 'btn ghost', spec.cancel || '取消');
      var yes = el('button', 'btn ' + (spec.danger ? 'danger' : 'primary'), spec.ok || '确定');
      no.type = 'button'; yes.type = 'button';
      no.setAttribute('data-dialog-cancel', '1');
      yes.setAttribute('data-dialog-ok', '1');
      actions.appendChild(no); actions.appendChild(yes);
      panel.appendChild(actions);
      var settled = false;
      var handle = overlay(panel, function () { if (!settled) { settled = true; resolve(false); } });
      function done(value) { if (settled) return; settled = true; handle.close(); resolve(value); }
      yes.addEventListener('click', function () { done(true); });
      no.addEventListener('click', function () { done(false); });
    });
  };

  /**
   * 输入框。
   * @returns {Promise<string|null>} 输入的文本；取消给 null
   */
  PixikoM.prompt = function (spec) {
    spec = spec || {};
    return new Promise(function (resolve) {
      var panel = el('div', 'dialog');
      panel.appendChild(el('div', 'dialog-title', spec.title || '输入'));
      if (spec.text) panel.appendChild(el('div', 'dialog-text', spec.text));
      var input = el('input', 'dialog-input');
      input.type = 'text';
      input.value = spec.value === undefined || spec.value === null ? '' : String(spec.value);
      if (spec.placeholder) input.placeholder = spec.placeholder;
      panel.appendChild(input);
      var actions = el('div', 'dialog-actions');
      var no = el('button', 'btn ghost', spec.cancel || '取消');
      var yes = el('button', 'btn primary', spec.ok || '保存');
      no.type = 'button'; yes.type = 'button';
      no.setAttribute('data-dialog-cancel', '1');
      yes.setAttribute('data-dialog-ok', '1');
      actions.appendChild(no); actions.appendChild(yes);
      panel.appendChild(actions);
      var settled = false;
      var handle = overlay(panel, function () { if (!settled) { settled = true; resolve(null); } });
      function done(value) { if (settled) return; settled = true; handle.close(); resolve(value); }
      yes.addEventListener('click', function () { done(input.value); });
      no.addEventListener('click', function () { done(null); });
      input.addEventListener('keydown', function (event) { if (event.key === 'Enter') { event.preventDefault(); done(input.value); } });
      setTimeout(function () { try { input.focus(); input.select(); } catch (error) { /* ignore */ } }, 60);
    });
  };

  /** 缩略图长边（契约：服务端返回长边 ≤ w 的等比缩略图，不放大）。格子约 110 CSS px，320 够 2–3 倍屏。 */
  var IMAGE_THUMB_W = 320;

  /**
   * 服务端存档里的图片字段有**三种写法**，这里统一取出可用的字符串：
   *   · 纯字符串            —— `/api/chat/log` 的 `images:["file:///F:/Bot/data/generated/…"]`；
   *   · 对象带 `file`       —— `/api/quest` 的 `images:[{file:"file:///…"}]` 与
   *                            `messages` 里的 `{type:'image', file:"…"}`；
   *   · 对象带 `url`/`path` —— `/api/images` 的 `{path:"data/generated/…"}`。
   * 以前把对象整个 `String()` 会拼出 `path=%5Bobject%20Object%5D`（服务端 400）—— 这是"图不显示"的另一个来源。
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

  /**
   * 路径归一：`file:///F:/Bot/data/generated/…`、`F:/Bot/data/generated/…`、反斜杠写法、
   * 已经干净的 `data/generated/…` —— 一律截成从 `data/generated/` 开始的相对路径；
   * 不含这一段的一律原样返回（`file://` 前缀由服务端那一侧去认，这里绝不自己猜）。
   *
   * <p>从**第一个**出现处截取（防目录名里带 `data/generated` 前缀时被啃掉一截）；
   * 只有在"第一个出现处后面紧跟着任务目录"时才往后挪，这是出于兼容的保守判断，
   * 对实测的 `…/data/generated/task-…` 与 `…/data/generated/webui/…` 都走第一个。
   */
  function normalizeImagePath(value) {
    var path = imageOf(value).replace(/\\/g, '/');
    if (!path) return '';
    var needle = 'data/generated/';
    var at = path.indexOf(needle);
    if (at < 0) return path;
    var later = path.lastIndexOf(needle);
    if (later > at && /^(task|webui)\//.test(path.slice(later + needle.length)) && !/^(task|webui)\//.test(path.slice(at + needle.length))) {
      at = later;
    }
    return path.slice(at);
  }

  /**
   * 图片地址 → `<img src>` 能用的地址。
   *
   * 已经"能直接用"的一律原样返回：`http(s)://`、`data:`、`blob:`、`/api/…`（比如
   * `/api/lora/preview?name=…`、`/api/style/preview?name=…`）、以及 `/m/…` 这类站内绝对路径。
   * 其余（`data/generated/…` 及 `file://…` / `F:\…` 等各种存档写法）先归一，再包成
   * `/api/image?token=…&path=…`。
   *
   * @param {string|{file?:string,path?:string,url?:string}} pathOrUrl
   * @param {{w?:number}|number} [optsOrW] 给了 `w` 就加 `&w=<32..1600>` 拿缩略图。
   *   **默认不加 w**（原图）—— 全屏查看器、原生桥保存/分享、复制图片地址都必须用默认调用；
   *   只有图集格子/对话气泡这种小尺寸展示才传 `{w: IMAGE_THUMB_W}`。
   *   参数现在会被旧服务端忽略（照返回原图，不报错），加上就已经是新旧都好。
   */
  PixikoM.imageUrl = function (pathOrUrl, optsOrW) {
    var value = normalizeImagePath(pathOrUrl);
    if (!value) return '';
    if (/^(https?:|blob:|data:)/i.test(value)) return value;
    if (value.charAt(0) === '/') return value;                 // /api/…、/m/… 等站内绝对路径直接放行
    var url = '/api/image?token=' + encodeURIComponent(PixikoM.token()) + '&path=' + encodeURIComponent(value);
    var w = Number(optsOrW && typeof optsOrW === 'object' ? optsOrW.w : optsOrW);
    if (Number.isFinite(w) && w >= 32 && w <= 1600) url += '&w=' + Math.round(w);
    return url;
  };

  /** 缩略图地址（格子/气泡/列表用）。语义上单独一个函数，调用点一眼看得出"这是小图"。 */
  function thumbUrl(value) { return PixikoM.imageUrl(value, { w: IMAGE_THUMB_W }); }

  /** 要不要马上加载：首屏可见的那几张 eager，其余 lazy（避免一进来满屏空白格子）。 */
  function loadingFor(node, index) {
    if (index < 4) return 'eager';
    var rect = node && node.getBoundingClientRect ? node.getBoundingClientRect() : null;
    if (rect && rect.top < (window.innerHeight || 800) && rect.bottom > 0) return 'eager';
    return 'lazy';
  }

  /* 失败态：`<img>` 加载不出来时，用 `!important` 的样式表压掉 app.css 里"强制定高"的规则，
     让占位块在气泡/图集/进度格子里都真的占得住位置、看得见字。
     （2026-10-05：原来这里还有一条 `.mx-contain{object-fit:contain!important}`，用来给"非 1:1 的图
     在 1:1 方块里"换成 contain 避免裁掉两头 —— 那只是把"裁切"换成"四周一大片空白"。
     现在格子按图片自身宽高比显示（见 applyNaturalRatio），这条 hack 已删除。） */
  (function installImageFailStyle() {
    var id = 'pixiko-m-imgfail-style';
    if (document.getElementById(id)) return;
    var style = document.createElement('style');
    style.id = id;
    style.textContent = '.m-img-fail{display:flex!important;align-items:center;justify-content:center!important;'
      + 'width:100%!important;height:100%!important;min-height:64px;padding:6px;box-sizing:border-box;'
      + 'background:#1b1119!important;border:1px dashed #5a2b33!important;border-radius:10px!important;'
      + 'color:#ff9b9b!important;font-size:11.5px;line-height:1.35;text-align:center;'
      + 'overflow-wrap:anywhere;word-break:break-word;cursor:pointer;user-select:none;}';
    document.head.appendChild(style);
  })();

  /** 复制一段文本（失败时至少把原文 toast 出来，方便手抄）。 */
  function copyToClipboard(value) {
    var textValue = String(value === null || value === undefined ? '' : value);
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(textValue).then(function () { PixikoM.toast('路径已复制'); }, function () { PixikoM.toast(textValue); });
        return;
      }
    } catch (error) { /* 落到 execCommand */ }
    var input = el('textarea');
    input.value = textValue;
    document.body.appendChild(input);
    input.select();
    try { document.execCommand('copy'); PixikoM.toast('路径已复制'); } catch (error) { PixikoM.toast(textValue); }
    document.body.removeChild(input);
  }

  /** 图取不到时按路径去重的记录（同一张图多处失败只提示一次，不刷屏 toast）。 */
  var imageFailures = Object.create(null);
  PixikoM.imageFailures = function () { return Object.keys(imageFailures); };

  /** 统一的失败占位块：文案「图取不到」，带上路径 + 文件名，点一下复制路径。 */
  function imageFailBox(label) {
    var box = el('div', 'm-img-fail');
    box.textContent = '图取不到';
    box.title = String(label || '');
    box.addEventListener('click', function (event) {
      event.preventDefault();
      event.stopPropagation();
      copyToClipboard(label);
    });
    return box;
  }

  /**
   * 把图片的**真实宽高比**写到它的格子盒子上（`.thumb` / `.bubble-img` / `.q-tile` 都是"图片的 parentNode"）。
   *
   * <p>为什么写在**盒子**而不是图片自己身上：CSS 给盒子一个中性初始比例（`aspect-ratio: 4 / 3`），
   * 图片还没解码时格子就已经占住位置，不会"塌成 0 高、图一到又跳一下"；图片 load 之后再按
   * `naturalWidth / naturalHeight` 把盒子换成真实比例，而 `<img>` 只写 `width:100%;height:auto`
   * （`object-fit: fill`，不裁不缩）。于是渲染宽高比 == 原图宽高比：**不变形、不裁切、不留大片空白**。
   *
   * <p>图已缓存（`complete && naturalWidth > 0`）时调用点会立刻进来；此刻若 img 还没被
   * `appendChild` 进盒子（调用点普遍是"先 mountImage 再 appendChild"），就等一拍补写一次，
   * 免得盒子永远停在 4:3。
   *
   * @param {HTMLImageElement} img
   * @param {boolean} [retry] 内部用：这是补写那次，别再往下排
   * @returns {boolean} 写成功没有
   */
  function applyNaturalRatio(img, retry) {
    var nw = img.naturalWidth, nh = img.naturalHeight;
    if (!(nw > 0 && nh > 0)) return false;
    var host = img.parentNode;
    if (!host || host.nodeType !== 1) {
      if (!retry) window.setTimeout(function () { applyNaturalRatio(img, true); }, 0);
      return false;
    }
    var ratio = nw + ' / ' + nh;
    if (host.style.aspectRatio !== ratio) host.style.aspectRatio = ratio;
    host.setAttribute('data-ratio', nw + 'x' + nh);
    return true;
  }
  /** 回执详情的图集格子（screen-quest.js）用同一份实现，别在两处写两套比例逻辑。 */
  PixikoM.applyNaturalRatio = applyNaturalRatio;

  var imageCache = (function () {
    var store = Object.create(null);
    function entry(path) { return store[path] || null; }
    function ensure(path) {
      var found = store[path];
      if (!found) found = store[path] = { url: '', nodes: [], loaded: false, failed: false, width: 0, height: 0 };
      return found;
    }
    return {
      /**
       * 取一个可以马上挂上去的 `<img>`：**优先还一个当前没挂在文档里的旧节点** —— 搬节点既不重新
       * 请求也不重新解码（`/api/image` 虽然带 `max-age=86400`，但新节点仍要解码一次，安卓上就是
       * 肉眼可见的"又是加载中"）。没有空闲节点才新建。
       */
      node: function (path, url) {
        var found = ensure(path);
        if (found.url && url && found.url !== url) { found.nodes.length = 0; found.loaded = false; found.failed = false; found.width = found.height = 0; }
        if (url) found.url = url;
        /* 挑一个"空闲"的旧节点：既没挂在文档里（`isConnected`），也没被这一拍的构造过程占用。
           为什么要有第二个条件：气泡/格子是**先离线拼好再挂上去**的，第一个格子拿到旧节点时它还没
           `isConnected`，第二个格子（同一路径出现两次时）就会拿到同一个节点 —— 再 appendChild 只是
           把它**搬走**，前一格就空了。挂上去之后占用标记自动释放，节点被摘掉后又能被复用。 */
        for (var i = 0; i < found.nodes.length; i++) {
          var candidate = found.nodes[i];
          if (candidate.isConnected) { candidate.__pooled = false; continue; }
          if (!candidate.__pooled) { candidate.__pooled = true; return candidate; }
        }
        if (found.nodes.length > 6) found.nodes.splice(0, found.nodes.length - 6);
        var fresh = document.createElement('img');
        fresh.__pooled = true;
        found.nodes.push(fresh);
        return fresh;
      },
      /** 这个路径在本会话里**已经装好图了**（调用点据此跳过加载态、直接按真实比例摆好）。 */
      ready: function (path) { var found = entry(path); return !!(found && found.loaded && found.width > 0 && found.height > 0); },
      /** 已加载图的真实比例 `'234 / 320'`；没加载过给空串。 */
      ratio: function (path) {
        var found = entry(path);
        return found && found.loaded && found.width > 0 && found.height > 0 ? found.width + ' / ' + found.height : '';
      },
      /** 失败过（终态：不再重试、不再刷请求）。 */
      failed: function (path) { var found = entry(path); return !!(found && found.failed); },
      /** 记一次结果：成功连 naturalWidth/Height 一起记，失败记终态。 */
      mark: function (path, ok, img) {
        var found = ensure(path);
        if (ok && img && img.naturalWidth > 0 && img.naturalHeight > 0) {
          found.loaded = true; found.failed = false; found.width = img.naturalWidth; found.height = img.naturalHeight;
        } else if (!ok) { found.failed = true; }
        return found;
      },
      /** 小账本（探针/报告用）。 */
      stats: function () {
        var paths = 0, loaded = 0, failed = 0, nodes = 0;
        Object.keys(store).forEach(function (key) {
          paths++;
          if (store[key].loaded) loaded++;
          if (store[key].failed) failed++;
          nodes += store[key].nodes.length;
        });
        return { paths: paths, loaded: loaded, failed: failed, nodes: nodes };
      },
      /** 清空（换 scope / 重新登录时用；正常流程不需要）。 */
      clear: function () { store = Object.create(null); }
    };
  })();
  /**
   * 会话级图片缓存（出图屏与回执图集共用；见 {@link imageCache}）。
   *
   * <p>为什么必须有：轮询（1.5s / 3s 一次）与换屏重挂都会重新造 `<img>`。即便 URL 命中 HTTP 缓存，
   * **新节点仍要重新解码一次**，于是"已经加载好的图又回到加载中/骨架"（用户报的"错误载入加载中的
   * 图集行为"）；失败路径更糟：每重造一次就重试一次请求。缓存了**节点**与**状态**之后：
   *   · 已加载过的路径 → 复用同一个节点（0 请求、0 解码、永不回到加载态）；
   *   · 失败过的路径 → 直接出「图取不到」终态，不再请求。
   */
  PixikoM.imageCache = imageCache;

  /**
   * 给一个路径拿"装好的 `<img>`"：命中会话缓存就**连解码都不做**（同一个节点搬过来），
   * 否则新建并走 {@link mountImage} 正常装载（设 src、挂 load/error、按真实比例撑格子）。
   * @param {string} [alt] 先设好 alt 再装载（装载失败时占位块要拿它当说明/复制的路径）
   * @returns {{img:HTMLImageElement, cached:boolean, failed:boolean}}
   */
  function imageNodeFor(rawPath, index, onReady, alt) {
    var path = normalizeImagePath(rawPath);
    var src = thumbUrl(path);
    var img = imageCache.node(path, src);
    if (alt !== undefined) img.alt = alt;
    if (path && imageCache.failed(path)) return { img: img, cached: false, failed: true };
    if (path && imageCache.ready(path) && img.getAttribute('src') === src) {
      if (typeof onReady === 'function') onReady(img);      // 已经装好：不设 src、不挂监听、不请求
      return { img: img, cached: true, failed: false };
    }
    mountImage(img, rawPath, index, onReady);
    return { img: img, cached: false, failed: false };
  }

  /**
   * 统一的图片装载（对话气泡 / 出图图集 / 进度格子都走它）：
   *   · 归一后的缩略图地址；`decoding=async`；
   *   · 失败 → 换成「图取不到」占位块（**只换这一张**，不清空整个图集/气泡），长按/点击复制路径；
   *   · 成功 → 把**格子的宽高比**换成原图宽高比（`applyNaturalRatio`），并记进会话缓存；
   *     调用点自己的 `onReady` 照旧回调。
   */
  function mountImage(img, rawPath, index, onReady) {
    var path = normalizeImagePath(rawPath);
    var src = thumbUrl(path);
    img.decoding = 'async';
    img.loading = loadingFor(img, index);
    img.src = src;
    if (!path) { markImageFail(img, img.alt || rawPath); return img; }
    img.addEventListener('error', function () { imageCache.mark(path, false, img); markImageFail(img, path); }, { once: true });
    function ready() {
      // 按图片自身的宽高比撑格子（旧写法是给非 1:1 的图加 .mx-contain → contain 四周一片空白，已删）
      applyNaturalRatio(img);
      imageCache.mark(path, true, img);
      if (typeof onReady === 'function') onReady(img);
    }
    if (img.complete) { if (img.naturalWidth > 0) ready(); }
    else img.addEventListener('load', ready, { once: true });
    return img;
  }

  /** 把失败的 `<img>` 换成占位块（保留盒子尺寸，别的图不受影响）。 */
  function markImageFail(img, label) {
    var host = img.parentNode;
    if (!host || host.getAttribute('data-img-failed') === '1') return;
    host.setAttribute('data-img-failed', '1');
    if (host.classList) host.classList.remove('loading');       // 失败态不是"还在加载"，别留骨架灰条
    var path = String(label || img.alt || '');
    if (path && !imageFailures[path]) imageFailures[path] = true;
    try { host.removeChild(img); } catch (error) { /* 已经被换掉了 */ }
    host.appendChild(imageFailBox(path));
  }

  /**
   * 全屏图片查看器：左右翻页、双指缩放、长按交给原生桥。
   * @param {Array<{src:string, caption?:string}>} list
   * @param {number} index
   * @returns {{close:function}}
   */
  PixikoM.openViewer = function (list, index) {
    // 归一成 {src, caption}：`src` 保持**原始写法**（`file:///…` 或 `data/generated/…` 都行），
    // 由 `imageUrl` 在真正设 `<img src>` 时归一 —— 查看器一律**原图**（不带 w），保存/分享才拿得到全分辨率。
    var items = (list || []).filter(Boolean).map(function (item) {
      if (typeof item === 'string') return { src: item, caption: '' };
      if (item && typeof item === 'object' && typeof item.src === 'string') return { src: item.src, caption: item.caption || '' };
      return { src: imageOf(item), caption: (item && item.caption) || '' };
    }).filter(function (item) { return !!item.src; });
    if (!items.length) return { close: function () { } };
    var at = Math.max(0, Math.min(items.length - 1, Number(index) || 0));

    var panel = el('div', 'viewer');
    panel.setAttribute('data-viewer', '1');
    /* 两个翻页按钮**必须排在 `<img>` 后面**：`img` 上有 `will-change: transform`（app.css），
       它自成一个层叠上下文，而绝对定位的按钮是 z-index:auto —— 同一层里按 DOM 顺序画，
       排在 img 前面的那个（原来的「上一张」）会被图片整个盖住：看不见、也点不到
       （用户报的「向左浏览的按钮缺失」就是这么来的）。再给它们显式 z-index 兜一层。 */
    panel.innerHTML =
      '<div class="viewer-top"><span class="viewer-count"></span>' +
        '<button class="bar-btn" data-viewer-close="1" type="button" aria-label="关闭">' +
          '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg>' +
        '</button></div>' +
      '<div class="viewer-stage">' +
        '<img alt="" draggable="false">' +
        '<button class="viewer-nav prev" data-viewer-prev="1" type="button" aria-label="上一张">' +
          '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15 5l-7 7 7 7"/></svg></button>' +
        '<button class="viewer-nav next" data-viewer-next="1" type="button" aria-label="下一张">' +
          '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 5l7 7-7 7"/></svg></button>' +
      '</div>' +
      '<div class="viewer-bottom"></div>';

    var root = $('m-overlay') || document.body;
    root.appendChild(panel);
    nextFrame(function () { panel.classList.add('in'); });

    var img = panel.querySelector('img');
    var countNode = panel.querySelector('.viewer-count');
    var capNode = panel.querySelector('.viewer-bottom');
    var prevBtn = panel.querySelector('[data-viewer-prev]');
    var nextBtn = panel.querySelector('[data-viewer-next]');
    prevBtn.style.zIndex = '2';
    nextBtn.style.zIndex = '2';
    var scale = 1, tx = 0, ty = 0;

    /* 翻页箭头贴在**图片本体**左右两侧（用户报的"箭头放到最两边、离图太远"）。
       `.viewer-stage img` 的 max-width 已经给箭头留出 2×(44+12) 的位置，所以这里按图片的实际
       矩形算 left/right 就一定能落在图片外面：距离 12px、整体夹在 stage 内（不出屏、不盖图）。
       换图 / 图片解码完 / 窗口尺寸变化 / 旋转都会重算。 */
    var NAV_GAP = 12;
    function placeNav() {
      if (!panel.parentNode || !img) return;
      var stageRect = stage.getBoundingClientRect();
      if (!stageRect.width) return;
      var imgRect = img.getBoundingClientRect();
      var size = Math.round(prevBtn.getBoundingClientRect().width) || 44;
      var cap = Math.max(4, Math.round(stageRect.width - size - 4));
      var left = Math.round(imgRect.left - stageRect.left - NAV_GAP - size);
      var right = Math.round(stageRect.right - imgRect.right - NAV_GAP - size);
      prevBtn.style.left = Math.max(4, Math.min(left, cap)) + 'px';
      prevBtn.style.right = 'auto';
      nextBtn.style.right = Math.max(4, Math.min(right, cap)) + 'px';
      nextBtn.style.left = 'auto';
    }
    img.addEventListener('load', function () { placeNav(); });
    window.addEventListener('resize', placeNav);
    window.addEventListener('orientationchange', placeNav);

    function apply() {
      img.style.transform = 'translate(' + tx + 'px,' + ty + 'px) scale(' + scale + ')';
    }
    function reset() { scale = 1; tx = 0; ty = 0; img.classList.add('snap'); apply(); setTimeout(function () { img.classList.remove('snap'); }, 180); }

    function render() {
      var item = items[at];
      img.src = PixikoM.imageUrl(item.src);
      img.alt = item.caption || '';
      countNode.textContent = (at + 1) + ' / ' + items.length;
      capNode.textContent = item.caption || '';
      prevBtn.disabled = at <= 0;
      nextBtn.disabled = at >= items.length - 1;
      reset();
      nextFrame(placeNav);                  // 换图之后（比例/尺寸都变了）重新贴一次图片两侧
    }
    function step(delta) {
      var next = at + delta;
      if (next < 0 || next >= items.length) return;
      at = next; render();
    }
    var closed = false;
    function close() {
      if (closed) return;
      closed = true;
      panel.classList.remove('in');
      setTimeout(function () { if (panel.parentNode) panel.parentNode.removeChild(panel); }, 180);
      document.removeEventListener('keydown', onKey);
      window.removeEventListener('resize', placeNav);
      window.removeEventListener('orientationchange', placeNav);
      if (stopPoll) stopPoll();
    }
    function onKey(event) {
      if (event.key === 'Escape') close();
      else if (event.key === 'ArrowLeft') step(-1);
      else if (event.key === 'ArrowRight') step(1);
    }
    var stopPoll = PixikoM.pollWhileVisible(function () { /* 查看器打开时保持存活，便于离开/回来重排 */ }, 4000);

    panel.querySelector('[data-viewer-close]').addEventListener('click', close);
    prevBtn.addEventListener('click', function () { step(-1); });
    nextBtn.addEventListener('click', function () { step(1); });
    document.addEventListener('keydown', onKey);

    // 长按图片 → 原生桥（Android 外壳注入的 PixikoNative）；桌面上没桥时用 sheet 兜底。
    var holdTimer = null;
    function holdStart() {
      clearTimeout(holdTimer);
      holdTimer = setTimeout(function () {
        var item = items[at];
        var src = PixikoM.imageUrl(item.src);
        var label = item.caption || '';
        if (window.PixikoNative && typeof window.PixikoNative.showImageMenu === 'function') {
          try { window.PixikoNative.showImageMenu(src, label); return; } catch (error) { /* 落到兜底 */ }
        }
        PixikoM.sheet({
          title: label || '这张图片',
          items: [{
            text: '在新标签打开', sub: '桌面上没有原生桥时的兜底', onSelect: function () { window.open(src, '_blank'); }
          }, {
            text: '复制图片地址', onSelect: function () {
              var input = el('textarea'); input.value = src; document.body.appendChild(input); input.select();
              try { document.execCommand('copy'); PixikoM.toast('已复制图片地址'); } catch (error) { PixikoM.toast('复制失败'); }
              document.body.removeChild(input);
            }
          }]
        });
      }, 520);
    }
    function holdCancel() { clearTimeout(holdTimer); holdTimer = null; }
    img.addEventListener('touchstart', holdStart, { passive: true });
    img.addEventListener('touchend', holdCancel, { passive: true });
    img.addEventListener('touchmove', holdCancel, { passive: true });
    img.addEventListener('contextmenu', function (event) {
      event.preventDefault();
      holdCancel();
      var item = items[at];
      var src = PixikoM.imageUrl(item.src);
      if (window.PixikoNative && typeof window.PixikoNative.showImageMenu === 'function') {
        try { window.PixikoNative.showImageMenu(src, item.caption || ''); } catch (error) { /* ignore */ }
      }
    });

    // 手势：单指左右翻页；双指捏合缩放；放大后单指拖动平移。
    var pointers = Object.create(null);
    var startX = 0, startY = 0, startDist = 0, startScale = 1, startTx = 0, startTy = 0, moved = false;
    var startTarget = null;      // 本次手势的落点：落在翻页按钮上时，单击翻页交给按钮自己（否则会**翻两张**）
    function pointList() { return Object.keys(pointers).map(function (k) { return pointers[k]; }); }
    function dist(a, b) { return Math.hypot(a.x - b.x, a.y - b.y); }
    var stage = panel.querySelector('.viewer-stage');
    stage.addEventListener('pointerdown', function (event) {
      if (pointList().length === 0) startTarget = event.target;
      pointers[event.pointerId] = { x: event.clientX, y: event.clientY };
      var all = pointList();
      moved = false;
      if (all.length === 1) { startX = event.clientX; startY = event.clientY; startTx = tx; startTy = ty; }
      else if (all.length === 2) { startDist = dist(all[0], all[1]); startScale = scale; }
      img.classList.add('snap');
    });
    stage.addEventListener('pointermove', function (event) {
      if (!pointers[event.pointerId]) return;
      pointers[event.pointerId] = { x: event.clientX, y: event.clientY };
      var all = pointList();
      if (all.length === 1) {
        var dx = event.clientX - startX, dy = event.clientY - startY;
        if (Math.abs(dx) > 6 || Math.abs(dy) > 6) moved = true;
        if (scale > 1.02) { tx = startTx + dx; ty = startTy + dy; apply(); }
      } else if (all.length === 2 && startDist > 0) {
        moved = true;
        scale = Math.max(1, Math.min(6, startScale * dist(all[0], all[1]) / startDist));
        if (scale <= 1.02) { scale = 1; tx = 0; ty = 0; }
        apply();
      }
    });
    function pointerUp(event) {
      // 只认"我们记过的指针"：兼容鼠标/多余 pointerup 也会落到这里，不挡的话一次点击能翻两张
      if (!pointers[event.pointerId]) return;
      var wasSingle = pointList().length === 1;
      delete pointers[event.pointerId];
      img.classList.remove('snap');
      if (wasSingle && !moved) {
        // 单击：左右半屏翻页（和大多数看图 app 一致）。
        // **落在翻页按钮上的那次不算** —— 按钮自己的 click 会翻一张，这里再翻就跳张了。
        var onNav = !!(startTarget && startTarget.closest && startTarget.closest('.viewer-nav'));
        if (!onNav) {
          var rect = stage.getBoundingClientRect();
          if (event.clientX < rect.left + rect.width * 0.3) step(-1);
          else if (event.clientX > rect.left + rect.width * 0.7) step(1);
          else if (scale > 1.02) reset();
        }
      }
      if (!moved && pointList().length === 0 && scale <= 1.02) { tx = 0; ty = 0; apply(); }
      if (pointList().length === 0) startTarget = null;
    }
    stage.addEventListener('pointerup', pointerUp);
    stage.addEventListener('pointercancel', function (event) { delete pointers[event.pointerId]; img.classList.remove('snap'); });

    render();
    return { close: close, next: function () { step(1); }, prev: function () { step(-1); }, index: function () { return at; } };
  };

  /** 只在"这一屏还可见"时轮询：切屏、页面隐藏、元素被移除都会自动停。 */
  /* ── 「页面又活了」的唤醒通道 ─────────────────────────────────────────────
   * 为什么需要：安卓 WebView（以及某些壳）里 `visibilitychange` 可能**根本不派发**，`document.hidden`
   * 甚至可能一直停在 `true` —— 于是"页面隐藏时不排下一拍"的轮询再也醒不过来，用户看到的就是
   * 「对话不在焦点就不返回消息」。所以：把"重新可见"的所有可能信号（事件 + 看门狗 + 用户碰一下）
   * 汇到一个注册表里，谁在轮询谁登记，任意一个信号到了就**立刻补一拍**。
   */
  var wakeHandlers = [];
  var wakeBound = false;
  function fireWake(why) {
    wakeHandlers.slice().forEach(function (fn) { try { fn(why); } catch (error) { /* 一个唤醒失败不影响别的 */ } });
  }
  /** 登记一个"该补一拍了"的回调；返回注销函数。 */
  PixikoM.onWake = function (fn) {
    if (typeof fn !== 'function') return function () {};
    wakeHandlers.push(fn);
    if (!wakeBound) {
      wakeBound = true;
      var onEvent = function (why) { return function () { fireWake(why); }; };
      document.addEventListener('visibilitychange', onEvent('visibilitychange'));
      window.addEventListener('focus', onEvent('focus'));
      window.addEventListener('pageshow', onEvent('pageshow'));
      document.addEventListener('resume', onEvent('resume'));           // 某些安卓壳会派这个
      window.addEventListener('online', onEvent('online'));
      /* 用户碰一下 = 页面一定在前台 —— **不信 `document.hidden`**，有的壳里它一直是 true。 */
      ['pointerdown', 'touchstart', 'keydown'].forEach(function (name) {
        window.addEventListener(name, onEvent('user:' + name), { passive: true, capture: true });
      });
    }
    return function () { var at = wakeHandlers.indexOf(fn); if (at >= 0) wakeHandlers.splice(at, 1); };
  };

  /**
   * 按 `ms` 轮询。**页面隐藏时不再主动停表**：隐藏期间浏览器本来就会节流定时器（省电，正常、不对抗），
   * 但主动停表就等于"服务端已经发好的图，要等我切回来才收" —— 用户看到的正是"失焦就不发图"。
   *
   * <p>保住的三重保底（任何一条生效就够）：
   *   ① `visibilitychange` / `focus` / `pageshow` / `resume` / `online` 事件（见 {@link PixikoM.onWake}）
   *      —— 回到前台**立刻补一拍**，不必等下一轮；
   *   ② **看门狗**：每 1.5 秒自查一次，只要定时器不在就补排 —— 专治"事件没派发"（安卓壳）；
   *   ③ **用户交互**（touch/pointer/keydown）：看不见状态也能立刻补一拍。
   *
   * <p>去重照旧：补拍不会重复画（`seenText` / `seenImages` / 服务端存档的窗口差集）。
   */
  PixikoM.pollWhileVisible = function (fn, ms) {
    var interval = Math.max(200, Number(ms) || 1000);
    var stopped = false, timer = null, running = false;
    /* 这一拍是不是"被系统冻过之后补的"：传给 fn 的第一个参数（见下面看门狗）。
       只有需要区分"正常到点"与"刚从挂起里出来"的调用方（对话屏的补拉链）会用它。 */
    var stalledThisTick = false;
    /* 只看"还有没有这一屏"：`document.hidden` 不再参与 —— 有的安卓壳里它一直停在 true，
       拿它当闸门反而会把轮询永久掐死（那正是这套唤醒通道当初要绕开的坑）。 */
    function canRun() { return !stopped && !!currentId; }
    async function tick() {
      timer = null;
      if (!canRun() || running) { schedule(); return; }
      running = true;
      var stalled = stalledThisTick;
      stalledThisTick = false;
      try { await fn(stalled); } catch (error) { /* 轮询失败不打断（错误由各屏自己呈现） */ }
      running = false;
      schedule();
    }
    function schedule(soon) {
      if (stopped || timer || !canRun()) return;
      timer = setTimeout(tick, soon ? 120 : interval);
    }
    /* 唤醒：定时器已经没了才补一拍（正常跑着的轮询不被手势插队多打一次请求）。 */
    function wake(why) {
      if (stopped) return;
      if (timer) return;
      schedule(true);
    }
    function onVisibility() {
      if (stopped) return;
      if (timer) { clearTimeout(timer); timer = null; }   // 回到前台：立刻补一拍，不等下一轮
      schedule(true);
    }
    var stopWake = PixikoM.onWake(wake);
    document.addEventListener('visibilitychange', onVisibility);
    /* 看门狗：事件没派发也不怕（安卓壳兜底）—— 定时器不在就补排。
       还要认"被冻过"：页面被系统整体冻结（安卓把 WebView 挂起 / 浏览器把标签页冻住）时
       定时器会一起停；解冻后这一轮看门狗**迟到**（间隔明显大于它自己的节拍），
       那一刻必须立刻补一拍，并给这一拍打上 stalled 标记 —— 否则"挂起回来"要等下一个
       4 秒轮次 + 补拉链自己的最小间隔，用户看到的就是「回来半天才出图」（用户报的正是这个）。
       阈值按**看门狗自己的节拍**算（不是被轮询的 interval）：它每 1500ms 跑一次，
       正常抖动最多几百毫秒，迟到 1 秒以上就只可能是"定时器被冻过"。
       为什么不在看门狗里直接补拉：这里只是"拍子"的调度器，补什么由各屏自己决定。 */
    var WATCHDOG_MS = 1500;
    var lastWatchdogAt = Date.now();
    var watchdog = setInterval(function () {
      if (stopped) return;
      var now = Date.now();
      var stalled = now - lastWatchdogAt > WATCHDOG_MS + 1000;
      lastWatchdogAt = now;
      if (stalled) {
        if (timer) { clearTimeout(timer); timer = null; }   // 作废在等的那一拍，立刻补
        stalledThisTick = true;
        schedule(true);
        return;
      }
      if (!timer) schedule(true);
    }, WATCHDOG_MS);
    timer = setTimeout(tick, Math.min(interval, 400));
    return function stop() {
      stopped = true;
      stopWake();
      clearInterval(watchdog);
      if (timer) { clearTimeout(timer); timer = null; }
      document.removeEventListener('visibilitychange', onVisibility);
    };
  };

  /* ── 3. 状态 ─────────────────────────────────────────────────────────── */
  /** 全局共享状态：{}里的字段是契约的一部分，别的屏幕会读。 */
  PixikoM.state = {
    status: null,                                   // /api/status 的原始返回
    quests: { unread: 0, latest: 0 },               // 回执未读与最新任务号
    tasks: [],                                      // /api/tasks
    progress: {},                                   // /api/progress
    generation: null,                               // state.status.generation（生成参数）
    lastError: null                                 // {code, message} 最近一次失败的分类，便于错误态文案
  };

  /** 状态变化广播：其它屏幕可以 PixikoM.on('status', fn) 订阅。 */
  var listeners = Object.create(null);
  PixikoM.on = function (name, fn) {
    (listeners[name] || (listeners[name] = [])).push(fn);
    return function () { listeners[name] = (listeners[name] || []).filter(function (f) { return f !== fn; }); };
  };
  function emit(name, payload) {
    var list = listeners[name] || [];
    for (var i = 0; i < list.length; i++) { try { list[i](payload); } catch (error) { /* 一个订阅者出错不影响别人 */ } }
  }

  /**
   * 拉一次 /api/status（用 POST，接口 GET/POST 都收），更新 state.status / state.quests，
   * 并广播 'status'。失败时按分类记进 state.lastError 后**原样抛出**（调用方决定怎么显示）。
   */
  PixikoM.refreshStatus = async function () {
    try {
      var data = await PixikoM.api('/api/status', { body: { scope: PixikoM.scope() } });
      PixikoM.state.status = data || null;
      PixikoM.state.generation = data && data.generation ? data.generation : null;
      var quests = (data && data.quests) || {};
      var unread = fmtNum(quests.unread, 0);
      var latest = fmtNum(quests.latest, 0);
      /* 未读角标的权威来源：服务端按会话筛过（quests.scoped）就用它；
         还没筛（老服务端/未重启）而回执屏已经在用本机账本过滤时，用**列表自己算的**那个数 ——
         否则会出现"角标 6 条、点进去 1 条"（用户报的同一类不一致）。 */
      var view = PixikoM.questView;
      if (view && view.filtering && !(quests.scoped === true)) {
        unread = fmtNum(view.unread, unread);
        latest = fmtNum(view.latest, latest);
      }
      PixikoM.state.quests = { unread: unread, latest: latest, total: fmtNum(quests.total, unread) };
      PixikoM.state.lastError = null;
      renderQuestBadge();
      emit('status', PixikoM.state);
      return data;
    } catch (error) {
      PixikoM.state.lastError = { code: error.code || 'unknown', message: error.message };
      emit('status', PixikoM.state);
      throw error;
    }
  };

  /** 回执角标（底部 tab 上的红点数字）。 */
  function renderQuestBadge() {
    var node = document.querySelector('.tab-badge[data-badge="quest"]');
    if (!node) return;
    var unread = fmtNum(PixikoM.state.quests.unread, 0);
    if (unread > 0) { node.textContent = unread > 99 ? '99+' : String(unread); node.hidden = false; }
    else { node.hidden = true; node.textContent = ''; }
  }

  /* ── 4. 三态（加载 / 空 / 错误）公共件 ───────────────────────────────── */

  /** 骨架屏：rows 行列表占位。 */
  function skeleton(rows, kind) {
    var box = el('div', 'sk-list');
    var count = Math.max(1, rows || 3);
    for (var i = 0; i < count; i++) box.appendChild(el('div', 'sk ' + (kind === 'bubble' ? 'sk-bubble' + (i % 2 ? ' right' : '') : 'sk-row')));
    return box;
  }

  /**
   * 统一空态。
   * @returns {HTMLElement}
   */
  function emptyState(text, actionText, onAction) {
    var box = el('div', 'state state-empty');
    box.setAttribute('data-state', 'empty');
    box.innerHTML = '<svg class="state-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16v12H4zM4 7l3-3h10l3 3"/><path d="M9 13h6"/></svg>' +
      '<p class="state-title">没有内容</p><p class="state-text">' + esc(text || '') + '</p>';
    if (actionText) {
      var btn = el('button', 'btn ghost', actionText);
      btn.type = 'button';
      btn.addEventListener('click', function () { if (onAction) onAction(); });
      box.appendChild(btn);
    }
    return box;
  }

  /**
   * 统一错误态：**区分连不上（网络/超时）与令牌不对（401）**，都带「重试」。
   */
  function errorState(error, onRetry) {
    var code = error && error.code ? error.code : 'unknown';
    var title, text;
    if (code === 'unauthorized') {
      title = '令牌不对';
      text = (error && error.message) || '访问令牌不正确，请在「更多 → 访问令牌」里改成 config.json 的 webui.access_token。';
    } else if (code === 'network' || code === 'timeout') {
      title = '连不上机器人';
      text = (error && error.message) || '网络不通或机器人没在跑，确认地址后重试。';
    } else {
      title = '读取失败';
      text = (error && error.message) || '机器人返回了一个错误。';
    }
    var box = el('div', 'state state-error');
    box.setAttribute('data-state', 'error');
    box.setAttribute('data-error-code', code);
    box.innerHTML = '<svg class="state-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3l9 16H3z"/><path d="M12 9v5M12 17h.01"/></svg>' +
      '<p class="state-title">' + esc(title) + '</p><p class="state-text">' + esc(text) + '</p>';
    var btn = el('button', 'btn primary', '重试');
    btn.type = 'button';
    btn.setAttribute('data-retry', '1');
    btn.addEventListener('click', function () { if (onRetry) onRetry(); });
    box.appendChild(btn);
    return box;
  }

  /* ── 5. 外壳：app bar / 底部 tab / 路由 / 下拉刷新 / 键盘 ─────────────── */

  function ensureMounted(id) {
    if (mounted[id]) return screens[id];
    var spec = screens[id];
    if (!spec) return null;
    var root = el('section', 'screen');
    root.setAttribute('data-screen', id);
    root.id = 'screen-' + id;
    $('m-main').appendChild(root);
    mounted[id] = true;
    try { spec.mount(root); } catch (error) {
      // 一个屏幕挂掉不能带走整个 app：把错误画在这一屏里。
      clear(root);
      root.appendChild(errorState({ code: 'unknown', message: '这一屏加载失败：' + (error && error.message ? error.message : error) }, function () { render(); }));
    }
    return spec;
  }

  function renderBar() {
    var spec = screens[currentId];
    var title = (spec && spec.title) || TITLES[currentId] || currentId || 'Pixiko';
    var node = $('m-bar-title');
    if (node) node.textContent = title;
    document.title = title + ' · Pixiko';
    // 二级屏（不在底部 tab 里的）给一个返回按钮：等价于 Android 返回键。
    var isSecondary = SECONDARY.indexOf(currentId) >= 0 || /-detail$/.test(String(currentId || ''));
    var back = $('m-bar-back');
    if (back) {
      back.hidden = !isSecondary;
      back.setAttribute('data-back-for', isSecondary ? String(currentId) : '');
    }
  }

  /** 高亮当前 tab（secondary 屏没有对应 tab，则全部不高亮）。 */
  function renderTabs() {
    var nodes = document.querySelectorAll('#m-tabs .tab');
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      var on = node.getAttribute('data-nav') === currentId;
      node.classList.toggle('on', on);
      node.setAttribute('aria-selected', on ? 'true' : 'false');
    }
  }

  /** 应用当前 hash：挂屏、切成 active、更新 app bar 与 tab。 */
  function applyRoute() {
    var raw = String(location.hash || '').replace(/^#\/?/, '').split('?')[0].split('/')[0];
    var id = raw || 'chat';
    if (!screens[id] && TITLES[id] === undefined && !raw) id = 'chat';
    // scope 变了（外壳改了地址栏、或注入进了 window.__PIXIKO_SCOPE）＝ 眼前这一屏的数据已经属于「另一个人」。
    // 这里**只做一件事：整页重载**。为什么不就地重挂屏幕 —— 每屏的 mount 只在首次进入时跑一次，
    // 就地重挂要挨个通知六个 screen-*.js 丢掉自己的缓存（列表、图集、记账表都在各自闭包里），
    // 漏一个就会拿旧 scope 的内容接着显示。重载是唯一「所有状态一起归零」的做法，而且外壳的下拉刷新
    // 本来就是整页重载（MainActivity 的 SwipeRefreshLayout → webView.reload），这条路已经跑熟了。
    var liveScope = PixikoM.scope();
    if (scopeGuardArmed && liveScope !== routedScope) {
      routedScope = liveScope;
      location.reload();
      return;
    }
    if (id === currentId && mounted[id]) { renderBar(); renderTabs(); return; }

    // 离开旧屏：所有屏幕的滚动位置各自保留（display:none 不会丢 scrollTop）
    var previous = $('m-main').querySelector('.screen.active');
    if (previous) { previous.classList.remove('active'); if (previous._ptrStop) { previous._ptrStop(); previous._ptrStop = null; } }
    currentId = id;

    if (!screens[id]) {
      // 没注册（screen-*.js 缺失或还没加载完）：画一张"还没做好"的占位，**绝不让整个 app 崩**。
      var missingId = 'missing-' + id;
      var existing = document.getElementById('screen-' + missingId);
      if (!existing) {
        var root = el('section', 'screen');
        root.id = 'screen-' + missingId;
        root.setAttribute('data-screen', missingId);
        root.innerHTML = '<div class="missing"><h2>「' + esc(TITLES[id] || id) + '」还没做好</h2>' +
          '<p>这一屏的脚本（screen-' + esc(id) + '.js）没有加载成功。' +
          '不影响其它屏：底部其它 tab 照常可用。</p></div>';
        $('m-main').appendChild(root);
      }
      document.getElementById('screen-' + missingId).classList.add('active');
      renderBar(); renderTabs();
      return;
    }

    var spec = ensureMounted(id);
    var screen = document.getElementById('screen-' + id);
    if (screen) screen.classList.add('active');
    if (spec && typeof spec.refresh === 'function') {
      // 首次进入也刷新一次（mount 只负责搭骨架，数据由 refresh 拉）
      try { Promise.resolve(spec.refresh()).catch(function (error) { PixikoM.toast(error && error.message ? error.message : String(error)); }); }
      catch (error) { PixikoM.toast(error && error.message ? error.message : String(error)); }
    }
    renderBar(); renderTabs();
    var main = $('m-main');
    if (main) main.scrollTop = 0;
    // 进入对话屏：每条历史都要贴到最底（用户要求"每次点进去都应当在滚轮条最下方"）
    if (id === 'chat') chatEnterBottom();
    emit('route', id);
  }

  /** app bar 的刷新按钮：调当前屏的 refresh；没有就刷状态。 */
  async function refreshCurrent() {
    var spec = screens[currentId];
    if (spec && typeof spec.refresh === 'function') { await spec.refresh(); return; }
    if (currentId) await PixikoM.refreshStatus();
  }

  /** app bar 的溢出菜单：屏幕自己的动作 + 全局动作（服务器设置 / 刷新 / 关于）。 */
  function openBarMenu() {
    var items = [];
    var spec = screens[currentId];
    if (spec && typeof spec.menu === 'function') {
      try {
        var extra = spec.menu() || [];
        for (var i = 0; i < extra.length; i++) items.push(extra[i]);
      } catch (error) { /* 屏幕自己的菜单出错不影响全局项 */ }
    }
    if (currentId === 'chat') {
      items.push({
        text: '清空对话', danger: true, sub: '连同服务端那份正文一起清',
        onSelect: function () { clearChat(); }
      });
    }
    // 服务器设置：Android 外壳新增的桥方法；浏览器里没有那把桥，给一句人话。
    items.push({
      text: '服务器设置', sub: '换地址 / 令牌（Android app 里配）', onSelect: openServerSettings
    });
    items.push({
      text: '刷新这一屏', sub: '等价于右上角的刷新按钮',
      onSelect: function () { refreshCurrent().catch(function (error) { PixikoM.toast(error.message); }); }
    });
    items.push({
      text: '刷新运行状态', sub: '拉一次 /api/status',
      onSelect: function () { PixikoM.refreshStatus().then(function () { PixikoM.toast('状态已刷新'); }).catch(function (error) { PixikoM.toast(error.message); }); }
    });
    items.push({
      text: '关于这个界面', sub: '手机端 Web UI · 复用桌面版同一套 /api', onSelect: function () {
        var status = PixikoM.state.status || {};
        PixikoM.sheet({
          title: '关于',
          items: [{
            text: status.botName ? ('机器人：' + status.botName) : '机器人：未知',
            sub: 'scope：' + PixikoM.scope() + ' · 版本：' + (status.version || '—'),
            onSelect: function () { }
          }, {
            text: '当前地址', sub: location.origin, onSelect: function () { }
          }, {
            text: '提示词 / 出图参数都在桌面版同一套接口上跑（/api/*）', onSelect: function () { }
          }]
        });
      }
    });
    PixikoM.sheet({ title: (screens[currentId] && screens[currentId].title) || TITLES[currentId] || '', items: items });
  }

  /** 服务器设置：优先交给 Android 外壳的原生界面；没有桥时提示一句。 */
  function openServerSettings() {
    var bridge = window.PixikoNative;
    if (bridge && typeof bridge.openServerSettings === 'function') {
      try { bridge.openServerSettings(); return; } catch (error) { /* 落到下面的提示 */ }
    }
    PixikoM.toast('服务器设置在 Android app 里配置；浏览器里直接用当前地址即可');
  }

  /* ── 下拉刷新：外壳与各屏**共用同一份判据**（用户报的「上滑很容易触发刷新」）──────────────
   *
   * 老代码有三个坑，全在判据里：
   *   1) 只看 `#m-main.scrollTop`。可对话屏真正滚的是内层 `.chat-log`（帮助屏的日志框是
   *      `.hp-logscroll`、sheet 里是 `.st-panel-body`），`#m-main` 的 scrollTop 恒为 0 ——
   *      于是**列表滚在中间照样武装**，一松手就刷；
   *   2) `touchend` 只看 `armed`、不看 `tracking`，而 `touchstart` 被拒时又**不重置** `armed`：
   *      一次成功下拉之后，在列表中部**碰一下再抬手**（零位移）也会再刷一次；
   *   3) 外壳与屏各自装了一份（都挂 `#m-main`）：一次下拉触发**两次**刷新。
   * 现在三处（app.js 外壳 / screen-quest.js / screen-styles.js）都走 {@link PixikoM.ptrInstall}：
   *   · 认**手指起点所属的最近可滚动祖先**（`overflow-y: auto|scroll` 且真的 overflow），找不到才退回 `#m-main`；
   *   · 起手那一刻容器 `scrollTop !== 0` → 根本不武装；手势过程中那个容器**只要滚过一次**（含惯性、
   *     回弹到顶）→ 立刻作废且不恢复；
   *   · 位移取**手指竖直位移**（下拖为正）：> {@link PTR_SLOP} 显示提示，> {@link PTR_ARM} 松手才刷；
   *     向上位移超过 SLOP（明确上滑）同样作废 —— "上滑到顶再下拖"不会刷；
   *   · 一次触摸只有一个赢家：屏自己那份先认领，外壳那份闭嘴（不再刷两次）。
   */
  var PTR_SLOP = 12;      // 下拖超过它才显示提示
  var PTR_ARM = 56;       // 下拖超过它、松手才刷新（外壳原来是 66、两个屏是 56，现统一 56）
  var ptrGesture = { event: null, owner: null };

  /** 手指起点所属的、**真的在滚**的那个容器（最近的可滚动祖先）；找不到退回 `#m-main`。 */
  function ptrScrollHost(target) {
    var node = target && target.nodeType === 1 ? target : (target && target.parentElement);
    while (node && node !== document.body && node !== document.documentElement) {
      var style = null;
      try { style = window.getComputedStyle(node); } catch (error) { style = null; }
      if (style && /(auto|scroll|overlay)/.test(String(style.overflowY || '')) && node.scrollHeight > node.clientHeight + 1) return node;
      node = node.parentElement;
    }
    return $('m-main');
  }

  /**
   * 装一个下拉刷新手势（三处用同一份判据）。
   *
   * @param {Element} host 事件宿主（三处都是 `#m-main`；touch 事件从手指下的元素冒泡上来）
   * @param {{screen?:string,isFallback?:boolean,progress:function(boolean),disarm:function(),fire:function()}} hooks
   *   · `screen` 给了就只在 `PixikoM.current() === screen` 时参与（屏自己那份）；
   *   · `isFallback` 是外壳那份：只有这次手势**没有任何屏认领**时才刷；
   *   · `progress(armed)` 显示 / 更新提示（`armed` = 松手就刷），`disarm()` 收起提示，
   *     `fire()` 真刷新（返回 Promise 会等它结束，期间不再响应新手势）。
   * @returns {boolean} 装上没有（同一个 host + 同一份 hooks 只装一次）
   */
  PixikoM.ptrInstall = function (host, hooks) {
    if (!host || !hooks) return false;
    var bound = host.__ptrHooks || (host.__ptrHooks = []);
    if (bound.indexOf(hooks) >= 0) return false;
    bound.push(hooks);

    var startY = 0, tracking = false, armed = false, busy = false, moved = 0;
    var scrollHost = null, scrolled = false, watch = null;

    function stopWatch() { if (watch && scrollHost) { scrollHost.removeEventListener('scroll', watch); watch = null; } }
    function disarm() { armed = false; hooks.disarm(); }
    function active() { return !hooks.screen || (typeof PixikoM.current === 'function' && PixikoM.current() === hooks.screen); }
    function finish(fire) {
      tracking = false; armed = false; stopWatch();
      hooks.disarm();
      if (!fire || busy) return;
      busy = true;
      Promise.resolve(hooks.fire()).catch(function () { /* 错误已经由各屏自己显示 */ }).then(function () { busy = false; });
    }

    host.addEventListener('touchstart', function (event) {
      // 一次触摸一个令牌（同一事件对象只建一次）：认领关系不跨手势
      if (ptrGesture.event !== event) ptrGesture = { event: event, owner: null };
      // **无条件**重置：绝不能把上一次手势的 armed 带到这一次（老代码就是这么误刷的）
      tracking = false; armed = false; moved = 0; scrolled = false;
      stopWatch();
      if (busy || !active() || !event.touches || event.touches.length !== 1) return;
      var touch = event.touches[0];
      scrollHost = ptrScrollHost(touch.target || event.target);
      if (!scrollHost) return;
      if (scrollHost.scrollTop > 0) { scrolled = true; return; }      // 起手就不在顶部 → 这次不作数
      startY = touch.clientY;
      tracking = true;
      // 手势期间容器滚动过（含惯性/回弹到顶）→ 立刻作废（scroll 事件比 touchmove 采样更及时）
      watch = function () { scrolled = true; if (tracking) disarm(); };
      scrollHost.addEventListener('scroll', watch, { passive: true });
    }, { passive: true });

    host.addEventListener('touchmove', function (event) {
      if (!tracking) return;
      if (event.touches.length !== 1 || scrolled || !scrollHost || scrollHost.scrollTop > 0) {
        if (scrollHost && scrollHost.scrollTop > 0) scrolled = true;
        disarm();
        return;
      }
      var touch = event.touches[0];
      var delta = touch.clientY - startY;                             // 手指下拖为正
      if (delta < moved) moved = delta;                               // 记下本次手势最靠上的位置
      if (delta <= PTR_SLOP || moved < -PTR_SLOP) { disarm(); return; }   // 没下拖 / 先明显上滑 → 不武装
      armed = delta > PTR_ARM;
      if (armed) {
        if (!ptrGesture.owner) ptrGesture.owner = hooks;              // 谁先武装谁认领
        if (ptrGesture.owner !== hooks) { disarm(); return; }         // 已经被别的（屏自己的）认领 → 让位
      }
      hooks.progress(armed);
    }, { passive: true });

    host.addEventListener('touchend', function () {
      // 必须"这次手势真的武装过"才算（老代码漏了 tracking，才会被上一次的 armed 带着误刷）
      var fire = tracking && armed;
      if (fire && ptrGesture.owner && ptrGesture.owner !== hooks) fire = false;
      finish(fire);
    }, { passive: true });
    host.addEventListener('touchcancel', function () { finish(false); }, { passive: true });
    return true;
  };

  /**
   * 下拉刷新（外壳这份是**兜底**：给没有自己实现 refresh 的屏用；有自己那份的屏会先认领，它就让位）。
   * 手势只在主区滚到顶且单指下拖时生效，位移超过阈值松手才刷；不做原生那种回弹动画。
   */
  function installPullToRefresh() {
    var main = $('m-main');
    var tip = el('div', 'ptr');
    tip.innerHTML = '<div class="ptr-track"><span class="spinner"></span><span class="ptr-text">下拉刷新</span></div>';
    main.insertBefore(tip, main.firstChild);
    var textNode = tip.querySelector('.ptr-text');
    var setText = function (value) { if (textNode) textNode.textContent = value; };
    PixikoM.ptrInstall(main, {
      isFallback: true,
      progress: function (armed) { tip.classList.add('on'); setText(armed ? '松手刷新' : '下拉刷新'); },
      disarm: function () { tip.classList.remove('on'); setText('下拉刷新'); },
      fire: function () {
        setText('正在刷新…');
        return Promise.resolve().then(refreshCurrent).then(function () { setText('下拉刷新'); });
      }
    });
  }

  /**
   * 软键盘：Android WebView 会缩 window.innerHeight、iOS 缩 visualViewport。
   * 两条路都算，取较大的那个当"被键盘盖住的高度"（--kb）。
   *
   * <p><b>关键判据（M4 报的 bug）</b>：不能把"窗口变矮"一律当成键盘。横竖屏旋转、浏览器
   * 被拖小、桌面模拟手机尺寸都会让 innerHeight 变小，以前那种"历史最大高度 − 当前高度"
   * 会算出 204px 甚至负数的 --kb，把 #m-app 压扁。
   * 现在只在**宽度基本不变**（差 ≤ 32px，旋转会显著改宽度）时才算键盘，并且：
   *   · --kb 永不为负；
   *   · 上限 40% 视口高（键盘再高也不该把内容压没）；
   *   · 窗口空闲 500ms（没再变）就清零 —— 键盘收起/窗口重排之后一定回到 0。
   */
  function installKeyboardHandling() {
    var lastWidth = window.innerWidth;
    var lastHeight = window.innerHeight;
    var idleTimer = null;
    var wasOpen = false;

    var apply = function () {
      var width = window.innerWidth;
      var height = window.innerHeight;
      var viewport = window.visualViewport;
      var widthStable = Math.abs(width - lastWidth) <= 32;
      var byViewport = viewport ? Math.max(0, height - viewport.height - viewport.offsetTop) : 0;
      var byWindow = widthStable ? Math.max(0, lastHeight - height) : 0;
      var hidden = Math.min(Math.round(Math.max(byViewport, byWindow)), Math.round(height * 0.4));
      document.documentElement.style.setProperty('--kb', Math.max(0, hidden) + 'px');
      var open = hidden > 40;
      lastWidth = width;
      lastHeight = height;
      if (open !== wasOpen) {
        wasOpen = open;
        // 键盘开合会改变可视区高度：在底部附近的话跟着贴底一次
        if (currentId === 'chat') setTimeout(chatFollow, 60);
      }
      // 空闲下来就清零：任何一次真实的键盘开合结束都会走到这里
      if (idleTimer) clearTimeout(idleTimer);
      idleTimer = setTimeout(function () {
        document.documentElement.style.setProperty('--kb', '0px');
        if (wasOpen) { wasOpen = false; if (currentId === 'chat') setTimeout(chatFollow, 60); }
      }, 500);
    };

    window.addEventListener('resize', apply);
    if (window.visualViewport) {
      window.visualViewport.addEventListener('resize', apply);
      window.visualViewport.addEventListener('scroll', apply);
    }
    // 页面重新可见时重新取基准（后台期间视口可能已经变过）
    document.addEventListener('visibilitychange', function () {
      if (document.hidden) return;
      lastWidth = window.innerWidth;
      lastHeight = window.innerHeight;
      document.documentElement.style.setProperty('--kb', '0px');
      if (currentId === 'chat') setTimeout(chatFollow, 60);
    });
    document.documentElement.style.setProperty('--kb', '0px');
  }

  /* ── 4b. 宽视口下的「手机模拟框」（安卓尺寸预览） ─────────────────────── */

  /** 设备框的基准尺寸：必须是安卓手机的尺寸，抽成常量好让 CSS 与断言对齐。 */
  var DEVICE = { width: 390, height: 844 };
  /** 框外上下留给"说明小字 + 内边距"的高度。 */
  var FRAME_CHROME = 96;
  /**
   * 缩放的**可读性下限**。0.7 时 15px 正文渲染成 10.5px，已经是"手机上还能读"的底线；
   * 再小（旧值 0.4 → 6px）就是用户抱怨的"框太小，参数整片糊掉、看不见"。
   * 窗口高度不够撑到 0.7 时**干脆不套框**（见 isWideViewport 的高度判据），走铺满那条路。
   */
  var MIN_DEVICE_SCALE = 0.7;
  /** 套框所需的最小"可用高度"：0.7 × 844 ≈ 591px，即 innerHeight 至少约 687px。 */
  var MIN_FRAME_AVAILABLE = MIN_DEVICE_SCALE * DEVICE.height;

  /**
   * "窄视口 / 宽视口"的判据，四个条件都要满足才画机身框：
   *   1. **顶层文档**（window.self === window.top）—— 被 /android 预览页嵌成同源 iframe 时，
   *      innerWidth 是 iframe 的宽度而不是电脑窗口的宽度，预览页已经有一个机身框了；
   *   2. **宽度够**（innerWidth > 560）—— 真机竖屏 390 / 430 不套框；
   *   3. **主指针不是触摸**（matchMedia('(pointer: coarse)') 为假）—— Android 外壳的 WebView 是
   *      顶层文档且 setUseWideViewPort(true)，手机上"横屏 844×390""平板竖屏 768×1024"都满足 1+2，
   *      只看宽度的话用户把手机一横过来就会缩成中间一条 156px 的窄条。
   *      不用 navigator.maxTouchPoints：带触摸屏的电脑上桌面 Chrome 也报非 0（实测本机报 10）。
   *   4. **高度够撑到可读性下限**（可用高 = innerHeight - FRAME_CHROME ≥ 0.7 × 844 ≈ 591px，
   *      即 innerHeight ≥ 约 687px）。窗口又矮又宽时（比如 1280×560，可用高只有 464px）套一个
   *      0.55 的框只会让 15px 正文变成 8px、参数全糊；这一屏本来就是响应式的，**铺满反而每个
   *      参数都看得清**，所以宁可放弃机身框。
   *
   * <p>为什么必须带 `window.self === window.top`：这一页也会被装进 Android 外壳的**网页预览页**
   * （`/android` 是个同源 iframe）。iframe 里 `innerWidth` 是**iframe 的宽度**，不是电脑窗口的宽度；
   * 预览页自己已经画了一个机身框，我们再画一个就成了"框里套框"。
   * 所以被嵌进任何 iframe 时一律不画框（CSS 那边也改成读 `html[data-wide="1"]`，
   * 于是 **JS 与 CSS 只有一个真相来源**，不会再出现"CSS 认宽、JS 认不宽"的分裂）。
   *
   */
  function isWideViewport() {
    var topLevel = true;
    try { topLevel = window.self === window.top; } catch (error) { topLevel = false; }
    if (!topLevel) return false;
    if (window.innerWidth <= 560) return false;
    // 高度不够就别套框：套了也只有 MIN_DEVICE_SCALE 以下的可读性，还不如铺满
    if (window.innerHeight - FRAME_CHROME < MIN_FRAME_AVAILABLE) return false;
    var coarse = false;
    try { coarse = window.matchMedia('(pointer: coarse)').matches; } catch (error) { coarse = false; }
    return !coarse;
  }

  /**
   * 缩放档位：只按可用高度等比缩，最大 1（不放大），保留两位小数。
   * 下限是 MIN_DEVICE_SCALE（0.7）—— isWideViewport 已经保证可用高够到它，这里的 max 是双保险。
   */
  function deviceScale() {
    var available = window.innerHeight - FRAME_CHROME;
    return Math.max(MIN_DEVICE_SCALE, Math.min(1, Math.round((available / DEVICE.height) * 100) / 100));
  }

  /**
   * 宽视口：标 data-wide="1" 并算好 --device-scale，让 CSS 把 app 装进 390×844 的框里；
   * 窄视口：标 data-wide="0"，CSS 第 13 段不命中，app 照旧铺满整屏（真机体验）。
   */
  function installDeviceFrame() {
    var apply = function () {
      var wide = isWideViewport();
      var scale = wide ? deviceScale() : 1;
      var root = document.documentElement;
      root.setAttribute('data-wide', wide ? '1' : '0');
      root.style.setProperty('--device-scale', String(scale));
      root.style.setProperty('--device-width', DEVICE.width + 'px');
      root.style.setProperty('--device-height', DEVICE.height + 'px');
      var tag = $('m-frame-scale');
      if (tag) tag.textContent = Math.round(scale * 100) + '%';
      var note = $('m-frame-note');
      if (note) note.hidden = !wide;
    };
    apply();
    window.addEventListener('resize', apply);
    window.addEventListener('orientationchange', apply);
  }

  /* ── 6. 屏幕：chat（对话） ───────────────────────────────────────────── */

  /** 对话正文（本地为准，和服务端 data/webui/<scope>-chat-log.json 同形状）。 */
  var chat = {
    entries: [],
    host: null,        // .chat-log
    sendBtn: null,
    input: null,
    execute: true,
    sending: false,
    captureStop: null,
    loading: false,
    loaded: false,
    seq: 0
  };

  var CHAT_ENTRIES_CAP = 200;      // 与 ChatLogStore.ENTRIES_CAP 同值

  function chatEntry(role, text, images) {
    var entry = { role: role, text: String(text === null || text === undefined ? '' : text), seq: ++chat.seq };
    if (images && images.length) entry.images = images.slice(0);
    return entry;
  }

  /** 规范化服务端回来的条目（role 只认 user/bot/sys）。 */
  function chatNormalize(raw) {
    var out = [];
    var list = raw || [];
    for (var i = 0; i < list.length; i++) {
      var item = list[i];
      if (!item || typeof item !== 'object') continue;
      var role = item.role === 'user' || item.role === 'sys' ? item.role : 'bot';
      var text = item.text === null || item.text === undefined ? '' : String(item.text);
      var images = Array.isArray(item.images) ? item.images.filter(function (p) { return typeof p === 'string' && p; }) : [];
      if (!text && !images.length) continue;
      out.push({ role: role, text: text, images: images, seq: ++chat.seq });
    }
    return out.slice(-CHAT_ENTRIES_CAP);
  }

  /**
   * 对话里**所有**图片（按时间顺序）。气泡点图进查看器时用它当列表 —— 于是"最近生成"的那几张
   * 在对话里也能左右翻（用户报的"最近生成的图片查看器也应该带有浏览箭头"：老写法只把**这一条气泡**里
   * 的图当列表，一条消息一张图时列表长度就是 1，两个箭头全是灰的，看着就像没有箭头）。
   */
  function chatImageList() {
    var out = [];
    (chat.entries || []).forEach(function (entry) {
      (entry.images || []).forEach(function (raw) {
        var path = normalizeImagePath(raw);
        if (path) out.push({ src: raw, caption: String(path).replace(/^.*[\\/]/, '') || '图片' });
      });
    });
    return out;
  }

  /** 这张图在 {@link chatImageList} 里的下标（同一张出现多次时取最靠前的那次）。 */
  function chatImageIndex(raw) {
    var target = normalizeImagePath(raw);
    var at = -1, seen = 0;
    (chat.entries || []).forEach(function (entry) {
      (entry.images || []).forEach(function (item) {
        if (at < 0 && normalizeImagePath(item) === target) at = seen;
        seen++;
      });
    });
    return at < 0 ? 0 : at;
  }

  /** 一条条目 → DOM。图片缩略图点击进查看器。 */
  function chatBubble(entry) {
    var node = el('div', 'bubble ' + (entry.role === 'user' ? 'user' : entry.role === 'sys' ? 'sys' : 'bot'));
    if (entry.images && entry.images.length) node.classList.add('imgs');
    if (entry.text) node.appendChild(el('div', 'bubble-text', entry.text));
    if (entry.images && entry.images.length) {
      var box = el('div', 'bubble-imgs' + (entry.images.length === 1 ? ' one' : ''));
      entry.images.forEach(function (raw, index) {
        var src = normalizeImagePath(raw);
        // 每张图外面套一层宽高比宿主：CSS 先给中性 4:3 占位，图 load 后由 applyNaturalRatio
        // 换成原图真实比例（不用固定 height + object-fit，免得横图被裁、竖图被挤成小方块）。
        // 命中会话缓存时连占位都不用：直接按已知的真实比例摆好（见 imageCache）。
        var cell = el('div', 'bubble-img');
        var cached = imageCache.ratio(src);
        if (cached) cell.style.aspectRatio = cached;
        if (imageCache.failed(src)) { cell.appendChild(imageFailBox(src)); box.appendChild(cell); return; }
        // 气泡是首屏内容：前几张 eager，其余 lazy（mountImage 里按可见性判断）
        var made = imageNodeFor(raw, index, function (loaded) {
          var w = loaded.naturalWidth, h = loaded.naturalHeight;
          if (w > 0 && h > 0) cell.style.aspectRatio = w + ' / ' + h;
        }, '图片 ' + (index + 1) + '：' + String(src).replace(/^.*[\\/]/, ''));
        var img = made.img;
        img.setAttribute('data-img-index', String(index));
        img.addEventListener('click', function () {
          if (img.parentNode && img.parentNode.getAttribute('data-img-failed') === '1') return;   // 失败态点击 = 复制路径
          /* 列表用**整段对话的图片**（不是这一条气泡自己的）：一条消息只有一张图时也能左右翻，
             箭头才不会两边全灰（用户报的"最近生成的图片查看器没有箭头"）。 */
          PixikoM.openViewer(chatImageList(), chatImageIndex(raw));
        });
        cell.appendChild(img);
        box.appendChild(cell);
      });
      node.appendChild(box);
    }
    return node;
  }

  function chatScrollToEnd(smooth) {
    if (!chat.host) return;
    if (smooth) {
      try { chat.host.scrollTo({ top: chat.host.scrollHeight, behavior: 'smooth' }); } catch (error) { chat.host.scrollTop = chat.host.scrollHeight; }
    } else {
      chat.host.scrollTop = chat.host.scrollHeight;
    }
  }

  /**
   * "是否跟随到底"的状态位。
   *
   * <p>规则（用户明确要求）：**只有用户本来就在底部附近时才继续跟随；用户已经上滑离开底部就绝不抢滚动。**
   * 关键是这个判断必须在"新内容插进 DOM 之前"算好 —— 内容一进 DOM，高度就变了，那时再算必然失真。
   *   · 用户 scroll 到离底 > {@link CHAT_FOLLOW_PX} → follow = false；
   *   · 用户重新滑回贴底（≤ 阈值）→ follow = true；
   *   · 进入/切回对话屏、自己发消息 → 重置为 true（"每次进去都贴底"靠它）。
   */
  var CHAT_FOLLOW_PX = 64;
  /** 一次几何读数的"离底距离"；元素不在时给 0（当贴底处理）。 */
  function chatGapToBottom() {
    if (!chat.host) return 0;
    return chat.host.scrollHeight - chat.host.scrollTop - chat.host.clientHeight;
  }
  function chatNearBottom() { return chatGapToBottom() <= CHAT_FOLLOW_PX; }
  function setChatFollow(value) { chat.follow = !!value; return chat.follow; }
  /** 调试/自动化用：读出当前的跟随标志与离底距离。 */
  PixikoM.chatFollowState = function () {
    return { follow: chat.follow !== false, gap: chatGapToBottom(), threshold: CHAT_FOLLOW_PX };
  };

  /** 贴底（无动画）。列表有滚动时才动；外层 #m-main 也一起收敛（它现在一般不滚）。 */
  function scrollChatToBottom(options) {
    var smooth = !!(options && options.smooth);
    var targets = chatScrollTargets();
    if (!targets.length) { chatScrollToEnd(smooth); return 0; }
    for (var i = 0; i < targets.length; i++) {
      var node = targets[i];
      if (smooth) {
        try { node.scrollTo({ top: node.scrollHeight, behavior: 'smooth' }); } catch (error) { node.scrollTop = node.scrollHeight; }
      } else {
        node.scrollTop = node.scrollHeight;
      }
    }
    return targets.length;
  }
  PixikoM.scrollChatToBottom = scrollChatToBottom;    // 给对话相关的屏幕复用

  /**
   * 跟随新内容（新消息、图片解码完、回执陆续到达、回执卡片变化…）。
   * **只在 follow 为真时才贴底**；follow 为假就一动不动。
   */
  function chatFollow() {
    if (!chat.host) return;
    if (chat.follow === false) return;
    scrollChatToBottom({ smooth: false });
    chat.follow = chatNearBottom();
  }

  /**
   * 每样滚动的"真实容器"到底是谁 —— 现在的布局里就是 #m-main 里的 .chat-log，
   * 但外层 #m-main 也可能被别的东西顶出滚动条，所以两处一起探测、一起收敛。
   */
  function chatScrollTargets() {
    var list = [];
    if (chat.host) list.push(chat.host);
    var main = $('m-main');
    if (main) list.push(main);
    return list.filter(function (node) {
      return node && node.scrollHeight > node.clientHeight + 1;
    });
  }

  /**
   * 滚到最底（进入对话屏、发消息、跟随新内容都用它）。
   * `{smooth:false}` = 直接设 scrollTop，不带动画 —— 首次进入时不希望"看见滑动一下"。
   */
  function legacyScrollChatToBottomRemoved() { return 0; }
  void legacyScrollChatToBottomRemoved;

  /**
   * 把 `chat.entries` 同步进 DOM —— **增量**：只 append 还没画过的条目，内容变了的原地换掉。
   *
   * <p>为什么要增量：回执跟随时每轮 `chatRenderAll()` 都会 `clear(chat.host)` 整屏重建，
   * 已经加载好的 `<img>` 被反复销毁重建 —— 浏览器要重新建连接、重新解码，"每次加载都有延迟"
   * 就是它（`loading=lazy` 的图还会被重新判定成屏外）。现在已画过的气泡原样不动。
   *
   * <p>为什么还要认"内容变了"：回执的文字与图片是**分几次**到达的（先来文字、几秒到几十秒后
   * 才来图片，见 followCapture）。只 append 的话，先画好的那条气泡**永远不会再更新** ——
   * 服务端把图发出来了、`chat.entries` 里也有了、存档也写进去了，用户眼前那一条却还是没图
   * （用户报的「下命令之后不回图片」在对话屏里就是它）。所以给每个气泡记一份内容签名
   * （{@link chatEntrySig}），对不上的那一格原地换成新节点（只换这一格，别的图不动、不重新解码）。
   *
   * <p>认"画过没画过"用 `entry.seq`（`chatEntry`/`chatNormalize` 都发单调递增号），
   * 记在气泡的 `data-seq` 上；数量对不上（比如被别的分支清过）就退回整屏重建一次。
   */
  function chatSyncEntries() {
    if (!chat.host) return;
    if (!chat.entries.length) {
      clear(chat.host);
      chat.host.appendChild(emptyState('还没有对话。下面输入一句话就能开始（这一屏的 scope 是「' + PixikoM.scope() + '」）。'));
      return;
    }
    var placeholder = chat.host.querySelector('[data-state="empty"]');
    if (placeholder) clear(chat.host);
    var painted = chat.host.querySelectorAll('[data-seq]');
    var first = painted.length ? Number(painted[0].getAttribute('data-seq')) : 0;
    // 第一条对不上说明 DOM 与 entries 不同步（清空过/整屏换过）→ 重建一次，之后都走增量
    if (!painted.length || first !== chat.entries[0].seq || painted.length > chat.entries.length) {
      clear(chat.host);
      chat.entries.forEach(function (entry) {
        var node = chatBubble(entry);
        node.setAttribute('data-seq', String(entry.seq));
        node.setAttribute('data-sig', chatEntrySig(entry));
        chat.host.appendChild(node);
      });
    } else {
      // ① 已经画过、但内容变了的（回执跟随时"后到的图片"就落在这里）：只换这一格
      for (var k = 0; k < painted.length; k++) {
        var known = chat.entries[k];
        if (!known) break;
        var want = chatEntrySig(known);
        if (painted[k].getAttribute('data-sig') === want) continue;
        var redraw = chatBubble(known);
        redraw.setAttribute('data-seq', String(known.seq));
        redraw.setAttribute('data-sig', want);
        if (painted[k].parentNode) painted[k].parentNode.replaceChild(redraw, painted[k]);
      }
      // ② 还没画过的尾巴：照旧 append
      for (var i = painted.length; i < chat.entries.length; i++) {
        var fresh = chatBubble(chat.entries[i]);
        fresh.setAttribute('data-seq', String(chat.entries[i].seq));
        fresh.setAttribute('data-sig', chatEntrySig(chat.entries[i]));
        chat.host.appendChild(fresh);
      }
    }
    chatWatchMedia();
  }

  function chatRenderAll() {
    if (!chat.host) return;
    chatSyncEntries();
  }

  /** 一条条目的签名（角色+正文+图片），用来对齐"服务端有哪些、本地已经有哪条"。 */
  function chatEntrySig(entry) {
    return entry.role + '\u0001' + entry.text + '\u0001' + (entry.images || []).join('\u0002');
  }

  /**
   * 轻轮询服务端存档，**只追加**本地还没有的尾巴。
   *
   * <p>为什么需要：出图完成时那条带图的条目不一定是"这台手机自己发的"——控制台 / QQ / 别台设备出的图
   * 也会写进同一条正文存档里；而对话屏以前只在**进屏那一次**（和手动刷新）读存档，用户已经停在对话屏时
   * 那张图永远不出现，只能去回执看（用户报的正是这个）。
   *
   * <p>为什么是"合并"而不是整屏重读：重读会把用户正在输入/刚发出去、还没防抖存回去的那条冲掉。
   * 这里按 {@link chatEntrySig} 对齐 —— 本地已有的（含还没存上去的）一条都不动，只补服务端多出来的。
   * 新增的条目走正常的 `chatRenderAll()` 增量路径，图仍是缩略图 `&w=320` 且命中 {@link imageCache}。
   */
  function chatPollMerge() {
    if (chat.loading || chat.sending) return Promise.resolve(null);
    return PixikoM.api('/api/chat/log', { body: { scope: PixikoM.scope() } }).then(function (data) {
      var fresh = chatNormalize(data && data.entries);
      if (!fresh.length) return 0;
      var have = Object.create(null);
      var byText = Object.create(null);
      chat.entries.forEach(function (entry) {
        var key = chatEntrySig(entry);
        have[key] = (have[key] || 0) + 1;
        var textKey = entry.role + '\u0001' + entry.text;
        (byText[textKey] || (byText[textKey] = [])).push(entry);
      });
      var added = 0, changed = 0;
      fresh.forEach(function (entry) {
        var key = chatEntrySig(entry);
        if (have[key]) { have[key]--; return; }
        /* 服务端这一条本地没有完全一样的：先看是不是"同一条的完整版"——
           本地那条先落了文字（还没图）就被防抖存了上去，图片到达后服务端那份已经是带图的。
           以前这种情况会被当成新条目 **再 append 一条**，于是同一段回执在对话里出现两遍
           （一份没图、一份有图）—— 用户报的「回执重复」。这里改成"并进本地那一条"。 */
        var same = byText[entry.role + '\u0001' + entry.text];
        var target = same && same.length ? same.shift() : null;
        if (target) {
          var mine = target.images || [];
          var missing = (entry.images || []).filter(function (src) { return mine.indexOf(src) < 0; });
          if (missing.length) {
            target.images = mine.concat(missing);
            have[key] = (have[key] || 0) + 1;      // 记上，免得同一份再并一次
            changed++;
            return;
          }
        }
        chat.entries.push(entry);
        added++;
      });
      if (!added && !changed) return 0;
      if (chat.entries.length > CHAT_ENTRIES_CAP) chat.entries = chat.entries.slice(-CHAT_ENTRIES_CAP);
      var placeholder = chat.host && chat.host.querySelector('[data-state="empty"]');
      if (placeholder) clear(chat.host);
      chatRenderAll();
      chatFollow();                 // 只有用户本来就在底部附近才跟着走（不抢正在上翻的视口）
      if (changed && !added) saveChatLogDebounced();   // 本地内容变了：把完整版存回去，别再产生分叉
      return added || changed;
    }).catch(function () { return null; });
  }

  function chatAppend(entry, scroll) {
    chat.entries.push(entry);
    if (chat.entries.length > CHAT_ENTRIES_CAP) chat.entries = chat.entries.slice(-CHAT_ENTRIES_CAP);
    // 空态占位要先撤掉
    var placeholder = chat.host && chat.host.querySelector('[data-state="empty"]');
    if (placeholder) clear(chat.host);
    // 关键：**在内容插入 DOM 之前**取一次"要不要跟随"的快照。
    // 内容一进 DOM 高度就变了，那时再判断一定失真。
    // 用户自己发的消息也一样要尊重 follow：他已经上滑看历史时，连自己的新气泡也不该把视口拽回底部。
    var shouldFollow = chat.follow !== false;
    if (chat.host) chat.host.appendChild(chatBubble(entry));
    chatWatchMedia();
    if (scroll !== false && shouldFollow) scrollChatToBottom({ smooth: false });
    chat.follow = chatNearBottom();
    saveChatLogDebounced();
  }

  /**
   * 进入对话屏：**立刻、无动画**贴到最底。
   * 内层列表的高度会随后续帧（图片解码、字体、样式）再变，所以在几帧之后补一次；
   * 但只有在用户本来就在底部附近时才补，避免抢走正在上翻的视口。
   */
  function chatEnterBottom() {
    chat.lastEnteredAt = Date.now();
    chat.follow = true;                     // 每次进入/切回对话屏都恢复跟随（用户要求"每次进去都贴底"）
    scrollChatToBottom({ smooth: false });
    var delays = [0, 50, 160, 400];
    delays.forEach(function (ms) {
      setTimeout(function () {
        if (currentId !== 'chat') return;
        // 刚进入对话屏的 1 秒内一律贴底（列表高度还在随后续帧变），之后交给 follow 标志
        if (Date.now() - (chat.lastEnteredAt || 0) < 1000 || chat.follow !== false) scrollChatToBottom({ smooth: false });
      }, ms);
    });
  }

  /**
   * 图片解码完会让列表变高：只要用户本来在底部附近就跟到底。
   * 只挂一次（img.complete 的补一次），避免每次渲染都叠监听器。
   */
  function chatWatchMedia() {
    if (!chat.host) return;
    var images = chat.host.querySelectorAll('img');
    for (var i = 0; i < images.length; i++) {
      (function (img) {
        if (img.dataset.pixikoFollow) return;
        img.dataset.pixikoFollow = '1';
        if (img.complete) { setTimeout(chatFollow, 0); return; }
        img.addEventListener('load', function () { setTimeout(chatFollow, 0); }, { once: true });
        img.addEventListener('error', function () { setTimeout(chatFollow, 0); }, { once: true });
      })(images[i]);
    }
  }

  /** 800ms 防抖写回服务端正文（页面隐藏时另有补一次，见 installVisibilityFlush）。 */
  var saveChatLogDebounced = debounce(function () { saveChatLog(); }, 800);
  var saveChatLogInFlight = null;
  /**
   * 整份写回服务端正文，但**推之前先与服务端那一份并一次集**。
   *
   * <p>服务端现在也是这份正文的写入者之一（产生回执消息时自己 append 一条，幂等键 = 回执号 + 序号，
   * 见 {@code ChatLogStore.append}）。手机端这一份是"上一次读到之后"的快照时，直接整份覆盖回去
   * 就会把服务端刚 append 的那条抹掉（"回执时不时少一条"就是这个形状）。
   * 并集只往本地补"服务端有、本地没有"的条目，**绝不删本地任何一条**。
   *
   * <p>服务端读不到就退回老行为：照旧推本地这一份（失败只 toast，不影响聊天）。
   */
  async function saveChatLog() {
    if (!chat.loaded) return;                       // 还没读到服务端正文就先别写，免得把别人的覆盖掉
    if (saveChatLogInFlight) return saveChatLogInFlight;   // 上一笔还没回来就跳过这一次（防抖下不会积压）
    saveChatLogInFlight = (async function () {
      var payload = chat.entries.map(function (entry) {
        var item = { role: entry.role, text: entry.text };
        if (entry.images && entry.images.length) item.images = entry.images.slice(0);
        return item;
      });
      if (!payload.length) return;                  // 空内容不推：清空走 /api/chat/reset
      var outgoing = payload;
      try {
        var remote = await PixikoM.api('/api/chat/log', { body: { scope: PixikoM.scope() } });
        // 并集用**服务端原样的条目**（不是 chatNormalize 之后的：那个会补 seq/空 images，
        // 并集比的是"角色 + 正文 + 图片"，键必须与本地这一份同口径）。
        var list = remote && Array.isArray(remote.entries) ? remote.entries : [];
        if (list.length) outgoing = unionChatEntries(list, payload);
      } catch (error) { /* 读不到就照老行为整份推本地这一份 */ }
      await PixikoM.api('/api/chat/log/save', { body: { scope: PixikoM.scope(), entries: outgoing } });
    })().catch(function (error) {
      if (error.code !== 'unauthorized') PixikoM.toast('对话正文没存上：' + error.message);
    }).then(function () { saveChatLogInFlight = null; });
    return saveChatLogInFlight;
  }

  /**
   * 两份正文的**并集**（服务端那份在前、本地独有的接在后面）：按"角色 + 正文 + 图片"对齐
   * （同一条重复出现时按出现次数配对），只补本地缺的，**不删任何一条**。
   */
  function unionChatEntries(remote, local) {
    var known = Object.create(null);
    function keyOf(entry) {
      var images = entry && entry.images && entry.images.length ? entry.images.join('\u0002') : '';
      return String((entry && entry.role) || '') + '\u0001' + String((entry && entry.text) || '') + '\u0001' + images;
    }
    for (var i = 0; i < remote.length; i++) {
      var key = keyOf(remote[i]);
      known[key] = (known[key] || 0) + 1;
    }
    var extra = [];
    for (var j = 0; j < local.length; j++) {
      var localKey = keyOf(local[j]);
      var left = known[localKey] || 0;
      if (left > 0) { known[localKey] = left - 1; continue; }   // 服务端已经有了 → 不重复
      extra.push(local[j]);                                     // 本地独有的 → 接在后面
    }
    if (!extra.length) return remote.slice(0);
    return remote.concat(extra).slice(-200);                    // 与本地快照同一条 200 条上限
  }

  async function chatLoad() {
    if (chat.loading) return;
    chat.loading = true;
    chat.loaded = false;
    if (chat.host) { clear(chat.host); chat.host.appendChild(skeleton(4, 'bubble')); }
    try {
      // 接口：POST /api/chat/log {scope} → {entries:[{role,text,images}], count}
      var data = await PixikoM.api('/api/chat/log', { body: { scope: PixikoM.scope() } });
      chat.entries = chatNormalize(data && data.entries);
      chat.loaded = true;
      chatRenderAll();
      chatEnterBottom();                       // 读完正文就贴底（首次进入不带动画）
    } catch (error) {
      PixikoM.state.lastError = { code: error.code || 'unknown', message: error.message };
      if (chat.host) clear(chat.host).appendChild(errorState(error, function () { chatLoad(); }));
    } finally {
      chat.loading = false;
    }
  }

  /** 发送一条消息 → 追加 → 若有指令回执就跟着轮询 /api/capture。 */
  async function chatSend() {
    if (chat.sending || !chat.input) return;
    var message = chat.input.value.trim();
    if (!message) { PixikoM.toast('先写点什么再发。'); return; }
    chat.input.value = '';
    chatInputAutoGrow();
    chatAppend(chatEntry('user', message), true);
    chat.sending = true;
    if (chat.sendBtn) chat.sendBtn.disabled = true;
    var typing = el('div', 'typing');
    typing.innerHTML = '<i></i><i></i><i></i><span>正在想…</span>';
    if (chat.host) chat.host.appendChild(typing);
    chatScrollToEnd(false);             // 自己发消息：无条件贴底（设计意图，见 chatSend 的注释）
    // 而且要把 follow 一起置回 true —— 这正是上面 chatFollow 注释里写的"自己发消息 → 重置为 true"。
    // 不置的话：用户上滑看历史（follow=false）时发消息，chatScrollToEnd 只把视口挪到底、follow 仍是 false，
    // 于是紧接着到达的**回复不会被跟随**，视口停在半空（差一个气泡的高度）。
    // 原来这里只靠 scroll 事件异步把 follow 改回来，网速快/接口被本地伪造时事件还没派发、回复就已经插进 DOM 了 —— 那是个竞态。
    // 之后用户再上滑，scroll 监听照样会把 follow 打回 false（C2 那条断言依赖它）。
    chat.follow = true;
    try {
      // 接口：POST /api/chat {message, execute, history?, scope} → {reply, commands[], captureId?, quest?, interest?}
      var data = await PixikoM.api('/api/chat', {
        body: { message: message, execute: !!chat.execute, scope: PixikoM.scope() },
        timeout: LONG_REQUEST_TIMEOUT
      });
      if (typing.parentNode) typing.parentNode.removeChild(typing);
      var reply = data && data.reply ? String(data.reply) : '';
      if (reply) chatAppend(chatEntry('bot', reply), true);
      var commands = (data && data.commands) || [];
      if (commands.length) chatAppend(chatEntry('sys', '已执行：' + commands.join('、')), true);
      else chatFollow();
      if (data && data.captureId) followCapture(String(data.captureId), data.quest);
    } catch (error) {
      if (typing.parentNode) typing.parentNode.removeChild(typing);
      chatAppend(chatEntry('sys', '（发送失败）' + error.message), true);
    } finally {
      chat.sending = false;
      if (chat.sendBtn) chat.sendBtn.disabled = false;
    }
  }

  /**
   * 跟着一条回执轮询 POST /api/capture {id}，把陆续到达的文本与图片追加成**一条图文条目**（去重）。
   * 结束条件：closed === true，或「done 且静默两轮**且生成队列也空了**」。
   *
   * <p><b>为什么不能只看 done</b>：`/gen` 这类指令把出图丢进队列就返回，`done` 在**受理后几十毫秒**
   * 就变成 true（实测：受理 0.0s、done 0.2s、图进正文 19.8s）。老写法只看 `done && 静默两轮`
   * 于是 3.7 秒就收工，图 16 秒后才回来 —— **图发到了服务端，页面却早就不接了**
   * （用户报的「下命令之后有概率不回图片」；在对话屏里这几乎是必然，不是概率）。
   * 现在收工前再问一次只读的 {@link /api/progress}：SD 还在生成、或生成队列里还有任务就继续跟；
   * 队列空了再多等两轮（覆盖"图已投递、正要落进回执"的那几十毫秒），并且整体有时长兜底，绝不无限跟。
   */
  function followCapture(id, quest) {
    if (chat.captureStop) chat.captureStop();
    var seenImages = Object.create(null);
    var idleRounds = 0;
    var queueRounds = 0;                      // 「回执已 done，但生成队列还没空」时多等的轮数
    /* 这条回执**每一条消息各自的条目**：消息下标 → 气泡。
       一条回执本来就有多条不同正文（汇总 / 每步 / 结算 / 领取），它们各自成泡才对；
       只有"同一条消息的重复版本"（先无图后有图）才并进同一个气泡 —— 判据见下面的索引对账。 */
    var parts = [];
    var startedAt = Date.now();
    /* 跟单现场落盘：页面被重建（挂起期间渲染进程被系统回收 / 下拉刷新 / 进程被收掉）之后，
       启动时靠它把这条回执补回来并接着跟（见 catchUpReceipts）。收工时清掉（见下面的 settled 分支）。 */
    if (id) rememberFollow({ scope: PixikoM.scope(), id: String(id), quest: Number(quest) || 0, at: Date.now() });
    chat.captureStop = PixikoM.pollWhileVisible(async function () {
      var data;
      try { data = await PixikoM.api('/api/capture', { body: { id: id, scope: PixikoM.scope() } }); }
      catch (error) {
        if (chat.captureStop) chat.captureStop();
        chat.captureStop = null;
        return;
      }
      /* 去重只按**图片路径**：文字一律按索引认（同一条回执里两段一模一样的正文也是两条消息）。 */
      var texts = (data && data.texts) || [];
      var allImages = (data && data.images) || [];
      var images = [];
      for (var j = 0; j < allImages.length; j++) {
        var file = allImages[j] && allImages[j].file ? String(allImages[j].file) : '';
        if (!file || seenImages[file]) continue;
        seenImages[file] = true; images.push(file);
      }

      /* `/api/capture` 每轮给的是**到此刻为止的整份正文**（一条一个元素），索引就是消息身份。
         按索引认条目：同一个索引 = 同一条消息（可能只是"多了图"）→ 并进它那个气泡；
         索引是新的 = 另一条消息 → **新开一个气泡**。绝不按正文前缀猜。 */
      var touched = [];            // 这一轮动过的条目（按消息顺序）
      var added = false;           // 这一轮真的有新东西（新消息 / 新图）：没有就别反复存账本
      for (var t = 0; t < texts.length; t++) {
        var part = String(texts[t] || '');
        if (!part) continue;
        if (!parts[t]) {
          var entry = chatEntry('bot', part, []);
          if (quest) entry.quest = quest;
          parts[t] = entry;
          chat.entries.push(entry);
          added = true;
        }
        touched.push(parts[t]);
      }
      if (touched.length || images.length) {
        idleRounds = 0;
        if (touched.length) {
          var placeholder2 = chat.host && chat.host.querySelector('[data-state="empty"]');
          if (placeholder2) clear(chat.host);
        }
        if (images.length) {
          /* 图归到"这条消息"自己的气泡上：这一轮刚到的最后一条消息（先无图后有图 → 并进同一个），
             没有新文字时就沿用最后一条消息的气泡。回执本来没有正文、只有图（纯图回执）才新开一个空泡。 */
          var picture = touched.length ? touched[touched.length - 1] : (parts.length ? parts[parts.length - 1] : null);
          if (!picture) { picture = chatEntry('bot', '', []); chat.entries.push(picture); }
          picture.images = (picture.images || []).concat(images);
          added = true;
        }
        chatRenderAll();            // 新增条目与"后到的图"都走 chatSyncEntries 的增量路径
        chatFollow();               // 回执里的文字/图片是异步到达的：只在用户本来就在底部附近才跟随
        if (added) saveChatLogDebounced();
      } else {
        idleRounds++;
      }

      var settled = !!(data && data.closed);
      if (!settled && data && data.done && idleRounds >= 2) {
        var live = null;
        try { live = await PixikoM.api('/api/progress', { body: { scope: PixikoM.scope() } }); }
        catch (error) { live = null; }
        var pending = !!(live && (live.running || Number(live.queue) > 0));
        if (pending) queueRounds = 0; else queueRounds++;
        if (queueRounds >= 2) settled = true;
      }
      if (!settled && Date.now() - startedAt > CAPTURE_FOLLOW_MAX_MS) {
        settled = true;               // 兜底：异常残留的回执不会让这个轮询永远跑下去
      }
      if (settled) {
        if (chat.captureStop) chat.captureStop();
        chat.captureStop = null;
        forgetFollow();               // 跟单收工：下次启动不必再补它
        if (quest) PixikoM.refreshStatus().catch(function () { /* 角标刷不到不影响对话 */ });
      }
    }, CAPTURE_POLL_MS);
  }

  /* ── 6b. 回执补拉：页面被重建 / 从挂起回到前台后，把「我这台设备」还没领到的回执补齐 ────────
   *
   * 用户报的「手机端挂起（在浏览其他应用）也收不到图」有两半，分开治：
   *   ① **页面还活着、只是被冻住**：系统在后台会整体冻结 WebView 的定时器与网络（省电行为，
   *      网页里对抗不了）。能做的是"回到前台立刻补一拍" —— 由 PixikoM.onWake（visibilitychange /
   *      focus / pageshow / resume / 用户碰一下）＋ pollWhileVisible 的看门狗保证。
   *   ② **页面被重建**：安卓在后台回收渲染进程、或用户下拉刷新、或进程被系统收掉再打开，
   *      页面整页重来 —— 内存里的跟单现场（`chat.captureStop` 那个闭包）一起没了，
   *      服务端早就投递好的图**再也没人接**；而对话正文是客户端存上去的，缺的那几张永远缺着。
   *      这一半由这里治：跟单现场落盘（{@link #FOLLOW_KEY}）＋ 启动/回前台按 scope 补拉。
   */

  /** 读回跟单现场（原样，不做新旧判断）—— 只是给 {@link rememberFollow} 沿用"最早那次的时间"。 */
  function readFollowRaw() {
    var raw;
    try { raw = localStorage.getItem(FOLLOW_KEY); } catch (error) { return null; }
    if (!raw) return null;
    try {
      var record = JSON.parse(raw);
      return record && typeof record === 'object' ? record : null;
    } catch (error) { return null; }
  }

  /** 记下「正在跟哪条回执」（页面重建后靠它续上）。 */
  function rememberFollow(record) {
    if (!record || !record.id) return;
    var previous = readFollowRaw();
    /* 同一条回执继续跟：沿用**最早那次**的时间。否则每次重载都把窗口续命，
       一条异常残留的回执能被无限跟下去（followCapture 的 15 分钟兜底就形同虚设）。 */
    if (previous && String(previous.id) === String(record.id)
      && Number(previous.quest) === Number(record.quest) && Number(previous.at) > 0) {
      record.at = Number(previous.at);
    }
    try { localStorage.setItem(FOLLOW_KEY, JSON.stringify(record)); } catch (error) { /* 隐私模式：只在本次会话里有效 */ }
  }

  /** 这条回执收工了：清掉跟单现场。 */
  function forgetFollow() {
    try { localStorage.removeItem(FOLLOW_KEY); } catch (error) { /* ignore */ }
  }

  /**
   * 读回跟单现场，**同一台设备、且不太老**才算数：
   * scope 不同（外壳把这一页换给了另一台设备）或超过 {@link CAPTURE_FOLLOW_MAX_MS} 的一律丢掉。
   */
  function readFollow(scope) {
    var record = readFollowRaw();
    if (!record) return null;
    if (String(record.scope === null || record.scope === undefined ? '' : record.scope) !== String(scope || '')) return null;
    var at = Number(record.at) || 0;
    if (!at || Date.now() - at > CAPTURE_FOLLOW_MAX_MS) return null;
    return record;
  }

  /**
   * 「回执号 + 序号 → 正文里哪个气泡」的对齐表（**本页内存**，换页重建就没了，没有持久化的必要）。
   *
   * <p>为什么有它：服务端现在也是对话正文的写入者之一（产生回执消息时顺手 append，
   * 幂等键就是"回执号 + 条内序号"）。有了这张表，补拉回执时第 N 段正文能**直接命中**同一条，
   * 不必再靠"正文互为前缀"去猜 —— 猜错就是用户报过的「多条消息并成一个气泡」。
   * 键用 {@code quest|序号}；同一段正文再来一次（先文字后图）也命中同一个气泡，所以不会长出新泡。
   */
  var receiptAlign = Object.create(null);

  /** 对齐表的键：回执号 + 条内序号（与服务端 append 的幂等键同一个口径）。 */
  function alignKey(payload, seq) {
    var number = Number(payload && payload.quest) || 0;
    return number > 0 ? number + '|' + seq : '';
  }

  /** 这一段正文（第 seq 段）是不是已经对齐到某个气泡了？没被本轮别的段占用才算命中。 */
  function alignmentHit(payload, seq, used) {
    var at = receiptAlign[alignKey(payload, seq)];
    if (typeof at !== 'number' || at < 0 || at >= chat.entries.length) return -1;
    var entry = chat.entries[at];
    if (!entry || entry.role !== 'bot' || used[at]) return -1;
    return at;
  }

  /** 记下/更新"这条回执的第 seq 段正文 = 正文里第 at 个条目"。 */
  function rememberAlign(payload, seq, at) {
    var key = alignKey(payload, seq);
    if (!key || typeof at !== 'number' || at < 0) return;
    receiptAlign[key] = at;
  }

  /**
   * 服务端那一趟给每一张图定的"条内序号"（0 = 还没有正文，与 {@link ChatLogStore#receipt} 同口径）：
   * 每个**带正文的**消息 +1，图片归到它**前面**那条正文上。`/api/quest` 回来的 `messages` 就是服务端
   * 分组的原样，所以这里算出来的序号与"服务端 append 时用的键"一致，图片就能落到同一个气泡上。
   *
   * <p>老后端（或老回执）没有 `messages` 时返回空数组：调用方退回原来的"图并进最后一条正文"。
   */
  function imageAlignments(payload) {
    var groups = payload && payload.messages;
    var out = [];
    if (!groups || !groups.length) return out;
    var seq = 0;
    for (var g = 0; g < groups.length; g++) {
      var segments = groups[g] || [];
      var hasText = false;
      var files = [];
      for (var s = 0; s < segments.length; s++) {
        var segment = segments[s] || {};
        if (segment.type === 'text' && String(segment.text || '').length) hasText = true;
        else if (segment.type === 'image' && segment.file && files.indexOf(String(segment.file)) < 0) files.push(String(segment.file));
      }
      if (hasText) { seq++; continue; }
      for (var f = 0; f < files.length; f++) out.push({ file: files[f], seq: seq });
    }
    return out;
  }

  /**
   * 把一条回执（`/api/quest` 的返回）并进对话正文 —— **每条消息各自成一个气泡**，且可以重复调用。
   *
   * <p>为什么不是简单地 append：页面重建后 `chatLoad()` 已经从服务端读回了正文，
   * 那条回执的文字**可能已经在里面**（挂起前存上去的、只是缺图）。再 append 一条就会变成
   * "同一条回执出现两遍（一份没图一份有图）"—— 用户报过的「回执重复」正是这个形状。
   *
   * <p><b>按消息身份对账，不按正文前缀猜</b>：`payload.texts` 的下标就是服务端的消息序号，
   * 一个下标 = 一条消息 = 一个气泡。下标是新的 → 新开一个气泡（多步回执的汇总 / 每步 / 结算 / 领取
   * 本来就该各占一个）；只有"同一条消息的重复版本"（先无图后有图、服务端这一段比本地更长）才并进
   * 同一个气泡。老写法把几条正文 `join('\n')` 成一条、又对"最后一条有正文的 bot 条目"做**互为前缀**
   * 判断，于是「多步执行完成：2/2 条…」「任务 #12 已完成…」「本次领取完成…」这种共享前缀的不同消息
   * 被并成一个气泡 —— 用户报的「多个信息合并成一个气泡」。
   *
   * <p>幂等：同一份 payload 反复调用只补缺的文字/图片，绝不重复新增（"同一批不重复"）。
   *
   * @returns {number} 真的有变化返回 1，什么都没动返回 0
   */
  function mergeReceiptIntoChat(payload) {
    if (!payload || payload.error) return 0;
    /* 消息序号 → 这一段正文（**空段保留**：索引就是身份，不能因为空就往下挤）。 */
    var parts = [];
    var rawTexts = payload.texts || [];
    for (var i = 0; i < rawTexts.length; i++) {
      parts[i] = rawTexts[i] === null || rawTexts[i] === undefined ? '' : String(rawTexts[i]);
    }
    var images = [];
    var rawImages = payload.images || [];
    for (var j = 0; j < rawImages.length; j++) {
      var file = rawImages[j] && rawImages[j].file ? String(rawImages[j].file) : '';
      if (file && images.indexOf(file) < 0) images.push(file);
    }
    if (!parts.length && !images.length) return 0;

    /* 先把"这一段正文已经在哪个气泡里"对出来。对账只看**存在的条目**，且只认**正文逐字相同**
       （本地那条可能只是缺图）。

       为什么不认"本地那条是它的前缀"（这里曾经就是 `text.indexOf(entry.text) === 0`）：
       共享前缀的不同消息太多了 ——「任务 #12 已受理」「任务 #12 已完成」「任务 #12 已完成：1/1」
       在服务端是**三条不同消息（条内序号 1/2/3）**，前缀判定却会把它们串成一个气泡。
       更要命的是它**跨历史**：补拉一条老回执时，本地那条几小时前的短正文会被当成"它长出来的"，
       于是图/新正文被并进一条与本次会话无关的老气泡里。
       对不上就新开一个气泡：没有稳定身份时宁可各占一个，也不拿前缀去猜
       （稳定的那一半在服务端落盘时用"回执号 + 条内序号"钉住，见 {@link alignmentHit}）。 */
    var used = Object.create(null);      // 这个气泡已经被本回执的哪一段认领了（含本轮新建的）
    var targets = [];
    var adopted = 0;
    /** 这个气泡是不是这一段正文的「同一条消息」：**正文逐字相同**（服务端就地更新过的版本也相同）。 */
    function sameMessage(entry, text) {
      return !!entry.text && entry.text === text;
    }
    for (var t = 0; t < parts.length; t++) {
      var text = parts[t];
      if (!text) { targets[t] = null; continue; }
      // ① 先按**回执号 + 序号**直接对齐：服务端现在是对话正文的权威写入者之一，它 append 的条目
      //    就带着这两个身份（顺序 + 内容都对得上）。命中就并进同一条，压根不进入下面的启发式。
      var at = alignmentHit(payload, t, used);
      if (at < 0) {
        // ② 兜底（老后端 / 正文里那条还没被服务端 append 过）：沿用"正文相同或本地是它前缀"的对账。
        //    只认**还没被别的段认领**的条目：同一条回执里两段一模一样的正文是两条消息，不能挤一个气泡
        for (var k = chat.entries.length - 1; k >= 0; k--) {
          var entry = chat.entries[k];
          if (!entry || entry.role !== 'bot' || used[k] || !sameMessage(entry, text)) continue;
          at = k; break;
        }
      }
      if (at < 0) {
        var made = chatEntry('bot', text, []);
        chat.entries.push(made);
        used[chat.entries.length - 1] = true;
        targets[t] = made;
        adopted++;
      } else {
        used[at] = true;
        targets[t] = chat.entries[at];
        if (text.length > (chat.entries[at].text || '').length) chat.entries[at].text = text;
      }
      // 这一条对上了就把"这条回执的第几段正文 = 正文里哪个下标"记下来，给同一条回执的后续补拉用。
      if (targets[t]) rememberAlign(payload, t, chat.entries.indexOf(targets[t]));
    }
    var changed = adopted;

    if (images.length) {
      /* 图归到"这条消息"自己的气泡。
         **首选"回执号 + 序号"对齐**：服务端 append 时就把图挂在了它前面那条正文的同一个序号上，
         所以按序号命中就与服务端落盘的形状**逐条一致**，也不会把图塞给后面那条正文
         （那正是"先文字后图被拆成两个气泡 / 图跑到下一条上"的来源）。
         命不中才退回老口径：这条回执最后一条有正文的消息；只有纯图回执才另起一个空泡。
         —— 绝不为了图去新开一个气泡（那样「先无图后有图」会多出一个空泡）。 */
      var mapped = imageAlignments(payload);
      var seqHits = Object.create(null);
      var imageTargets = Object.create(null);   // 图片路径 → 它应该挂进的那条条目
      for (var m = 0; m < mapped.length; m++) {
        var seq = mapped[m].seq;
        if (seqHits[seq] === undefined) seqHits[seq] = seq > 0 ? alignmentHit(payload, seq, used) : -1;
        var hit = seqHits[seq];
        if (hit >= 0) imageTargets[mapped[m].file] = chat.entries[hit];   // 命中：图就挂在那条正文上
      }
      var picture = null;
      for (var p = targets.length - 1; p >= 0 && !picture; p--) { if (targets[p]) picture = targets[p]; }
      if (!picture && parts.length) {
        for (var q = chat.entries.length - 1; q >= 0 && !picture; q--) {
          var tail = chat.entries[q];
          if (tail && tail.role === 'bot' && tail.text) picture = tail;
        }
      }
      if (!picture) {
        for (var r = chat.entries.length - 1; r >= 0 && !picture; r--) {
          var blank = chat.entries[r];
          if (blank && blank.role === 'bot' && !blank.text && !(blank.images || []).length) picture = blank;
        }
        if (!picture) {
          picture = chatEntry('bot', '', []);
          chat.entries.push(picture);
          changed++;
        }
      }
      for (var z = 0; z < images.length; z++) {
        var target = imageTargets[images[z]] || picture;     // 对齐命中就挂那条正文，命不中退回兜底那条
        var mine = target.images || [];
        if (mine.indexOf(images[z]) >= 0) continue;
        target.images = mine.concat([images[z]]);
        changed++;
      }
    }

    if (!changed) return 0;
    if (chat.entries.length > CHAT_ENTRIES_CAP) chat.entries = chat.entries.slice(-CHAT_ENTRIES_CAP);
    var placeholder = chat.host && chat.host.querySelector('[data-state="empty"]');
    if (placeholder) clear(chat.host);
    chatRenderAll();
    chatFollow();
    saveChatLogDebounced();
    return 1;
  }

  /**
   * 补拉链：从服务端把「正在跟的那条回执」与「这一趟页面会话**真的错过**的回执」补齐（幂等）。
   *
   * <p><b>只补该补的，绝不动用户在看的这段历史。</b>两条来源，一条都不许放宽：
   *   <ol>
   *     <li>{@link readFollow} 里那条正在跟的回执（挂起时被系统收掉的现场）—— 顺带把跟单**续上**；
   *         且仍然受 {@link CATCHUP_WINDOW_MS} 约束（跟单本身有 {@code CAPTURE_FOLLOW_MAX_MS} 兜底）；</li>
   *     <li>**本条页面会话开始之后才发生**的回执 —— 覆盖挂起/被回收期间新到的那几条。
   *         判据是"开始时间晚于这次页面加载"，见 {@link catchUpCutoff}。</li>
   *   </ol>
   *
   * <p><b>为什么不能靠"未读 / 有图 / 比跟单新 / 6 小时内"</b>（这里曾经就是这么写的，是
   * 「对话框莫名其妙插入历史对话」的元凶）：`unread` 只会被用户在**回执屏**手动标记才清零，
   * 手机上几乎永远是 true；`busy` 在"受理后立刻 done"的指令上也是 false；于是几小时前那几条
   * 老回执条条都满足条件，被当成"这次错过的"重新 {@link mergeReceiptIntoChat} 进来 ——
   * 用户删掉（或清掉）的老回执正文就这样**又冒出来，而且是追加到对话末尾**。
   * 现在窗口按"这次页面会话"收死：老回执一条都不补（要看在回执屏里看）。
   *
   * <p>只在对话屏挂好之后跑：正文还没读回来时补进去的条目会被 {@link chatLoad} 整屏重读冲掉。
   *
   * @param {boolean} [force] 回到前台 / 页面刚重建时为 true：立刻补，不受最小间隔限制
   */
  var catchUpAt = 0;
  var catchUpInFlight = null;
  /**
   * 「这条回执算不算这一次页面会话里发生的」的时间闸门（见 {@link catchUpReceipts}）。
   *
   * <p>取"这次页面加载那一刻"，再往前留 {@link CATCHUP_CLOCK_SLACK_MS} 的余量（只为了让
   * 页面加载与回执受理之间正常的先后抖动不至于把**刚发生**的那条挡在外面）。老回执动辄
   * 几小时前，余量多大都不影响判据；真正错过的那条一定是"页面加载之后才受理"的
   * （受理在先、页面加载在后 = 它早就在正文存档里了）。
   *
   * <p>重算时机见 {@link catchUpCutoff}：每次"整页重建/首次进对话屏"都会重算，
   * 所以下拉刷新之后仍然只补刷新之后发生的那几条。
   */
  var CATCHUP_CLOCK_SLACK_MS = 10000;
  var catchUpCutoffAt = 0;

  /** 本条页面会话的补拉时间闸门（毫秒墙钟）；没定过就现在定，页面重建后会重新定。 */
  function catchUpCutoff() {
    if (!catchUpCutoffAt) catchUpCutoffAt = Date.now() - CATCHUP_CLOCK_SLACK_MS;
    return catchUpCutoffAt;
  }

  /** 服务端那条回执的受理时刻（毫秒墙钟）；解析不出来给 0（= 认不出新旧，按"老"处理）。 */
  function questStartedAt(row) {
    var raw = row && (row.startedAt === null || row.startedAt === undefined ? '' : String(row.startedAt).trim());
    if (!raw) return 0;
    var at = Date.parse(raw);
    return isNaN(at) ? 0 : at;
  }

  function catchUpReceipts(force) {
    if (catchUpInFlight) return catchUpInFlight;
    if (!chat.loaded || currentId !== 'chat') return Promise.resolve(0);
    var now = Date.now();
    if (!force && now - catchUpAt < CATCHUP_MIN_GAP_MS) return Promise.resolve(0);
    catchUpAt = now;

    var scope = PixikoM.scope();
    var cutoff = catchUpCutoff();
    var follow = readFollow(scope);
    /* 跟单现场太老（超过补拉窗口）就不认它 —— 与下面的时间闸门同一个口径：补拉只服务"眼下的这一趟"。 */
    if (follow && follow.at && Date.now() - Number(follow.at) > CATCHUP_WINDOW_MS) follow = null;
    var followQuest = follow ? Number(follow.quest) || 0 : 0;
    var wanted = followQuest > 0 ? [followQuest] : [];
    var followed = null;
    var merged = 0;

    catchUpInFlight = PixikoM.api('/api/quests', { body: { limit: 8, scope: scope } }).then(function (data) {
      var rows = (data && data.quests) || [];
      var extra = 0;
      for (var i = 0; i < rows.length && extra < CATCHUP_MAX_QUESTS; i++) {
        var row = rows[i] || {};
        var number = Number(row.number) || 0;
        if (number <= 0 || wanted.indexOf(number) >= 0) continue;
        /* 闸门①：**这次页面会话之后**才受理的才算"我错过的"。老回执一条都不补 ——
           它就是靠这一条被挡在门外的（不看 unread / busy / 有没有图，那些都会漏）。 */
        if (questStartedAt(row) < cutoff) continue;
        /* 闸门②：还在跑 / 有新正文 / 有新图 —— 三种都算"有新东西"，避免把一条空回执补进来。
           注意这里不再用"比跟单那条新"当宽松条件：那对新会话是恒真的（followQuest=0 时谁都比它新）。 */
        if (!(row.unread === true || row.busy === true || Number(row.texts) > 0 || Number(row.images) > 0)) continue;
        wanted.push(number);
        extra++;
      }
      // 一条一条来：并发会让"并进哪一条条目"的前缀判断互相打架
      return wanted.reduce(function (chain, number) {
        return chain.then(function () {
          return PixikoM.api('/api/quest', { body: { id: number, scope: scope } }).then(function (payload) {
            if (followQuest > 0 && number === followQuest) followed = payload;
            merged += mergeReceiptIntoChat(payload);
          }).catch(function () { /* 单条失败不影响别的（回执可能刚被淘汰/不属于这个会话） */ });
        });
      }, Promise.resolve());
    }).catch(function () { /* 列表拿不到：这一轮不补，下一轮或下次唤醒再来 */
    }).then(function () {
      catchUpInFlight = null;
      /* 跟单续上：这条回执**还在跑**、当前又没人在跟，就接着跟它的 /api/capture
         （页面重建前干到哪就接着干；收工条件与正常路径完全一样，见 followCapture）。

         为什么必须判"还在跑"：命令跑完但出图队列还没空时也要跟（done 且 busy，图片还在路上）；
         真跑完的（done 且不忙）或磁盘读回来的（closed，服务端明确说"收集器等同于已关闭"）
         一律不跟 —— 那种回执的图已经由上面的 /api/quest 一次性取全了，再跟只是白打请求。 */
      var stillRunning = !!followed && !followed.error && followed.closed !== true
        && (followed.done !== true || followed.busy === true);
      if (follow && follow.id && stillRunning && !chat.captureStop) {
        followCapture(String(follow.id), followQuest > 0 ? followQuest : undefined);
      }
      return merged;
    });
    return catchUpInFlight;
  }

  /** 调试/自动化用：手动触发一次补拉（`force` 默认为 true）。 */
  PixikoM.catchUpReceipts = function (force) { return catchUpReceipts(force !== false); };

  /** 调试/自动化用：看一眼本次页面会话的补拉闸门（= 只补这个时刻之后受理的回执）。 */
  PixikoM.catchUpCutoff = function () { return catchUpCutoff(); };

  /** 清空对话：POST /api/chat/reset {scope} 之后再清本地。 */
  async function clearChat() {
    var ok = await PixikoM.confirm({ title: '清空对话？', text: '服务端与本地这份正文都会被清掉，之后无法恢复。', ok: '清空', danger: true });
    if (!ok) return;
    try {
      await PixikoM.api('/api/chat/reset', { body: { scope: PixikoM.scope() } });
    } catch (error) {
      PixikoM.toast('清空失败：' + error.message);
      return;
    }
    chat.entries = [];
    chat.loaded = true;
    chatRenderAll();
    chatEnterBottom();
    PixikoM.toast('对话已清空');
  }

  /** 输入框自动长高：1–6 行，最大 40vh；长高后列表可视区变矮，视口按"变矮了多少"补一次。 */
  function chatInputAutoGrow() {
    var input = chat.input;
    if (!input) return;
    var heightBefore = chat.host ? chat.host.clientHeight : 0;
    var shouldFollow = chatNearBottom();
    input.style.height = 'auto';
    var max = Math.round(window.innerHeight * 0.4);
    input.style.maxHeight = max + 'px';
    input.style.height = Math.min(input.scrollHeight, max) + 'px';
    // 输入条长高会**缩小**列表可视区（bottom 不变、clientHeight 变小）。
    // 此时不能"贴底"（那会把用户正在看的位置顶走），只能把视口按变矮的高度补回去；
    // 而且只有用户本来就在底部附近才补，否则原样不动。
    if (shouldFollow && chat.host && heightBefore) {
      var shrink = heightBefore - chat.host.clientHeight;
      if (shrink > 0) chat.host.scrollTop += shrink;
    }
  }

  function chatMount(root) {
    root.classList.add('chat');
    /* 整页重建（挂起被回收 / 下拉刷新）之后重新起算补拉闸门：这次页面会话之前受理的回执一律不补。
       放在 mount 里而不是 boot 里 —— mount 才是"用户的对话屏从这一刻起"的那个时刻。 */
    catchUpCutoffAt = 0;
    var log = el('div', 'chat-log');
    log.setAttribute('data-chat-log', '1');
    root.appendChild(log);
    chat.host = log;
    // 用户自己滚到离底 > 阈值 → 停止跟随；滑回贴底 → 恢复跟随。
    // 这个标志位就是"新消息到底要不要把我拽到底"的唯一依据（见 chatFollow 的注释）。
    log.addEventListener('scroll', function () {
      chat.lastScrollAt = Date.now();
      chat.follow = chatNearBottom();
    }, { passive: true });

    var composer = el('div', 'composer');
    composer.setAttribute('data-composer', '1');
    var row = el('div', 'composer-row');
    var input = el('textarea', 'chat-input');
    input.rows = 1;
    input.placeholder = '说点什么…';
    input.setAttribute('data-chat-input', '1');
    input.setAttribute('enterkeyhint', 'send');
    var send = el('button', 'send');
    send.type = 'button';
    send.setAttribute('data-chat-send', '1');
    send.setAttribute('aria-label', '发送');
    send.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 12l15-7-7 15-2-6z"/></svg>';
    row.appendChild(input);
    row.appendChild(send);
    composer.appendChild(row);

    var meta = el('div', 'composer-meta');
    var switchRow = el('div', 'switch-row');
    switchRow.style.minHeight = 'auto';
    var toggle = el('button', 'switch on');
    toggle.type = 'button';
    toggle.setAttribute('data-chat-execute', '1');
    toggle.setAttribute('aria-pressed', 'true');
    toggle.setAttribute('aria-label', '允许执行指令');
    var label = el('span', null, '允许执行指令');
    switchRow.appendChild(toggle);
    switchRow.appendChild(label);
    meta.appendChild(switchRow);
    var scopeTag = el('span', 'muted', 'scope: ' + PixikoM.scope());
    scopeTag.style.marginLeft = 'auto';
    scopeTag.setAttribute('data-chat-scope-tag', '1');
    meta.appendChild(scopeTag);
    composer.appendChild(meta);
    root.appendChild(composer);

    chat.input = input;
    chat.sendBtn = send;

    toggle.addEventListener('click', function () {
      chat.execute = !chat.execute;
      toggle.classList.toggle('on', chat.execute);
      toggle.setAttribute('aria-pressed', chat.execute ? 'true' : 'false');
      PixikoM.toast(chat.execute ? '允许执行指令' : '只聊天，不执行指令');
    });

    input.addEventListener('input', chatInputAutoGrow);
    // 聚焦（软键盘弹出）时只保证"正在看的这段不被输入条挡住"：
    // 绝对不能无条件贴底 —— 用户可能正翻到上面看历史，一点输入框就被拽到底是最烦人的那种行为。
    input.addEventListener('focus', function () {
      setTimeout(function () {
        if (!chat.host) return;
        if (!chatNearBottom()) return;
        scrollChatToBottom({ smooth: false });
      }, 240);
    });
    input.addEventListener('keydown', function (event) {
      // Enter 发送、Shift+Enter 换行（外接键盘；手机上用回车键就是换行更安全，这里只在非触屏按 Enter 才发）
      if (event.key === 'Enter' && !event.shiftKey && !('ontouchstart' in window)) {
        event.preventDefault(); chatSend();
      }
    });
    send.addEventListener('click', function () { chatSend(); });

    /* 首次读正文**立刻开始**，不等 4 秒轮询的第一拍（那一拍本身还有 min(interval,400)=400ms 的延迟）。
       页面被重建时"回来多快能看到图"全看这一下 —— 那 400ms 是白等的（用户报的正是"回来收不到图"，
       而系统的冻结期我们本来就无能为力，能省的每一毫秒都该省）。与下面 chat.poll 里那条链同一个口径，
       两边都有 chat.loaded / chat.loading 挡着，不会重复读。 */
    if (!chat.loaded) {
      chatLoad().then(function () { return catchUpReceipts(true); })
        .catch(function () { /* 读不回来就交给轮询下一拍 */ });
    }

    /* 对话屏的轻轮询：只补服务端多出来的尾巴（别的入口出的图也能自己冒出来），
       页面隐藏 / 不在这一屏 / 有浮层 都自动跳过；用户在输入或正在发消息时不读，避免打架。
       首次读回正文之后紧接着补拉一次回执（catchUpReceipts）—— 页面刚被重建时，
       缺的那几张图只存在于服务端的回执里，正文存档里根本没有（见 6b 那一段的说明）。
       参数 `stalled` 来自 pollWhileVisible 的看门狗：这一拍是"被系统冻过之后补的"，
       于是补拉要**绕过最小间隔**（"回到前台立刻补一拍"，不靠任何事件也不靠外壳）。 */
    chat.poll = PixikoM.pollWhileVisible(function (stalled) {
      if (currentId !== 'chat') return;
      if (!chat.loaded) return chatLoad().then(function () { return catchUpReceipts(true); });
      if (PixikoM.overlayBusy && PixikoM.overlayBusy()) return;
      return Promise.resolve(chatPollMerge()).then(function () { return catchUpReceipts(!!stalled); });
    }, 4000);
  }

  PixikoM.register('chat', {
    title: '对话',
    mount: chatMount,
    refresh: function () { return chatLoad(); },
    menu: function () {
      return [
        { text: '刷新对话', sub: '重新从服务端读正文', onSelect: function () { chatLoad(); } },
        { text: '跳到出图', sub: '去生成参数与进度', onSelect: function () { PixikoM.go('gen'); } }
      ];
    }
  });

  /* ── 7. 屏幕：gen（出图） ────────────────────────────────────────────── */

  var gen = {
    root: null,
    options: null,          // {samplers:[], models:[], modelOptions:[], error}
    params: null,           // {sampler,width,height,steps,cfg,seed,model,imageCount}
    count: 1,
    images: [],             // /api/images → [{path,name,size,modified,pending}]
    ratios: Object.create(null),   // 路径 → 已知的真实比例 '234 / 320'（新格子先用它/生成参数垫盒子）
    liveActive: false,             // 队列为空但 SD 正在生成（单图 `.gen 1` 实测就是这样）
    liveImage: '',                 // 那次"没进队列的生成"最后产出的图（收尾时接进「这一轮的图」）
    busyRendering: false,
    stops: [],
    lastGridKey: ''
  };

  /** 采样器/模型下拉的数据（SD 没在跑时是空列表，接口不会 500）。 */
  async function genLoadOptions() {
    try {
      // 接口：/api/options → {samplers[], models[], modelOptions[], functions[], error?}
      gen.options = await PixikoM.api('/api/options', { body: { scope: PixikoM.scope() } });
    } catch (error) {
      gen.options = { samplers: [], models: [], error: error.message };
    }
    return gen.options;
  }

  /**
   * 当前参数：从 /api/status 的 generation 段取。
   * 字段名照 Bot.webStatus()：sampler/width/height/model/steps/cfg/seed；图片上限是 status.imageCount。
   * 同时把 /api/presets 的 current 当补充（它给的字段一样，SD 离线时也能显示上次的值）。
   */
  async function genLoadParams() {
    var data = await PixikoM.refreshStatus();
    var g = (data && data.generation) || {};
    gen.params = {
      sampler: g.sampler || '',
      width: fmtNum(g.width, 512),
      height: fmtNum(g.height, 512),
      steps: fmtNum(g.steps, 20),
      cfg: fmtNum(g.cfg, 7),
      seed: fmtNum(g.seed, -1),
      model: g.model || '',
      imageCount: fmtNum(data && data.imageCount, 1)
    };
    gen.state = g.status || null;
    return gen.params;
  }

  /**
   * 提交一个参数改动：POST /api/generation，**只带改动的字段**（其余字段留空表示"不动它"，
   * 见 Bot.webGeneration 的 numberProvided 约定）。返回完整的 /api/status，用它刷新面板。
   */
  async function genApply(fields) {
    try {
      var data = await PixikoM.api('/api/generation', { body: Object.assign({ scope: PixikoM.scope() }, fields) });
      if (data && data.generation) {
        var g = data.generation;
        gen.params = Object.assign({}, gen.params, {
          sampler: g.sampler || gen.params.sampler,
          width: fmtNum(g.width, gen.params.width),
          height: fmtNum(g.height, gen.params.height),
          steps: fmtNum(g.steps, gen.params.steps),
          cfg: fmtNum(g.cfg, gen.params.cfg),
          seed: fmtNum(g.seed, gen.params.seed),
          model: g.model || gen.params.model
        });
      }
      if (data && data.imageCount !== undefined) {
        gen.params.imageCount = fmtNum(data.imageCount, gen.params.imageCount);
      }
      genRenderParams();
      var message = data && data.message ? String(data.message) : '已生效';
      // 消息里可能带"参数来源：…"，一句话说不完就放进 sheet（toast 太窄）
      if (message.length > 60) {
        PixikoM.sheet({ title: '生成参数已更新', items: [{ text: '知道了', onSelect: function () { } }] });
        PixikoM.toast(message.split('\n')[0]);
      } else {
        PixikoM.toast(message);
      }
      return data;
    } catch (error) {
      PixikoM.toast('改参数失败：' + error.message);
      return null;
    }
  }

  /** 生成：POST /api/command {command:'/gen <N>', scope}。 */
  async function genStart() {
    var count = Math.max(1, Math.min(99, fmtNum(gen.count, 1)));
    gen.count = count;
    genRenderParams();
    try {
      await PixikoM.api('/api/command', { body: { command: '/gen ' + count, scope: PixikoM.scope() } });
      PixikoM.toast('已经排上 ' + count + ' 张，回执会自己跟上来。');
      gen.busyRendering = true;
      genRenderProgress();
      // 立刻刷一次，让队列与进度马上有反应
      gen.pollOnce();
    } catch (error) {
      PixikoM.toast('开始生成失败：' + error.message);
    }
  }

  /** 队列操作：POST /api/tasks/action {action, number}。 */
  async function genTaskAction(action, number) {
    try {
      var data = await PixikoM.api('/api/tasks/action', { body: { action: action, number: String(number), scope: PixikoM.scope() } });
      if (data && data.message) PixikoM.toast(data.message);
      if (data && data.tasks) { PixikoM.state.tasks = data.tasks; genRenderProgress(); }
    } catch (error) {
      PixikoM.toast('队列操作失败：' + error.message);
    }
  }

  /** 图集按钮上的动作菜单（每张图一个）。 */
  function genImageMenu(item, index) {
    var src = PixikoM.imageUrl(item.path);
    var label = item.name || '';
    if (window.PixikoNative && typeof window.PixikoNative.showImageMenu === 'function') {
      try { window.PixikoNative.showImageMenu(src, label); return; } catch (error) { /* 落到兜底 */ }
    }
    PixikoM.sheet({
      title: label || '这张图片',
      items: [
        { text: '保存到相册 / 分享', sub: '真机里走原生菜单（PixikoNative）', onSelect: function () { PixikoM.toast('这台设备没有原生桥，试试「在新标签打开」再长按。'); } },
        { text: '在新标签打开', onSelect: function () { window.open(src, '_blank'); } },
        { text: '填入提示词引用', sub: '把文件名复制出来', onSelect: function () {
            var input = el('textarea'); input.value = item.path; document.body.appendChild(input); input.select();
            try { document.execCommand('copy'); PixikoM.toast('已复制路径：' + item.path); } catch (e) { PixikoM.toast(item.path); }
            document.body.removeChild(input);
          } }
      ]
    });
  }

  /** 参数卡：原生控件（数字步进 / 下拉 / 滑块），改完立刻 POST /api/generation。 */
  function genRenderParams() {
    var host = gen.root && gen.root.querySelector('[data-gen-params]');
    if (!host || !gen.params) return;
    clear(host);
    var p = gen.params;
    var options = gen.options || { samplers: [], models: [] };

    // 基础模型（下拉）
    host.appendChild(genSelect('基础模型', 'model', p.model, options.models || [], '（SD 没在跑，列不出来）'));
    // 采样方法（下拉）
    host.appendChild(genSelect('采样方法', 'sampler', p.sampler, options.samplers || [], '（SD 没在跑，列不出来）'));

    // 尺寸：两个数字步进
    var sizeWrap = el('div', 'param-grid');
    sizeWrap.appendChild(genNumber('宽', 'width', p.width, 64, 4096, 64));
    sizeWrap.appendChild(genNumber('高', 'height', p.height, 64, 4096, 64));
    host.appendChild(sizeWrap);

    // 步数 / CFG：滑块 + 数字
    var stepsField = genSlider('步数', 'steps', p.steps, 1, 150, 1);
    host.appendChild(stepsField);
    var cfgField = genSlider('CFG', 'cfg', p.cfg, 1, 30, 0.5);
    host.appendChild(cfgField);

    // 种子（数字步进，-1 = 随机）+ 一颗骰子
    var seedField = genNumber('种子', 'seed', p.seed, -1, 2147483647, 1);
    var seedRow = seedField.querySelector('.stepper');
    var dice = el('button', 'stepper-btn');
    dice.type = 'button';
    dice.setAttribute('data-gen-dice', '1');
    dice.title = '随机种子';
    dice.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="4" width="16" height="16" rx="3"/><circle cx="9" cy="9" r="1.2"/><circle cx="15" cy="15" r="1.2"/><circle cx="12" cy="12" r="1.2"/></svg>';
    dice.addEventListener('click', function () { genApply({ seed: -1 }); });
    seedRow.appendChild(dice);
    host.appendChild(seedField);

    // 图片上限（settings.imageCount，走 /api/generation 的 imageCount 字段）
    host.appendChild(genNumber('每条图片上限', 'imageCount', p.imageCount, 1, 50, 1));

    if (options.error) {
      var warn = el('p', 'hint warn-text', 'SD 未就绪：' + options.error);
      host.appendChild(warn);
    }
  }

  /**
   * 一行下拉。
   *
   * 原生 `<select>` 的**关闭态永远是单行、超长就自动截断**（这是 UA 行为，CSS 改不了），
   * 基础模型名（`waiIllustriousSDXL_v170.safetensors [f116b0c78f]`）在 390px 宽下只看得到
   * 前半截 —— 用户抱怨的就是这个。所以下拉下面再补一条**完整值行**（`.field-value`，
   * 允许换行、overflow-wrap:anywhere），保证完整字符串在页面上真的看得见。
   * 下拉本身照旧保留（`data-gen-field` 契约、原生选择器体验、改完立刻 POST /api/generation）。
   */
  function genSelect(label, key, value, values, placeholder) {
    var field = el('div', 'field');
    field.appendChild(el('div', 'field-label', label));
    var select = el('select', 'input');
    select.setAttribute('data-gen-field', key);
    // 当前值不在列表里（SD 离线 / 换了模型）也要能显示出来
    var list = values.slice(0);
    if (value && list.indexOf(value) < 0) list.unshift(value);
    if (!list.length) list.push('');
    list.forEach(function (item) {
      var option = el('option', null, item || placeholder);
      option.value = item;
      if (item === value) option.selected = true;
      select.appendChild(option);
    });
    // 完整值行：显示 select 当前值的**全文**（可换行）
    var readout = el('div', 'field-value selectable');
    readout.setAttribute('data-gen-value-for', key);
    readout.textContent = select.value || (value || '') || placeholder;
    select.addEventListener('change', function () {
      readout.textContent = select.value || placeholder;
      var fields = {}; fields[key] = select.value; genApply(fields);
    });
    field.appendChild(select);
    field.appendChild(readout);
    return field;
  }

  /** 一行数字步进（− / 输入 / ＋）。 */
  function genNumber(label, key, value, min, max, step) {
    var field = el('div', 'field');
    field.appendChild(el('div', 'field-label', label));
    var row = el('div', 'stepper');
    var minus = el('button', 'stepper-btn', '−');
    var plus = el('button', 'stepper-btn', '＋');
    minus.type = 'button'; plus.type = 'button';
    var input = el('input', 'input');
    input.type = 'number';
    input.inputMode = 'numeric';
    input.value = String(value);
    input.min = String(min); input.max = String(max); input.step = String(step);
    input.setAttribute('data-gen-field', key);
    function commit(next) {
      var n = Number(next);
      if (!isFinite(n)) return;
      n = Math.max(min, Math.min(max, n));
      input.value = String(n);
      var fields = {}; fields[key] = n; genApply(fields);
    }
    minus.addEventListener('click', function () { commit(Number(input.value) - step); });
    plus.addEventListener('click', function () { commit(Number(input.value) + step); });
    var commitDebounced = debounce(function () { commit(input.value); }, 600);
    input.addEventListener('input', commitDebounced);
    input.addEventListener('change', function () { commit(input.value); });
    row.appendChild(minus); row.appendChild(input); row.appendChild(plus);
    field.appendChild(row);
    return field;
  }

  /** 一行滑块（拖动时只在松手后提交）。 */
  function genSlider(label, key, value, min, max, step) {
    var field = el('div', 'field');
    var head = el('div', 'spread');
    head.appendChild(el('div', 'field-label', label));
    var readout = el('div', 'muted mono', String(value));
    head.appendChild(readout);
    field.appendChild(head);
    var range = el('input');
    range.type = 'range';
    range.min = String(min); range.max = String(max); range.step = String(step);
    range.value = String(value);
    range.setAttribute('data-gen-field', key);
    range.addEventListener('input', function () { readout.textContent = range.value; });
    range.addEventListener('change', function () {
      var n = Number(range.value);
      var fields = {}; fields[key] = n; genApply(fields);
    });
    field.appendChild(range);
    return field;
  }

  /** 进度卡的数：总进度 = total>0 ? (done + percent/100)/total*100 : percent（任务书给的公式）。 */
  /**
   * 总进度 = total>0 ? (done + percent/100)/total*100 : percent（任务书给的公式）。
   *
   * <p>**队列为空但 SD 正在生成**这一支是单图任务的关键：实测 `.gen 1`（单张）全程 `/api/tasks` 都是
   * `[]`（83/83 采样），SD 的 `/api/progress` 却从 0% 走到 91%；老代码在这里一律返回
   * `percent: 0, running: false`，于是单图生成**既没有进度条也没有 placeholder**（用户报的就是这个）。
   * 现在把"SD 在跑但没进队列"当成一次真实的生成（`live: true`），百分比用 SD 的实时进度。
   */
  function genOverall(tasks, imagePercent, progress) {
    var list = tasks || [];
    var percent = Math.max(0, Math.min(100, fmtNum(imagePercent, 0)));
    if (!list.length) {
      var live = !!(progress && progress.running);
      if (!live) return { percent: 0, text: '队列是空的', running: false, current: null, live: false };
      return {
        percent: percent,
        text: progress.text ? String(progress.text) : ('正在生成这一张：' + Math.round(percent) + '%'),
        running: true,
        current: null,
        tasks: list,
        live: true
      };
    }
    var task = list[0];
    var total = fmtNum(task.total, 0);
    var done = fmtNum(task.done, 0);
    var overall = total > 0 ? ((done + percent / 100) / total) * 100 : percent;
    return {
      percent: Math.max(0, Math.min(100, overall)),
      text: '第 ' + done + ' / ' + total + ' 张（当前这张 ' + Math.round(percent) + '%）',
      running: !!task.running,
      current: task,
      tasks: list,
      live: false
    };
  }

  /* ── 出图屏：跨轮询复用的 DOM（进度卡每拍重建，网格里的 <img> 绝不重建）───────────────
   *
   * 为什么必须这样：这个屏每 1.5 秒轮询一次 /api/progress + /api/tasks + /api/images，
   * 老写法每拍把整块宿主 `clear()` 掉重建 —— 刚生成的那张图**缩略图还没缓存**（服务端要现解码
   * 一张 1216×1664 再缩到 320px，往往超过一个轮询周期），于是每一拍都把它正在加载的 <img> 丢掉、
   * 下一个 <img> 从头再来。用户看到的就是"出图完成后冒出一个空占位、过一会儿才消失"。
   * 现在只有进度卡（纯文字）每拍重建；两个网格连同它们里面的 <img> 一直在，按**图片路径**增量增删。
   */
  function genProgressDom(host) {
    var dom = host.__genDom;
    if (dom && host.contains(dom.grid)) return dom;              // 建好过、还在宿主里 → 直接复用
    clear(host);
    var cardHost = el('div');
    cardHost.setAttribute('data-gen-card-host', '1');
    host.appendChild(cardHost);
    var gridCard = el('div', 'card');
    var head = el('div', 'card-head');
    head.appendChild(el('div', 'card-title', '这一轮的图'));
    var count = el('div', 'row-tail', '0 / 0');
    count.setAttribute('data-gen-grid-count', '0');
    head.appendChild(count);
    gridCard.appendChild(head);
    var grid = el('div', 'grid-imgs');
    grid.setAttribute('data-gen-grid', '1');
    gridCard.appendChild(grid);
    var empty = el('div', 'hint', '还没有图。点下面的「开始生成」。');
    empty.style.display = 'none';
    gridCard.appendChild(empty);
    host.appendChild(gridCard);
    dom = { cardHost: cardHost, grid: grid, count: count, empty: empty };
    host.__genDom = dom;
    return dom;
  }

  /** 「最近的作品」那块的持久 DOM（网格 + 空态提示；网格跨轮询保留）。 */
  function genGalleryDom(host) {
    var dom = host.__genDom;
    if (dom && host.contains(dom.grid)) return dom;
    clear(host);
    var grid = el('div', 'grid-imgs');
    grid.setAttribute('data-gen-gallery-grid', '1');
    host.appendChild(grid);
    var empty = el('div', 'hint', '读取中…');
    host.appendChild(empty);
    dom = { grid: grid, empty: empty };
    host.__genDom = dom;
    return dom;
  }

  /** 一个网格的"键 → 格子"表（挂在网格元素上，跨轮询复用同一批节点）。 */
  function gridCells(grid) {
    if (!grid.__cells) grid.__cells = Object.create(null);
    return grid.__cells;
  }

  /**
   * 把 `entries` 增量同步进网格：`entry.key` 认格子。
   *   · 已有键 → **原样复用**（只调 `update` 改文字/属性，绝不碰 <img> 的 src，更不重建节点）；
   *   · 新键 → `make` 造一个；本轮不该有的键 → 摘掉。
   * 顺序按 `entries` 排（`insertBefore` 只移动节点，不会让图片重新解码、不会重发请求）。
   */
  function syncCells(grid, entries, make, update) {
    var store = gridCells(grid);
    var keep = Object.create(null);
    entries.forEach(function (entry) { keep[entry.key] = true; });
    Object.keys(store).forEach(function (key) {
      if (keep[key]) return;
      var dead = store[key];
      if (dead.parentNode === grid) grid.removeChild(dead);
      delete store[key];
    });
    var previous = null;
    entries.forEach(function (entry) {
      var node = store[entry.key];
      if (!node) { node = make(entry); store[entry.key] = node; }
      else if (typeof update === 'function') update(node, entry);
      if (previous ? previous.nextSibling !== node : grid.firstChild !== node) {
        grid.insertBefore(node, previous ? previous.nextSibling : grid.firstChild);
      }
      previous = node;
    });
  }

  /**
   * 新格子该预设什么比例：
   *   0) **本会话已经量过**这张图 → 直接用缓存里的真实比例（连加载态都不用出现）；
   *   1) 这张图以前画过 → 用记下来的**真实比例**（`gen.ratios`，最准）；
   *   2) 否则用**生成参数**（`/api/status` 的 `generation.width/height`）—— 图就是按这个尺寸出的，
   *      缩略图没到之前盒子已经是正确形状，不会"先空盒子、图到了再变一下"；
   *   3) 都拿不到 → 空串，CSS 的 `.thumb.ratio-unknown` 给中性 4:3 **加明确的「加载中…」**。
   */
  function genPresetRatio(path) {
    var cached = path ? imageCache.ratio(path) : '';
    if (cached) return cached;
    var known = path ? gen.ratios[path] : '';
    if (known) return known;
    var p = gen.params || {};
    var w = fmtNum(p.width, 0), h = fmtNum(p.height, 0);
    return w > 0 && h > 0 ? w + ' / ' + h : '';
  }

  /** 图片在 `gen.images` 里的下标。**点击这一刻现算**：列表会增长，建格子时记下的下标会过期。 */
  function genIndexOf(path) {
    for (var i = 0; i < gen.images.length; i++) if (gen.images[i] && gen.images[i].path === path) return i;
    return -1;
  }
  /** 查看器条目：与 `gen.images` 一一对应（原图地址，不带 w）。 */
  function genViewerList() {
    return gen.images.map(function (it) { return { src: it.path, caption: (it.name || '') + '（' + fmtBytes(it.size) + '）' }; });
  }
  function genOpenImage(path) {
    var at = genIndexOf(path);
    PixikoM.openViewer(genViewerList(), at < 0 ? 0 : at);
  }
  function genMenuAt(path) {
    var at = genIndexOf(path);
    if (at >= 0) genImageMenu(gen.images[at], at);
  }

  /**
   * 一个图片格子：`.cell.done[data-path] > .thumb(.loading) > img`，文件名/「待领取」放在 `.cap`。
   * `.thumb` **先用已知比例把形状定下来**并带 `loading`（骨架灰条 + 一行「加载中…」）；图片 load 后
   * 由 `applyNaturalRatio` 换成真实比例、这里摘掉 `loading`。所以从第一帧起就不是"空盒子"。
   */
  function genImageCell(entry) {
    var item = entry.item;
    var cell = el('div', 'cell done tap');
    cell.setAttribute('data-cell', 'done');
    cell.setAttribute('data-path', item.path);
    var cached = imageCache.ratio(item.path);              // 本会话已经量过 → 连加载态都不用出现
    /* 只有"现在真的会去取"的格子才显示加载态（骨架 + 「加载中…」）：新格子是游离节点，`loadingFor`
       只会对前 4 个给 eager，其余是 `loading=lazy`（浏览器要等它滚进视口才发请求）—— 那些格子如果也挂
       `loading`，屏幕上就永远挂着一排"加载中…"（实测真机观察里 100 秒内有 58 帧是这种假加载态）。 */
    var willFetch = cached ? false : (entry.index < 4);
    var thumb = el('div', 'thumb' + (willFetch ? ' loading' : ''));
    var ratio = genPresetRatio(item.path);
    if (ratio) thumb.style.aspectRatio = ratio;
    else if (!cached) thumb.classList.add('ratio-unknown');
    if (imageCache.failed(item.path)) {
      // 失败终态：直接出「图取不到」，不再造 <img>、不再重试刷请求
      thumb.appendChild(imageFailBox(item.path));
      cell.appendChild(thumb);
      genImageCellUpdate(cell, entry);
      return cell;
    }
    var img = imageCache.node(item.path, thumbUrl(item.path));
    var reuse = !!(cached && img.getAttribute('src') === thumbUrl(item.path));
    if (reuse) {
      img.alt = item.name || '';
      thumb.appendChild(img);                              // 同一个节点搬过来：0 请求、0 解码、不闪
    } else {
      img.alt = item.name || '';
      mountImage(img, item.path, entry.index, function () {
        thumb.classList.remove('loading');
        var w = img.naturalWidth, h = img.naturalHeight;
        if (w > 0 && h > 0) gen.ratios[item.path] = w + ' / ' + h;   // 记下来给以后复用的格子用
      });
      thumb.appendChild(img);
    }
    cell.appendChild(thumb);
    genImageCellUpdate(cell, entry);
    cell.addEventListener('click', function () { genOpenImage(item.path); });
    var timer = null;
    cell.addEventListener('touchstart', function () {
      clearTimeout(timer);
      timer = setTimeout(function () { genMenuAt(item.path); }, 520);
    }, { passive: true });
    cell.addEventListener('touchend', function () { clearTimeout(timer); }, { passive: true });
    cell.addEventListener('touchmove', function () { clearTimeout(timer); }, { passive: true });
    cell.addEventListener('contextmenu', function (event) { event.preventDefault(); genMenuAt(item.path); });
    return cell;
  }

  /** 复用格子时只更新"会变的那点东西"：文件名/「待领取」这一行、画廊下标属性。图片本身不动。 */
  function genImageCellUpdate(cell, entry) {
    var want = entry.cap === 'name' ? (entry.item.name || '') : entry.cap === 'pending' ? '待领取' : '';
    var cap = null;
    for (var i = 0; i < cell.children.length; i++) {
      if (cell.children[i].classList && cell.children[i].classList.contains('cap')) cap = cell.children[i];
    }
    if (want) {
      if (!cap) cell.appendChild(el('div', 'cap', want));
      else if (cap.textContent !== want) cap.textContent = want;
    } else if (cap) cell.removeChild(cap);
    if (entry.galleryIndex !== undefined && cell.getAttribute('data-gallery-cell') !== String(entry.galleryIndex)) {
      cell.setAttribute('data-gallery-cell', String(entry.galleryIndex));
    }
  }

  /** 生成中的环形进度格子（节点复用，只改 stroke-dashoffset 与百分比文字）。 */
  function genRingCell(entry) {
    var cell = el('div', 'cell');
    cell.setAttribute('data-cell', 'running');
    var thumb = el('div', 'thumb');
    var ratio = genPresetRatio('');
    if (ratio) thumb.style.aspectRatio = ratio;
    var radius = 15, circumference = 2 * Math.PI * radius;
    thumb.innerHTML = '<svg class="ring" viewBox="0 0 34 34"><circle class="bg" cx="17" cy="17" r="' + radius + '"></circle>' +
      '<circle class="fg" cx="17" cy="17" r="' + radius + '" stroke-dasharray="' + circumference.toFixed(1) + '" stroke-dashoffset="' + circumference.toFixed(1) + '" transform="rotate(-90 17 17)"></circle></svg>' +
      '<div class="pct">0%</div>';
    cell.appendChild(thumb);
    cell.__ring = { thumb: thumb, fg: thumb.querySelector('.fg'), pct: thumb.querySelector('.pct'), circumference: circumference, held: null };
    genRingUpdate(cell, entry);
    return cell;
  }
  function genRingUpdate(cell, entry) {
    var ring = cell.__ring;
    if (!ring) return;
    var percent = Math.max(0, Math.min(100, fmtNum(entry.percent, 0)));
    ring.fg.setAttribute('stroke-dashoffset', (ring.circumference * (1 - percent / 100)).toFixed(1));
    ring.pct.textContent = Math.round(percent) + '%';
    if (entry.suspended && !ring.held) { ring.held = el('div', 'held-tag', '挂起'); ring.thumb.appendChild(ring.held); }
    else if (!entry.suspended && ring.held) { ring.thumb.removeChild(ring.held); ring.held = null; }
  }

  /** 还在排队、这一轮还没轮到的格子：明确的「等待」占位（不是空的 `—`）。 */
  function genPendingCell() {
    var cell = el('div', 'cell pending');
    cell.setAttribute('data-cell', 'pending');
    var thumb = el('div', 'thumb');
    var ratio = genPresetRatio('');
    if (ratio) thumb.style.aspectRatio = ratio;
    thumb.appendChild(el('div', 'muted', '等待'));
    cell.appendChild(thumb);
    return cell;
  }

  /** 进度卡 + 图集网格。 */
  function genRenderProgress() {
    var host = gen.root && gen.root.querySelector('[data-gen-progress]');
    if (!host) return;
    var tasks = PixikoM.state.tasks || [];
    var progress = PixikoM.state.progress || {};
    var imagePercent = fmtNum(progress.percent, 0);
    var overall = genOverall(tasks, imagePercent, progress);
    var dom = genProgressDom(host);
    clear(dom.cardHost);                     // 只有进度卡（纯文字，没有图片）每拍重建

    // ── 进度卡
    var card = el('div', 'card progress-card');
    card.setAttribute('data-gen-progress-card', '1');
    var top = el('div', 'progress-top');
    var pctNode = el('div', 'progress-pct', Math.round(overall.percent) + '%');
    pctNode.setAttribute('data-gen-percent', String(Math.round(overall.percent)));
    top.appendChild(pctNode);
    top.appendChild(el('div', 'muted grow', overall.text));
    card.appendChild(top);
    var track = el('div', 'bar-track');
    var fill = el('div', 'bar-fill');
    fill.style.width = overall.percent + '%';
    /* 只有在"跑了但拿不到百分比"时才用不确定动画（`.indet` 是 `width:40%!important`，会把真实进度盖掉）。
       现在"SD 在跑但没进队列"这一支有真实百分比（overall.live），所以只在 0% 那一下才用动画。 */
    if (overall.running && !tasks.length && !(imagePercent > 0)) fill.classList.add('indet');
    track.appendChild(fill);
    card.appendChild(track);

    var task = overall.current;
    if (task) {
      var detail = el('div', 'row-sub');
      detail.setAttribute('data-gen-task-status', String(task.status || ''));
      detail.textContent = '任务 #' + task.number + '：' + (task.status || '') +
        '（成功 ' + fmtNum(task.images, 0) + ' / 失败 ' + fmtNum(task.failed, 0) + '）';
      card.appendChild(detail);
      var actions = el('div', 'inline');
      var mk = function (text, action, cls) {
        var btn = el('button', 'chip' + (cls ? ' ' + cls : ''), text);
        btn.type = 'button';
        btn.setAttribute('data-task-action', action);
        btn.addEventListener('click', function () { genTaskAction(action, task.number); });
        return btn;
      };
      actions.appendChild(mk(task.suspended ? '继续' : '挂起', task.suspended ? 'resume' : 'hold'));
      actions.appendChild(mk('置顶', 'first'));
      actions.appendChild(mk('取消', 'cancel'));
      var allBtn = el('button', 'chip', '取消全部');
      allBtn.type = 'button';
      allBtn.setAttribute('data-task-action', 'cancel-all');
      allBtn.addEventListener('click', async function () {
        var ok = await PixikoM.confirm({ title: '取消所有任务？', text: '当前队列里剩下的都会停。', ok: '取消任务', danger: true });
        if (ok) genTaskAction('cancel', 'all');
      });
      actions.appendChild(allBtn);
      card.appendChild(actions);
    } else if (overall.live) {
      /* 没进队列的那一张（单图 `.gen 1`）：也要有一行"任务进度"，否则用户只看到一条空队列。 */
      var liveRow = el('div', 'row-sub');
      liveRow.setAttribute('data-gen-task-status', '生成中');
      liveRow.setAttribute('data-gen-live', '1');
      liveRow.textContent = '正在生成这一张：' + Math.round(overall.percent) + '%'
        + (fmtNum(progress.etaSeconds, 0) > 0 ? '（预计还需 ' + Math.round(fmtNum(progress.etaSeconds, 0)) + ' 秒）' : '');
      card.appendChild(liveRow);
    }
    var queueNote = el('div', 'hint');
    if (tasks.length > 1) {
      queueNote.textContent = '队列里还有 ' + (tasks.length - 1) + ' 条任务在后面。';
    } else if (!tasks.length) {
      if (overall.live) {
        queueNote.setAttribute('data-gen-live-note', '1');
        queueNote.textContent = overall.text || '正在生成这一张…';       // SD 的实时文字（步骤 x/y、预计还需…）
      } else {
        queueNote.textContent = progress.reachable === false ? 'SD 没在跑（/api/progress 说 reachable=false）。' : '现在没有排队或生成中的任务。';
      }
    } else {
      queueNote.textContent = progress.text ? String(progress.text) : '';
    }
    card.appendChild(queueNote);
    dom.cardHost.appendChild(card);

    // ── 「这一轮的图」：按**图片路径**增量同步（老格子连同它的 <img> 原样复用，跨轮询不重建）
    //    数量关系：done = 真的在 /api/images 里存在的那些；任务计数（task.images）比列表快一拍时
    //    （图还没进列表）**只画已存在的、剩下的等下一拍**，绝不画 `—` 空占位 —— 那正是 ghost placeholder。
    var images = fmtNum(task && task.images, 0);
    var failed = fmtNum(task && task.failed, 0);
    var total = fmtNum(task && task.total, 0);
    var runningSlots = task && task.running ? 1 : 0;
    var pending = Math.max(0, total - images - failed - runningSlots);
    var done = Math.min(images, gen.images.length);
    var entries = [];
    for (var i = 0; i < done; i++) {
      entries.push({ key: 'img:' + gen.images[i].path, kind: 'img', item: gen.images[i], index: i, cap: 'name' });
    }
    if (runningSlots) entries.push({ key: 'ring', kind: 'ring', percent: imagePercent, suspended: !!(task && task.suspended) });
    /* 队列为空但 SD 正在生成（单图 `.gen 1` 实测就是这样）：也要给一个**看得见的 placeholder**。
       用和任务生成中同一个环形进度格，百分比同样取 SD 的实时进度。 */
    if (!task && overall.live) entries.push({ key: 'ring', kind: 'ring', percent: imagePercent, suspended: false });
    /* 这一次"没进队列的生成"刚出完图：把那一张接进「这一轮的图」，于是环形占位**在同一个网格里**
       被真图替换（不是凭空消失，也不是幽灵）。下次开始新一轮时会被覆盖。 */
    if (!task && !overall.live && gen.liveImage) {
      for (var j = 0; j < gen.images.length; j++) {
        if (gen.images[j].path === gen.liveImage) { entries.push({ key: 'img:' + gen.images[j].path, kind: 'img', item: gen.images[j], index: j, cap: 'name' }); break; }
      }
    }
    for (var k = 0; k < pending; k++) entries.push({ key: 'pending:' + k, kind: 'pending' });
    syncCells(dom.grid, entries, function (entry) {
      if (entry.kind === 'ring') return genRingCell(entry);
      if (entry.kind === 'pending') return genPendingCell(entry);
      return genImageCell(entry);
    }, function (node, entry) {
      if (entry.kind === 'ring') genRingUpdate(node, entry);
      else if (entry.kind === 'img') genImageCellUpdate(node, entry);
    });
    var shown = entries.length;
    dom.count.textContent = shown + ' / ' + (total || shown);
    dom.count.setAttribute('data-gen-grid-count', String(shown));
    dom.grid.style.display = shown ? '' : 'none';
    dom.empty.style.display = shown ? 'none' : '';
    // 任务说"出过图"但列表里还没有 → 给一句明确的说明（不是画一个空盒子）
    var emptyText = task && images > 0 && !done ? '图还在路上，稍等一下…'
      : overall.live ? '正在生成这一张，图出来就显示在这里。' : '还没有图。点下面的「开始生成」。';
    if (dom.empty.textContent !== emptyText) dom.empty.textContent = emptyText;
    /* 记住"没进队列的那次生成"最后产出的那张图：live 结束的那一拍记下来，下一拍它就被接进
       「这一轮的图」（于是环形占位在同一个网格里被真图替换）。有任务或开始新一轮时清掉。 */
    if (overall.live) { gen.liveActive = true; gen.liveImage = ''; }
    else if (gen.liveActive) { gen.liveActive = false; gen.liveImage = gen.images.length && gen.images[0] ? gen.images[0].path : ''; }
    if (task) gen.liveImage = '';

    // ── 最近的作品（/api/images）：同一个增量同步，同一个 <img> 跨轮询一直活着
    var galleryHost = gen.root.querySelector('[data-gen-gallery]');
    if (galleryHost) {
      var gdom = genGalleryDom(galleryHost);
      var gentries = gen.images.map(function (item, index) {
        return { key: 'img:' + item.path, kind: 'img', item: item, index: index, cap: item.pending ? 'pending' : 'none', galleryIndex: index };
      });
      syncCells(gdom.grid, gentries, genImageCell, genImageCellUpdate);
      gdom.empty.style.display = gentries.length ? 'none' : '';
      if (!gentries.length && gdom.empty.textContent !== '还没有历史作品。') gdom.empty.textContent = '还没有历史作品。';
    }
  }

  /** 一次轮询：/api/progress + /api/tasks + /api/images。 */
  async function genPoll() {    var results = await Promise.allSettled([
      PixikoM.api('/api/progress', { body: { scope: PixikoM.scope() } }),
      PixikoM.api('/api/tasks', { body: { scope: PixikoM.scope() } }),
      PixikoM.api('/api/images', { body: { limit: 24, scope: PixikoM.scope() } })
    ]);
    if (results[0].status === 'fulfilled') PixikoM.state.progress = results[0].value || {};
    if (results[1].status === 'fulfilled') PixikoM.state.tasks = (results[1].value && results[1].value.tasks) || [];
    if (results[2].status === 'fulfilled') gen.images = (results[2].value && results[2].value.images) || [];
    var failed = results.filter(function (r) { return r.status === 'rejected'; });
    if (failed.length === 3) {
      var error = failed[0].reason;
      PixikoM.state.lastError = { code: error.code || 'unknown', message: error.message };
      var host = gen.root && gen.root.querySelector('[data-gen-progress]');
      if (host) { clear(host).appendChild(errorState(error, function () { genRefreshAll(); })); }
      return;
    }
    genRenderProgress();
  }
  /** 需要"等一拍再刷"的地方用它（避免连点生成时打出一串请求）。 */
  gen.pollOnce = debounce(function () { genPoll(); }, 120);

  async function genRefreshAll() {
    var host = gen.root && gen.root.querySelector('[data-gen-progress]');
    if (host && !gen.params) clear(host).appendChild(skeleton(3));
    try {
      await genLoadParams();
      await genLoadOptions();
      genRenderParams();
      await genPoll();
    } catch (error) {
      PixikoM.state.lastError = { code: error.code || 'unknown', message: error.message };
      if (host) clear(host).appendChild(errorState(error, function () { genRefreshAll(); }));
    }
  }

  /** 预设：POST /api/presets 列表 + /api/presets/edit（保存/删除），载入走 /api/generation。 */
  async function genPresetsMenu() {
    var data;
    try { data = await PixikoM.api('/api/presets', { body: { scope: PixikoM.scope() } }); }
    catch (error) { PixikoM.toast('读预设失败：' + error.message); return; }
    var items = [];
    var presets = (data && data.presets) || [];
    items.push({
      text: '保存当前参数为预设…', sub: 'POST /api/presets/edit {action:save}', onSelect: async function () {
        var name = await PixikoM.prompt({ title: '预设名称', placeholder: '例如 人像 1024', ok: '保存' });
        if (!name) return;
        try {
          var result = await PixikoM.api('/api/presets/edit', { body: { action: 'save', name: name, scope: PixikoM.scope() } });
          PixikoM.toast(result && result.message ? result.message : '已保存');
        } catch (error) { PixikoM.toast('保存失败：' + error.message); }
      }
    });
    presets.forEach(function (entry) {
      var preset = entry.preset || {};
      items.push({
        text: '载入：' + entry.name,
        sub: [preset.sampler, preset.width + '×' + preset.height, 'steps ' + preset.steps, 'cfg ' + preset.cfg].filter(Boolean).join(' · '),
        onSelect: function () {
          // 「载入」= 把这份快照的字段直接发给 /api/generation（与桌面版一致）。
          var fields = {};
          ['sampler', 'width', 'height', 'steps', 'cfg', 'seed', 'model'].forEach(function (key) {
            if (preset[key] !== undefined && preset[key] !== null && preset[key] !== '') fields[key] = preset[key];
          });
          genApply(fields);
        }
      });
    });
    presets.forEach(function (entry) {
      items.push({
        text: '删除：' + entry.name, danger: true, onSelect: async function () {
          var ok = await PixikoM.confirm({ title: '删除预设？', text: entry.name, ok: '删除', danger: true });
          if (!ok) return;
          try {
            var result = await PixikoM.api('/api/presets/edit', { body: { action: 'remove', name: entry.name, scope: PixikoM.scope() } });
            PixikoM.toast(result && result.message ? result.message : '已删除');
          } catch (error) { PixikoM.toast('删除失败：' + error.message); }
        }
      });
    });
    PixikoM.sheet({ title: '参数预设', items: items });
  }

  function genMount(root) {
    gen.root = root;
    root.setAttribute('data-gen', '1');

    // 生成按钮卡
    var start = el('div', 'card');
    var head = el('div', 'card-head');
    head.appendChild(el('div', 'card-title', '开始生成'));
    start.appendChild(head);
    var countRow = el('div', 'spread');
    var countField = el('div', 'field grow');
    countField.style.marginBottom = '0';
    countField.appendChild(el('div', 'field-label', '数量'));
    var stepper = el('div', 'stepper');
    var minus = el('button', 'stepper-btn', '−');
    var plus = el('button', 'stepper-btn', '＋');
    minus.type = 'button'; plus.type = 'button';
    var countInput = el('input', 'input');
    countInput.type = 'number';
    countInput.inputMode = 'numeric';
    countInput.value = String(gen.count);
    countInput.setAttribute('data-gen-count', '1');
    minus.addEventListener('click', function () {
      gen.count = Math.max(1, fmtNum(countInput.value, 1) - 1); countInput.value = String(gen.count);
    });
    plus.addEventListener('click', function () {
      gen.count = Math.min(99, fmtNum(countInput.value, 1) + 1); countInput.value = String(gen.count);
    });
    countInput.addEventListener('input', function () { gen.count = Math.max(1, Math.min(99, fmtNum(countInput.value, 1))); });
    stepper.appendChild(minus); stepper.appendChild(countInput); stepper.appendChild(plus);
    countField.appendChild(stepper);
    countRow.appendChild(countField);

    var presetBtn = el('button', 'btn ghost');
    presetBtn.type = 'button';
    presetBtn.setAttribute('data-gen-presets', '1');
    presetBtn.textContent = '预设';
    // 明确"别挤我"：.btn 已经是 flex:0 0 auto，这里再补 nowrap —— 上一版「预设」被挤成
    // 竖排的"预/设"两行，就是因为它在 .spread 行里被 stepper 抢走了宽度。
    presetBtn.style.whiteSpace = 'nowrap';
    presetBtn.addEventListener('click', genPresetsMenu);
    countRow.appendChild(presetBtn);
    start.appendChild(countRow);

    var go = el('button', 'btn primary big block');
    go.type = 'button';
    go.setAttribute('data-gen-start', '1');
    go.style.marginTop = '10px';
    go.textContent = '开始生成';
    go.addEventListener('click', genStart);
    start.appendChild(go);
    root.appendChild(start);

    // 参数卡
    var paramsCard = el('div', 'card');
    var paramsHead = el('div', 'card-head');
    paramsHead.appendChild(el('div', 'card-title', '生成参数'));
    var reload = el('button', 'chip', '重新读取');
    reload.type = 'button';
    reload.setAttribute('data-gen-reload', '1');
    reload.addEventListener('click', function () { genRefreshAll(); });
    paramsHead.appendChild(reload);
    paramsCard.appendChild(paramsHead);
    var paramsHost = el('div');
    paramsHost.setAttribute('data-gen-params', '1');
    paramsHost.appendChild(skeleton(3));
    paramsCard.appendChild(paramsHost);
    paramsCard.appendChild(el('p', 'hint', '改完立刻生效（POST /api/generation），没有「应用」按钮。'));
    root.appendChild(paramsCard);

    // 进度区
    var progressHost = el('div');
    progressHost.setAttribute('data-gen-progress', '1');
    progressHost.appendChild(skeleton(3));
    root.appendChild(progressHost);

    // 历史作品
    var galleryCard = el('div', 'card');
    var galleryHead = el('div', 'card-head');
    galleryHead.appendChild(el('div', 'card-title', '最近的作品'));
    var galleryReload = el('button', 'chip', '刷新');
    galleryReload.type = 'button';
    galleryReload.setAttribute('data-gen-gallery-reload', '1');
    galleryReload.addEventListener('click', function () { genPoll(); });
    galleryHead.appendChild(galleryReload);
    galleryCard.appendChild(galleryHead);
    var galleryHost = el('div');
    galleryHost.setAttribute('data-gen-gallery', '1');
    galleryHost.appendChild(el('div', 'hint', '读取中…'));
    galleryCard.appendChild(galleryHost);
    root.appendChild(galleryCard);
  }

  PixikoM.register('gen', {
    title: '出图',
    mount: genMount,
    refresh: function () { return genRefreshAll(); },
    menu: function () {
      return [
        { text: '参数预设', sub: '保存 / 载入 / 删除', onSelect: genPresetsMenu },
        { text: '刷新队列与图片', onSelect: function () { genPoll(); } },
        { text: '取消全部任务', danger: true, onSelect: async function () {
            var ok = await PixikoM.confirm({ title: '取消所有任务？', ok: '取消任务', danger: true });
            if (ok) genTaskAction('cancel', 'all');
          } }
      ];
    }
  });

  /* ── 8. 启动 ─────────────────────────────────────────────────────────── */

  /** 页面隐藏时把没存上的对话正文补一次（iOS/Android 切后台随时可能被杀）。 */
  function installVisibilityFlush() {
    document.addEventListener('visibilitychange', function () {
      if (document.hidden && chat.loaded && chat.entries.length) saveChatLog();
    });
    window.addEventListener('pagehide', function () { if (chat.loaded && chat.entries.length) saveChatLog(); });
  }

  /** 底部 tab 用 <a href="#/...">，交给 hash 路由；这里只补 :active 反馈与 aria。 */
  function installTabs() {
    var nodes = document.querySelectorAll('#m-tabs .tab');
    for (var i = 0; i < nodes.length; i++) {
      (function (node) {
        node.addEventListener('click', function () {
          // 已经在这一屏：再点一次就当"回到顶部 + 刷新"
          if (node.getAttribute('data-nav') === currentId) { refreshCurrent().catch(function () { }); }
        });
      })(nodes[i]);
    }
  }

  function boot() {
    if (booted) return;
    booted = true;
    // 先把这一页的对话身份定下来（外壳给的设备 scope → 本机镜像 → 老行为）：
    // 同时会**固化进本机镜像**（localStorage 的 pixiko-device-scope），于是**整页重载**
    // （Android 的下拉刷新就是 webView.reload）之后即使地址上没带 scope，也还是同一台设备的对话。
    PixikoM.scope();
    installDeviceFrame();
    installTabs();
    installPullToRefresh();
    installKeyboardHandling();
    installVisibilityFlush();

    var refreshBtn = $('m-bar-refresh');
    if (refreshBtn) refreshBtn.addEventListener('click', function () {
      refreshCurrent().catch(function (error) { PixikoM.toast(error && error.message ? error.message : String(error)); });
    });
    var menuBtn = $('m-bar-menu');
    if (menuBtn) menuBtn.addEventListener('click', openBarMenu);
    var backBtn = $('m-bar-back');
    if (backBtn) backBtn.addEventListener('click', function () {
      // 二级屏的返回：优先回到底部 tab 的主屏，实在没有就回历史
      if (TABS.length) PixikoM.go('chat'); else history.back();
    });

    window.addEventListener('hashchange', applyRoute);
    // 首次路由之前把 scope 守卫武装起来：记下此刻解析出的 scope，之后任何一次
    // 「解析结果与它不同」都视为外壳把这一页换给了另一个设备 scope → 整页重跑（见 applyRoute）。
    routedScope = PixikoM.scope();
    scopeGuardArmed = true;
    applyRoute();
    if (!location.hash) location.replace('#/chat');
    // 给自动化/外壳用：**首次路由跑完并且画面稳定之后**才置位。
    // 只写一个属性还不够 —— defer 脚本执行完的那一刻 DOM 已经存在，但首次路由与首屏布局
    // 可能还没落地，自动化立刻点 tab 会读到"hash 变了、屏幕还没切"的中间态。
    // 用两帧 rAF 等一次样式/布局收尾（后台标签页 rAF 会被节流，所以加一个 400ms 兜底）。
    var markReady = function () {
      if (document.documentElement.getAttribute('data-pixiko-ready') === '1') return;
      document.documentElement.setAttribute('data-pixiko-ready', '1');
    };
    try {
      requestAnimationFrame(function () { requestAnimationFrame(markReady); });
    } catch (error) { /* 老浏览器没有 rAF 就直接兜底 */ }
    setTimeout(markReady, 400);

    // 状态轮询：只在"这一屏可见"时跑（pollWhileVisible 自己会判断），顺便喂回执角标。
    PixikoM.pollWhileVisible(function () { return PixikoM.refreshStatus(); }, 6000);

    /* 「回到前台 / 页面刚重建」立刻补一拍回执（补拉链见 catchUpReceipts）。
       挂起期间什么都不会发生（系统冻住了定时器与网络，对抗不了），唯一能做的是回来这一下
       立刻把缺的图补齐。用户手势（touch/pointer/keydown）太频繁，走"最小间隔"那一档；
       其余信号（visibilitychange / pageshow / focus / resume / online）都是"真的回来了"，立刻补。 */
    PixikoM.onWake(function (why) {
      if (currentId !== 'chat' || !chat.loaded) return;
      catchUpReceipts(!/^user:/.test(String(why || '')));
    });

    // 出图屏的进度轮询：常驻注册，pollWhileVisible 保证只在页面可见时跑，
    // 屏内再判断自己有没有被挂载过（没进过出图屏就不必拉）。
    PixikoM.pollWhileVisible(function () {
      if (!mounted.gen || currentId !== 'gen') return;
      return genPoll();
    }, PROGRESS_POLL_MS);
  }

  /**
   * 启动时机：**必须等所有 screen-*.js 注册完**再做首次路由。
   *
   * <p>坑（M3 报的深链接 bug 的根因）：本文件是 `defer`，执行时 `document.readyState` 已经是
   * `'interactive'`，所以以前那个 `readyState === 'loading' ? … : boot()` 会在 IIFE 末尾**立刻** boot，
   * 比后面所有 `screen-*.js` 的 register() 都早；于是 `/m#/prompt` 这类深链接首次路由时
   * 还没人注册 prompt，只能画出占位屏，hash 又不再变 → 真屏永远 hidden。
   * 现在改成：只在 DOMContentLoaded 之后 boot（DOMContentLoaded 一定晚于所有 defer 脚本执行完），
   * 并且再兜一次 `readyState === 'complete'` 的情况。
   */
  var booted = false;
  function scheduleBoot() {
    if (booted) return;
    if (document.readyState === 'complete') { boot(); return; }
    document.addEventListener('DOMContentLoaded', boot, { once: true });
    // 兜底：DOMContentLoaded 万一已经被别的脚本吃掉（不该发生），window.load 再补一次。
    window.addEventListener('load', function () { if (!booted) boot(); }, { once: true });
  }
  scheduleBoot();
})();
