#!/usr/bin/env node
/**
 * 神户小鸟说话风格拟合：从原作语料里量出可执行的写作规则。
 *
 *   node tools/kotori-style-fit.mjs                    # 打印 Markdown 报告
 *   node tools/kotori-style-fit.mjs --write            # 写到 docs/KOTORI-STYLE-STATS.md
 *   node tools/kotori-style-fit.mjs --file <路径>       # 换一份语料
 *
 * 语料格式（data/kotori-corpus.txt，不随仓库分发）：
 *   ===== seen01003 =====   ← 场景分隔
 *   【瑚太朗】「……」          ← 说话人 + 台词
 *   【小鸟】「……」
 *
 * 只读、离线、无依赖。报告里的每个数字都能在语料里复查——提示词里的风格规则
 * 应该照着这里改，而不是凭印象写。
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const argv = process.argv.slice(2);
const flag = (name) => argv.includes(name);
const value = (name, fallback) => {
  const at = argv.indexOf(name);
  return at >= 0 && argv[at + 1] ? argv[at + 1] : fallback;
};

const file = resolve(ROOT, value('--file', 'data/kotori-corpus.txt'));
let text;
try {
  text = readFileSync(file, 'utf8');
} catch (error) {
  console.error(`读不到语料：${file}\n${error.message}\n（原作语料不随仓库分发，请自行准备该文件。）`);
  process.exit(1);
}

const lines = text.split('\n').map((line) => line.trim());
const line = /^【([^】]+)】「(.*)」$/;
const spoken = (who) => lines.filter((l) => {
  const m = l.match(line);
  return m && m[1] === who;
}).map((l) => l.match(line)[2]);

const kotori = spoken('小鸟');
const kotarou = spoken('瑚太朗');
if (!kotori.length) {
  console.error(`语料里没有【小鸟】「…」形式的台词：${file}`);
  process.exit(1);
}

/** 她回应瑚太朗：前一行是瑚太朗、这一行是她。 */
const replies = [];
for (let i = 1; i < lines.length; i++) {
  const a = lines[i - 1].match(line), b = lines[i].match(line);
  if (a && b && a[1] === '瑚太朗' && b[1] === '小鸟') replies.push([a[2], b[2]]);
}

const pct = (n, total) => `${((100 * n) / total).toFixed(1)}%`;
const count = (arr, test) => arr.filter(test).length;
const top = (map, n) => [...map.entries()].sort((a, b) => b[1] - a[1]).slice(0, n);
const tally = (arr, key) => {
  const map = new Map();
  for (const item of arr) {
    const k = key(item);
    if (k) map.set(k, (map.get(k) || 0) + 1);
  }
  return map;
};

const N = kotori.length;
const R = replies.length || 1;
const out = [];
const say = (s = '') => out.push(s);

say('# 神户小鸟 · 语料拟合数据（自动生成）');
say();
say(`> 由 \`tools/kotori-style-fit.mjs\` 从 \`${file.slice(ROOT.length + 1)}\` 量出，**不要手改**。`);
say('> 原作语料有版权、不随仓库分发；没有该文件时这份报告无法重建。');
say();
say('## 一、语料规模');
say();
say('| 项 | 值 |');
say('| --- | --- |');
say(`| 场景分隔（\`===== seen… =====\`） | ${(text.match(/^===== seen/gm) || []).length} 段 |`);
say(`| 总行数 | ${lines.filter(Boolean).length} |`);
say(`| 小鸟台词 | ${N} 条 |`);
say(`| 瑚太朗台词 | ${kotarou.length} 条 |`);
say(`| 她回应瑚太朗的配对 | ${replies.length} 组 |`);
say(`| 她的平均句长 | ${(kotori.reduce((s, x) => s + x.length, 0) / N).toFixed(1)} 字 |`);
say(`| 她最长一句 | ${Math.max(...kotori.map((x) => x.length))} 字 |`);
say();

say('## 二、句长（决定了"默认多短"）');
say();
say('| 字数 | 全部台词 | 回应瑚太朗时 |');
say('| --- | --- | --- |');
for (const [lo, hi, label] of [[1, 4, '1–4'], [5, 8, '5–8'], [9, 14, '9–14'], [15, 22, '15–22'], [23, Infinity, '23+']]) {
  const a = count(kotori, (x) => x.length >= lo && x.length <= hi);
  const b = count(replies.map((r) => r[1]), (x) => x.length >= lo && x.length <= hi);
  say(`| ${label} | ${a}（${pct(a, N)}） | ${b}（${pct(b, replies.length)}） |`);
}
say();
say('## 三、行尾符号（她怎么收句）');
say();
say('| 行尾 | 条数 | 占比 |');
say('| --- | --- | --- |');
for (const [ch, c] of top(tally(kotori, (x) => x.slice(-1)), 8)) {
  say(`| \`${ch}\` | ${c} | ${pct(c, N)} |`);
}
say();
say(`含 \`…\` 的台词 ${count(kotori, (x) => x.includes('…'))} 条（${pct(count(kotori, (x) => x.includes('…')), N)}），`
  + `其中以 \`…\` 收尾 ${count(kotori, (x) => x.endsWith('…'))} 条；`
  + `整条只有省略号/顿号 ${count(kotori, (x) => /^[…、，\s]+$/.test(x))} 条。`);
say();
say(`含 \`！\` ${count(kotori, (x) => x.includes('！'))} 条（${pct(count(kotori, (x) => x.includes('！')), N)}）；`
  + `含 \`？\` ${count(kotori, (x) => x.includes('？'))} 条（${pct(count(kotori, (x) => x.includes('？')), N)}）；`
  + `含 \`～\` ${count(kotori, (x) => x.includes('～'))} 条（${pct(count(kotori, (x) => x.includes('～')), N)}）。`);
say();

say('## 四、终助词（去掉句末标点后的最后一个字）');
say();
say('| 终助词 | 条数 | 占比 |');
say('| --- | --- | --- |');
for (const [ch, c] of top(tally(kotori, (x) => (x.replace(/[。！？…～\s]+$/, '').slice(-1) || null)), 14)) {
  say(`| \`${ch}\` | ${c} | ${pct(c, N)} |`);
}
say();

say('## 五、语气词与口癖');
say();
say('| 词 | 条数 | 占比 |');
say('| --- | --- | --- |');
for (const w of ['嗯', '唔', '诶', '啊', '呀', '哦', '呢', '嘛', '啦', '吧', '呐', '哇', '嘿', '哈', '咕', '呜', '喔', '嗯～', '唔～', '诶嘿', '嘿嘿', '啊哈哈', '呜哇']) {
  const c = count(kotori, (x) => x.includes(w));
  if (c) say(`| ${w} | ${c} | ${pct(c, N)} |`);
}
say();
say(`拖音/叠字（同一个字连三下以上，如 \`～～～\`）${count(kotori, (x) => /(.)\1\1/.test(x))} 条（${pct(count(kotori, (x) => /(.)\1\1/.test(x)), N)}）；`
  + `含全角空格 \`　\` ${count(kotori, (x) => x.includes('\u3000'))} 条；`
  + `含全角英数（\`ＮＯ\`／\`ＦＵ\`）${count(kotori, (x) => /[Ａ-Ｚａ-ｚ０-９]/.test(x))} 条。`);
say();

say('## 六、她怎么称呼人');
say();
say('| 称呼 | 出现次数 |');
say('| --- | --- |');
const names = new Map();
for (const x of kotori) {
  for (const m of x.matchAll(/瑚太朗[君酱]?|小[千静露朱吉咲]|[千朱露静吉咲]早|露西娅|咲夜/g)) {
    names.set(m[0], (names.get(m[0]) || 0) + 1);
  }
}
for (const [w, c] of top(names, 10)) say(`| ${w} | ${c} |`);
say();
const self = new Map([['我', count(kotori, (x) => x.includes('我'))], ['小鸟', count(kotori, (x) => x.includes('小鸟'))]]);
say(`自称：${[...self.entries()].map(([w, c]) => `\`${w}\` ${c} 次`).join('，')}。`);
say();

say('## 七、她平时聊什么（主题词）');
say();
say('| 主题词 | 条数 |');
say('| --- | --- |');
for (const w of ['琪比', '魔物', '德鲁伊', '吃', '好吃', '便当', '饭', '点心', '动物', '鸟', '花', '草', '树', '森林', '学校', '社团', '厉害', '加油', '可爱', '讨厌', '喜欢', '对不起', '谢谢', '没事', '奇怪', '约定']) {
  const c = count(kotori, (x) => x.includes(w));
  if (c) say(`| ${w} | ${c} |`);
}
say();

say('## 八、她回应瑚太朗的方式');
say();
say('| 方式 | 组数 | 占比 |');
say('| --- | --- | --- |');
const particleOnly = replies.filter(([, b]) => /^[嗯唔诶啊哦呀呢嘛啦吧呐哇嘿哈…～！？\s]{1,4}$/.test(b)).length;
const echo = replies.filter(([a, b]) => b.length >= 2 && a.includes(b.slice(0, 2))).length;
say(`| 极短应和（≤4 字、纯语气/省略号） | ${particleOnly} | ${pct(particleOnly, R)} |`);
say(`| 反问/提问（含 \`？\`） | ${replies.filter(([, b]) => b.includes('？')).length} | ${pct(replies.filter(([, b]) => b.includes('？')).length, R)} |`);
say(`| 带感叹（含 \`！\`） | ${replies.filter(([, b]) => b.includes('！')).length} | ${pct(replies.filter(([, b]) => b.includes('！')).length, R)} |`);
say(`| 复述对方（开头 2 字与瑚太朗那句重合） | ${echo} | ${pct(echo, R)} |`);
say();

const report = out.join('\n') + '\n';
if (flag('--write')) {
  const target = resolve(ROOT, value('--out', 'docs/KOTORI-STYLE-STATS.md'));
  writeFileSync(target, report, 'utf8');
  console.log(`已写入 ${target}（小鸟台词 ${N} 条）`);
} else {
  process.stdout.write(report);
}
