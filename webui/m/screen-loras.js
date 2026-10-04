/*
 * Pixiko 手机端 —— 「LoRA」屏（M3）。
 *
 * 同样是按 App 习惯重写的一套移动 UI（不是给桌面控制台注入 CSS），复用桌面那套 /api 接口：
 * /api/loras、/api/lora/download、/api/lora/progress、/api/lora/cover、/api/lora/preview，
 * 改名/删除沿用桌面做法发指令（/api/command 的 `.lora rename …` / `.lora delete …`）。
 *
 * 分组口径与后端一致：`/api/loras` 的 `groups[].key` 就是**归属栈**（Forge 预设栈：anima / xl / …），
 * 组头文字取 `groups[].label`（"Anima 栈"）；判不出栈的组 label 为空，这里显示成「未识别底模」，
 * 并由后端排序放在最后。每一行仍显示它自己的底模标签（同一个栈下可以有多个底模）。
 *
 * 与桌面版的交互差异：
 *   · 桌面一行四个常驻按钮（加载/改名/删除/…）；手机一行只留一个「⋯」，四个动作全收进底部 sheet
 *     （触摸目标 ≥44px，不会误触）。
 *   · 桌面「筛选本地 LoRA」是行内输入框 + 整表重画；手机用同一套过滤逻辑，但结果实时重画并显示条数。
 *   · 桌面把「Civitai 搜索/下载」铺成一张大卡片常驻在列表上面；手机折进「下载 LoRA」折叠块，
 *     默认收起，不占列表空间。
 *   · 桌面的下载进度条挂在卡片里；手机同源数据，进度块固定在列表顶部，结束后自动收起来。
 *
 * 纪律：本文件不写任何用户数据（不改 LoRA 目录、不下载），下载/补图/改名/删除都由用户主动点。
 */
(function () {
  'use strict';

  /* ───────────────────────── 样式（一次性注入，全部 .lo- 前缀） ───────────────────────── */

  /* 排版尺度（与 app.css 的移动端令牌同值，令牌缺失时用兜底值，见任务书「统一排版规格」）：
       正文 15px · 说明/参数名 13px · 标签/胶囊 12px（下限） · 行高 1.5 / 1.45；行距 10px。
     本轮重点：**LoRA 文件名、底模/栈、说明一律完整显示** —— `.lo-name` / `.lo-sub` 原先
     `nowrap + ellipsis`，长文件名（`Naruse_Shiroha_-_Summer_po…`）被剪掉 20px；现在改成换行。 */
  var CSS = [
    '.lo-wrap{display:flex;flex-direction:column;gap:12px;padding:0 0 96px;}',
    '.lo-note{font-size:13px;line-height:1.45;color:var(--muted,#93a4c4);padding:0 4px;',
    'overflow-wrap:anywhere;word-break:break-word;white-space:pre-line;}',
    '.lo-toolbar{display:flex;gap:8px;}',
    '.lo-search{flex:1;min-width:0;box-sizing:border-box;height:44px;padding:0 12px;background:#131c2e;',
    'color:#e8eefc;border:1px solid #26324c;border-radius:10px;font-size:16px;font-family:inherit;}',
    '.lo-search:focus{outline:none;border-color:#5aa2ff;}',
    '.lo-btn{min-height:44px;min-width:44px;padding:0 14px;border-radius:10px;border:1px solid transparent;',
    'background:#5aa2ff;color:#08111f;font-size:15px;font-weight:600;font-family:inherit;cursor:pointer;',
    'display:inline-flex;align-items:center;justify-content:center;}',
    '.lo-btn:active{opacity:.72;}',
    '.lo-btn[disabled]{opacity:.45;}',
    '.lo-btn.lo-ghost{background:#131c2e;color:#cfe0ff;border-color:#2a3a5c;}',
    '.lo-btn.lo-danger{background:#3a1c22;color:#ff6b6b;border-color:#5c2a30;}',
    '.lo-block{background:#131c2e;border-radius:14px;overflow:hidden;border:1px solid #1d2942;}',
    '.lo-block.lo-collapsed .lo-panel{display:none;}',
    '.lo-block.lo-collapsed .lo-chev{transform:rotate(-90deg);}',
    '.lo-head{display:flex;align-items:center;gap:8px;width:100%;min-height:44px;padding:10px 14px;',
    'background:none;border:0;color:#e8eefc;font-size:15px;font-weight:600;line-height:1.5;text-align:left;cursor:pointer;font-family:inherit;}',
    '.lo-head:active{background:#1a2540;}',
    '.lo-chev{margin-left:auto;flex:none;color:#93a4c4;transition:transform .18s ease;}',
    '.lo-panel{display:flex;flex-direction:column;gap:10px;padding:0 14px 14px;}',
    '.lo-field{display:flex;flex-direction:column;gap:6px;}',
    '.lo-label{font-size:13px;line-height:1.45;color:#93a4c4;}',
    '.lo-input{width:100%;box-sizing:border-box;height:44px;padding:0 12px;background:#0b1220;color:#e8eefc;',
    'border:1px solid #26324c;border-radius:10px;font-size:16px;font-family:inherit;}',
    '.lo-input:focus{outline:none;border-color:#5aa2ff;}',
    '.lo-progress{background:#131c2e;border-radius:14px;border:1px solid #1d2942;padding:14px;}',
    '.lo-track{height:6px;border-radius:4px;background:#1b2540;overflow:hidden;}',
    '.lo-fill{height:100%;width:0;background:#5aa2ff;border-radius:4px;transition:width .3s ease;}',
    '.lo-progress.lo-indeterminate .lo-track{opacity:.5;}',
    '.lo-progress.lo-indeterminate .lo-fill{width:36%!important;animation:lo-slide 1.1s ease-in-out infinite;}',
    '@keyframes lo-slide{0%{margin-left:0}50%{margin-left:64%}100%{margin-left:0}}',
    '.lo-ptext{margin-top:8px;font-size:13px;color:#93a4c4;line-height:1.45;font-variant-numeric:tabular-nums;',
    'overflow-wrap:anywhere;word-break:break-word;}',
    '.lo-group{background:#131c2e;border-radius:14px;border:1px solid #1d2942;overflow:hidden;}',
    '.lo-ghead{display:flex;align-items:center;flex-wrap:wrap;gap:6px;padding:12px 14px 8px;}',
    '.lo-gname{font-size:15px;font-weight:600;line-height:1.5;color:#e8eefc;min-width:0;',
    'overflow-wrap:anywhere;word-break:break-word;}',
    '.lo-tag{font-size:12px;line-height:1.45;color:#93a4c4;background:#1b2540;border:1px solid #2a3a5c;border-radius:8px;',
    'padding:2px 8px;max-width:100%;overflow-wrap:anywhere;word-break:break-word;}',
    '.lo-rows{display:flex;flex-direction:column;}',
    '.lo-row{display:flex;align-items:center;gap:10px;width:100%;min-height:64px;padding:8px 10px 8px 14px;',
    'background:none;border:0;border-top:1px solid #1a2338;color:#e8eefc;font-family:inherit;text-align:left;cursor:pointer;}',
    '.lo-row:active{background:#1a2540;}',
    '.lo-thumb{flex:none;width:44px;height:44px;border-radius:10px;overflow:hidden;background:#0b1220;',
    'display:flex;align-items:center;justify-content:center;border:1px solid #26324c;}',
    '.lo-thumb img{width:100%;height:100%;object-fit:cover;display:block;}',
    '.lo-nocover{font-size:12px;line-height:1.45;color:#93a4c4;}',
    '.lo-info{flex:1;min-width:0;display:flex;flex-direction:column;gap:2px;}',
    /* 文件名 / 说明：长名字就折行，不再单行 ellipsis。 */
    '.lo-name{font-size:15px;line-height:1.5;color:#e8eefc;white-space:normal;overflow:visible;text-overflow:clip;',
    'overflow-wrap:anywhere;word-break:break-word;}',
    '.lo-sub{font-size:13px;line-height:1.45;color:#93a4c4;white-space:normal;overflow:visible;text-overflow:clip;',
    'overflow-wrap:anywhere;word-break:break-word;}',
    '.lo-more{flex:none;width:44px;height:44px;display:inline-flex;align-items:center;justify-content:center;',
    'background:none;border:0;color:#93a4c4;font-size:20px;line-height:1;font-family:inherit;cursor:pointer;}',
    '.lo-more:active{background:#26324c;color:#5aa2ff;}',
    '.lo-empty{color:#93a4c4;font-size:15px;line-height:1.5;padding:14px;background:#131c2e;border-radius:14px;',
    'overflow-wrap:anywhere;word-break:break-word;}',
    '.lo-err{color:#ff6b6b;font-size:15px;line-height:1.5;padding:14px;background:#131c2e;border-radius:14px;',
    'overflow-wrap:anywhere;word-break:break-word;}',
    '.lo-loading{color:#93a4c4;font-size:15px;line-height:1.5;padding:20px 4px;text-align:center;}',
    '.lo-count{font-size:13px;line-height:1.45;color:#93a4c4;padding:0 4px;font-variant-numeric:tabular-nums;}',
    '/* 外壳把 body 设成 user-select:none；输入框必须能选中文本。 */',
    '.lo-search,.lo-input{user-select:text;-webkit-user-select:text;}'
  ].join('');

  function injectStyle() {
    if (document.getElementById('lo-style')) return;
    var style = document.createElement('style');
    style.id = 'lo-style';
    style.textContent = CSS;
    document.head.appendChild(style);
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
  function localFmtBytes(n) {
    var value = Number(n);
    if (!isFinite(value) || value < 0) return '—';
    if (value < 1024) return Math.round(value) + ' B';
    if (value < 1024 * 1024) return (value / 1024).toFixed(1) + ' KB';
    if (value < 1024 * 1024 * 1024) return (value / 1024 / 1024).toFixed(1) + ' MB';
    return (value / 1024 / 1024 / 1024).toFixed(2) + ' GB';
  }

  /* ───────────────────────── 屏幕本体 ───────────────────────── */

  function setup(P) {
    var el = typeof P.el === 'function' ? P.el : localEl;
    var clear = typeof P.clear === 'function' ? P.clear : localClear;
    var fmtBytes = typeof P.fmtBytes === 'function' ? P.fmtBytes : localFmtBytes;

    var state = {
      data: null,
      filter: '',
      mounted: false,
      seq: 0,
      inflight: null,
      progress: null,      // pollWhileVisible 的 stop()
      hideTimer: null,     // 下载结束后把进度块收起来用的定时器
      nodes: {}
    };

    /* ---------- DOM 骨架 ---------- */

    function build() {
      var wrap = el('div', 'lo-wrap');

      var note = el('div', 'lo-note');
      state.nodes.note = note;
      wrap.appendChild(note);

      var status = el('div', 'lo-status');
      state.nodes.status = status;
      wrap.appendChild(status);

      var progress = el('div', 'lo-progress');
      progress.hidden = true;
      var track = el('div', 'lo-track');
      var fill = el('div', 'lo-fill');
      track.appendChild(fill);
      progress.appendChild(track);
      var ptext = el('div', 'lo-ptext', '');
      progress.appendChild(ptext);
      state.nodes.progress = progress;
      state.nodes.fill = fill;
      state.nodes.ptext = ptext;
      wrap.appendChild(progress);

      // 搜索 + 刷新
      var toolbar = el('div', 'lo-toolbar');
      var search = el('input', 'lo-search');
      search.type = 'search';
      search.placeholder = '搜索名称 / 底模 / 栈';
      search.setAttribute('autocomplete', 'off');
      search.addEventListener('input', function () {
        state.filter = search.value.trim().toLowerCase();
        renderGroups();
      });
      var reload = el('button', 'lo-btn lo-ghost lo-reload', '刷新');
      reload.type = 'button';
      reload.addEventListener('click', function () { load(); });
      toolbar.appendChild(search);
      toolbar.appendChild(reload);
      wrap.appendChild(toolbar);
      state.nodes.search = search;
      state.nodes.reload = reload;

      // 下载 LoRA（折叠）
      wrap.appendChild(buildDownload());

      var count = el('div', 'lo-count');
      state.nodes.count = count;
      wrap.appendChild(count);

      var groups = el('div', 'lo-groups');
      state.nodes.groups = groups;
      wrap.appendChild(groups);

      return wrap;
    }

    function buildDownload() {
      var block = el('section', 'lo-block lo-collapsed');
      var head = el('button', 'lo-head');
      head.type = 'button';
      head.appendChild(el('span', null, '下载 LoRA'));
      head.appendChild(el('span', 'lo-chev', '⌄'));
      head.addEventListener('click', function () { block.classList.toggle('lo-collapsed'); });
      block.appendChild(head);

      var panel = el('div', 'lo-panel');
      var hint = el('div', 'lo-label', 'Civitai 模型页链接或直链；权重 0.1–1.5，默认 1.0。');
      panel.appendChild(hint);

      var urlField = el('div', 'lo-field');
      urlField.appendChild(el('span', 'lo-label', '链接'));
      var url = el('input', 'lo-input lo-url');
      url.type = 'text';
      url.setAttribute('autocomplete', 'off');
      url.placeholder = 'https://civitai.com/models/…';
      urlField.appendChild(url);
      panel.appendChild(urlField);

      var weightField = el('div', 'lo-field');
      weightField.appendChild(el('span', 'lo-label', '权重'));
      var weight = el('input', 'lo-input lo-weight');
      weight.type = 'number';
      weight.min = '0.1';
      weight.max = '1.5';
      weight.step = '0.05';
      weight.value = '1.0';
      weight.inputMode = 'decimal';
      weightField.appendChild(weight);
      panel.appendChild(weightField);

      var row = el('div', 'lo-toolbar');
      var go = el('button', 'lo-btn lo-download', '开始下载');
      go.type = 'button';
      go.addEventListener('click', function () { download(url, weight, go); });
      row.appendChild(go);
      panel.appendChild(row);

      block.appendChild(panel);
      state.nodes.dlBlock = block;
      state.nodes.dlUrl = url;
      state.nodes.dlWeight = weight;
      state.nodes.dlGo = go;
      return block;
    }

    /* ---------- 状态 ---------- */

    function message(error) {
      return (error && error.message) ? String(error.message) : String(error || '未知错误');
    }

    function showStatus(text, kind) {
      var box = state.nodes.status;
      clear(box);
      if (!text) { box.hidden = true; return; }
      box.hidden = false;
      box.appendChild(el('div', kind === 'error' ? 'lo-err' : 'lo-loading', text));
    }

    /* ---------- 读 /api/loras ---------- */

    function load() {
      if (state.inflight) return state.inflight;
      var seq = ++state.seq;
      showStatus('正在读取本机 LoRA…');
      state.inflight = P.api('/api/loras', { body: { scope: P.scope() } }).then(function (data) {
        state.inflight = null;
        if (seq !== state.seq) return data;
        state.data = data || {};
        showStatus('');
        renderGroups();
        // 进屏时后台可能正在下载（甚至刚刷新过页面）：接着把进度条挂上，不然进度就"看不见了"。
        var download = state.data.download;
        if (download && (download.busy || download.downloading)) watchProgress(false);
        return data;
      }, function (error) {
        state.inflight = null;
        if (seq !== state.seq) return null;
        state.data = null;
        showStatus('读取本机 LoRA 失败：' + message(error) + '（下拉可重试）', 'error');
        return null;
      });
      return state.inflight;
    }

    /* ---------- 分组（与后端 groups 口径一致） ---------- */

    function buckets() {
      var data = state.data || {};
      var items = data.loras || [];
      var byKey = {};
      (data.groups || []).forEach(function (group, index) {
        var key = String(group.key == null ? '' : group.key);
        byKey[key] = { group: group, order: index };
      });
      var order = [];
      var map = {};
      items.forEach(function (item) {
        var key = String(item.groupKey == null ? '' : item.groupKey);
        if (!map[key]) {
          var known = byKey[key];
          var group = (known && known.group) || {};
          var title = group.label || item.stackLabel
            || (item.baseModel ? '底模 ' + item.baseModel : '') || '未识别底模';
          map[key] = {
            key: key,
            order: known ? known.order : 100000,
            title: title,
            base: ((group.baseModels || []).filter(Boolean).join('、')) || item.baseModel || '',
            source: sourceLabel(group.stackSource || item.stackSource),
            items: []
          };
          order.push(map[key]);
        }
        map[key].items.push(item);
      });
      order.sort(function (left, right) {
        if (!!left.key !== !!right.key) return left.key ? -1 : 1;   // 判不出栈的排最后
        return left.order - right.order || left.title.localeCompare(right.title, 'zh');
      });
      return order;
    }

    function sourceLabel(source) {
      var value = String(source == null ? '' : source);
      var table = {
        'safetensors-header': 'safetensors 头部元数据',
        'safetensors-keys': 'safetensors 张量结构',
        'civitai': 'Civitai 记录',
        'forge-metadata': 'Forge 元数据',
        'forge-preset': 'Forge 预设配置',
        'preset-inferred': '按当前预设推断',
        'inferred': '按文件名/当前预设推断'
      };
      return table[value] || '';
    }

    function matches(item, filter) {
      if (!filter) return true;
      var hay = [item.name, item.alias, item.baseModel, item.baseModelLabel, item.stack, item.stackLabel]
        .map(function (value) { return String(value == null ? '' : value).toLowerCase(); }).join(' ');
      return hay.indexOf(filter) >= 0;
    }

    /* ---------- 渲染列表 ---------- */

    function renderGroups() {
      var box = state.nodes.groups;
      clear(box);
      var data = state.data;
      if (!data) { state.nodes.count.textContent = ''; return; }

      var directory = data.directory || '';
      var lines = [];
      if (directory) lines.push('LoRA 目录：' + directory);
      if (data.status) lines.push(String(data.status));
      state.nodes.note.textContent = lines.join('\n');

      // SD 没在跑：/api/loras 会带 error，但不能显示成"一个 LoRA 都没有"。
      if (data.error) {
        var failure = el('div', 'lo-err', '读取本机 LoRA 失败：' + data.error);
        box.appendChild(failure);
        state.nodes.count.textContent = data.status || '';
        return;
      }

      var items = data.loras || [];
      var matched = items.filter(function (item) { return matches(item, state.filter); });
      state.nodes.count.textContent = '共 ' + items.length + ' 个'
        + (state.filter ? '，匹配 ' + matched.length + ' 个' : '')
        + (data.status ? '' : '');

      if (!matched.length) {
        var text = items.length
          ? '没有匹配「' + state.filter + '」的 LoRA（本机共 ' + items.length + ' 个）。'
          : 'LoRA 目录：' + (directory || '（未配置）') + '（还没有 LoRA）';
        var empty = el('div', 'lo-empty', text);
        box.appendChild(empty);
        return;
      }

      // 查看器用的封面清单：只含有图的项，顺序与屏幕一致。
      var covers = [];
      buckets().forEach(function (bucket) {
        var rows = bucket.items.filter(function (item) { return matches(item, state.filter); });
        if (!rows.length) return;
        var groupBox = el('section', 'lo-group');
        var head = el('div', 'lo-ghead');
        head.appendChild(el('span', 'lo-gname', bucket.title + '（' + rows.length + '）'));
        if (bucket.base && bucket.base !== bucket.title) head.appendChild(el('span', 'lo-tag', '底模 ' + bucket.base));
        if (bucket.source) head.appendChild(el('span', 'lo-tag', bucket.source));
        groupBox.appendChild(head);

        var list = el('div', 'lo-rows');
        rows.forEach(function (item) {
          if (item.preview) covers.push({ src: coverUrl(item.name), caption: item.name });
          list.appendChild(row(item));
        });
        groupBox.appendChild(list);
        box.appendChild(groupBox);
      });
      state.covers = covers;
    }

    function row(item) {
      var button = el('button', 'lo-row');
      button.type = 'button';
      button.setAttribute('data-name', item.name);
      button.appendChild(thumb(item));

      var info = el('div', 'lo-info');
      info.appendChild(el('div', 'lo-name', item.name));
      info.appendChild(el('div', 'lo-sub', baseText(item)));
      var size = item.size || item.sizeBytes || item.bytes;
      if (size) info.appendChild(el('div', 'lo-sub', P.fmtBytes ? fmtBytes(size) : String(size)));
      button.appendChild(info);

      var more = el('span', 'lo-more', '⋯');
      button.appendChild(more);

      button.addEventListener('click', function () { actions(item); });
      return button;
    }

    function baseText(item) {
      var stack = item.stackLabel || '';
      var base = item.baseModelLabel || (item.baseModel ? '底模 ' + item.baseModel : '');
      if (stack && base) return stack + ' · ' + base;
      return stack || base || '底模未识别';
    }

    /**
     * LoRA 展示图地址。
     *
     * <p>/api/lora/preview 只认查询串（见 WebApiController.loraNameQuery），令牌也挂在查询串上
     * （和桌面版 loraPreviewUrl 同款）。这里**不能**过 PixikoM.imageUrl：那一支只把
     * `data/generated/…` 形态的相对路径拼成 `/api/image?path=…`，遇到 `/api/...` 会当成相对路径错拼。
     * 用绝对地址还顺带保证了查看器里那同一份地址能原样直通（imageUrl 对 http(s): 是直通的）。
     */
    function coverUrl(name) {
      var token = typeof P.token === 'function' ? P.token() : '';
      return location.origin + '/api/lora/preview?name=' + encodeURIComponent(name)
        + (token ? '&token=' + encodeURIComponent(token) : '');
    }

    function thumb(item) {
      var box = el('div', 'lo-thumb');
      if (!item.preview) {
        box.appendChild(el('span', 'lo-nocover', '无图'));
        return box;
      }
      var image = document.createElement('img');
      image.alt = '';
      image.loading = 'lazy';
      image.src = coverUrl(item.name);
      image.addEventListener('error', function () {
        clear(box);
        box.appendChild(el('span', 'lo-nocover', '无图'));
      });
      box.appendChild(image);
      return box;
    }

    /* ---------- 行动作：底部 sheet，五个动作 ────────── */

    function actions(item) {
      var items = [
        { text: '查看封面', onSelect: function () { viewCover(item); } },
        { text: '补抓封面', onSelect: function () { cover(item); } },
        { text: '改名', onSelect: function () { rename(item); } },
        { text: '删除', danger: true, onSelect: function () { remove(item); } },
        { text: '复制名字', onSelect: function () { copyName(item); } }
      ];
      P.sheet({ title: item.name, items: items });
    }

    /** 查看封面：把本屏所有有图的 LoRA 一起交给查看器（左右翻页就是在封面之间翻）。 */
    function viewCover(item) {
      if (!item.preview) { P.toast('这个 LoRA 还没有展示图，可以先「补抓封面」'); return; }
      var entry = { src: coverUrl(item.name), caption: item.name };
      var list = (state.covers || []).slice();
      var index = list.map(function (row) { return row.src; }).indexOf(entry.src);
      if (index < 0) { list = [entry]; index = 0; }
      P.openViewer(list, index);
    }

    /** 补抓展示图：接口只负责启动，进度靠 /api/lora/progress。 */
    function cover(item) {
      P.api('/api/lora/cover', { body: { name: item.name, scope: P.scope() } }).then(function (started) {
        if (started && started.quest) P.toast('任务 #' + started.quest + ' 已下达');
        watchProgress(true);
      }, function (error) {
        P.toast(message(error));
      });
    }

    /** 改名：先把新名字问出来，再发桌面同款指令（会同步个人 prompt 里的 LoRA 标签）。 */
    function rename(item) {
      P.prompt({
        title: '重命名 LoRA',
        value: item.name,
        placeholder: '新的 LoRA 名字（不含扩展名）',
        ok: '改名'
      }).then(function (next) {
        var value = String(next == null ? '' : next).trim();
        if (!value || value === item.name) return;
        var command = '.lora rename "' + item.name + '" "' + value + '"';
        P.api('/api/command', { body: { command: command, scope: P.scope() } }).then(function () {
          P.toast('已下达改名：' + item.name + ' → ' + value);
          load();
        }, function (error) { P.toast(message(error)); });
      });
    }

    /** 删除：必须过一道 confirm（默认焦点不在"删除"上），确认后才真的发指令。 */
    function remove(item) {
      P.confirm({
        title: '删除 LoRA',
        text: '从磁盘删除「' + item.name + '」？文件会被真的删掉，无法撤销。',
        ok: '删除',
        danger: true
      }).then(function (yes) {
        if (!yes) return false;
        var target = item.number ? '#' + item.number : '"' + item.name + '"';
        var command = '.lora delete ' + target;
        return P.api('/api/command', { body: { command: command, scope: P.scope() } }).then(function () {
          P.toast('已下达删除：' + item.name);
          load();
          return true;
        }, function (error) {
          P.toast(message(error));
          return false;
        });
      });
    }

    function copyName(item) {
      var text = String(item.name || '');
      var done = function () { P.toast('已复制名字'); };
      var fail = function () { P.toast('复制失败，请长按列表项手动复制'); };
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text).then(done, fail);
          return;
        }
      } catch (error) { /* 落到下面的兜底 */ }
      try {
        var area = document.createElement('textarea');
        area.value = text;
        area.setAttribute('readonly', 'readonly');
        area.style.position = 'fixed';
        area.style.opacity = '0';
        document.body.appendChild(area);
        area.select();
        var ok = document.execCommand('copy');
        document.body.removeChild(area);
        if (ok) done(); else fail();
      } catch (error) { fail(); }
    }

    /* ---------- 下载 + 进度 ---------- */

    function download(urlNode, weightNode, button) {
      var url = String(urlNode.value || '').trim();
      if (!url) { P.toast('请填写 Civitai 链接'); return; }
      var weight = Number(weightNode.value);
      if (!isFinite(weight) || weight <= 0) weight = 1.0;
      weight = Math.min(1.5, Math.max(0.1, weight));      // 界面上就按 0.1–1.5 夹住
      weightNode.value = String(weight);
      button.disabled = true;
      P.api('/api/lora/download', { body: { url: url, weight: weight, scope: P.scope() } }).then(function (started) {
        button.disabled = false;
        if (started && started.quest) P.toast('任务 #' + started.quest + ' 已下达');
        watchProgress(true);
      }, function (error) {
        button.disabled = false;
        P.toast(message(error));
      });
    }

    /**
     * 轮询 /api/lora/progress 画进度：有 metered/percent 就画百分比，没有（正在读模型信息/正在加载）
     * 就退回不确定态——不能显示成 0%，那会让人以为卡死了。
     */
    function watchProgress(force) {
      if (state.progress) { if (!force) return; state.progress(); state.progress = null; }
      var misses = 0, grace = 0, seenRunning = false;
      state.progress = P.pollWhileVisible(function () {
        return P.api('/api/lora/progress', { body: { scope: P.scope() } }).then(function (data) {
          misses = 0;
          var running = !!(data && (data.busy || data.downloading));
          if (running) { seenRunning = true; grace = 0; }
          else if (!seenRunning && ++grace <= 20) return data;   // 空窗期：先不画也不收手
          paint(data, running);
          if (!running) {
            if (state.progress) { state.progress(); state.progress = null; }
            load();
          }
          return data;
        }, function () {
          if (++misses >= 3 && state.progress) { state.progress(); state.progress = null; }
          return null;
        });
      }, 1000);
    }

    function paint(data, running) {
      var box = state.nodes.progress;
      if (state.hideTimer) { clearTimeout(state.hideTimer); state.hideTimer = null; }
      var hasPercent = data && typeof data.percent === 'number' && isFinite(data.percent);
      box.hidden = false;
      if (hasPercent) {
        box.classList.remove('lo-indeterminate');
        state.nodes.fill.style.width = Math.max(0, Math.min(100, data.percent)).toFixed(1) + '%';
      } else {
        box.classList.add('lo-indeterminate');
        state.nodes.fill.style.width = '';
      }
      var line = '';
      if (hasPercent) {
        line = data.percent.toFixed(1) + '%';
        if (data.done && data.total) line += '（' + fmtBytes(data.done) + ' / ' + fmtBytes(data.total) + '）';
      }
      var stage = String((data && (data.stage || data.status)) || (running ? '正在处理…' : '已结束')).trim();
      var first = stage.split('\n')[0].trim();
      state.nodes.ptext.textContent = (line ? line + ' · ' : '') + first;
      // 结束后让最后一行（"下载成功…"）停一会儿再收起来，别一闪而过（和桌面版同一套脾气）。
      if (!running) {
        state.hideTimer = setTimeout(function () {
          state.hideTimer = null;
          box.hidden = true;
          box.classList.remove('lo-indeterminate');
        }, 6000);
      }
    }

    /* ---------- 契约入口 ---------- */

    /**
     * 深度链接兜底（核心的一个坑，交付报告里点了名）。
     *
     * app.js 虽然是 defer，但它在 IIFE 末尾就 boot()（defer 脚本执行时 readyState 已经是
     * 'interactive'，"loading" 判断不成立，所以 boot 立刻跑），**比 screen-*.js 的 register() 早**。
     * 于是 applyRoute() 先给 #/loras 画了一张「还没做好」占位并把 .active 挂在占位上；等这一屏
     * register() 进来时，核心只 ensureMounted() 建了真屏，既没给真屏 .active、也没摘掉那个占位 ——
     * 真屏元素在、数据也拉了，却一直 display:none（尺寸为 0，用户看不见）。
     *
     * 这里只在"当前路由就是这一屏、而这一屏没 active"时才动手；核心补好之后它自动变成空操作。
     */
    function takeOverRoute(root) {
      if (typeof P.current !== 'function' || P.current() !== 'loras') return;
      if (!root || root.classList.contains('active')) return;
      var stale = document.querySelector('#m-main .screen.active');
      if (stale && stale !== root) stale.classList.remove('active');
      root.classList.add('active');
    }

    return {
      title: 'LoRA',
      mount: function (root) {
        injectStyle();
        state.mounted = true;
        state.seq++;
        state.inflight = null;
        state.filter = '';
        if (state.hideTimer) { clearTimeout(state.hideTimer); state.hideTimer = null; }
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
    P.register('loras', setup(P));
    return true;
  }

  if (!boot()) {
    var tries = 0;
    var timer = setInterval(function () {
      if (boot() || ++tries > 120) clearInterval(timer);
    }, 50);
  }
})();
