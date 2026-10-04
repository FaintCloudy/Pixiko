/*
 * Pixiko 手机端 Web UI ·「提示词集」与「参数预设」（M4）。
 *
 * 注册 id：'functions'、'presets'。两屏接口完全以 Java 为准（`WebApiController` 的
 * `/api/functions`、`/api/functions/edit`、`/api/presets`、`/api/presets/edit` 分支，
 * 与 `Bot#webFunctions/webFunctionsEdit/webPresets/webPresetsEdit` 的字段名逐个核对）：
 *
 *   POST /api/functions        body {scope}                → {functions:[{name,active,positive,negative}], active:[…]}
 *   POST /api/functions/edit   body {action,name,newName,overwrite,scope}
 *                              action ∈ save|overwrite|load|remove|clear|reset|rename|delete
 *   POST /api/presets          body {}                     → {presets:[{name,preset:{…}}], current:{…}}
 *   POST /api/presets/edit     body {action,name,overwrite}
 *                              action ∈ save|overwrite|remove|delete（**Java 没有 load**）
 *
 * 「参数预设 · 载入」会改 SD 参数，Java 里只能走指令通道（桌面版同样如此，见 app.js 第 4134 行
 * `runCommands(['.preset load ' + item.name])`），所以这里也发 `POST /api/command`。
 * 预设对象字段来自 `GenerationPreset#json()`：sampler_name / width / height / steps / cfg_scale / seed / checkpoint。
 *
 * 本文件不创建任何全局变量；样式只注入自己 .fn-* 前缀的那一份。
 */
(function () {
  'use strict';

  var STYLE_ID = 'fn-style';

  /**
   * 复选框的**视觉**用 22px 的 SVG 画（不依赖浏览器原生外观），
   * 元素本身留成 44×44 的可点区 —— 规格要求「开关/复选框可点区 ≥44×44」。
   * 样式令牌都带兜底值，另一份 app.css 里定义了同名令牌时会自动接管。
   */
  var CHECK_OFF = "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Crect x='2' y='2' width='20' height='20' rx='6' fill='%230e1626' stroke='%2393a4c4' stroke-opacity='0.55' stroke-width='2'/%3E%3C/svg%3E\")";
  var CHECK_ON = "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Crect x='2' y='2' width='20' height='20' rx='6' fill='%235aa2ff'/%3E%3Cpath d='M7 12.4l3.3 3.3L17 8.6' fill='none' stroke='%2308101f' stroke-width='2.6' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\")";

  function injectStyle() {
    if (document.getElementById(STYLE_ID)) return;
    var style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = [
      '.fn-wrap{display:flex;flex-direction:column;gap:var(--m-gap,12px);padding:12px 12px 28px;box-sizing:border-box}',
      '.fn-card{background:#131c2e;border-radius:var(--m-radius,14px);padding:var(--m-pad,14px)}',
      '.fn-head{display:flex;align-items:center;gap:10px;margin-bottom:10px}',
      // 标题不折行（360px 下「参数预设」曾被挤成「参数预 / 设」两行）；让右侧提示行去折。
      '.fn-head b{font-size:var(--m-fs-title,15px);color:#e8eefc;font-weight:600;flex:0 0 auto;white-space:nowrap}',
      '.fn-head .fn-hint{font-size:var(--m-fs-sm,13px);color:#93a4c4;margin-left:auto;text-align:right;',
      'flex:1 1 auto;min-width:0;line-height:var(--m-lh-sm,1.45)}',
      '.fn-field{display:flex;flex-direction:column;gap:6px;margin-bottom:10px}',
      '.fn-field>span{font-size:var(--m-fs-sm,13px);color:#93a4c4;line-height:var(--m-lh-sm,1.45)}',
      '.fn-input,.fn-select,.fn-area{width:100%;box-sizing:border-box;min-height:44px;padding:10px 12px;border-radius:12px;',
      'border:1px solid rgba(147,164,196,.24);background:#0e1626;color:#e8eefc;font-size:16px;font-family:inherit}',
      '.fn-input:focus,.fn-select:focus,.fn-area:focus{outline:none;border-color:#5aa2ff}',
      '.fn-area{min-height:88px;resize:vertical;line-height:var(--m-lh-body,1.5)}',
      '.fn-check{display:flex;align-items:center;gap:9px;min-height:44px;font-size:var(--m-fs-body,15px);color:#e8eefc;cursor:pointer}',
      '.fn-check input{-webkit-appearance:none;appearance:none;flex:0 0 44px;width:44px;height:44px;margin:0;padding:0;border:0;',
      'background-color:transparent;background-repeat:no-repeat;background-position:center;background-size:22px 22px;',
      'background-image:' + CHECK_OFF + ';cursor:pointer}',
      '.fn-check input:checked{background-image:' + CHECK_ON + '}',
      '.fn-check input:focus-visible{outline:2px solid #5aa2ff;outline-offset:2px;border-radius:12px}',
      '.fn-row{display:flex;gap:10px;flex-wrap:wrap}',
      '.fn-row>.fn-input{flex:1 1 150px}',
      '.fn-btn{min-height:44px;padding:0 16px;border-radius:12px;border:0;background:#5aa2ff;color:#08101f;',
      'font-size:var(--m-fs-body,15px);font-weight:600;font-family:inherit;-webkit-tap-highlight-color:transparent}',
      '.fn-btn:active{opacity:.78}',
      '.fn-btn.ghost{background:rgba(90,162,255,.14);color:#5aa2ff}',
      '.fn-btn.danger{background:rgba(255,107,107,.16);color:#ff6b6b}',
      '.fn-btn[disabled]{opacity:.5}',
      '.fn-list{display:flex;flex-direction:column}',
      '.fn-item{display:flex;align-items:center;gap:12px;width:100%;box-sizing:border-box;min-height:56px;padding:11px 2px;',
      'background:transparent;border:0;border-top:1px solid rgba(147,164,196,.13);color:#e8eefc;',
      'font-size:var(--m-fs-body,15px);text-align:left;font-family:inherit;-webkit-tap-highlight-color:transparent}',
      '.fn-item:first-child{border-top:0}',
      '.fn-item:active{background:rgba(90,162,255,.12)}',
      '.fn-item-main{flex:1 1 auto;min-width:0}',
      '.fn-item-name{display:flex;align-items:center;gap:6px;font-size:var(--m-fs-body,15px);font-weight:600;line-height:1.45;',
      'overflow-wrap:anywhere}',
      // 预设摘要（尺寸/采样/步数/CFG/种子/模型名+hash）不再用 -webkit-line-clamp + ellipsis：
      // 一律完整折行显示，长 token（模型名、hash）用 overflow-wrap:anywhere 断行。
      '.fn-item-sub{font-size:var(--m-fs-sm,13px);color:#93a4c4;margin-top:3px;line-height:var(--m-lh-sm,1.45);',
      'white-space:normal;overflow:visible;text-overflow:clip;overflow-wrap:anywhere;word-break:normal}',
      '.fn-star{color:#5aa2ff;font-size:var(--m-fs-xs,12px);flex:0 0 auto}',
      '.fn-chev{flex:0 0 18px;width:18px;height:18px;stroke:#93a4c4;fill:none;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}',
      '.fn-state{font-size:14px;color:#93a4c4;padding:12px 2px;line-height:var(--m-lh-body,1.5);overflow-wrap:anywhere}',
      '.fn-state.bad{color:#ff6b6b}',
      '.fn-note{font-size:var(--m-fs-sm,13px);color:#93a4c4;margin-top:10px;line-height:var(--m-lh-sm,1.45);overflow-wrap:anywhere}',
      '.fn-applied{font-size:var(--m-fs-body,15px);color:#dfe8fa;line-height:1.55;font-variant-numeric:tabular-nums;',
      'white-space:normal;overflow:visible;text-overflow:clip;overflow-wrap:anywhere;word-break:normal}',
      '.fn-applied b{color:#e8eefc;font-weight:600}'
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
      if (tries > 400) { console.warn('[functions] PixikoM 未就绪，屏幕未注册'); return; }
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

  function messageOf(error) {
    if (!error) return '未知错误';
    return String(error.message || error);
  }

  function scopeOf() {
    var M = api();
    try { return M && M.scope ? String(M.scope() || '') : ''; } catch (error) { return ''; }
  }

  function chevron() {
    var span = document.createElement('span');
    span.innerHTML = '<svg class="fn-chev" viewBox="0 0 24 24" aria-hidden="true"><path d="M9 5l7 7-7 7"/></svg>';
    return span.firstChild;
  }

  function button(cls, text, id) {
    var node = make('button', cls, text);
    node.type = 'button';
    if (id) node.id = id;
    return node;
  }

  function field(label, input) {
    var box = make('label', 'fn-field');
    box.appendChild(make('span', null, label));
    box.appendChild(input);
    return box;
  }

  function textInput(id, placeholder, value) {
    var input = make('input', 'fn-input');
    input.type = 'text';
    input.id = id;
    if (placeholder) input.placeholder = placeholder;
    if (value !== undefined) input.value = value;
    return input;
  }

  function checkRow(id, label, checked) {
    var wrap = make('label', 'fn-check');
    var box = make('input');
    box.type = 'checkbox';
    box.id = id;
    box.checked = !!checked;
    wrap.appendChild(box);
    wrap.appendChild(make('span', null, label));
    return wrap;
  }

  function info(title, text, note) {
    var M = api();
    if (M && typeof M.confirm === 'function') {
      return M.confirm({ title: title, text: note ? (text + '\n\n' + note) : text, ok: '知道了' });
    }
    toast(text);
    return Promise.resolve(true);
  }

  function confirmBox(title, text, danger) {
    var M = api();
    if (M && typeof M.confirm === 'function') return M.confirm({ title: title, text: text, ok: danger ? '删除' : '确定', danger: !!danger });
    return Promise.resolve(window.confirm(text));
  }

  function promptBox(title, value, placeholder, ok) {
    var M = api();
    if (M && typeof M.prompt === 'function') return M.prompt({ title: title, value: value, placeholder: placeholder, ok: ok });
    return Promise.resolve(window.prompt(title, value));
  }

  function sheet(title, items) {
    var M = api();
    if (M && typeof M.sheet === 'function') { M.sheet({ title: title, items: items }); return; }
    toast('这一台设备上还不能弹出操作菜单。');
  }

  /**
   * 指令通道（只在「参数预设 · 载入」用得上）：POST /api/command，
   * 返回值与桌面版 `runCommands` 读的是同一条回执（texts / images / quest / done）。
   */
  function runCommand(command) {
    var M = api();
    var body = { command: command, scope: scopeOf() };
    return M.api('/api/command', { method: 'POST', body: body }).then(function (capture) {
      var result = capture || {};
      if (result.done || !result.id) return result;
      // 指令是异步跑的：短轮询几次把回执文字取回来（预设加载很快，通常在第一次就齐了）。
      var tries = 0;
      return new Promise(function (resolve) {
        (function poll() {
          tries += 1;
          if (tries > 6) { resolve(result); return; }
          setTimeout(function () {
            M.api('/api/capture', { method: 'POST', body: { id: result.id } }).then(function (next) {
              result = next || result;
              if (result.done && (result.texts || []).length) resolve(result);
              else poll();
            }, function () { resolve(result); });
          }, 400);
        })();
      });
    });
  }

  function textsOf(capture) {
    if (!capture) return '';
    var texts = capture.texts || [];
    return texts.filter(function (line) { return String(line || '').trim(); }).join('\n');
  }

  /* ---------------------------------------------------------------- 提示词集 */

  var fnRoot = null;
  var fnData = null;

  function renderFunctions(target) {
    fnRoot = target || fnRoot;
    if (!fnRoot) return;
    clear(fnRoot);
    var wrap = make('div', 'fn-wrap');
    wrap.appendChild(functionsForm());
    wrap.appendChild(functionsListCard());
    fnRoot.appendChild(wrap);
    loadFunctions();
  }

  function functionsForm() {
    var card = make('div', 'fn-card');
    var head = make('div', 'fn-head');
    head.appendChild(make('b', null, '提示词集'));
    head.appendChild(make('span', 'fn-hint', '定义共享，加载关联按个人'));
    card.appendChild(head);

    card.appendChild(field('名称', textInput('fn-name', '例如：夜景人像')));
    card.appendChild(checkRow('fn-overwrite', '同名时覆盖（定义共享，所有人可见）', false));

    var row = make('div', 'fn-row');
    var save = button('fn-btn', '保存当前', 'fn-save');
    save.addEventListener('click', function () {
      var name = String((document.getElementById('fn-name') || {}).value || '').trim();
      if (!name) { toast('请填写提示词集名称。'); return; }
      var overwrite = !!(document.getElementById('fn-overwrite') || {}).checked;
      editFunctions('save', name, { overwrite: overwrite });
    });
    var more = button('fn-btn ghost', '更多操作', 'fn-more');
    more.addEventListener('click', function () {
      sheet('提示词集', [
        { text: '移出全部已加载（clear）', onSelect: function () { editFunctions('clear', ''); } },
        { text: '仅清除关联，不动当前提示词（reset）', onSelect: function () { editFunctions('reset', ''); } },
        { text: '刷新列表', onSelect: function () { loadFunctions(); } }
      ]);
    });
    var reload = button('fn-btn ghost', '刷新', 'fn-reload');
    reload.addEventListener('click', function () { loadFunctions(); });
    row.appendChild(save);
    row.appendChild(more);
    row.appendChild(reload);
    card.appendChild(row);

    var active = make('div', 'fn-note', '');
    active.id = 'fn-active';
    card.appendChild(active);
    return card;
  }

  function functionsListCard() {
    var card = make('div', 'fn-card');
    var head = make('div', 'fn-head');
    head.appendChild(make('b', null, '已保存的提示词集'));
    head.appendChild(make('span', 'fn-hint', '点一行查看操作'));
    card.appendChild(head);
    var list = make('div', 'fn-list');
    list.id = 'fn-list';
    list.appendChild(make('div', 'fn-state', '正在读取…'));
    card.appendChild(list);
    return card;
  }

  function loadFunctions() {
    return api().api('/api/functions', { method: 'POST', body: { scope: scopeOf() } })
      .then(function (data) { applyFunctions(data); })
      .catch(function (error) { functionsError(messageOf(error)); });
  }

  function functionsError(message) {
    var list = document.getElementById('fn-list');
    if (!list) return;
    clear(list);
    list.appendChild(make('div', 'fn-state bad', '读取失败：' + message));
    var retry = button('fn-btn ghost', '重试');
    retry.addEventListener('click', function () { loadFunctions(); });
    list.appendChild(retry);
  }

  /** 编辑接口的返回本身就是一份新的列表，直接用它局部刷新（失败才重新拉）。 */
  function applyFunctions(data) {
    fnData = data || {};
    var list = document.getElementById('fn-list');
    var activeLine = document.getElementById('fn-active');
    var active = fnData.active || [];
    if (activeLine) activeLine.textContent = '已加载：' + (active.length ? active.join('、') : '（无）');
    if (!list) return;
    clear(list);
    var items = fnData.functions || [];
    if (!items.length) {
      list.appendChild(make('div', 'fn-state', '还没有保存过提示词集：填个名称，把当前正向/反向提示词存起来。'));
      return;
    }
    items.forEach(function (item) {
      var row = button('fn-item');
      row.setAttribute('data-fn-name', String(item.name || ''));
      if (item.active) row.setAttribute('data-fn-active', '1');
      var main = make('div', 'fn-item-main');
      var nameLine = make('div', 'fn-item-name');
      nameLine.appendChild(make('span', null, String(item.name || '（无名）')));
      if (item.active) nameLine.appendChild(make('span', 'fn-star', '★ 已加载'));
      main.appendChild(nameLine);
      var positive = String(item.positive || '').trim();
      main.appendChild(make('div', 'fn-item-sub', positive ? positive.slice(0, 120) : '（正向为空）'));
      row.appendChild(main);
      row.appendChild(chevron());
      row.addEventListener('click', function () { functionsSheet(item); });
      list.appendChild(row);
    });
  }

  function functionsSheet(item) {
    var name = String(item.name || '');
    var active = !!item.active;
    sheet('提示词集：' + name, [
      {
        text: active ? '移出已加载的词条' : '加载到我的提示词',
        onSelect: function () { editFunctions(active ? 'remove' : 'load', name); }
      },
      {
        text: '用当前提示词覆盖保存',
        onSelect: function () {
          confirmBox('覆盖提示词集', '用当前正向/反向提示词覆盖「' + name + '」？定义是共享的，其他人加载它时也会拿到新内容。', false)
            .then(function (ok) { if (ok) editFunctions('overwrite', name, { overwrite: true }); });
        }
      },
      {
        text: '查看内容',
        onSelect: function () {
          info('提示词集：' + name,
            '正向：\n' + (String(item.positive || '').trim() || '（空）') + '\n\n反向：\n' + (String(item.negative || '').trim() || '（空）'));
        }
      },
      {
        text: '改名',
        onSelect: function () {
          promptBox('提示词集改名', name, '新名称', '改名').then(function (next) {
            var target = String(next || '').trim();
            if (!target || target === name) return;
            editFunctions('rename', name, { newName: target });
          });
        }
      },
      {
        text: '删除定义',
        danger: true,
        onSelect: function () {
          confirmBox('删除提示词集', '删除「' + name + '」的定义？已加载的词条会先从这个会话移出，删除后无法恢复。', true)
            .then(function (ok) { if (ok) editFunctions('delete', name); });
        }
      }
    ]);
  }

  function editFunctions(action, name, extra) {
    var body = { action: action, name: name || '', scope: scopeOf() };
    if (extra) { if (extra.newName !== undefined) body.newName = extra.newName; if (extra.overwrite !== undefined) body.overwrite = !!extra.overwrite; }
    return api().api('/api/functions/edit', { method: 'POST', body: body })
      .then(function (data) {
        applyFunctions(data);
        var text = String((data && data.message) || '').trim();
        if (text) toast(text.split('\n')[0]);
        return data;
      })
      .catch(function (error) {
        toast('操作失败：' + messageOf(error));
        loadFunctions();
        return null;
      });
  }

  /* ---------------------------------------------------------------- 参数预设 */

  var psRoot = null;

  function renderPresets(target) {
    psRoot = target || psRoot;
    if (!psRoot) return;
    clear(psRoot);
    var wrap = make('div', 'fn-wrap');
    wrap.appendChild(presetsForm());
    wrap.appendChild(presetsCurrentCard());
    wrap.appendChild(presetsListCard());
    psRoot.appendChild(wrap);
    loadPresets();
  }

  function presetsForm() {
    var card = make('div', 'fn-card');
    var head = make('div', 'fn-head');
    head.appendChild(make('b', null, '参数预设'));
    head.appendChild(make('span', 'fn-hint', '保存当前的尺寸/采样/步数/CFG/种子'));
    card.appendChild(head);
    card.appendChild(field('名称', textInput('ps-name', '例如：横构图 1024')));
    card.appendChild(checkRow('ps-overwrite', '同名时覆盖', false));
    var row = make('div', 'fn-row');
    var save = button('fn-btn', '保存当前', 'ps-save');
    save.addEventListener('click', function () {
      var name = String((document.getElementById('ps-name') || {}).value || '').trim();
      if (!name) { toast('请填写预设名称。'); return; }
      var overwrite = !!(document.getElementById('ps-overwrite') || {}).checked;
      editPresets('save', name, overwrite);
    });
    var reload = button('fn-btn ghost', '刷新', 'ps-reload');
    reload.addEventListener('click', function () { loadPresets(); });
    row.appendChild(save);
    row.appendChild(reload);
    card.appendChild(row);
    card.appendChild(make('div', 'fn-note', '当前参数由「系统」屏的生成参数卡维护；保存 = 把机器人此刻真正在用的那份存下来。'));
    return card;
  }

  function presetsCurrentCard() {
    var card = make('div', 'fn-card');
    var head = make('div', 'fn-head');
    head.appendChild(make('b', null, '当前生效参数'));
    card.appendChild(head);
    var box = make('div', 'fn-applied', '正在读取…');
    box.id = 'ps-current';
    card.appendChild(box);
    return card;
  }

  function presetsListCard() {
    var card = make('div', 'fn-card');
    var head = make('div', 'fn-head');
    head.appendChild(make('b', null, '已保存的预设'));
    head.appendChild(make('span', 'fn-hint', '点一行查看操作'));
    card.appendChild(head);
    var list = make('div', 'fn-list');
    list.id = 'ps-list';
    list.appendChild(make('div', 'fn-state', '正在读取…'));
    card.appendChild(list);
    return card;
  }

  function loadPresets() {
    return api().api('/api/presets', { method: 'POST', body: {} })
      .then(function (data) { applyPresets(data); })
      .catch(function (error) {
        var list = document.getElementById('ps-list');
        if (list) {
          clear(list);
          list.appendChild(make('div', 'fn-state bad', '读取失败：' + messageOf(error)));
          var retry = button('fn-btn ghost', '重试');
          retry.addEventListener('click', function () { loadPresets(); });
          list.appendChild(retry);
        }
        var current = document.getElementById('ps-current');
        if (current) current.textContent = '读取失败：' + messageOf(error);
      });
  }

  function presetSummary(preset) {
    var p = preset || {};
    var parts = [];
    if (p.width || p.height) parts.push((p.width || '?') + ' × ' + (p.height || '?'));
    if (p.sampler_name) parts.push(String(p.sampler_name));
    if (p.steps !== undefined && p.steps !== null) parts.push('步数 ' + p.steps);
    if (p.cfg_scale !== undefined && p.cfg_scale !== null) parts.push('CFG ' + p.cfg_scale);
    if (p.seed !== undefined && p.seed !== null) parts.push('种子 ' + p.seed);
    parts.push('模型 ' + (p.checkpoint ? String(p.checkpoint) : '跟随 WebUI'));
    return parts.join(' · ');
  }

  function applyPresets(data) {
    var list = document.getElementById('ps-list');
    var current = document.getElementById('ps-current');
    var currentData = (data && data.current) || {};
    if (current) {
      current.textContent = presetSummary({
        width: currentData.width, height: currentData.height, sampler_name: currentData.sampler,
        steps: currentData.steps, cfg_scale: currentData.cfg, seed: currentData.seed, checkpoint: currentData.model
      });
    }
    if (!list) return;
    clear(list);
    var items = (data && data.presets) || [];
    if (!items.length) {
      list.appendChild(make('div', 'fn-state', '还没有保存过参数预设。'));
      return;
    }
    items.forEach(function (item) {
      var row = button('fn-item');
      row.setAttribute('data-ps-name', String(item.name || ''));
      var main = make('div', 'fn-item-main');
      main.appendChild(make('div', 'fn-item-name'));
      main.firstChild.appendChild(make('span', null, String(item.name || '（无名）')));
      main.appendChild(make('div', 'fn-item-sub', presetSummary(item.preset)));
      row.appendChild(main);
      row.appendChild(chevron());
      row.addEventListener('click', function () { presetsSheet(item); });
      list.appendChild(row);
    });
  }

  function presetsSheet(item) {
    var name = String(item.name || '');
    sheet('参数预设：' + name, [
      {
        text: '载入（改当前生成参数）',
        onSelect: function () {
          confirmBox('载入参数预设', '把「' + name + '」的尺寸、采样方法、步数、CFG、种子与基础模型采纳为当前参数？只影响之后提交的生成任务。', false)
            .then(function (ok) {
              if (!ok) return;
              runCommand('.preset load ' + name).then(function (capture) {
                var text = textsOf(capture);
                toast(text ? text.split('\n')[0] : '已下达载入指令。');
                if (text) info('载入结果', text);
                loadPresets();
              }).catch(function (error) { toast('载入失败：' + messageOf(error)); });
            });
        }
      },
      {
        text: '用当前参数覆盖保存',
        onSelect: function () {
          confirmBox('覆盖参数预设', '用当前参数覆盖「' + name + '」？', false)
            .then(function (ok) { if (ok) editPresets('overwrite', name, true); });
        }
      },
      {
        text: '查看内容',
        onSelect: function () { info('参数预设：' + name, presetSummary(item.preset)); }
      },
      {
        text: '删除',
        danger: true,
        onSelect: function () {
          confirmBox('删除参数预设', '删除「' + name + '」？删除后无法恢复。', true)
            .then(function (ok) { if (ok) editPresets('delete', name, false); });
        }
      }
    ]);
  }

  function editPresets(action, name, overwrite) {
    return api().api('/api/presets/edit', { method: 'POST', body: { action: action, name: String(name || '').trim(), overwrite: !!overwrite } })
      .then(function (data) {
        applyPresets(data);
        var text = String((data && data.message) || '').trim();
        if (text) toast(text.split('\n')[0]);
        return data;
      })
      .catch(function (error) {
        toast('操作失败：' + messageOf(error));
        loadPresets();
        return null;
      });
  }

  /** 深链兜底：boot() 在 defer 脚本里就跑了，直开 #/functions 会先落在「还没做好」占位上。 */
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
    M.register('functions', {
      title: '提示词集',
      mount: function (target) { renderFunctions(target); },
      refresh: function (target) { renderFunctions(target || fnRoot); }
    });
    M.register('presets', {
      title: '参数预设',
      mount: function (target) { renderPresets(target); },
      refresh: function (target) { renderPresets(target || psRoot); }
    });
    claimRoute('functions');
    claimRoute('presets');
  });
})();
