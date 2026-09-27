// Web 控制台功能自检：直接打内部 /api 接口（不走机器人公用指令通道），能并行的并行。
//
// 覆盖：鉴权与静态资源、全部只读接口、设置写接口（幂等回写原值）、指令通道与回执生命周期、
// 错误处理（未知接口/空指令/超长批量/路径穿越）、聊天规划接口，以及前端调用的接口与后端路由是否一一对应。
//
// 用法：
//   node tools/webui-selfcheck.mjs
//   node tools/webui-selfcheck.mjs --base http://127.0.0.1:8787 --token <你的 webui.access_token>
//   node tools/webui-selfcheck.mjs --out work/webui-selfcheck.md --json work/webui-selfcheck.json
//
// 原则：只读 + 幂等。不发送任何会改变提示词、样式、队列或配置的命令；聊天检查用一个临时 scope 并在结束时清理。
import fs from 'node:fs';
import path from 'node:path';

const REPO = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..');
const argv = process.argv.slice(2);
const value = (name, fallback) => {
  const at = argv.indexOf(name);
  return at >= 0 && argv[at + 1] ? argv[at + 1] : fallback;
};
const BASE = value('--base', 'http://127.0.0.1:8787').replace(/\/$/, '');
const TOKEN = value('--token', process.env.PIXIKO_WEBUI_TOKEN ?? '');
// 令牌必须由使用者提供：仓库里不留任何真实令牌，留空时直接退出而不是让几十项检查
// 全部以 401 失败（那样看不出真正的原因）。
if (!TOKEN) {
  console.error('缺少访问令牌：请用 --token <config.json 里的 webui.access_token>，或设置环境变量 PIXIKO_WEBUI_TOKEN。');
  process.exit(2);
}
const OUT = path.resolve(REPO, value('--out', 'work/webui-selfcheck.md'));
const JSON_OUT = path.resolve(REPO, value('--json', 'work/webui-selfcheck.json'));
const TIMEOUT = Number(value('--timeout', '60000'));
const PROBE_SCOPE = value('--probe-scope', 'selfcheck');

const results = [];
const recorded = (group, name, ok, detail, ms) => {
  results.push({ group, name, ok, ms, detail: String(detail ?? '') });
  const flag = ok ? 'PASS' : 'FAIL';
  console.log(`[${flag}] ${group} · ${name}${ok ? '' : ' → ' + detail}${ms != null ? ` (${ms}ms)` : ''}`);
};

/** 单个检查：label + 异步函数（返回详情字符串，抛错即失败）。 */
async function check(group, name, fn) {
  const started = Date.now();
  try {
    const detail = await fn();
    recorded(group, name, true, detail ?? '', Date.now() - started);
    return true;
  } catch (error) {
    recorded(group, name, false, error.message, Date.now() - started);
    return false;
  }
}

const assert = (condition, message) => { if (!condition) throw new Error(message); };

async function request(pathname, { method = 'GET', body, token = TOKEN, raw = false } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json; charset=utf-8';
  const response = await fetch(BASE + pathname, {
    method, headers,
    body: body === undefined ? undefined : (typeof body === 'string' ? body : JSON.stringify(body)),
    signal: AbortSignal.timeout(TIMEOUT),
  });
  if (raw) return response;
  const text = await response.text();
  let payload = null;
  try { payload = text ? JSON.parse(text) : null; } catch { payload = { __text: text }; }
  return { status: response.status, headers: response.headers, body: payload, text };
}

const api = async (pathname, body, options = {}) => {
  const noBody = body === undefined || body === null;
  const result = await request(pathname, { method: noBody ? 'GET' : 'POST', body: noBody ? undefined : body, ...options });
  assert(result.status === 200, `${pathname} 期望 200，实际 ${result.status}：${snippet(result)}`);
  return result.body;
};
const expectError = async (pathname, body, statuses, label) => {
  const result = await request(pathname, { method: 'POST', body });
  assert(statuses.includes(result.status), `${label} 期望 ${statuses.join('/')}，实际 ${result.status}：${snippet(result)}`);
  assert(result.body && result.body.error, `${label} 应返回 {error}：${snippet(result)}`);
  return result.body.error;
};
const snippet = (result) => String(result?.text ?? JSON.stringify(result?.body ?? '')).replace(/\s+/g, ' ').slice(0, 160);

// ---------------------------------------------------------------- 1. 鉴权与静态资源

async function authAndStatic() {
  await check('鉴权', '/healthz 免令牌可用', async () => {
    const result = await request('/healthz', { token: '' });
    assert(result.status === 200 && result.body?.ok === true, `实际 ${result.status}`);
    return 'ok:true';
  });
  await check('鉴权', '无令牌访问 /api/status → 401', async () => {
    const result = await request('/api/status', { token: '' });
    assert(result.status === 401, `实际 ${result.status}`);
    return result.body?.error || '';
  });
  await check('鉴权', '错误令牌 → 401', async () => {
    const result = await request('/api/status', { token: 'definitely-wrong-token' });
    assert(result.status === 401, `实际 ${result.status}`);
    return result.body?.error || '';
  });
  await check('鉴权', '?token= 查询串同样可用（图片 <img> 用）', async () => {
    const result = await request(`/api/status?token=${encodeURIComponent(TOKEN)}`, { token: '' });
    assert(result.status === 200, `实际 ${result.status}`);
    return 'ok';
  });
  await check('静态资源', '登录页 / 可无令牌打开且是 Pixiko 控制台', async () => {
    const result = await request('/', { token: '' });
    assert(result.status === 200, `实际 ${result.status}`);
    assert(result.text.includes('Pixiko'), '首页缺少 Pixiko 标题');
    assert(result.text.includes('viewport'), '首页缺少移动端 viewport');
    return 'index.html';
  });
  await check('静态资源', '每个栏目一个页面：只用本栏目的面板 + 10 个页签链接', async () => {
    const tabs = ['chat', 'gen', 'prompt', 'styles', 'loras', 'functions', 'chatcfg', 'system', 'setup', 'logs', 'help'];
    const panels = tabs.map((tab) => `panel-${tab}`);
    const seen = new Map();
    for (const tab of tabs) {
      const path = tab === 'chat' ? '/' : `/${tab}`;
      const result = await request(path, { token: '' });
      assert(result.status === 200, `${path} 实际 ${result.status}`);
      assert(result.text.includes(`id="panel-${tab}"`), `${path} 缺少自己的面板 panel-${tab}`);
      // Next 版把 Java 的 `window.PIXIKO_PAGE = "…"` 换成了外壳上的 `data-page="…"`（同一个作用）。
      assert(result.text.includes(`data-page="${tab}"`), `${path} 缺少页面标记 data-page="${tab}"`);
      assert(result.text.includes(`class="tab active" data-tab="${tab}"`), `${path} 当前页签没有高亮`);
      for (const other of panels) {
        if (other === `panel-${tab}`) continue;
        assert(!result.text.includes(`id="${other}"`), `${path} 里混进了别的栏目 ${other}`);
      }
      const linked = tabs.filter((each) => result.text.includes(`data-tab="${each}"`)).length;
      assert(linked === tabs.length, `${path} 页签不全（${linked}/${tabs.length}）`);
      seen.set(tab, result.text.length);
    }
    return `10 个页面，单页 ${Math.min(...seen.values())}–${Math.max(...seen.values())} 字节`;
  });
  await check('静态资源', '样式表与脚本产物可取用（Next 构建产物）', async () => {
    const index = await request('/', { token: '' });
    const cssHref = (index.text.match(/<link[^>]+rel="stylesheet"[^>]+href="([^"]+)"/) || [])[1];
    assert(cssHref, '首页没有引用样式表');
    const css = await request(cssHref, { token: '' });
    assert(css.status === 200 && css.text.includes('border-radius'), `${cssHref} ${css.status}`);
    const sources = [...index.text.matchAll(/<script[^>]+src="([^"]+)"/g)].map((match) => match[1]);
    assert(sources.length > 0, '首页没有引用脚本产物');
    let hits = 0;
    for (const source of sources) {
      const chunk = await request(source, { token: '' });
      assert(chunk.status === 200, `${source} ${chunk.status}`);
      if (chunk.text.includes('/api/')) hits += 1;
    }
    assert(hits > 0, '没有任何脚本产物里出现 /api/ —— 前端可能没有打包进接口调用');
    return `${Math.round(css.text.length / 1024)}KB css + ${sources.length} 个 chunk（${hits} 个含 /api/）`;
  });
  await check('静态资源', '站点图标可取用（favicon.ico 是 ICO）', async () => {
    const index = await request('/', { token: '' });
    assert(index.text.includes('/favicon-32.png'), '首页没有引用 favicon-32.png');
    assert(index.text.includes('/apple-touch-icon.png'), '首页没有引用 apple-touch-icon');
    for (const path of ['/favicon-32.png', '/icon-192.png', '/apple-touch-icon.png']) {
      const response = await request(path, { token: '', raw: true });
      const bytes = Buffer.from(await response.arrayBuffer());
      assert(response.status === 200 && bytes.length > 1000, `${path} ${response.status}`);
      assert((response.headers.get('content-type') || '').startsWith('image/png'), `${path} 不是 PNG`);
      assert(bytes[0] === 0x89 && bytes[1] === 0x50, `${path} PNG 文件头不对`);
    }
    const iconResponse = await request('/favicon.ico', { token: '', raw: true });
    const icon = Buffer.from(await iconResponse.arrayBuffer());
    const type = iconResponse.headers.get('content-type') || '';
    assert(iconResponse.status === 200, `favicon.ico ${iconResponse.status}`);
    assert(type.startsWith('image/x-icon'), `favicon.ico Content-Type ${type}`);
    assert(icon.length > 1000 && icon[0] === 0 && icon[1] === 0 && icon[2] === 1 && icon[3] === 0, 'favicon.ico 不是 ICO 文件头');
    return `${Math.round(icon.length / 1024)}KB ico + 3 个 PNG`;
  });
  await check('静态资源', '静态目录穿越被拒', async () => {
    const result = await request('/../config.json', { token: '' });
    assert(result.status === 404, `实际 ${result.status}`);
    return '404';
  });
}

// ---------------------------------------------------------------- 2. 只读接口（并行）

async function readEndpoints(status) {
  const scope = status.scope;
  const jobs = [
    ['/api/status', null, (body) => {
      assert(body.scope && body.botName, '缺少 scope/botName');
      assert(body.scope === 'web', `网页应使用独立 scope web，实际 ${body.scope}`);
      assert(!('ownerId' in body) && !('admins' in body) && !('maps' in body), '状态里不该再有 QQ 侧身份信息');
      assert(body.generation && typeof body.generation.width === 'number', '缺少 generation.width');
      assert(body.chatChannel && body.imageChannel, '缺少两条 DeepSeek 通道');
      assert(body.civitai && 'hasCookie' in body.civitai, '缺少 civitai 状态');
      return `${body.botName} · ${body.generation.width}×${body.generation.height}`;
    }],
    ['/api/options', null, (body) => {
      assert(Array.isArray(body.samplers) && body.samplers.length, '缺少 samplers');
      assert(Array.isArray(body.models), '缺少 models');
      return `${body.samplers.length} 采样器 / ${body.models.length} 模型`;
    }],
    ['/api/help', null, (body) => {
      assert(typeof body.help === 'string' && body.help.includes('.infix'), 'help 缺少 .infix');
      return `${body.help.split('\n').length} 行`;
    }],
    ['/api/prompt', { scope }, (body) => {
      assert(body.scope === scope, '`scope` 不一致');
      assert(typeof body.positive === 'string' && typeof body.negative === 'string', '缺少正反向文本');
      assert(Array.isArray(body.positiveTerms) && Array.isArray(body.negativeTerms), '缺少词条数组');
      assert(typeof body.canUndo === 'number', '缺少 canUndo');
      assert(!('loadedStyles' in body), '仍然返回已废除的 loadedStyles');
      return `${body.positiveTerms.length} 正 / ${body.negativeTerms.length} 反，可回退 ${body.canUndo}`;
    }],
    ['/api/styles', null, (body) => {
      assert(Array.isArray(body.styles) && typeof body.library === 'number', '缺少 styles/library');
      assert(!('loaded' in body), '仍然返回已废除的 loaded');
      const first = body.styles[0];
      if (first) assert(typeof first.number === 'number' && first.name, '条目缺少 number/name');
      return `${body.library} 个样式`;
    }],
    ['/api/loras', null, (body) => {
      assert(Array.isArray(body.loras), '缺少 loras');
      return `${body.loras.length} 个 LoRA`;
    }],
    ['/api/functions', { scope }, (body) => {
      assert(Array.isArray(body.functions), '缺少 functions');
      return `${body.functions.length} 个提示词集`;
    }],
    ['/api/presets', null, (body) => {
      assert(Array.isArray(body.presets), '缺少 presets');
      return `${body.presets.length} 个预设`;
    }],
    ['/api/images', { limit: 60 }, (body) => {
      assert(Array.isArray(body.images), '缺少 images');
      for (const image of body.images) assert(image.path && image.name, '图片条目缺少 path/name');
      return `${body.images.length} 张待领取`;
    }],
    ['/api/logs', { lines: 80, source: 'all' }, (body) => {
      assert(Array.isArray(body.lines), '缺少 lines');
      return `${body.lines.length} 行（全部）`;
    }],
    ['/api/logs', { lines: 80, source: 'qq' }, (body) => {
      assert(Array.isArray(body.lines), '缺少 lines');
      return `${body.lines.length} 行（QQ 侧）`;
    }],
    ['/api/logs', { lines: 80, source: 'web' }, (body) => {
      assert(Array.isArray(body.lines), '缺少 lines');
      return `${body.lines.length} 行（网页侧）`;
    }],
    ['/api/civitai/status', null, (body) => {
      assert('hasCookie' in body && 'host' in body && 'fallbackHost' in body, '缺少 Civitai 账号状态字段');
      return `${body.host}${body.hasCookie ? '（已登录）' : '（未配置，回退 ' + body.fallbackHost + '）'}`;
    }],
    ['/api/usage', { query: '' }, (body) => {
      assert(typeof body.text === 'string' && body.text.length, '缺少 text');
      assert(Array.isArray(body.choices), '缺少 choices');
      return `${body.text.split('\n').length} 行 / ${body.choices.length} 个选项`;
    }],
    ['/api/usage', { query: '词库' }, (body) => {
      assert(body.text.includes('分类') || body.text.includes('词条'), '词库概览缺少分类信息');
      return snippet({ text: body.text });
    }],
    ['/api/usage', { query: '搜索 分镜' }, (body) => {
      assert(body.text.includes('#1'), '搜索结果缺少编号');
      return snippet({ text: body.text });
    }],
    ['/api/usage', { query: '词条 chikan' }, (body) => {
      assert(body.text.length > 0, '词条用法为空');
      return snippet({ text: body.text });
    }],
    ['/api/usage', { query: '不存在的词条zzz' }, (body) => {
      assert(typeof body.text === 'string', '未知查询应当也返回文本');
      return snippet({ text: body.text });
    }],
    ['/api/tags', { query: '1gi', limit: 5 }, (body) => {
      assert(Array.isArray(body.tags) && body.tags.length, '补全候选为空（词库装了吗？）');
      assert(body.tags[0].tag && 'rank' in body.tags[0], '候选缺少 tag/rank');
      return body.tags.map((item) => item.tag).join('、');
    }],
    ['/api/tags', { query: '长发', limit: 5 }, (body) => {
      assert(body.tags.some((item) => item.zh), '中文查询应当带回中文写法');
      return snippet({ text: body.tags.map((item) => item.tag + (item.zh ? '/' + item.zh : '')).join('、') });
    }],
    ['/api/tags', { query: '', limit: 3 }, (body) => {
      assert(body.tags.length === 3, `空查询应当返回最热的 3 条，实际 ${body.tags.length}`);
      return body.tags.map((item) => item.tag).join('、');
    }],
    ['/api/chat/history', { scope }, (body) => {
      assert(Array.isArray(body), '聊天历史应为数组');
      return `${body.length} 条`;
    }],
  ];
  await parallel(jobs.map(([pathname, body, validate]) => () => check('只读接口', `${pathname}${body && body.query !== undefined ? ' ' + body.query : ''}`, async () => {
    const payload = await api(pathname, body);
    return validate(payload);
  })));
}

// ---------------------------------------------------------------- 3. 设置写接口（幂等）

async function settingsWrites(status) {
  const chat = status.chat || {};
  const jobs = [
    ['chatFrequency', String(chat.frequency ?? 6), '频率'],
    ['chatThinking', status.chatChannel?.thinking ? 'on' : 'off', '聊天思考开关'],
    ['imageThinking', status.imageChannel?.thinking ? 'on' : 'off', '生图思考开关'],
    ['infixFilter', status.infixFilter ? 'on' : 'off', '词库约束'],
    ['chatModel', status.chatChannel?.model || '', '聊天模型'],
    ['imageModel', status.imageChannel?.model || '', '生图模型'],
  ].filter(([, value]) => value !== '' && value != null);
  await parallel(jobs.map(([key, value, label]) => () => check('设置接口', `${key} ← 原值回写（${label}）`, async () => {
    const body = await api('/api/settings', { key, value, scope: status.scope });
    assert(body && body.scope, '设置后应返回最新状态');
    return `已回写 ${value}`;
  })));
  await check('设置接口', '未知设置项被拒', async () => {
    const message = await expectError('/api/settings', { key: 'notAKey', value: 'x' }, [400], '未知设置项');
    return message;
  });
  await check('设置接口', '非法频率被拒（abc）', async () => {
    const message = await expectError('/api/settings', { key: 'chatFrequency', value: 'abc' }, [400], '非法频率');
    return message;
  });
  await check('设置接口', '超范围频率被拒（9999）', async () => {
    const message = await expectError('/api/settings', { key: 'chatFrequency', value: '9999' }, [400], '超范围频率');
    return message;
  });
  await check('设置接口', '过短令牌被拒且不改令牌', async () => {
    const message = await expectError('/api/settings', { key: 'token', value: 'short' }, [400], '过短令牌');
    const again = await api('/api/status');
    assert(again && again.scope, '令牌未被误改（原令牌仍然可用）');
    return message;
  });
}

// ---------------------------------------------------------------- 3.5 内部面板接口（纯本机操作，不拼指令）

/** 这些功能完全在机器人这边（个人 prompt / 提示词集 / 样式库 / 参数预设），网页直接调内部接口。 */
async function internalPanels(status) {
  const scope = status.scope;
  await check('内部接口', '/api/prompt/edit 加词条并按原文删掉（可逆）', async () => {
    const probe = 'selfcheck-probe-term';
    const added = await api('/api/prompt/edit', { side: 'positive', action: 'add', value: probe, scope });
    assert((added.positive || '').includes(probe), `加词条后应出现在 prompt 里：${added.positive}`);
    assert(added.message && added.message.includes('已添加'), `应带回执：${added.message}`);
    const removed = await api('/api/prompt/edit', { side: 'positive', action: 'remove', value: probe, scope });
    assert(!(removed.positive || '').includes(probe), `删掉后不应还在：${removed.positive}`);
    assert(Array.isArray(removed.positiveItems), '应返回刷新后的词条条目');
    return '加 → 删';
  });
  await check('内部接口', '/api/functions/edit 保存/加载/移出/删除（自建自清）', async () => {
    const name = '自检探针集';
    const saved = await api('/api/functions/edit', { action: 'save', name, scope });
    assert(names(saved.functions).includes(name), `保存后应出现在列表里：${names(saved.functions)}`);
    const loaded = await api('/api/functions/edit', { action: 'load', name, scope });
    assert((loaded.active || []).includes(name), `加载后 active 应包含它：${loaded.active}`);
    const removed = await api('/api/functions/edit', { action: 'remove', name, scope });
    assert(!(removed.active || []).includes(name), `移出后 active 不应包含它：${removed.active}`);
    const deleted = await api('/api/functions/edit', { action: 'delete', name, scope });
    assert(!names(deleted.functions).includes(name), `删除后列表里不该还有：${names(deleted.functions)}`);
    return '保存 → 加载 → 移出 → 删除';
  });
  await check('内部接口', '/api/styles/edit 保存/改名/删除（自建自清）', async () => {
    const name = '自检探针样式';
    const saved = await api('/api/styles/edit', { action: 'save', name, scope });
    assert(names(saved.styles).includes(name), `保存后应出现在样式库里：${names(saved.styles)}`);
    const renamed = await api('/api/styles/edit', { action: 'rename', name, newName: name + '二', scope });
    assert(names(renamed.styles).includes(name + '二'), `改名后应出现新名字：${names(renamed.styles)}`);
    const deleted = await api('/api/styles/edit', { action: 'delete', name: name + '二', scope });
    assert(!names(deleted.styles).includes(name + '二'), `删除后不该还在：${names(deleted.styles)}`);
    return '保存 → 改名 → 删除';
  });
  await check('内部接口', '/api/presets/edit 保存/删除（自建自清）', async () => {
    const name = '自检探针预设';
    const saved = await api('/api/presets/edit', { action: 'save', name });
    assert(names(saved.presets).includes(name), `保存后应出现在预设列表里：${names(saved.presets)}`);
    const deleted = await api('/api/presets/edit', { action: 'remove', name });
    assert(!names(deleted.presets).includes(name), `删除后不该还在：${names(deleted.presets)}`);
    return '保存 → 删除';
  });
  await check('内部接口', '/api/meanings 词库命中 + 认不出的词条如实列出（learn=false 不花钱）', async () => {
    const known = await api('/api/meanings', { terms: ['blonde hair', 'long hair'], learn: false });
    assert(known.meanings && typeof known.meanings === 'object', '应返回 meanings 对象');
    assert(Object.keys(known.meanings).length >= 1, `常用词条应能查到释义：${JSON.stringify(known.meanings)}`);
    const miss = await api('/api/meanings', { terms: ['自检不存在的词条zzz'], learn: false });
    assert((miss.unresolved || []).includes('自检不存在的词条zzz'), `未命中应出现在 unresolved：${JSON.stringify(miss)}`);
    assert(miss.asked === 0, `learn=false 不应问模型：${miss.asked}`);
    return `${Object.keys(known.meanings).length} 个命中`;
  });
  await check('内部接口', 'Civitai 搜索/封面代理的输入校验（不发网络请求）', async () => {
    const empty = await expectError('/api/civitai/search', { query: '' }, [400], '空搜索词');
    const badHost = await expectError('/api/civitai/thumb', { url: 'https://example.com/a.jpg' }, [400], '非 Civitai 图床');
    const missing = await expectError('/api/civitai/thumb', { url: '' }, [400], '缺少封面地址');
    return `${empty} / ${badHost} / ${missing}`;
  });
  await check('内部接口', '写接口要求 POST（GET 被拒）', async () => {
    const result = await request('/api/prompt/edit', { method: 'GET' });
    assert(result.status === 400, `期望 400，实际 ${result.status}：${snippet(result)}`);
    return result.body?.error || '400';
  });
  await check('内部接口', '未知操作被拒（不静默改数据）', async () => {
    const message = await expectError('/api/prompt/edit', { side: 'positive', action: '不存在的操作' }, [400], '未知 prompt 操作');
    return message;
  });
}

const names = (items) => (items || []).map((item) => item.name);

// ---------------------------------------------------------------- 4. 指令通道与回执

async function waitCapture(id, { timeout = 30000 } = {}) {
  const deadline = Date.now() + timeout;
  let capture = null;
  while (Date.now() < deadline) {
    const result = await request('/api/capture', { method: 'POST', body: { id } });
    if (result.status !== 200) return { gone: true, texts: capture?.texts || [] };
    capture = result.body;
    if ((capture.texts || []).length > 0 && !capture.busy) return capture;
    await new Promise((resolve) => setTimeout(resolve, 350));
  }
  return capture;
}

/** 指令通道是串行的（同一会话同时只允许一条指令），所以这一组必须顺序执行。 */
async function commandChannel() {
  const readOnly = ['.help', '.settings', '.style list', '.lora list', '.function list', '.preset list', '.gen status', '.chat', '.usage 词库'];
  for (const command of readOnly) {
    await check('指令通道', `${command} 回执`, async () => {
      const started = await request('/api/command', { method: 'POST', body: { command } });
      assert(started.status === 202, `期望 202，实际 ${started.status}：${snippet(started)}`);
      const id = started.body?.id;
      assert(id, '缺少回执 id');
      const capture = await waitCapture(id);
      const texts = (capture?.texts || []).join('\n');
      assert(texts.trim().length > 0, '没有收到回执文本');
      assert(!/操作失败|失败：|用法：/.test(texts), `回执报错：${texts.slice(0, 120)}`);
      const closed = await request('/api/capture/close', { method: 'POST', body: { id } });
      assert(closed.status === 200 || closed.status === 400, `关闭回执异常：${closed.status}`);
      return `${texts.split('\n').length} 行`;
    });
  }
  // 回归：指令跑完后收集器不能一直算"进行中"，否则整个控制台会永久 409。
  await check('指令通道', '连续三条指令都能执行（回执不会把控制台卡死）', async () => {
    const ids = [];
    for (let index = 0; index < 3; index++) {
      const started = await request('/api/command', { method: 'POST', body: { command: '.help' } });
      assert(started.status === 202, `第 ${index + 1} 条指令被拒：${started.status} ${snippet(started)}`);
      ids.push(started.body.id);
      await waitCapture(started.body.id);
    }
    return `3 条连续指令均受理（${ids.length} 个回执）`;
  });

  await check('指令通道', '空指令被拒', async () => {
    const message = await expectError('/api/command', { command: '   ' }, [400], '空指令');
    return message;
  });
  await check('指令通道', '超过 20 条批量被拒', async () => {
    const message = await expectError('/api/command', { command: Array.from({ length: 21 }, () => '.help').join('\n') }, [400], '超长批量');
    return message;
  });
  await check('指令通道', '未知回执 id 被拒', async () => {
    const message = await expectError('/api/capture', { id: 'does-not-exist' }, [400, 404], '未知回执');
    return message;
  });
  await check('指令通道', '关闭回执幂等', async () => {
    const started = await request('/api/command', { method: 'POST', body: { command: '.help' } });
    assert(started.status === 202, `发指令失败：${started.status} ${snippet(started)}`);
    const id = started.body?.id;
    await waitCapture(id);
    const first = await api('/api/capture/close', { id });
    const second = await request('/api/capture/close', { method: 'POST', body: { id } });
    assert(first.closed === true, '关闭应返回 closed:true');
    assert([200, 400, 404].includes(second.status), `重复关闭状态异常：${second.status}`);
    return `首次 closed，重复关闭 ${second.status}`;
  });
}

// ---------------------------------------------------------------- 5. 未知接口与图片

async function errorsAndImages(images) {
  await check('错误处理', '未知 /api 路由返回 404', async () => {
    const result = await request('/api/not-a-real-endpoint', { method: 'POST', body: {} });
    assert(result.status === 404, `实际 ${result.status}`);
    return result.body?.error || '';
  });
  await check('Civitai 登录', '一次性链接可生成，登录页可打开', async () => {
    const link = await api('/api/civitai/login-link', {});
    assert(link.url && link.url.includes('/civitai-login?token='), '缺少一次性链接：' + JSON.stringify(link));
    const page = await request(new URL(link.url).pathname + new URL(link.url).search, { token: '' });
    assert(page.status === 200, `登录页期望 200，实际 ${page.status}`);
    assert(page.text.includes('Civitai') && page.text.includes('javascript:(function'), '登录页缺少说明或书签小工具');
    return `${link.expiresMinutes} 分钟有效`;
  });
  await check('Civitai 登录', '无效令牌被拒（410）', async () => {
    const page = await request('/civitai-login?token=not-a-real-token', { token: '' });
    assert(page.status === 410, `实际 ${page.status}`);
    return page.text.includes('失效') ? '已提示重新生成' : '410';
  });
  await check('Civitai 登录', '回填 Cookie 需要有效令牌', async () => {
    const result = await request('/civitai-cookie', { method: 'POST', token: '', body: { token: 'bogus', cookie: 'session=placeholder' } });
    assert(result.status === 400, `实际 ${result.status}`);
    return result.body?.error || '';
  });
  await check('Civitai 登录', '清除 Cookie 的接口可用（未配置时才真清）', async () => {
    const before = await api('/api/civitai/status', {});
    if (before.hasCookie) return '已配置 Cookie，跳过清除（自检不做破坏性操作）';
    const cleared = await api('/api/civitai/cookie/clear', {});
    assert(cleared.ok === true && cleared.hasCookie === false, '清除后应没有 Cookie：' + JSON.stringify(cleared));
    return `回退站 ${cleared.fallbackHost}`;
  });
  await check('错误处理', '非法 JSON 请求体被拒', async () => {
    const result = await request('/api/status', { method: 'POST', body: '{broken' });
    assert(result.status === 400, `实际 ${result.status}`);
    return result.body?.error || '';
  });
  await check('图片接口', '路径穿越被拒（../../config.json）', async () => {
    const result = await request(`/api/image?path=${encodeURIComponent('../../config.json')}`);
    assert(result.status === 404, `实际 ${result.status}`);
    assert(!result.text.includes('owner_user_id'), '疑似读到了 config.json 内容');
    return '404';
  });
  await check('图片接口', '非图片扩展名被拒', async () => {
    const result = await request(`/api/image?path=${encodeURIComponent('data/generated/nope.txt')}`);
    assert([403, 404].includes(result.status), `实际 ${result.status}`);
    return String(result.status);
  });
  await check('图片接口', '待领取图片可读（若有）', async () => {
    if (!images.length) return '当前没有待领取图片，跳过';
    const first = images[0];
    const response = await request(`/api/image?path=${encodeURIComponent(first.path)}`, { raw: true });
    assert(response.status === 200, `实际 ${response.status}`);
    const type = response.headers.get('content-type') || '';
    const bytes = (await response.arrayBuffer()).byteLength;
    assert(type.startsWith('image/'), `Content-Type 异常：${type}`);
    assert(bytes > 100, `图片内容过小：${bytes}`);
    return `${first.name} ${type} ${Math.round(bytes / 1024)}KB`;
  });
}

// ---------------------------------------------------------------- 6. 聊天接口（临时 scope）

async function chatEndpoints() {
  try {
    await check('聊天接口', '临时 scope 历史为空数组', async () => {
      const history = await api('/api/chat/history', { scope: PROBE_SCOPE });
      assert(Array.isArray(history), '历史应为数组');
      return `${history.length} 条`;
    });
    await check('聊天接口', 'execute=false 只规划不执行（生成三张）', async () => {
      const result = await api('/api/chat', { message: '生成三张', execute: false, scope: PROBE_SCOPE });
      assert(Array.isArray(result.commands), '缺少 commands');
      assert(result.commands.length === 1 && result.commands[0] === '.gen 3', `规划结果异常：${JSON.stringify(result.commands)}`);
      return `${result.reply} → ${result.commands.join(',')}`;
    });
    await check('聊天接口', '清空对话后历史为空', async () => {
      await api('/api/chat/reset', { scope: PROBE_SCOPE });
      const history = await api('/api/chat/history', { scope: PROBE_SCOPE });
      assert(history.length === 0, `清空后仍有 ${history.length} 条`);
      return '已清空';
    });
    await check('聊天接口', '空消息被拒', async () => {
      const message = await expectError('/api/chat', { message: '  ', scope: PROBE_SCOPE }, [400], '空消息');
      return message;
    });
  } finally {
    const probe = path.join(REPO, 'data', 'webui', `${PROBE_SCOPE}-chat.json`);
    if (fs.existsSync(probe)) fs.rmSync(probe);
  }
}

// ---------------------------------------------------------------- 7. 前端调用与后端路由对应

/** 后端有、前端不用的接口：图片与关回执是给 <img> 和外部工具（work/webctl.ps1）用的。 */
// 这些接口是给 <img src>（拼查询串）或一次性登录页/工具用的，不在前端源码里以整串出现。
const TOOLING_ONLY_ROUTES = new Set(['/api/image', '/api/civitai/thumb', '/api/capture/close',
  '/api/civitai/cookie', '/api/civitai/status']);

/** 前端源码（Next 版把 Java 的 webui/app.js 拆成了 components/ 下的 TS/TSX）。 */
function frontendSources() {
  const files = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (/\.tsx?$/.test(entry.name)) files.push(full);
    }
  };
  walk(path.join(REPO, 'components'));
  return files.map((file) => ({ file, text: fs.readFileSync(file, 'utf8') }));
}

async function frontendRoutes() {
  // Next 版路由就是 `app/api/[...path]/route.ts` 里的 `case "/api/…"`（Java 版在 WebApiController）。
  const server = fs.readFileSync(path.join(REPO, 'app', 'api', '[...path]', 'route.ts'), 'utf8');
  await check('接线', '前端调用的接口都存在于后端路由', async () => {
    const called = new Set();
    for (const { text } of frontendSources()) {
      for (const match of text.matchAll(/['"`](\/api\/[a-z0-9/-]+)/g)) called.add(match[1]);
    }
    const missing = [...called].filter((route) => !server.includes(`case "${route}"`));
    assert(missing.length === 0, `前端调用了后端没有的接口：${missing.join(', ')}`);
    return `${called.size} 个接口都有对应 case`;
  });
  await check('接线', '后端路由都有前端或工具使用（无死接口）', async () => {
    const sources = frontendSources().map((each) => each.text).join('\n');
    const routes = [...server.matchAll(/case "(\/api\/[a-z0-9/-]+)"/g)].map((match) => match[1]);
    // 只看"这个路径后面不再接路径字符"，这样 `/api/prompt?scope=…` 与模板串拼接都算用到了。
    const used = (route) => new RegExp(route.replace(/[/-]/g, '\\$&') + '(?![a-z0-9/-])').test(sources);
    const unused = routes.filter((route) => !TOOLING_ONLY_ROUTES.has(route) && !used(route));
    assert(unused.length === 0, `后端有前端未使用的接口：${unused.join(', ')}`);
    return `${routes.length} 个路由（其中 ${TOOLING_ONLY_ROUTES.size} 个供 <img>/工具使用）`;
  });
  await check('接线', '10 个栏目：页签、页面、面板三者一一对应', async () => {
    const tabs = [...fs.readFileSync(path.join(REPO, 'components', 'shell', 'panels.ts'), 'utf8')
      .matchAll(/\{ id: "(\w+)", href: "([^"]+)", label: "([^"]+)" \}/g)]
      .map((match) => ({ id: match[1], href: match[2], label: match[3] }));
    assert(tabs.length === 10, `页签表里只有 ${tabs.length} 个栏目`);
    for (const tab of tabs) {
      const page = tab.id === 'chat' ? path.join(REPO, 'app', 'page.tsx') : path.join(REPO, 'app', tab.id, 'page.tsx');
      assert(fs.existsSync(page), `缺少页面 ${page}`);
      const source = fs.readFileSync(page, 'utf8');
      assert(source.includes(`<PanelPage id="${tab.id}">`), `${page} 没有渲染 PanelPage id="${tab.id}"`);
    }
    const panels = fs.readdirSync(path.join(REPO, 'components', 'panels'))
      .map((name) => fs.readFileSync(path.join(REPO, 'components', 'panels', name), 'utf8'));
    const declared = new Set();
    for (const source of panels) {
      for (const match of source.matchAll(/id="panel-(\w+)"/g)) declared.add(match[1]);
    }
    const missing = tabs.filter((tab) => !declared.has(tab.id)).map((tab) => tab.id);
    assert(missing.length === 0, `这些栏目没有对应的面板组件：${missing.join(', ')}`);
    const extra = [...declared].filter((id) => !tabs.some((tab) => tab.id === id));
    assert(extra.length === 0, `面板组件里有页签表以外的栏目：${extra.join(', ')}`);
    return `${tabs.length} 个栏目：页签 / 页面 / 面板齐备`;
  });
}

async function parallel(thunks) {
  await Promise.all(thunks.map((thunk) => thunk()));
}

// ---------------------------------------------------------------- 运行

const started = Date.now();
console.log(`WebUI 自检：${BASE}\n`);
await authAndStatic();
let status;
await check('启动', '读取 /api/status 作为后续检查的基准', async () => {
  status = await api('/api/status');
  return `${status.botName} · scope ${status.scope}`;
});
if (!status) {
  console.log('\n无法读取状态，后续检查跳过。');
} else {
  const images = await api('/api/images', { limit: 60 }).then((body) => body.images || []).catch(() => []);
  await readEndpoints(status);
  await settingsWrites(status);
  await internalPanels(status);
  await commandChannel();
  await errorsAndImages(images);
  await chatEndpoints();
}
await frontendRoutes();

const failed = results.filter((item) => !item.ok);
const wall = Date.now() - started;
const byGroup = new Map();
for (const item of results) {
  if (!byGroup.has(item.group)) byGroup.set(item.group, []);
  byGroup.get(item.group).push(item);
}

const lines = [
  '# Web 控制台功能自检报告',
  '',
  `- 时间：${new Date().toLocaleString('zh-CN')}　目标：${BASE}`,
  `- 结果：**${results.length - failed.length}/${results.length} 通过**${failed.length ? `，${failed.length} 项失败` : ''}　总耗时 ${wall} ms`,
  '- 方式：直接调用内部 `/api` 接口（只读 + 幂等回写），独立检查并行执行；指令通道只发只读指令，聊天检查使用临时 scope 并已清理。',
  '',
];
for (const [group, items] of byGroup) {
  lines.push(`## ${group}（${items.filter((item) => item.ok).length}/${items.length}）`, '', '| 检查项 | 结果 | 耗时 | 详情 |', '| --- | --- | --- | --- |');
  for (const item of items) lines.push(`| ${item.name} | ${item.ok ? '通过' : '**失败**'} | ${item.ms} ms | ${item.detail.replace(/\|/g, '\\|').replace(/\n/g, ' ').slice(0, 200)} |`);
  lines.push('');
}
if (failed.length) {
  lines.push('## 失败明细', '');
  for (const item of failed) lines.push(`- **${item.group} · ${item.name}**：${item.detail}`);
  lines.push('');
}
fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, lines.join('\n'), 'utf8');
fs.writeFileSync(JSON_OUT, JSON.stringify({ base: BASE, at: new Date().toISOString(), wallMillis: wall, total: results.length, failed: failed.length, results }, null, 2), 'utf8');

console.log(`\n合计 ${results.length - failed.length}/${results.length} 通过，耗时 ${wall} ms`);
console.log(`报告：${OUT}`);
if (failed.length) {
  console.log('失败项：');
  for (const item of failed) console.log(`  · ${item.group} · ${item.name} → ${item.detail}`);
  process.exit(1);
}
