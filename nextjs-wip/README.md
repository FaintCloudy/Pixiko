# nextjs-wip —— 一次**未完成**的 Next.js 重构（请勿用于生产）

这个目录里放的是把 Pixiko 从 Java 改写成 **Next.js + TypeScript** 的那次尝试的残留代码。
它**不能构建、不能运行**，也不代表项目的当前状态。

## 为什么不能跑

- **后端 `lib/**` 已丢失。** 那次重构把机器人本体（命令解析、生成队列、DeepSeek 两条通道、
  Civitai 客户端与登录链路、SD WebUI 客户端与自启动、Web 鉴权……约 60 个文件）
  全部搬进了 `lib/**`，而这批文件没有保留下来。
- 这个目录里的 `app/**`（路由与接口）和 `components/**`（控制台外壳与 10 个面板）
  都靠 `import ... from '@/lib/...'` 拿到后端；`lib/**` 一没，类型解析和运行时都断在入口。
- **`tests/**` 没有随仓库提供**：那一批 TS 测试断言的都是 `lib/**` 的行为，
  依赖同一个已丢失的后端，因此同样跑不起来。留着一堆必定失败的测试只会误导人。

## 目录里还有什么

| 路径 | 内容 |
|---|---|
| `app/` | Next.js App Router 路由：10 个栏目页 + 全部 `/api/**`（含 `civitai-login` / `civitai-cookie` 往返） |
| `components/` | `shell/`（外壳、页签、终端、图片查看器）、`panels/`（10 个面板）、`client/`（令牌与状态、接口客户端、回执） |
| `public/` | 图标（与 `webui/` 里那份重复；保留是为了让目录结构完整） |
| `server.mjs` | 自定义 server：一个进程、两个模块图 |
| `instrumentation.ts` | 启动时拉起 QQ / SD / 队列等后台服务 |
| `next.config.ts` | Next 配置（`serverExternalPackages`、`outputFileTracingExcludes`） |
| `tsconfig.json` | TypeScript 配置，路径别名 `@/lib/...` |
| `package.json` / `package-lock.json` | Next 16 / React 19 / TypeScript 5.9 的依赖清单 |

没有包含：`lib/**`（丢失）、`tests/**`（依赖丢失的 `lib/**`）、`next-env.d.ts`（Next 自动生成，
已在 `.gitignore` 里）、`.next/`、`node_modules/`。

## 那它还有什么用

- 记录了**接口形状**：REST 路由、面板 id/class（与 `webui/index.html` 逐块对得上）、
  Civitai 一次性登录的整条往返链路。想重做一遍网页层时可以直接抄。
- `docs/MIGRATION.md`、`docs/PANEL-PORT.md`、`docs/PORTING.md` 是这次重构的过程记录与约定，
  里面保留了 Java 版与 TS 版**逐条行为对照**，读的时候记得它们是**历史记录**。

## 当前实现是哪一份

**Java 版**：仓库根目录的 `src/`（机器人本体 + 测试）、`webui/`（网页控制台）、
`webui-extension/`（SD WebUI 桥接扩展）。见根目录 `README.md`。
