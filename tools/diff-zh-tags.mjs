// 比较两份中文词库（data/prompt-zh-tags.json），列出实际新增/移除的中文写法。
// 用来核对"审查意见"最终有没有真的落到词库里。
//
// 用法：node tools/diff-zh-tags.mjs --before work/prompt-zh-tags.before-audit.json --after data/prompt-zh-tags.json
import fs from 'node:fs';
import path from 'node:path';

const REPO = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..');
const argv = process.argv.slice(2);
const value = (name, fallback) => {
  const at = argv.indexOf(name);
  return at >= 0 && argv[at + 1] ? argv[at + 1] : fallback;
};
const BEFORE = path.resolve(REPO, value('--before', 'work/prompt-zh-tags.before-audit.json'));
const AFTER = path.resolve(REPO, value('--after', 'data/prompt-zh-tags.json'));
const OUT = path.resolve(REPO, value('--out', 'work/zh-library-diff.md'));
const CSV = path.resolve(REPO, value('--csv', 'work/zh-library-diff.csv'));

const load = (file) => {
  const library = JSON.parse(fs.readFileSync(file, 'utf8'));
  const map = new Map();
  for (const entry of library.entries) map.set(entry[0], new Set(entry[1].split('|').filter(Boolean)));
  return map;
};
const size = (map) => [...map.values()].reduce((sum, set) => sum + set.size, 0);
const before = load(BEFORE);
const after = load(AFTER);

const removed = [], added = [];
for (const [tag, aliases] of before) {
  const now = after.get(tag) || new Set();
  for (const alias of aliases) if (!now.has(alias)) removed.push([tag, alias]);
}
for (const [tag, aliases] of after) {
  const old = before.get(tag) || new Set();
  for (const alias of aliases) if (!old.has(alias)) added.push([tag, alias]);
}
const group = (list) => {
  const map = new Map();
  for (const [tag, alias] of list) {
    if (!map.has(tag)) map.set(tag, []);
    map.get(tag).push(alias);
  }
  return map;
};
const removedByTag = group(removed), addedByTag = group(added);
const watch = value('--watch', '交合部位,臀部主视角,分镜,女性,抱枕').split(',').map((s) => s.trim()).filter(Boolean);
const owners = (map, alias) => [...map.entries()].filter(([, set]) => set.has(alias)).map(([tag]) => tag);

const lines = [
  '# 词库重建前后差异（全量审查的实际生效结果）',
  '',
  `- 生成时间：${new Date().toLocaleString('zh-CN')}`,
  `- 前：\`${path.relative(REPO, BEFORE).replace(/\\/g, '/')}\` — ${before.size} 条词条 / ${size(before)} 个中文写法`,
  `- 后：\`${path.relative(REPO, AFTER).replace(/\\/g, '/')}\` — ${after.size} 条词条 / ${size(after)} 个中文写法`,
  `- 实际移除写法 **${removed.length}** 处（${removedByTag.size} 个词条）、实际新增写法 **${added.length}** 处（${addedByTag.size} 个词条）`,
  '',
  '## 一、被移除的中文写法',
  '',
  '| 词条 | 移除的中文写法 |',
  '| --- | --- |',
];
for (const [tag, list] of [...removedByTag.entries()].sort((a, b) => b[1].length - a[1].length)) {
  lines.push(`| \`${tag}\` | ${list.join('、')} |`);
}
lines.push('', '## 二、新增的中文写法', '', '| 词条 | 新增的中文写法 |', '| --- | --- |');
for (const [tag, list] of [...addedByTag.entries()].sort((a, b) => b[1].length - a[1].length)) {
  lines.push(`| \`${tag}\` | ${list.join('、')} |`);
}
lines.push('', '## 三、关键写法抽查', '', '| 中文写法 | 审查前挂载在 | 审查后挂载在 |', '| --- | --- | --- |');
for (const alias of watch) {
  lines.push(`| ${alias} | ${owners(before, alias).join('、') || '（无）'} | ${owners(after, alias).join('、') || '（无）'} |`);
}
lines.push('');
fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, lines.join('\n'), 'utf8');

const csv = ['词条,变化,中文写法'];
for (const [tag, alias] of removed) csv.push(`${tag},移除,${alias}`);
for (const [tag, alias] of added) csv.push(`${tag},新增,${alias}`);
fs.writeFileSync(CSV, csv.join('\n') + '\n', 'utf8');

console.log(`实际移除 ${removed.length} 处（${removedByTag.size} 个词条）/ 实际新增 ${added.length} 处（${addedByTag.size} 个词条）`);
console.log(`报告：${OUT}`);
console.log('移除最多的词条：');
for (const [tag, list] of [...removedByTag.entries()].sort((a, b) => b[1].length - a[1].length).slice(0, 12)) {
  console.log(`  ${tag} ${list.length}`);
}
console.log('关键写法抽查：');
for (const alias of watch) {
  console.log(`  ${alias}: [${owners(before, alias).join(',')}] → [${owners(after, alias).join(',')}]`);
}
