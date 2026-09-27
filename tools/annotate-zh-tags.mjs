// 生成"词条用法表"：把内置中文词库里的每一条词条按分类列出，标注词意（中文写法）、
// 使用需求（什么情况下该用）与注意事项，并给出分类/标记统计。
//
// 用法：node tools/annotate-zh-tags.mjs [输出文件]
//   默认写 data/prompt-zh-usage.md
import fs from 'node:fs';
import path from 'node:path';

const REPO = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..');
const out = process.argv[2] ? path.resolve(process.argv[2]) : path.join(REPO, 'data/prompt-zh-usage.md');
const library = JSON.parse(fs.readFileSync(path.join(REPO, 'data/prompt-zh-tags.json'), 'utf8'));
const CATEGORIES = library.categories;

/** 逐条人工说明（data/prompt-zh-use-notes.txt）：`词条=使用需求`，优先于模板。 */
const notes = new Map();
const notesFile = path.join(REPO, 'data/prompt-zh-use-notes.txt');
if (fs.existsSync(notesFile)) {
  for (const raw of fs.readFileSync(notesFile, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const at = line.indexOf('=');
    if (at < 0) continue;
    for (const tag of line.slice(0, at).split(',').map((value) => value.trim()).filter(Boolean))
      notes.set(tag.toLowerCase(), line.slice(at + 1).trim());
  }
}

/** 使用需求模板：与 PromptUsage.timing 保持一致。 */
const WHEN = {
  人物: (names) => `要求画面里出现「${names}」这类人物或外貌特征时`,
  角色: () => '只在用户点名该角色时（角色词条不要自己加）',
  作品: () => '只在用户点名该作品时（作品词条不要自己加）',
  表情: (names) => `要求「${names}」这类表情时`,
  动作: (names) => `要求「${names}」这个动作/行为时`,
  姿势: (names) => `要求「${names}」这个姿势或体位时`,
  服装: (names) => `要求角色穿「${names}」时`,
  场景: (names) => `要求场景是「${names}」时`,
  环境: (names) => `要求天气、时段或自然环境是「${names}」时`,
  物品: (names) => `要求画面里出现「${names}」时`,
  镜头: (names) => `要求用「${names}」这种取景或视角时`,
  画面: (names) => `要求画质或画风是「${names}」时（用户没提画质/画风就不要加）`,
  其他: (names) => `用户明确提到「${names}」时`,
};
const CAUTION = {
  成人向: '成人向词条：只在用户明确要求该行为/部位时使用，不要自行添加',
  画质: '画质词条：用户没提画质时不要加',
  画风: '画风词条：用户没提画风时不要加，避免整张图跑偏',
  点名才用: '角色/作品词条：只在用户点名时使用',
  构图: '取景/构图词条：用户没要求机位或视角时不要加，也不要与同族词条同时使用',
};

const byCategory = new Map();
for (const [tag, aliases, category, rank, flags] of library.entries) {
  const name = CATEGORIES[category] || '其他';
  if (!byCategory.has(name)) byCategory.set(name, []);
  byCategory.get(name).push({ tag, aliases: aliases.split('|'), rank: rank || 0, flags: (flags || '').split(',').filter(Boolean) });
}
const flagCount = new Map();
const lines = [];
lines.push('# 内置中文词库 · 词条用法表', '');
lines.push(`共 ${library.counts.entries} 条词条、${library.counts.aliases} 个中文写法；由 \`node tools/annotate-zh-tags.mjs\` 生成。`);
lines.push('');
lines.push('| 字段 | 含义 |');
lines.push('|---|---|');
lines.push('| 词条 | SD/Danbooru 标准词条（已核对存在于 `data/prompt-tags.txt`） |');
lines.push('| 中文写法 | 词库认得的说法；`.infix` 就是靠它把中文对应到标准词条 |');
lines.push('| 使用需求 | 什么需求下才该用这个词条 |');
lines.push('| 注意 | 使用限制（成人向、画质/画风、角色/作品、构图等） |');
lines.push('');
lines.push('## 分类统计', '');
lines.push('| 分类 | 条数 |');
lines.push('|---|---|');
for (const name of CATEGORIES) {
  const list = byCategory.get(name) || [];
  if (!list.length) continue;
  lines.push(`| ${name} | ${list.length} |`);
}
lines.push('');
lines.push('## 标记统计', '');
lines.push('| 标记 | 条数 | 说明 |');
lines.push('|---|---|---|');
const order = ['成人向', '画质', '画风', '构图', '点名才用'];
for (const flag of order) lines.push(`| ${flag} | ${flagCount.get(flag) || 0} | ${CAUTION[flag] || ''} |`);
lines.push('');

for (const name of CATEGORIES) {
  const list = byCategory.get(name) || [];
  if (!list.length) continue;
  lines.push(`## ${name}（${list.length} 条）`, '');
  lines.push('| 词条 | 中文写法 | 使用需求 | 注意 |');
  lines.push('|---|---|---|---|');
  for (const item of list.sort((a, b) => b.rank - a.rank || (a.tag < b.tag ? -1 : 1))) {
    for (const flag of item.flags) flagCount.set(flag, (flagCount.get(flag) || 0) + 1);
    const when = notes.get(item.tag) || (WHEN[name] || WHEN.其他)(item.aliases.slice(0, 3).join('、'));
    const caution = item.flags.map((flag) => CAUTION[flag]).filter(Boolean).join('；');
    lines.push(`| \`${item.tag}\` | ${item.aliases.join('、')} | ${when} | ${caution} |`);
  }
  lines.push('');
}
fs.writeFileSync(out, lines.join('\n'));
const size = fs.statSync(out).size;
console.log(`已写入 ${out}（${(size / 1048576).toFixed(2)} MB，${library.entries.length} 条词条）`);
console.log('分类：' + CATEGORIES.map((name) => `${name} ${(byCategory.get(name) || []).length}`).filter((item) => !item.endsWith(' 0')).join('，'));
console.log('标记：' + order.map((flag) => `${flag} ${flagCount.get(flag) || 0}`).join('，'));
console.log(`带人工说明的词条：${notes.size} 条`);
