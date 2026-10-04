/* Pixiko 控制台：无构建步骤。每个栏目是一个独立页面（/gen、/prompt…），前端只加载本栏目的数据。 */
(() => {
  const TOKEN_KEY = 'kotori-webui-token';
  /** 当前页面属于哪个栏目：由服务端在页面里注入（见 WebPages.render）。 */
  const PAGE = window.PIXIKO_PAGE || 'chat';
  const state = { token: localStorage.getItem(TOKEN_KEY) || '', status: null, options: null, pollTimer: null, followTimer: null, busy: 0,
    seenImages: new Map(), seenGroups: new Map(), followUntil: 0, receiptKey: '', receiptTexts: 0, receiptImages: 0,
    receiptCount: 0, receiptBox: null, receiptToasted: '',
    // 面板底部回执栏的图片是增量追加的：记住当前那张图集卡 / 单张卡，新图到了就地更新，不整块重建。
    receiptGallery: null, receiptSingle: null, receiptSingleFile: '',
    // 「回执」栏：全部回执列表 + 未查看（unread）标记。字段与 /api/quests 契约一致。
    quests: { unread: 0, latest: 0, retainedMinutes: 0 }, questPollTimer: null,
    questList: null, questListError: '', questListLoading: false, questListWarned: false,
    loras: null, loraGroups: null, terminalHistory: [], terminalCursor: 0,
    // Civitai 搜索的翻页状态：搜索词与当前页留在前端，翻页时不用重敲。
    civitaiQuery: '', civitaiPage: 1 };

  const $ = (id) => document.getElementById(id);
  const el = (tag, cls, text) => { const node = document.createElement(tag); if (cls) node.className = cls; if (text !== undefined) node.textContent = text; return node; };
  const on = (id, event, handler) => { const node = $(id); if (node) node.addEventListener(event, handler); };
  /** 「生成参数」面板的字段：改完即发，不再有「应用」按钮。 */
  const GEN_FIELDS = [['set-width', 'width'], ['set-height', 'height'], ['set-sampler', 'sampler'], ['set-model', 'model'],
    ['set-steps', 'steps'], ['set-cfg', 'cfg'], ['set-seed', 'seed'], ['set-imgcnt', 'imageCount']];
  let appliedGeneration = '';

  function toast(message) {
    const box = $('toast');
    box.textContent = message;
    box.hidden = false;
    requestAnimationFrame(() => box.classList.add('show'));
    clearTimeout(box._timer);
    box._timer = setTimeout(() => { box.classList.remove('show'); setTimeout(() => { box.hidden = true; }, 260); }, 2600);
  }

  function banner(message) {
    const box = $('banner');
    if (!message) { box.hidden = true; box.textContent = ''; return; }
    box.textContent = message;
    box.hidden = false;
  }

  // ---------------------------------------------------------------- 图片查看器

  /** 缩放范围与滚轮步进：0.2×–8×，一格滚轮 20%。 */
  const VIEWER_MIN_ZOOM = 0.2;
  const VIEWER_MAX_ZOOM = 8;
  const VIEWER_ZOOM_STEP = 0.2;
  /** 查看器状态：`list` 是当前这一组图（单张时只有一项），`index` 是第几张，`zoom` 是倍率。 */
  const viewerState = { list: [], index: 0, zoom: 1, caption: '' };
  /** 浏览器原生支持 CSS zoom 就用它（能撑开滚动区，放大后拖得动）；否则退回 transform。 */
  const VIEWER_NATIVE_ZOOM = (() => { try { return 'zoom' in document.createElement('div').style; } catch (error) { return false; } })();

  /** 一组图里的一项：字符串地址或 {src, caption} 都认。 */
  function viewerItem(entry) {
    if (typeof entry === 'string') return entry ? { src: entry, caption: '' } : null;
    if (entry && typeof entry === 'object') {
      const src = entry.src || entry.file || entry.url || '';
      return src ? { src: String(src), caption: String(entry.caption || entry.alt || entry.name || '') } : null;
    }
    return null;
  }

  /**
   * 点图放大：弹出查看器（背景压暗）。滚轮缩放（向上放大 / 向下缩小，钳在 0.2×–8×），
   * 点图在「适应屏幕 / 1:1 原图」之间切换，← / → 换图（配合图集），
   * Esc、点图片周围的空白或「关闭」收起。Ctrl/中键仍然走浏览器的新标签打开。
   *
   * 向后兼容：单张就是 openViewer(src, caption)，前后按钮与计数自动隐藏/禁用；
   * 一组图用 openViewer(src, caption, list, index)，list 可以是地址数组，也可以是 {src, caption} 数组。
   */
  function openViewer(src, caption, list, index) {
    const viewer = $('viewer');
    if (!viewer || !src) return;
    const items = (Array.isArray(list) ? list.map(viewerItem).filter(Boolean) : []);
    if (!items.length) items.push({ src: String(src), caption: caption || '' });
    let current = Math.floor(Number(index));
    current = Number.isFinite(current) ? Math.min(items.length - 1, Math.max(0, current)) : 0;
    // 点的那一张以节点上的实际地址为准（封面代理兜底可能换过地址），说明优先用图集里那一项。
    items[current] = { src: String(src), caption: items[current].caption || caption || '' };
    viewerState.list = items;
    viewerState.index = current;
    viewerState.caption = caption || '';
    viewerState.zoom = 1;
    viewer.classList.remove('actual');
    viewer.hidden = false;
    document.body.classList.add('viewer-open');   // 查看器打开时锁住页面滚动
    viewerShowCurrent();
    viewerApplyZoom();
    $('viewer-close').focus();
  }

  function closeViewer() {
    const viewer = $('viewer');
    if (!viewer || viewer.hidden) return;
    viewer.hidden = true;
    viewer.classList.remove('actual');
    viewerState.list = []; viewerState.index = 0; viewerState.zoom = 1; viewerState.caption = '';
    document.body.classList.remove('viewer-open');
    const image = $('viewer-image');
    if (image) image.removeAttribute('src');
    viewerSyncControls();
  }

  /** 工具条一次算齐：倍率、第几张/共几张、前后按钮、1:1 按钮文字（缩放与翻页都调它）。 */
  function viewerSyncControls() {
    const total = viewerState.list.length;
    const many = total > 1;
    const prev = $('viewer-prev'), next = $('viewer-next'), count = $('viewer-count');
    if (prev) { prev.hidden = !many; prev.disabled = !many; }
    if (next) { next.hidden = !many; next.disabled = !many; }
    if (count) { count.hidden = !many; count.textContent = many ? (viewerState.index + 1) + ' / ' + total : ''; }
    const zoom = $('viewer-zoom');
    if (zoom) zoom.textContent = Math.round(viewerState.zoom * 100) + '%';
    const toggle = $('viewer-toggle');
    const viewer = $('viewer');
    if (toggle && viewer) toggle.textContent = viewer.classList.contains('actual') ? '适应屏幕' : '1:1 原图';
  }

  /** 当前这一张显示出来：地址、说明、按钮状态。 */
  function viewerShowCurrent() {
    const item = viewerState.list[viewerState.index];
    const image = $('viewer-image');
    if (!item || !image) return;
    if (image.getAttribute('src') !== item.src) image.src = item.src;   // 同一张不重设，免得闪
    image.alt = item.caption || '图片';
    const caption = $('viewer-caption');
    if (caption) caption.textContent = item.caption || viewerState.caption || '';
    viewerSyncControls();
  }

  /** 落倍率：钳在上下限内（缩到最小再往下滚只是停在 20%，不会把查看器关掉）。 */
  function viewerSetZoom(value) {
    const wanted = Number(value);
    const clamped = Math.min(VIEWER_MAX_ZOOM, Math.max(VIEWER_MIN_ZOOM, Number.isFinite(wanted) ? wanted : 1));
    viewerState.zoom = Math.round(clamped * 100) / 100;
    viewerApplyZoom();
  }

  /** 把倍率落到图上（原生 zoom 优先，退回 transform），并刷新工具条上的百分比提示。 */
  function viewerApplyZoom() {
    const image = $('viewer-image');
    if (!image) return;
    const zoom = viewerState.zoom;
    if (VIEWER_NATIVE_ZOOM) {
      image.style.zoom = zoom === 1 ? '' : String(zoom);
    } else {
      image.style.transform = zoom === 1 ? '' : 'scale(' + zoom + ')';
      image.style.transformOrigin = zoom > 1 ? 'top left' : 'center center';
    }
    const label = $('viewer-zoom');
    if (label) label.textContent = Math.round(zoom * 100) + '%';
  }

  /** 滚轮：向上放大、向下缩小。preventDefault 掉，别让页面跟着滚。 */
  function viewerWheel(event) {
    const viewer = $('viewer');
    if (!viewer || viewer.hidden) return;
    event.preventDefault();
    const delta = Number(event.deltaY) || 0;
    if (!delta) return;
    viewerSetZoom(viewerState.zoom + (delta < 0 ? VIEWER_ZOOM_STEP : -VIEWER_ZOOM_STEP));
  }

  /** ← / → 换图：到头循环（和图集的前后按钮一个行为）。 */
  function viewerStep(delta) {
    const total = viewerState.list.length;
    if (total < 2) return false;
    viewerState.index = (viewerState.index + delta + total) % total;
    const stage = $('viewer-stage');
    if (stage) { stage.scrollTop = 0; stage.scrollLeft = 0; }
    viewerShowCurrent();
    return true;
  }

  /** 「1:1 原图 / 适应屏幕」：切换的同时把倍率归一到 100%，按钮文字与倍率提示一起回到正确状态。 */
  function toggleViewerScale() {
    const viewer = $('viewer');
    if (!viewer) return false;
    const actual = viewer.classList.toggle('actual');
    viewerSetZoom(1);
    const stage = $('viewer-stage');
    if (stage) { stage.scrollTop = 0; stage.scrollLeft = 0; }
    viewerSyncControls();
    return actual;
  }

  /** 统一的图片节点：包一层链接（中键可以新标签打开），左键点击打开查看器。
   *  传了 list/index 就是图集里的一张：点击时把整组交给查看器翻页（list 可以是活的数组，后到的图也算）。 */
  function imageNode(src, caption, className, list, index) {
    const link = el('a', 'image-link');
    link.href = src;
    link.title = (caption ? caption + ' · ' : '') + '点击放大（Esc 关闭）';
    const img = el('img', className || null);
    img.loading = 'lazy';
    img.src = src;
    img.alt = caption || '图片';
    // 封面代理只认 Civitai 图床：代理取不到就退回原始远程地址再试一次；两次都不行就把 HTTP 状态
    // 直接显示在图上（回执里的图都是机器人自己发出来的；<img> 不能带 Authorization 头，只能这样兜底）。
    const coverMarker = String(src).indexOf('&url=');
    const coverSrc = String(src).startsWith('/api/civitai/thumb') && coverMarker >= 0;
    let coverRetried = false;
    img.addEventListener('error', () => {
      if (coverSrc && !coverRetried) {
        coverRetried = true;
        const raw = decodeURIComponent(String(src).slice(coverMarker + 5));
        if (img.getAttribute('src') !== raw) { img.setAttribute('src', raw); return; }
      }
      if (!coverSrc) return;
      // 原因摆在页面上：401 令牌不对、429 请求太密、502 机器人抓不到图（网络/代理/Cookie）。
      fetch(src).then((response) => {
        if (response.ok) return;
        link.replaceChild(el('div', 'civitai-nocover', '封面 ' + response.status + '（点刷新重试）'), img);
      }).catch(() => link.replaceChild(el('div', 'civitai-nocover', '封面取不到（点刷新重试）'), img));
    });
    link.appendChild(img);
    link._viewerList = list || null;                      // 点击时才读：图集里后到的图也能翻到
    link._viewerIndex = Number.isFinite(Number(index)) ? Math.max(0, Math.floor(Number(index))) : 0;
    link.addEventListener('click', (event) => {
      if (event.metaKey || event.ctrlKey || event.shiftKey) return;   // 保留浏览器的新标签行为
      event.preventDefault();
      openViewer(img.getAttribute('src'), caption, link._viewerList, link._viewerIndex);   // 兜底换过的地址也照样放大
    });
    return link;
  }

  // ---------------------------------------------------------------- 同风格确认框

  /**
   * 不用浏览器自带的 confirm/prompt：同一个 UI 风格的对话框，弹出时整页压暗。
   * 确认返回 true（输入框模式下返回去掉首尾空格的文本），取消返回 null。
   */
  function askDialog(options = {}) {
    const withInput = options.value !== undefined && options.value !== null;
    return new Promise((resolve) => {
      const overlay = $('overlay');
      const ok = $('dialog-ok');
      const cancel = $('dialog-cancel');
      const input = $('dialog-input');
      const field = $('dialog-field');
      $('dialog-title').textContent = options.title || '确认';
      const text = $('dialog-text');
      text.textContent = options.text || '';
      text.classList.toggle('danger', !!options.danger);
      $('dialog-label').textContent = options.label || '名称';
      field.hidden = !withInput;
      input.value = withInput ? options.value : '';
      ok.textContent = options.confirmText || '确定';
      ok.className = options.danger ? 'danger' : '';
      const restore = document.activeElement;
      overlay.hidden = false;
      const finish = (result) => {
        overlay.hidden = true;
        document.removeEventListener('keydown', onKey, true);
        ok.onclick = null; cancel.onclick = null; overlay.onclick = null;
        if (restore && typeof restore.focus === 'function') restore.focus();
        resolve(result);
      };
      const accept = () => finish(withInput ? input.value.trim() : true);
      const onKey = (event) => {
        if (event.key === 'Escape') { event.preventDefault(); finish(null); }
        else if (event.key === 'Enter') { event.preventDefault(); accept(); }
      };
      ok.onclick = accept;
      cancel.onclick = () => finish(null);
      overlay.onclick = (event) => { if (event.target === overlay) finish(null); };
      document.addEventListener('keydown', onKey, true);
      setTimeout(() => (withInput ? input : ok).focus(), 20);
    });
  }

  const askConfirm = (text, options) => askDialog(Object.assign({ text }, options || {}));
  const askInput = (title, value, label, confirmText) => askDialog({ title, value, label, confirmText: confirmText || '保存' });

  /**
   * 信息框：同风格弹窗里展示一段原文（样式/提示词集/预设的「查看」都走它），
   * 不再把结果丢到面板最下面的回执行里。
   */
  function showInfo(title, content, options) {
    const overlay = $('overlay');
    if (!overlay) return Promise.resolve(null);
    const ok = $('dialog-ok'), cancel = $('dialog-cancel'), input = $('dialog-input'), field = $('dialog-field');
    $('dialog-title').textContent = title || '查看';
    const text = $('dialog-text');
    text.textContent = (options && options.text) || '';
    text.classList.remove('danger');
    const pre = $('dialog-pre');
    pre.textContent = content || '（空）';
    pre.hidden = false;
    field.hidden = true;
    input.value = '';
    ok.textContent = (options && options.confirmText) || '关闭';
    ok.className = '';
    cancel.hidden = true;
    const restore = document.activeElement;
    overlay.hidden = false;
    return new Promise((resolve) => {
      const finish = () => {
        overlay.hidden = true;
        pre.hidden = true;
        cancel.hidden = false;
        document.removeEventListener('keydown', onKey, true);
        ok.onclick = null; cancel.onclick = null; overlay.onclick = null;
        if (restore && typeof restore.focus === 'function') restore.focus();
        resolve(true);
      };
      const onKey = (event) => {
        if (event.key === 'Escape' || event.key === 'Enter') { event.preventDefault(); finish(); }
      };
      ok.onclick = finish;
      overlay.onclick = (event) => { if (event.target === overlay) finish(); };
      document.addEventListener('keydown', onKey, true);
      setTimeout(() => ok.focus(), 20);
    });
  }

  // ---------------------------------------------------------------- 网络

  async function api(path, options = {}) {
    const response = await fetch(path, {
      method: options.method || (options.body ? 'POST' : 'GET'),
      headers: Object.assign({ 'Content-Type': 'application/json' }, state.token ? { Authorization: 'Bearer ' + state.token } : {}),
      body: options.body ? JSON.stringify(options.body) : undefined,
    });
    if (response.status === 401) { lock('令牌不正确，请重新输入。'); throw new Error('unauthorized'); }
    const text = await response.text();
    let payload = {};
    try { payload = text ? JSON.parse(text) : {}; } catch { payload = { error: text }; }
    if (!response.ok) throw new Error(payload.error || ('HTTP ' + response.status));
    return payload;
  }

  function lock(message) {
    state.token = '';
    localStorage.removeItem(TOKEN_KEY);
    document.querySelector('main').hidden = true;
    $('login').hidden = false;
    $('login-error').textContent = message || '';
    $('health').className = 'dot bad';
  }

  function unlock(token) {
    state.token = token;
    localStorage.setItem(TOKEN_KEY, token);
    $('login').hidden = true;
    $('app').hidden = false;
    $('login-error').textContent = '';
    $('health').className = 'dot ok';
  }

  // ---------------------------------------------------------------- 回执

  // 对话页底部的回执栏已经取消：聊天里只留对话本身，回执统一去「回执」页看。
  // 每个 box 都可能不存在（页面不同/HTML 又删了一处），下面一律先判空再判 active。
  const RECEIPT_BOXES = ['gen-receipts', 'prompt-receipts', 'style-receipts',
    'lora-receipts', 'function-receipts', 'chatcfg-receipts', 'system-receipts'];

  /** 面板底部那些回执栏里，此刻真正可见（所在 .panel 是 active）的几个；缺的、不在面板里的都跳过。 */
  function activeReceiptBoxes() {
    return RECEIPT_BOXES.filter((id) => {
      const box = $(id);
      const panel = box && box.closest ? box.closest('.panel') : null;
      return !!panel && panel.classList.contains('active');
    });
  }

  // ---------------------------------------------------------------- 任务回执（/quest/#N）

  /** 见过的图片数（用来判断"出图完成"该不该再冒一条云）。 */
  const questCloudSeen = new Map();

  /**
   * 任务信息云：从屏幕下方冒一条，带跳去 /quest/#N 的链接。
   * 任务下达时冒一条（普通样式），出图完成后再冒一条（done 样式，留久一点）。
   */
  function questCloud(text, number, options) {
    const box = $('quest-clouds');
    if (!box || !number) { toast(text); return; }
    const cloud = el('div', 'quest-cloud' + (options && options.done ? ' done' : ''));
    cloud.appendChild(el('div', 'quest-cloud-text', text));
    const link = el('a', 'quest-cloud-link', '查看回执 #' + number + ' →');
    link.href = '/quest#' + number;   // 不要写成 /quest/#N：那会变成 /quest/ 路径，服务端没有这条路由
    cloud.appendChild(link);
    box.appendChild(cloud);
    requestAnimationFrame(() => cloud.classList.add('show'));
    const ttl = options && options.done ? 15000 : 9000;
    setTimeout(() => { cloud.classList.remove('show'); setTimeout(() => cloud.remove(), 300); }, ttl);
  }

  const questWatch = { timer: null, number: 0 };

  /** 会话里记住「当前打开的回执号」与各处的滚动位置：切页面/切会话回来时接着看。 */
  const QUEST_CURRENT_KEY = 'pixiko-quest-current';
  const QUEST_SCROLL_PREFIX = 'pixiko-quest-scroll:';
  const CHAT_SCROLL_PREFIX = 'pixiko-chat-scroll:';
  const PAGE_SCROLL_PREFIX = 'pixiko-scroll:';
  /** 生成期间实时视图（进度卡 + 图集预览）的轮询间隔；与 questWatch.timer（900ms 回执轮询）各走各的表。 */
  const QUEST_LIVE_INTERVAL = 1500;
  /** 距底小于这么多像素就算「在底部」（切换回来仍然贴底跟随）。 */
  const STICK_BOTTOM_PX = 40;

  /** 会话存储读写：隐私模式/被禁用时一律吞掉异常，退化成「不记忆」，不能让整页崩掉。 */
  function storeGet(key) { try { return sessionStorage.getItem(key); } catch { return null; } }
  function storeSet(key, value) { try { sessionStorage.setItem(key, String(value)); } catch { /* 没有会话存储：不记忆 */ } }
  function storeJson(key) {
    const raw = storeGet(key);
    if (!raw) return null;
    try { const parsed = JSON.parse(raw); return parsed && typeof parsed === 'object' ? parsed : null; } catch { return null; }
  }

  /** 当前地址里的任务号（/quest/#22 → 22）；没有就是 0（表示"最新一条"）。 */
  function questNumberFromLocation() {
    const value = Number(String(location.hash || '').replace(/^#/, '').trim());
    return Number.isFinite(value) && value > 0 ? Math.floor(value) : 0;
  }

  /** 会话里记住的回执号（上次在回执页看的那条）。 */
  function questStoredNumber() {
    const value = Math.floor(Number(storeGet(QUEST_CURRENT_KEY)) || 0);
    return value > 0 ? value : 0;
  }

  function questStoreNumber(number) {
    const value = Math.floor(Number(number) || 0);
    if (value > 0) storeSet(QUEST_CURRENT_KEY, value);
  }

  /**
   * 回执页：跟着一条回执实时刷新（指令结果 + 生成图片都在里面）。
   * 跑完就停表；换任务号（点另一条云）会重新开始跟。
   * 没带 hash 时恢复会话里记住的那条（切页面回来不会掉回「最新一条」）。
   */
  async function loadQuest(number) {
    clearInterval(questWatch.timer);
    questWatch.number = Number(number) || questNumberFromLocation() || questStoredNumber() || 0;
    if (questWatch.number > 0) questStoreNumber(questWatch.number);
    const tick = async () => {
      let data;
      try { data = await api('/api/quest', { body: { id: questWatch.number } }); }
      catch (error) {
        clearInterval(questWatch.timer);
        if (String(error.message) !== 'unauthorized') setText('quest-state', '读取失败：' + error.message);
        return;
      }
      renderQuest(data);
      if (data.done && !data.busy) {
        clearInterval(questWatch.timer);
        renderQuestList();                 // 跑完了：列表里那条从「进行中…」变成已完成
        refreshQuestListQuietly();
      }
    };
    await tick();
    questWatch.timer = setInterval(tick, 900);
  }

  function renderQuest(data) {
    const body = $('quest-body');
    if (!body) return;
    const number = data.quest || questWatch.number;
    const running = !data.error && !(data.done && !data.busy);
    if (number > 0) questStoreNumber(number);          // 记住当前这条：下次回到 /quest 还是它
    // 换了一条回执（或压根没有回执号）：上一张的进度卡、图集、去重集合全部作废（提前重置，下面才好重建）。
    if (questLive.number !== number) questLiveReset(number);
    setText('quest-state', data.error ? data.error : '#' + number + (running ? '（进行中…）' : '（已完成）'));
    setText('quest-command', data.command ? '指令：' + data.command : '');
    setText('quest-progress', data.latest ? '最新一条是 #' + data.latest : '');
    // 重画前后把滚动位置接回来：本次就是按现在 DOM 里的位置，换页回来按会话里记的那份（之前在底部就保持贴底）。
    const here = { top: Number(body.scrollTop) || 0, atBottom: nodeAtBottom(body, STICK_BOTTOM_PX) };
    const restore = Number(body.scrollHeight) > 0 ? here : (questScrollRead(number) || here);
    body.innerHTML = '';
    if (data.error) {
      // 正文读不到（被清理/重启后）：旧后端会把「过期」当错误说，这里统一成人话；索引里还有摘要就一并给出。
      const listed = Array.isArray(state.questList) ? state.questList.find((item) => item && item.number === number) : null;
      const summary = String(data.summary || (listed && listed.summary) || '').trim();
      const notice = el('div', 'quest-step');
      notice.appendChild(el('div', 'head', '正文读不到'));
      notice.appendChild(el('div', 'quest-text', '这条回执的正文读不到了（磁盘上也没有）。'
        + (summary ? '\n摘要：' + summary : '')));
      body.appendChild(notice);
    } else {
      const groups = Array.isArray(data.messages) && data.messages.length ? data.messages : null;
      const steps = groups || (data.texts || []).map((text) => [{ type: 'text', text }]);
      // 同一条回执里的图片属于**一个图集**：不管分在 messages 的哪一步，都收进同一张图集卡（不重复渲染）。
      const files = [];
      steps.forEach((pieces) => (pieces || []).forEach((piece) => { if (piece.type === 'image' && piece.file) files.push(piece.file); }));
      if (!groups) (data.images || []).forEach((image) => { if (image && image.file) files.push(image.file); });
      // 生成期间提前预览到的新图（本页 1.5 秒轮询到的）也并进同一张图集：按 path 去重，重画不丢。
      const seenFiles = new Set(files.map(questFileKey));
      if (questLive.number === number) {
        questLive.extras.forEach((file) => {
          const fileKey = questFileKey(file);
          if (seenFiles.has(fileKey)) return;
          seenFiles.add(fileKey);
          files.push(file);
        });
      }
      if (!steps.length && !files.length) {
        body.appendChild(el('div', 'quest-empty', '这条任务还没有输出。'));
      }
      questLive.base = '任务 #' + number + ' 的图';
      questLive.startedAt = questStartedMillis(number, data);
      questLive.files = seenFiles;
      let picture = null;
      const picturesOnce = () => { if (!picture) picture = questPictureArea(files, questLive.base); return picture; };
      let placed = false;
      steps.forEach((pieces, index) => {
        const text = (pieces || []).filter((piece) => piece.type !== 'image' && piece.text).map((piece) => piece.text).join('\n');
        const hasPicture = !!groups && (pieces || []).some((piece) => piece.type === 'image' && piece.file);
        const galleryHere = hasPicture && !placed && files.length > 0;   // 图集放在第一处出现图片的位置
        if (!text && hasPicture && !galleryHere) return;                 // 图片已经被图集收走：这一步不再单独出一张空卡
        const step = el('div', 'quest-step');
        step.appendChild(el('div', 'head', groups ? '第 ' + (index + 1) + ' 步' : '输出'));
        if (text) {
          if (/(^|\n)[^\n]{0,16}(失败|错误|不正确|无效|超时|拒绝|找不到)[:：]/.test(text)) step.classList.add('err');
          step.appendChild(el('div', 'quest-text', text));
        }
        if (galleryHere) { placed = true; const area = picturesOnce(); if (area) step.appendChild(area); }
        body.appendChild(step);
      });
      if (!placed && files.length) { const area = picturesOnce(); if (area) body.appendChild(area); }
      if (running) body.appendChild(el('div', 'quest-empty', '（还在跑，实时刷新中…）'));
    }
    // 进度卡与生成期间的图集预览：起表/停表都在这里收口，卡片与图集都是就地更新，不重建 #quest-body。
    questLiveSync(number, running);
    questGalleryHead();
    questMountCard();
    scrollApply(body, restore);
  }

  // ---------------------------------------------------------------- 回执实时视图（进度卡 + 生成期间图集预览）

  /**
   * 当前回执的实时视图：`#quest-body` 顶部那张进度卡，以及生成期间提前到达的图片。
   * 只问 /api/progress（单张图内部的采样）、/api/tasks（图片级 done/total）、/api/images（一完成就出现的图），
   * 与 questWatch.timer 的 900ms 回执轮询完全独立：空闲就停表，绝不留悬挂的定时器。
   */
  const questLive = {
    number: 0, timer: null, ticked: false, openAt: 0,
    questRunning: false, tasksRunning: false,
    card: null, head: null, fill: null, note: null,
    gallery: null, single: null, singleFile: '', base: '',
    files: new Set(), extras: [], baseline: null, startedAt: 0,
  };

  /** 图片去重键：正反斜杠、绝对/相对都归一，回执正文里的 file 与 /api/images 的 path 能对上。 */
  function questFileKey(file) {
    return String(file == null ? '' : file).replace(/\\/g, '/').replace(/^.*?(data\/generated\/)/, '$1');
  }

  function questCount(value) {
    const number = Number(value);
    return Number.isFinite(number) && number > 0 ? Math.floor(number) : 0;
  }

  /** 这条回执是什么时候开始的：列表项的 startedAt 优先，退化到 ageMillis；都拿不到返回 0。 */
  function questStartedMillis(number, data) {
    const listed = Array.isArray(state.questList) ? state.questList.find((item) => item && Number(item.number) === Number(number)) : null;
    const raw = (listed && listed.startedAt) || (data && data.startedAt);
    if (raw !== undefined && raw !== null && raw !== '') {
      const parsed = typeof raw === 'number' ? raw : Date.parse(String(raw));
      if (Number.isFinite(parsed) && parsed > 0) return parsed;
    }
    const age = Number(data && data.ageMillis !== undefined ? data.ageMillis : (listed && listed.ageMillis));
    if (Number.isFinite(age) && age >= 0) return Date.now() - age;
    return 0;
  }

  /** 队列里该盯哪一条：优先当前这条回执（在跑，或队列里只有它），否则队列里正在跑的那条。 */
  function questTaskOf(tasks, number) {
    const list = Array.isArray(tasks) ? tasks.filter(Boolean) : (tasks && tasks.number !== undefined ? [tasks] : []);
    if (!list.length) return null;
    const mine = list.find((task) => Number(task.number) === Number(number));
    if (mine && (mine.running || list.length === 1)) return mine;
    return list.find((task) => task.running) || mine || (list.length === 1 ? list[0] : null);
  }

  /** 进度卡：一条回执只建一次，之后就地改文字与宽度（不重建 #quest-body，不打断正在播的图集）。 */
  function questBuildCard() {
    const card = el('div', 'quest-progress-card');
    const head = el('div', 'head');
    const track = el('div', 'quest-progress-bar');
    const fill = el('div', 'quest-progress-fill');
    const note = el('div', 'quest-progress-note');
    track.appendChild(fill);
    card.appendChild(head);
    card.appendChild(track);
    card.appendChild(note);
    note.hidden = true;
    // 这一类名 CSS 里还没有规则：卡片与进度条给最小内联样式，免得条高 0 看不见（后面有 CSS 也能盖外观）。
    card.style.border = '1px solid var(--line, rgba(120, 160, 220, .28))';
    card.style.borderRadius = '12px';
    card.style.padding = '10px 12px';
    card.style.background = 'linear-gradient(135deg, rgba(31, 44, 66, .5), rgba(23, 33, 51, .42))';
    card.style.display = 'flex';
    card.style.flexDirection = 'column';
    card.style.gap = '8px';
    head.style.fontSize = '12.5px';
    track.style.height = '6px';
    track.style.borderRadius = '999px';
    track.style.background = 'rgba(120, 160, 220, .22)';
    track.style.overflow = 'hidden';
    fill.style.height = '100%';
    fill.style.width = '0%';
    fill.style.borderRadius = '999px';
    fill.style.background = 'var(--accent, #5aa2ff)';
    fill.style.transition = 'width .3s ease';
    note.style.fontSize = '12px';
    note.style.color = 'var(--muted, #8fa4bf)';
    questLive.card = card; questLive.head = head; questLive.fill = fill; questLive.note = note;
    return card;
  }

  /** 进度卡文案与宽度：主条看图片级 done/total/percent，副标题看单张图内部的采样进度。 */
  function questPaintCard(task, progress) {
    const card = questLive.card, head = questLive.head, fill = questLive.fill, note = questLive.note;
    if (!card || !head || !fill || !note) return;
    const number = questLive.number;
    const running = questLive.questRunning || questLive.tasksRunning;
    const done = task ? questCount(task.done) : 0;
    const total = task ? questCount(task.total) : 0;
    let percent = task && Number.isFinite(Number(task.percent)) ? Number(task.percent) : (total ? Math.round(100 * done / total) : 0);
    percent = Math.max(0, Math.min(100, Math.round(percent)));
    let text = task
      ? '任务 #' + (Number(task.number) || number) + ' · 已生成 ' + done + '/' + total + ' 张 · ' + percent + '%'
      : '任务 #' + number + (running ? ' · 生成中…' : '');
    if (!running) {
      if (!task || (total > 0 && done >= total)) percent = 100;   // 真跑满了才把条推满（被取消的不假装 100%）
      text += ' · 已完成';
    }
    head.textContent = text;
    fill.style.width = percent + '%';
    card.classList.toggle('done', !running);
    // 副标题只放「单张图内部」的采样进度；SD 读不到（reachable=false）就不显示，免得拿它冒充任务进度。
    const steps = progress ? questCount(progress.steps) : 0;
    if (running && progress && progress.reachable !== false && steps > 0) {
      let sub = '采样中 ' + questCount(progress.step) + '/' + steps + '（' + Math.round(Number(progress.percent) || 0) + '%）';
      const eta = Math.round(Number(progress.etaSeconds) || 0);
      if (eta > 0) sub += ' · 预计 ' + eta + ' 秒';
      note.textContent = sub;
      note.hidden = false;
    } else {
      note.textContent = '';
      note.hidden = true;
    }
  }

  /** 图集表头：生成期间「已生成 N 张（生成中…）」，跑完回到「共 N 张」。 */
  function questGalleryHead() {
    const gallery = questLive.gallery;
    if (!gallery || !gallery.head) return;
    const count = gallery.items.length;
    gallery.head.textContent = (questLive.questRunning || questLive.tasksRunning)
      ? '图集 · 已生成 ' + count + ' 张（生成中…）'
      : '图集 · 共 ' + count + ' 张';
  }

  /** 把进度卡插到 #quest-body 顶部（已经在里面就不动它）。 */
  function questMountCard() {
    const body = $('quest-body'), card = questLive.card;
    if (!body || !card || card.parentNode === body) return;
    body.insertBefore(card, body.firstChild || null);
  }

  /** 生成期间新到的图：卡片就地追加，绝不重建 #quest-body（进度卡后面是它们的位置）。 */
  function questMountGallery(node) {
    const body = $('quest-body');
    if (!body || !node) return;
    const card = questLive.card && questLive.card.parentNode === body ? questLive.card : null;
    if (card) body.insertBefore(node, card.nextSibling || null);
    else body.insertBefore(node, body.firstChild || null);
  }

  /** 这张新图属于当前这条回执吗：modified 晚于回执开始时间；拿不到开始时间就退化成「本次打开后新出现的」。 */
  function questImageFresh(image, startedAt, baseline) {
    const path = String((image && image.path) || '');
    if (!path) return false;
    if (startedAt > 0) {
      const modified = Date.parse(String((image && image.modified) || ''));
      return Number.isFinite(modified) ? modified >= startedAt : true;
    }
    return baseline ? !baseline.has(path) : true;
  }

  /** 一张新图就地追加进这条回执的图集（单张卡会被就地升级成图集，已经加载好的图不重新加载）。 */
  function questAddImage(file) {
    const fileKey = questFileKey(file);
    if (questLive.files.has(fileKey)) return;
    questLive.files.add(fileKey);
    if (questLive.gallery) {
      questLive.gallery.add(file);
      questGalleryHead();
      return;
    }
    if (questLive.single && questLive.single.parentNode) {
      const gallery = createGallery({ base: questLive.base, cardClass: 'quest-step gallery-step',
        gridClass: 'gallery-grid', reuse: questLive.single, firstFile: questLive.singleFile || file });
      questLive.gallery = gallery;
      questLive.single = null;
      questLive.singleFile = '';
      gallery.add(file);
      questGalleryHead();
      return;
    }
    const gallery = createGallery({ base: questLive.base, cardClass: 'quest-step gallery-step',
      gridClass: 'gallery-grid', firstFile: file });
    questLive.gallery = gallery;
    questMountGallery(gallery.card);
    questGalleryHead();
  }

  /** /api/images 里属于这条回执、还没画过的图 → 增量追加（按 path 去重，绝不出现重复项）。 */
  function questAppendImages(payload) {
    const list = payload && Array.isArray(payload.images) ? payload.images : null;
    if (!list) return;
    if (!questLive.baseline) questLive.baseline = new Set(list.map((image) => String((image && image.path) || '')));
    const fresh = [];
    list.forEach((image) => {
      const path = String((image && image.path) || '');
      if (!path) return;
      const fileKey = questFileKey(path);
      if (questLive.files.has(fileKey)) return;                              // 回执正文里已经有这张
      if (fresh.some((item) => questFileKey(item) === fileKey)) return;      // 这一批里重复的
      if (!questImageFresh(image, questLive.startedAt, questLive.baseline)) return;
      fresh.push(path);
    });
    fresh.reverse();                                                         // 接口是最新在前：反过来才是生成顺序
    fresh.forEach((path) => { questLive.extras.push(path); questAddImage(path); });
  }

  /** 生成期间的一轮：进度（/api/progress + /api/tasks）与提前预览（/api/images）。 */
  async function questLiveTick() {
    const number = questLive.number;
    if (!number) { questLiveStop(); return; }
    const [progress, tasks, images] = await Promise.all([
      api('/api/progress').catch(() => null),
      api('/api/tasks').catch(() => null),
      api('/api/images?limit=40').catch(() => null),
    ]);
    if (questLive.number !== number) return;                                 // 期间切到别的回执：这一轮作废
    const list = Array.isArray(tasks) ? tasks : (tasks && tasks.number !== undefined ? [tasks] : []);
    questLive.tasksRunning = list.some((task) => task && task.running);
    if ((questLive.questRunning || questLive.tasksRunning) && !questLive.card) { questBuildCard(); questMountCard(); }
    questPaintCard(questTaskOf(list, number), progress);
    questAppendImages(images);
    questGalleryHead();
    if (!questLive.questRunning && !questLive.tasksRunning) questLiveStop();  // 空闲：卡片留在原地标「已完成」，表停掉
  }

  function questLiveStart() {
    questLive.ticked = true;
    if (questLive.timer === null) questLive.timer = setInterval(questLiveTick, QUEST_LIVE_INTERVAL);
    questLiveTick();
  }

  /** 只清这一个定时器；questWatch.timer（900ms 回执轮询）不归它管。 */
  function questLiveStop() {
    if (questLive.timer !== null) { clearInterval(questLive.timer); questLive.timer = null; }
  }

  /** 换了一条回执：上一张的进度、图集、去重集合全部作废重来。 */
  function questLiveReset(number) {
    questLiveStop();
    questLive.number = number;
    questLive.ticked = false;
    questLive.openAt = Date.now();
    questLive.questRunning = false;
    questLive.tasksRunning = false;
    questLive.card = null; questLive.head = null; questLive.fill = null; questLive.note = null;
    questLive.gallery = null; questLive.single = null; questLive.singleFile = '';
    questLive.files = new Set();
    questLive.extras = [];
    questLive.baseline = null;
    questLive.startedAt = 0;
  }

  /**
   * 每次重画回执后同步一次：换了回执就整个重来；这条在跑（或这一条还没问过一轮）就把表起上，
   * 空闲就让这一轮自己收尾。`ticked` 保证「跑完停表」之后不会每 900ms 又白问一轮。
   */
  function questLiveSync(number, questRunning) {
    if (!number) { questLiveStop(); return; }
    if (questLive.number !== number) questLiveReset(number);
    questLive.questRunning = !!questRunning;
    if (questLive.timer !== null) return;
    if (questRunning || !questLive.ticked) questLiveStart();
  }

  /** 节点是不是贴底（距底小于 slack 像素）。 */
  function nodeAtBottom(node, slack) {
    if (!node) return true;
    const height = Number(node.scrollHeight) || 0;
    if (height <= 0) return true;
    return height - (Number(node.scrollTop) || 0) - (Number(node.clientHeight) || 0) < (slack || 0);
  }

  /** 把记下的偏移贴回节点：之前在底部就保持贴底。 */
  function scrollApply(node, saved) {
    if (!node || !saved) return;
    const height = Number(node.scrollHeight) || 0;
    const top = Math.max(0, Number(saved.top) || 0);
    node.scrollTop = saved.atBottom ? height : (height > 0 ? Math.min(top, height) : top);
  }

  /** 回执正文的滚动偏移（按回执号记）。 */
  function questScrollRead(number) {
    const saved = storeJson(QUEST_SCROLL_PREFIX + number);
    if (!saved) return null;
    return { top: Math.max(0, Number(saved.top) || 0), atBottom: saved.atBottom !== false };
  }

  let questScrollSaved = { number: 0, top: 0, atBottom: true };

  function questScrollPersist() {
    const body = $('quest-body');
    const number = questWatch.number || questLive.number;
    if (!body || !number) return;
    const top = Math.max(0, Number(body.scrollTop) || 0);
    const atBottom = nodeAtBottom(body, STICK_BOTTOM_PX);
    if (questScrollSaved.number === number && questScrollSaved.top === top && questScrollSaved.atBottom === atBottom) return;
    questScrollSaved = { number, top, atBottom };
    storeSet(QUEST_SCROLL_PREFIX + number, JSON.stringify({ top, atBottom }));
  }

  /** 对话滚动偏移（按会话 scope 记，切会话互不污染）。 */
  function chatScrollRead() {
    const saved = storeJson(CHAT_SCROLL_PREFIX + (scope() || 'default'));
    if (!saved) return null;
    return { top: Math.max(0, Number(saved.top) || 0), atBottom: saved.atBottom !== false };
  }

  let chatScrollSaved = { scope: '', top: -1, atBottom: false };

  function chatScrollPersist() {
    const log = $('chat-log');
    if (!log) return;
    const current = { scope: scope() || 'default', top: Math.max(0, Number(log.scrollTop) || 0), atBottom: nodeAtBottom(log, STICK_BOTTOM_PX) };
    if (chatScrollSaved.scope === current.scope && chatScrollSaved.top === current.top && chatScrollSaved.atBottom === current.atBottom) return;
    chatScrollSaved = current;
    storeSet(CHAT_SCROLL_PREFIX + current.scope, JSON.stringify({ top: current.top, atBottom: current.atBottom }));
  }

  /**
   * 给对话里已经渲染出来的图片各挂一次性贴底校准（load/error 各一次，不是死循环）：
   * <img> 是异步撑高的，加载完再把滚动条压回底部，视图才不会被顶走。
   */
  function chatStickImages(log) {
    if (!log || !log.querySelectorAll) return;
    log.querySelectorAll('img').forEach((img) => {
      if (!img || img.__chatStick) return;
      img.__chatStick = true;
      const stick = () => { img.__chatStick = false; log.scrollTop = Number(log.scrollHeight) || 0; };
      img.addEventListener('load', stick, { once: true });
      img.addEventListener('error', stick, { once: true });
    });
  }

  /**
   * 恢复对话滚动：之前在底部就贴底，并给异步加载的图片挂一次性校准；
   * 不在底部就原样恢复偏移，不打扰用户正在看的地方。
   */
  function chatScrollRestore(log, saved) {
    if (!log) return;
    const atBottom = !saved || saved.atBottom !== false;
    scrollApply(log, saved || { top: 0, atBottom: true });
    if (atBottom) chatStickImages(log);
  }

  /** 其它页面的窗口滚动：按 location.pathname 各记一份（最小实现；值没变就不重复写）。 */
  let pageScrollSaved = -1;

  function pageScrollPersist() {
    const top = Math.round(Number(window.scrollY) || 0);
    if (top === pageScrollSaved) return;
    pageScrollSaved = top;
    storeSet(PAGE_SCROLL_PREFIX + (location.pathname || '/'), top);
  }

  function pageScrollRestore() {
    const raw = storeGet(PAGE_SCROLL_PREFIX + (location.pathname || '/'));
    if (raw === null || raw === undefined || raw === '') return;
    const top = Number(raw);
    if (!Number.isFinite(top) || top <= 0) return;
    if (typeof window.scrollTo === 'function') window.scrollTo(0, top);
  }

  /**
   * 一条回执的图片区：1 张还是那张单张图片卡，2 张以上合成一张图集卡。
   * 与 {@link pictureArea} 的样子一致，另外把图集对象记进 questLive，生成期间的新图才能就地追加上去。
   */
  function questPictureArea(files, base) {
    const list = (files || []).filter(Boolean);
    if (!list.length) return null;
    if (list.length === 1) {
      const box = el('div', 'quest-images');
      box.appendChild(imageNode(imageUrl(list[0]), base, 'quest-image'));
      questLive.gallery = null;
      questLive.single = box;
      questLive.singleFile = list[0];
      return box;
    }
    const gallery = createGallery({ base, cardClass: 'quest-step gallery-step', gridClass: 'gallery-grid', firstFile: list[0] });
    list.slice(1).forEach((file) => gallery.add(file));
    questLive.gallery = gallery;
    questLive.single = null;
    questLive.singleFile = '';
    return gallery.card;
  }

  // ---------------------------------------------------------------- 回执列表（/quest 左栏）与未读标记

  /**
   * 页签徽标上的未读数：只认 `state.quests.unread`（服务端的未读总数）。
   * 三处会刷新它：/api/status 的轻量轮询、/api/quests 列表头部的总数、/api/quests/read 标记后的新值。
   * 不按列表里的行数去数——列表可能落后于服务端（少报），也可能还挂着刚点掉的那一行（多报）。
   */
  function questUnreadCount() {
    return Math.max(0, Number(state.quests.unread) || 0);
  }

  /** 相对时间：刚刚 / 3 分钟前 / 2 小时前（超过一天就退回本地绝对时间）。 */
  function relativeTime(millis) {
    const value = Number(millis);
    if (!Number.isFinite(value) || value < 0) return '未知';
    if (value < 60_000) return '刚刚';
    const minutes = Math.floor(value / 60_000);
    if (minutes < 60) return minutes + ' 分钟前';
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return hours + ' 小时前';
    const days = Math.floor(hours / 24);
    if (days < 7) return days + ' 天前';
    const when = new Date(Date.now() - value);
    return when.toLocaleDateString('zh-CN');
  }

  function questListState(text) {
    const node = $('quest-list-state');
    if (!node) return;
    node.textContent = text || '';
    node.hidden = !text;
  }

  /** 把整张列表刷成"已读"（本地），未读点立刻消失。 */
  function clearLocalUnread() {
    if (!Array.isArray(state.questList)) return;
    state.questList.forEach((item) => { if (item) item.unread = false; });
    state.quests.unread = 0;
    renderQuestList();
  }

  /** 单条标为已读：本地先落，再让后端确认；失败就把点补回来（列表与徽标同步）。 */
  async function readQuests(payload) {
    if (payload && payload.all) {
      clearLocalUnread();
      renderQuestTabBadge();
    } else if (payload && Array.isArray(payload.numbers) && Array.isArray(state.questList)) {
      payload.numbers.forEach((number) => {
        const item = state.questList.find((row) => row && row.number === number);
        if (item) item.unread = false;
      });
      // 先把本地总数减一（徽标立刻掉一格），/api/quests/read 的返回值随后会覆盖成权威值。
      state.quests.unread = Math.max(0, (Number(state.quests.unread) || 0) - payload.numbers.length);
      renderQuestList();
    }
    try {
      const data = await api('/api/quests/read', { body: payload || { all: true } });
      if (data && Number.isFinite(Number(data.unread))) state.quests.unread = Number(data.unread);
      renderQuestTabBadge();
      return data;
    } catch (error) {
      if (String(error.message) !== 'unauthorized') {
        // 后端不认这个接口（旧版本）：本地按已读处理就行，不要把点又弹回来，也不再重拉列表。
        toast('标记已读没有成功：' + error.message);
      }
      return null;
    }
  }

  /**
   * 拉全部回执列表（**只读，不隐式改已读** —— 一行在还没被点开之前必须是"未读"）。
   * 旧后端没有 /api/quests 时给出提示并优雅降级，其余功能照常。
   */
  async function loadQuestList() {
    if (!Array.isArray(state.questList)) questListState('正在读取回执列表…');
    const data = await api('/api/quests?limit=50');
    if (!data || !Array.isArray(data.quests)) throw new Error('bad payload');   // 旧后端返回 {} 时也走这里
    state.questList = data.quests;
    // unread 总数以服务端为准（列表里可能还有没带 unread 的条目），列表只用来画每行的点。
    if (Number.isFinite(Number(data.unread))) state.quests.unread = Number(data.unread);
    else state.quests.unread = state.questList.filter((item) => item && item.unread).length;
    if (Number.isFinite(Number(data.latest))) state.quests.latest = Number(data.latest);
    if (Number.isFinite(Number(data.retainedMinutes))) state.quests.retainedMinutes = Number(data.retainedMinutes);
    state.questListError = '';
    renderQuestList();
    renderQuestTabBadge();
    return data;
  }

  function questListFailed(error) {
    const message = String((error && error.message) || error || '');
    if (message === 'unauthorized') return;               // 401 由 api() 弹回登录页
    state.questList = null;
    state.questListError = '列表接口不可用（' + message + '）';
    const box = $('quest-list');
    if (box) box.innerHTML = '';                          // 先把上一次的行清掉，再画降级提示
    renderQuestList();
    renderQuestTabBadge();
    if (!state.questListWarned) {                          // 旧后端：只提示一次，别每 30 秒吵一遍
      state.questListWarned = true;
      toast('回执列表接口不可用，单条回执照常可看。');
    }
  }

  /** 末位刷新：列表里有"进行中"的条目就顺带更新一下状态（不重复标已读）。 */
  async function refreshQuestListQuietly() {
    if (!Array.isArray(state.questList)) return;
    if (!(PAGE === 'quest' && document.visibilityState !== 'hidden')) return;
    if (!state.questList.some((item) => item && (item.busy || (item.done === false && !item.expired)))) return;
    try { await loadQuestList(); } catch { /* 旧后端/断网：下一次动作再刷新 */ }
  }

  /**
   * 回执未读数的轻量轮询：每 30 秒问一次 /api/status（**不拉列表**），
   * 有新回执时页签徽标就涨。标签页切回来立刻问一次；页面隐藏时完全不动。
   */
  async function pollQuestStatusQuietly() {
    if (!state.token || document.visibilityState === 'hidden') return;
    try {
      const status = await api('/api/status');
      const quests = status.quests || {};
      // 这里**只认服务端的数**：列表是上一次拉的，可能已经过期；下一行才是真相。
      if (Number.isFinite(Number(quests.unread))) state.quests.unread = Number(quests.unread);
      if (Number.isFinite(Number(quests.latest))) state.quests.latest = Number(quests.latest);
      renderQuestTabBadge();
      if (PAGE === 'quest') await refreshQuestListQuietly();
    } catch { /* 旧后端/断网/未登录：静默，等下一次 */ }
  }

  /** 「回执」页签上的未读数徽标（所有栏目都会跟着 /api/status 更新）。 */
  function renderQuestTabBadge() {
    const badge = $('quest-tab-badge');
    const tab = $('tab-quest');
    if (!badge) return;
    const count = questUnreadCount();
    badge.hidden = count <= 0;
    badge.textContent = count > 99 ? '99+' : String(count);
    badge.title = count > 0 ? count + ' 条未查看的回执' : '';
    if (tab) tab.setAttribute('aria-label', '回执' + (count > 0 ? '（' + count + ' 条未查看）' : ''));
  }

  function renderQuestList() {
    const box = $('quest-list');
    if (!box) return;
    box.innerHTML = '';
    const unread = questUnreadCount();
    if (state.questListError) {
      questListState(state.questListError);
      const hint = el('div', 'quest-empty', '列表接口不可用：单条回执照样看（从右下角的回执云，或直接打开 /quest#编号）。');
      hint.setAttribute('data-quest', 'list-unavailable');
      box.appendChild(hint);
      renderQuestTabBadge();
      return;
    }
    if (!Array.isArray(state.questList)) { questListState('正在读取回执列表…'); return; }
    const retained = state.quests.retainedMinutes;
    questListState(state.questList.length
      ? '共 ' + state.questList.length + ' 条' + (unread ? ' · 未读 ' + unread : '') + (retained ? ' · 正文保留 ' + retained + ' 分钟' : '')
      : '还没有任何回执。');
    if (!state.questList.length) {
      box.appendChild(el('div', 'quest-empty', '下达一条指令后，回执会出现在这里。'));
      renderQuestTabBadge();
      return;
    }
    const current = questWatch.number || questNumberFromLocation();
    state.questList.forEach((item) => {
      if (!item) return;
      const number = Number(item.number);
      const running = !item.done || item.busy;
      // 「过期」不再是一个界面分支：后端马上会取消过期（正文落盘、expired 永远 false），
      // 就算旧后端仍给 expired:true，这里也按普通行渲染，不再显示「已过期」。
      const row = el('button', 'quest-row' + (item.unread ? ' unread' : '') + (running ? ' running' : '')
        + (number === current ? ' current' : ''));
      row.type = 'button';
      row.setAttribute('role', 'listitem');
      row.setAttribute('data-number', String(number));
      row.setAttribute('aria-label', '回执 #' + number + '：' + (item.command || '（无指令）')
        + '，' + (running ? '进行中' : relativeTime(item.ageMillis)) + (item.unread ? '，未读' : ''));
      if (number === current) row.setAttribute('aria-current', 'true');
      row.appendChild(el('span', 'quest-dot', item.unread ? '' : null));   // 未读圆点（已读时保持占位，行高不跳）
      const main = el('div', 'quest-row-main');
      const line = el('div', 'quest-row-line');
      line.appendChild(el('span', 'quest-row-num', '#' + number));
      line.appendChild(el('span', 'quest-row-cmd', item.command || '（无指令）'));
      // 未读条目的消息条数（文字 + 图片）以数字呈现；0 条就不占位（后端只有 texts>0 才算未读）。
      const unreadMessages = Math.max(0, (Number(item.texts) || 0) + (Number(item.images) || 0));
      if (item.unread && unreadMessages > 0) {
        const pill = el('span', 'quest-unread-count', String(unreadMessages));
        pill.title = '未读 ' + unreadMessages + ' 条消息';
        pill.setAttribute('aria-label', '未读 ' + unreadMessages + ' 条消息');
        line.appendChild(pill);
      }
      line.appendChild(el('span', 'quest-row-state', running ? '进行中…' : relativeTime(item.ageMillis)));
      if (running) line.appendChild(el('span', 'spin quest-row-spin'));
      main.appendChild(line);
      main.appendChild(el('div', 'quest-row-summary', item.summary || (running ? '（还在跑，暂无摘要）' : '（没有摘要）')));
      row.appendChild(main);
      row.addEventListener('click', () => openQuest(number));
      box.appendChild(row);
    });
    renderQuestTabBadge();
  }

  /** 点列表里的一行：换地址（可分享/可刷新）→ 打开那条 → 标为已读（点立刻消失）。 */
  function openQuest(number) {
    const value = Number(number);
    if (!Number.isFinite(value) || value <= 0) return;
    if (questNumberFromLocation() !== Math.floor(value)) history.replaceState(null, '', '/quest#' + Math.floor(value));
    questWatch.number = Math.floor(value);              // 先认下来：下面的列表重画得知道哪一行是"当前"
    loadQuest(Math.floor(value));                       // 内含 POST /api/quest {id}，后端会把它标为已读
    readQuests({ numbers: [Math.floor(value)] });       // 前端不等后端：列表上的未读点立刻消失
  }

  /** 列表卡头部的两个按钮。 */
  function bindQuestListActions() {
    on('quest-list-refresh', 'click', () => {
      // 「刷新」一次刷两样：左栏列表 + 右栏当前打开的那条回执。
      // 还没打开过任何一条（questWatch.number 是 0）就只刷列表，不空跳也不报错。
      const current = Number(questWatch.number) || 0;
      if (current > 0) loadQuest(current).catch(() => {});   // loadQuest 自己会先清掉旧 timer，不会留下两条轮询
      else clearInterval(questWatch.timer);                  // 没跟任何一条：只把可能残留的 timer 收干净
      loadQuestList()
        .then((data) => toast('回执列表已刷新，共 ' + data.quests.length + ' 条。'))
        .catch((error) => { questListFailed(error); toast('刷新失败：' + error.message); });
    });
    on('quest-list-all', 'click', () => {
      if (!Array.isArray(state.questList)) { toast('列表还没读出来，先点「刷新」。'); return; }
      if (!questUnreadCount()) { toast('没有未读的回执。'); return; }
      readQuests({ all: true })
        .then((data) => {
          if (data && Number.isFinite(Number(data.marked))) toast('已把 ' + data.marked + ' 条标为已读。');
          return loadQuestList();
        })
        .catch((error) => toast('操作失败：' + error.message));
    });
  }

  /**
   * 回执渲染：**面板底部不再追加"指令 · …"那张卡**。结果由面板自己刷新 + 右上角 toast 呈现，
   * 想看原文就在控制台（系统页）或终端里敲同一条指令——那里是完整输出。
   *
   * <p>仍然留卡的两类：**报错**（失败不能没声）和**图片**（出图/领取的图就得在这儿看）。
   * 只追加新到的内容，不清空重建，否则整块会一直闪。state.receipt 记住当前回执 id 与已渲染条数。
   */
  function renderCapture(capture) {
    const key = String(capture.id || capture.command || '');
    const texts = capture.texts || [], images = capture.images || [];
    // 图片卡的标题（面板回执栏与对话页共用同一句）。
    const pictureBase = capture.command ? '指令 · ' + capture.command + ' 的图' : '生成结果图';
    if (state.receiptKey !== key) {
      state.receiptKey = key; state.receiptTexts = 0; state.receiptImages = 0; state.receiptCount = 0; state.receiptBox = null;
      state.receiptGallery = null; state.receiptSingle = null; state.receiptSingleFile = '';
    }
    for (const id of RECEIPT_BOXES) {
      const box = $(id);
      if (!box || !box.closest) continue;                          // 这一栏没有回执栏（HTML 里已经删了）：跳过
      const panel = box.closest('.panel');
      if (!panel || !panel.classList.contains('active')) continue;
      if (state.receiptBox !== box) {
        box.innerHTML = '';
        state.receiptTexts = 0; state.receiptImages = 0; state.receiptCount = 0;
        state.receiptGallery = null; state.receiptSingle = null; state.receiptSingleFile = '';
      }
      // 认下当前这条回执画在哪张卡里：下一次同一回执的轮询就只追加新内容。
      // （以前这里漏了赋值，等于每轮都把整块清空重建 —— 出图时图集/图片会跟着闪。）
      state.receiptBox = box;
      // 判"是不是报错"看**短标签＋冒号**（"操作失败："／"刷新/确认本机标签失败："）。
      // 不能见到"失败"就判错：成功回执里也有"失败 0"这种统计字样。
      for (let index = state.receiptTexts; index < texts.length; index++) {
        const text = texts[index];
        if (!/(^|\n)[^\n]{0,16}(失败|错误|不正确|无效|超时|拒绝|找不到)[:：]/.test(text)) continue;
        const card = el('div', 'receipt err');
        card.appendChild(el('div', 'head', capture.command ? '指令 · ' + capture.command : '执行回执'));
        card.appendChild(el('div', null, text));
        box.appendChild(card);
      }
      // 图片：同一条回执里的图属于**一个图集** —— 2 张以上只出一张卡，1 张时还是原来那张单张图片卡。
      // 增量（图片是陆续到的）：第一张先按单张出；第二张到达时**复用同一张卡**就地改成图集
      // （卡里那张已经加载好的 <a>/<img> 直接挪进网格，图不重新加载、位置不跳），之后每来一张
      // 只往网格里 append 一个缩略图并改表头张数：卡片节点从头到尾是同一个，滚动与其它卡片都不受影响。
      for (let index = state.receiptImages; index < images.length; index++) {
        const file = images[index].file;
        if (images.length < 2) {
          // 生成的图片只在这里出现一次；回溯/领取的图也走它，点一下弹查看器放大。
          const card = el('div', 'receipt ok image-receipt');
          card.appendChild(imageNode(imageUrl(file), shortName(file), 'receipt-image'));
          box.appendChild(card);
          state.receiptSingle = card;
          state.receiptSingleFile = file;
          continue;
        }
        let gallery = state.receiptGallery;
        if (!gallery) {
          const reuse = state.receiptSingle && state.receiptSingle.parentNode === box ? state.receiptSingle : null;
          const first = reuse ? (state.receiptSingleFile || file) : file;
          gallery = createGallery({ base: pictureBase, cardClass: 'receipt ok image-receipt gallery-receipt',
            gridClass: 'gallery-grid', reuse, firstFile: first });
          if (!reuse) box.appendChild(gallery.card);
          state.receiptGallery = gallery;
          state.receiptSingle = null;
          state.receiptSingleFile = '';
          if (reuse) gallery.add(file);   // 复用的那张卡已经是第 1 张，当前这张得补进网格
          continue;
        }
        gallery.add(file);
      }
      state.receiptTexts = Math.max(state.receiptTexts, texts.length);
      state.receiptImages = Math.max(state.receiptImages, images.length);
      const spinner = box.querySelector('.receipt.pending');
      if (capture.busy && !spinner) {
        const card = el('div', 'receipt pending');
        card.appendChild(el('span', 'spin'));
        card.appendChild(document.createTextNode(' 执行中…'));
        box.appendChild(card);
      } else if (!capture.busy && spinner) spinner.remove();
      // 新回执用信息云提示一次（同一条回执只提示一次）。
      if (texts.length && state.receiptToasted !== key) {
        state.receiptToasted = key;
        toast(texts[texts.length - 1].split('\n')[0]);
      }
    }
    // 对话页：图片还必须落进 #chat-log —— 对话页底部的回执栏已经取消（HTML 里没有 #chat-receipts），
    // 这里再不管，图片在对话页就没有任何落点了。文字配图的那条消息由 appendMessage 画进消息里
    // （并已记进快照），这里靠快照去重，同一张图不会画两回：只补「聊天那边还没画过」的图。
    // **只动对话页**：其它面板底部的 receipts 行为一个字没改（见上面的循环）。
    if (PAGE === 'chat' && images.length) {
      const seen = chatSnapshotImages();
      const missing = [];
      images.forEach((image) => {
        const file = image && image.file ? String(image.file) : '';
        if (file && !seen.has(file) && missing.indexOf(file) < 0) missing.push(file);
      });
      if (missing.length) chatEntryAdd({ role: 'bot', text: pictureBase, images: missing });
    }
  }

  /**
   * 图片地址：本地路径走 /api/image。但机器人发给 QQ 的封面是**图床 URL**
   * （回执里存的是 `https://image.civitai.com/…`），套到 /api/image 上必然取不到图——这里改走封面代理。
   * 代理只认 Civitai 图床，兜底见 {@link imageNode} 里的加载失败重试。
   */
  function imageUrl(file) {
    const value = String(file || '');
    if (/^https?:\/\//i.test(value)) return coverUrl(value);
    const path = value.replace(/\\/g, '/').replace(/^.*?(data\/generated\/)/, '$1');
    return '/api/image?token=' + encodeURIComponent(state.token) + '&path=' + encodeURIComponent(path);
  }

  // ---------------------------------------------------------------- 图集（一条回执里的多张图 = 一个图集）

  /** 路径 → 文件名（图片说明用）。 */
  function shortName(file) { return String(file || '').replace(/^.*[\\/]/, ''); }

  /** 缩略图 / 查看器的说明文字：第几张 + 文件名（alt 要有意义）。 */
  function imageAlt(base, index, file) {
    const name = shortName(file).split('?')[0];
    return (base ? base + ' ' : '') + '第 ' + (index + 1) + ' 张' + (name ? '：' + name : '');
  }

  /** <a> 当前真正在显示的地址（封面兜底可能换过）。 */
  function viewerSrcOf(link) {
    const img = link && link.querySelector('img');
    return (img && img.getAttribute('src')) || (link && link.getAttribute('href')) || '';
  }

  /**
   * 一个图集卡片：表头「图集 · 共 N 张（点击看大图）」+ 缩略图网格，点任意一张用查看器翻整组。
   * `items` 是**活的**数组（缩略图点击的那一刻才交给查看器），所以后到的图也能翻到。
   *
   * 增量渲染靠它：`add()` 只往网格里 append 一张缩略图并就地改表头张数，卡片节点从头到尾是同一个 ——
   * 图片陆续到达时不会整块重建，滚动位置与其它卡片都不受影响。
   * `reuse` 用来把已经渲染好的单张图片卡**就地**改成图集：卡片节点不换，里面那张已经加载好的
   * <a>/<img> 直接挪进网格（图不重新加载，页面上也不闪）。
   *
   * @param {{base?:string, cardClass:string, gridClass?:string, reuse?:object, firstFile?:string}} options
   */
  function createGallery(options) {
    const base = options.base || '';
    const card = options.reuse || el('div', options.cardClass);
    const moved = options.reuse ? options.reuse.querySelector('a.image-link') : null;   // 单张卡里那张已经加载好的图
    card.className = options.cardClass;
    card.innerHTML = '';                                    // 只清内容：节点本身（位置、滚动）不动
    const head = el('div', 'head');
    const grid = el('div', options.gridClass || 'gallery-grid');
    card.appendChild(head);
    card.appendChild(grid);
    const items = [];
    const syncHead = () => { head.textContent = '图集 · 共 ' + items.length + ' 张（点击看大图）'; };
    const gallery = {
      card, head, grid, items,
      /** 追加一张缩略图；`existing` 是要复用/挪进来的那个 <a>。 */
      add(file, alt, existing) {
        const index = items.length;
        const caption = alt || imageAlt(base, index, file);
        const src = existing ? (viewerSrcOf(existing) || imageUrl(file)) : imageUrl(file);
        items.push({ src, caption });
        let link = existing || null;
        if (link) {
          link._viewerList = items;                         // 接管成图集里的第 index 张（点击行为本来就读这两项）
          link._viewerIndex = index;
          link.classList.add('gallery-thumb');
          const img = link.querySelector('img');
          if (img) { img.className = 'gallery-thumb-image'; img.alt = caption; img.loading = 'lazy'; }
        } else {
          link = imageNode(src, caption, 'gallery-thumb-image', items, index);
          link.classList.add('gallery-thumb');
        }
        link.title = caption + ' · 点击看大图';
        grid.appendChild(link);
        syncHead();
        return index;
      }
    };
    if (options.firstFile) gallery.add(options.firstFile, null, moved);
    syncHead();
    return gallery;
  }

  /**
   * 一条回执的图片区：**1 张**还是原来那张单张图片卡（说明文字照旧，不套图集壳），
   * **2 张以上**合成只有一张卡的图集。回执页（#quest-body）与面板底部回执栏都按这个判定，样子才一致。
   */
  function pictureArea(files, base, options) {
    const list = (files || []).filter(Boolean);
    if (!list.length) return null;
    if (list.length === 1) {
      const box = el('div', options.singleClass);
      box.appendChild(imageNode(imageUrl(list[0]), base, options.imageClass));
      return box;
    }
    const gallery = createGallery({ base, cardClass: options.cardClass, gridClass: options.gridClass, firstFile: list[0] });
    list.slice(1).forEach((file) => gallery.add(file));
    return gallery.card;
  }

  /** 回执里新到的一条消息 → 对话里的一条机器人消息（文字在上、图片在下，同属一条）。 */
  function appendCaptureGroup(segments) {
    const texts = [], images = [];
    (segments || []).forEach((segment) => {
      if (segment.type === 'image' && segment.file) images.push({ file: segment.file });
      else if (segment.text) texts.push(segment.text);
    });
    if (!texts.length && !images.length) return;
    appendMessage('bot', texts.join('\n') || '（一张图片）', images);
  }

  async function pollCapture(id, attempt = 0, follow = false) {
    clearTimeout(state.pollTimer);
    try {
      const capture = await api('/api/capture', { body: { id } });
      // 机器人发的每条消息都直接回到对话里：一条消息 = 一条聊天记录（文字配着自己的图）。
      // 后端给了 messages 就按它分条；旧格式（只有扁平 texts/images）仍走「图片好了」那条老路。
      // 先把消息画进对话、再 renderCapture：对话页的图片补漏（renderCapture 末尾那段）靠快照去重，
      // 顺序反了会先补一张图、再画一条带同样图的消息 —— 同一张图就画两回了。
      // 这一步只写 #chat-log（别的栏目没有它，等于空转），面板 receipts 的行为一个字没变。
      const groups = Array.isArray(capture.messages) && capture.messages.length ? capture.messages : null;
      if (groups) {
        const seenGroups = state.seenGroups.get(id) || 0;
        if (groups.length > seenGroups) {
          for (let index = seenGroups; index < groups.length; index++) appendCaptureGroup(groups[index]);
          state.seenGroups.set(id, groups.length);
          loadImages().catch(() => {});
        }
      } else {
        const images = capture.images || [];
        const seen = state.seenImages.get(id) || 0;
        if (images.length > seen) {
          state.seenImages.set(id, images.length);
          appendMessage('bot', '图片好了，直接发在这里：', images.slice(seen));
          loadImages().catch(() => {});
        }
      }
      renderCapture(capture);
      // 出图成功再冒一条信息云（第一次看到新图片时）：任务回执里已经附了图，这里只负责提醒。
      const imageCount = (capture.images || []).length;
      const seenImages = questCloudSeen.get(id) || 0;
      if (capture.quest && imageCount > seenImages) {
        questCloudSeen.set(id, imageCount);
        if (seenImages > 0 || imageCount > 0) {
          questCloud('任务 #' + capture.quest + ' 出图完成：' + imageCount + ' 张', capture.quest, { done: true });
        }
      }
      const busy = capture.busy || (!capture.closed && capture.ageMillis < 1200 && attempt < 3);
      if (attempt % 4 === 0) await loadStatus().catch(() => {});
      const generating = !!state.status?.generation?.status;
      const followOn = follow && (busy || generating || Date.now() < state.followUntil);
      if ((busy || followOn) && attempt < 2400) {
        state.pollTimer = setTimeout(() => pollCapture(id, attempt + 1, follow), busy ? 900 : 2500);
      }
    } catch (error) {
      if (String(error.message) !== 'unauthorized') toast('读取回执失败：' + error.message);
    }
  }

  async function runCommands(commands, after, follow = false) {
    const list = Array.isArray(commands) ? commands : [commands];
    try {
      state.busy++;
      const capture = await api('/api/command', { body: { command: list.join('\n'), scope: scope() } });
      renderCapture({ texts: [], images: [], command: list.join(' ; '), busy: true });
      // 任务一受理就从下方冒一条信息云，带跳去 /quest/#N 的链接（一个回执 = 一次任务，可多步）。
      if (capture.quest) questCloud('任务 #' + capture.quest + ' 已下达：' + list.join(' ; '), capture.quest);
      // 出图/领取这类要等的指令：顺便开始轮询 SD 的生成进度。
      if (follow) { state.followUntil = Date.now() + 20 * 60 * 1000; startProgressPolling(); }
      // 下达任务后自动刷新任务队列（不用再手点「刷新队列」）。
      if (/\b(gen|get|rg)\b/i.test(list.join(' '))) loadTasks().catch(() => {});
      await pollCapture(capture.id, 0, follow);
      if (after) await after();
      if (/\b(gen|get|rg)\b/i.test(list.join(' '))) loadTasks().catch(() => {});
      return capture;
    } catch (error) {
      if (String(error.message) !== 'unauthorized') toast(error.message);
      return null;
    } finally { state.busy--; }
  }

  const scope = () => state.status?.scope;

  // ---------------------------------------------------------------- 对话

  /** 对话页渲染结果快照的存储键、上限与写盘节流。 */
  const CHAT_LOG_PREFIX = 'pixiko-chat-log:';       // 按会话 scope 各存一份（和 pixiko-chat-scroll: 一个路子）
  const CHAT_LOG_LIMIT = 200;                       // 条目只留最近 200 条
  const CHAT_LOG_BYTES = 512 * 1024;                // 序列化超过约 512KB 就从最旧的开始丢
  const CHAT_LOG_THROTTLE = 400;                    // 写盘节流：最多每 400ms 一次

  /**
   * 对话页的渲染结果快照：`{scope, entries, server, timer, dirty}`。
   *
   * <p>存的是**结构化条目**（`{role,text}` 或 `{role:'bot',text,images:[file]}`），不是整段 HTML ——
   * 体积小，也不会把存下来的标签当代码跑。切栏目是真的一次页面加载（每个栏目一份 HTML），
   * DOM 一没就只剩这份快照可用：服务端历史（`/api/chat/history`）里只有对话文字，
   * 指令回执的文字与图片都不在里面，光按它渲染就是「切一下回来回执和图片全消失」的原因。
   *
   * <p>`server` 是「上次铺完时服务端历史有多少条」，重新加载时用它算出只该补哪一段。
   */
  const chatSnapshot = { scope: '', entries: [], server: 0, timer: null, dirty: false };

  /** 快照的存储键（按会话 scope）。 */
  function chatLogKey(scopeName) { return CHAT_LOG_PREFIX + (scopeName || 'default'); }

  /** 当前该写哪个 scope 的键：以铺回时记下的为准，没记过就用当前会话 scope（loadChatHistory 没跑到也不会写错地方）。 */
  function chatScopeName() { return chatSnapshot.scope || scope() || 'default'; }

  /** 把一条任意来源的条目洗成能存能渲染的形状；坏数据返回 null（不抛、也不渲染半条）。 */
  function chatEntryClean(raw) {
    if (!raw || typeof raw !== 'object') return null;
    const role = raw.role === 'user' || raw.role === 'sys' ? raw.role : 'bot';
    const text = typeof raw.text === 'string' ? raw.text : '';
    const images = Array.isArray(raw.images)
      ? raw.images.filter((file) => typeof file === 'string' && file).map(String).slice(0, 60) : [];
    if (!text && !images.length) return null;
    return images.length ? { role, text, images } : { role, text };
  }

  /** 体积保护：只留最近 200 条；序列化超过约 512KB 就从最旧的开始丢（至少留一条，别把一条超长消息也丢了）。 */
  function chatSnapshotTrim(entries) {
    let list = entries.slice(-CHAT_LOG_LIMIT);
    while (list.length > 1 && JSON.stringify(list).length > CHAT_LOG_BYTES) list = list.slice(1);
    return list;
  }

  /** 读一份快照；坏 JSON / 没有会话存储（隐私模式）都当成「没有」——聊天照常，只是不记忆。 */
  function chatSnapshotRead(scopeName) {
    const saved = storeJson(chatLogKey(scopeName));
    if (!saved) return { entries: [], server: 0, dropped: false };
    const raw = (Array.isArray(saved.entries) ? saved.entries : []).map(chatEntryClean).filter(Boolean);
    const entries = chatSnapshotTrim(raw);
    return { entries, server: Math.max(0, Math.floor(Number(saved.server) || 0)), dropped: entries.length !== raw.length };
  }

  /** 落盘一次：写失败（超配额、存储被禁、坏存储）只警告，绝不影响聊天与其它面板。 */
  function chatSnapshotWrite() {
    chatSnapshot.scope = chatScopeName();
    chatSnapshot.entries = chatSnapshotTrim(chatSnapshot.entries);
    try {
      sessionStorage.setItem(chatLogKey(chatSnapshot.scope),
        JSON.stringify({ entries: chatSnapshot.entries, server: chatSnapshot.server || 0 }));
    } catch (error) {
      console.warn('对话快照未保存（不影响聊天）：' + (error && error.message ? error.message : error));
    }
  }

  /** 追加消息/图片后调它：节流最多每 400ms 写一次；页面隐藏或卸载时用 {@link chatSnapshotFlush} 补一次。 */
  function chatSnapshotSchedule() {
    chatSnapshot.dirty = true;
    if (chatSnapshot.timer) return;
    chatSnapshot.timer = setTimeout(() => {
      chatSnapshot.timer = null;
      chatSnapshotWrite();
      chatSnapshot.dirty = false;
    }, CHAT_LOG_THROTTLE);
  }

  /** 把节流窗口里还没写完的那次立刻写掉（pagehide / 页面隐藏时调）。 */
  function chatSnapshotFlush() {
    if (chatSnapshot.timer) { clearTimeout(chatSnapshot.timer); chatSnapshot.timer = null; }
    if (!chatSnapshot.dirty) return;
    chatSnapshot.dirty = false;
    chatSnapshotWrite();
  }

  /** 清空对话：DOM、内存快照、会话存储一起删（不然清空后又「复活」）。 */
  function chatSnapshotClear() {
    if (chatSnapshot.timer) { clearTimeout(chatSnapshot.timer); chatSnapshot.timer = null; }
    chatSnapshot.scope = chatScopeName();
    chatSnapshot.entries = [];
    chatSnapshot.server = 0;
    chatSnapshot.dirty = false;
    try { sessionStorage.removeItem(chatLogKey(chatSnapshot.scope)); }
    catch (error) { console.warn('对话快照未删除（不影响聊天）：' + (error && error.message ? error.message : error)); }
  }

  /**
   * 一条对话条目 → 一条消息卡。文字在上、图片在下同属一条；带图的机器人消息多挂一个
   * `chat-receipt-images` 类（对话页底部的回执栏已经取消，`#chat-log` 就是图片的落点）。
   *
   * <p>**2 张以上**用图集网格（内部还是 imageUrl + imageNode），点任意一张都用查看器打开
   * **这条消息的整组图**并能在其中前后翻页；**1 张**仍是原来那张 `.msg-image`。
   */
  function chatEntryNode(entry) {
    const images = entry.images || [];
    const node = el('div', 'msg ' + entry.role + (images.length ? ' chat-receipt-images' : ''));
    if (entry.text || !images.length) node.appendChild(el('div', null, entry.text || ''));
    if (images.length === 1) {
      // 点图打开查看器放大（不再跳到新标签页）。
      node.appendChild(imageNode(imageUrl(images[0]), shortName(images[0]), 'msg-image'));
    } else if (images.length > 1) {
      // createGallery 内部就是 imageUrl() + imageNode()，并把「活的」图集数组交给查看器 —— 翻页天然可用。
      const gallery = createGallery({ cardClass: 'chat-gallery', gridClass: 'gallery-grid', firstFile: images[0] });
      images.slice(1).forEach((file) => gallery.add(file));
      node.appendChild(gallery.grid);
    }
    return node;
  }

  /** 只渲染（不记快照）：铺回上次的对话用。 */
  function chatEntryAppend(entry) {
    const log = $('chat-log');
    if (!log) return null;
    const node = chatEntryNode(entry);
    log.appendChild(node);
    log.scrollTop = log.scrollHeight;
    chatStickImages(log);                 // 图片是异步加载的：加载完再贴一次底，别让图把视图顶上去
    return node;
  }

  /** 只把条目记进快照（不渲染）：DOM 上已经由别处显示过的内容（例如「正在思考…」那条被就地改成回复）。 */
  function chatSnapshotRemember(entry) {
    const clean = chatEntryClean(entry);
    if (!clean) return null;
    chatSnapshot.entries.push(clean);
    if (chatSnapshot.entries.length > CHAT_LOG_LIMIT) chatSnapshot.entries = chatSnapshot.entries.slice(-CHAT_LOG_LIMIT);
    chatSnapshotSchedule();
    return clean;
  }

  /** 追加一条对话条目：先记进快照（切页面回来要恢复），再渲染进 #chat-log。 */
  function chatEntryAdd(entry) {
    const clean = chatSnapshotRemember(entry);
    return clean ? chatEntryAppend(clean) : null;
  }

  /** 快照里已经画过的图片：renderCapture 的补漏靠它去重，同一张图不会画两回。 */
  function chatSnapshotImages() {
    const files = new Set();
    chatSnapshot.entries.forEach((entry) => (entry.images || []).forEach((file) => files.add(file)));
    return files;
  }

  /** 往对话页加一条消息（同时记进快照）；不在对话页面（别的栏目）时静默跳过。 */
  function appendMessage(kind, text, images) {
    const log = $('chat-log');
    if (!log) return null;                     // 每个栏目一份 HTML：别的栏目根本没有 #chat-log
    const files = (images || []).map((image) => (image && image.file) || image).filter(Boolean).map(String);
    const entry = { role: kind, text: String(text === undefined || text === null ? '' : text) };
    if (files.length) entry.images = files;
    return chatEntryAdd(entry);
  }

  async function sendChat(event) {
    if (event) event.preventDefault();
    const input = $('chat-input');
    const message = input.value.trim();
    if (!message) return;
    input.value = '';
    input.style.height = 'auto';
    appendMessage('user', message);
    const pending = el('div', 'msg bot');
    pending.appendChild(el('span', 'spin'));
    pending.appendChild(document.createTextNode(' 正在思考…'));
    $('chat-log').appendChild(pending);
    $('chat-log').scrollTop = $('chat-log').scrollHeight;
    $('chat-send').disabled = true;
    try {
      const result = await api('/api/chat', { body: { message, execute: $('chat-execute').checked, scope: scope() } });
      const reply = result.reply || '(空回复)';
      pending.textContent = reply;
      // 就地改成回复的那条也要进快照：切页面回来位置才对，也能和服务端历史里的同一条对上（不重复画）。
      chatSnapshotRemember({ role: 'bot', text: reply });
      $('chat-interest').textContent = result.interest != null ? '相关度 ' + result.interest : '';
      if (result.commands && result.commands.length) {
        appendMessage('sys', '执行指令：' + result.commands.join('  '));
        if (result.quest) questCloud('任务 #' + result.quest + ' 已下达：' + result.commands.join(' ; '), result.quest);
        if (result.captureId) {
          // 网页对话里要图的请求：一直跟到图片回来，直接发在对话里。
          state.followUntil = Date.now() + 20 * 60 * 1000;
          await pollCapture(result.captureId, 0, true);
        }
      }
    } catch (error) {
      if (String(error.message) !== 'unauthorized') pending.textContent = '出错了：' + error.message;
    } finally {
      $('chat-send').disabled = false;
    }
  }

  /**
   * 打开对话页：先把**上次渲染过的内容**（指令回执的文字与图片都在里面）从会话存储里铺回来，
   * 再拉服务端历史，只补「比本地基准多出来的那一段」。
   *
   * <p>服务端历史里只有文字，图片与指令回执都不在里面 —— 只按它渲染，
   * 切一下栏目回来回执文字与图片就全没了（这就是这个函数以前的样子）。
   */
  async function loadChatHistory() {
    const log = $('chat-log');
    if (!log) return;                           // 只有对话页面有消息区
    // 切换会话回来时接回原来的滚动位置（按 scope 各记一份，互不污染）。
    const saved = chatScrollRead();
    // 1) 先铺上次的快照：文字、指令回执、图片都在，顺序也照旧。
    const scopeName = scope() || 'default';
    const snapshot = chatSnapshotRead(scopeName);
    chatSnapshot.scope = scopeName;
    chatSnapshot.entries = snapshot.entries;
    chatSnapshot.server = snapshot.server;
    chatSnapshot.dirty = false;
    // 读回来被裁过（超 200 条 / 超 512KB）：把裁过的版本写回去，存储里不留超限的旧账。
    if (snapshot.dropped) chatSnapshotSchedule();
    log.innerHTML = '';
    chatSnapshot.entries.forEach((entry) => chatEntryAppend(entry));
    // 2) 再拉服务端历史，只追加比基准多出来的那部分。
    try {
      const history = await api('/api/chat/history', { body: { scope: scope() } });
      const list = Array.isArray(history) ? history : [];
      // 基准 = 保存时服务端历史有多少条。服务端被清空/变短（列表比基准还短）时一段都不补，
      // 本地内容也原样留着（不会重复渲染，也不会把本地清掉）。
      const base = Math.min(chatSnapshot.server || 0, list.length);
      // 本地已经有的 role+文本记一份：本地刚发出去的那条也在历史里，靠它认出来别画两回。
      const known = new Map();
      chatSnapshot.entries.forEach((entry) => {
        const key = entry.role + '\n' + (entry.text || '');
        known.set(key, (known.get(key) || 0) + 1);
      });
      let added = 0;
      for (let index = base; index < list.length; index++) {
        const raw = list[index] || {};
        const clean = chatEntryClean({ role: raw.role === 'user' ? 'user' : 'bot', text: raw.content || '' });
        if (!clean) continue;
        const key = clean.role + '\n' + (clean.text || '');
        if (known.get(key)) { known.set(key, known.get(key) - 1); continue; }
        chatEntryAdd(clean);
        added++;
      }
      const next = Math.max(chatSnapshot.server || 0, list.length);
      const moved = next !== (chatSnapshot.server || 0);
      chatSnapshot.server = next;
      if (added || moved) chatSnapshotSchedule();
    } catch { /* 历史读不到不影响使用 */ }
    chatScrollRestore(log, saved);              // 之前在底部就贴底（图片异步加载完再校准一次）
  }

  // ---------------------------------------------------------------- 状态与设置

  /** 给某个 id 的元素赋值/改属性；这个页面上没有它（其它栏目的控件）就跳过。 */
  function setNode(id, apply) {
    const node = $(id);
    if (node) apply(node);
  }
  const setText = (id, value) => setNode(id, (node) => { node.textContent = value; });
  const setValue = (id, value) => setNode(id, (node) => { node.value = value; });
  const setChecked = (id, value) => setNode(id, (node) => { node.checked = value; });

  async function loadStatus() {
    const status = await api('/api/status');
    state.status = status;
    // 回执未读数：控制台所有栏目都在轮询 /api/status，新回执一到页签徽标就跟着涨。
    const quests = status.quests || {};
    if (Number.isFinite(Number(quests.unread)) || Number.isFinite(Number(quests.latest))) {
      // 只覆盖服务端给的数：列表已经读出来时以列表为准（它刚刚才按本地点击改过）。
      if (Number.isFinite(Number(quests.unread))) state.quests.unread = Number(quests.unread);
      if (Number.isFinite(Number(quests.latest))) state.quests.latest = Number(quests.latest);
      renderQuestTabBadge();
    } else if (!Array.isArray(state.questList)) {
      renderQuestTabBadge();   // 旧后端没有 quests 字段：没有徽标，但也不能因此报错
    }
    setText('botname', status.botName || '神户小鸟');
    setText('subtitle', (status.scope || '') + ' · :' + (status.webPort || ''));
    setNode('health', (node) => { node.className = 'dot ok'; });

    setNode('settings-bot', (bot) => {
      bot.innerHTML = '';
      const rows = [['机器人', status.botName], ['提示词归属', status.scope], ['监听端口', status.webPort],
        ['待领取图片', status.pendingImages], ['聊天全局', status.chat?.global ? '开启' : '关闭'],
        ['每分钟上限', status.chat?.frequency], ['性格字数', status.chat?.personalityChars],
        ['提示词词条', status.promptTerms], ['LoRA', status.loraStatus]];
      // 禁言中的群：只影响"能不能发出去"，不影响指令执行与队列。
      (status.mutes || []).forEach((mute) => rows.push(['禁言', '群 ' + mute.group + ' ' + mute.reason + '，到 ' + mute.untilText]));
      rows.forEach(([key, value]) => {
        bot.appendChild(el('div', null, key));
        bot.appendChild(el('div', null, String(value ?? '')));
      });
    });

    setText('channel-info', [status.chatChannel, status.imageChannel]
      .map((c) => c.label + '：' + c.model + (c.keyConfigured ? ' · 密钥已配置' : ' · 密钥未配置') + ' · ' + c.thinking)
      .join('　'));
    setChecked('set-chat-thinking', !!status.chatChannel?.thinking);
    setChecked('set-image-thinking', !!status.imageChannel?.thinking);
    setChecked('infix-filter', !!status.infixFilter);
    setChecked('set-chat-global', !!status.chat?.global);
    setValue('set-chat-model', status.chatChannel?.model || '');
    setValue('set-image-model', status.imageChannel?.model || '');
    setValue('set-frequency', status.chat?.frequency ?? '');
    setValue('set-personality', status.chat?.personality || '');
    setText('personality-chars', (status.chat?.personalityChars || 0) + ' 字符');
    setText('chatcfg-summary', '全局聊天 ' + (status.chat?.global ? '开启' : '关闭')
      + '　每分钟上限 ' + (status.chat?.frequency ?? '-'));
    // 只读展示好感度与情绪（L4）：只影响语气与亲密度，不影响照不照做。
    setText('persona-state', '你与她的好感度：' + (status.affinity?.score ?? '-')
      + '（' + (status.affinity?.tier || '-') + '）　此刻情绪：' + (status.mood?.mood || '-')
      + '（强度 ' + (status.mood?.intensity ?? '-') + '）');
    updateQueueSummary(status.generation?.status);
    setText('lora-status', status.loraStatus || '');
    const civitai = status.civitai || {};
    setText('civitai-state', civitai.hasCookie
      ? '已保存 ' + civitai.host + ' 的登录 Cookie（' + (civitai.cookieHint || '') + '）'
      : '未保存 Cookie：搜索与下载会使用 civitai.com，成人内容与部分模型不可见。');
    renderSd(status.sd || {});
    renderEndpoints(status.endpoints);

    const gen = status.generation || {};
    setValue('set-width', gen.width ?? '');
    setValue('set-height', gen.height ?? '');
    setValue('set-steps', gen.steps ?? '');
    setValue('set-cfg', gen.cfg ?? '');
    setValue('set-seed', gen.seed ?? '');
    setValue('set-imgcnt', status.imageCount ?? '');
    setChecked('gen-autoget', !!status.autoGet);
    setChecked('lora-auto-get', !!status.autoGet);
    setChecked('set-notice', !!status.noticeEnabled);
    setChecked('set-logmirror', !!status.logMirror);
    // 面板显示的就是机器人里当前生效的值：同步后不该再重复提交一次。
    appliedGeneration = JSON.stringify(generationPayload());
    applyGenerationSummary(status);
    // 队列里有任务就在生成中：开始/继续轮询 SD 进度；空闲就停掉。
    if (status.generation?.status) startProgressPolling(); else loadProgress().catch(() => {});
    return status;
  }

  /** 系统页签的接口地址：两条 DeepSeek 通道、SD、NapCat（只有地址，没有密钥）。 */
  function renderEndpoints(endpoints) {
    const box = $('endpoint-list');
    if (!box) return;
    const data = endpoints || {};
    box.innerHTML = '';
    [['DeepSeek 聊天 API', data.chatApi], ['DeepSeek 生图 API', data.imageApi],
      ['Stable Diffusion', data.sd], ['NapCat（QQ）', data.napcat]].forEach(([label, value]) => {
      box.appendChild(el('div', null, label));
      box.appendChild(el('div', null, value || '（未配置）'));
    });
  }

  /**
   * DeepSeek 通道卡片：地址与密钥都能改，改完立刻生效（不用重启）。
   * 密钥只回显掩码——读接口永远不回传原文，输入框留空就表示"不改"。
   */
  async function loadChannels() {
    try {
      renderChannels(await api('/api/config/state'));
    } catch (error) {
      if (String(error.message) !== 'unauthorized') setText('channel-note', '读取通道配置失败：' + error.message);
    }
  }

  function renderChannels(config) {
    const channels = (config && config.values && config.values.channels) || {};
    const image = channels.image || {}, chat = channels.chat || {};
    setValue('channel-image-base', image.base || '');
    setValue('channel-chat-base', chat.base || '');
    setNode('channel-image-base', (node) => { node.placeholder = image.official || '留空用官方 api.deepseek.com'; });
    setNode('channel-chat-base', (node) => { node.placeholder = chat.official || '留空用官方 api.deepseek.com'; });
    setText('channel-note', [image, chat].map((channel) => channel.label + '：'
      + (channel.keySet ? '密钥已配置 ' + channel.keyMasked : '密钥未配置')
      + '　当前生效地址 ' + channel.effective).join('　|　'));
  }

  /** 保存两条通道的地址与密钥（只提交改动过的字段）。 */
  async function saveChannels() {
    const channels = {};
    for (const name of ['image', 'chat']) {
      const patch = { base: ($('channel-' + name + '-base').value || '').trim() };
      const key = ($('channel-' + name + '-key').value || '').trim();
      if (key) patch.key = key;
      channels[name] = patch;
    }
    try {
      const saved = await api('/api/config/apply', { body: { channels } });
      setValue('channel-image-key', '');
      setValue('channel-chat-key', '');
      renderChannels(saved);
      toast(saved.notice || '通道设置已保存');
      await loadStatus();
    } catch (error) {
      if (String(error.message) !== 'unauthorized') toast('保存失败：' + error.message);
    }
  }

  async function testChannel(name) {
    try {
      const result = await api('/api/config/test', { body: { channel: name } });
      toast(result.label + '：' + result.message);
      setText('channel-note', result.label + '：' + result.message);
    } catch (error) {
      if (String(error.message) !== 'unauthorized') toast('测试失败：' + error.message);
    }
  }

  async function clearChannelKey(name) {
    const label = name === 'image' ? '生图频道' : '聊天频道';
    if (!await askConfirm('清空' + label + '的 API 密钥？\n清空后这条通道的聊天/改写会不可用，直到重新填入。',
        { title: '清空密钥', confirmText: '清空', danger: true })) return;
    const channels = {};
    channels[name] = { clearKey: true };
    try {
      renderChannels(await api('/api/config/apply', { body: { channels } }));
      toast('已清空' + label + '密钥');
      await loadStatus();
    } catch (error) {
      if (String(error.message) !== 'unauthorized') toast('清空失败：' + error.message);
    }
  }

  /** Stable Diffusion 卡片：是否在跑、启动入口、自启动开关。 */  function renderSd(sd) {
    const stateNode = $('sd-state');
    if (!stateNode) return;
    stateNode.textContent = sd.reachable ? '运行中' : '未运行';
    stateNode.className = 'muted';
    const detail = $('sd-detail');
    detail.textContent = [
      sd.root ? '目录：' + sd.root : '目录未配置（config.json 的 sd.root）',
      sd.launcher ? '启动入口：' + sd.launcher : '启动入口：' + (sd.available ? '-' : '没找到'),
      sd.args ? '启动参数：' + sd.args : '',
    ].filter(Boolean).join('\n');
    $('sd-auto-start').checked = !!sd.autoStart;
    $('sd-start-on-boot').checked = !!sd.startOnBoot;
  }

  async function loadSd() {
    try { renderSd(await api('/api/sd/status', { body: {} })); }
    catch { /* 读不到就保持上一次显示 */ }
  }

  async function loadOptions() {
    const options = await api('/api/options');
    state.options = options;
    fillSelect('set-sampler', options.samplers, (state.status?.generation?.sampler) || '');
    fillModelSelect('set-model', options, (state.status?.generation?.model) || '');
    appliedGeneration = JSON.stringify(generationPayload());
    applyGenerationSummary(state.status);
  }

  /** 填下拉框：元素不在本栏目时直接跳过——提示词集这类页面也会调 loadOptions()，别去操作出图面板才有的控件。 */
  function fillSelect(id, values, current) {
    const select = $(id);
    if (!select) return;
    select.innerHTML = '';
    const list = values && values.length ? values : [current].filter(Boolean);
    list.forEach((value) => {
      const option = el('option', null, value);
      option.value = value;
      if (value === current) option.selected = true;
      select.appendChild(option);
    });
    if (current && !list.includes(current)) {
      const option = el('option', null, current + '（当前）');
      option.value = current;
      option.selected = true;
      select.appendChild(option);
    }
  }

  /**
   * 基础模型下拉：每项后面带**归属栈**（后端从 safetensors 头部/记录/Forge 预设配置判出来），
   * 例如 {@code waiIllustriousSDXL_v170.safetensors [SDXL 栈]}；悬停给出判定依据。
   * 后端没给 modelOptions（SD 没在跑的老情况）时退回只有名字的下拉。
   */
  function fillModelSelect(id, options, current) {
    const rows = (options && options.modelOptions) || [];
    if (!rows.length) { fillSelect(id, (options && options.models) || [], current); return; }
    const select = $(id);
    if (!select) return;
    select.innerHTML = '';
    rows.forEach((item) => {
      const option = el('option', null, item.stackLabel ? item.name + ' [' + item.stackLabel + ']' : (item.name || item.title));
      option.value = item.title;
      const why = item.evidence ? '判定依据：' + item.evidence : '';
      option.title = item.stackLabel
        ? item.stackLabel + (item.preset ? '（预设 ' + item.preset + '）' : '') + (why ? '；' + why : '')
        : (why || '栈未识别');
      if (item.title === current) option.selected = true;
      select.appendChild(option);
    });
    if (current && !rows.some((item) => item.title === current)) {
      const option = el('option', null, current + '（当前）');
      option.value = current;
      option.selected = true;
      select.appendChild(option);
    }
  }

  // ---------------------------------------------------------------- 提示词

  async function loadPrompt() {
    return renderPrompt(await api('/api/prompt', { body: { scope: scope() } }));
  }

  /** 用一份 /api/prompt 数据刷新面板（读接口和改接口返回的是同一份结构）。 */
  function renderPrompt(prompt) {
    $('prompt-scope').textContent = '归属 ' + prompt.scope;
    $('prompt-positive').value = prompt.positive || '';
    $('prompt-negative').value = prompt.negative || '';
    // 点词条即移除：走内部接口按**词条原文**删（不拼 `.prompt remove`，也不轮询回执）。
    chips('prompt-positive-terms', prompt.positiveItems || prompt.positiveTerms,
      (index, term) => editPrompt('positive', 'remove', term));
    chips('prompt-negative-terms', prompt.negativeItems || prompt.negativeTerms,
      (index, term) => editPrompt('negative', 'remove', term));
    // 词库里没有的释义问一次 DeepSeek（后台去问，回来就地补上）
    fillMeanings('prompt-positive-terms', prompt.positiveItems || prompt.positiveTerms).catch(() => {});
    fillMeanings('prompt-negative-terms', prompt.negativeItems || prompt.negativeTerms).catch(() => {});
    $('prompt-meta').textContent = '样式只作模板：载入即替换，之后 prompt 就是你自己的文本';
    $('undo-depth').textContent = '可回退 ' + prompt.canUndo + ' 步';
    return prompt;
  }

  /**
   * 提示词面板的增删改：直接调 `/api/prompt/edit`（个人 prompt 是本机 JSON，和 SD 桥接无关）。
   * 接口返回刷新后的整份面板数据，一次往返就够，不用再 loadPrompt()，也没有回执要轮询。
   */
  async function editPrompt(side, action, value) {
    try {
      const data = await api('/api/prompt/edit', { body: { side, action, value: value == null ? '' : String(value), scope: scope() } });
      renderPrompt(data);
      if (data.message) toast(data.message);
      return data;
    } catch (error) {
      if (String(error.message) !== 'unauthorized') toast(error.message);
      await loadPrompt().catch(() => {});
      return null;
    }
  }

  /**
   * 词条 chip：`英文 中文`（中文只是颜色淡一点，不另开元素），点一下移除。
   * 释义先看词库；词库里没有的，渲染完再问一次 DeepSeek（见 fillMeanings）。
   */
  function chips(boxId, list, onRemove) {
    const box = $(boxId);
    box.innerHTML = '';
    (list || []).forEach((entry, index) => {
      const item = typeof entry === 'string' ? { term: entry, meaning: '', number: index + 1 } : entry;
      const chip = el('span');
      chip.dataset.term = item.term;
      chip.appendChild(el('b', null, '#' + (item.number || index + 1)));
      chip.appendChild(document.createTextNode(' '));
      chip.appendChild(el('span', 'term', item.term));
      const meaning = el('span', 'meaning', item.meaning ? ' ' + item.meaning : ' …');
      if (!item.meaning) meaning.classList.add('pending');
      chip.appendChild(meaning);
      if (onRemove) {
        chip.title = item.meaning ? item.term + '（' + item.meaning + '，点击移除）' : item.term + '（点击移除）';
        chip.style.cursor = 'pointer';
        chip.onclick = () => onRemove(item.number || index + 1, item.term);
      }
      box.appendChild(chip);
    });
    return box;
  }

  /**
   * 词库里查不到释义的词条：问一次 DeepSeek（后端会把结果记进 data/prompt-meanings.json，之后不再问），
   * 回来后就地补到对应 chip 上，不整块重画（否则面板会闪、滚动位置也会跳）。
   */
  async function fillMeanings(boxId, list) {
    const box = $(boxId);
    const missing = [];
    (list || []).forEach((entry) => {
      const item = typeof entry === 'string' ? { term: entry, meaning: '' } : entry;
      if (item.term && !item.meaning && !missing.includes(item.term)) missing.push(item.term);
    });
    if (!missing.length) return null;
    try {
      const data = await api('/api/meanings', { body: { terms: missing, learn: true } });
      const meanings = data.meanings || {};
      let filled = 0;
      box.querySelectorAll('span').forEach((chip) => {
        const meaning = meanings[chip.dataset.term];
        if (!meaning) return;
        const slot = chip.querySelector('.meaning');
        if (slot) { slot.textContent = ' ' + meaning; slot.classList.remove('pending'); filled++; }
        chip.title = chip.dataset.term + '（' + meaning + '，点击移除）';
      });
      return { filled, unresolved: (data.unresolved || []).length };
    } catch (error) {
      // 释义只是锦上添花：拿不到就把省略号去掉，别打扰用户。
      box.querySelectorAll('.meaning.pending').forEach((slot) => { slot.textContent = ''; slot.classList.remove('pending'); });
      return null;
    }
  }

  // ---------------------------------------------------------------- 样式

  /** 样式列表的行缓存：按名称复用 DOM。整表重画会让 79 行的列表在删除时"晃"，所以只增删变化的部分。 */
  const styleRows = new Map();
  /** 样式数据缓存（行 DOM 只负责显示，"查看原文"要的是数据）。 */
  const styleItems = new Map();
  /** 分类清单（组头顺序、筛选下拉、改分类的候选都用它；每次 /api/styles 都刷新）。
   *  后端一个 LoRA 一个分类：每项形如 {key:'lora:鸣濑白羽', name:'鸣濑白羽', kind:'lora', count:9, lora:true}。 */
  let styleCategories = [];
  /** 最近一次 /api/styles 的完整返回（筛选是纯前端的事，不必再打一次接口）。 */
  let lastStyles = null;
  /** 分类清单的指纹：一样就不重建筛选下拉/候选表（在筛选框里打字时别抖）。 */
  let categorySignature = '';
  /** 折叠状态存 localStorage（分类 key 的数组），默认全部展开；null ＝ 还没从 localStorage 读过。 */
  const STYLE_COLLAPSED_KEY = 'pixiko-style-collapsed';
  let styleCollapsed = null;

  async function loadStyles() {
    return renderStyles(await api('/api/styles'));
  }

  /**
   * 样式库是本机 JSON（`data/local-styles.json`）：保存/覆盖/改名/删除/载入/改分类走 `/api/styles/edit`。
   * 只有「导入 WebUI 预设样式」要读 SD 桥接，仍走指令通道。
   */
  async function editStyles(action, name, extra) {
    try {
      const data = await api('/api/styles/edit', { body: Object.assign({ action, name: name == null ? '' : String(name), scope: scope() }, extra || {}) });
      renderStyles(data);
      if (data.message) toast(data.message.split('\n')[0]);
      return data;
    } catch (error) {
      if (String(error.message) !== 'unauthorized') toast(error.message);
      await loadStyles().catch(() => {});
      return null;
    }
  }

  /** 一条样式的生效分类（后端已经算好：手动优先、否则按规则；都没有就是「未分类」）。 */
  function styleCategory(item) {
    return (item && item.category) || '未分类';
  }

  /**
   * 分类清单：新后端直接给 key（一个 LoRA 一个分类，key 形如 {@code lora:<显示名>}，另有 stack:/manual:/none）；
   * 旧后端只给 name/count/lora，甚至完全没有 categories —— 那就按 style.category 现场聚合，行为与改造前一致（不白屏）。
   */
  function normalizeCategories(data) {
    const raw = data && Array.isArray(data.categories) ? data.categories : [];
    if (raw.length) {
      return raw.map((entry) => {
        const name = String(entry.name || entry.key || '未分类');
        const kind = entry.kind || (entry.lora ? 'lora' : (name === '未分类' ? 'none' : 'manual'));
        return { key: String(entry.key || name), name, kind, count: Number(entry.count) || 0, lora: !!entry.lora || kind === 'lora' };
      });
    }
    // 兜底：没有分类清单时按 style.category 分桶；key 加 name: 前缀，免得和 lora:/stack:/manual: 撞车。
    const buckets = new Map();
    ((data && data.styles) || []).forEach((item) => {
      const name = styleCategory(item);
      if (!buckets.has(name)) buckets.set(name, { key: 'name:' + name, name, kind: name === '未分类' ? 'none' : 'manual', count: 0, lora: false });
      buckets.get(name).count++;
    });
    return [...buckets.values()];
  }

  /** 一条样式的分类 key：新后端直接给 categoryKey；缺了就按分类名回查，再不行用 name:<分类名> 兜住。 */
  function styleCategoryKey(item) {
    const key = item && item.categoryKey;
    if (typeof key === 'string' && key) return key;
    const name = styleCategory(item);
    const known = styleCategories.find((entry) => entry.name === name);
    return known ? known.key : 'name:' + name;
  }

  // ---------------------------------------------------------- 分类折叠

  /** 折叠状态（分类 key 的集合）：读 localStorage；读不出来或内容坏了就当全部展开（不抛错、不白屏）。 */
  function collapsedGroups() {
    if (styleCollapsed) return styleCollapsed;
    styleCollapsed = new Set();
    try {
      const saved = JSON.parse(localStorage.getItem(STYLE_COLLAPSED_KEY) || '[]');
      if (Array.isArray(saved)) saved.forEach((key) => { if (typeof key === 'string' && key) styleCollapsed.add(key); });
    } catch (error) { /* 存储被禁用/内容坏了：按默认（全部展开）走 */ }
    return styleCollapsed;
  }

  function saveCollapsedGroups() {
    try { localStorage.setItem(STYLE_COLLAPSED_KEY, JSON.stringify([...collapsedGroups()])); } catch (error) { /* 存不下就只在本次会话里生效 */ }
  }

  /** 把一组的折叠/展开画出来：class 交给 CSS 收起行，aria 给读屏，title 提示下一步动作。 */
  function applyGroupCollapse(holder) {
    if (!holder) return;
    const collapsed = collapsedGroups().has(holder.dataset.key);
    holder.classList.toggle('collapsed', collapsed);
    const head = holder.querySelector('.group-head');
    if (head) {
      head.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
      head.title = (collapsed ? '展开' : '折叠') + '「' + (holder.dataset.name || '') + '」这一组';
    }
  }

  /** 整块列表按 localStorage 对齐一次：重画（改名/改分类/刷新）之后折叠状态不会丢。 */
  function applyAllGroupCollapse() {
    const list = $('style-list');
    if (!list) return;
    list.querySelectorAll('li.cat-group').forEach(applyGroupCollapse);
  }

  /** 点组头：切换这一组的折叠状态并写回 localStorage。 */
  function toggleGroupCollapse(holder) {
    if (!holder) return;
    const key = holder.dataset.key;
    if (!key) return;
    const set = collapsedGroups();
    if (set.has(key)) set.delete(key); else set.add(key);
    saveCollapsedGroups();
    applyGroupCollapse(holder);
  }

  /**
   * 「全部折叠」/「全部展开」：按**真的画出来的组**来写（空的分类不落垃圾 key），再整块对齐 DOM。
   * 列表还没画出来（数据还没回来）时退回分类清单，保证这一次点击不是白点。
   */
  function setAllGroupCollapse(collapsed) {
    const list = $('style-list');
    const holders = list ? [...list.querySelectorAll('li.cat-group')] : [];
    const keys = holders.length ? holders.map((holder) => holder.dataset.key) : styleCategories.map((entry) => entry.key);
    const set = collapsedGroups();
    keys.forEach((key) => { if (collapsed) set.add(key); else set.delete(key); });
    saveCollapsedGroups();
    applyAllGroupCollapse();
  }

  function renderStyles(data) {
    if (!data) return;
    lastStyles = data;
    $('style-loaded').textContent = '载入即替换，之后 prompt 由你自己改';
    $('style-pageselected').textContent = '样式库 ' + (data.library ?? (data.styles || []).length) + ' 个（只属于机器人，与 WebUI 的样式互不影响）';
    // 底模分类：列表按分类分组，这里只在上面汇总一行。
    const groups = (data.baseModelGroups || []).map((group) => group.baseModel + ' ' + group.count);
    if (groups.length) $('style-pageselected').textContent += '；底模：' + groups.join('、');
    styleCategories = normalizeCategories(data);
    // 分类多了（一个 LoRA 一类）会很长：只列前几个，其余折成「等 N 类」。
    const categories = styleCategories.map((entry) => entry.name + ' ' + entry.count);
    if (categories.length) {
      const head = categories.slice(0, 8).join('、');
      $('style-pageselected').textContent += '；分类：' + head + (categories.length > 8 ? ' …（共 ' + categories.length + ' 类）' : '');
    }
    fillCategoryFilter();
    const filter = $('style-filter').value.trim().toLowerCase();
    const select = $('style-category-filter');
    const only = select ? select.value : '';
    const items = (data.styles || []).filter((item) => {
      if (only && styleCategoryKey(item) !== only) return false;
      if (!filter) return true;
      return item.name.toLowerCase().includes(filter)
        || styleCategory(item).toLowerCase().includes(filter)
        || String(item.baseModel || '').toLowerCase().includes(filter)
        || String(item.stack || '').toLowerCase().includes(filter)
        || String(item.stackLabel || '').toLowerCase().includes(filter);
    });
    styleItems.clear();
    (data.styles || []).forEach((item) => styleItems.set(item.name, item));
    syncRows(items, (data.styles || []).length ? '没有匹配的样式。' : '还没有样式：保存一条当前提示词就会出现在这里。');
  }

  /**
   * 分类筛选下拉 + 改分类的候选（datalist）：保留当前选择，选项顺序与组头一致。
   * 下拉的 value 用分类 key（同名不同类不会串），显示文字用分类名；改分类的候选仍发分类名（接口要的是名字，不能改）。
   */
  function fillCategoryFilter() {
    const signature = styleCategories.map((entry) => entry.key + ':' + entry.name + ':' + entry.count).join('|');
    if (signature === categorySignature) return;      // 筛选框里打字时不必反复重建下拉
    categorySignature = signature;
    const select = $('style-category-filter');
    if (select) {
      const current = select.value;
      select.innerHTML = '';
      select.appendChild(new Option('全部分类', ''));
      styleCategories.forEach((entry) => select.appendChild(new Option(entry.name + '（' + entry.count + '）', entry.key)));
      select.value = [...select.options].some((option) => option.value === current) ? current : '';
    }
    const datalist = $('style-category-options');
    if (datalist) {
      datalist.innerHTML = '';
      styleCategories.forEach((entry) => datalist.appendChild(new Option(entry.name, entry.name)));
    }
  }

  /** 按分类 key 分桶：顺序跟分类清单走（一个 LoRA 一组 → 各栈 → 未分类 → 自定义），清单外的按出现顺序排后面。 */
  function styleBuckets(items) {
    const buckets = new Map();
    styleCategories.forEach((entry) => buckets.set(entry.key, { key: entry.key, name: entry.name, kind: entry.kind, lora: entry.lora, items: [] }));
    items.forEach((item) => {
      const key = styleCategoryKey(item);
      if (!buckets.has(key)) buckets.set(key, { key, name: styleCategory(item), kind: 'manual', lora: false, items: [] });
      buckets.get(key).items.push(item);
    });
    return [...buckets.values()].filter((bucket) => bucket.items.length);
  }

  /**
   * 增量同步列表：**按分类分组，组头可折叠**（「鸣濑白羽（9）▾」），同名的行原地复用（只更新编号，不重建、
   * 不重放动画、不丢滚动位置）；分类变了的行重建一次——分类徽标与它所在的分组都得跟着变。
   * 编号仍取自后端列表，所以 `.style load #N` / 批量 `#6-#9` 不因分组而错位。
   */
  function syncRows(items, emptyText) {
    const list = $('style-list');
    const wanted = new Set(items.map((item) => item.name));
    for (const [name, row] of [...styleRows]) if (!wanted.has(name)) { row.remove(); styleRows.delete(name); }
    list.querySelectorAll('li.cat-group, li.muted').forEach((node) => node.remove());
    if (!items.length) { list.appendChild(el('li', 'muted', emptyText)); return; }
    styleBuckets(items).forEach((bucket) => {
      const holder = el('li', 'cat-group');
      holder.dataset.key = bucket.key;
      holder.dataset.name = bucket.name;
      holder.appendChild(groupHead(bucket, holder));
      const rows = el('ul', 'list group-rows');
      bucket.items.forEach((item) => {
        let row = styleRows.get(item.name);
        if (row && row.dataset.categoryKey !== styleCategoryKey(item)) { row.remove(); styleRows.delete(item.name); row = null; }
        if (!row) { row = styleRow(item); styleRows.set(item.name, row); }
        row.dataset.name = item.name;
        row.dataset.categoryKey = styleCategoryKey(item);
        row.dataset.number = String(item.number);
        const number = row.querySelector('.num');
        if (number) number.textContent = '#' + item.number;
        rows.appendChild(row);
      });
      holder.appendChild(rows);
      list.appendChild(holder);
    });
    applyAllGroupCollapse();     // 重画之后按 localStorage 重新对齐折叠状态
  }

  /** 组头：折叠箭头 + 分类名（条数）+ 类型标签；点一下（或回车/空格）折叠或展开这一组。 */
  function groupHead(bucket, holder) {
    const head = el('div', 'group-head');
    head.setAttribute('role', 'button');
    head.setAttribute('tabindex', '0');
    head.setAttribute('aria-expanded', 'true');
    head.appendChild(el('span', 'group-arrow', '▾'));
    head.appendChild(el('span', 'group-name', bucket.name + '（' + bucket.items.length + '）'));
    head.appendChild(el('span', 'tag' + (bucket.lora ? ' on' : ''), groupKindText(bucket)));
    const toggle = () => toggleGroupCollapse(holder);
    head.onclick = toggle;
    head.onkeydown = (event) => {
      if (event.key !== 'Enter' && event.key !== ' ' && event.key !== 'Spacebar') return;
      event.preventDefault();
      toggle();
    };
    return head;
  }

  /** 组头右边的类型标签：一眼看出这组是 LoRA 自动组、栈组还是手工分类。 */
  function groupKindText(bucket) {
    if (bucket.lora || bucket.kind === 'lora') return 'LoRA 样式';
    if (bucket.kind === 'stack') return '栈';
    if (bucket.kind === 'none') return '未分类';
    return '手动分类';
  }

  /** 一行样式：按钮都从 dataset 读当前名称/编号，编号前移时不需要重建这一行。 */
  function styleRow(item) {
    const li = el('li', 'fresh');
    li.appendChild(el('span', 'num', '#' + (item.number || '')));
    li.appendChild(previewThumb(item.preview ? stylePreviewUrl(item.name) : '', item.name));
    const name = el('span', 'name');
    name.appendChild(el('span', 'tag on', '样式'));
    const label = el('span', 'editable', ' ' + item.name);
    label.title = '点一下直接改名';
    const startRename = () => {
      const current = li.dataset.name || item.name;
      inlineRename(label, current, (next) => { editStyles('rename', current, { newName: next }); }, loadStyles);
    };
    label.onclick = startRename;
    name.appendChild(label);
    name.appendChild(el('div', 'sub', '载入时替换你的个人提示词，之后 prompt 就是你自己的文本'));
    // 分类徽标（点一下就改）+ 尺寸：展示图样式记的是展示图自己的像素，单独标出来。
    const meta = el('div', 'sub');
    const chip = categoryChip(item);
    meta.appendChild(chip);
    if (item.width > 0 && item.height > 0) {
      const size = el('span', 'tag' + (item.sizeSource === 'preview' ? ' on' : ''), item.width + '×' + item.height);
      size.title = item.sizeSource === 'preview'
        ? '尺寸来自这张展示图本身（载入样式时按它出图）' : '尺寸来自样式保存时的模型参数';
      meta.appendChild(document.createTextNode(' '));
      meta.appendChild(size);
    }
    if (item.previewImage) meta.title = '展示图：' + item.previewImage;
    name.appendChild(meta);
    // 样式保存时记下的模型参数（底模 + 归属栈 + 采样方法/调度器/步数/CFG/Shift/尺寸）：载入时一并套用。
    if (item.modelSummary) {
      const model = el('div', 'sub');
      model.appendChild(el('span', 'tag', item.stackLabel || item.baseModel || '底模未记录'));
      model.appendChild(document.createTextNode(' ' + item.modelSummary));
      model.title = item.modelSummary;
      name.appendChild(model);
    }
    li.appendChild(name);
    const acts = el('div', 'acts');
    acts.appendChild(actionButton('载入', () => {
      const current = li.dataset.name || item.name;
      const noLora = $('style-load-nolora') && $('style-load-nolora').checked;
      editStyles('load', current, { noLora: !!noLora });
    }));
    const rename = actionButton('改名', startRename, 'ghost');
    rename.title = '重命名这条样式（只改样式库里的名字，已生成的图片不受影响）';
    acts.appendChild(rename);
    const recategorize = actionButton('改分类', () => startCategoryEdit(chip, item), 'ghost');
    recategorize.title = '改这条样式的分类（可选已有分类，也可以直接输入新的分类名）';
    acts.appendChild(recategorize);
    acts.appendChild(actionButton('查看原文', () => {
      const current = li.dataset.name || item.name;
      const data = styleItems.get(current) || item;
      showInfo('样式原文：' + current,
        '分类：' + styleCategory(data) + (data.categoryAuto ? '（按规则自动）' : '（手动设置）')
          + (data.width > 0 && data.height > 0 ? '\n尺寸：' + data.width + '×' + data.height
              + (data.sizeSource === 'preview' ? '（展示图尺寸）' : '（保存时的模型参数）') : '')
          + (data.previewImage ? '\n展示图：' + data.previewImage : '')
          + (data.loraName ? '\n所属 LoRA：' + data.loraName : '')
          + '\n\n正向：\n' + (data.positive || '（空）') + '\n\n反向：\n' + (data.negative || '（空）')
          + (data.modelSummary ? '\n\n模型参数（载入时一并套用）：\n' + data.modelSummary : '\n\n模型参数：这条样式没记录（保存时读不到 SD 参数）。'),
        { text: '这是一份固定模板：载入会把这两段原样写进你个人的提示词。' });
    }, 'ghost'));
    acts.appendChild(actionButton('删除', async () => {
      const current = li.dataset.name || item.name;
      if (!await askConfirm('删除样式「' + current + '」？\n样式库里的这一条会被移除，已生成的图片不受影响。',
          { title: '删除样式', confirmText: '删除', danger: true })) return;
      const data = await editStyles('delete', current);
      if (!data) return;
      // 删除成功：只摘掉这一行，列表其余部分原地不动（不整表重画）。
      const row = styleRows.get(current);
      if (row) { row.remove(); styleRows.delete(current); }
      toast('样式已删除：' + current);
      loadStyles().catch(() => {});   // 后台对齐一次编号
    }, 'danger'));
    li.appendChild(acts);
    return li;
  }

  /** 行里的分类徽标：手动设过的实心、按规则自动的描边，点一下就地改。 */
  function categoryChip(item) {
    const current = styleCategory(item);
    const chip = el('span', 'tag cat' + (item.categoryAuto ? '' : ' on'), '分类：' + current);
    chip.title = item.categoryAuto
      ? '按默认规则归类（' + current + '）：点一下手动指定分类'
      : '手动分类（' + current + '）：点一下改；留空或输入 - 恢复按规则自动分类';
    chip.onclick = () => startCategoryEdit(chip, item);
    return chip;
  }

  /**
   * 就地改分类：输入框 + 已有分类候选（datalist），回车提交、Esc/失焦取消；留空或输入 - 恢复自动分类。
   * 不弹浏览器的 prompt 框（和改名同一套交互）。
   */
  function startCategoryEdit(chip, item) {
    if (!chip || chip.querySelector('input')) return;
    const current = chip.textContent;
    const input = el('input', 'rename-input');
    input.value = item.categoryAuto ? '' : styleCategory(item);
    input.placeholder = item.categoryAuto ? '留空＝自动（' + styleCategory(item) + '）' : '分类名（留空＝自动）';
    input.setAttribute('list', 'style-category-options');
    input.title = '回车保存；留空或输入 - 恢复按规则自动分类';
    chip.textContent = '';
    chip.appendChild(input);
    input.focus();
    input.select();
    let finished = false;
    const finish = (save) => {
      if (finished) return;
      finished = true;
      const value = input.value.trim();
      chip.textContent = current;        // 列表随后会按新分类重画这一行，这里先把徽标文字还原
      if (save) editStyles('category', item.name, { category: value });
    };
    input.onkeydown = (event) => {
      if (event.key === 'Enter') { event.preventDefault(); finish(true); }
      else if (event.key === 'Escape') { event.preventDefault(); finish(false); }
    };
    input.onblur = () => finish(true);
  }

  /** 就地改名：把名字变成输入框，回车提交、Esc 取消，不再弹浏览器的 prompt 框。 */
  function inlineRename(node, current, commit, revert) {
    if (node.querySelector('input')) return;
    // 名字前后的空格（样式名前面留了一个）要原样保留，提交/取消都要把标签写回去：
    // 只 commit 不还原的话，行里会一直留着一个输入框（要等列表重画才恢复）。
    const original = node.textContent;
    const prefix = original.slice(0, Math.max(0, original.indexOf(current)));
    const restore = (name) => { node.textContent = prefix + name; };
    const input = el('input', 'rename-input');
    input.value = current;
    node.textContent = '';
    node.appendChild(input);
    input.focus();
    input.select();
    let finished = false;
    const finish = (save) => {
      if (finished) return;
      finished = true;
      const value = input.value.trim();
      if (save && value && value !== current) { restore(value); commit(value); }
      else { restore(current); if (revert) revert(); }
    };
    input.onkeydown = (event) => {
      if (event.key === 'Enter') { event.preventDefault(); finish(true); }
      else if (event.key === 'Escape') { event.preventDefault(); finish(false); }
    };
    input.onblur = () => finish(true);
  }

  function actionButton(label, handler, cls) {
    const button = el('button', cls || null, label);
    button.onclick = handler;
    return button;
  }

  // ---------------------------------------------------------------- LoRA

  async function loadLoras() {
    const list = $('lora-list');
    // 先用手上这份列表渲染：切页签、刷新失败时都不会出现"空列表"的错觉。
    if (!list.children.length && state.loras) renderLoras(state.loras, state.loraGroups);
    if (!list.children.length) list.appendChild(el('li', 'muted', '正在读取本机 LoRA…'));
    let data;
    try {
      data = await api('/api/loras');
    } catch (error) {
      if (String(error.message) === 'unauthorized') return;
      list.innerHTML = '';
      list.appendChild(el('li', 'muted', '读取本机 LoRA 失败：' + error.message));
      list.appendChild(actionButton('重试', loadLoras, 'ghost'));
      $('lora-status').textContent = '读取失败';
      return;
    }
    state.loras = data.loras || [];
    state.loraGroups = data.groups || [];
    const count = $('lora-count');
    if (data.error) {
      // SD 没在跑：说清楚原因，别显示成"本机没有 LoRA 文件"。
      list.innerHTML = '';
      list.appendChild(el('li', 'muted', '读取本机 LoRA 失败：' + data.error));
      list.appendChild(actionButton('重试', loadLoras, 'ghost'));
      $('lora-status').textContent = data.status || '读取失败';
      if (count) count.textContent = '';
      return;
    }
    renderLoras(state.loras, state.loraGroups);
    $('lora-status').textContent = data.status || ('本机 ' + state.loras.length + ' 个 LoRA');
    if (count) count.textContent = '共 ' + state.loras.length + ' 个' + (data.directory ? '（' + data.directory + '）' : '');
    // 进面板时后台可能正在下载（甚至刷新过页面）：接着把进度条挂上，不然进度就"看不见了"。
    if (data.download && (data.download.busy || data.download.downloading)) watchLoraProgress();
  }

  // ------------------------------------------------- LoRA 下载实时进度

  const loraWatch = { timer: null, hideTimer: null };

  /**
   * 控制台的 LoRA 写操作（下载 / 补展示图）：走内部接口，<b>不</b>借道指令通道——
   * 指令通道要等整件事做完才结束回执，进度就没法实时显示了。
   * 接口只负责"启动"，发完立刻挂上每秒轮询；轮询结束时会刷新本机列表。
   */
  async function startLoraJob(path, body) {
    try {
      const started = await api(path, { body: Object.assign({ scope: scope() }, body || {}) });
      if (started && started.quest) questCloud('任务 #' + started.quest + ' 已下达（' + path + '）', started.quest);
      watchLoraProgress();
      return true;
    } catch (error) {
      if (String(error.message) !== 'unauthorized') toast(error.message);
      return false;
    }
  }

  /** 秒数转成人话（和机器人的 /lora status 一个口径）。 */
  function describeEta(seconds) {
    const total = Math.max(1, Math.round(seconds));
    if (total >= 3600) return Math.floor(total / 3600) + ' 小时 ' + Math.floor((total % 3600) / 60) + ' 分';
    if (total >= 60) return Math.floor(total / 60) + ' 分 ' + (total % 60) + ' 秒';
    return total + ' 秒';
  }

  /**
   * 画一次进度：机器人给字节数就按百分比画，没给（正在读模型信息/正在加载到 WebUI/正在补图）
   * 就退回不确定态——不能显示成 0%，那会让人以为卡死了。
   */
  function renderLoraProgress(data) {
    const box = $('lora-progress'), fill = $('lora-progress-fill'), text = $('lora-progress-text');
    if (!box || !fill || !text) return;
    clearTimeout(loraWatch.hideTimer);
    box.hidden = false;
    const running = !!(data && (data.busy || data.downloading));
    if (data && data.metered) {
      box.classList.remove('indeterminate');
      fill.style.width = Math.max(0, Math.min(100, data.percent)).toFixed(1) + '%';
      const mib = 1024 * 1024;
      let line = (data.done / mib).toFixed(1) + ' / ' + (data.total / mib).toFixed(1) + ' MiB（' + data.percent.toFixed(1) + '%）';
      if (data.speed > 0) line += ' · ' + (data.speed / mib).toFixed(2) + ' MiB/s';
      if (data.etaSeconds > 0) line += ' · 剩余约 ' + describeEta(data.etaSeconds);
      text.textContent = line;
    } else {
      box.classList.add('indeterminate');
      fill.style.width = '';
      // 结束后的完整报告很长（触发词、展示图清单十几行）：进度条上只留第一行，细节看下面/控制台。
      const stage = String((data && data.stage) || (running ? '正在处理…' : '已结束')).trim();
      const first = stage.split('\n')[0].trim();
      text.textContent = stage === first ? first : first + ' …';
    }
    // 结束后让最后一行（"展示图已保存…"/"下载成功…"）停一会儿再收起来，别一闪而过。
    if (!running) loraWatch.hideTimer = setTimeout(() => { box.hidden = true; box.classList.remove('indeterminate'); }, 6000);
  }

  /**
   * 每秒问一次 /api/lora/progress，直到这次下载/补图结束；结束时顺带刷新本机列表，
   * 新 LoRA 和它的展示图就一起出现了（不用用户再点一次「刷新」）。
   *
   * <p>不能一看到"没在下载"就收手：点下「下载」到机器人真正登记任务之间有一小段空窗，
   * 第一次轮询常常正好落在里面。所以要么先看到过"正在下载"，要么等满宽限期才判定没跑起来。
   */
  function watchLoraProgress() {
    clearInterval(loraWatch.timer);
    let misses = 0, grace = 0, seenRunning = false;
    const tick = async () => {
      let data;
      try { data = await api('/api/lora/progress'); }
      catch (error) {
        if (String(error.message) === 'unauthorized') { clearInterval(loraWatch.timer); return; }
        if (++misses >= 3) clearInterval(loraWatch.timer);      // 机器人没了就别一直敲
        return;
      }
      misses = 0;
      const running = !!(data.busy || data.downloading);
      if (running) { seenRunning = true; grace = 0; }
      else if (!seenRunning && ++grace <= 20) return;            // 空窗期：先不画也不收手
      renderLoraProgress(data);
      if (!running) {
        clearInterval(loraWatch.timer);
        loadLoras().catch(() => {});
      }
    };
    loraWatch.timer = setInterval(tick, 1000);
    tick();
  }

  /** 底模/归属栈来源的中文说法（和机器人回执同一个词表）。 */
  function baseModelSourceLabel(source) {
    if (source === 'safetensors-header') return 'safetensors 头部元数据';
    if (source === 'safetensors-keys') return 'safetensors 张量结构';
    if (source === 'civitai') return 'Civitai 记录';
    if (source === 'forge-metadata') return 'Forge 元数据';
    if (source === 'forge-preset') return 'Forge 预设配置';
    if (source === 'preset-inferred') return '按当前预设推断';
    if (source === 'inferred') return '按文件名/当前预设推断';
    return '';
  }

  /** 按**归属栈**分组：组头是栈名（Anima 栈），旁边标出底模；判不出栈的排最后（组序由后端 groups 定）。 */
  function loraBuckets(items, groups) {
    const meta = new Map((groups || []).map((group) => [String(group.key == null ? '' : group.key), group]));
    const order = new Map((groups || []).map((group, index) => [String(group.key == null ? '' : group.key), index]));
    const buckets = new Map();
    items.forEach((item) => {
      const key = String(item.groupKey == null ? '' : item.groupKey);
      if (!buckets.has(key)) {
        const group = meta.get(key) || {};
        buckets.set(key, {
          key,
          keyed: !!key,
          title: group.label || item.stackLabel || group.baseModel || item.baseModel || '未识别栈',
          base: (group.baseModels || []).filter(Boolean).join('、') || item.baseModel || '',
          source: baseModelSourceLabel(group.stackSource || item.stackSource),
          items: [],
        });
      }
      buckets.get(key).items.push(item);
    });
    return [...buckets.values()].sort((left, right) => {
      if (left.keyed !== right.keyed) return left.keyed ? -1 : 1;         // 未识别栈的排最后
      const leftRank = order.has(left.key) ? order.get(left.key) : order.size;
      const rightRank = order.has(right.key) ? order.get(right.key) : order.size;
      return leftRank - rightRank || left.title.localeCompare(right.title, 'zh');
    });
  }

  /**
   * 渲染本机 LoRA 列表：按**归属栈**分组（组头例如 Anima 栈（4）· 底模 Anima · Forge 元数据），
   * 每项也带自己的栈与底模标签。行 DOM 仍按名字复用：加载/删除后不整表重画，列表不会晃。
   */
  function renderLoras(items, groups) {
    const list = $('lora-list');
    const filter = ($('lora-filter') && $('lora-filter').value || '').trim().toLowerCase();
    const matched = (items || []).filter((item) => !filter || item.name.toLowerCase().includes(filter)
      || String(item.alias || '').toLowerCase().includes(filter)
      || String(item.baseModel || '').toLowerCase().includes(filter)
      || String(item.stack || '').toLowerCase().includes(filter)
      || String(item.stackLabel || '').toLowerCase().includes(filter));
    const emptyText = (items || []).length
      ? '没有匹配「' + filter + '」的 LoRA（本机共 ' + items.length + ' 个）。'
      : '本机 LoRA 目录里还没有 .safetensors 文件；用上面「Civitai 搜索」下载，或把模型放进 '
        + 'config.json 的 civitai.lora_dir。';
    // 只摘掉真的消失的行；组容器每次重建（行节点原样搬过去，不重放动画）。
    const wanted = new Set(matched.map((item) => item.name));
    for (const [name, row] of [...loraRows]) if (!wanted.has(name)) { row.remove(); loraRows.delete(name); }
    list.querySelectorAll('li.lora-group, li.muted').forEach((node) => node.remove());
    if (!matched.length) { list.appendChild(el('li', 'muted', emptyText)); return; }
    loraBuckets(matched, groups).forEach((bucket) => {
      const holder = el('li', 'lora-group');
      const head = el('div', 'group-head');
      head.appendChild(el('span', 'group-name', bucket.title + '（' + bucket.items.length + '）'));
      // 组头保留底模名：同一个栈下可以有多个底模（Illustrious / NoobAI / Pony 都是 SDXL 栈）。
      if (bucket.base && bucket.base !== bucket.title) head.appendChild(el('span', 'tag', '底模 ' + bucket.base));
      if (bucket.source) head.appendChild(el('span', 'tag', bucket.source));
      holder.appendChild(head);
      const rows = el('ul', 'list group-rows');
      bucket.items.forEach((item) => {
        let row = loraRows.get(item.name);
        if (!row) { row = loraRow(item); loraRows.set(item.name, row); }
        row.dataset.name = item.name;
        row.dataset.number = String(item.number);
        const number = row.querySelector('.num');
        if (number) number.textContent = '#' + item.number;
        // 底模与归属栈标签可能因为 Forge 元数据/预设栈的变化而变（补展示图之后就会），每次渲染对齐一次。
        const base = row.querySelector('.lora-base');
        if (base) base.textContent = loraBaseText(item);
        rows.appendChild(row);
      });
      holder.appendChild(rows);
      list.appendChild(holder);
    });
  }

  /** 一行 LoRA 的归属文字：{@code Anima 栈 · 底模 Anima}（栈判不出来就只说底模，都不识别就说未识别）。 */
  function loraBaseText(item) {
    const stack = item && item.stackLabel ? item.stackLabel : '';
    const base = item && (item.baseModelLabel || (item.baseModel ? '底模 ' + item.baseModel : ''));
    if (stack && base) return stack + ' · ' + base;
    if (stack) return stack;
    return base || '底模未识别';
  }

  const loraRows = new Map();

  /**
   * 本机 LoRA 的展示图：机器人下载 LoRA 时会连图一起存成 `<模型名>.preview.png`，
   * 这里按名字回读（/api/lora/preview）。老下载没有图就显示"无图"，可以点上面「补抓展示图」。
   */
  /**
   * 列表行里的竖版小封面：本机 LoRA 的展示图、样式的预览图共用。
   * 没有图给"无图"占位；图取不到（401/404）也退回占位，不显示成坏图。
   */
  function previewThumb(src, caption) {
    const box = el('div', 'row-thumb');
    if (!src) { box.appendChild(el('div', 'row-nocover', '无图')); return box; }
    const link = imageNode(src, caption, 'row-cover');
    const image = link.querySelector('img');
    if (image) image.addEventListener('error', () => box.replaceChild(el('div', 'row-nocover', '无图'), link));
    box.appendChild(link);
    return box;
  }

  function loraRow(item) {
    const li = el('li', 'fresh');
    li.appendChild(el('span', 'num', '#' + (item.number || '')));
    li.appendChild(previewThumb(item.preview ? loraPreviewUrl(item.name) : '', item.name));
    const name = el('span', 'name');
    const label = el('span', 'editable', item.name);
    label.title = '点一下直接改名';
    // 改名：磁盘上的 .safetensors 会被重命名，并同步你个人 prompt 里的 LoRA 标签。
    const startRename = () => {
      const current = li.dataset.name || item.name;
      inlineRename(label, current, (next) => {
        runCommands(['.lora rename "' + current + '" "' + next + '"'], null, false)
          .then(() => { toast('LoRA 已改名：' + current + ' → ' + next); loadLoras(); });
      }, loadLoras);
    };
    label.onclick = startRename;
    name.appendChild(label);
    if (item.alias) name.appendChild(el('div', 'sub', '别名：' + item.alias));
    // 归属栈 + 底模：栈与当前 Forge 预设不一致时不能直接用（换底模不换栈会出全灰废图）。
    const baseLine = el('div', 'sub lora-base', loraBaseText(item));
    if (item.evidence) baseLine.title = '判定依据：' + item.evidence;
    name.appendChild(baseLine);
    li.appendChild(name);
    const acts = el('div', 'acts');
    acts.appendChild(actionButton('加载', () => {
      const current = li.dataset.name || item.name;
      runCommands(['.lora load #' + (li.dataset.number || item.number)], null, false)
        .then(() => toast('已加载 LoRA：' + current));
    }));
    const rename = actionButton('改名', startRename, 'ghost');
    rename.title = '重命名本地 LoRA 文件（.safetensors），并同步你个人 prompt 里的 LoRA 标签';
    acts.appendChild(rename);
    acts.appendChild(actionButton('删除', async () => {
      const current = li.dataset.name || item.name, number = li.dataset.number || item.number;
      if (!await askConfirm('从磁盘删除 LoRA「' + current + '」？\n文件会被真的删掉，无法撤销。',
          { title: '删除 LoRA', confirmText: '删除', danger: true })) return;
      const capture = await runCommands(['.lora delete #' + number], null, false);
      if (!capture) return;
      const row = loraRows.get(current);
      if (row) { row.remove(); loraRows.delete(current); }
      toast('已删除 LoRA：' + current);
      loadLoras().catch(() => {});   // 后台对齐编号
    }, 'danger'));
    li.appendChild(acts);
    return li;
  }

  /**
   * Civitai 搜索结果：直接渲染在「Civitai 搜索」卡片里（封面图 + 名称/基础模型/下载数/训练词/大小），
   * 不再像 QQ 那样把文本和封面当一条消息发出去。封面走 /api/civitai/thumb 由机器人代取
   * （浏览器不直连图床：避免被登录/年龄门挡掉，也不暴露本机地址）。
   */
  function renderCivitai(results, options) {
    const list = $('civitai-list');
    if (!list) return null;
    const items = Array.isArray(results) ? results : [];
    list.innerHTML = '';
    if (options && options.loading) {
      list.appendChild(el('div', 'civitai-empty', options.loading));
      return items;
    }
    if (!items.length) {
      list.appendChild(el('div', 'civitai-empty', (options && options.empty) || '搜索到的候选会显示在这里：输入关键词后点「搜索」。'));
      return items;
    }
    items.forEach((item, index) => {
      const number = item.number || index + 1;
      const card = el('div', 'civitai-card');
      const thumb = el('div', 'civitai-thumb');
      if (item.cover) {
        // 点封面用查看器放大（和其它图片一致）
        thumb.appendChild(imageNode(coverUrl(item.cover), item.name || '', 'civitai-cover'));
      } else {
        thumb.appendChild(el('div', 'civitai-nocover', '暂无封面'));
      }
      const meta = el('div', 'civitai-meta');
      const head = el('div', 'civitai-name');
      head.appendChild(el('b', null, '#' + number));
      head.appendChild(document.createTextNode(' ' + (item.name || '未命名')));
      if (item.nsfw) head.appendChild(el('span', 'civitai-badge warn', 'NSFW'));
      meta.appendChild(head);
      const facts = [item.baseModel ? '基础模型：' + item.baseModel : null,
        item.downloads ? '下载 ' + item.downloads.toLocaleString('zh-CN') + ' 次' : null,
        item.sizeKb ? '约 ' + (item.sizeKb / 1024).toFixed(1) + ' MB' : null].filter(Boolean);
      if (facts.length) meta.appendChild(el('div', 'civitai-facts', facts.join(' · ')));
      if ((item.trainedWords || []).length) {
        // 训练词有的模型会给整段示例 prompt：卡片上只显示前两条、每条截断，完整内容鼠标悬停可见。
        const words = item.trainedWords.slice(0, 2)
          .map((word) => (word.length > 80 ? word.slice(0, 80) + '…' : word));
        const more = item.trainedWords.length > 2 ? '（等 ' + item.trainedWords.length + ' 条）' : '';
        const line = el('div', 'civitai-words', '训练词：' + words.join('、') + more);
        line.title = item.trainedWords.join('\n');
        meta.appendChild(line);
      }
      const acts = el('div', 'civitai-acts');
      // 权重不在这里给：机器人默认就是 1，要别的权重可以下载后用本地列表的「加载」调。
      // 走控制台自己的接口（不是指令通道），发完立刻开始轮询进度，不等下载跑完。
      acts.appendChild(actionButton('下载', () => startLoraJob('/api/lora/download', { url: item.url, weight: 1 })));
      if (item.url) {
        const link = el('a', 'civitai-link', '打开模型页');
        link.href = item.url; link.target = '_blank'; link.rel = 'noreferrer';
        acts.appendChild(link);
      }
      meta.appendChild(acts);
      card.appendChild(thumb);
      card.appendChild(meta);
      list.appendChild(card);
    });
    return items;
  }

  /** 封面代取地址（<img src> 只能带查询串，所以 token 也放在 query 上）。 */
  function coverUrl(url) {
    return '/api/civitai/thumb?token=' + encodeURIComponent(state.token) + '&url=' + encodeURIComponent(url);
  }

  /**
   * 翻页控件：只有搜索过一次才显示。页数用服务端给的 page 与 hasMore——Civitai 的关键词搜索
   * **不返回总页数**（cursor 分页），所以知道总数时才写「第 X/Y 页」，否则只写「第 X 页」，
   * 能不能再翻一页由 hasMore 决定（下一页按钮据此禁用）。
   */
  function renderCivitaiPager(data) {
    const pager = $('civitai-pager');
    if (!pager) return;
    pager.hidden = false;
    const page = Number(data.page) || 1;
    const total = Number(data.totalPages);
    $('civitai-page').textContent = (data.totalPagesKnown && total > 0) ? '第 ' + page + '/' + total + ' 页' : '第 ' + page + ' 页';
    $('civitai-prev').disabled = page <= 1;
    $('civitai-next').disabled = !data.hasMore;
    $('civitai-page-hint').textContent = (data.count || 0) + ' 项（卡片上的 #N 是本页编号）';
  }

  /** 搜一页：query 为空时沿用上一次的搜索词（翻页就不必重敲）。 */
  function searchCivitai(page) {
    const input = $('lora-query');
    const typed = input ? input.value.trim() : '';
    const query = typed || state.civitaiQuery;
    if (!query) { toast('请先填搜索词。'); return Promise.resolve(); }
    state.civitaiQuery = query;
    renderCivitai([], { loading: '正在搜索 Civitai：' + query + '…' });
    return api('/api/civitai/search', { body: { query, page: page || 1, scope: scope() } }).then((data) => {
      state.civitaiQuery = data.query || query;
      state.civitaiPage = Number(data.page) || 1;
      renderCivitai(data.results, { empty: data.note || '没有找到匹配的 LoRA。' });
      renderCivitaiPager(data);
      if ($('lora-status')) {
        const at = '「' + state.civitaiQuery + '」第 ' + state.civitaiPage + ' 页：' + (data.count || 0) + ' 项'
          + (data.hasMore ? '（还有下一页）' : '（已经是最后一页）');
        $('lora-status').textContent = at;
      }
      return data;
    }).catch((error) => {
      renderCivitai([], { empty: '搜索失败：' + error.message });
    });
  }

  /**
   * 本机 LoRA 展示图 / 样式预览图的地址。同样是 `<img>` 带不了 Authorization 头，
   * 令牌只能挂在查询串上（少了它图会 401，页面上看起来就是"无图"）。
   */
  function loraPreviewUrl(name) {
    return '/api/lora/preview?token=' + encodeURIComponent(state.token) + '&name=' + encodeURIComponent(name);
  }

  function stylePreviewUrl(name) {
    return '/api/style/preview?token=' + encodeURIComponent(state.token) + '&name=' + encodeURIComponent(name);
  }

  // ---------------------------------------------------------------- 提示词集

  async function loadFunctions() {
    return renderFunctions(await api('/api/functions', { body: { scope: scope() } }));
  }

  /** 提示词集的保存/加载/改名/删除都是本机 JSON：直接走 /api/functions/edit。 */
  async function editFunctions(action, name, extra) {
    try {
      const data = await api('/api/functions/edit', { body: Object.assign({ action, name: name || '', scope: scope() }, extra || {}) });
      renderFunctions(data);
      if (data.message) toast(data.message);
      return data;
    } catch (error) {
      if (String(error.message) !== 'unauthorized') toast(error.message);
      await loadFunctions().catch(() => {});
      return null;
    }
  }

  function renderFunctions(data) {
    $('function-active').textContent = '已加载：' + ((data.active || []).join('、') || '（无）');
    const list = $('function-list');
    list.innerHTML = '';
    if (!(data.functions || []).length) list.appendChild(el('li', 'muted', '还没有保存过提示词集。'));
    (data.functions || []).forEach((item) => {
      const li = el('li', item.active ? 'current' : '');
      const box = el('div', 'name');
      box.appendChild(document.createTextNode(item.name + (item.active ? ' ★' : '')));
      if (item.positive) box.appendChild(el('div', 'sub', item.positive.slice(0, 90)));
      li.appendChild(box);
      const acts = el('div', 'acts');
      acts.appendChild(actionButton(item.active ? '移出' : '加载',
        () => editFunctions(item.active ? 'remove' : 'load', item.name)));
      acts.appendChild(actionButton('查看', () => showInfo('提示词集：' + item.name,
        '正向：\n' + (item.positive || '（空）') + '\n\n反向：\n' + (item.negative || '（空）'),
        { text: '定义共享，加载关联按个人：加载会把这些词条追加到你个人的 prompt。' }), 'ghost'));
      acts.appendChild(actionButton('改名', async () => {
        const target = await askInput('提示词集改名', item.name, '新名称', '改名');
        if (target) editFunctions('rename', item.name, { newName: target });
      }, 'ghost'));
      acts.appendChild(actionButton('删除', async () => {
        if (!await askConfirm('删除提示词集「' + item.name + '」？', { title: '删除提示词集', confirmText: '删除', danger: true })) return;
        editFunctions('delete', item.name);
      }, 'danger'));
      li.appendChild(acts);
      list.appendChild(li);
    });
  }

  /** 参数预设有本机 JSON：保存/覆盖/删除走 /api/presets/edit（「加载」会改 SD 参数，仍走指令）。 */
  async function editPresets(action, name) {
    if (!String(name || '').trim()) { toast('请填写预设名称。'); return null; }
    try {
      const data = await api('/api/presets/edit', { body: { action, name: String(name).trim() } });
      renderPresets(data);
      if (data.message) toast(data.message);
      return data;
    } catch (error) {
      if (String(error.message) !== 'unauthorized') toast(error.message);
      await loadPresets().catch(() => {});
      return null;
    }
  }

  async function loadPresets() {
    return renderPresets(await api('/api/presets'));
  }

  /**
   * Forge / Forge Neo 的预设栈（底模 + VAE + 文本编码器）。切换会把该栈的采样方法 / 调度器 /
   * 尺寸 / 步数 / CFG / Shift 一起采纳成机器人设置——参数是随请求发的，不换就会拿旧栈参数跑新模型。
   */
  async function loadForgePresets() {
    const select = $('forge-preset');
    // 先把下拉置成"读取中"，读取失败也要给出可点的提示——空下拉会让人以为功能没做。
    if (select && !select.options.length) {
      select.innerHTML = '';
      const loading = el('option', null, '（正在读取 Forge 预设…）');
      loading.value = '';
      select.appendChild(loading);
    }
    try {
      renderForgePresets(await api('/api/sd/presets'));
    } catch (error) {
      const note = $('forge-note');
      if (String(error.message) === 'unauthorized') return;
      if (select) {
        select.innerHTML = '';
        const failed = el('option', null, '读取失败：' + error.message + '（点「刷新列表」重试）');
        failed.value = '';
        select.appendChild(failed);
      }
      if (note) { note.textContent = '读取预设失败：' + error.message; note.className = 'bad'; }
    }
  }

  function renderForgePresets(data) {
    const select = $('forge-preset');
    if (!select) return;                       // 预设下拉只在出图页面
    const state = $('forge-state'), note = $('forge-note');
    const presets = (data && data.presets) || [];
    const forge = !!(data && data.forge);
    if (state) state.textContent = forge ? ('当前：' + (data.active || '（未选）')) : '当前 WebUI 不是 Forge / Forge Neo';
    select.innerHTML = '';
    if (!forge || !presets.length) {
      const option = el('option', null, forge ? '（Forge 里还没有配置预设）' : '（不是 Forge，用「基础模型」下拉即可）');
      option.value = '';
      select.appendChild(option);
      select.disabled = true;
      if ($('forge-apply')) $('forge-apply').disabled = true;
      if (note) { note.textContent = forge ? '在 Forge 页面里给每个预设选好底模后，这里就会列出来。' : '底模直接在「基础模型」里换。'; note.className = 'muted'; }
      return;
    }
    select.disabled = false;
    if ($('forge-apply')) $('forge-apply').disabled = false;
    presets.forEach((preset) => {
      const option = el('option', null, (preset.active ? '▶ ' : '') + preset.preset + ' ← ' + (preset.checkpoint || '（没有底模）'));
      option.value = preset.preset;
      if (preset.active) option.selected = true;
      select.appendChild(option);
    });
    const active = presets.find((preset) => preset.active) || presets[0];
    const bits = [active.sampler, active.scheduler ? '调度器 ' + active.scheduler : '', active.steps ? active.steps + ' 步' : '',
      active.cfg ? 'CFG ' + active.cfg : '', active.distilledCfg ? 'Shift ' + active.distilledCfg : '',
      (active.width && active.height) ? active.width + '×' + active.height : '',
      (active.modules && active.modules.length) ? '模块 ' + active.modules.length + ' 个' : ''].filter(Boolean);
    if (note) { note.textContent = active.preset + '：' + bits.join('，') + '；当前底模 ' + ((data && data.model) || '（未知）'); note.className = 'muted'; }
  }

  async function switchForgePreset(name) {
    const note = $('forge-note');
    try {
      if (note) { note.textContent = '正在切换…'; note.className = 'muted'; }
      const data = await api('/api/sd/preset', { body: { name } });
      renderForgePresets(data);
      if (note && data.notice) { note.textContent = data.notice; note.className = 'ok'; }
      toast(data.notice || '已切换预设');
      await Promise.all([loadStatus().catch(() => {}), loadOptions().catch(() => {})]);
    } catch (error) {
      if (String(error.message) !== 'unauthorized' && note) { note.textContent = '切换失败：' + error.message; note.className = 'bad'; }
    }
  }

  function renderPresets(data) {
    const list = $('preset-list');
    if (!list) return null;                     // 预设列表只在出图页面
    list.innerHTML = '';
    if (!(data.presets || []).length) list.appendChild(el('li', 'muted', '还没有保存过参数预设。'));
    (data.presets || []).forEach((item) => {
      const preset = item.preset || {};
      // preset 的字段是平铺的（sampler_name/width/height/steps/cfg_scale/seed/checkpoint）；parameters 是旧格式。
      const params = preset.parameters || preset;
      const sampler = preset.sampler_name || preset.sampler || params.sampler_name || '';
      const li = el('li');
      const box = el('div', 'name');
      box.appendChild(document.createTextNode(item.name));
      box.appendChild(el('div', 'sub', [preset.width + '×' + preset.height, sampler, 'steps ' + (params.steps ?? '-'),
        'cfg ' + (params.cfg_scale ?? '-'), params.checkpoint || '跟随 WebUI'].filter(Boolean).join(' · ')));
      li.appendChild(box);
      const acts = el('div', 'acts');
      // 名称直接跟在指令后面（之前带引号发出去，后端当成名字的一部分 → "参数预设不存在"）。
      acts.appendChild(actionButton('加载', () => runCommands(['.preset load ' + item.name]).then(loadStatus)));
      acts.appendChild(actionButton('查看', () => showInfo('参数预设：' + item.name,
        ['图片尺寸：' + preset.width + ' × ' + preset.height + ' 像素',
          '采样方法：' + (sampler || '-'),
          '步数：' + (params.steps ?? '-'),
          'CFG：' + (params.cfg_scale ?? '-'),
          '种子：' + (params.seed ?? '-'),
          '基础模型：' + (params.checkpoint || '跟随 WebUI 当前模型')].join('\n'),
        { text: '加载后只影响之后提交的生成任务；当前提示词保持不变。' }), 'ghost'));
      acts.appendChild(actionButton('删除', async () => {
        if (!await askConfirm('删除参数预设「' + item.name + '」？', { title: '删除参数预设', confirmText: '删除', danger: true })) return;
        editPresets('remove', item.name);
      }, 'danger'));
      li.appendChild(acts);
      list.appendChild(li);
    });
  }

  // ---------------------------------------------------------------- 生成参数（改完即生效）

  /** 面板里当前显示的值；空着的字段不发，表示这次不动它。 */
  function generationPayload() {
    const payload = {};
    GEN_FIELDS.forEach(([id, key]) => {
      const node = $(id);
      if (!node) return;
      const value = String(node.value == null ? '' : node.value).trim();
      if (value === '') return;
      payload[key] = (key === 'sampler' || key === 'model') ? value : Number(value);
    });
    return payload;
  }

  /** 把面板里的值立刻发回机器人生效；同一份值不重复发。 */
  async function applyGeneration(quiet) {
    const payload = generationPayload();
    const signature = JSON.stringify(payload);
    if (!Object.keys(payload).length || signature === appliedGeneration) return null;
    try {
      const status = await api('/api/generation', { body: payload });
      appliedGeneration = signature;
      applyGenerationSummary(status);
      if (!quiet && status.message) toast(status.message);
      return status;
    } catch (error) {
      if (String(error.message) !== 'unauthorized') toast('没能生效：' + error.message);
      await loadStatus().catch(() => {});   // 回到机器人里真正在用的值
      return null;
    }
  }

  function applyGenerationSummary(status) {
    const box = $('gen-applied');
    if (!box) return;
    const gen = (status && status.generation) || {};
    box.textContent = '当前生效：' + [gen.width + ' × ' + gen.height + ' 像素', gen.sampler, '步数 ' + gen.steps,
      'CFG ' + gen.cfg, '种子 ' + gen.seed, '图片上限 ' + ((status && status.imageCount) ?? '-') + ' 张/条',
      gen.model ? '模型 ' + gen.model : null].filter(Boolean).join(' · ');
  }

  // ---------------------------------------------------------------- SD 生成进度

  let progressTimer = null;

  /** 进度条数据直接来自 SD WebUI（/sdapi/v1/progress）；队列状态来自机器人自己。 */
  async function loadProgress() {
    const box = $('gen-progress-box');
    if (!box) return;
    try { renderProgress(await api('/api/progress')); }
    catch { /* 读不到进度不影响其它功能 */ }
    // 队列有任务时顺带刷新任务队列：状态、进度条都是实时的。
    if (state.status?.generation?.status) loadTasks().catch(() => {});
    if (!state.status?.generation?.status) stopProgressPolling();
  }

  function renderProgress(data) {
    const box = $('gen-progress-box'), bar = $('gen-progress-bar'), text = $('gen-progress-text');
    if (!box) return;
    const queue = String(data.queue || '').trim();
    // 队列文字永远有内容（空闲时是「生成队列：空闲…」），判断"有没有任务"要看状态词，
    // 否则队列空着也会一直显示进度条 + 「排队中」，而队列详情现在归「任务队列」卡片。
    const busy = /运行中/.test(queue);
    // 队列状态文字跟着进度轮询一起刷新（1.5 秒一次），「任务队列」卡片上的摘要总是最新的。
    updateQueueSummary(queue);
    if (data.reachable === false) {
      // SD 没在跑/地址不对，和"读到了但空闲"不是一回事：单独说清楚。
      box.hidden = false;
      box.classList.remove('done');
      bar.style.width = '0%';
      text.textContent = data.text || '读不到 SD 进度（SD WebUI 未启动？）';
      return;
    }
    if (!data.running && !busy) {
      box.hidden = true;
      bar.style.width = '0%';
      text.textContent = '';
      return;
    }
    box.hidden = false;
    box.classList.toggle('done', !data.running && busy);
    bar.style.width = (data.running ? Math.max(3, Math.min(100, data.percent)) : busy ? 100 : 0) + '%';
    text.textContent = data.running ? data.text : 'SD 还没开始（排在队列里等）';
  }

  function startProgressPolling() {
    const box = $('gen-progress-box');
    if (box) box.hidden = false;
    loadProgress();
    if (!progressTimer) progressTimer = setInterval(loadProgress, 1500);
  }

  function stopProgressPolling() {
    clearInterval(progressTimer);
    progressTimer = null;
  }

  // ---------------------------------------------------------------- 图片与日志

  async function loadImages() {
    const grid = $('image-grid');
    if (!grid) return null;                     // 图片网格只在出图页面
    const data = await api('/api/images', { body: { limit: 60 } });
    grid.innerHTML = '';
    // 后端已按「新 → 旧」给：最近生成的图片 + 还没领取的；不再反过来排。
    const images = data.images || [];
    const note = $('image-note');
    if (!images.length) {
      if (note) note.textContent = '还没有生成过图片。';
      grid.appendChild(el('div', 'muted', '生成完成后图片会留在这里，刷新、重启都不会丢。'));
      return images;
    }
    const pending = images.filter((image) => image.pending).length;
    if (note) note.textContent = '最近生成 ' + images.length + ' 张'
      + (pending ? '，其中 ' + pending + ' 张待领取' : '（都已领取）');
    images.forEach((image) => {
      // 缩略图点开进查看器放大（列表本身不再跳新标签页）。
      const link = imageNode(imageUrl(image.path), image.name + '（' + Math.round(image.size / 1024) + ' KB）');
      const meta = el('div', 'meta', image.name + '\n' + Math.round(image.size / 1024) + ' KB'
        + (image.pending ? '\n待领取' : ''));
      link.appendChild(meta);
      grid.appendChild(link);
    });
    return images;
  }

  // ---------------------------------------------------------------- 任务队列

  /**
   * 「任务队列」卡片顶部那行摘要 + 徽章（任务本身在下面的卡片里，出图面板不再重复显示）。
   * 队列状态文字来自机器人的 generation.status（/api/status 与 /api/progress 的 queue 字段同源）。
   */
  function updateQueueSummary(text) {
    const line = String(text || '').split('\n')[0].trim();
    const summary = $('task-summary');
    if (summary) summary.textContent = line || '队列是空的。';
    const badge = $('queue-badge');
    if (badge) badge.textContent = /运行中/.test(line) ? '队列有任务' : '空闲';
  }

  /** 任务状态 → 徽章样式。 */
  function taskKind(task) {
    const status = String(task.status || '');
    if (status.includes('取消')) return 'cancelled';
    if (status.includes('挂起') || task.suspended) return 'held';
    if (status.includes('生成中')) return 'running';
    if (status.includes('失败') || Number(task.failed)) return 'failed';
    if (status.includes('完成')) return 'done';
    return 'waiting';
  }

  /** 任务队列：徽章 + 进度条 + 图片/失败统计，生成期间每 1.5 秒自动刷新。 */
  async function loadTasks() {
    const box = $('task-list');
    if (!box) return;
    let tasks = [];
    try { tasks = await api('/api/tasks'); } catch { return; }
    const atBottom = window.scrollY + window.innerHeight > document.body.scrollHeight - 80;
    box.innerHTML = '';
    if (!tasks.length) {
      box.appendChild(el('div', 'task-empty', '队列是空的（去「生成」里点开始生成，或直接说「生成三张」）'));
      return;
    }
    tasks.forEach((task) => {
      const kind = taskKind(task);
      const row = el('div', 'task ' + kind);
      const head = el('div', 'task-head');
      head.appendChild(el('span', 'task-num', '#' + task.number));
      head.appendChild(el('span', 'task-badge', String(task.status || '')));
      const percent = Number.isFinite(Number(task.percent)) ? Number(task.percent)
        : (Number(task.total) ? Math.round(100 * Number(task.done) / Number(task.total)) : 0);
      head.appendChild(el('span', 'task-count', task.done + '/' + task.total + ' 次'));
      if (Number(task.images)) head.appendChild(el('span', 'task-chip', '已生成 ' + task.images + ' 张'));
      if (Number(task.failed)) head.appendChild(el('span', 'task-chip bad', '失败 ' + task.failed + ' 次'));
      if (task.suspended) head.appendChild(el('span', 'task-chip warn', '已挂起'));
      row.appendChild(head);
      const track = el('div', 'task-track');
      const bar = el('div', 'task-bar');
      bar.style.width = Math.max(0, Math.min(100, percent)) + '%';
      track.appendChild(bar);
      row.appendChild(track);
      const acts = el('div', 'task-acts');
      acts.appendChild(actionButton('置顶', () => taskAction('first', task.number), 'ghost'));
      acts.appendChild(actionButton(task.suspended ? '继续' : '挂起', () => taskAction(task.suspended ? 'resume' : 'hold', task.number), 'ghost'));
      acts.appendChild(actionButton('取消', () => taskAction('cancel', task.number), 'danger'));
      row.appendChild(acts);
      box.appendChild(row);
    });
    if (atBottom) window.scrollTo({ top: document.body.scrollHeight });
  }

  async function taskAction(action, number) {
    try {
      const result = await api('/api/tasks/action', { body: { action, number } });
      toast(result.message.split('\n')[0]);
      await loadTasks();
      await loadImages();
      await loadStatus().catch(() => {});   // 队列摘要与徽章跟着动作走（挂起/取消后不用等下一次轮询）
    } catch (error) { if (String(error.message) !== 'unauthorized') toast(error.message); }
  }

  // ---------------------------------------------------------------- 真 CLI 控制台

  const TERMINAL_LIMIT = 1500;         // 终端里最多保留的行数，避免长跑后卡顿
  const FULLSCREEN_KEY = 'pixiko-console-full';   // 全屏偏好（默认全屏）
  /** 控制台里不用加点和斜杠：首词命中这些名字就当指令，否则当作和机器人说话。 */
  const CLI_COMMANDS = new Set(['help', 'yh', 'liv', 'get', 'settings', 'chat', 'admin', 'char', 'batch',
    'sampler', 'style', 'size', 'steps', 'cfg', 'seed', 'model', 'prompt', 'promptr', 'preset', 'function',
    'lora', 'gen', 'rg', 'imgcnt', 'usage', 'map', 'progen', 'infix', 'progress', 'sd', 'jrlp',
    '进度', '帮助', '老婆', '今日老婆', '强娶', '离婚']);
  let logAnchor = null;                // 上一次渲染到的最后一行日志
  let logKey = '';                     // 来源 + 过滤条件：变了就整屏重来

  /**
   * 往终端追加一行（或一张图）：插在提示符**之前**，所以提示符始终紧跟最新一行日志。
   * 提示符后面还有一片空白（`#terminal-tail`）：日志一多、提示符被顶到底部时，
   * 那片空白把它托上来（滚到底时提示符不会贴死底边）。只有本来就在底部时才自动跟随新日志。
   */
  function appendTerminal(kind, text, imagePath) {
    const body = $('terminal-body');
    if (!body) return;
    const atBottom = body.scrollTop + body.clientHeight > body.scrollHeight - 40;
    const node = el('div', 'line ' + kind);
    if (text !== undefined && text !== null && text !== '') node.textContent = text;
    if (imagePath) {
      // 终端里的图也走查看器（点一下放大，Esc 关闭）。
      node.appendChild(imageNode(imageUrl(imagePath), String(imagePath).replace(/^.*[\\/]/, '')));
    }
    const prompt = $('terminal-form');
    if (prompt && prompt.parentElement === body) body.insertBefore(node, prompt);
    else body.appendChild(node);
    // 只数日志行：提示符与底部空白不算行数，别把它们挤掉。
    let lines = body.querySelectorAll(':scope > .line');
    while (lines.length > TERMINAL_LIMIT) { body.removeChild(lines[0]); lines = body.querySelectorAll(':scope > .line'); }
    if (atBottom) body.scrollTop = body.scrollHeight;
  }

  /** 日志以"追加"的方式进终端：每 4 秒跟随一次也只补新行，不整屏重画。 */
  async function loadLogs() {
    if (!$('terminal-body')) return;
    const source = $('logs-source') ? $('logs-source').value : 'all';
    const filter = $('logs-filter') ? $('logs-filter').value.trim() : '';
    const key = source + '\u0000' + filter;
    let lines = [];
    try {
      const data = await api('/api/logs', { body: { lines: 400, source } });
      lines = (data.lines || []).filter((line) => !filter || line.includes(filter));
    } catch (error) {
      if (String(error.message) === 'unauthorized') return;
      appendTerminal('err', '读取日志失败：' + error.message);
      return;
    }
    if (key !== logKey) { logKey = key; logAnchor = null; clearTerminalLines(); }
    let start = 0;
    if (logAnchor !== null) {
      const at = lines.lastIndexOf(logAnchor);
      if (at >= 0 && at + 1 <= lines.length) start = at + 1;
      else { clearTerminalLines(); start = 0; }
    }
    if (lines.length) {
      for (let index = start; index < lines.length; index++) appendTerminal('log', lines[index]);
      logAnchor = lines[lines.length - 1];
    } else if (logKey === key && $('terminal-body').querySelectorAll('.line').length === 0) {
      appendTerminal('sys', '（这一路还没有日志）');
    }
  }

  /** 只清日志/回显行，提示符那一行留着。 */
  function clearTerminalLines() {
    const body = $('terminal-body');
    if (!body) return;
    body.querySelectorAll('.line').forEach((node) => node.remove());
  }

  /** 清屏：只清显示，不动日志文件；提示符下面那片空白也收回初始高度。 */
  function clearTerminal() {
    clearTerminalLines();
    resetTerminalTail();
    logAnchor = null;
    appendTerminal('sys', '已清屏（日志文件不受影响，刷新会重新读出来）');
  }

  /**
   * 提示符下面的空白（`#terminal-tail`）：它是「可增长的」。
   * 一直往下滚、碰到边界时自动再加一屏左右，于是可以**无限往下滑**，
   * 提示符就能一直被顶到更上面（滚到底只会看到越来越多的空白，不会有硬边界）。
   */
  function resetTerminalTail() {
    const tail = $('terminal-tail');
    if (tail) tail.style.height = '';        // 回到 CSS 里的初始高度
  }

  function growTerminalTail() {
    const body = $('terminal-body'), tail = $('terminal-tail');
    if (!body || !tail) return false;
    if (body.scrollTop + body.clientHeight < body.scrollHeight - 4) return false;   // 只有到边界才加
    const current = tail.getBoundingClientRect().height;
    const step = Math.max(240, Math.round(body.clientHeight * 0.9));
    tail.style.height = Math.round(current + step) + 'px';
    return true;
  }

  /** 滚轮/触屏/翻页键往下顶到底时补空白（程序自己的自动滚动不会触发，免得无限长高）。 */
  function bindTerminalTail() {
    const body = $('terminal-body');
    if (!body) return;
    body.addEventListener('wheel', (event) => { if (event.deltaY > 0) growTerminalTail(); }, { passive: true });
    body.addEventListener('touchmove', () => growTerminalTail(), { passive: true });
    body.addEventListener('keydown', (event) => {
      if (event.key === 'PageDown' || event.key === 'End') growTerminalTail();
    });
  }

  /** 控制台是否全屏（默认全屏：body 里其它控件全部让位，终端独占窗口）。 */
  function consoleFullscreen() { return document.body.classList.contains('console-full'); }

  function applyConsoleFullscreen(on, remember = true) {
    document.body.classList.toggle('console-full', !!on);
    if (remember) localStorage.setItem(FULLSCREEN_KEY, on ? 'on' : 'off');
    const button = $('terminal-fullscreen');
    if (button) button.textContent = on ? '退出全屏' : '全屏';
    const input = $('terminal-input');
    if (input) input.focus();
  }

  /** 默认全屏：跟着页签进出（记得住用户手动退出过）。 */
  function syncConsoleFullscreen(tab) {
    const preference = localStorage.getItem(FULLSCREEN_KEY) !== 'off';
    if (tab === 'logs' && preference) applyConsoleFullscreen(true, false);
    else if (tab !== 'logs') applyConsoleFullscreen(false, false);
  }

  /** 把用户输入变成真正的指令：`help` → `.help`；已经带前缀的原样；认不出的返回 null（交给聊天）。 */
  function terminalCommand(text) {
    const trimmed = String(text || '').trim();
    if (!trimmed) return null;
    if (/^[./]/.test(trimmed)) return trimmed;
    const first = trimmed.split(/\s+/)[0].toLowerCase();
    return CLI_COMMANDS.has(first) ? '.' + trimmed : null;
  }

  /** 终端里敲一条：指令走指令通道（和 QQ、控制台按钮完全同源），其余当作和机器人说话。 */
  async function runTerminal(raw) {
    const text = String(raw || '').trim();
    if (!text) return;
    appendTerminal('cmd', 'pixiko@web:~$ ' + text);
    state.terminalHistory.push(text);
    if (state.terminalHistory.length > 100) state.terminalHistory.shift();
    state.terminalCursor = state.terminalHistory.length;
    const command = terminalCommand(text);
    try {
      if (command) {
        const capture = await api('/api/command', { body: { command, scope: scope() } });
        if (capture.quest) questCloud('任务 #' + capture.quest + ' 已下达：' + command, capture.quest);
        if (/\b(gen|get|rg)\b/i.test(command)) loadTasks().catch(() => {});
        await followTerminal(capture.id);
        if (/\b(gen|get|rg)\b/i.test(command)) loadTasks().catch(() => {});
      } else {
        const result = await api('/api/chat', { body: { message: text, execute: true, scope: scope() } });
        if (result.quest) questCloud('任务 #' + result.quest + ' 已下达：' + text, result.quest);
        (result.reply || '(空回复)').split('\n').forEach((line) => appendTerminal('out', line));
        if (result.commands && result.commands.length) appendTerminal('sys', '执行指令：' + result.commands.join('  '));
        if (result.captureId) await followTerminal(result.captureId);
      }
    } catch (error) {
      if (String(error.message) !== 'unauthorized') appendTerminal('err', error.message);
    }
    appendTerminal('out', '');
  }

  /**
   * 跑一条"目的是看内容"的指令（`.help`／`.style list`／`.chat model` 这类），
   * 把最后一段回复弹进对话框——面板底部不再堆回执卡，这些内容总得有个地方看。
   */
  async function runInfo(command, title) {
    const capture = await runCommands([command]);
    const texts = ((capture && capture.texts) || []).filter((line) => line && !/正在通过 DeepSeek/.test(line));
    if (texts.length) showInfo(title || command, texts[texts.length - 1]);
    else toast('没有可显示的内容。');
  }

  /** 队列里还有等待/生成中/挂起的任务吗（终端跟随用，只读接口、不重画面板）。 */
  async function tasksActive() {
    try {
      const tasks = await api('/api/tasks');
      return (tasks || []).some((task) => ['running', 'waiting', 'held'].includes(taskKind(task)));
    } catch { return false; }
  }

  /**
   * 等一条回执真的跑完（`done` 且不在忙）。用于 `.infix` 这种要等 DeepSeek 的指令：
   * runCommands 只保证"指令已受理"，跑完再刷新面板才不会刷到旧内容。
   */
  async function waitCaptureSettled(captureId, timeoutMs = 10 * 60 * 1000) {
    if (!captureId) return null;
    const deadline = Date.now() + timeoutMs;
    let quietSince = Date.now();
    for (let attempt = 0; Date.now() < deadline; attempt++) {
      await new Promise((resolve) => setTimeout(resolve, attempt === 0 ? 250 : 400));
      let capture;
      try { capture = await api('/api/capture', { body: { id: captureId } }); }
      catch { return null; }   // 回执已被回收：当作结束
      if (capture.busy) { quietSince = Date.now(); continue; }
      if (capture.done && Date.now() - quietSince > 600) return capture;
    }
    return null;
  }

  /**
   * 跟着一条回执把文本与图片打进终端，直到它真的收完。
   * 收工条件不能只看 busy（它只表示 DeepSeek/LoRA 这类后台工作）：指令刚受理那一瞬间回执还是空的，
   * 只看 busy 会在 120ms 的第一次轮询就退出，`.rg` 的图、`.gen` 的图都打不出来。
   * 这里等三件事：指令执行体跑完（done）、这一轮的内容都打完了、并且连续 1.2 秒没有新东西再来
   * （回复与图片是异步回执，通常比 done 晚一两百毫秒到），队列里也不能还有在跑的任务。
   */
  async function followTerminal(captureId) {
    let printed = 0, images = 0, queueActive = false, quietSince = Date.now();
    for (let attempt = 0; attempt < 2400; attempt++) {
      await new Promise((resolve) => setTimeout(resolve, attempt === 0 ? 120 : 400));
      const current = await api('/api/capture', { body: { id: captureId } });
      const texts = current.texts || [], list = current.images || [];
      let grew = false;
      for (; printed < texts.length; printed++) {
        grew = true;
        texts[printed].split('\n').forEach((line) =>
          appendTerminal(/操作失败|失败|错误|不正确|无效/.test(line) ? 'err' : 'out', line));
      }
      for (; images < list.length; images++) { grew = true; appendTerminal('out', '', list[images].file); }
      if (grew) quietSince = Date.now();
      if (attempt % 5 === 0) queueActive = await tasksActive();
      const caughtUp = printed >= texts.length && images >= list.length;
      if (caughtUp && current.done && !current.busy && !queueActive && Date.now() - quietSince > 1200) break;
    }
  }

  /** 输入行提交：清空输入、执行、把焦点留在输入行（ssh 手感）。 */
  async function submitTerminal() {
    const input = $('terminal-input');
    if (!input) return;
    const text = input.value;
    input.value = '';
    await runTerminal(text);
    input.focus();
  }

  function terminalHistoryMove(delta) {
    const history = state.terminalHistory;
    if (!history.length) return;
    state.terminalCursor = Math.max(0, Math.min(history.length, state.terminalCursor + delta));
    $('terminal-input').value = state.terminalCursor >= history.length ? '' : history[state.terminalCursor];
  }

  async function loadHelp() {
    try { const data = await api('/api/help'); $('help-body').textContent = data.help || ''; } catch { /* 忽略 */ }
  }

  async function loadUsage(query) {
    const data = await api('/api/usage', { body: { query: query || '' } });
    $('usage-body').textContent = data.text || '';
    const box = $('usage-choices');
    box.innerHTML = '';
    (data.choices || []).forEach((choice, index) => {
      const chip = el('button', 'ghost', '#' + (index + 1));
      chip.onclick = () => loadUsage(choice.startsWith('tag:') ? '搜索 ' + choice.slice(4).split(' — ')[0] : choice);
      box.appendChild(chip);
      box.appendChild(document.createTextNode(' '));
    });
  }

  /** 设置项 id → 中文名（提示语用，别把 chatFrequency 这种键名弹给用户看）。 */
  const OPTION_LABELS = { chatGlobal: '聊天全局开关', chatFrequency: '每分钟上限', chatModel: '聊天模型',
    chatThinking: '聊天思考模式', imageThinking: '生图思考模式', infixFilter: '词库约束',
    notice: '上线播报', logMirror: '日志镜像', sdAutoStart: 'SD 自动启动', sdStartOnBoot: '开机自启动 SD',
    personality: '性格设定' };

  async function setOption(key, value) {
    try {
      const status = await api('/api/settings', { body: { key, value, scope: scope() } });
      state.status = status;
      toast('已保存：' + (OPTION_LABELS[key] || key));
      applyStatus(status);
    } catch (error) { if (String(error.message) !== 'unauthorized') toast(error.message); }
  }

  async function applyStatus(status) {
    state.status = status;
    await loadStatus().catch(() => {});
  }

  // ---------------------------------------------------------------- Setup 栏目

  /**
   * 配置栏目：首次配置与账号凭据都收在这一页（数据来自 /api/config/*）。
   * 首次配置期间（还没配好 DeepSeek 密钥）这些接口对本机免令牌，所以没有令牌也能填、能存；
   * 配置完成后它们和别的接口一样要令牌，此时走控制台自己的登录页。
   */
  let setupLoadedToken = '';

  function setupChannelState(channel) {
    return (channel.keyMasked ? '已配置 ' + channel.keyMasked : '未配置密钥') + ' · 生效地址 ' + channel.effective;
  }

  function fillSetup(values) {
    $('setup-bot-name').value = values.bot_name || '';
    $('setup-owner').value = values.owner_user_id || '';
    $('setup-sd-base').value = values.sd_base_url || '';
    $('setup-sd-root').value = values.sd_root || '';
    $('setup-qq-ws').value = values.qq_ws_url || '';
    $('setup-web-url').value = location.origin;
    setupLoadedToken = values.webui_access_token || '';
    $('setup-web-token').value = setupLoadedToken;
    const image = values.channels.image, chat = values.channels.chat;
    $('setup-image-base').value = image.base || '';
    $('setup-chat-base').value = chat.base || '';
    $('setup-image-base').placeholder = image.official;
    $('setup-chat-base').placeholder = chat.official;
    setText('setup-image-state', setupChannelState(image));
    setText('setup-chat-state', setupChannelState(chat));
    setText('setup-token-state', values.webui_token_set ? '令牌已配置' : '还没有令牌（保存时会自动生成）');
  }

  /** 只发要改的字段：密钥留空＝不改（与命令行向导同一套语义）。 */
  function setupPayload() {
    const channels = {};
    for (const name of ['image', 'chat']) {
      const patch = { base: $('setup-' + name + '-base').value.trim() };
      const key = $('setup-' + name + '-key').value.trim();
      if (key) patch.key = key;
      channels[name] = patch;
    }
    const body = { channels };
    body.bot_name = $('setup-bot-name').value.trim();
    body.owner_user_id = $('setup-owner').value.trim();
    body.sd_base_url = $('setup-sd-base').value.trim();
    body.sd_root = $('setup-sd-root').value.trim();
    body.qq_ws_url = $('setup-qq-ws').value.trim();
    const qqToken = $('setup-qq-token').value.trim();
    if (qqToken) body.qq_access_token = qqToken;
    const webToken = $('setup-web-token').value.trim();
    if (webToken && webToken !== setupLoadedToken) body.webui_access_token = webToken;
    return body;
  }

  async function loadSetup() {
    let config;
    try {
      config = await api('/api/config/state');
    } catch (error) {
      if (String(error.message) === 'unauthorized') return;   // api() 已经切到登录页，这里不再叠加报错
      throw error;
    }
    fillSetup(config.values);
    $('setup-note').textContent = config.needed ? '首次配置' : '配置已完成';
    setText('setup-state', config.needed
      ? '还差：' + (config.missingText || '') + '本机访问免令牌，填好保存即可。'
      : '改完保存即可：DeepSeek 地址与密钥立刻生效，SD 与 NapCat 的地址重启后生效。');
    // 首次配置期间服务端会把当前访问令牌交给本页（本机免令牌）：直接记住，省得配完还要去 config.json 里翻。
    if (config.needed && config.values.webui_access_token && !state.token) unlock(config.values.webui_access_token);
    if (state.token) {
      try { await loadStatus(); } catch (error) { if (String(error.message) !== 'unauthorized') throw error; }
    }
  }

  function bindSetupTests() {
    document.querySelectorAll('#panel-setup button[data-setup-test]').forEach((button) => {
      button.addEventListener('click', async () => {
        const channel = button.dataset.setupTest;
        const note = $('setup-' + channel + '-test');
        note.textContent = '正在测试…'; note.className = 'muted';
        try {
          const result = await api('/api/config/test', { body: { channel } });
          note.textContent = result.message;
          note.className = 'muted ' + (result.ok ? 'setup-ok' : 'setup-bad');
        } catch (error) {
          note.textContent = error.message; note.className = 'muted setup-bad';
        }
      });
    });
  }

  // ---------------------------------------------------------------- 提示词 tab 补全

  /**
   * 提示词输入框的 tab 补全：敲英文前缀或中文说法，下拉里给标准词条（数据来自 /api/tags）。
   * 只用原生 DOM：Tab / Enter 采纳、↑↓ 选择、Esc 关闭，鼠标点也行；输入框带 data-tag-complete 就自动挂上。
   * 空词条不打扰（只有按 Ctrl+Space 才列出最热词条），结果按词缓存，避免每敲一下都打后端。
   */
  const TAG_LIMIT = 20;
  const tagSuggest = { node: null, mirror: null, field: null, items: [], active: -1, start: 0, end: 0, seq: 0, timer: 0, cache: new Map() };

  function tagSuggestBox() {
    if (!tagSuggest.node) {
      tagSuggest.node = el('div', 'tag-suggest');
      tagSuggest.node.hidden = true;
      document.body.appendChild(tagSuggest.node);
    }
    return tagSuggest.node;
  }

  /** 光标前正在输入的那个词，以及它在本输入框里的替换区间。 */
  function tagToken(field) {
    const caret = field.selectionStart === null ? field.value.length : field.selectionStart;
    const head = field.value.slice(0, caret);
    let start = Math.max(head.lastIndexOf(','), head.lastIndexOf('，'), head.lastIndexOf('\n')) + 1;
    // 权重 / lora 写法 `(tag:1.2)`、`<lora:name:0.8>`：只补最后那截词条本身
    const piece = head.slice(start);
    const inner = Math.max(piece.lastIndexOf('('), piece.lastIndexOf('<'), piece.lastIndexOf(':'));
    if (inner >= 0) start += inner + 1;
    return { text: field.value.slice(start, caret).trim(), start, end: caret };
  }

  /** 候选框该出现在哪：textarea 用隐藏镜像层量出光标坐标，input 直接贴在框下面。 */
  function tagCaretPoint(field) {
    const rect = field.getBoundingClientRect();
    if (field.tagName === 'INPUT') return { left: rect.left, top: rect.bottom + 4, width: rect.width };
    if (!tagSuggest.mirror) {
      tagSuggest.mirror = el('div', 'tag-mirror');
      document.body.appendChild(tagSuggest.mirror);
    }
    const mirror = tagSuggest.mirror, style = getComputedStyle(field);
    ['fontFamily', 'fontSize', 'fontWeight', 'lineHeight', 'letterSpacing', 'paddingTop', 'paddingRight',
      'paddingBottom', 'paddingLeft', 'borderTopWidth', 'borderRightWidth', 'borderBottomWidth',
      'borderLeftWidth', 'boxSizing'].forEach((name) => { mirror.style[name] = style[name]; });
    mirror.style.width = field.clientWidth + 'px';
    const caret = field.selectionStart === null ? field.value.length : field.selectionStart;
    mirror.textContent = field.value.slice(0, caret);
    const marker = el('span', null, '\u200b');
    mirror.appendChild(marker);
    const base = mirror.getBoundingClientRect(), point = marker.getBoundingClientRect();
    const line = parseFloat(style.lineHeight) || 18;
    return { left: rect.left + point.left - base.left - field.scrollLeft,
             top: rect.top + point.top - base.top - field.scrollTop + line,
             width: rect.width };
  }

  function paintTagSuggest() {
    if (!tagSuggest.node) return;
    Array.from(tagSuggest.node.children).forEach((row, index) => row.classList.toggle('active', index === tagSuggest.active));
  }

  function renderTagSuggest(field, items) {
    const box = tagSuggestBox();
    tagSuggest.field = field;
    tagSuggest.items = items;
    box.innerHTML = '';
    if (!items.length) { closeTagSuggest(); return; }
    items.forEach((item, index) => {
      const row = el('button', 'tag-suggest-item' + (index === tagSuggest.active ? ' active' : ''));
      row.type = 'button';
      row.appendChild(el('span', 'tag-suggest-tag', item.tag));
      if (item.zh) row.appendChild(el('span', 'tag-suggest-zh', item.zh));
      const meta = [];
      if (item.category) meta.push(item.category);
      if (item.rank) meta.push(compactCount(item.rank));
      if (meta.length) row.appendChild(el('span', 'tag-suggest-meta', meta.join(' · ')));
      row.addEventListener('mousedown', (event) => { event.preventDefault(); acceptTag(field, item); });
      row.addEventListener('mouseenter', () => { tagSuggest.active = index; paintTagSuggest(); });
      box.appendChild(row);
    });
    const point = tagCaretPoint(field);
    box.hidden = false;
    box.style.left = Math.max(8, Math.min(point.left, window.innerWidth - 320)) + 'px';
    box.style.top = (point.top + 300 > window.innerHeight ? Math.max(8, point.top - 308) : point.top) + 'px';
    box.style.width = Math.max(240, Math.min(420, point.width)) + 'px';
  }

  /** 采纳候选：替换光标前那个词，补一个逗号继续输入。 */
  function acceptTag(field, item) {
    if (!item) return;
    const before = field.value.slice(0, tagSuggest.start);
    const after = field.value.slice(tagSuggest.end).replace(/^[\s,，]*/, '');
    field.value = before + item.tag + (after ? ', ' + after : ', ');
    closeTagSuggest();
    field.focus();
    const caret = (before + item.tag).length;
    field.setSelectionRange(caret, caret);
  }

  async function queryTagSuggest(field, force) {
    const token = tagToken(field);
    tagSuggest.start = token.start;
    tagSuggest.end = token.end;
    tagSuggest.field = field;
    if (!token.text && !force) { closeTagSuggest(); return; }
    const cached = tagSuggest.cache.get(token.text);
    if (cached) { tagSuggest.active = 0; renderTagSuggest(field, cached); return; }
    const seq = ++tagSuggest.seq;
    try {
      const data = await api('/api/tags', { body: { query: token.text, limit: TAG_LIMIT } });
      // 结果回来时用户可能已经敲了别的字：词变了就丢掉这次结果。
      if (seq !== tagSuggest.seq || tagToken(field).text !== token.text) return;
      const items = data.tags || [];
      if (tagSuggest.cache.size > 300) tagSuggest.cache.clear();
      tagSuggest.cache.set(token.text, items);
      tagSuggest.active = 0;
      renderTagSuggest(field, items);
    } catch (error) {
      if (String(error.message) !== 'unauthorized') closeTagSuggest();
    }
  }

  function tagSuggestKey(event, field) {
    const open = tagSuggest.node && !tagSuggest.node.hidden && tagSuggest.field === field;
    if (!open) {
      // 空词条上主动要候选（Tab 在没开下拉时不抢焦点，留给浏览器默认行为）。
      if (event.key === ' ' && event.ctrlKey) { event.preventDefault(); queryTagSuggest(field, true); }
      return;
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const size = tagSuggest.items.length;
      tagSuggest.active = (tagSuggest.active + (event.key === 'ArrowDown' ? 1 : -1) + size) % size;
      paintTagSuggest();
    } else if (event.key === 'Tab' || (event.key === 'Enter' && !event.shiftKey)) {
      event.preventDefault();
      acceptTag(field, tagSuggest.items[Math.max(0, tagSuggest.active)]);
    } else if (event.key === 'Escape') { event.preventDefault(); closeTagSuggest(); }
  }

  function attachTagComplete(field) {
    field.addEventListener('input', () => {
      clearTimeout(tagSuggest.timer);
      tagSuggest.timer = setTimeout(() => queryTagSuggest(field, false), 120);
    });
    field.addEventListener('keydown', (event) => tagSuggestKey(event, field));
    field.addEventListener('blur', () => setTimeout(() => { if (tagSuggest.field === field) closeTagSuggest(); }, 150));
    field.addEventListener('scroll', () => closeTagSuggest());
  }

  function closeTagSuggest() {
    tagSuggest.seq++;                       // 在途请求作废
    tagSuggest.active = -1;
    if (tagSuggest.node) tagSuggest.node.hidden = true;
  }

  /** 热度显示：426664 → 43万，12345 → 12k。 */
  function compactCount(value) {
    if (value >= 1000000) return (value / 1000000).toFixed(1).replace(/\.0$/, '') + 'M';
    if (value >= 10000) return Math.round(value / 10000) + '万';
    if (value >= 1000) return (value / 1000).toFixed(1).replace(/\.0$/, '') + 'k';
    return String(value);
  }

  // ---------------------------------------------------------------- 绑定

  function bind() {
    // 提示词输入框的 tab 补全：标了 data-tag-complete 的输入框全部挂上（见上面的模块）。
    document.querySelectorAll('[data-tag-complete]').forEach(attachTagComplete);
    window.addEventListener('resize', () => closeTagSuggest());
    window.addEventListener('scroll', () => closeTagSuggest(), true);
    // 滚动位置记忆：回执正文 / 对话各自按号/按会话记，窗口滚动按 location.pathname 记（切页面回来不丢）。
    const questBody = $('quest-body');
    if (questBody) questBody.addEventListener('scroll', () => questScrollPersist(), { passive: true });
    const chatLog = $('chat-log');
    if (chatLog) chatLog.addEventListener('scroll', () => chatScrollPersist(), { passive: true });
    // 对话快照：节流窗口（最多 400ms 一次）里还没写完的那次，在页面隐藏/卸载时补一次 ——
    // 切栏目回来要原样恢复，就靠它别漏掉最后几条。
    document.addEventListener('visibilitychange', () => { if (document.hidden) chatSnapshotFlush(); });
    window.addEventListener('pagehide', () => chatSnapshotFlush());
    window.addEventListener('scroll', () => pageScrollPersist(), { passive: true });
    on('login-form', 'submit', async (event) => {
      event.preventDefault();
      const token = $('token').value.trim();
      if (!token) return;
      unlock(token);
      try { await boot(); }
      catch (error) {
        if (String(error.message) === 'unauthorized') lock('令牌不正确，请重新输入。');
        else banner('已进入控制台，但部分数据读取失败：' + error.message);
      }
    });
    on('lock', 'click', () => lock(''));
    on('refresh', 'click', () => boot().catch((error) => banner('刷新失败：' + error.message)));
    on('chat-form', 'submit', sendChat);
    on('chat-input', 'keydown', (event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); sendChat(event); } });
    on('chat-reset', 'click', async () => {
      await api('/api/chat/reset', { body: { scope: scope() } });
      $('chat-log').innerHTML = '';
      chatSnapshotClear();               // 会话存储里的那份也一起删：清空之后切回来不能又"复活"
      toast('对话已清空');
    });

    // 出图
    // 生成参数没有「应用」按钮：失焦/回车/下拉选择都会触发 change，等它发完再生图。
    GEN_FIELDS.forEach(([id]) => on(id, 'change', () => applyGeneration()));
    on('gen-btn', 'click', async () => {
      await applyGeneration(true);
      runCommands(['.gen ' + ($('gen-count').value || '1')], null, true);
    });
    on('gen-status-btn', 'click', () => runCommands(['.gen status'], loadTasks).then(loadProgress));
    on('get-btn', 'click', () => runCommands(['.get'], null, true));
    on('gen-toggle-btn', 'click', () => setOption('autoGet', $('gen-autoget').checked ? 'off' : 'on'));
    on('preset-save', 'click', () => editPresets('save', $('preset-name').value));
    on('preset-overwrite', 'click', () => editPresets('overwrite', $('preset-name').value));
    on('rg-btn', 'click', () => runCommands(['.rg ' + ($('rg-count').value || '4')]));

    // 提示词：本机 JSON，直接走 /api/prompt/edit（不拼指令、不轮询回执）
    on('prompt-add-btn', 'click', () => { const v = $('prompt-add').value.trim(); if (v) editPrompt('positive', 'add', v); $('prompt-add').value = ''; });
    on('prompt-remove-btn', 'click', () => { const v = $('prompt-add').value.trim(); if (v) editPrompt('positive', 'remove', v); });
    on('prompt-clear-btn', 'click', async () => {
      if (await askConfirm('清空正向提示词？', { title: '清空正向提示词', confirmText: '清空', danger: true }))
        editPrompt('positive', 'clear');
    });
    on('prompt-r-add-btn', 'click', () => { const v = $('prompt-r-add').value.trim(); if (v) editPrompt('negative', 'add', v); $('prompt-r-add').value = ''; });
    on('prompt-r-remove-btn', 'click', () => { const v = $('prompt-r-add').value.trim(); if (v) editPrompt('negative', 'remove', v); });
    on('prompt-r-clear-btn', 'click', async () => {
      if (await askConfirm('清空反向提示词？', { title: '清空反向提示词', confirmText: '清空', danger: true }))
        editPrompt('negative', 'clear');
    });
    on('prompt-save-btn', 'click', async () => {
      await editPrompt('positive', 'set', $('prompt-positive').value);
      await editPrompt('negative', 'set', $('prompt-negative').value);
    });
    on('undo-btn', 'click', () => editPrompt('positive', 'undo'));
    // 智能改写要等 DeepSeek（几秒到几十秒）：等这条回执真的跑完，再把正反向提示词刷新出来
    // （改写入口已经搬到「出图」面板，提示词面板此刻不在眼前，早刷新会刷出旧的文本）。
    on('infix-btn', 'click', async () => {
      const instruction = $('infix-input').value.trim();
      $('infix-input').value = '';
      if (!instruction) return;
      const capture = await runCommands(['.infix ' + instruction], null, true);
      await waitCaptureSettled(capture && capture.id);
      await loadPrompt().catch(() => {});
    });
    on('infix-filter', 'change', (event) => setOption('infixFilter', event.target.checked ? 'on' : 'off'));
    on('progen-btn', 'click', () => {
      const v = $('progen-input').value.trim();
      if (v) runInfo('.progen ' + v, 'DeepSeek 生成的提示词');
    });
    on('usage-btn', 'click', () => loadUsage($('usage-query').value.trim()));

    // 样式（本机样式库走 /api/styles/edit；只有「导入 WebUI 预设样式」和查看原文要读桥接）
    // 查看原文走弹窗（和列表行上的「查看原文」同一个对话框）：面板底部不再放回执行。
    on('style-prompt-btn', 'click', () => {
      const wanted = $('style-prompt-name').value.trim().replace(/^#/, '');
      const names = [...styleItems.keys()];
      const name = names.find((item) => item === wanted)
        || names.find((item) => item.toLowerCase() === wanted.toLowerCase())
        || names[Number(wanted) - 1];
      if (!name) { toast(wanted ? '样式库里没有「' + wanted + '」；列表见下方。' : '样式库是空的。'); return; }
      const data = styleItems.get(name) || {};
      showInfo('样式原文：' + name,
        '分类：' + styleCategory(data) + (data.categoryAuto ? '（按规则自动）' : '（手动设置）')
          + (data.width > 0 && data.height > 0 ? '\n尺寸：' + data.width + '×' + data.height
              + (data.sizeSource === 'preview' ? '（展示图尺寸）' : '（保存时的模型参数）') : '')
          + '\n\n正向：\n' + (data.positive || '（空）') + '\n\n反向：\n' + (data.negative || '（空）'),
        { text: '这是一份固定模板：载入会把这两段原样写进你个人的提示词。' });
    });
    on('style-save', 'click', () => {
      const name = $('style-save-name').value.trim();
      if (name) editStyles('save', name);
    });
    on('style-overwrite', 'click', () => {
      const name = $('style-save-name').value.trim();
      if (name) editStyles('overwrite', name);
    });
    on('style-import', 'click', async () => {
      if (await askConfirm('把 WebUI 里已有的预设样式一次性搬进机器人样式库？\n同名默认跳过，不会覆盖机器人已有的样式。',
          { title: '导入 WebUI 样式', confirmText: '导入' })) {
        runCommands(['.style import webui']).then(loadStyles);
      }
    });
    on('style-batch-rename', 'click', () => {
      const range = $('style-range').value.trim(), name = $('style-range-name').value.trim();
      if (range && name) editStyles('rename', range, { newName: name });
    });
    on('style-batch-category', 'click', () => {
      const range = $('style-range').value.trim(), category = $('style-range-category').value.trim();
      if (!range) { toast('请先填要改分类的编号区间，例如 #12-#16。'); return; }
      // 分类留空＝清空手动分类、回到默认规则（与 `.style category <目标> -` 同一个意思）。
      editStyles('category', range, { category });
    });
    on('style-batch-delete', 'click', async () => {
      const range = $('style-range').value.trim();
      if (!range) return;
      if (!await askConfirm('删除 ' + range + '？', { title: '批量删除样式', confirmText: '删除', danger: true })) return;
      editStyles('delete', range);
    });
    on('style-filter', 'input', () => { if (lastStyles) renderStyles(lastStyles); });
    on('style-category-filter', 'change', () => { if (lastStyles) renderStyles(lastStyles); });
    // 分类折叠：默认全部展开，折叠状态记在 localStorage（pixiko-style-collapsed），刷新后保持。
    on('style-expand-all', 'click', () => setAllGroupCollapse(false));
    on('style-collapse-all', 'click', () => setAllGroupCollapse(true));
    on('style-reload', 'click', loadStyles);

    // LoRA
    on('lora-query-btn', 'click', () => searchCivitai(1));
    // 回车直接搜（不用先点按钮），与「搜索」按钮同一条路径。
    on('lora-query', 'keydown', (event) => { if (event.key === 'Enter') { event.preventDefault(); searchCivitai(1); } });
    // 翻页：搜索词保持在 state.civitaiQuery 里，翻页不会丢；页码越界由后端给 note，不静默。
    on('civitai-prev', 'click', () => { if (state.civitaiPage > 1) searchCivitai(state.civitaiPage - 1); });
    on('civitai-next', 'click', () => searchCivitai(state.civitaiPage + 1));
    on('lora-status-btn', 'click', () => runCommands(['.lora status']).then(loadLoras));
    on('lora-auto-get', 'change', (event) => setOption('autoGet', event.target.checked ? 'on' : 'off'));
    on('lora-filter', 'input', () => renderLoras(state.loras, state.loraGroups));
    on('lora-reload', 'click', loadLoras);
    // 补抓展示图：早先下载的 LoRA 没有配图，按本地 Civitai 记录去补一张（不重新下载模型文件）。
    on('lora-cover-btn', 'click', () => startLoraJob('/api/lora/cover', {}));

    // 提示词集
    on('function-save', 'click', () => { const n = $('function-save-name').value.trim(); if (n) editFunctions('save', n); });
    on('function-overwrite', 'click', () => { const n = $('function-save-name').value.trim(); if (n) editFunctions('overwrite', n); });
    on('function-clear', 'click', () => editFunctions('clear', ''));
    on('function-reset', 'click', () => editFunctions('reset', ''));
    on('function-reload', 'click', loadFunctions);

    // 聊天设置：本机配置项直接走 /api/settings（不再拼 .chat 指令）；
    // 只有需要 DeepSeek 的（性格改写 / 追加性格）和改 SD 基础模型的（.model set）仍走指令通道。
    on('set-chat-global', 'change', (event) => setOption('chatGlobal', event.target.checked ? 'on' : 'off'));
    on('apply-frequency', 'click', () => { const v = $('set-frequency').value.trim(); if (v !== '') setOption('chatFrequency', v); });
    on('set-chat-thinking', 'change', (event) => setOption('chatThinking', event.target.checked ? 'on' : 'off'));
    on('set-image-thinking', 'change', (event) => setOption('imageThinking', event.target.checked ? 'on' : 'off'));
    on('apply-chat-model', 'click', () => { const v = $('set-chat-model').value.trim(); if (v) setOption('chatModel', v); });
    on('apply-image-model', 'click', () => { const v = $('set-image-model').value.trim(); if (v) runCommands(['.model set ' + v]).then(loadStatus); });
    on('chat-model-info', 'click', () => runInfo('.chat model', '聊天模型'));
    on('apply-personality', 'click', () => setOption('personality', $('set-personality').value));
    on('apply-personality-add', 'click', () => { const v = $('personality-add').value.trim(); if (v) runCommands(['.chat add ' + v]).then(loadStatus); });
    on('apply-personality-infix', 'click', () => { const v = $('personality-infix').value.trim(); if (v) runCommands(['.chat infix ' + v]).then(loadStatus); });
    on('set-notice', 'change', (event) => setOption('notice', event.target.checked ? 'on' : 'off'));
    on('set-logmirror', 'change', (event) => setOption('logMirror', event.target.checked ? 'on' : 'off'));

    // 系统：网页面板只管网页自己的事情，QQ 侧的地图/婚姻/角色/admin 不在这里出现
    on('sd-start-btn', 'click', async () => {
      // 启动 SD 要等模型加载：接口立刻返回，之后每 3 秒刷新状态直到就绪或超时。
      try {
        const result = await api('/api/sd/start', { body: {} });
        renderSd(result.sd || {});
        toast(result.sd?.reachable ? 'SD 已经在运行' : '正在启动 SD WebUI…');
        if (!result.sd?.reachable) {
          const deadline = Date.now() + 6 * 60 * 1000;
          while (Date.now() < deadline) {
            await new Promise((resolve) => setTimeout(resolve, 3000));
            const status = await api('/api/sd/status', { body: {} });
            renderSd(status);
            if (status.reachable) { toast('SD WebUI 已就绪'); break; }
          }
          await loadStatus();
        }
      } catch (error) { if (String(error.message) !== 'unauthorized') toast(error.message); }
    });
    on('sd-auto-start', 'change', (event) => setOption('sdAutoStart', event.target.checked ? 'on' : 'off'));
    on('sd-start-on-boot', 'change', (event) => setOption('sdStartOnBoot', event.target.checked ? 'on' : 'off'));
    // Civitai 账号：粘一条一次性登录链接，机器人自己走完跳转链并保存会话 Cookie（见 docs/MIGRATION.md）。
    on('civitai-link-save', 'click', async () => {
      const input = $('civitai-link'), note = $('civitai-link-note');
      const link = input.value.trim();
      if (!link) { note.textContent = '请先粘贴邮件里那条一次性登录链接。'; note.className = 'muted setup-bad'; return; }
      note.textContent = '正在跟随登录链接（最多 10 跳）…'; note.className = 'muted';
      try {
        const result = await api('/api/civitai/link', { body: { link } });
        input.value = '';
        note.textContent = result.verified
          ? '已保存并验证通过（' + (result.cookieHint || '') + '），搜索与下载改用它。'
          : '已保存（' + (result.cookieHint || '') + '），但带它查询 LoRA 列表没成功：检查 civitai.proxy_url，或重新申请一封登录邮件。';
        note.className = 'muted ' + (result.verified ? 'setup-ok' : 'setup-bad');
        toast('Civitai 登录链接已处理');
        if (state.token) await loadStatus();
      } catch (error) {
        if (String(error.message) !== 'unauthorized') { note.textContent = '保存失败：' + error.message; note.className = 'muted setup-bad'; }
      }
    });
    on('civitai-clear-btn', 'click', async () => {
      if (!await askConfirm('清除已保存的 Civitai Cookie？\n之后只能用 civitai.com，成人内容与部分模型不可见。',
          { title: '清除 Civitai Cookie', confirmText: '清除', danger: true })) return;
      try {
        await api('/api/civitai/cookie/clear', { body: {} });
        toast('已清除 Civitai Cookie');
      } catch (error) { if (String(error.message) !== 'unauthorized') toast(error.message); }
      await loadStatus();
    });
    // Setup 栏目：首次配置表单（保存 / 测试连接 / 重新读取）
    on('setup-save', 'click', async () => {
      const result = $('setup-result');
      const typedToken = $('setup-web-token').value.trim();
      result.textContent = '正在保存…'; result.className = 'muted';
      try {
        const saved = await api('/api/config/apply', { body: setupPayload() });
        fillSetup(saved.values);
        $('setup-image-key').value = ''; $('setup-chat-key').value = '';
        $('setup-qq-token').value = ''; $('setup-web-token').value = '';
        const changed = (saved.changed || []).join('；');
        result.textContent = changed ? '已保存：' + changed : '没有改动。';
        result.className = 'muted setup-ok';
        $('setup-note').textContent = saved.needed ? '首次配置' : '配置已完成';
        setText('setup-state', saved.needed
          ? '还差：' + (saved.missingText || '') + '本机访问免令牌，填好保存即可。'
          : '配置完成，可以去别的栏目了。');
        // 用户自己换了令牌就直接记住：配置完成后接口恢复要令牌，刷新也不会被挡在外面。
        if (typedToken.length >= 8) unlock(typedToken);
        if (state.token) await loadStatus();
      } catch (error) {
        if (String(error.message) !== 'unauthorized') {
          result.textContent = '保存失败：' + error.message;
          result.className = 'muted setup-bad';
        }
      }
    });
    on('setup-reload', 'click', () => loadSetup().catch((error) => banner('读取配置失败：' + error.message)));
    bindSetupTests();
    on('console-form', 'submit', (event) => { event.preventDefault(); const text = $('console-input').value.trim(); if (text) runCommands([text]); });
    // DeepSeek 通道：地址与密钥直接写本机配置，不拼指令、不用轮询回执。
    on('channel-save', 'click', saveChannels);
    on('channel-test-image', 'click', () => testChannel('image'));
    on('channel-test-chat', 'click', () => testChannel('chat'));
    on('channel-clear-image', 'click', () => clearChannelKey('image'));
    on('channel-clear-chat', 'click', () => clearChannelKey('chat'));
    on('tasks-reload', 'click', loadTasks);
    on('tasks-cancel-all', 'click', async () => {
      if (!await askConfirm('取消队列里的全部任务？\n已经生成的图片仍可领取。',
          { title: '取消全部任务', confirmText: '全部取消', danger: true })) return;
      await taskAction('cancel', 'all');
    });

    // 控制台（类 ssh）：日志跟随 + 底部一行敲指令
    on('logs-reload', 'click', loadLogs);
    on('logs-filter', 'input', () => { logKey = ''; loadLogs(); });
    on('logs-source', 'change', () => { logKey = ''; loadLogs(); });
    // 图片查看器：滚轮缩放、点图切 1:1/适应屏幕、点图片周围的空白关闭、←/→ 换图（图集翻页）
    on('viewer-close', 'click', closeViewer);
    on('viewer-toggle', 'click', toggleViewerScale);
    on('viewer-image', 'click', toggleViewerScale);
    on('viewer-prev', 'click', () => viewerStep(-1));
    on('viewer-next', 'click', () => viewerStep(1));
    on('viewer-stage', 'click', (event) => { if (event.target === $('viewer-stage')) closeViewer(); });   // 图片周围的空白
    on('viewer', 'click', (event) => { if (event.target === $('viewer')) closeViewer(); });
    const viewerNode = $('viewer');
    if (viewerNode) viewerNode.addEventListener('wheel', viewerWheel, { passive: false });   // 滚轮缩放，preventDefault 掉页面滚动
    document.addEventListener('keydown', (event) => {
      const viewer = $('viewer');
      if (viewer && !viewer.hidden && (event.key === 'ArrowLeft' || event.key === 'ArrowRight')) {
        event.preventDefault();
        viewerStep(event.key === 'ArrowRight' ? 1 : -1);
        return;
      }
      if (event.key === 'Escape') closeViewer();
    });

    on('terminal-clear', 'click', clearTerminal);
    bindTerminalTail();
    on('terminal-fullscreen', 'click', () => applyConsoleFullscreen(!consoleFullscreen()));
    // 任务回执页：地址里的 #N 就是任务号（/quest#22）；点信息云里的链接、点左栏列表里的一行都会走到这里。
    window.addEventListener('hashchange', () => { if (PAGE === 'quest') loadQuest(questNumberFromLocation()); });
    bindQuestListActions();
    // 列表里的「进行中」条目不靠高频轮询：页面重新可见时刷一次就够了。
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) return;
      refreshQuestListQuietly();
      pollQuestStatusQuietly();            // 切回来顺手问一次未读数（新回执 → 徽标涨）
    });

    on('terminal-form', 'submit', async (event) => { event.preventDefault(); await submitTerminal(); });
    // 点终端任意位置都聚焦到提示符（真终端就是这样）
    on('terminal-body', 'click', (event) => {
      if (event.target.tagName === 'A' || event.target.tagName === 'IMG') return;
      const input = $('terminal-input');
      if (input) input.focus();
    });
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape' && consoleFullscreen()) { event.preventDefault(); applyConsoleFullscreen(false); }
    });
    on('terminal-input', 'keydown', (event) => {
      if (event.key === 'ArrowUp') { event.preventDefault(); terminalHistoryMove(-1); }
      else if (event.key === 'ArrowDown') { event.preventDefault(); terminalHistoryMove(1); }
      else if (event.key === 'l' && event.ctrlKey) { event.preventDefault(); clearTerminal(); }
    });

    const shortcuts = $('shortcuts');
    if (shortcuts) ['.style list', '.lora list', '.function list', '.preset list', '.settings', '.gen status', '.progress', '.chat', '.help']
      .forEach((command) => shortcuts.appendChild(actionButton(command, () => runInfo(command, command), 'ghost')));
  }

  /**
   * 加载本栏目要用的数据。页签现在是真链接（每个栏目一个 URL），切栏目＝换页面，
   * 所以这里只按 {@link PAGE} 拉这一栏的接口，不再像以前那样每个页面都把整套数据拉一遍。
   */
  async function loadPage() {
    try {
      // Setup 栏目要能在「还没有令牌」的首次配置阶段打开（那些接口对本机免令牌），所以不走 loadStatus。
      if (PAGE === 'setup') { await loadSetup(); banner(''); return; }
      await loadStatus();                      // 顶栏、健康点、共享状态（约 15ms）
      if (PAGE === 'chat') await loadChatHistory();
      else if (PAGE === 'gen') await Promise.all([loadOptions(), loadPresets(), loadForgePresets(), loadImages(), loadTasks()]);
      else if (PAGE === 'prompt') await loadPrompt();
      else if (PAGE === 'styles') await loadStyles();
      else if (PAGE === 'loras') {
        await loadLoras();
        renderCivitai([]);                     // 搜索结果区先给占位（结果由「搜索」按钮填）
      }
      else if (PAGE === 'functions') await Promise.all([loadOptions(), loadFunctions()]);
      else if (PAGE === 'logs') {
        resetTerminalTail();                   // 进控制台时把提示符下面那片"被顶上去"的空白收回默认高度
        await loadLogs();
        if ($('terminal-input')) $('terminal-input').focus();
      } else if (PAGE === 'quest') {
        await loadQuest();                     // 先单条回执（旧后端也必须有）
        // 再拉整张列表：串行、单独 try，列表挂了（旧后端没有 /api/quests）也不影响上面那条。
        try { await loadQuestList(); state.questListWarned = false; }
        catch (error) { questListFailed(error); }
      }
      else if (PAGE === 'help') await loadHelp();
      else if (PAGE === 'system') await loadChannels();
      // chatcfg / system 只要状态，上面已经拉过
      if (state.options) {
        fillSelect('set-sampler', state.options.samplers, state.status?.generation?.sampler || '');
        fillModelSelect('set-model', state.options, state.status?.generation?.model || '');
      }
      banner('');
      pageScrollRestore();                     // 其它页面的窗口滚动位置：本栏目上次看到哪就回到哪
    } catch (error) {
      if (String(error.message) !== 'unauthorized') banner('「' + PAGE + '」面板加载失败：' + error.message);
    }
  }

  async function boot() {
    banner('');
    syncConsoleFullscreen(PAGE);               // 控制台栏目默认全屏（跟着页面走）
    clearInterval(state.followTimer);
    clearInterval(state.questPollTimer);
    // 回执未读徽标：只问 /api/status（很轻），不用整页刷新；页面隐藏时不发请求。
    state.questPollTimer = setInterval(() => { pollQuestStatusQuietly(); }, 30000);
    state.followTimer = setInterval(() => {
      if ($('logs-follow') && $('logs-follow').checked && PAGE === 'logs') loadLogs().catch(() => {});
    }, 4000);
    await loadPage();
  }

  bind();
  if (state.token) {
    unlock(state.token);
    boot().catch((error) => {
      if (String(error.message) === 'unauthorized') lock('令牌已失效，请重新输入。');
      else banner('已进入控制台，但数据读取失败：' + error.message + '（可直接切到其它面板，或点刷新重试）');
    });
  } else if (PAGE === 'setup') {
    // 首次配置期间本机免令牌：没有令牌也要能打开 Setup 填密钥，登录页不该挡在前面。
    $('login').hidden = true;
    $('app').hidden = false;
    boot().catch((error) => banner('配置栏目加载失败：' + error.message + '（可直接点刷新重试）'));
  } else {
    lock('');
  }
})();
