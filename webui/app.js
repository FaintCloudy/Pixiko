/* Pixiko 控制台：无构建步骤。每个栏目是一个独立页面（/gen、/prompt…），前端只加载本栏目的数据。 */
(() => {
  const TOKEN_KEY = 'kotori-webui-token';
  /** 当前页面属于哪个栏目：由服务端在页面里注入（见 WebPages.render）。 */
  const PAGE = window.PIXIKO_PAGE || 'chat';
  const state = { token: localStorage.getItem(TOKEN_KEY) || '', status: null, options: null, pollTimer: null, followTimer: null, busy: 0,
    seenImages: new Map(), seenGroups: new Map(), followUntil: 0, receiptKey: '', receiptTexts: 0, receiptImages: 0,
    receiptCount: 0, receiptBox: null, receiptToasted: '',
    loras: null, loraGroups: null, terminalHistory: [], terminalCursor: 0 };

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

  /**
   * 点图放大：弹出查看器（背景压暗），点图在「适应屏幕 / 1:1 原图」之间切换，
   * Esc、点背景或「关闭」收起。Ctrl/中键仍然走浏览器的新标签打开。
   */
  function openViewer(src, caption) {
    const viewer = $('viewer');
    if (!viewer || !src) return;
    viewer.classList.remove('actual');
    document.getElementById('viewer-image').src = src;
    $('viewer-caption').textContent = caption || '';
    $('viewer-toggle').textContent = '1:1 原图';
    viewer.hidden = false;
    document.body.classList.add('viewer-open');   // 查看器打开时锁住页面滚动
    $('viewer-close').focus();
  }

  function closeViewer() {
    const viewer = $('viewer');
    if (!viewer || viewer.hidden) return;
    viewer.hidden = true;
    viewer.classList.remove('actual');
    document.body.classList.remove('viewer-open');
    document.getElementById('viewer-image').removeAttribute('src');
  }

  function toggleViewerScale() {
    const viewer = $('viewer');
    if (!viewer) return;
    const actual = viewer.classList.toggle('actual');
    $('viewer-toggle').textContent = actual ? '适应屏幕' : '1:1 原图';
    if (!actual) $('viewer-stage').scrollTop = 0;
  }

  /** 统一的图片节点：包一层链接（中键可以新标签打开），左键点击打开查看器。 */
  function imageNode(src, caption, className) {
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
    link.addEventListener('click', (event) => {
      if (event.metaKey || event.ctrlKey || event.shiftKey) return;   // 保留浏览器的新标签行为
      event.preventDefault();
      openViewer(img.getAttribute('src'), caption);                   // 兜底换过的地址也照样放大
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

  const RECEIPT_BOXES = ['chat-receipts', 'gen-receipts', 'prompt-receipts', 'style-receipts',
    'lora-receipts', 'function-receipts', 'chatcfg-receipts', 'system-receipts'];

  function activeReceiptBoxes() { return RECEIPT_BOXES.filter((id) => $(id) && $(id).closest('.panel').classList.contains('active')); }

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

  /** 当前地址里的任务号（/quest/#22 → 22）；没有就是 0（表示"最新一条"）。 */
  function questNumberFromLocation() {
    const value = Number(String(location.hash || '').replace(/^#/, '').trim());
    return Number.isFinite(value) && value > 0 ? Math.floor(value) : 0;
  }

  /**
   * 回执页：跟着一条回执实时刷新（指令结果 + 生成图片都在里面）。
   * 跑完就停表；换任务号（点另一条云）会重新开始跟。
   */
  async function loadQuest(number) {
    clearInterval(questWatch.timer);
    questWatch.number = number || questNumberFromLocation() || 0;
    const tick = async () => {
      let data;
      try { data = await api('/api/quest', { body: { id: questWatch.number } }); }
      catch (error) {
        clearInterval(questWatch.timer);
        if (String(error.message) !== 'unauthorized' && $('quest-state')) $('quest-state').textContent = '读取失败：' + error.message;
        return;
      }
      renderQuest(data);
      if (data.done && !data.busy) clearInterval(questWatch.timer);
    };
    await tick();
    questWatch.timer = setInterval(tick, 900);
  }

  function renderQuest(data) {
    const body = $('quest-body');
    if (!body) return;
    const number = data.quest || questWatch.number;
    const field = $('quest-number');
    if (field && document.activeElement !== field) field.value = number || '';
    const running = !data.error && !(data.done && !data.busy);
    $('quest-state').textContent = data.error ? data.error : '#' + number + (running ? '（进行中…）' : '（已完成）');
    $('quest-command').textContent = data.command ? '指令：' + data.command : '';
    $('quest-progress').textContent = data.latest ? '最新一条是 #' + data.latest : '';
    body.innerHTML = '';
    if (data.error) { body.appendChild(el('div', 'quest-empty', data.error)); return; }

    const groups = Array.isArray(data.messages) && data.messages.length ? data.messages : null;
    const steps = groups || (data.texts || []).map((text) => [{ type: 'text', text }]);
    const flatImages = groups ? [] : (data.images || []).map((image) => ({ type: 'image', file: image.file }));
    if (!steps.length && !flatImages.length) {
      body.appendChild(el('div', 'quest-empty', '这条任务还没有输出。'));
    }
    steps.forEach((pieces, index) => {
      const step = el('div', 'quest-step');
      step.appendChild(el('div', 'head', groups ? '第 ' + (index + 1) + ' 步' : '输出'));
      const text = (pieces || []).filter((piece) => piece.type !== 'image' && piece.text).map((piece) => piece.text).join('\n');
      if (text) {
        if (/(^|\n)[^\n]{0,16}(失败|错误|不正确|无效|超时|拒绝|找不到)[:：]/.test(text)) step.classList.add('err');
        step.appendChild(el('div', 'quest-text', text));
      }
      const pictures = (pieces || []).filter((piece) => piece.type === 'image' && piece.file);
      if (pictures.length) {
        const box = el('div', 'quest-images');
        pictures.forEach((piece) => box.appendChild(imageNode(imageUrl(piece.file), '任务 #' + number + ' 的图', 'quest-image')));
        step.appendChild(box);
      }
      body.appendChild(step);
    });
    if (flatImages.length) {
      const box = el('div', 'quest-images');
      flatImages.forEach((piece) => box.appendChild(imageNode(imageUrl(piece.file), '任务 #' + number + ' 的图', 'quest-image')));
      body.appendChild(box);
    }
    if (running) body.appendChild(el('div', 'quest-empty', '（还在跑，实时刷新中…）'));
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
    if (state.receiptKey !== key) {
      state.receiptKey = key; state.receiptTexts = 0; state.receiptImages = 0; state.receiptCount = 0; state.receiptBox = null;
    }
    for (const id of RECEIPT_BOXES) {
      const box = $(id);
      if (!box) continue;
      const visible = box.closest('.panel').classList.contains('active');
      if (!visible) continue;
      if (state.receiptBox !== box) { box.innerHTML = ''; state.receiptTexts = 0; state.receiptImages = 0; state.receiptCount = 0; }
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
      for (let index = state.receiptImages; index < images.length; index++) {
        // 生成的图片只在这里出现一次；回溯/领取的图也走它，点一下弹查看器放大。
        const card = el('div', 'receipt ok image-receipt');
        const name = String(images[index].file).replace(/^.*[\\/]/, '');
        card.appendChild(imageNode(imageUrl(images[index].file), name, 'receipt-image'));
        box.appendChild(card);
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
      renderCapture(capture);
      // 机器人发的每条消息都直接回到对话里：一条消息 = 一条聊天记录（文字配着自己的图）。
      // 后端给了 messages 就按它分条；旧格式（只有扁平 texts/images）仍走「图片好了」那条老路。
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

  /** 往对话页加一条消息；不在对话页面（别的栏目）时静默跳过。 */
  function appendMessage(kind, text, images) {
    const log = $('chat-log');
    if (!log) return null;
    const node = el('div', 'msg ' + kind);
    node.appendChild(el('div', null, text));
    (images || []).forEach((image) => {
      // 点图打开查看器放大（不再跳到新标签页）。
      node.appendChild(imageNode(imageUrl(image.file), String(image.file).replace(/^.*[\\/]/, ''), 'msg-image'));
    });
    log.appendChild(node);
    log.scrollTop = log.scrollHeight;
    return node;
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
      pending.textContent = result.reply || '(空回复)';
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

  async function loadChatHistory() {
    const log = $('chat-log');
    if (!log) return;                           // 只有对话页面有消息区
    try {
      const history = await api('/api/chat/history', { body: { scope: scope() } });
      log.innerHTML = '';
      (Array.isArray(history) ? history : []).forEach((entry) => appendMessage(entry.role === 'user' ? 'user' : 'bot', entry.content || ''));
    } catch { /* 历史读不到不影响使用 */ }
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
    fillSelect('set-model', options.models, (state.status?.generation?.model) || '');
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

  async function loadStyles() {
    return renderStyles(await api('/api/styles'));
  }

  /**
   * 样式库是本机 JSON（`data/local-styles.json`）：保存/覆盖/改名/删除/载入走 `/api/styles/edit`。
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

  function renderStyles(data) {
    $('style-loaded').textContent = '载入即替换，之后 prompt 由你自己改';
    $('style-pageselected').textContent = '样式库 ' + (data.library ?? (data.styles || []).length) + ' 个（只属于机器人，与 WebUI 的样式互不影响）';
    // 底模分类：列表不按底模重排（编号是 .style load #N / 批量 #6-#9 的依据），只在上面汇总一行。
    const groups = (data.baseModelGroups || []).map((group) => group.baseModel + ' ' + group.count);
    if (groups.length) $('style-pageselected').textContent += '；底模：' + groups.join('、');
    const filter = $('style-filter').value.trim().toLowerCase();
    const items = (data.styles || []).filter((item) => !filter || item.name.toLowerCase().includes(filter)
      || String(item.baseModel || '').toLowerCase().includes(filter));
    styleItems.clear();
    (data.styles || []).forEach((item) => styleItems.set(item.name, item));
    syncRows($('style-list'), styleRows, items, styleRow, '没有匹配的样式。');
  }

  /**
   * 增量同步列表：同名的行**原地复用**（只更新编号，不重建、不重放动画、不丢滚动位置），
   * 新行插到正确位置，消失的行只摘它自己。整表 innerHTML='' 重画正是"晃"的根源。
   */
  function syncRows(list, cache, items, build, emptyText) {
    const wanted = new Set(items.map((item) => item.name));
    for (const [name, row] of [...cache]) if (!wanted.has(name)) { row.remove(); cache.delete(name); }
    for (const placeholder of [...list.querySelectorAll('li.muted')]) placeholder.remove();
    if (!items.length) { list.appendChild(el('li', 'muted', emptyText)); return; }
    let anchor = list.firstElementChild;
    items.forEach((item) => {
      let row = cache.get(item.name);
      if (!row) { row = build(item); cache.set(item.name, row); }
      if (row === anchor) anchor = anchor.nextElementSibling;
      else list.insertBefore(row, anchor || null);
      row.dataset.name = item.name;
      row.dataset.number = String(item.number);
      const number = row.querySelector('.num');
      if (number) number.textContent = '#' + item.number;
    });
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
    // 样式保存时记下的模型参数（底模 + 采样方法/调度器/步数/CFG/Shift/尺寸）：载入时一并套用。
    if (item.modelSummary) {
      const model = el('div', 'sub');
      model.appendChild(el('span', 'tag', item.baseModel || '底模未记录'));
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
    acts.appendChild(actionButton('查看原文', () => {
      const current = li.dataset.name || item.name;
      const data = styleItems.get(current) || item;
      showInfo('样式原文：' + current,
        '正向：\n' + (data.positive || '（空）') + '\n\n反向：\n' + (data.negative || '（空）')
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

  /** 底模来源的中文说法（和机器人回执同一个词表）。 */
  function baseModelSourceLabel(source) {
    if (source === 'civitai') return 'Civitai 记录';
    if (source === 'forge-metadata') return 'Forge 元数据';
    if (source === 'preset-inferred') return '按当前预设推断';
    return '';
  }

  /** 按底模分组：组头是底模名，来源写在旁边；未识别的排最后（组序由后端 groups 定）。 */
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
          title: group.baseModel || item.baseModel || '未识别底模',
          source: baseModelSourceLabel(group.baseModelSource || item.baseModelSource),
          items: [],
        });
      }
      buckets.get(key).items.push(item);
    });
    return [...buckets.values()].sort((left, right) => {
      if (left.keyed !== right.keyed) return left.keyed ? -1 : 1;         // 未识别底模的排最后
      const leftRank = order.has(left.key) ? order.get(left.key) : order.size;
      const rightRank = order.has(right.key) ? order.get(right.key) : order.size;
      return leftRank - rightRank || left.title.localeCompare(right.title, 'zh');
    });
  }

  /**
   * 渲染本机 LoRA 列表：按底模分组（组头例如 Anima（4）· Civitai 记录），每项也带自己的底模标签。
   * 行 DOM 仍按名字复用：加载/删除后不整表重画，列表不会晃。
   */
  function renderLoras(items, groups) {
    const list = $('lora-list');
    const filter = ($('lora-filter') && $('lora-filter').value || '').trim().toLowerCase();
    const matched = (items || []).filter((item) => !filter || item.name.toLowerCase().includes(filter)
      || String(item.alias || '').toLowerCase().includes(filter)
      || String(item.baseModel || '').toLowerCase().includes(filter));
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
        // 底模标签可能因为 Forge 元数据/预设栈的变化而变（补展示图之后就会），每次渲染对齐一次。
        const base = row.querySelector('.lora-base');
        if (base) base.textContent = item.baseModelLabel || (item.baseModel ? '底模 ' + item.baseModel : '底模未识别');
        rows.appendChild(row);
      });
      holder.appendChild(rows);
      list.appendChild(holder);
    });
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
    // 底模标签：Civitai 记录 → Forge 元数据 → 当前预设栈（推断的会写明），识别不出来就如实写未识别。
    name.appendChild(el('div', 'sub lora-base', item.baseModelLabel || (item.baseModel ? '底模 ' + item.baseModel : '底模未识别')));
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
    on('chat-reset', 'click', async () => { await api('/api/chat/reset', { body: { scope: scope() } }); $('chat-log').innerHTML = ''; toast('对话已清空'); });

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
        '正向：\n' + (data.positive || '（空）') + '\n\n反向：\n' + (data.negative || '（空）'),
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
    on('style-batch-delete', 'click', async () => {
      const range = $('style-range').value.trim();
      if (!range) return;
      if (!await askConfirm('删除 ' + range + '？', { title: '批量删除样式', confirmText: '删除', danger: true })) return;
      editStyles('delete', range);
    });
    on('style-filter', 'input', loadStyles);
    on('style-reload', 'click', loadStyles);

    // LoRA
    on('lora-query-btn', 'click', async () => {
      const query = $('lora-query').value.trim();
      if (!query) return;
      renderCivitai([], { loading: '正在搜索 Civitai：' + query + '…' });
      try {
        const data = await api('/api/civitai/search', { body: { query, scope: scope() } });
        renderCivitai(data.results, { empty: '没有找到匹配的 LoRA。' });
        if ($('lora-status')) $('lora-status').textContent = '「' + (data.query || query) + '」找到 ' + (data.count || 0) + ' 项（点卡片上的「下载」即可）';
      } catch (error) {
        renderCivitai([], { empty: '搜索失败：' + error.message });
      }
    });
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
    // 图片查看器
    on('viewer-close', 'click', closeViewer);
    on('viewer-toggle', 'click', toggleViewerScale);
    on('viewer-stage', 'click', toggleViewerScale);
    on('viewer', 'click', (event) => { if (event.target === $('viewer')) closeViewer(); });
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') closeViewer();
    });

    on('terminal-clear', 'click', clearTerminal);
    bindTerminalTail();
    on('terminal-fullscreen', 'click', () => applyConsoleFullscreen(!consoleFullscreen()));
    // 任务回执页：地址里的 #N 就是任务号；点信息云里的链接会直接进来。
    on('quest-open', 'click', () => {
      const value = Number($('quest-number').value.trim().replace(/^#/, ''));
      if (!Number.isFinite(value) || value <= 0) { toast('请填任务号，例如 22。'); return; }
      location.hash = '#' + Math.floor(value);
      loadQuest(Math.floor(value));
    });
    on('quest-latest-btn', 'click', async () => {
      const data = await api('/api/quest', { body: { id: 0 } });
      if (data.quest) { location.hash = '#' + data.quest; loadQuest(data.quest); }
      else toast(data.error || '还没有任何任务回执。');
    });
    on('quest-reload', 'click', () => loadQuest(questNumberFromLocation()));
    if ($('quest-number')) on('quest-number', 'keydown', (event) => { if (event.key === 'Enter') { event.preventDefault(); $('quest-open').click(); } });
    window.addEventListener('hashchange', () => { if (PAGE === 'quest') loadQuest(questNumberFromLocation()); });

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
      } else if (PAGE === 'quest') await loadQuest();
      else if (PAGE === 'help') await loadHelp();
      else if (PAGE === 'system') await loadChannels();
      // chatcfg / system 只要状态，上面已经拉过
      if (state.options) {
        fillSelect('set-sampler', state.options.samplers, state.status?.generation?.sampler || '');
        fillSelect('set-model', state.options.models, state.status?.generation?.model || '');
      }
      banner('');
    } catch (error) {
      if (String(error.message) !== 'unauthorized') banner('「' + PAGE + '」面板加载失败：' + error.message);
    }
  }

  async function boot() {
    banner('');
    syncConsoleFullscreen(PAGE);               // 控制台栏目默认全屏（跟着页面走）
    clearInterval(state.followTimer);
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
