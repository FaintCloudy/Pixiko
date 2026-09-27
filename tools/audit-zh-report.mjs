// 把 data/prompt-zh-extra.txt 里的「自动审查」区块整理成可读的纠正记录。
//
// 用法：
//   node tools/audit-zh-report.mjs                                  # 全部累计记录 → work/audit-corrections.md
//   node tools/audit-zh-report.mjs --baseline work/xxx.bak.txt      # 只列基线之后新增的记录
//   node tools/audit-zh-report.mjs --out work/x.md --csv work/x.csv
import fs from 'node:fs';
import path from 'node:path';

const REPO = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..');
const argv = process.argv.slice(2);
const value = (name, fallback) => {
  const at = argv.indexOf(name);
  return at >= 0 && argv[at + 1] ? argv[at + 1] : fallback;
};
const SOURCE = path.resolve(REPO, value('--source', 'data/prompt-zh-extra.txt'));
const BASELINE = argv.includes('--baseline') ? path.resolve(REPO, value('--baseline', '')) : '';
const OUT = path.resolve(REPO, value('--out', 'work/audit-corrections.md'));
const CSV = path.resolve(REPO, value('--csv', 'work/audit-corrections.csv'));

const START = '# ===== 自动审查：DeepSeek 标记的错误翻译（tools/audit-zh-translations.mjs 生成）=====';
const END = '# ===== 自动审查结束 =====';

/** 解析审查区块：返回 { drops: Map<zh, Set<tag>>, adds: Map<zh, Set<tag>> }。 */
function parseBlock(file) {
  const text = fs.readFileSync(file, 'utf8');
  const from = text.indexOf(START);
  if (from < 0) throw new Error(`没有找到审查区块：${file}`);
  const to = text.indexOf(END, from);
  const block = to >= 0 ? text.slice(from + START.length, to) : text.slice(from + START.length);
  const drops = new Map(), adds = new Map();
  for (const raw of block.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const drop = line.startsWith('!');
    const body = drop ? line.slice(1) : line;
    const eq = body.indexOf('=');
    if (eq <= 0) continue;
    const zh = body.slice(0, eq).trim();
    const bucket = drop ? drops : adds;
    for (const tag of body.slice(eq + 1).split(',').map((t) => t.trim().toLowerCase()).filter(Boolean)) {
      if (!bucket.has(zh)) bucket.set(zh, new Set());
      bucket.get(zh).add(tag);
    }
  }
  return { drops, adds };
}

const current = parseBlock(SOURCE);
const baseline = BASELINE ? parseBlock(BASELINE) : { drops: new Map(), adds: new Map() };

/** 把 (词条, 中文写法) 展平成集合，便于做差集。 */
function flatten(map) {
  const set = new Set();
  for (const [zh, tags] of map) for (const tag of tags) set.add(`${tag}\u0000${zh}`);
  return set;
}
const baseDropKeys = flatten(baseline.drops), baseAddKeys = flatten(baseline.adds);

/** 按词条汇总：tag → { dropped: [zh], added: [zh] }。 */
function group(drops, adds, dropSkip, addSkip) {
  const tags = new Map();
  const touch = (tag) => {
    if (!tags.has(tag)) tags.set(tag, { dropped: [], added: [] });
    return tags.get(tag);
  };
  for (const [zh, list] of drops) for (const tag of list) if (!dropSkip.has(`${tag}\u0000${zh}`)) touch(tag).dropped.push(zh);
  for (const [zh, list] of adds) for (const tag of list) if (!addSkip.has(`${tag}\u0000${zh}`)) touch(tag).added.push(zh);
  for (const entry of tags.values()) {
    entry.dropped.sort((a, b) => a.localeCompare(b, 'zh'));
    entry.added.sort((a, b) => a.localeCompare(b, 'zh'));
  }
  return tags;
}

const all = group(current.drops, current.adds, new Set(), new Set());
const fresh = group(current.drops, current.adds, baseDropKeys, baseAddKeys);

/**
 * 人工同义词表（审查区块之前的部分）里的 `中文=tag` 行是最高优先级，
 * build-zh-tags.mjs 只摘掉优先级 ≥2 的社区机翻写法，所以这些写法不会真被删掉。
 */
function manualClaims(file) {
  const text = fs.readFileSync(file, 'utf8');
  const stop = text.indexOf(START);
  const head = stop >= 0 ? text.slice(0, stop) : text;
  const claims = new Set();
  for (const raw of head.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#') || line.startsWith('!')) continue;
    const at = line.indexOf('=');
    if (at < 0) continue;
    const words = line.slice(0, at).split('/').map((word) => word.trim()).filter(Boolean);
    const tags = line.slice(at + 1).split(',').map((tag) => tag.trim().toLowerCase()).filter(Boolean);
    for (const tag of tags) for (const word of words) claims.add(`${tag}\u0000${word}`);
  }
  return claims;
}
const protectedClaims = manualClaims(SOURCE);
const isProtected = (tag, zh) => protectedClaims.has(`${tag}\u0000${zh}`);

const countOf = (tags, key) => [...tags.values()].reduce((sum, entry) => sum + entry[key].length, 0);
const rowsOf = (tags) => [...tags.entries()]
  .sort((a, b) => (b[1].dropped.length + b[1].added.length) - (a[1].dropped.length + a[1].added.length)
    || a[0].localeCompare(b[0]))
  .flatMap(([tag, entry]) => {
    const rows = [];
    const max = Math.max(entry.dropped.length, entry.added.length);
    for (let i = 0; i < max; i++) rows.push({ tag, dropped: entry.dropped[i] || '', added: entry.added[i] || '' });
    return rows;
  });

function markdown(title, tags, note) {
  const rows = rowsOf(tags);
  const kept = rows.filter((row) => row.dropped && isProtected(row.tag, row.dropped)).length;
  const dropped = countOf(tags, 'dropped');
  const lines = [
    `# ${title}`,
    '',
    `- 生成时间：${new Date().toLocaleString('zh-CN')}`,
    `- 数据来源：\`${path.relative(REPO, SOURCE).replace(/\\/g, '/')}\` 的自动审查区块`,
    note ? `- 对比基线：\`${path.relative(REPO, BASELINE).replace(/\\/g, '/')}\`` : '- 范围：全部累计记录',
    `- 摘掉错误写法 **${dropped}** 处、补上正确写法 **${countOf(tags, 'added')}** 处，涉及 **${tags.size}** 个词条`,
    `- 其中 **${dropped - kept}** 处会真正从词库移除，**${kept}** 处因为人工同义词表里明确写过（优先级最高）而保持不变`,
    '',
    '> 说明：摘掉 = 从该词条上移除这个中文写法（社区机器翻译把它当成了这个词条）；补上 = 审查给出的更准确中文说法。',
    '> 状态列：`生效` = 该写法只来自社区机翻，会被移除；`受保护` = 人工同义词表里明确写过，审查意见不自动执行。',
    '> 同一词条的多条记录按顺序排在一起，未保留"哪条错配修成哪条正确写法"的一一对应关系。',
    '',
    '## 一、按词条查看',
    '',
    '| 词条 | 摘掉的中文写法 | 状态 | 补上的正确写法 |',
    '| --- | --- | --- | --- |',
    ...rows.map((row) => `| \`${row.tag}\` | ${row.dropped || ''} | ${row.dropped ? (isProtected(row.tag, row.dropped) ? '受保护' : '生效') : ''} | ${row.added || ''} |`),
    '',
    '## 二、逐条明细',
    '',
    '| # | 词条 | 动作 | 中文写法 | 状态 |',
    '| --- | --- | --- | --- | --- |',
  ];
  let n = 0;
  for (const [tag, entry] of tags) {
    for (const zh of entry.dropped) lines.push(`| ${++n} | \`${tag}\` | 摘掉 | ${zh} | ${isProtected(tag, zh) ? '受保护' : '生效'} |`);
    for (const zh of entry.added) lines.push(`| ${++n} | \`${tag}\` | 补上 | ${zh} | 生效 |`);
  }
  lines.push('');
  return lines.join('\n');
}

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, markdown(
  BASELINE ? '词库翻译审查：本次新增的纠正记录' : '词库翻译审查：累计纠正记录',
  BASELINE ? fresh : all,
  Boolean(BASELINE),
), 'utf8');

const csv = ['词条,动作,中文写法,状态'];
for (const [tag, entry] of all) {
  for (const zh of entry.dropped) csv.push(`${tag},摘掉,${zh},${isProtected(tag, zh) ? '受保护' : '生效'}`);
  for (const zh of entry.added) csv.push(`${tag},补上,${zh},生效`);
}
fs.writeFileSync(CSV, csv.join('\n') + '\n', 'utf8');

const shown = BASELINE ? fresh : all;
console.log(`累计：摘掉 ${countOf(all, 'dropped')} 处 / 补上 ${countOf(all, 'added')} 处（${all.size} 个词条）`);
if (BASELINE) console.log(`本次新增：摘掉 ${countOf(fresh, 'dropped')} 处 / 补上 ${countOf(fresh, 'added')} 处（${fresh.size} 个词条）`);
const protectedCount = [...shown.entries()].reduce((sum, [tag, entry]) => sum + entry.dropped.filter((zh) => isProtected(tag, zh)).length, 0);
console.log(`其中受人工同义词表保护、不会真被摘掉：${protectedCount} 处`);
console.log(`报告：${OUT}`);
console.log(`CSV：${CSV}`);
const top = [...shown.entries()].sort((a, b) => (b[1].dropped.length + b[1].added.length) - (a[1].dropped.length + a[1].added.length)).slice(0, 12);
for (const [tag, entry] of top) {
  console.log(`  ${tag}：摘掉 ${entry.dropped.length}、补上 ${entry.added.length}`);
}
