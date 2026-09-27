// 校验中文→标准词映射文件：每行 `中文=tag1,tag2`（左侧可用 `/` 分隔多个中文同义词），
// 打印所有不在 data/prompt-tags.txt 里的词条，以及格式错误的行。
// 用法：node tools/validate-zh-extra.mjs data\prompt-zh-extra.txt [更多文件...]
import fs from 'node:fs';
import path from 'node:path';

const REPO = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..');
const dict = new Set();
for (const line of fs.readFileSync(path.join(REPO, 'data/prompt-tags.txt'), 'utf8').split(/\r?\n/)) {
  const t = line.trim().toLowerCase();
  if (!t) continue;
  dict.add(t);
  dict.add(t.replace(/_/g, ' '));
}

const has = (tag) => {
  const t = tag.trim().toLowerCase();
  return dict.has(t) || dict.has(t.replace(/_/g, ' ')) || dict.has(t.replace(/ /g, '_'));
};
const isChinese = (text) => /[\u4e00-\u9fff]/.test(text);

let bad = 0, unknown = 0, ok = 0, dup = 0;
const seen = new Map();
for (const file of process.argv.slice(2)) {
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
  lines.forEach((raw, index) => {
    const line = raw.trim();
    if (!line || line.startsWith('#')) return;
    const at = line.indexOf('=');
    if (at < 0) { console.log(`${file}:${index + 1} 缺少等号: ${line}`); bad++; return; }
    const removing = line.startsWith('!');
    const left = (removing ? line.slice(1, at) : line.slice(0, at)).trim();
    const right = line.slice(at + 1).trim();
    if (!left || !right) { console.log(`${file}:${index + 1} 左右为空: ${line}`); bad++; return; }
    for (const word of left.split('/')) {
      if (!word.trim() || !isChinese(word)) { console.log(`${file}:${index + 1} 左侧不是中文: ${line}`); bad++; }
      else if (word.trim().length > 14) { console.log(`${file}:${index + 1} 中文过长: ${word}`); bad++; }
    }
    if (left.split('/').length > 8) { console.log(`${file}:${index + 1} 同义词过多: ${line}`); bad++; }
    // `!中文=词条…` 是"从这些词条里删掉这个写法"，一条可以点名很多词条。
    if (right.split(',').length > (removing ? 60 : 6)) { console.log(`${file}:${index + 1} 词条过多: ${line}`); bad++; }
    for (const tag of right.split(',')) {
      const t = tag.trim();
      if (!t) { console.log(`${file}:${index + 1} 空词条: ${line}`); bad++; continue; }
      if (!/^[a-z0-9_()\-+ !?:/]+$/i.test(t)) { console.log(`${file}:${index + 1} 词条写法异常: ${t}`); bad++; continue; }
      if (!removing && (t.includes('(') || t.includes(')') || t.includes(':'))) { console.log(`${file}:${index + 1} 词条带括号/冒号（机器人会过滤）: ${t}`); bad++; continue; }
      if (!has(t)) { console.log(`${file}:${index + 1} 词库中不存在: ${t}`); unknown++; continue; }
      ok++;
      const previous = seen.get(left + '|' + t.toLowerCase());
      if (previous) { dup++; } else { seen.set(left + '|' + t.toLowerCase(), file + ':' + (index + 1)); }
    }
  });
}
console.log(`\n合计: 有效词条 ${ok} 行，词库外 ${unknown}，格式错误 ${bad}，跨文件重复 ${dup}`);
if (unknown || bad) process.exitCode = 1;
