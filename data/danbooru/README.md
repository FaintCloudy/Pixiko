# Danbooru 原始词库 / Raw Danbooru dictionaries

这里是**上游原始词表**，用来生成 `data/prompt-tags.txt` 与 `data/prompt-zh-tags.json`；
机器人运行时**不读**这个目录（它读的是上一层的 `data/prompt-*`）。放进仓库是为了
「不装 SD WebUI 扩展、也能按记录重建词库」。

三个文件都来自 [DominikDoom/a1111-sd-webui-tagcomplete](https://github.com/DominikDoom/a1111-sd-webui-tagcomplete)
（`tags/` 目录，上游 **MIT** © 2022 Dominik Reh，全文见 `LICENSES/MIT-tagcomplete.txt`），
一个字节都没改；`sha256` 与 `data/prompt-tags.source.json`、`data/prompt-zh-tags.json`
里记录的校验值完全一致（可据此确认拿到的是同一份）。

| 文件 | 行数 | 体积 | sha256（前 16 位） | 作用 |
|---|---|---|---|---|
| `danbooru.main-140782.csv` | 140,782 | 3.4 MB | `f936684fa0b041e9` | 上游 `main` 分支的 `tags/danbooru.csv`；**`data/prompt-tags.txt`（140,779 条标准词条）就是从它导出的**，导出时只取第 1 列（词条名），没有导入别名列 |
| `danbooru.csv` | 121,034 | 3.0 MB | `696a672050f2b154` | 本机 SD WebUI 里那份（2024-12-19 快照）。`tools/build-zh-tags.mjs` 从它取**分类与热度**（第 2 列 category、第 3 列 count），中文词库的 13 个分类与候选排序按它来 |
| `danbooru.zh_CN_SFW.csv` | 99,293 | 3.0 MB | `f6f84365d394b27c` | 上游的中文翻译表（SFW），`词条,中文` 两列；中文词库的中文写法主要来自它 |

三份都是 `词条,category,count,"别名1,别名2"` / `词条,中文` 的纯文本，UTF-8（第一份是纯 ASCII）。

## 怎么用它重建词库

```powershell
# 只校验：现有 data/prompt-zh-tags.json 是否与这些来源一致（不写文件）
node tools\build-zh-tags.mjs --check --tags-dir data\danbooru

# 真的重新生成（会覆盖 data/prompt-zh-tags.json）
node tools\build-zh-tags.mjs --tags-dir data\danbooru

# 只改手工同义词表之后重建
#   1) 编辑 data/prompt-zh-extra.txt（格式：中文=tag1,tag2）
#   2) node tools\validate-zh-extra.mjs
#   3) node tools\build-zh-tags.mjs --tags-dir data\danbooru
#   4) node tools\annotate-zh-tags.mjs        （重新生成人读用法表 data/prompt-zh-usage.md）
```

`--tags-dir` 不写时默认就是本目录（`data/danbooru`）；想用别处的词表就显式指定，
例如 SD WebUI 扩展目录 `--tags-dir "<SD WebUI 根目录>\extensions\a1111-sd-webui-tagcomplete\tags"`。

## 版权

- 词条名是 Danbooru 的标签标识符（事实性数据）；这三份文件的**编译产物**由上游以 MIT 发布，本项目原样分发。
- 中文翻译里**含机器翻译**，可能不准确。
- Pixiko 自身的代码与生成的词库仍是**保留所有权利**（见 `RELEASE.md`）；再分发本目录请保留
  `LICENSES/MIT-tagcomplete.txt` 与本说明。
