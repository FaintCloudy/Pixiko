# 代码约定（Java → TypeScript 迁移期订下的）

> **发布说明：** 这份约定属于那次**未完成的** Next.js + TypeScript 重写——后端 `lib/**` 已丢失，
> 相关代码见 `nextjs-wip/`（不可运行）。本仓库**当前实现是 Java 版（`src/`）**，下文所有
> `lib/**`、`app/**`、`components/**` 的约定对 Java 版不适用，只作为那次重写的记录保留。

迁移期为了让 Java 版与 Next.js 版行为逐条对得上而订的规矩，**迁移完成之后仍然是本仓库的代码约定**
（`lib/**` 是机器人本体，`app/**` + `components/**` 是网页层）。

## 语言与运行时

- TypeScript `strict`，目标 Node 24；`lib/**` 里的相对 import **必须带 `.ts` 后缀**
  （Node 24 原生跑 TS，不带后缀 Node 解析不了；`app/**` 走 Next 别名 `@/lib/...` 才可省略）。
- 只能用**可擦除语法**：不要 `enum`、`namespace`、构造函数参数属性（`constructor(private x: T)`）、
  装饰器 —— Node 的类型擦除不支持这些。
- 不要新增 npm 依赖。`node:` 内置模块优先（`node:fs`、`node:path`、`node:http`、`node:crypto`）。
- 不用 `any`；确实动态的地方用 `unknown` + 窄化。

## 对应关系

| Java | TypeScript |
| --- | --- |
| `Json.parse/obj/str/num/bool` | `lib/util/json.ts` 同名函数（`int` 是截断版 `num`，`arr`/`stringList` 对应 JsonArray） |
| `Json.GSON.toJson` | `stringify()`（2 空格、不转义 HTML、末尾换行） |
| `Json.atomicWrite` | `atomicWrite()` / `update()`（读-改-写，自动串行） |
| `String.strip()` / `isBlank()` | `lib/util/text.ts` 的 `javaStrip()` / `javaIsBlank()` —— **不要用 JS 的 `trim()`** |
| `replaceAll("(?U)\\s+", " ")` | `lib/util/text.ts` 的 `JAVA_UNICODE_SPACE_RUN`（JS 的 `\s` 多认一个 BOM） |
| `Log.info/warn/error/text/raw` | `lib/util/log.ts` 同名函数（`warn(msg, e)` → `warnError(msg, e)`） |
| `Bot.error(e)` | `lib/util/errors.ts` 的 `errorText(e)`（剥 cause、截断 600 字），用户可见文案用 `reason(e)` |
| `Path root`、`settings.root` | 构造函数收一个 `root: string`（绝对路径），不要用 `process.cwd()` |
| `IllegalArgumentException` | `throw new Error("同样的中文提示")`；`IllegalStateException` 同理，调用方按消息区分 |
| `synchronized` 方法 | 读写同一文件时用 `update()`/`withFileLock()`；纯内存状态不需要锁 |

## 行为要求

1. **提示文案逐字保留**（中文、标点、"。"都要一样）：用户在 QQ 和网页上看到的就是这些字。
2. **数据文件格式不变**：键名、层级、默认值照抄。老文件必须能直接读，新写的文件必须能被 Java 版读回。
3. **默认值照抄**：Java 里 `Json.num(o,"x",15)` 就是默认 15，不要"顺手"改。
4. **异常语义照抄**：Java 抛异常的地方这里也抛，别改成返回 null；消息一致。
5. 文件 IO 用同步版即可（与 Java 语义最接近，也避免并发交错）；只有网络请求用 `async`。
6. 敏感信息（API key、Cookie）不进日志。
7. **空白判定必须用 `lib/util/text.ts`**：JS 的 `trim()` 会去掉 NBSP(`\u00A0`) 和 BOM(`\uFEFF`)，
   Java 的 `strip()` 不会。从聊天软件粘过来的提示词经常夹着 NBSP，混用会让"看起来一样"的两个词条
   被算成两个（或反过来被错误合并）。同理，Java 的 `(?U)\s` 不认 BOM，JS 的 `\s` 认。
8. 校验类异常抛 `lib/util/errors.ts` 的 `BadInput`（→ HTTP 400），状态冲突抛 `Conflict`（→ 409）；
   其余异常按 500 处理。别用裸 `Error` 表达"用户输入不合法"。

## 测试要求

- 用 `node:test` + `node:assert/strict`，文件放 `tests/<名字>.test.ts`。
- 每个 Java 测试类都要有对应用例，断言**行为**（数字、字符串、文件内容），不要只断言"没抛异常"。
- 临时目录统一 `fs.mkdtempSync(path.join(os.tmpdir(), "pixiko-<模块>-"))`。
- 单跑自己的用例：`node --test tests/<名字>.test.ts`。
- 类型检查：`cmd /c npx.cmd tsc --noEmit`（只关心自己文件的报错；别人文件的报错在报告里提一句）。

## 不要动这些文件

`package.json`、`tsconfig.json`、`next.config.ts`、`lib/util/**`、`lib/config/settings.ts`、
`lib/core/**`、`app/**`、`docs/**`、`webui/**`、`src/**`、`tools/**`、`backup/**`。
确有必要改，先在报告里说明理由。

## 并行工作的硬约束

1. **同一时间只跑一个 `next build`**：构建会重写 `.next/`，正在运行的 `next start` 会立刻报
   `Cannot find module '.next/server/middleware-manifest.json'`。需要验证页面时，先在报告里说明，
   或者改用 `node --test` / 直接 import 模块的方式验证逻辑。
2. **端口分配固定**：Java 版 8787（迁移期间不要动）、Next 迁移验证 8788、别人临时起的服务用 8789+。
3. **`data/` 只读**：那是 9 GB 真实数据，测试一律用 `fs.mkdtempSync(path.join(os.tmpdir(), ...))`。
4. 一个模块一个 owner：只写自己负责的文件，别人的文件哪怕有 bug 也只写进报告（由父 agent 统一改）。
