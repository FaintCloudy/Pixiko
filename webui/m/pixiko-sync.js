/*
 * pixiko-sync.js —— Pixiko 前端**信息交互逻辑**的唯一实现（控制台与手机端共用这一份）。
 *
 * ── 为什么有它（用户原话：「为什么手机端的逻辑不能按照网页端的照抄呢？」→ 澄清：
 *    「我的意思是**信息交互逻辑**」）─────────────────────────────────────────────
 * 视图层（桌面两栏布局 / 手机全屏 + 底部 tab + 手势）本来就该各写各的；但"信息怎么进、怎么对账、
 * 怎么跟单、怎么记账"在两端**必须是同一套判据**。以前两端各维护一份补丁逻辑，同一个 bug 要在
 * 两个文件里各修一遍，而且两边的口径已经悄悄分叉：
 *
 *   项目            条目身份            跟单收工判据                      未读
 *   ─────────────  ─────────────────  ──────────────────────────────  ─────────────────
 *   控制台 app.js   角色+正文+图片       busy / 服务端在出图 / followUntil  服务端 unread 数
 *   手机 m/app.js   回执号+条内序号      closed / (done+静默两轮+队列空)     屏内本地列表
 *
 * 这份文件把四件事收敛成一份，两端都调它：
 *   ① **单一数据源**：服务端对话日志（`/api/chat/log`，服务端已在权威追加回执条目
 *      `quest:<回执号>:<条内序号>`，见 ChatLogStore.receipt/append）+ `/api/capture`（回执进度与图片）
 *      + `/api/quests`（列表/未读）。页面**不再自己缝两路数据**；
 *   ② **增量对账与渲染**：按**条目身份** diff，只增改变化的那几条，不整块重建、不重复、不插历史；
 *      同一条的判据（用户冻结的口径）：**正文逐字相同，或一方是另一方的严格前缀**；
 *   ③ **跟单状态机**：只跟"当前正在跑的那条"，用只读的 `/api/progress` 决定何时收工 ——
 *      不再用"有图 / 6 小时 / unread / busy"这类宽松窗口；
 *   ④ **未读记账**：按服务端权威数算，不靠内存表；
 *   ⑤ **滚动锚定**：任何"改了列表内容"的时机（轮询渲染、增量插入、图片异步撑高、补拉并回）
 *      都走 grabAnchor/settleAnchor —— 用户往上翻过之后，**任何周期行为都不许改变 scrollTop**。
 *
 * ── 形态 ────────────────────────────────────────────────────────────────
 * 纯逻辑、**不碰 DOM 的选择器**（只有 grabAnchor/settleAnchor 读 `el.scrollTop` 那三个几何属性，
 * 不查节点、不建节点）、不依赖任何全局、不用打包器：两端页面各自
 *   <script src="/m/pixiko-sync.js"></script>     ← 在各自的 app.js 之前
 * 引进去，浏览器里挂 `window.PixikoSync`，Node/测试里 `module.exports` 同一份对象。
 *
 * 与服务端 Java 的口径对应关系（ChatLogStore.java / Bot.WebCapture.json）：
 *   · 幂等键 `quest:<number>:<seq>`   ← 服务端 append 用的身份；本模块把它复现在 chatEntryId() 里
 *     （同一份来源、同一份推导规则），所以两端算出来的身份与服务端落盘的那一条是同一个；
 *   · 图片"并进上一条正文"（`receipt()` / `WebCapture.messages`）← receiptParts() + receiptImages()
 *     用的分组规则与它逐条一致（每个带正文的消息 +1；图片归到它前面那条正文）。
 */
(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module === 'object' && module && module.exports) module.exports = api;
  if (root) root.PixikoSync = api;
}(typeof globalThis !== 'undefined' ? globalThis : (typeof window !== 'undefined' ? window : this), function () {
  'use strict';

  /* ── 0. 常量（与服务端 ChatLogStore 保持同值） ───────────────────────────── */

  /** 对话正文的滚动上限（ChatLogStore.ENTRIES_CAP 同值）。 */
  var ENTRIES_CAP = 200;
  /** 贴底判定的默认阈值（px）：距底 ≤ 它就当"用户本来就在底部"。 */
  var STICK_PX = 40;
  /** 条内序号的图片标记（`quest:<号>:img:<下标>`，见 ChatLogStore.receipt 的纯图分支）。 */
  var IMG_KEY = 'img';
  /** 前缀对账时每个条目最多比对的候选数（同角色 + 同图片的候选，短的优先）。 */
  var PREFIX_CANDIDATES = 8;

  /* ── 1. 条目规范化与身份 ─────────────────────────────────────────────── */

  /**
   * 一张图的路径：存档里实测有两种写法 —— 字符串（`/api/chat/log` 的 `images` 现在是这个）
   * 与对象 `{file:"…"}` / `{path:"…"}`（`/api/capture` / `/api/images` 用的是对象）。
   * 统一收敛成字符串，认不出给空串。
   */
  function imageFile(item) {
    if (typeof item === 'string') return item.trim();
    if (!item || typeof item !== 'object') return '';
    return String(item.file || item.path || '').trim();
  }

  /** 把一条任意来源的条目洗成能存能渲染的形状；坏数据返回 null（不抛、也不渲染半条）。 */
  function cleanEntry(raw) {
    if (!raw || typeof raw !== 'object') return null;
    var role = raw.role === 'user' || raw.role === 'sys' ? raw.role : 'bot';
    var text = typeof raw.text === 'string' ? raw.text : (raw.text === null || raw.text === undefined ? '' : String(raw.text));
    var images = [];
    if (Array.isArray(raw.images)) {
      for (var i = 0; i < raw.images.length && images.length < 60; i++) {
        var file = imageFile(raw.images[i]);
        if (file) images.push(file);
      }
    }
    if (!text && !images.length) return null;
    return images.length ? { role: role, text: text, images: images } : { role: role, text: text };
  }

  /** 一个数组里所有条目的规范化副本（坏条目丢掉，保持顺序）。 */
  function cleanEntries(list) {
    var out = [];
    if (!Array.isArray(list)) return out;
    for (var i = 0; i < list.length; i++) {
      var clean = cleanEntry(list[i]);
      if (clean) out.push(clean);
    }
    return out;
  }

  /** 图片指纹（只用来分组：同一条的图片顺序在"并进"时保持，所以指纹稳定）。 */
  function imageKey(entry) {
    var images = entry && entry.images;
    return images && images.length ? images.join('\u0002') : '';
  }

  /** 条目指纹：角色 + 正文 + 图片（与两端原来的签名一字不差，供"内容变没变"判断用）。 */
  function entrySig(entry) {
    if (!entry) return '';
    return String(entry.role || '') + '\u0001' + String(entry.text || '') + '\u0001' + imageKey(entry);
  }

  /** 正文的严格前缀关系（`a` 是 `b` 的严格前缀，或反过来）。 */
  function prefixPair(a, b) {
    if (!a || !b || a === b) return false;
    var shorter = a.length <= b.length ? a : b;
    var longer = a.length <= b.length ? b : a;
    return longer.slice(0, shorter.length) === shorter;
  }

  /**
   * 正文身份的**锚点**解析器（同一次对账里必须用同一份）。
   *
   * <p>口径（用户冻结的那一条的重述）：**逐字相同、或一方是另一方的严格前缀**，就算同一条。
   * 实现上取"**最短的完整前缀**"当身份：
   *   · "任务 #12 已完成：1/1" 的完整前缀里有短的那条「任务 #12 已完成」→ 身份取它；
   *   · 短的那条自己是完整条目也有完整前缀（更长的不算，要求**严格**短）→ 身份就取它自己；
   *   · 于是"正文又长了一点"时身份不变（长出来的那条回到同一个身份 → 原地更新，不新增）。
   *
   * <p>没有严格更短的完整前缀时，身份就是正文本身 —— **绝不拿"本地那条是它前缀"去猜**：
   * 共享前缀的不同消息太多了（「任务 #12 已受理」「任务 #12 已完成」），猜错就是用户报过的
   * 「多条消息并成一个气泡」。
   *
   * @param {Array} list 参与对账的**并集**（上一轮 + 这一轮）
   * @returns {{anchors:object, texts:object}} `anchors[text]` = 该正文的身份；`texts` = 见过哪些正文
   */
  function anchorResolver(list) {
    var seen = Object.create(null);          // 角色 →（长度 → 该长度的完整正文集合）
    var all = Object.create(null);           // 角色 → 全部完整正文
    var items = Array.isArray(list) ? list : [];
    var i, text, role;
    for (i = 0; i < items.length; i++) {
      text = String((items[i] && items[i].text) || '');
      if (!text) continue;
      role = String((items[i] && items[i].role) || '');
      if (!all[role]) { all[role] = Object.create(null); seen[role] = Object.create(null); }
      if (all[role][text]) continue;
      all[role][text] = 1;
      (seen[role][text.length] || (seen[role][text.length] = Object.create(null)))[text] = 1;
    }
    var anchors = Object.create(null);
    var texts = Object.create(null);
    for (i = 0; i < items.length; i++) {
      text = String((items[i] && items[i].text) || '');
      if (!text || anchors[text]) continue;
      role = String((items[i] && items[i].role) || '');
      texts[text] = 1;
      var byLength = seen[role] || Object.create(null);
      var resolved = text;
      for (var length = 1; length < text.length; length++) {
        var bucket = byLength[length];
        if (bucket && bucket[text.slice(0, length)]) { resolved = text.slice(0, length); break; }
      }
      anchors[text] = resolved;
    }
    return { anchors: anchors, texts: texts };
  }

  /**
   * 条目身份键：**同一条**必须算出同一个键。
   *
   * <p>服务端权威的那一半：{@code ChatLogStore.receipt} 推导的幂等键是
   * {@code quest:<回执号>:<条内序号>}（纯图条目 {@code quest:<号>:img:<下标>}）。条目里带
   * {@code key}/{@code id} 时**一律以它为准**（服务端哪天把键透出来，本模块自动跟着走）。
   *
   * <p>服务端没透键的那一半（现在的 `/api/chat/log` 只有 role/text/images）：用
   * `角色 + 正文锚点` 复现同一口径 —— **图片不进身份**：回执的图是"后到"的，同一条先把文字落进
   * 存档、几秒到几十秒后才带上图（服务端 {@code ChatLogStore.append} 的就地更新就是这个形状）。
   * 图片若进身份，"先文字后图"就会被当成两条（用户报的「回执重复」正是它）。
   *
   * @param {object} entry 一条对话条目
   * @param {object|object} [resolver] {@link anchorResolver}（或它的一部分：`{anchors}`）
   */
  function entryId(entry, resolver) {
    if (!entry) return '';
    var explicit = entry.key || entry.id || entry.appendKey;
    if (explicit) return 'k\u0001' + String(explicit);
    var text = String(entry.text || '');
    var anchors = resolver && resolver.anchors ? resolver.anchors : null;
    var anchor = anchors && anchors[text] ? anchors[text] : text;
    return 'e\u0001' + String(entry.role || '') + '\u0001' + anchor;
  }

  /** 一轮对账用的身份视图：`{anchors, texts}`（同一个数组里算好的，供 entryId 复用）。 */
  function identityView(list) {
    return anchorResolver(list);
  }

  /* ── 2. 增量对账（diff） ─────────────────────────────────────────────── */

  /**
   * 上一轮窗口 → 这一轮窗口的**增量对账**（用户要求：按条目身份 diff，只增改变化的，
   * 不整块重建、不重复、不插历史）。
   *
   * <p>规则：
   *   <ul>
   *     <li>顺序按 `next`；身份在 `prev` 里出现过且**还没被这一轮别的条目认领** → {@code keep}
   *         （正文变长了/多了图 → {@code changed:true}，调用方原地换掉这一格，别的节点不动）；</li>
   *     <li>`next` 里身份没出现过 → {@code add}（只在末尾追加，绝不插到中间）；</li>
   *     <li>`prev` 里有、`next` 里没有 → {@code remove}（窗口滚出去了/被删了），
   *         调用方按 id 找到那个节点删掉，**不要整块重建**。</li>
   *   </ul>
   *
   * <p>判据是**身份**不是下标也不是长度：服务端存档是滚动窗口（满了从最旧的丢），
   * "条数变多"永远不成立 —— 实测就是这里，按长度判据一次都不触发，新消息一条都补不上。
   *
   * @param {Array} prev 上一轮已经画过的条目（或它们的身份数组）
   * @param {Array} next 这一轮从服务端读到的条目
   * @returns {{items:Array<{op:string,id:string,entry:object,index:number,prevIndex:number,changed:boolean}>,
   *            add:Array,update:Array,remove:Array,prev:Array,next:Array}}
   */
  function diffEntries(prev, next) {
    var before = Array.isArray(prev) ? prev : [];
    var after = Array.isArray(next) ? next : [];
    // 前缀锚点要在**两轮的并集**上算：只在这一轮里算的话，正文刚变长那一刻锚点会漂。
    var unionTexts = [];
    var i;
    for (i = 0; i < before.length; i++) unionTexts.push(before[i]);
    for (i = 0; i < after.length; i++) unionTexts.push(after[i]);
    var resolver = anchorResolver(unionTexts);

    var pool = Object.create(null);          // 身份 → 上一轮还没被认领的条目（按出现次数配对）
    var prevIds = [];
    for (i = 0; i < before.length; i++) {
      var prevId = entryId(before[i], resolver);
      prevIds.push(prevId);
      (pool[prevId] || (pool[prevId] = [])).push(i);
    }

    var items = [];
    var add = [], update = [], remove = [];
    var cursor = Object.create(null);        // 身份 → 已经吃掉了 pool 里第几个
    var used = Object.create(null);          // 上一轮下标 → 被这一轮认领过（不能被当成删除）
    for (i = 0; i < after.length; i++) {
      var entry = after[i];
      var id = entryId(entry, resolver);
      var slots = pool[id];
      var taken = cursor[id] || 0;
      if (slots && taken < slots.length) {
        cursor[id] = taken + 1;
        var prevIndex = slots[taken];
        used[prevIndex] = 1;
        var changed = entrySig(before[prevIndex]) !== entrySig(entry);
        items.push({ op: 'keep', id: id, entry: entry, index: i, prevIndex: prevIndex, changed: changed });
        if (changed) update.push(entry);
      } else {
        items.push({ op: 'add', id: id, entry: entry, index: i, prevIndex: -1, changed: true });
        add.push(entry);
      }
    }
    for (i = 0; i < before.length; i++) {
      if (used[i]) continue;                 // 这一轮认领过 → 不是删除
      remove.push({ id: prevIds[i], index: i, entry: before[i] });
    }
    return { items: items, add: add, update: update, remove: remove, prev: before, next: after, resolver: resolver };
  }

  /** 身份序列（给"只关心尾部多了哪几条"的调用方用）。 */
  function entryIds(list) {
    var resolver = anchorResolver(Array.isArray(list) ? list : []);
    var out = [];
    var items = Array.isArray(list) ? list : [];
    for (var i = 0; i < items.length; i++) out.push(entryId(items[i], resolver));
    return out;
  }

  /* ── 3. 对话日志的并集 / 对齐 ─────────────────────────────────────────── */

  /**
   * 两份对话正文的**并集**（服务端那份在前、本地独有的接在后面）：按身份配对（同一条重复出现时
   * 按出现次数各算一条），只补本地缺的，**绝不删任何一条**。
   *
   * <p>为什么必须并集而不是整份覆盖：服务端现在也是这份正文的写入者之一（产生回执消息时自己
   * append 一条，幂等键 = 回执号 + 条内序号）。本地这份是"上一次读到之后"的快照时，整份覆盖回去
   * 就会把服务端刚 append 的那条抹掉 —— 用户报的「回执时不时消失一条」正是这个形状。
   */
  function unionEntries(remote, local) {
    var head = Array.isArray(remote) ? remote : [];
    var tail = Array.isArray(local) ? local : [];
    var unionTexts = head.concat(tail);
    var resolver = anchorResolver(unionTexts);
    var known = Object.create(null);
    var i;
    for (i = 0; i < head.length; i++) {
      var key = entryId(head[i], resolver);
      known[key] = (known[key] || 0) + 1;
    }
    var extra = [];
    for (i = 0; i < tail.length; i++) {
      var localKey = entryId(tail[i], resolver);
      var left = known[localKey] || 0;
      if (left > 0) { known[localKey] = left - 1; continue; }    // 服务端已经有了 → 不重复
      extra.push(tail[i]);                                       // 本地独有的 → 接在后面
    }
    if (!extra.length) return head.slice(-ENTRIES_CAP);
    var merged = head.concat(extra);
    return merged.length > ENTRIES_CAP ? merged.slice(-ENTRIES_CAP) : merged;
  }

  /**
   * 服务端日志里"这一轮新出现、本地还没画过"的那一段（**只读尾部，不做整块重建**）。
   *
   * @param {Array} log 这一轮从 `/api/chat/log` 读到的条目（已 canon 化）
   * @param {Array} painted 本地已经画过的条目（`chat.entries` / 快照）
   * @param {Array} [seen] 上一轮服务端窗口的身份快照（`nil` = 第一次同步，只记不补）
   * @returns {{fresh:Array, seen:Array, first:boolean}}
   */
  function freshTail(log, painted, seen) {
    var list = Array.isArray(log) ? log : [];
    var ids = entryIds(list);
    if (!Array.isArray(seen)) return { fresh: [], seen: ids, first: true };
    var before = Object.create(null);
    var i;
    for (i = 0; i < seen.length; i++) before[seen[i]] = (before[seen[i]] || 0) + 1;
    var candidate = [];
    for (i = 0; i < list.length; i++) {
      var left = before[ids[i]] || 0;
      if (left > 0) { before[ids[i]] = left - 1; continue; }     // 上一轮窗口就有 → 不是新的
      candidate.push(list[i]);
    }
    // 再和本地已画的对一遍：前端自己发的那条会被它自己推上存档，那条已经在 DOM 里了，
    // 这里必须认出来，否则每发一条都会多画一份。身份在两轮的并集上算（前缀锚点才稳定）。
    var resolver = anchorResolver(list.concat(candidate).concat(Array.isArray(painted) ? painted : []));
    var drawn = Object.create(null);
    var paintedList = Array.isArray(painted) ? painted : [];
    for (i = 0; i < paintedList.length; i++) {
      var pkey = entryId(paintedList[i], resolver);
      drawn[pkey] = (drawn[pkey] || 0) + 1;
    }
    var fresh = [];
    for (i = 0; i < candidate.length; i++) {
      var key = entryId(candidate[i], resolver);
      var have = drawn[key] || 0;
      if (have > 0) { drawn[key] = have - 1; continue; }         // 本地自己刚发的那条已经在存档里了
      fresh.push(candidate[i]);
    }
    return { fresh: fresh, seen: ids, first: false };
  }

  /**
   * 「该按哪一份重建」的**冻结口径**（打开页面时用一次）：
   *   服务端条数 > 本地条数 → `'remote'`（采用服务端那一份，服务端是权威、不回推）；
   *   本地条数 > 服务端条数 → `'local'`（保留本地这份并整份推给服务端）；
   *   相等                  → `'none'`（以本地为准，省一次写）。
   */
  function pickSource(remote, local) {
    var r = Array.isArray(remote) ? remote : [];
    var l = Array.isArray(local) ? local : [];
    if (r.length > l.length) return 'remote';
    if (l.length > r.length) return 'local';
    return 'none';
  }

  /* ── 3b. 图片身份：**图片路径**（同一个气泡里同一路径只画一次） ──────────── */

  /**
   * 图片的**身份键**：归一化后的路径（`file:///F:/x.png`、`F:\x.png`、`data/generated/x.png`
   * 指同一张图时给同一个键）。
   *
   * <p>用户报的「控制台有图片回执重复」/「同一张图出现两次」在两端是同一个形状：同一条回执的图
   * 从**两条路**都进了渲染（`/api/capture` 的 `images` 与 `/api/quest` 的 `images`/`messages`，
   * 或者 `messages` 里"纯图组"与"带正文组"各带一次）—— 只要图的身份是路径，合并就是幂等的。
   */
  function imageIdentity(file) {
    var path = String(file === null || file === undefined ? '' : file).trim().replace(/\\/g, '/');
    path = path.replace(/^file:\/+/i, '');
    path = path.replace(/\/{2,}/g, '/');
    // **整体小写**：同一台 Windows 上 `Data/Generated/A.PNG` 与 `data/generated/a.png` 是同一张图；
    // 身份只用来判"画过没有"，显示与请求一律用**第一次见到的那条原路径**（见 dedupeImages）。
    return path.toLowerCase();
  }

  /** 图片路径是否指同一张图。 */
  function sameImage(a, b) {
    var left = imageIdentity(a);
    return !!left && left === imageIdentity(b);
  }

  /** 按图片路径去重（保持原顺序；空串、重复项丢掉）。 */
  function dedupeImages(files) {
    var out = [];
    var seen = Object.create(null);
    var list = Array.isArray(files) ? files : [];
    for (var i = 0; i < list.length; i++) {
      var file = imageFile(list[i]);
      if (!file) continue;
      var key = imageIdentity(file);
      if (seen[key]) continue;
      seen[key] = 1;
      out.push(file);
    }
    return out;
  }

  /**
   * 把若干张图并进**它该去的那条条目**（幂等；已存在的路径一张都不重复画）。
   *
   * <p>为什么必须有它（"图片晚到"那一半）：回执的图比正文晚到几秒到几十秒，那一轮可能已经有
   * 别的气泡插在末尾了。老写法只认"末尾那条还是我才并进去"，一旦不是就**新起一条** ——
   * 同一张图于是出现两次（用户报的「图片回执重复」）。现在按**条目身份**找那条（`entryId`），
   * 找到就并进去，找不到才新建；路径级去重保证同一路径在一条里只出现一次。
   *
   * @param {Array} entries 本地条目列表（**会被修改**：图片并进命中那条，或 push 一条新的）
   * @param {string[]} files 这次要落位的图片（可含重复，内部去重）
   * @param {object} [options] `{role, text, match}`：新建条目时的角色/正文；
   *        `match` = 自定义"哪条是它该去的地方"（默认：最后一条同身份角色的条目）
   * @returns {{entry:object, added:string[], created:boolean}|null}
   */
  function mergeImages(entries, files, options) {
    var list = Array.isArray(entries) ? entries : null;
    var incoming = dedupeImages(files);
    if (!list || !incoming.length) return null;
    var opts = options || {};
    var role = opts.role || 'bot';
    var where = typeof opts.match === 'function' ? opts.match : null;
    var owner = null;
    if (where) {
      for (var m = list.length - 1; m >= 0 && !owner; m--) if (list[m] && where(list[m], m)) owner = list[m];
    } else {
      for (var n = list.length - 1; n >= 0 && !owner; n--) {
        var entry = list[n];
        if (entry && entry.role === role) owner = entry;
      }
    }
    var created = false;
    if (!owner) {
      owner = { role: role, text: String(opts.text || '') };
      list.push(owner);
      created = true;
    }
    var mine = Array.isArray(owner.images) ? owner.images : (owner.images = []);
    var have = Object.create(null);
    for (var h = 0; h < mine.length; h++) have[imageIdentity(mine[h])] = 1;
    var added = [];
    for (var i = 0; i < incoming.length; i++) {
      var key = imageIdentity(incoming[i]);
      if (have[key]) continue;            // **路径级去重**：同一张图在一条里只画一次
      have[key] = 1;
      mine.push(incoming[i]);
      added.push(incoming[i]);
    }
    return { entry: owner, added: added, created: created };
  }

  /** 条目集合里已经出现过的图片路径（`imageIdentity` 口径）—— 渲染前"这张画过没有"的唯一判据。 */
  function paintedImages(entries) {
    var seen = Object.create(null);
    var list = Array.isArray(entries) ? entries : [];
    for (var i = 0; i < list.length; i++) {
      var images = list[i] && list[i].images;
      if (!Array.isArray(images)) continue;
      for (var j = 0; j < images.length; j++) {
        var key = imageIdentity(images[j]);
        if (key) seen[key] = 1;
      }
    }
    return seen;
  }

  /** 从一堆图片里挑出"还没画过"的那些（按路径；保持顺序）。 */
  function unpaintedImages(entries, files) {
    var seen = paintedImages(entries);
    var out = [];
    var list = dedupeImages(files);
    for (var i = 0; i < list.length; i++) {
      var key = imageIdentity(list[i]);
      if (!key || seen[key]) continue;
      seen[key] = 1;
      out.push(list[i]);
    }
    return out;
  }

  /* ── 4. 跟单状态机 ───────────────────────────────────────────────────── */

  /**
   * 一条回执(`/api/capture` 或 `/api/quest` 的返回) → 按**消息身份**切出的条目。
   *
   * <p>与 `ChatLogStore.receipt()` 的分组规则逐条一致：
   *   <ul>
   *     <li>每条**带正文的**消息占一条，条内序号 1、2、3…（= 服务端 `quest:<号>:<序号>` 的序号）；</li>
   *     <li>**图片并进上一条正文**（"先文字后图"仍是同一个气泡，绝不为了图新开一个气泡）；</li>
   *     <li>整条回执一条正文都没有时，图片各占一条只有图的条目（键 `quest:<号>:img:<下标>`）。</li>
   *   </ul>
   *
   * @returns {{texts:Array<string>, images:Array<string>, seqs:Array<number>,
   *            imageSeqs:Array<{file:string,seq:number}>, imageOnly:Array<{key:string,images:Array<string>}>}}
   */
  function receiptParts(payload) {
    var out = { texts: [], images: [], seqs: [], imageSeqs: [], imageOnly: [] };
    var lastSeq = 0;                 // 最近一条**有正文**的片段序号（图片的落点；0 = 还没有正文）
    if (payload && Array.isArray(payload.messages) && payload.messages.length) {
      var seq = 0;
      for (var g = 0; g < payload.messages.length; g++) {
        var segments = payload.messages[g] || [];
        var text = '';
        var files = [];
        for (var s = 0; s < segments.length; s++) {
          var segment = segments[s] || {};
          if (segment.type === 'text') {
            var value = String(segment.text === null || segment.text === undefined ? '' : segment.text);
            if (value) text = text ? text + '\n' + value : value;
          } else if (segment.type === 'image') {
            var file = imageFile(segment);
            if (file && files.indexOf(file) < 0) files.push(file);
          }
        }
        if (text) {
          seq++;
          out.texts.push(text);
          out.seqs.push(seq);
          lastSeq = seq;
        }
        for (var f = 0; f < files.length; f++) {
          if (out.images.indexOf(files[f]) < 0) out.images.push(files[f]);
          // 图片归到**它前面那条正文**（与服务端 ChatLogStore.receipt 一致）；
          // 前面还没有正文时 seq=0（服务端那些条目就是 `quest:<号>:img:<下标>`）。
          out.imageSeqs.push({ file: files[f], seq: lastSeq, all: files });
        }
        if (!text && files.length && lastSeq === 0) out.imageOnly.push({ key: IMG_KEY + ':' + g, images: files });
      }
      return out;
    }
    // 老后端 / 老回执：只有扁平的 texts / images（下标就是消息身份，空段保留）
    var texts = Array.isArray(payload && payload.texts) ? payload.texts : [];
    for (var t = 0; t < texts.length; t++) {
      var part = texts[t] === null || texts[t] === undefined ? '' : String(texts[t]);
      out.texts.push(part);
      out.seqs.push(t + 1);
      if (part) lastSeq = t + 1;
    }
    var images = Array.isArray(payload && payload.images) ? payload.images : [];
    for (var i = 0; i < images.length; i++) {
      var raw = imageFile(images[i]);
      if (raw && out.images.indexOf(raw) < 0) out.images.push(raw);
      if (raw) out.imageSeqs.push({ file: raw, seq: lastSeq });
    }
    return out;
  }

  /** 只取图片列表（顺序去重）。 */
  function receiptImages(payload) { return receiptParts(payload).images; }

  /** 回执的条内序号（与服务端 append 的幂等键同口径：`quest:<号>:<序号>`）。 */
  function receiptKey(number, seq) {
    var n = Number(number) || 0;
    return n > 0 && seq > 0 ? 'quest:' + n + ':' + seq : '';
  }

  /**
   * 把服务的 `/api/progress`（只读）解析成"还有活没活"。
   * **只看这两个字段**：`running`（SD 正在生成）、`queue`（生成队列里还有几个）。
   * 认不出来一律当"没有活"（观测不到就不留人，绝不靠它硬撑）。
   */
  function progressLive(progress) {
    if (!progress || typeof progress !== 'object') return { live: false, known: false, running: false, queue: 0 };
    var known = (progress.running !== undefined) || (progress.queue !== undefined);
    if (!known) return { live: false, known: false, running: false, queue: 0 };
    var running = progress.running === true;
    var queue = Number(progress.queue) || 0;
    return { live: running || queue > 0, known: true, running: running, queue: queue };
  }

  /** 跟单状态机的默认参数（两端各自可覆盖，但判据只有这一份）。 */
  var FOLLOW_DEFAULTS = {
    maxMillis: 15 * 60 * 1000,   // 兜底：异常残留的回执不会让轮询永远跑下去（与 Bot#webActive 同口径）
    graceRounds: 2,              // 队列空了再多等几轮：覆盖"图已投递、正要落进回执"的那几十毫秒
    idleRounds: 2                // 连续几轮没新东西才算静默
  };

  /**
   * 新建一个跟单状态机的状态。
   *
   * <p>收工判据（**唯一**，两端同一份）：`closed` → 收；超时 → 收；
   * `!done`（指令执行体还在跑）→ 跟；`done` 且静默够了 → 看 `/api/progress`：
   *   `running || queue > 0` → 继续跟（**这就是"别再用有图/6 小时/unread/busy 当窗口"的落点**：
   *   `/gen` 这类指令把出图丢进队列就返回，`done` 在受理后几十毫秒就变成 true，
   *   只看 done 会立刻收工、图十几秒后才回来 —— 图发到了服务端，页面却早就不接了）；
   *   真没活了 → 连续 {@link FOLLOW_DEFAULTS.graceRounds} 轮确认，然后收。
   */
  function newFollow(id, quest, options) {
    var opts = options || {};
    return {
      id: id === null || id === undefined ? '' : String(id),
      quest: Number(quest) || 0,
      startedAt: Number(opts.now) || Date.now(),
      maxMillis: Number(opts.maxMillis) || FOLLOW_DEFAULTS.maxMillis,
      graceRounds: opts.graceRounds === undefined ? FOLLOW_DEFAULTS.graceRounds : Number(opts.graceRounds),
      idleRoundsWanted: opts.idleRounds === undefined ? FOLLOW_DEFAULTS.idleRounds : Number(opts.idleRounds),
      idle: 0,               // 连续没新东西的轮数
      grace: 0,              // "done 且静默够了、队列也空了"的连续轮数
      needsProgress: false,  // 这一轮要不要去问 /api/progress
      lastCapture: null,
      settled: false
    };
  }

  /**
   * 跟单状态机：喂进这一轮的 `/api/capture` 结果（与可选的 `/api/progress`），决定"接着跟还是收工"。
   *
   * <p>**纯函数式的推进**：只改传进来的 `state`，不发请求、不碰 DOM。调用方拿
   * `decision.needsProgress === true` 时去问一次只读的 `/api/progress`，下一轮把它作为
   * `progress` 传回来（也可以同轮传）。
   *
   * @param {object} state {@link newFollow} 的产物
   * @param {object} capture 这一轮 `/api/capture` 的返回（null/异常时传 `{error:true}`）
   * @param {object} [progress] `/api/progress` 的返回（只在这一轮问过时传）
   * @param {number} [nowMillis] 这一轮的墙钟（测试要用；默认 `Date.now()`）
   * @returns {{settled:boolean, needsProgress:boolean, progressed:boolean, decision:string,
   *            idle:number, grace:number, elapsed:number}}
   */
  function advance(state, capture, progress, nowMillis) {
    var data = capture || {};
    var now = Number(nowMillis) || Date.now();
    state.lastCapture = data;
    var elapsed = now - (Number(state.startedAt) || now);

    if (data.closed === true) return finish(state, 'closed', elapsed);
    if (elapsed > state.maxMillis) return finish(state, 'timeout', elapsed);

    var texts = Array.isArray(data.texts) ? data.texts : [];
    var images = Array.isArray(data.images) ? data.images : [];
    var progressed = texts.length > 0 || images.length > 0;
    state.idle = progressed ? 0 : state.idle + 1;
    if (progressed) state.grace = 0;

    if (data.done !== true) {                 // 指令执行体还在跑 → 一定接着跟
      state.needsProgress = false;
      return done(state, false, 'running', elapsed, progressed);
    }
    if (state.idle < state.idleRoundsWanted) { // 静默还不够：多等两轮（图文是分几次到的）
      state.needsProgress = false;
      return done(state, false, 'idle', elapsed, progressed);
    }
    // done 且静默够了：**只有这里**需要问一次 /api/progress
    if (progress === undefined || progress === null) {
      state.needsProgress = true;
      return done(state, false, 'progress', elapsed, progressed);
    }
    state.needsProgress = false;
    var live = progressLive(progress);
    if (live.live) { state.grace = 0; return done(state, false, 'queue', elapsed, progressed); }
    state.grace += 1;
    if (state.grace >= state.graceRounds) return finish(state, 'settled', elapsed);
    return done(state, false, 'grace', elapsed, progressed);
  }

  function done(state, settled, decision, elapsed, progressed) {
    state.settled = !!settled;
    return { settled: !!settled, needsProgress: !!state.needsProgress, progressed: !!progressed,
      decision: decision, idle: state.idle, grace: state.grace, elapsed: elapsed };
  }

  function finish(state, decision, elapsed) {
    state.settled = true;
    state.needsProgress = false;
    return { settled: true, needsProgress: false, progressed: false, decision: decision,
      idle: state.idle, grace: state.grace, elapsed: elapsed };
  }

  /**
   * 一条回执"还在跑吗"（补拉链续跟用的唯一判据）：服务端明确说没关、且
   * （执行体没跑完 **或** 还在忙）→ 接着跟。真跑完的（done 且不忙）与磁盘读回来的（closed）
   * 一律不跟 —— 那种回执的图已经一次性取全了，再跟只是白打请求。
   */
  function stillRunning(payload) {
    if (!payload || payload.error || payload.closed === true) return false;
    return payload.done !== true || payload.busy === true;
  }

  /* ── 5. 未读记账 ─────────────────────────────────────────────────────── */

  /**
   * 未读账本：**服务端的回执号说了算**。
   *
   * <p>为什么不靠内存表：用户原话里的判据是"按服务端回执号算"。服务端 `/api/quests` 给的是
   * 权威的未读总数与每条的 `unread`；页面在**回执屏点开**那一条时才把号记进来、下次拿到
   * 服务端数就对齐（不回弹）。内存表只当"请求在飞的时候"的临时视图，永远以服务端数为准。
   */
  function newUnread(options) {
    var opts = options || {};
    return {
      server: 0,                                   // 服务端权威未读数（/api/quests 或 /api/status）
      latest: 0,                                   // 服务端最新回执号
      scope: opts.scope === null || opts.scope === undefined ? '' : String(opts.scope),
      read: Object.create(null),                   // 本地已点开的号（"打开就标读"的即时视图）
      pending: Object.create(null)                 // 点了但还没落地的号
    };
  }

  /** 服务端返回（`/api/quests` 的 `{quests,unread,latest}`）→ 账本（服务端数为准，覆盖本地猜测）。 */
  function reconcileUnread(state, payload) {
    if (!payload || typeof payload !== 'object') return unreadCount(state);
    if (Number.isFinite(Number(payload.unread))) state.server = Math.max(0, Number(payload.unread));
    if (Number.isFinite(Number(payload.latest))) state.latest = Math.max(0, Number(payload.latest));
    if (Array.isArray(payload.quests)) {
      for (var i = 0; i < payload.quests.length; i++) {
        var row = payload.quests[i] || {};
        var number = Number(row.number) || 0;
        if (number <= 0) continue;
        // 服务端说这条已读：本地那些"点开但还没落地"的记账就没有意义了（已包含在服务端数里），
        // 否则会重复从服务端总数里再扣一次（实测：服务端 unread=1 + 一条飞行中的 pending
        // 会被算成 0，界面上未读凭空少一条）。
        if (row.unread === false) { state.read[number] = 1; state.pending[number] = 0; }
      }
    }
    return unreadCount(state);
  }

  /** 页面在**回执屏点开**某号：立刻从本地视图里扣掉，并记成待落地（等 `/api/quests/read` 的成功）。 */
  function markRead(state, numbers) {
    var list = Array.isArray(numbers) ? numbers : [numbers];
    var before = unreadCount(state);
    for (var i = 0; i < list.length; i++) {
      var number = Number(list[i]) || 0;
      if (number <= 0) continue;
      state.read[number] = 1;
      state.pending[number] = 1;
    }
    if (before > 0) state.server = Math.max(0, state.server - countNew(state, list));
    return unreadCount(state);
  }

  function countNew(state, list) {
    var n = 0;
    for (var i = 0; i < list.length; i++) {
      var number = Number(list[i]) || 0;
      if (number > 0 && !state.pending[number]) n++;
    }
    return n;
  }

  /** `/api/quests/read` 回来（或失败回滚）：`ok=false` 就把待落地的号退回去。 */
  function confirmRead(state, numbers, ok) {
    var list = Array.isArray(numbers) ? numbers : [numbers];
    for (var i = 0; i < list.length; i++) {
      var number = Number(list[i]) || 0;
      if (number <= 0) continue;
      if (ok === false) { delete state.read[number]; delete state.pending[number]; }
      else state.pending[number] = 0;
    }
    return unreadCount(state);
  }

  /** 当前该显示的未读数（服务端数为准；点开但还没落地的按本地减）。 */
  function unreadCount(state) {
    if (!state) return 0;
    var server = Math.max(0, Number(state.server) || 0);
    var flying = 0;
    for (var key in state.pending) if (state.pending[key]) flying++;
    return Math.max(0, server - flying);
  }

  /* ── 5b. 事件队列（取件 → 渲染 → ack） ───────────────────────────────────
   *
   * 用户定的契约（**冻结**）：
   *   POST /api/events      {scope, after, limit?} → {items:[…], next:N, latest:M, hasMore:bool}
   *   POST /api/events/ack  {scope, seq}           （只进不退）
   *   条目 {seq(递增), id(稳定), type, at}；
   *     type=message → {role/text/images/quest/index}
   *     type=patch   → {target:<某条 message 的 id>, images}
   *   服务端保证：同一信息只入队一次（幂等）、items 严格升序、多 scope 隔离、持久化。
   *
   * 前端规矩（**唯一路径**）：一个 drain 循环 → 按 seq 顺序渲染 → **渲染成功后再 ack** →
   * 本地只持久化 `lastSeq`。不猜身份、不拼正文、不合并多源 —— 幂等由 seq/id 保证。
   */

  /** 事件条目的**稳定 id**：服务端给了就用它的；只给了回执号+条内序号就按同一口径补出来。 */
  function eventId(item) {
    if (!item || typeof item !== 'object') return '';
    if (item.id) return String(item.id);
    var index = Number(item.index);
    if (isFinite(index) && index > 0) {
      var quest = Number(item.quest) || 0;
      return quest > 0 ? 'message:quest:' + quest + ':' + index : 'message:index:' + index;
    }
    return item.seq === undefined ? '' : 'message:seq:' + Number(item.seq);
  }

  /**
   * 一件事件 → 视图该怎么画（**纯决策，不碰 DOM**）。
   *
   * @returns {{kind:'message'|'patch'|'ignore'|'ackonly', id:string, seq:number, entry:object,
   *            target:string, images:string[], reason:string}}
   */
  function eventAction(item, options) {
    var opts = options || {};
    var seq = Number(item && item.seq);
    var base = { seq: isFinite(seq) ? seq : 0, id: eventId(item), entry: null, target: '', images: [], reason: '' };
    if (!item || typeof item !== 'object') { base.kind = 'ackonly'; base.reason = 'not-an-object'; return base; }
    if (!isFinite(seq) || seq <= 0) { base.kind = 'ackonly'; base.reason = 'no-seq'; return base; }
    var type = String(item.type || '');
    if (type === 'message') {
      var entry = cleanEntry(item);
      if (!entry) { base.kind = 'ignore'; base.reason = 'empty-entry'; return base; }
      if (opts.existing) {
        for (var i = 0; i < opts.existing.length; i++) {
          if (opts.idOf && opts.idOf(opts.existing[i]) === base.id) { base.kind = 'ignore'; base.reason = 'duplicate'; return base; }
        }
      }
      base.kind = 'message';
      base.entry = entry;
      return base;
    }
    if (type === 'patch') {
      var images = dedupeImages(item.images || []);
      if (!images.length) { base.kind = 'ignore'; base.reason = 'empty-patch'; return base; }
      base.kind = 'patch';
      base.images = images;
      base.target = String(item.target || '');
      if (!base.target) { base.kind = 'ignore'; base.reason = 'patch-without-target'; return base; }
      if (opts.resolve && !opts.resolve(base.target)) { base.kind = 'ignore'; base.reason = 'target-not-loaded'; return base; }
      return base;
    }
    // 未知 type：**忽略而不是崩**（契约要求）
    base.kind = 'ignore';
    base.reason = 'unknown-type:' + (type || '(empty)');
    return base;
  }

  /** 取件返回的规范化：坏 JSON / 缺字段都不抛，返回一个"这一轮什么都没有"的形状。 */
  function eventBatch(payload, after) {
    var cursor = Number(after) || 0;
    var out = { items: [], next: cursor, latest: cursor, hasMore: false, ok: false };
    if (!payload || typeof payload !== 'object') return out;
    var items = Array.isArray(payload.items) ? payload.items : [];
    for (var i = 0; i < items.length; i++) {
      var seq = Number(items[i] && items[i].seq);
      if (!isFinite(seq) || seq <= cursor) continue;         // 只认"游标之后"的（服务端保证升序，这里再挡一次）
      out.items.push(items[i]);
    }
    // 严格升序：同一 seq 只留第一件（重复投递由渲染侧幂等兜底）
    out.items.sort(function (a, b) { return Number(a.seq) - Number(b.seq); });
    out.next = Number(payload.next);
    if (!isFinite(out.next) || out.next < cursor) out.next = out.items.length ? Number(out.items[out.items.length - 1].seq) : cursor;
    out.latest = Number(payload.latest);
    if (!isFinite(out.latest)) out.latest = out.next;
    out.hasMore = payload.hasMore === true || out.next < out.latest;
    out.ok = true;
    return out;
  }

  /**
   * 建一个 drain 循环的**状态**（调用方只提供三个副作用：取件 / 渲染 / ack）。
   *
   * @param {object} io `{request(body) → Promise<payload|null>, render(action) → boolean|null,
   *                      ack(seq) → Promise|null, now() → ms, onError(error)}`
   * @param {object} [options] `{interval, batchLimit, backoff, unavailableAfter}`
   */
  function newDrain(io, options) {
    var opts = options || {};
    return {
      io: io || {},
      interval: Number(opts.interval) || 2000,
      batchLimit: Number(opts.batchLimit) || 200,
      lastSeq: 0,                 // 已经**渲染并 ack** 到的最大 seq（本地持久化的就是它）
      running: false,
      timer: null,
      unavailable: false,         // 服务端还没有 /api/events（404）时置上：退化成"历史 + 写回"模式
      failures: 0,
      lastDrainAt: 0,
      stats: { drains: 0, items: 0, acks: 0, ignored: 0, errors: 0, lastReason: '' }
    };
  }

  /** 一次取件 + 渲染 + ack（同一个 tick 里不会被重入；失败保留游标，下次重取）。 */
  function drainOnce(state) {
    if (!state || state.running || state.unavailable) return Promise.resolve(0);
    var io = state.io || {};
    if (typeof io.request !== 'function') return Promise.resolve(0);
    state.running = true;
    state.stats.drains++;
    state.lastDrainAt = Date.now();
    return Promise.resolve()
      .then(function () { return io.request({ after: Number(state.lastSeq) || 0, limit: state.batchLimit }); })
      .then(function (payload) {
        var batch = eventBatch(payload, state.lastSeq);
        if (!batch.ok) { state.stats.lastReason = 'bad-payload'; return 0; }
        var applied = 0;
        for (var i = 0; i < batch.items.length; i++) {
          var action = eventAction(batch.items[i], { resolve: io.resolve, existing: io.existing, idOf: io.idOf });
          var ok = false;
          if (action.kind === 'ignore') { state.stats.ignored++; state.stats.lastReason = action.reason; }
          else if (action.kind === 'ackonly') { state.stats.lastReason = action.reason; }
          else if (typeof io.render === 'function') ok = io.render(action) !== false;
          // 渲染成功（或本来就没事可做）才推进游标；**失败不 ack**，下次重取同一批
          if (action.kind === 'ignore' || action.kind === 'ackonly' || ok) {
            state.lastSeq = Math.max(state.lastSeq, action.seq);
            state.stats.items++;
            applied++;
            if (typeof io.ack === 'function') {
              try { io.ack(state.lastSeq); state.stats.acks++; } catch (error) { /* ack 失败：下一轮还会重取这一段，渲染侧幂等 */ }
            }
          } else break;
        }
        state.failures = 0;
        return applied;
      })
      .catch(function (error) {
        state.failures++;
        state.stats.errors++;
        state.stats.lastReason = 'error:' + (error && error.message ? error.message : String(error));
        if (error && (error.status === 404 || error.code === 404 || error.status === 405)) state.unavailable = true;
        if (typeof io.onError === 'function') io.onError(error);
        return 0;
      })
      .then(function (value) { state.running = false; return value; });
  }

  /** 开始 drain 循环（一个定时器；唤醒时用 {@link drainNow} 立刻插一拍）。 */
  function startDrain(state) {
    if (!state || state.timer) return state;
    var tick = function () {
      state.timer = null;
      Promise.resolve(drainOnce(state)).then(function () {
        if (!state.timer && !state.unavailable) state.timer = setTimeout(tick, state.interval);
      });
    };
    state.timer = setTimeout(tick, 0);            // 起手立刻取一次
    return state;
  }

  /** 停表（注销页面时用）。 */
  function stopDrain(state) {
    if (!state) return state;
    if (state.timer) { clearTimeout(state.timer); state.timer = null; }
    return state;
  }

  /** 唤醒/进屏/回前台：立刻补一拍（**不等下一轮**）。 */
  function drainNow(state) { return drainOnce(state); }

  /** 本地只持久化 lastSeq（accepted 语义）：读到坏值当 0。 */
  function readCursor(store, key) {
    try {
      var raw = store && store.getItem ? store.getItem(key) : null;
      var value = Number(raw);
      return isFinite(value) && value > 0 ? Math.floor(value) : 0;
    } catch (error) { return 0; }
  }

  function writeCursor(store, key, seq) {
    try {
      if (store && store.setItem) store.setItem(key, String(Math.max(0, Number(seq) || 0)));
    } catch (error) { /* 隐私模式：本次会话里有效 */ }
  }

  /* ── 6. 滚动锚定（两端同一套判据） ───────────────────────────────────── */

  /**
   * 渲染**之前**记锚点：`{top, atBottom, height}`。
   *
   * <p>为什么必须在改内容**之前**记："在不在底部"是按 `scrollHeight - scrollTop - clientHeight`
   * 算的，插入一条就让 `scrollHeight` 变大 —— 改完再判，差出来的那几十像素已经超过阈值，
   * 明明刚才贴在底部也会被判成"不在底部"（实测 gap 从 0 直接变 63），新消息就不跟手了。
   */
  function grabAnchor(el, slack) {
    if (!el) return null;
    var height = Number(el.scrollHeight) || 0;
    var top = Number(el.scrollTop) || 0;
    return { top: top, atBottom: nodeAtBottom(el, slack), height: height };
  }

  /** 元素是不是贴底（距底 < slack 像素）；量不出高度时当贴底（和桌面版同口径）。 */
  function nodeAtBottom(el, slack) {
    if (!el) return true;
    var height = Number(el.scrollHeight) || 0;
    if (height <= 0) return true;
    return height - (Number(el.scrollTop) || 0) - (Number(el.clientHeight) || 0) < (slack || STICK_PX);
  }

  /**
   * 渲染**之后**落位：
   *   · 原本在底部 → 贴到**新的**底部（`atBottom:true`）；
   *   · 原本不在底部 → 用几何差把 `scrollTop` 放回原处（`atBottom:false`），
   *     并返回 `false` 让调用方知道"这次没有跟随"（可以提示"有新消息 ↓"而不是硬拽）。
   *
   * <p>这就是"用户往上翻过之后，任何周期行为都不许改变 scrollTop"的落点：差值补偿能把
   * "上面插进来了 N 像素"消掉，用户看到的那一行纹丝不动。
   */
  function settleAnchor(el, anchor, options) {
    if (!el) return false;
    var smooth = !!(options && options.smooth);
    if (!anchor || anchor.atBottom) {
      var bottom = Number(el.scrollHeight) || 0;
      if (smooth && el.scrollTo) {
        try { el.scrollTo({ top: bottom, behavior: 'smooth' }); return true; } catch (error) { /* 老浏览器退回直设 */ }
      }
      el.scrollTop = bottom;
      return true;
    }
    var delta = (Number(el.scrollHeight) || 0) - (Number(anchor.height) || 0);
    el.scrollTop = Math.max(0, (Number(anchor.top) || 0) + (delta > 0 ? delta : 0));
    return false;
  }

  /**
   * 图片是**异步撑高**的（懒加载 + 解码），它们撑高的那一刻最容易把视图顶走。
   * 这里给元素里所有还没校准过的 `<img>` 各挂一次性 load/error 校准：
   * **只在挂的时候本来就在底部才贴底**；用户往上翻着读历史时，图加载完一动不动。
   *
   * @returns {number} 这次新挂上校准的图片数
   */
  function stickImages(el, slack) {
    if (!el || !el.querySelectorAll) return 0;
    var stick = nodeAtBottom(el, slack);
    var images = el.querySelectorAll('img');
    var count = 0;
    for (var i = 0; i < images.length; i++) {
      var img = images[i];
      if (!img || img.__pixikoStick) continue;
      img.__pixikoStick = true;
      count++;
      (function (node) {
        var settle = function () {
          node.__pixikoStick = false;
          if (stick) el.scrollTop = Number(el.scrollHeight) || 0;
        };
        node.addEventListener('load', settle, { once: true });
        node.addEventListener('error', settle, { once: true });
      }(img));
    }
    return count;
  }

  /* ── 7. 对外 ─────────────────────────────────────────────────────────── */

  return {
    // 常量
    ENTRIES_CAP: ENTRIES_CAP,
    STICK_PX: STICK_PX,
    FOLLOW_DEFAULTS: FOLLOW_DEFAULTS,
    // 条目
    imageFile: imageFile,
    cleanEntry: cleanEntry,
    cleanEntries: cleanEntries,
    entrySig: entrySig,
    entryId: entryId,
    entryIds: entryIds,
    identityView: identityView,
    prefixPair: prefixPair,
    sameEntry: function (a, b) { return entrySig(a) === entrySig(b); },
    // 对账
    diffEntries: diffEntries,
    unionEntries: unionEntries,
    freshTail: freshTail,
    pickSource: pickSource,
    // 回执
    receiptParts: receiptParts,
    receiptImages: receiptImages,
    receiptKey: receiptKey,
    // 图片身份（路径）
    imageIdentity: imageIdentity,
    sameImage: sameImage,
    dedupeImages: dedupeImages,
    mergeImages: mergeImages,
    paintedImages: paintedImages,
    unpaintedImages: unpaintedImages,
    // 跟单
    progressLive: progressLive,
    newFollow: newFollow,
    advance: advance,
    stillRunning: stillRunning,
    // 未读
    newUnread: newUnread,
    reconcileUnread: reconcileUnread,
    markRead: markRead,
    confirmRead: confirmRead,
    unreadCount: unreadCount,
    // 事件队列（取件 → 渲染 → ack）
    eventId: eventId,
    eventAction: eventAction,
    eventBatch: eventBatch,
    newDrain: newDrain,
    drainOnce: drainOnce,
    startDrain: startDrain,
    stopDrain: stopDrain,
    drainNow: drainNow,
    readCursor: readCursor,
    writeCursor: writeCursor,
    // 滚动锚定
    grabAnchor: grabAnchor,
    settleAnchor: settleAnchor,
    nodeAtBottom: nodeAtBottom,
    stickImages: stickImages
  };
}));
