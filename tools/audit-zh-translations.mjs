// 用 DeepSeek 审查内置中文词库里的错误翻译：分批把 (英文词条 → 中文写法) 交给模型判断，
// 只标记"明显错误/不相干"的写法，并在能给出正确说法时给出替换。
//
// 结果写回 data/prompt-zh-extra.txt 的固定区块（可重复运行，会覆盖上一次的结果）：
//   !错误写法=词条     从该词条上摘掉
//   正确写法=词条      补上正确的中文说法
//
// 用法：node tools/audit-zh-translations.mjs [--limit 1200] [--batch 60] [--dry]
import fs from 'node:fs';
import path from 'node:path';

const REPO = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..');
const argv = process.argv.slice(2);
const flag = (name, fallback) => {
  const at = argv.indexOf(name);
  return at >= 0 && argv[at + 1] ? Number(argv[at + 1]) : fallback;
};
const LIMIT = flag('--limit', 1200);
const BATCH = flag('--batch', 60);
const DRY = argv.includes('--dry');
const KEY_FILE = path.join(REPO, 'data/deepseek-chat-api-key.txt');
if (!fs.existsSync(KEY_FILE)) throw new Error('缺少聊天频道密钥 data/deepseek-chat-api-key.txt');
const KEY = fs.readFileSync(KEY_FILE, 'utf8').trim();
const MODEL = (JSON.parse(fs.readFileSync(path.join(REPO, 'config.json'), 'utf8')).progen || {}).model || 'deepseek-flash';

const library = JSON.parse(fs.readFileSync(path.join(REPO, 'data/prompt-zh-tags.json'), 'utf8'));
const VISUAL = new Set(['人物', '表情', '动作', '姿势', '服装', '场景', '环境', '物品', '镜头', '画面']);
const targets = library.entries
  .filter((entry) => VISUAL.has(library.categories[entry[2]]))
  .filter((entry) => (entry[3] || 0) >= 100)
  .sort((a, b) => (b[3] || 0) - (a[3] || 0))
  .slice(0, LIMIT);

const SYSTEM = `你在审查 Stable Diffusion 提示词词库的中文翻译。
用户给你一批 "英文词条 => 中文写法"，中文写法来自社区机器翻译。
只标出**明显错误或完全不相干**的写法（例如 "smelling_underwear => 内裤"，正确意思是"闻内裤"；
"3d_background => 背景"，它其实是"3D 背景"）。以下情况不算错误，不要标：
中文只是不够精确、是近义词、是上位词、带点机翻腔、或省略了修饰语（"白衬衫"写作"衬衫"可以接受）。
只输出 JSON：{"wrong":[{"tag":"英文词条","zh":"要摘掉的中文写法","right":"正确的中文说法（不知道就空串）"}]}
没有错误就输出 {"wrong":[]}。不要解释，不要输出别的字段。`;

async function ask(pairs) {
  const body = {
    model: MODEL,
    stream: false,
    messages: [
      { role: 'system', content: SYSTEM },
      { role: 'user', content: pairs.map((item) => `${item.tag} => ${item.zh}`).join('\n') },
    ],
  };
  const response = await fetch('https://api.deepseek.com/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${KEY}` },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${(await response.text()).slice(0, 200)}`);
  const data = await response.json();
  const text = data.choices?.[0]?.message?.content || '';
  const at = text.indexOf('{');
  const end = text.lastIndexOf('}');
  if (at < 0 || end < at) throw new Error('返回不是 JSON：' + text.slice(0, 200));
  return JSON.parse(text.slice(at, end + 1));
}

const pairs = [];
for (const [tag, aliases] of targets) for (const zh of aliases.split('|')) pairs.push({ tag, zh });
console.log(`待审 ${targets.length} 条词条 / ${pairs.length} 个中文写法；每批 ${BATCH}，共 ${Math.ceil(pairs.length / BATCH)} 批`);

const wrong = [];
let failed = 0, done = 0;
const batches = [];
for (let start = 0; start < pairs.length; start += BATCH) batches.push(pairs.slice(start, start + BATCH));
const progress = path.join(REPO, 'work/audit-progress.log');
fs.writeFileSync(progress, `开始审查 ${batches.length} 批，每批 ${BATCH}，并发 ${process.env.AUDIT_CONCURRENCY || 6}\n`);
async function runBatch(batch) {
  try {
    const result = await ask(batch);
    const list = Array.isArray(result.wrong) ? result.wrong : [];
    const found = [];
    for (const item of list) {
      const tag = String(item.tag || '').trim().toLowerCase();
      const zh = String(item.zh || '').trim();
      const right = String(item.right || '').trim();
      if (!tag || !zh) continue;
      if (!batch.some((pair) => pair.tag === tag && pair.zh === zh)) continue;   // 只接受本批里真实存在的
      found.push({ tag, zh, right });
      wrong.push({ tag, zh, right });
    }
    done++;
    fs.appendFileSync(progress, `  批次 ${done}/${batches.length}：本批 ${found.length} 处，累计 ${wrong.length}\n`);
    if (!DRY) saveBlock(wrong);      // 每批都落盘：随时可以拿已有结果重新生成词库
  } catch (error) {
    failed++; done++;
    fs.appendFileSync(progress, `  批次 ${done}/${batches.length} 失败：${error.message}\n`);
  }
}
let next = 0;
const workers = Math.max(1, Number(process.env.AUDIT_CONCURRENCY || 6));
await Promise.all(Array.from({ length: workers }, async () => {
  while (next < batches.length) await runBatch(batches[next++]);
}));
console.log(`审查完成：标记 ${wrong.length} 处错误，失败批次 ${failed}`);
for (const item of wrong.slice(0, 20)) console.log(`  ${item.tag} => ${item.zh}${item.right ? '（应为 ' + item.right + '）' : ''}`);

if (DRY) process.exit(0);
saveBlock(wrong);
console.log(`已写入 ${path.join(REPO, 'data/prompt-zh-extra.txt')}`);

/**
 * 整理模型给的正确说法：按 / 、；拆成多条，丢掉不含汉字或超过 12 个字的片段
 * （词库的合法性检查要求"有汉字、不超过 12 字"，否则 ZhTagEval 会报结构性问题）。
 */
function sanitizeAlias(text) {
  return String(text || '')
    .split(/[/、;；]/)
    .map((piece) => piece.replace(/[\s\u3000]+/g, '').trim())
    .filter((piece) => piece && /[\u4e00-\u9fff]/.test(piece) && piece.length <= 12)
    .join('/');
}

/** 把审查结果写回 data/prompt-zh-extra.txt 的固定区块（可反复调用，覆盖上一次）。 */
function saveBlock(items) {
  const extraFile = path.join(REPO, 'data/prompt-zh-extra.txt');
  const startMark = '# ===== 自动审查：DeepSeek 标记的错误翻译（tools/audit-zh-translations.mjs 生成）=====';
  const endMark = '# ===== 自动审查结束 =====';
  let text = fs.readFileSync(extraFile, 'utf8');
  const from = text.indexOf(startMark);
  const previous = [];
  if (from >= 0) {
    const to = text.indexOf(endMark, from);
    const block = to >= 0 ? text.slice(from + startMark.length, to) : '';
    // 保留上一次审查的结论：换更大范围重跑时，旧结论不会被覆盖掉
    for (const line of block.split(/\r?\n/)) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith('#')) continue;
      const drop = trimmed.startsWith('!');
      const body = drop ? trimmed.slice(1) : trimmed;
      const eq = body.indexOf('=');
      if (eq <= 0) continue;
      const zh = body.slice(0, eq).trim();
      for (const tag of body.slice(eq + 1).split(',').map((t) => t.trim().toLowerCase()).filter(Boolean)) {
        previous.push(drop ? { tag, zh, right: '' } : { tag, zh: '', right: zh });
      }
    }
    text = text.slice(0, from) + (to >= 0 ? text.slice(to + endMark.length) : '');
    text = text.replace(/\n{3,}/g, '\n\n').replace(/\s+$/, '') + '\n';
  }
  const drops = new Map(), adds = new Map();
  for (const item of [...previous, ...items]) {
    if (item.zh) {
      if (!drops.has(item.zh)) drops.set(item.zh, new Set());
      drops.get(item.zh).add(item.tag);
    }
    const right = sanitizeAlias(item.right);
    if (right && right !== item.zh) {
      if (!adds.has(right)) adds.set(right, new Set());
      adds.get(right).add(item.tag);
    }
  }
  const lines = ['', startMark, `# 本次标记 ${items.length} 处（含历史累计）；摘掉错误写法 ${drops.size} 个，补正确写法 ${adds.size} 个`];
  for (const [zh, tags] of drops) lines.push(`!${zh}=${[...tags].join(',')}`);
  for (const [zh, tags] of adds) lines.push(`${zh}=${[...tags].join(',')}`);
  lines.push(endMark);
  fs.writeFileSync(extraFile, text.replace(/\s+$/, '') + '\n' + lines.join('\n') + '\n');
}
