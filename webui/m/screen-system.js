/*
 * Pixiko 手机端 Web UI ·「系统」「聊天设置」「首次配置」「服务器信息」（M4）。
 *
 * 注册 id：'system'、'chatcfg'、'setup'、'server'。字段名逐个核对过：
 *
 *   POST /api/status            → Bot#webStatus()：botName / scope / webPort / path / time /
 *                                 chat{global,frequency,contextSeconds,personalityChars,personality} /
 *                                 chatChannel{model,thinking} / imageChannel{model,thinking} /
 *                                 affinity{score,tier} / mood{mood,intensity} /
 *                                 generation{sampler,width,height,source,model,steps,cfg,seed,status} /
 *                                 infixFilter / imageCount / autoGet / sd{reachable,autoStart,startOnBoot,
 *                                 available,root,launcher,args,text} / pendingImages / promptTerms /
 *                                 loraStatus / quests{unread,latest}
 *   POST /api/options           → Bot#webOptions()：samplers[] / models[] / modelOptions[{name,label,…}] / functions[]
 *   POST /api/generation        → Bot#webGeneration()：width,height,sampler,model,steps,cfg,seed,imageCount
 *                                 （空串/null 表示这次不改；返回一份完整 status + message + changed）
 *   POST /api/sd/status         → Bot#sdStatus()：reachable / autoStart / startOnBoot / available / root / launcher / text
 *   POST /api/sd/presets        → Bot#webForgePresets()：forge / active / model / presets[]
 *   POST /api/sd/preset {name}  → Bot#webSetForgePreset()
 *   POST /api/sd/start          → 202 {started,sd}（后台拉起，等模型加载要几十秒到几分钟）
 *   POST /api/settings {key,value,scope} → WebApiController#applySetting 支持的键：
 *                                 chatGlobal, chatFrequency, personality, chatModel, imageModel, infixFilter,
 *                                 chatThinking, imageThinking, sdAutoStart, sdStartOnBoot, notice, logMirror,
 *                                 autoGet, token（返回一份新的 status）
 *   POST /api/config/state      → WebSetup#state()：needed / missingText /
 *                                 values{bot_name,owner_user_id,sd_base_url,sd_root,qq_ws_url,qq_token_set,
 *                                 webui_host,webui_port,webui_token_set,channels{image{base,effective,keySet,
 *                                 keyMasked,official,model},chat{…}}}
 *   POST /api/config/apply      → WebSetup#apply()：bot_name, owner_user_id, sd_base_url, sd_root, qq_ws_url,
 *                                 qq_access_token(空=不改), channels{image{base,key},chat{base,key}},
 *                                 webui_access_token(空=不改) → {changed[],notice,values}
 *   POST /api/config/test {channel} → 用当前地址与密钥发一条最小请求
 *
 * 「聊天设置」里 Java **没有**开放成 /api/settings 键的项（上下文秒数 = chat.context_seconds、
 * 原作语料复现 = chat.corpus_replay、召回条数 = chat.corpus_top_k、已关闭会话 = chat.disabled_conversations）
 * 一律只读展示；其中语料复现可以走指令通道（`.chat corpus on|off`，与桌面版 app.js 的做法一致）。
 * 本文件不创建任何全局变量；样式只注入自己 .sy-* 前缀的那一份。
 */
(function () {
  'use strict';

  var STYLE_ID = 'sy-style';

  function injectStyle() {
    if (document.getElementById(STYLE_ID)) return;
    var style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = [
      /* 字号 / 间距 / 圆角一律走 app.css 第 1 段的 --m-* 令牌（回退值写成本轮规格的同一个
         数字，令牌没加载出来时也不会跟别的屏打架）。规格见 app.css 的"排版令牌"注释：
         正文 15/1.5 · 标题 15/600 · 参数名与说明 13/1.45 · 标签 12（下限）·
         卡片 内边距14/圆角14/间距12 · 行距10 · label 与输入 6。 */
      '.sy-wrap{display:flex;flex-direction:column;gap:var(--m-gap,12px);padding:12px 12px 28px;box-sizing:border-box}',
      '.sy-card{background:#131c2e;border-radius:var(--m-radius,14px);padding:var(--m-pad,14px)}',
      '.sy-head{display:flex;align-items:flex-start;gap:10px;margin-bottom:var(--m-row-gap,10px)}',
      '.sy-head b{font-size:var(--m-fs-title,15px);color:#e8eefc;font-weight:600;line-height:var(--m-lh-body,1.5);overflow-wrap:anywhere}',
      '.sy-head .sy-hint{font-size:var(--m-fs-xs,12px);color:#93a4c4;margin-left:auto;text-align:right;max-width:52%;',
      'line-height:var(--m-lh-sm,1.45);white-space:normal;overflow-wrap:anywhere}',
      '.sy-kv{display:flex;gap:10px;font-size:var(--m-fs-body,15px);padding:7px 0;border-top:1px solid rgba(147,164,196,.11);line-height:var(--m-lh-body,1.5)}',
      '.sy-kv:first-child{border-top:0}',
      '.sy-kv-key{flex:0 0 96px;color:#93a4c4;font-size:var(--m-fs-sm,13px);line-height:var(--m-lh-sm,1.45);overflow-wrap:anywhere}',
      '.sy-kv-val{flex:1 1 auto;min-width:0;color:#e8eefc;overflow-wrap:anywhere;word-break:break-word;font-variant-numeric:tabular-nums}',
      '.sy-kv-val.ok{color:#4ad991}.sy-kv-val.bad{color:#ff6b6b}.sy-kv-val.dim{color:#93a4c4}',
      '.sy-field{display:flex;flex-direction:column;gap:var(--m-label-gap,6px);margin-bottom:var(--m-row-gap,10px);min-width:0}',
      '.sy-field>span{font-size:var(--m-fs-sm,13px);color:#93a4c4;line-height:var(--m-lh-sm,1.45);overflow-wrap:anywhere}',
      /* 原生 <select> 的**关闭态永远单行**、超长自动截断（UA 行为，CSS 改不了）；
         「基础模型（检查点）」「Forge 预设栈」这类值动辄 40+ 字符，390px 宽下只能看见前半截。
         .sy-value 是紧跟在 select 后面的**完整值行**：允许换行、绝不 ellipsis（见 attachValueLine）。 */
      '.sy-value{font-size:var(--m-fs-body,15px);font-weight:600;line-height:var(--m-lh-sm,1.45);color:#e8eefc;',
      'margin-top:var(--m-label-gap,6px);white-space:normal;overflow:visible;text-overflow:clip;overflow-wrap:anywhere;',
      'font-variant-numeric:tabular-nums}',
      /* 输入 / select / textarea：高 44、字号 16（<16px 时 iOS 聚焦会把整页放大） */
      '.sy-input,.sy-select,.sy-area{width:100%;box-sizing:border-box;min-height:var(--m-input-h,44px);padding:10px 12px;border-radius:12px;',
      'border:1px solid rgba(147,164,196,.24);background:#0e1626;color:#e8eefc;font-size:var(--m-input-fs,16px);font-family:inherit}',
      '.sy-input:focus,.sy-select:focus,.sy-area:focus{outline:none;border-color:#5aa2ff}',
      /* 多行：最小高 ≥88px（规格），这里给 132px 便于写长文本 */
      '.sy-area{min-height:132px;resize:vertical;line-height:var(--m-lh-body,1.5)}',
      '.sy-grid2{display:flex;gap:var(--m-row-gap,10px);flex-wrap:wrap}',
      '.sy-grid2>*{flex:1 1 140px;min-width:0}',
      /* 开关整行：cursor:pointer 不是装饰 —— <label> 包着 <input>，点整行都会切换，
         所以**真正的可点区域是这一整行**（高 48px、宽吃满卡片），不是里面那个 24px 的方框。
         写明 cursor:pointer 就是把这件事实说出来，读屏/鼠标/自动化量命中区时才不会误判成 24×24。 */
      '.sy-check{display:flex;align-items:center;gap:10px;min-height:48px;font-size:var(--m-fs-body,15px);color:#e8eefc;',
      'border-top:1px solid rgba(147,164,196,.11);padding:4px 0;cursor:pointer}',
      '.sy-check:first-of-type{border-top:0}',
      '.sy-check input{width:24px;height:24px;accent-color:#5aa2ff;flex:0 0 24px;margin:0}',
      '.sy-check .sy-check-text{flex:1 1 auto;min-width:0;line-height:var(--m-lh-sm,1.45)}',
      '.sy-check .sy-check-sub{font-size:var(--m-fs-sm,13px);color:#93a4c4;margin-top:2px;line-height:var(--m-lh-sm,1.45);overflow-wrap:anywhere}',
      '.sy-btn{min-height:var(--m-tap,44px);padding:0 16px;border-radius:12px;border:0;background:#5aa2ff;color:#08101f;',
      'font-size:var(--m-fs-body,15px);font-weight:600;font-family:inherit;-webkit-tap-highlight-color:transparent}',
      '.sy-btn:active{opacity:.78}',
      '.sy-btn.ghost{background:rgba(90,162,255,.14);color:#5aa2ff}',
      '.sy-btn.danger{background:rgba(255,107,107,.16);color:#ff6b6b}',
      '.sy-btn[disabled]{opacity:.5}',
      '.sy-actions{display:flex;gap:var(--m-row-gap,10px);flex-wrap:wrap;margin-top:12px}',
      '.sy-note{font-size:var(--m-fs-sm,13px);color:#93a4c4;line-height:var(--m-lh-sm,1.45);margin-top:10px;white-space:pre-wrap;overflow-wrap:anywhere}',
      '.sy-note.bad{color:#ff6b6b}.sy-note.ok{color:#4ad991}',
      '.sy-mono{font-family:Consolas,Menlo,monospace;font-size:var(--m-fs-sm,13px);font-variant-numeric:tabular-nums}',
      '.sy-link{display:flex;align-items:center;min-height:48px;padding:0 2px;color:#5aa2ff;font-size:var(--m-fs-body,15px);',
      'text-decoration:none;white-space:normal;overflow-wrap:anywhere}',
      '.sy-link:active{opacity:.7}',
      /* 出图屏的 VAE 一行 + 栈冲突横幅（见文件末尾「出图屏：VAE 与栈冲突」那一节）。
         颜色一律走 app.css 第 1 段的 --m-* / --danger / --warn 令牌，回退值写成同一批数字：
         令牌没加载出来时也不会跟别的屏打架。触摸目标全部 ≥44px（--m-tap）。 */
      '.vae-field{margin-top:4px}',
      '.vae-alert{display:flex;flex-direction:column;gap:6px;margin-bottom:var(--m-gap,12px);',
      'font-size:var(--m-fs-sm,13px);line-height:var(--m-lh-sm,1.45);overflow-wrap:anywhere}',
      '.vae-alert-title{font-size:var(--m-fs-title,15px);font-weight:600;color:#e8eefc}',
      '.vae-alert-line{color:#e8eefc}',
      '.vae-alert-dim{color:#93a4c4;font-size:var(--m-fs-xs,12px)}',
      /* WARN：黄条。BLOCK：红条（左边框加粗 + 红底 + 红标题），出图屏顶部一眼就能看见。 */
      '.vae-alert-warn{border:1px solid rgba(255,194,102,.45);border-left:4px solid var(--warn,#ffc266);',
      'background:rgba(255,194,102,.1)}',
      '.vae-alert-warn .vae-alert-title{color:var(--warn,#ffc266)}',
      '.vae-alert-block{border:1px solid rgba(255,107,107,.55);border-left:4px solid var(--danger,#ff6b6b);',
      'background:rgba(255,107,107,.14)}',
      '.vae-alert-block .vae-alert-title{color:var(--danger,#ff6b6b)}',
      '.vae-alert .btn{align-self:stretch;margin-top:4px}'
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
      if (tries > 400) { console.warn('[system] PixikoM 未就绪，屏幕未注册'); return; }
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

  function scopeOf() {
    var M = api();
    try { return M && M.scope ? String(M.scope() || '') : ''; } catch (error) { return ''; }
  }

  function byId(id) { return document.getElementById(id); }

  function button(cls, text, id) {
    var node = make('button', cls, text);
    node.type = 'button';
    if (id) node.id = id;
    return node;
  }

  function card(title, hint) {
    var node = make('div', 'sy-card');
    var head = make('div', 'sy-head');
    head.appendChild(make('b', null, title));
    if (hint) head.appendChild(make('span', 'sy-hint', hint));
    node.appendChild(head);
    return node;
  }

  function kv(key, value, cls) {
    var row = make('div', 'sy-kv');
    row.appendChild(make('span', 'sy-kv-key', key));
    row.appendChild(make('span', 'sy-kv-val' + (cls ? ' ' + cls : ''), value === undefined || value === null || value === '' ? '—' : String(value)));
    return row;
  }

  function stateLine(text, bad) {
    return make('div', 'sy-note' + (bad ? ' bad' : ''), text);
  }

  function field(label, input) {
    var box = make('label', 'sy-field');
    box.appendChild(make('span', null, label));
    box.appendChild(input);
    return box;
  }

  /** 文本框 + 它的"溢出才出现"的完整值行，一起塞进一个 .sy-field。 */
  function textField(label, input) {
    var box = field(label, input);
    if (input.type !== 'password') box.appendChild(attachOverflowValueLine(input));
    return box;
  }

  /**
   * 给一个原生 <select> 补一条**完整值行**（`.sy-value`），返回那一行。
   *
   * 为什么需要：原生 `<select>` 关闭态是单行、超出就由 UA 截断，CSS 无解。
   * 「基础模型（检查点）」（`xl · waiIllustriousSDXL_v170.safetensors [f116b0c78f]`）、
   * 「Forge 预设栈」这类值在 390px 宽下只看得到前半截 —— 用户原话里的"被掩盖一部分"。
   * 这一行完整显示当前值、允许换行；下拉本身照旧保留（原生选择体验 + id 契约不变）。
   *
   * 同步时机：① 用户 change；② 选项被异步填进来（MutationObserver）；
   * ③ 代码里 setSelectValue()/fillForge() 改了 value —— 它们会调 `select.__syncValueLine()`。
   */
  function attachValueLine(selectNode, placeholder) {
    var line = make('div', 'sy-value selectable');
    if (selectNode.id) line.setAttribute('data-value-for', selectNode.id);
    function sync() {
      var text = String(selectNode.value || '');
      if (!text) {
        var option = selectNode.options && selectNode.selectedIndex >= 0 ? selectNode.options[selectNode.selectedIndex] : null;
        text = option ? String(option.textContent || '') : '';
      }
      line.textContent = text || placeholder || '';
    }
    selectNode.addEventListener('change', sync);
    selectNode.__syncValueLine = sync;
    if (typeof MutationObserver === 'function' && selectNode.id) {
      new MutationObserver(sync).observe(selectNode, { childList: true, subtree: true });
    }
    sync();
    return line;
  }

  /**
   * 普通文本框（不是 select）的完整值行：**只在当前值真的超出输入框可见宽度时**才显示。
   * 短值（deepseek-flash、http://127.0.0.1:7861）不打扰界面；长值（自建 base URL、长模型名）
   * 在下面补一行完整可换行的文本 —— 单行 `<input>` 同样是"看不全"的重灾区。
   * 宽度用 canvas 按计算字体量（`<input>` 没有文本节点，scrollWidth 量不出来）。
   */
  function attachOverflowValueLine(input) {
    var line = make('div', 'sy-value selectable');
    if (input.id) line.setAttribute('data-value-for', input.id);
    line.hidden = true;
    var ctx = document.createElement('canvas').getContext('2d');
    function sync() {
      var cs = window.getComputedStyle(input);
      if (!cs) return;
      ctx.font = cs.fontStyle + ' ' + cs.fontWeight + ' ' + cs.fontSize + ' ' + cs.fontFamily;
      var text = String(input.value || '');
      var need = ctx.measureText(text).width;
      var avail = input.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
      line.hidden = !(avail > 0 && text && need > avail + 0.5);
      line.textContent = text;
    }
    input.addEventListener('input', sync);
    input.addEventListener('change', sync);
    input.addEventListener('blur', sync);
    input.__syncValueLine = sync;
    // 挂进 DOM 之后 clientWidth 才有值：下一帧再量一次（renderSetup 是"先建后 append"，同步的）
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(sync);
    return line;
  }

  function textInput(id, placeholder, value, type) {
    var input = make('input', 'sy-input');
    input.type = type || 'text';
    input.id = id;
    if (placeholder) input.placeholder = placeholder;
    if (value !== undefined && value !== null) input.value = String(value);
    return input;
  }

  function numberInput(id, placeholder, value, step) {
    var input = make('input', 'sy-input');
    input.type = 'number';
    input.id = id;
    input.inputMode = 'numeric';
    if (step) input.step = step;
    if (placeholder) input.placeholder = placeholder;
    if (value !== undefined && value !== null) input.value = String(value);
    return input;
  }

  function select(id, values, current, labelOf) {
    var node = make('select', 'sy-select');
    node.id = id;
    var seen = {};
    (values || []).forEach(function (value) {
      var name = typeof value === 'string' ? value : String((value && value.name) || '');
      if (!name) return;
      seen[name] = true;
      var option = document.createElement('option');
      option.value = name;
      option.textContent = labelOf ? labelOf(value) : name;
      node.appendChild(option);
    });
    if (current && !seen[current]) {
      var extra = document.createElement('option');
      extra.value = current;
      extra.textContent = current + '（当前）';
      node.appendChild(extra);
    }
    if (current) node.value = current;
    return node;
  }

  function switchRow(id, label, checked, sub) {
    var wrap = make('label', 'sy-check');
    var box = make('input');
    box.type = 'checkbox';
    box.id = id;
    box.checked = !!checked;
    wrap.appendChild(box);
    var text = make('div', 'sy-check-text');
    text.appendChild(make('div', null, label));
    if (sub) text.appendChild(make('div', 'sy-check-sub', sub));
    wrap.appendChild(text);
    return wrap;
  }

  function confirmBox(title, text, okText, danger) {
    var M = api();
    if (M && typeof M.confirm === 'function') return M.confirm({ title: title, text: text, ok: okText || '确定', danger: !!danger });
    return Promise.resolve(window.confirm(text));
  }

  function promptBox(title, value, placeholder, ok) {
    var M = api();
    if (M && typeof M.prompt === 'function') return M.prompt({ title: title, value: value, placeholder: placeholder, ok: ok });
    return Promise.resolve(window.prompt(title, value));
  }

  function infoBox(title, text) {
    var M = api();
    if (M && typeof M.confirm === 'function') return M.confirm({ title: title, text: text, ok: '知道了' });
    toast(text);
    return Promise.resolve(true);
  }

  /** 失败分档：优先用 api() 的分类码（network/timeout/http/unauthorized），没有码时用 healthz 兜底。 */
  function classify(error) {
    var message = messageOf(error);
    var code = String((error && error.code) || '');
    if (code === 'unauthorized') return Promise.resolve('令牌不对或已失效：' + message);
    if (code === 'network' || code === 'timeout') return Promise.resolve('连不上服务器：' + message);
    var authLike = /unauthor|令牌|token|401|403/i.test(message);
    return fetch('/healthz', { cache: 'no-store' })
      .then(function (response) { return response && response.ok ? (authLike ? '令牌不对或已失效：' + message : '服务器有响应，但这个请求失败了：' + message) : '连不上服务器：' + message; })
      .catch(function () { return '连不上服务器：' + message; });
  }

  function timeText(value) {
    if (!value) return '—';
    var M = api();
    try { if (M && M.fmtTime) { var text = M.fmtTime(value); if (text) return String(text); } } catch (error) { /* 用原值 */ }
    return String(value);
  }

  /** 指令通道（系统/聊天设置里的几个“只能发指令”的开关）。 */
  function runCommand(command) {
    var M = api();
    return M.api('/api/command', { method: 'POST', body: { command: command, scope: scopeOf() } }).then(function (capture) {
      var result = capture || {};
      if (result.done || !result.id) return result;
      var tries = 0;
      return new Promise(function (resolve) {
        (function poll() {
          tries += 1;
          if (tries > 6) { resolve(result); return; }
          setTimeout(function () {
            M.api('/api/capture', { method: 'POST', body: { id: result.id } }).then(function (next) {
              result = next || result;
              if (result.done && (result.texts || []).length) resolve(result); else poll();
            }, function () { resolve(result); });
          }, 400);
        })();
      });
    });
  }

  function textsOf(capture) {
    if (!capture) return '';
    return (capture.texts || []).filter(function (line) { return String(line || '').trim(); }).join('\n');
  }

  /** 统一的设置写入：POST /api/settings {key,value,scope}（value 一律是字符串，Java 侧按字符串解析）。 */
  function saveSetting(key, value, label) {
    var M = api();
    return M.api('/api/settings', { method: 'POST', body: { key: key, value: String(value), scope: scopeOf() } })
      .then(function (status) {
        if (typeof onStatus === 'function') onStatus(status);
        toast('已保存：' + (label || key));
        return status;
      }, function (error) {
        toast('没能保存：' + messageOf(error));
        return null;
      });
  }

  /* ================================================================ 系统 */

  var syRoot = null;
  var syStatus = null;
  var sySdPoll = null;
  var sySdDeadline = 0;
  var onStatus = null;                         // 当前屏幕的状态回调（saveSetting 成功后回填）

  function renderSystem(target) {
    syRoot = target || syRoot;
    if (!syRoot) return;
    clear(syRoot);
    var wrap = make('div', 'sy-wrap');
    wrap.appendChild(systemStatusCard());
    wrap.appendChild(systemGenCard());
    wrap.appendChild(systemSdCard());
    wrap.appendChild(systemSwitchCard());
    syRoot.appendChild(wrap);
    onStatus = function (status) { if (status) fillSystem(status); };
    refreshSystem();
  }

  function systemStatusCard() {
    var node = card('运行状态', 'POST /api/status');
    var body = make('div');
    body.id = 'sy-status-body';
    body.appendChild(stateLine('正在读取…'));
    node.appendChild(body);
    var actions = make('div', 'sy-actions');
    var reload = button('sy-btn ghost', '刷新状态', 'sy-status-reload');
    reload.addEventListener('click', function () {
      clear(body);
      body.appendChild(stateLine('正在读取…'));
      loadSystemStatus();
    });
    actions.appendChild(reload);
    node.appendChild(actions);
    return node;
  }

  function systemGenCard() {
    var node = card('生成参数（高级）', '改完立即生效');
    // 注意：字段是在这张卡挂进 DOM **之前**建好的，所以监听一律挂在本地引用上（不能靠 getElementById）。
    var gen = {};
    var grid = make('div', 'sy-grid2');
    var width = numberInput('sy-gen-width', '宽', '');
    var height = numberInput('sy-gen-height', '高', '');
    var size = make('div', 'sy-grid2');
    size.appendChild(width);
    size.appendChild(height);
    grid.appendChild(field('宽 × 高', size));
    gen.width = width;
    gen.height = height;
    var pairs = [['步数', 'sy-gen-steps', 'steps', null], ['CFG', 'sy-gen-cfg', 'cfg', '0.1'],
      ['种子（-1 = 随机）', 'sy-gen-seed', 'seed', null], ['图片上限（张/条）', 'sy-gen-imgcnt', 'imageCount', null]];
    pairs.forEach(function (item) {
      var input = numberInput(item[1], item[0], '', item[3]);
      gen[item[2]] = input;
      grid.appendChild(field(item[0], input));
    });
    node.appendChild(grid);

    var sampler = select('sy-gen-sampler', [], '');
    gen.sampler = sampler;
    var samplerField = field('采样方法', sampler);
    samplerField.appendChild(attachValueLine(sampler, '（SD 没在跑，列不出来）'));
    node.appendChild(samplerField);
    var model = select('sy-gen-model', [], '');
    gen.model = model;
    var modelField = field('基础模型（检查点）', model);
    modelField.appendChild(attachValueLine(model, '（SD 没在跑，列不出来）'));
    node.appendChild(modelField);

    var note = make('div', 'sy-note', '正在读取当前参数…');
    note.id = 'sy-gen-note';
    node.appendChild(note);

    node.appendChild(make('div', 'sy-note', '采样方法与尺寸遵循 WebUI 页面，步数 / CFG / 种子 / 底模是机器人自己的记录（与桌面控制台「生成参数」同一批接口）。'));

    Object.keys(gen).forEach(function (key) {
      var input = gen[key];
      input.addEventListener('change', function () {
        var raw = String(input.value == null ? '' : input.value).trim();
        if (raw === '') return;                       // 空着 = 这次不改这一项（与桌面版一致）
        var payload = {};
        payload[key] = (key === 'sampler' || key === 'model') ? raw : Number(raw);
        if (typeof payload[key] === 'number' && !isFinite(payload[key])) { setGenNote('这一项要填数字。', true); return; }
        applyGeneration(payload);
      });
    });
    return node;
  }

  function setGenNote(text, bad) {
    var note = byId('sy-gen-note');
    if (!note) return;
    note.className = 'sy-note' + (bad ? ' bad' : '');
    note.textContent = text;
  }

  function applyGeneration(payload) {
    setGenNote('正在生效…');
    return api().api('/api/generation', { method: 'POST', body: payload })
      .then(function (status) {
        fillSystem(status);
        var message = String((status && status.message) || '已生效。');
        setGenNote(message);
        toast(message.split('\n')[0]);
        return status;
      }, function (error) {
        setGenNote('没能生效：' + messageOf(error), true);
        toast('没能生效：' + messageOf(error));
        loadSystemStatus();
        return null;
      });
  }

  function systemSdCard() {
    var node = card('Forge / Stable Diffusion', 'POST /api/sd/*');
    var body = make('div');
    body.id = 'sy-sd-body';
    body.appendChild(stateLine('正在读取…'));
    node.appendChild(body);

    var forgeSelect = select('sy-forge-preset', [], '');
    var forgeField = field('Forge 预设栈（底模 + VAE + 文本编码器）', forgeSelect);
    forgeField.appendChild(attachValueLine(forgeSelect, '（还没读到预设）'));
    node.appendChild(forgeField);
    var forgeNote = make('div', 'sy-note', '正在读取 Forge 预设…');
    forgeNote.id = 'sy-forge-note';
    node.appendChild(forgeNote);

    var actions = make('div', 'sy-actions');
    var apply = button('sy-btn', '切换预设', 'sy-forge-apply');
    apply.addEventListener('click', function () {
      var select2 = byId('sy-forge-preset');
      var name = select2 ? String(select2.value || '') : '';
      if (!name) { toast('请先选择一个预设。'); return; }
      confirmBox('切换 Forge 预设', '切到「' + name + '」？会连同该栈的采样方法、调度器、尺寸、步数、CFG 一起采纳为当前参数。', '切换', false)
        .then(function (ok) {
          if (!ok) return;
          api().api('/api/sd/preset', { method: 'POST', body: { name: name } }).then(function (data) {
            fillForge(data);
            toast(String((data && data.notice) || '已切换预设。').split('\n')[0]);
          }, function (error) { toast('切换失败：' + messageOf(error)); });
        });
    });
    var refresh = button('sy-btn ghost', '刷新 SD / 预设', 'sy-sd-reload');
    refresh.addEventListener('click', function () { loadSdStatus(); loadForgePresets(); });
    var start = button('sy-btn ghost', '启动 SD WebUI', 'sy-sd-start');
    start.addEventListener('click', function () {
      confirmBox('启动 Stable Diffusion', '现在拉起 SD WebUI？加载模型通常要几十秒到几分钟，期间出图会排队；启动过程在后台跑，这一屏会每 3 秒自动刷新状态。', '启动', false)
        .then(function (ok) {
          if (!ok) return;
          toast('正在启动 SD WebUI…');
          api().api('/api/sd/start', { method: 'POST', body: {} }).then(function (result) {
            fillSdBody((result && result.sd) || {});
            toast(result && result.sd && result.sd.reachable ? 'SD 已经在运行' : '正在启动 SD WebUI…');
            startSdWatch();
          }, function (error) { toast('启动失败：' + messageOf(error)); });
        });
    });
    actions.appendChild(apply);
    actions.appendChild(refresh);
    actions.appendChild(start);
    node.appendChild(actions);
    return node;
  }

  /** 启动后每 3 秒看一次（只在屏幕可见时轮询），最多盯 6 分钟。 */
  function startSdWatch() {
    var M = api();
    if (sySdPoll) { try { sySdPoll(); } catch (error) { /* 忽略 */ } sySdPoll = null; }
    sySdDeadline = Date.now() + 6 * 60 * 1000;
    sySdPoll = M.pollWhileVisible(function () {
      if (M.current && M.current() !== 'system') { stopSdWatch(); return; }
      if (Date.now() > sySdDeadline) { stopSdWatch(); return; }
      M.api('/api/sd/status', { method: 'POST', body: {} }).then(function (sd) {
        fillSdBody(sd);
        if (sd && sd.reachable) { stopSdWatch(); toast('SD WebUI 已就绪'); loadSystemStatus(); }
      }, function () { /* 状态读不到就继续等下一轮 */ });
    }, 3000);
  }

  function stopSdWatch() {
    if (!sySdPoll) return;
    try { sySdPoll(); } catch (error) { /* 忽略 */ }
    sySdPoll = null;
  }

  function systemSwitchCard() {
    var node = card('本机开关', 'POST /api/settings');
    var body = make('div');
    body.id = 'sy-switch-body';
    body.appendChild(stateLine('正在读取…'));
    node.appendChild(body);
    node.appendChild(make('div', 'sy-note', '自动领取控制「出图完成后自动领取」；SD 自动启动/开机自启只在装了启动器时可用。'));
    return node;
  }

  function refreshSystem() {
    loadSystemStatus();
    loadOptions();
    loadSdStatus();
    loadForgePresets();
  }

  function loadSystemStatus() {
    return api().api('/api/status', { method: 'POST', body: {} }).then(function (status) {
      fillSystem(status);
      return status;
    }, function (error) {
      var body = byId('sy-status-body');
      if (body) { clear(body); body.appendChild(stateLine('读取失败：' + messageOf(error), true)); }
      setGenNote('状态读取失败：' + messageOf(error), true);
      return null;
    });
  }

  function fillSystem(status) {
    if (!status) return;
    syStatus = status;
    var body = byId('sy-status-body');
    if (body) {
      clear(body);
      var chat = status.chat || {};
      var gen = status.generation || {};
      var sd = status.sd || {};
      var quests = status.quests || {};
      var affinity = status.affinity || {};
      var mood = status.mood || {};
      body.appendChild(kv('机器人', status.botName));
      body.appendChild(kv('会话 scope', status.scope));
      body.appendChild(kv('网页端口', status.webPort));
      body.appendChild(kv('聊天开关', chat.global ? '开启' : '关闭', chat.global ? 'ok' : 'bad'));
      body.appendChild(kv('回复上限', (chat.frequency === undefined ? '—' : chat.frequency + ' 次/分钟')));
      body.appendChild(kv('上下文', (chat.contextSeconds === undefined ? '—' : chat.contextSeconds + ' 秒')));
      body.appendChild(kv('聊天模型', (status.chatChannel || {}).model));
      body.appendChild(kv('生图模型', (status.imageChannel || {}).model));
      body.appendChild(kv('生成状态', gen.status || '空闲'));
      body.appendChild(kv('当前参数', gen.width === undefined ? '—'
        : [gen.width + '×' + gen.height, gen.sampler, '步数 ' + gen.steps, 'CFG ' + gen.cfg, '种子 ' + gen.seed].join(' · ')));
      body.appendChild(kv('底模', gen.model ? gen.model : '跟随 WebUI 当前模型'));
      body.appendChild(kv('SD 连接', sd.reachable ? '在线' : '离线', sd.reachable ? 'ok' : 'bad'));
      body.appendChild(kv('待领取图片', status.pendingImages === undefined ? '—' : status.pendingImages + ' 张'));
      body.appendChild(kv('回执', '未读 ' + (quests.unread === undefined ? 0 : quests.unread) + ' · 最新 #' + (quests.latest === undefined ? 0 : quests.latest)));
      body.appendChild(kv('提示词词条', status.promptTerms));
      body.appendChild(kv('图片上限', (status.imageCount === undefined ? '—' : status.imageCount + ' 张/条')));
      body.appendChild(kv('自动领取', status.autoGet ? '开启' : '关闭'));
      body.appendChild(kv('词库约束', status.infixFilter ? '开启' : '关闭'));
      body.appendChild(kv('好感度', (affinity.score === undefined ? '—' : affinity.score + '（' + (affinity.tier || '—') + '）')));
      body.appendChild(kv('情绪', mood.mood === undefined ? '—' : String(mood.mood) + (mood.intensity === undefined ? '' : '（强度 ' + mood.intensity + '）')));
      body.appendChild(kv('服务器时间', timeText(status.time)));
      body.appendChild(kv('机器人目录', status.path, 'dim'));
    }
    fillGenFields(status);
    fillSwitchBody(status);
  }

  function fillGenFields(status) {
    var gen = status.generation || {};
    var pairs = [['sy-gen-width', gen.width], ['sy-gen-height', gen.height], ['sy-gen-steps', gen.steps],
      ['sy-gen-cfg', gen.cfg], ['sy-gen-seed', gen.seed], ['sy-gen-imgcnt', status.imageCount]];
    pairs.forEach(function (pair) {
      var node = byId(pair[0]);
      if (!node || document.activeElement === node) return;
      node.value = pair[1] === undefined || pair[1] === null ? '' : String(pair[1]);
    });
    var sampler = byId('sy-gen-sampler');
    if (sampler && gen.sampler) setSelectValue(sampler, gen.sampler);
    var model = byId('sy-gen-model');
    if (model && gen.model) setSelectValue(model, gen.model);
    setGenNote('当前生效：' + [[gen.width + ' × ' + gen.height + ' 像素'], gen.sampler, '步数 ' + gen.steps,
      'CFG ' + gen.cfg, '种子 ' + gen.seed, '图片上限 ' + status.imageCount + ' 张/条',
      gen.model ? '模型 ' + gen.model : '模型跟随 WebUI'].filter(Boolean).join(' · '));
  }

  /** 下拉里没有这个值时补一个，再选中（底模名可能来自 WebUI 当前模型）。 */
  function setSelectValue(selectNode, value) {
    var found = false;
    for (var i = 0; i < selectNode.options.length; i++) {
      if (selectNode.options[i].value === value) { found = true; break; }
    }
    if (!found) {
      var option = document.createElement('option');
      option.value = value;
      option.textContent = value + '（当前）';
      selectNode.appendChild(option);
    }
    selectNode.value = value;
    if (typeof selectNode.__syncValueLine === 'function') selectNode.__syncValueLine();
  }

  function loadOptions() {
    return api().api('/api/options', { method: 'POST', body: {} }).then(function (data) {
      var samplers = byId('sy-gen-sampler');
      var models = byId('sy-gen-model');
      var current = (syStatus && syStatus.generation) || {};
      if (samplers) {
        clear(samplers);
        (data.samplers || []).forEach(function (name) { samplers.appendChild(optionNode(name, name)); });
        if (current.sampler) setSelectValue(samplers, current.sampler);
      }
      if (models) {
        clear(models);
        var options = data.modelOptions || [];
        if (options.length) {
          options.forEach(function (info) {
            models.appendChild(optionNode(info.name || info.title || '', info.label || info.name || info.title || ''));
          });
        } else {
          (data.models || []).forEach(function (name) { models.appendChild(optionNode(name, name)); });
        }
        if (current.model) setSelectValue(models, current.model);
      }
      if (data.error) setGenNote('采样方法/模型列表读取不完整（' + data.error + '）：SD 没在运行时会这样，可以点「启动 SD WebUI」。', false);
      return data;
    }, function (error) {
      var samplers = byId('sy-gen-sampler');
      if (samplers) { clear(samplers); samplers.appendChild(optionNode('', '（读取失败）')); }
      var models = byId('sy-gen-model');
      if (models) { clear(models); models.appendChild(optionNode('', '（读取失败）')); }
      setGenNote('下拉列表读取失败：' + messageOf(error), true);
      return null;
    });
  }

  function optionNode(value, label) {
    var option = document.createElement('option');
    option.value = value;
    option.textContent = label;
    return option;
  }

  function loadSdStatus() {
    return api().api('/api/sd/status', { method: 'POST', body: {} }).then(function (sd) {
      fillSdBody(sd);
      return sd;
    }, function (error) {
      var body = byId('sy-sd-body');
      if (body) { clear(body); body.appendChild(stateLine('读取失败：' + messageOf(error), true)); }
      return null;
    });
  }

  function fillSdBody(sd) {
    var body = byId('sy-sd-body');
    if (!body || !sd) return;
    clear(body);
    body.appendChild(kv('连接', sd.reachable ? '在线' : '离线', sd.reachable ? 'ok' : 'bad'));
    body.appendChild(kv('说明', sd.text));
    body.appendChild(kv('自动启动', sd.autoStart ? '开' : '关'));
    body.appendChild(kv('开机自启', sd.startOnBoot ? '开' : '关'));
    body.appendChild(kv('启动器可用', sd.available ? '可用' : '不可用', sd.available ? 'ok' : 'dim'));
    if (sd.launcher) body.appendChild(kv('启动脚本', sd.launcher, 'dim'));
    if (sd.args) body.appendChild(kv('启动参数', sd.args, 'dim'));
    if (sd.root) body.appendChild(kv('SD 目录', sd.root, 'dim'));
  }

  function loadForgePresets() {
    return api().api('/api/sd/presets', { method: 'POST', body: {} }).then(function (data) {
      fillForge(data);
      return data;
    }, function (error) {
      var note = byId('sy-forge-note');
      if (note) { note.className = 'sy-note bad'; note.textContent = '读取预设失败：' + messageOf(error); }
      return null;
    });
  }

  function fillForge(data) {
    var selectNode = byId('sy-forge-preset');
    var note = byId('sy-forge-note');
    if (!selectNode) return;
    clear(selectNode);
    var forge = !!(data && data.forge);
    var presets = (data && data.presets) || [];
    if (!forge) {
      selectNode.appendChild(optionNode('', '（当前 WebUI 不是 Forge / Forge Neo，用「基础模型」下拉即可）'));
      selectNode.disabled = true;
    } else {
      selectNode.disabled = false;
      if (!presets.length) selectNode.appendChild(optionNode('', '（Forge 里还没有配置预设）'));
      presets.forEach(function (item) {
        var name = String((item && (item.preset || item.name)) || '');
        if (!name) return;
        var checkpoint = String((item && item.checkpoint) || '');
        var short = checkpoint ? checkpoint.split(/[\\/]/).pop() : '';
        var label = name + (short ? ' · ' + short : '') + (item && item.steps ? ' · 步数 ' + item.steps : '');
        selectNode.appendChild(optionNode(name, label));
      });
      if (data && data.active) setSelectValue(selectNode, String(data.active));
    }
    if (note) {
      note.className = 'sy-note';
      note.textContent = forge
        ? ('当前预设：' + ((data && data.active) || '（未选）') + '；切换会连着该栈的采样方法/尺寸/步数/CFG 一起采纳。')
        : '当前 WebUI 不是 Forge / Forge Neo：没有预设栈可切，直接用上面的基础模型下拉。';
    }
    if (typeof selectNode.__syncValueLine === 'function') selectNode.__syncValueLine();
  }

  function fillSwitchBody(status) {
    var body = byId('sy-switch-body');
    if (!body) return;
    clear(body);
    body.appendChild(bindSwitch('sy-sw-autoGet', '自动领取生成的图片', !!status.autoGet, '每个任务完成后自动领取（等同 .gen toggle）', 'autoGet'));
    body.appendChild(bindSwitch('sy-sw-notice', '上线 / 下线播报', !!(status.chat && status.notice), '/api/status 不回显这一项，按需要重新设置一次', 'notice'));
    body.appendChild(bindSwitch('sy-sw-logmirror', 'WARN/ERROR 日志同步到主群', false, '/api/status 不回显这一项，按需要重新设置一次', 'logMirror'));
    body.appendChild(bindSwitch('sy-sw-infix', '词库约束（按会话）', !!status.infixFilter, '开启后只允许词库里的词条', 'infixFilter'));
    body.appendChild(bindSwitch('sy-sw-sdauto', 'SD 自动启动', !!(status.sd && status.sd.autoStart), '机器人启动时顺带拉起 SD WebUI（重启后生效）', 'sdAutoStart', true));
    body.appendChild(bindSwitch('sy-sw-sdboot', '开机自启动 SD', !!(status.sd && status.sd.startOnBoot), '随系统启动（重启后生效）', 'sdStartOnBoot', true));
  }

  function bindSwitch(id, label, checked, sub, key, danger) {
    var row = switchRow(id, label, checked, sub);
    var box = row.querySelector('input');
    box.addEventListener('change', function () {
      var wanted = box.checked;
      var value = wanted ? 'on' : 'off';
      var apply = function () { saveSetting(key, value, label).then(function (status) { if (!status) box.checked = !wanted; }); };
      if (danger && wanted) {
        confirmBox('确认开启', label + '：' + (sub || '会改动机器人的启动行为') + '。确定开启？', '开启', false).then(function (ok) {
          if (ok) apply(); else box.checked = false;
        });
      } else apply();
    });
    return row;
  }

  /* ================================================================ 聊天设置 */

  var ccRoot = null;
  var ccLoaded = '';

  function renderChatCfg(target) {
    ccRoot = target || ccRoot;
    if (!ccRoot) return;
    clear(ccRoot);
    var wrap = make('div', 'sy-wrap');
    wrap.appendChild(chatStatusCard());
    wrap.appendChild(chatSwitchCard());
    wrap.appendChild(chatNumberCard());
    wrap.appendChild(chatModelCard());
    wrap.appendChild(chatPersonalityCard());
    wrap.appendChild(chatCorpusCard());
    ccRoot.appendChild(wrap);
    onStatus = function (status) { if (status) fillChatStatus(status); };
    loadChatStatus();
  }

  function chatStatusCard() {
    var node = card('聊天状态', 'POST /api/status');
    var body = make('div');
    body.id = 'sy-cc-status';
    body.appendChild(stateLine('正在读取…'));
    node.appendChild(body);
    return node;
  }

  function chatSwitchCard() {
    var node = card('开关', '');
    var body = make('div');
    body.id = 'sy-cc-switches';
    body.appendChild(stateLine('正在读取…'));
    node.appendChild(body);
    return node;
  }

  function chatNumberCard() {
    var node = card('频率', '每分钟回复上限');
    node.appendChild(field('每分钟最多回复（0 = 静默）', numberInput('sy-cc-frequency', '例如 15', '')));
    var actions = make('div', 'sy-actions');
    var apply = button('sy-btn', '应用频率', 'sy-cc-apply-frequency');
    apply.addEventListener('click', function () {
      var node2 = byId('sy-cc-frequency');
      var raw = String(node2 ? node2.value : '').trim();
      if (raw === '') { toast('请填写频率。'); return; }
      saveSetting('chatFrequency', raw, '每分钟上限');
    });
    actions.appendChild(apply);
    node.appendChild(actions);
    node.appendChild(make('div', 'sy-note', '上下文秒数、语料复现、召回条数与已关闭会话不在 /api/settings 的键里（Java 侧只读），这里只如实展示。'));
    return node;
  }

  function chatModelCard() {
    var node = card('模型与思考', '聊天 / 生图两条通道各自独立');
    node.appendChild(textField('聊天模型', textInput('sy-cc-model', '例如 deepseek-flash', '')));
    var actions = make('div', 'sy-actions');
    var apply = button('sy-btn', '应用聊天模型', 'sy-cc-apply-model');
    apply.addEventListener('click', function () {
      var node2 = byId('sy-cc-model');
      var raw = String(node2 ? node2.value : '').trim();
      if (!raw) { toast('请填写模型名称。'); return; }
      saveSetting('chatModel', raw, '聊天模型');
    });
    actions.appendChild(apply);
    node.appendChild(actions);
    var body = make('div');
    body.id = 'sy-cc-thinking';
    body.appendChild(stateLine('正在读取…'));
    node.appendChild(body);
    return node;
  }

  function chatPersonalityCard() {
    var node = card('性格设定', '保存后立即生效');
    var area = make('textarea', 'sy-area');
    area.id = 'sy-cc-personality';
    area.placeholder = '基础性格设定';
    node.appendChild(area);
    var hint = make('div', 'sy-note', '');
    hint.id = 'sy-cc-personality-hint';
    node.appendChild(hint);
    var actions = make('div', 'sy-actions');
    var apply = button('sy-btn', '保存性格', 'sy-cc-apply-personality');
    apply.addEventListener('click', function () {
      var value = String(area.value || '');
      if (value.length > 20000) { toast('性格设定超过 20000 字符。'); return; }
      confirmBox('保存性格设定', '把这段文字保存为基础性格设定？长度 ' + value.length + ' 字符，保存后立刻生效。', '保存', false)
        .then(function (ok) { if (ok) saveSetting('personality', value, '性格设定'); });
    });
    actions.appendChild(apply);
    node.appendChild(actions);
    return node;
  }

  function chatCorpusCard() {
    var node = card('原作语料（指令通道）', '.chat corpus');
    node.appendChild(make('div', 'sy-note', 'Java 的 /api/settings 没有开放语料开关，只能走指令通道（与桌面控制台一致）。召回条数 chat.corpus_top_k 目前没有对外开关。'));
    var actions = make('div', 'sy-actions');
    var on = button('sy-btn ghost', '开启语料复现', 'sy-cc-corpus-on');
    var off = button('sy-btn ghost', '关闭语料复现', 'sy-cc-corpus-off');
    var read = button('sy-btn ghost', '读取当前设置', 'sy-cc-corpus-read');
    var out = make('div', 'sy-note sy-mono', '');
    out.id = 'sy-cc-corpus-out';
    function run(command) {
      out.className = 'sy-note sy-mono';
      out.textContent = '正在执行…';
      runCommand(command).then(function (capture) {
        var text = textsOf(capture);
        out.textContent = text || ('已下达指令' + (capture && capture.quest ? '（任务 #' + capture.quest + '）' : '') + '。');
        toast(text ? text.split('\n')[0] : '已下达指令。');
      }, function (error) {
        out.className = 'sy-note sy-mono bad';
        out.textContent = '执行失败：' + messageOf(error);
      });
    }
    on.addEventListener('click', function () { run('.chat corpus on'); });
    off.addEventListener('click', function () { run('.chat corpus off'); });
    read.addEventListener('click', function () { run('.chat corpus'); });
    actions.appendChild(on);
    actions.appendChild(off);
    actions.appendChild(read);
    node.appendChild(actions);
    node.appendChild(out);
    return node;
  }

  function loadChatStatus() {
    return api().api('/api/status', { method: 'POST', body: {} }).then(function (status) {
      fillChatStatus(status);
      return status;
    }, function (error) {
      var body = byId('sy-cc-status');
      if (body) { clear(body); body.appendChild(stateLine('读取失败：' + messageOf(error), true)); }
      return null;
    });
  }

  function fillChatStatus(status) {
    if (!status) return;
    var chat = status.chat || {};
    syStatus = status;

    var body = byId('sy-cc-status');
    if (body) {
      clear(body);
      body.appendChild(kv('全局聊天', chat.global ? '开启' : '关闭', chat.global ? 'ok' : 'bad'));
      body.appendChild(kv('回复上限', (chat.frequency === undefined ? '—' : chat.frequency + ' 次/分钟')));
      body.appendChild(kv('上下文秒数', (chat.contextSeconds === undefined ? '—' : chat.contextSeconds + ' 秒（只读）')));
      body.appendChild(kv('性格字数', (chat.personalityChars === undefined ? '—' : chat.personalityChars)));
      body.appendChild(kv('聊天模型', (status.chatChannel || {}).model));
      body.appendChild(kv('生图模型', (status.imageChannel || {}).model));
      var affinity = status.affinity || {};
      var mood = status.mood || {};
      body.appendChild(kv('好感度', affinity.score === undefined ? '—' : affinity.score + '（' + (affinity.tier || '—') + '）'));
      body.appendChild(kv('情绪', mood.mood === undefined ? '—' : String(mood.mood)));
      body.appendChild(kv('会话 scope', status.scope));
    }

    var switches = byId('sy-cc-switches');
    if (switches) {
      clear(switches);
      switches.appendChild(bindChatChannelSwitch('sy-cc-global', '全局聊天开关', !!chat.global, '关闭后所有会话都不回复（只影响聊天，生图照做）', 'chatGlobal'));
      switches.appendChild(bindChatChannelSwitch('sy-cc-notice', '上线 / 下线播报', !!(status.notice), '/api/status 不回显这一项，按需要重新设置一次', 'notice'));
      switches.appendChild(bindChatChannelSwitch('sy-cc-logmirror', 'WARN/ERROR 日志同步到主群', !!(status.logMirror), '/api/status 不回显这一项，按需要重新设置一次', 'logMirror'));
      switches.appendChild(bindChatChannelSwitch('sy-cc-infix', '词库约束（按会话）', !!status.infixFilter, '开启后只允许词库里的词条', 'infixFilter'));
      switches.appendChild(bindChatChannelSwitch('sy-cc-chatthinking', '聊天思考模式', !!((status.chatChannel || {}).thinking), '聊天通道的 DeepSeek 思考', 'chatThinking'));
      switches.appendChild(bindChatChannelSwitch('sy-cc-imagethinking', '生图思考模式', !!((status.imageChannel || {}).thinking), '生图通道的 DeepSeek 思考', 'imageThinking'));
    }

    var thinking = byId('sy-cc-thinking');
    if (thinking) {
      clear(thinking);
      thinking.appendChild(kv('聊天通道密钥', (status.chatChannel || {}).keyConfigured ? '已配置' : '未配置', (status.chatChannel || {}).keyConfigured ? 'ok' : 'bad'));
      thinking.appendChild(kv('生图通道密钥', (status.imageChannel || {}).keyConfigured ? '已配置' : '未配置', (status.imageChannel || {}).keyConfigured ? 'ok' : 'bad'));
    }

    var model = byId('sy-cc-model');
    if (model && document.activeElement !== model && (status.chatChannel || {}).model) {
      model.value = String(status.chatChannel.model);
      if (typeof model.__syncValueLine === 'function') model.__syncValueLine();
    }
    var frequency = byId('sy-cc-frequency');
    if (frequency && document.activeElement !== frequency && chat.frequency !== undefined) frequency.value = String(chat.frequency);
    var area = byId('sy-cc-personality');
    if (area && document.activeElement !== area) {
      area.value = String(chat.personality || '');
      ccLoaded = JSON.stringify({ personality: area.value });
    }
    var hint = byId('sy-cc-personality-hint');
    if (hint) hint.textContent = '当前 ' + (chat.personalityChars === undefined ? '—' : chat.personalityChars) + ' 字符；保存会把整段替换成文本框里的内容。';
  }

  function bindChatChannelSwitch(id, label, checked, sub, key) {
    var row = switchRow(id, label, checked, sub);
    var box = row.querySelector('input');
    box.addEventListener('change', function () {
      var wanted = box.checked;
      saveSetting(key, wanted ? 'on' : 'off', label).then(function (status) { if (!status) box.checked = !wanted; });
    });
    return row;
  }

  /* ================================================================ 首次配置 */

  var suRoot = null;

  function renderSetup(target) {
    suRoot = target || suRoot;
    if (!suRoot) return;
    clear(suRoot);
    var wrap = make('div', 'sy-wrap');
    var head = card('首次配置', '/api/config/*');
    var state = make('div', 'sy-note', '正在读取…');
    state.id = 'sy-su-state';
    head.appendChild(state);
    wrap.appendChild(head);
    wrap.appendChild(setupFieldCard());
    wrap.appendChild(setupChannelCard());
    wrap.appendChild(setupNoteCard());
    suRoot.appendChild(wrap);
    onStatus = null;
    loadSetup();
  }

  function setupFieldCard() {
    var node = card('基本信息', '改完点保存');
    node.appendChild(textField('机器人名字', textInput('sy-su-botname', '例如 神户小鸟', '')));
    node.appendChild(textField('owner QQ（1–20 位数字，可留空）', textInput('sy-su-owner', '例如 2070435720', '', 'text')));
    node.appendChild(textField('Stable Diffusion 地址（重启后生效）', textInput('sy-su-sd', '例如 http://127.0.0.1:7861', '')));
    node.appendChild(textField('Stable Diffusion 目录（重启后生效）', textInput('sy-su-sdroot', '例如 F:/sd/sd-webui-forge-neo', '')));
    node.appendChild(textField('NapCat 地址（ws:// 或 wss://，重启后生效）', textInput('sy-su-qqws', '例如 ws://127.0.0.1:3001', '')));
    node.appendChild(field('NapCat 令牌（留空 = 不改）', textInput('sy-su-qqtoken', '留空表示不修改', '', 'password')));
    node.appendChild(field('网页访问令牌（留空 = 不改，至少 8 位）', textInput('sy-su-webtoken', '留空表示不修改', '', 'password')));
    return node;
  }

  function setupChannelCard() {
    var node = card('DeepSeek 通道', '密钥留空 = 不改');
    node.appendChild(make('div', 'sy-note', '地址留空 = 用官方地址；密钥只写进机器人本机的密钥文件，网页不回显明文。'));
    node.appendChild(textField('生图通道地址', textInput('sy-su-imagebase', '', '')));
    node.appendChild(field('生图通道密钥（留空 = 不改）', textInput('sy-su-imagekey', '留空表示不修改', '', 'password')));
    node.appendChild(textField('聊天通道地址', textInput('sy-su-chatbase', '', '')));
    node.appendChild(field('聊天通道密钥（留空 = 不改）', textInput('sy-su-chatkey', '留空表示不修改', '', 'password')));
    var info = make('div', 'sy-note', '');
    info.id = 'sy-su-channels';
    node.appendChild(info);
    var actions = make('div', 'sy-actions');
    var testImage = button('sy-btn ghost', '测试生图通道', 'sy-su-test-image');
    var testChat = button('sy-btn ghost', '测试聊天通道', 'sy-su-test-chat');
    var reload = button('sy-btn ghost', '重新读取', 'sy-su-reload');
    function test(channel) {
      var out = byId('sy-su-channels');
      out.className = 'sy-note';
      out.textContent = '正在测试 ' + channel + ' 通道…';
      api().api('/api/config/test', { method: 'POST', body: { channel: channel } }).then(function (result) {
        out.className = 'sy-note ok';
        out.textContent = '测试结果：' + JSON.stringify(result);
      }, function (error) {
        out.className = 'sy-note bad';
        out.textContent = '测试失败：' + messageOf(error);
      });
    }
    testImage.addEventListener('click', function () { test('image'); });
    testChat.addEventListener('click', function () { test('chat'); });
    reload.addEventListener('click', function () { loadSetup(); });
    actions.appendChild(testImage);
    actions.appendChild(testChat);
    actions.appendChild(reload);
    node.appendChild(actions);
    return node;
  }

  function setupNoteCard() {
    var node = card('保存', '');
    var actions = make('div', 'sy-actions');
    var save = button('sy-btn', '保存配置', 'sy-su-save');
    save.addEventListener('click', function () {
      var out = byId('sy-su-state');
      out.className = 'sy-note';
      out.textContent = '正在保存…';
      api().api('/api/config/apply', { method: 'POST', body: setupPayload() }).then(function (saved) {
        fillSetup(saved);
        out.className = 'sy-note ok';
        out.textContent = String((saved && saved.notice) || '已保存。');
        toast('已保存');
      }, function (error) {
        out.className = 'sy-note bad';
        out.textContent = '保存失败：' + messageOf(error);
        toast('保存失败：' + messageOf(error));
      });
    });
    actions.appendChild(save);
    node.appendChild(actions);
    // 上/下线播报：Java 的 /api/config/apply 不收这个字段，它是 /api/settings 的 notice 键
    //（桌面版放在「聊天设置」里）。这里放一个同样的开关，省得为了一个播报开关来回跳屏。
    var noticeRow = switchRow('sy-su-notice', '上线 / 下线播报', false,
      '发到主群（与「聊天设置」里的同一个开关；这个接口不回显当前值，按需要设置一次）');
    var noticeBox = noticeRow.querySelector('input');
    noticeBox.addEventListener('change', function () {
      saveSetting('notice', noticeBox.checked ? 'on' : 'off', '上线播报');
    });
    node.appendChild(noticeRow);
    node.appendChild(make('div', 'sy-note', '启动群（startup_group_id）不在 /api/config/apply 能改的字段里，要去 config.json 或桌面控制台的「配置」栏目改。'));
    return node;
  }

  /** 只发要改的字段：与桌面版 webui/app.js 的 setupPayload() 完全一致（密钥留空 = 不改）。 */
  function setupPayload() {
    var body = { channels: {} };
    ['image', 'chat'].forEach(function (name) {
      var base = byId('sy-su-' + name + 'base');
      var key = byId('sy-su-' + name + 'key');
      var patch = { base: base ? String(base.value || '').trim() : '' };
      var secret = key ? String(key.value || '').trim() : '';
      if (secret) patch.key = secret;
      body.channels[name] = patch;
    });
    body.bot_name = valueOf('sy-su-botname');
    body.owner_user_id = valueOf('sy-su-owner');
    body.sd_base_url = valueOf('sy-su-sd');
    body.sd_root = valueOf('sy-su-sdroot');
    body.qq_ws_url = valueOf('sy-su-qqws');
    var qqToken = valueOf('sy-su-qqtoken');
    if (qqToken) body.qq_access_token = qqToken;
    var webToken = valueOf('sy-su-webtoken');
    if (webToken) body.webui_access_token = webToken;
    return body;
  }

  function valueOf(id) {
    var node = byId(id);
    return node ? String(node.value || '').trim() : '';
  }

  function loadSetup() {
    return api().api('/api/config/state', { method: 'POST', body: {} }).then(function (config) {
      fillSetup(config);
      return config;
    }, function (error) {
      var out = byId('sy-su-state');
      if (out) { out.className = 'sy-note bad'; out.textContent = '读取配置失败：' + messageOf(error); }
      return null;
    });
  }

  function fillSetup(config) {
    var values = (config && config.values) || {};
    setValue('sy-su-botname', values.bot_name);
    setValue('sy-su-owner', values.owner_user_id);
    setValue('sy-su-sd', values.sd_base_url);
    setValue('sy-su-sdroot', values.sd_root);
    setValue('sy-su-qqws', values.qq_ws_url);
    setValue('sy-su-qqtoken', '');
    setValue('sy-su-webtoken', '');
    var channels = values.channels || {};
    var image = channels.image || {};
    var chat = channels.chat || {};
    setValue('sy-su-imagebase', image.base);
    setValue('sy-su-imagekey', '');
    setValue('sy-su-chatbase', chat.base);
    setValue('sy-su-chatkey', '');
    var imageBase = byId('sy-su-imagebase');
    if (imageBase && image.official) imageBase.placeholder = image.official;
    var chatBase = byId('sy-su-chatbase');
    if (chatBase && chat.official) chatBase.placeholder = chat.official;

    var out = byId('sy-su-state');
    if (out && config) {
      out.className = 'sy-note' + (config.needed ? '' : ' ok');
      out.textContent = (config.needed ? '首次配置：还缺 ' + (config.missingText || '必要配置') + '本机访问免令牌，填好保存即可。'
        : '配置已完成：改完保存即可（DeepSeek 地址与密钥立刻生效，SD 与 NapCat 地址重启后生效）。');
    }
    var info = byId('sy-su-channels');
    if (info) {
      info.className = 'sy-note';
      info.textContent = [
        '生图通道：' + channelText(image),
        '聊天通道：' + channelText(chat),
        '网页令牌：' + (values.webui_token_set ? '已配置（' + (values.webui_host || '') + ':' + (values.webui_port || '') + '）' : '还没有令牌（保存时会自动生成）'),
        'NapCat 令牌：' + (values.qq_token_set ? '已配置' : '未配置')
      ].join('\n');
    }
  }

  function channelText(channel) {
    if (!channel) return '—';
    return (channel.keyMasked ? '已配置 ' + channel.keyMasked : '未配置密钥') + ' · 生效地址 ' + (channel.effective || '—');
  }

  function setValue(id, value) {
    var node = byId(id);
    if (!node) return;
    node.value = value === undefined || value === null ? '' : String(value);
    // 值是被代码写进去的（不是用户敲的），input/change 不会触发 —— 手动刷新那条"溢出才出现"的完整值行
    if (typeof node.__syncValueLine === 'function') node.__syncValueLine();
  }

  /* ================================================================ 服务器信息 */

  var svRoot = null;

  function renderServer(target) {
    svRoot = target || svRoot;
    if (!svRoot) return;
    clear(svRoot);
    var wrap = make('div', 'sy-wrap');
    wrap.appendChild(serverConnCard());
    wrap.appendChild(serverFieldsCard());
    wrap.appendChild(serverNoteCard());
    svRoot.appendChild(wrap);
    onStatus = function (status) { if (status) fillServerStatus(status); };
    loadServerStatus();
  }

  function serverConnCard() {
    var node = card('当前服务器', '只读');
    var token = '';
    try { token = String(api().token() || ''); } catch (error) { token = ''; }
    var scope = scopeOf();
    node.appendChild(kv('服务器地址', location.origin));
    node.appendChild(kv('访问令牌', token ? ('已配置（' + token.length + ' 位，不显示内容）') : '未配置', token ? 'ok' : 'bad'));
    node.appendChild(kv('令牌长度', token ? token.length + ' 位' : '0 位'));
    node.appendChild(kv('scope', scope || '（默认）'));
    var actions = make('div', 'sy-actions');
    var test = button('sy-btn', '测试连接', 'sy-sv-test');
    test.addEventListener('click', function () {
      var out = byId('sy-sv-result');
      out.className = 'sy-note';
      out.textContent = '正在测试…';
      api().api('/api/status', { method: 'POST', body: {} }).then(function (status) {
        fillServerStatus(status);
        out.className = 'sy-note ok';
        out.textContent = '连接正常：' + (status.botName || '机器人') + ' · 端口 ' + status.webPort + ' · 会话 ' + status.scope;
        toast('连接正常');
      }, function (error) {
        classify(error).then(function (text) {
          out.className = 'sy-note bad';
          out.textContent = text;
          toast(text);
        });
      });
    });
    var change = button('sy-btn ghost', '切换 scope', 'sy-sv-scope');
    change.addEventListener('click', function () {
      promptBox('切换 scope', scopeOf() || 'web', '例如 web / alice', '切换').then(function (next) {
        var value = String(next || '').trim();
        if (!value || value === scopeOf()) return;
        confirmBox('切换 scope', '把 scope 换成「' + value + '」？scope 决定个人提示词、样式关联与聊天历史的归属；换掉之后你会看到另一个会话的数据，令牌不变。', '切换', false)
          .then(function (ok) {
            if (!ok) return;
            try { api().setScope(value); } catch (error) { toast('切换失败：' + messageOf(error)); return; }
            toast('scope 已切到 ' + value);
            renderServer(svRoot);
          });
      });
    });
    actions.appendChild(test);
    actions.appendChild(change);
    node.appendChild(actions);
    var result = make('div', 'sy-note', '');
    result.id = 'sy-sv-result';
    node.appendChild(result);
    return node;
  }

  function serverFieldsCard() {
    var node = card('机器人 /api/status 关键字段', '');
    var body = make('div');
    body.id = 'sy-sv-body';
    body.appendChild(stateLine('正在读取…'));
    node.appendChild(body);
    return node;
  }

  function serverNoteCard() {
    var node = card('换服务器', '');
    node.appendChild(make('div', 'sy-note', '真正的服务器地址管理在 Android 外壳里：App 菜单 →「服务器设置」改地址与令牌，改完重新进入这个页面即可。这里只显示当前正在用的地址，避免在网页里改到自己连不上。'));
    var link = document.createElement('a');
    link.className = 'sy-link';
    link.href = '/';
    link.rel = 'noopener';
    link.textContent = '打开网页版完整控制台（备用入口）';
    node.appendChild(link);
    return node;
  }

  function loadServerStatus() {
    return api().api('/api/status', { method: 'POST', body: {} }).then(function (status) {
      fillServerStatus(status);
      return status;
    }, function (error) {
      var body = byId('sy-sv-body');
      if (body) { clear(body); body.appendChild(stateLine('读取失败：' + messageOf(error), true)); }
      return null;
    });
  }

  function fillServerStatus(status) {
    if (!status) return;
    var body = byId('sy-sv-body');
    if (!body) return;
    var chat = status.chat || {};
    clear(body);
    body.appendChild(kv('机器人', status.botName));
    body.appendChild(kv('网页端口', status.webPort));
    body.appendChild(kv('会话 scope', status.scope));
    body.appendChild(kv('聊天开关', chat.global ? '开启' : '关闭', chat.global ? 'ok' : 'bad'));
    body.appendChild(kv('回复上限', chat.frequency === undefined ? '—' : chat.frequency + ' 次/分钟'));
    body.appendChild(kv('SD 连接', (status.sd || {}).reachable ? '在线' : '离线', (status.sd || {}).reachable ? 'ok' : 'bad'));
    body.appendChild(kv('待领取图片', status.pendingImages === undefined ? '—' : status.pendingImages + ' 张'));
    body.appendChild(kv('图片上限', status.imageCount === undefined ? '—' : status.imageCount + ' 张/条'));
    body.appendChild(kv('回执未读', (status.quests || {}).unread === undefined ? 0 : (status.quests || {}).unread));
    body.appendChild(kv('服务器时间', timeText(status.time)));
    body.appendChild(kv('机器人目录', status.path, 'dim'));
  }

  /** 深链兜底：boot() 在 defer 脚本里就跑了，直开 #/system 之类会先落在「还没做好」占位上。 */
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

  /* ================================================================ 出图屏：VAE 与栈冲突（防呆）

     为什么写在这个文件里：手机端的「出图」屏（id `gen`）是在 `m/app.js` 里注册的，而
     本轮任务**不许改 `m/app.js`**，也不存在 `screen-gen.js`。`screen-system.js` 是
     `m/index.html` 已经加载、且已经拿 PixikoM 契约（el/clear/toast/confirm/api）的脚本，
     所以 VAE 选择与冲突横幅在这里实现，再挂到 `gen` 屏的 DOM 上：

       · VAE 一行：追加进出图的「生成参数」卡的 `[data-gen-params]`（与尺寸/步数/CFG 同一区）。
         卡里的参数是**整块重画**的（genRenderParams 每轮 clear 一遍），所以用 MutationObserver
         在每次重画之后把自己补回去，节点本身不重建（下拉焦点与选项不丢）。
       · 冲突横幅：插在 `#screen-gen` 的**最上面**（红条/黄条 + 「一键修复」），
         这条路径只在挂载时跑一次，不会被重画冲掉。

     服务端契约（字段已冻结，别的代理实现）：
       POST /api/sd/vae       → {vae, vaeAuto, modules[], checkpoint, choices[],
                                 conflict:{level:'OK|WARN|BLOCK', reason, suggestion, culprits[]}}
       POST /api/sd/vae/set   {name} → 同一份快照 + message
       POST /api/sd/vae/fix   → 同一份快照 + message + actions[]

     降级：接口 404 / 字段缺失 → 这一行置灰 + 一句人话，**不报错弹窗、不白屏**。
     事故背景：切 Forge 预设后 anima 的额外模块（qwen_image_vae + qwen_3_06b_base）没清掉，
     SDXL 底模配 Qwen 的 VAE → 出图纯灰。BLOCK 必须在出图屏顶部是一条醒目的红条。 */

  var VAE_MODULE_SHORT = 30;
  var vae = { ready: true, missing: false, last: null, error: '', busy: false, field: null, key: '',
    select: null, modulesBox: null, hint: null, conflict: null, observer: null, attached: false, timer: null };

  /** 服务端还没这个接口：`api()` 抛的是 404 +「未知接口：…」（WebApiController 的 default 分支）。 */
  function vaeMissingEndpoint(error) {
    var text = messageOf(error) + ' ' + String((error && error.status) || '');
    return /未知接口|未知的接口|HTTP 404|not found|404/i.test(text);
  }

  function vaeNamesOf(payload) {
    var list = (payload && payload.modules) || [];
    if (!(list instanceof Array)) return [];
    return list.map(function (item) { return String(item || ''); }).filter(Boolean);
  }

  /** 只认 WARN / BLOCK；OK 与字段缺失返回 null（界面上一点都不占位）。 */
  function vaeConflictOf(payload) {
    var raw = (payload && payload.conflict) || null;
    if (!raw || typeof raw !== 'object') return null;
    var level = String(raw.level || '').toUpperCase();
    if (level !== 'WARN' && level !== 'BLOCK') return null;
    var culprits = (raw.culprits instanceof Array ? raw.culprits : []).map(function (item) { return String(item || ''); }).filter(Boolean);
    return { level: level, reason: String(raw.reason || ''), suggestion: String(raw.suggestion || ''), culprits: culprits };
  }

  /* ---------------------------------------------------------- VAE 一行（在「生成参数」卡里） */

  function vaeFieldNode() {
    var wrap = make('div', 'sy-field vae-field');
    wrap.setAttribute('data-vae-field', '1');
    wrap.appendChild(make('span', null, 'VAE（可选，留 Automatic 就跟随底模）'));
    // 用出图屏既有的 `.input` 语言：44px 高、宽吃满卡片（触摸目标 ≥44px）
    var select = make('select', 'input');
    select.id = 'vae-select';
    select.setAttribute('aria-label', 'VAE');
    select.disabled = true;
    select.addEventListener('change', function () { setVae(select.value); });
    wrap.appendChild(select);
    var value = attachValueLine(select, '（还没读到 VAE 列表）');
    value.setAttribute('data-vae-value', '1');
    value.id = 'vae-value';
    var modulesBox = make('div', 'sy-note');
    modulesBox.id = 'vae-modules';
    modulesBox.hidden = true;
    wrap.appendChild(modulesBox);
    var hint = make('div', 'sy-note');
    hint.id = 'vae-hint';
    hint.hidden = true;
    wrap.appendChild(hint);
    vae.field = wrap; vae.select = select; vae.modulesBox = modulesBox; vae.hint = hint;
    return wrap;
  }

  /** 出图屏的参数卡里那个「参数宿主」；屏还没挂载时返回 null。 */
  function genParamsHost() {
    var root = document.getElementById('screen-gen');
    return root ? root.querySelector('[data-gen-params]') : null;
  }

  /** 把 VAE 那一行补进参数宿主（已经在里面就什么都不做）。 */
  function ensureVaeField() {
    var host = genParamsHost();
    if (!host) return null;
    if (!vae.field) vaeFieldNode();
    if (vae.field.parentNode !== host) host.appendChild(vae.field);
    return vae.field;
  }

  /**
   * 参数宿主是整块重画的：每次重画之后把 VAE 那一行补回去。
   *
   * <p>**这条回调必须是"纯幂等"的**：它自己改 DOM 又会把观察者叫醒（观察的是整棵 #m-main 的
   * childList + subtree），只要回调里有任何"每次都重建节点"的动作，就是死循环 —— 实测直接把
   * 渲染进程冻死（CDP 的 Runtime.evaluate 全部超时，页面再也醒不过来）。
   * 所以回调里只做两件幂等的事：① 行不在宿主里才 append；② 快照与画出来的一模一样就一个字都不改
   * （见 renderVae 的 currentKey 早退）。改完之后再没有新的 DOM 变更，观察者自然静默。
   */
  function watchGenParams() {
    if (vae.observer || typeof MutationObserver !== 'function') return;
    vae.observer = new MutationObserver(function () {
      ensureVaeField();
      renderVae(vae.last, vae.error);
    });
    vae.observer.observe(document.getElementById('m-main') || document.body, { childList: true, subtree: true });
  }

  function refreshVaeValueLine() {
    if (vae.select && typeof vae.select.__syncValueLine === 'function') {
      try { vae.select.__syncValueLine(); } catch (error) { /* 值行只是锦上添花 */ }
    }
  }

  /**
   * 画 VAE 一行。`payload` 为 null = 没读到（降级态或读失败）。
   *
   * <p>幂等：把"要画成什么样"压成一个指纹（`vae.key`），一样就直接返回 —— 一行 DOM 都不碰。
   * 这一条是**必需**的，不是优化：见 watchGenParams 的注释（不幂等 = 死循环）。
   */
  function renderVae(payload, errorText) {
    vae.error = errorText || '';
    if (!ensureVaeField()) return;
    var choices = ((payload && payload.choices) || []).map(function (item) { return String(item || ''); }).filter(Boolean);
    var current = payload ? String(payload.vae || '') : '';
    // choices 里可能有值对象（别的接口给的是 {name} 形状），这里统一按字符串收，缺了当前值就补一个。
    if (current && choices.indexOf(current) < 0) choices.push(current);
    var usable = !!(payload && choices.length);
    var modules = vaeNamesOf(payload);
    var hint = errorText || '';
    if (!vae.ready) hint = '这个机器人还没有 VAE 接口（服务端未就绪），这一行先不能用。';
    else if (payload && payload.vaeAuto && /automatic/i.test(current)) hint = 'VAE 正在跟随当前基础模型自动选。';
    var key = JSON.stringify([choices, current, usable, modules, hint]);
    if (key === vae.key) return;
    vae.key = key;

    clear(vae.select);
    choices.forEach(function (name) {
      var option = document.createElement('option');
      option.value = name;
      option.textContent = name;
      if (name === current) option.selected = true;
      vae.select.appendChild(option);
    });
    if (current) vae.select.value = current;
    vae.select.disabled = !usable;

    // 额外模块：一个短名字直接列出来；多个或很长就折叠成「共 N 个」（全名放 title，别把信息吞掉）
    var shortAll = modules.every(function (item) { return item.length <= VAE_MODULE_SHORT; });
    if (!modules.length) {
      vae.modulesBox.hidden = true;
      vae.modulesBox.textContent = '';
    } else {
      vae.modulesBox.hidden = false;
      vae.modulesBox.textContent = (modules.length === 1 && shortAll)
        ? '额外模块：' + modules[0]
        : '额外模块：共 ' + modules.length + ' 个' + (shortAll ? '（' + modules.join('、') + '）' : '');
      vae.modulesBox.title = modules.join('、');
    }

    vae.hint.textContent = hint;
    vae.hint.hidden = !hint;
    refreshVaeValueLine();
  }

  /**
   * 拉一次快照。缺 `choices` 时用 `/api/sd/vae/list` 补；接口不存在就整体降级。
   * `silent` 给 30 秒的背景轮询用：不重复 toast。
   */
  function loadVae(silent) {
    var M = api();
    if (!M || typeof M.api !== 'function') return Promise.resolve(null);
    return M.api('/api/sd/vae', { method: 'POST', body: { scope: scopeOf() } }).then(function (data) {
      var payload = data || {};
      vae.ready = true; vae.missing = false; vae.last = payload;
      renderVae(payload);
      if (payload.choices instanceof Array && payload.choices.length) return payload;
      return M.api('/api/sd/vae/list', { method: 'POST', body: { scope: scopeOf() } }).then(function (extra) {
        if (extra && extra.choices instanceof Array && extra.choices.length) {
          payload.choices = extra.choices;
          vae.last = payload;
          renderVae(payload);
        }
        return payload;
      }, function () { return payload; });        // 补不上就照旧：一个选项也能用
    }, function (error) {
      if (String((error && error.code) || '') === 'unauthorized') return null;
      vae.ready = false; vae.last = null;
      if (vaeMissingEndpoint(error)) {
        vae.missing = true;
        renderVae(null);
        return null;
      }
      renderVae(null, '读取 VAE 失败：' + messageOf(error));
      if (!silent) toast('读取 VAE 失败：' + messageOf(error));
      return null;
    });
  }

  /** 改选 VAE：POST /api/sd/vae/set {name}，成功后 toast 服务端的 message 并刷新快照。 */
  function setVae(name) {
    var wanted = String(name == null ? '' : name);
    if (!wanted || vae.busy) return;
    var M = api();
    vae.busy = true;
    vae.select.disabled = true;
    M.api('/api/sd/vae/set', { method: 'POST', body: { name: wanted, scope: scopeOf() } }).then(function (data) {
      vae.ready = true; vae.missing = false; vae.last = data || null;
      if (data) renderVae(data);
      toast((data && data.message) ? String(data.message) : ('VAE 已切到 ' + wanted));
      vae.busy = false;
      loadVae(true);
    }, function (error) {
      vae.busy = false;
      vae.select.disabled = false;
      if (String((error && error.code) || '') === 'unauthorized') return;
      if (vaeMissingEndpoint(error)) {
        vae.ready = false; vae.missing = true;
        toast('这个机器人还没有 VAE 接口（服务端未就绪），改不了。');
        renderVae(null);
        return;
      }
      toast('切换 VAE 失败：' + messageOf(error));
      loadVae(true);
    });
  }

  /* ---------------------------------------------------------- 冲突横幅（出图屏最上面） */

  /**
   * 冲突横幅：WARN 黄条、BLOCK 红条 + 「一键修复」。
   * 挂在 `#screen-gen` 的第一个子节点上（挂载时一次）；OK / 缺字段整块 hidden，不占位。
   */
  function ensureVaeConflict(root) {
    if (!root) root = document.getElementById('screen-gen');
    if (!root) return null;
    if (vae.conflict && vae.conflict.parentNode === root) return vae.conflict;
    var box = make('div', 'sy-card vae-alert');
    box.id = 'vae-conflict';
    box.setAttribute('data-vae-conflict', '1');
    box.hidden = true;
    root.insertBefore(box, root.firstChild || null);
    vae.conflict = box;
    return box;
  }

  /** 红条里的「一键修复」：**先 PixikoM.confirm 二次确认**，确认了才发 /api/sd/vae/fix。 */
  function confirmVaeFix(reason) {
    return confirmBox('一键修复栈冲突？',
      '会清掉与当前底模不匹配的额外模块（VAE / 文本编码器），并把 VAE 设回 Automatic，修复完立刻生效。\n\n'
      + (reason || '检测到栈冲突'),
      '修复', true);
  }

  function vaeActionText(action) {
    var text = String(action == null ? '' : action);
    var MAP = {
      clear_modules: '已清空额外模块', clear_extra_modules: '已清空额外模块',
      set_vae_auto: 'VAE 已设为 Automatic', set_vae_automatic: 'VAE 已设为 Automatic',
      reload_checkpoint: '已重载底模', reload_model: '已重载底模', reset_vae: 'VAE 已重置',
      clear_vae: '已清空 VAE 选择', refresh: '已刷新', apply_preset: '已重新应用预设'
    };
    return MAP[text] || text;
  }

  function fixVae(button) {
    var M = api();
    var conflict = vaeConflictOf(vae.last);
    confirmVaeFix(conflict && conflict.reason).then(function (yes) {
      if (!yes) return null;
      if (button) button.disabled = true;
      return M.api('/api/sd/vae/fix', { method: 'POST', body: { scope: scopeOf() } }).then(function (data) {
        vae.ready = true; vae.missing = false; vae.last = data || null;
        renderVae(data || null);
        renderVaeConflict(data || null);        // 红条必须跟着修复结果收掉（少了这句，修好了红条还挂在那儿）
        var actions = (data && data.actions instanceof Array ? data.actions : []).map(vaeActionText).filter(Boolean);
        toast((data && data.message) ? String(data.message) : '已修复栈冲突');
        if (actions.length) toast('已执行：' + actions.slice(0, 2).join('；'));
        return data;
      }, function (error) {
        if (button) button.disabled = false;
        if (String((error && error.code) || '') === 'unauthorized') return null;
        if (vaeMissingEndpoint(error)) {
          vae.ready = false; vae.missing = true;
          toast('这个机器人还没有一键修复接口（服务端未就绪）。');
          renderVae(null);
          renderVaeConflict(null);
          return null;
        }
        toast('修复失败：' + messageOf(error));
        return null;
      });
    });
  }

  /** 按一份快照画冲突横幅（WARN 黄 / BLOCK 红 + 修复键）。 */
  function renderVaeConflict(payload, errorText) {
    var box = ensureVaeConflict();
    if (!box) return;
    clear(box);
    box.className = 'sy-card vae-alert';
    box.removeAttribute('data-vae-level');
    var conflict = vaeConflictOf(payload);
    if (!conflict) {
      box.hidden = true;
      return;
    }
    box.hidden = false;
    box.setAttribute('data-vae-level', conflict.level);
    box.classList.add(conflict.level === 'BLOCK' ? 'vae-alert-block' : 'vae-alert-warn');
    box.appendChild(make('b', 'vae-alert-title',
      conflict.level === 'BLOCK' ? '⛔ 栈冲突，会出灰图' : '⚠ 可能有冲突'));
    box.appendChild(make('div', 'vae-alert-line',
      conflict.reason || '检测到额外模块与当前底模不匹配'));
    if (conflict.suggestion) box.appendChild(make('div', 'vae-alert-line', '建议：' + conflict.suggestion));
    if (conflict.culprits.length) box.appendChild(make('div', 'vae-alert-line vae-alert-dim', '可疑项：' + conflict.culprits.join('、')));
    if (errorText) box.appendChild(make('div', 'vae-alert-line vae-alert-dim', errorText));
    if (conflict.level === 'BLOCK') {
      var fix = button('btn danger vae-fix', '一键修复');
      fix.id = 'vae-fix';
      fix.setAttribute('data-vae-fix', '1');
      fix.addEventListener('click', function () { fixVae(fix); });
      box.appendChild(fix);
    }
  }

  /** 出图屏挂载后接手：先建冲突槽，再灌数据、装观察者、起 30 秒的背景轮询。 */
  function attachGenVae() {
    var root = document.getElementById('screen-gen');
    if (!root || vae.attached) return !!vae.attached;
    vae.attached = true;
    ensureVaeConflict(root);
    ensureVaeField();
    watchGenParams();
    loadVae(true).then(function (payload) {
      renderVaeConflict(payload, vae.ready ? '' : undefined);
    });
    vae.timer = setInterval(function () {
      if (document.hidden) return;
      var main = document.getElementById('m-main');
      if (!main || !root.classList.contains('active')) return;
      loadVae(true).then(function (payload) { renderVaeConflict(payload); });
    }, 30000);
    return true;
  }

  /** 出图屏是别的文件注册的、root 要等用户第一次进那一屏才有：轮询等它出现（最多约 20 秒）。 */
  function awaitGenScreen() {
    var tries = 0;
    function tick() {
      if (attachGenVae()) return;
      tries += 1;
      if (tries > 100) return;                    // 一直没进过出图屏：什么都不做（下次进也不会漏，mount 会再触发）
      setTimeout(tick, 200);
    }
    tick();
  }

  /* ---------------------------------------------------------------- 注册 */

  injectStyle();
  whenReady(function (M) {
    M.register('system', {
      title: '系统',
      mount: function (target) { renderSystem(target); },
      refresh: function (target) { renderSystem(target || syRoot); }
    });
    M.register('chatcfg', {
      title: '聊天设置',
      mount: function (target) { renderChatCfg(target); },
      refresh: function (target) { renderChatCfg(target || ccRoot); }
    });
    M.register('setup', {
      title: '首次配置',
      mount: function (target) { renderSetup(target); },
      refresh: function (target) { renderSetup(target || suRoot); }
    });
    M.register('server', {
      title: '服务器信息',
      mount: function (target) { renderServer(target); },
      refresh: function (target) { renderServer(target || svRoot); }
    });
    claimRoute('system');
    claimRoute('chatcfg');
    claimRoute('setup');
    claimRoute('server');
    // 出图屏的 VAE 与栈冲突（防呆）：屏本体由 m/app.js 注册（本文件不改它），这里等它挂载后接手。
    awaitGenScreen();
  });
})();
