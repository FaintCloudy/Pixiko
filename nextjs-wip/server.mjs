/**
 * 自定义服务器：同一进程里跑 **两个模块图** —— Next（Web 控制台）与机器人本体。
 *
 * 为什么要这样：`next build`/页面数据收集会**求值**路由所在 chunk 里的表达式，而机器人那堆模块
 * 有大量 BigInt 运算，静态求值器会把 `BigInt(x) * 1000n` 折成「数字 ⊗ BigInt」并崩
 * （`Cannot mix BigInt and other types`）。实测 instrumentation、路由静态 import、路由动态 import
 * 三种写法都会让 build 失败，所以机器人必须**绕开 webpack**：由本文件用 Node 的类型擦除直接 import。
 *
 * 用法：
 * ```
 * node server.mjs                 # 只起 Web 控制台（默认，不连 QQ）
 * $env:PIXIKO_BOT='on'; node server.mjs   # 同时把机器人拉起来（连 NapCat）
 * node server.mjs --port 8788     # 换端口（默认取 config.json 的 webui.port）
 * ```
 * 迁移期与 Java 版并行时要加 `PIXIKO_LOCK=off`（Java 握着 `data/bot.lock`）。
 */
import { createServer } from "node:http";
import path from "node:path";

const args = process.argv.slice(2);
function flag(name, fallback) {
  const at = args.indexOf(`--${name}`);
  return at >= 0 && args[at + 1] ? args[at + 1] : fallback;
}
const dev = args.includes("--dev");

// ── 1. 基础设施（日志、单实例锁、数据目录）与机器人 ────────────────────────────
const { bootstrap } = await import("./lib/core/runtime.ts");
const runtime = await bootstrap();
const { info, warn, error } = await import("./lib/util/log.ts");
const { errorText } = await import("./lib/util/errors.ts");

const hostname = flag("host", process.env.HOST ?? "0.0.0.0");
const port = Number(flag("port", process.env.PORT ?? String(runtime.settings.webPort())));

const enabled = (process.env.PIXIKO_BOT ?? "").trim().toLowerCase();
const botWanted = enabled === "on" || enabled === "1" || enabled === "true";

let bot = null;
if (botWanted) {
  const { bot: getBot } = await import("./lib/core/bot.ts");
  const { setBotInstance } = await import("./lib/core/bot-holder.ts");
  bot = getBot();
  setBotInstance(bot);
  // 连接失败不阻塞 Web：QQ 掉线时控制台照常可用（与 Java 版一致）。
  try {
    await bot.start({ announceOnline: true });
    info("机器人已启动（QQ 连接中）：" + runtime.root);
  } catch (failure) {
    error("机器人启动失败（Web 控制台继续可用）：" + errorText(failure));
  }
} else {
  info("QQ 连接未启用（PIXIKO_BOT=on 可开启）；Web 控制台照常可用。");
}

// ── 2. Next ──────────────────────────────────────────────────────────────────
const nextModule = await import("next");
const next = nextModule.default ?? nextModule;
const app = next({ dev, hostname, port });
await app.prepare();
const handle = app.getRequestHandler();

const server = createServer((request, response) => {
  void handle(request, response).catch((failure) => {
    error("请求处理失败：" + errorText(failure));
    if (!response.headersSent) response.statusCode = 500;
    response.end("Internal Server Error");
  });
});

server.listen(port, hostname, () => {
  info(`Pixiko（Next.js 版）监听 http://${hostname}:${port}${botWanted ? "（含机器人）" : "（仅控制台）"}`);
});

// ── 3. 停机 ──────────────────────────────────────────────────────────────────
let closing = false;
async function shutdown(signal) {
  if (closing) return;
  closing = true;
  info(`收到 ${signal}，正在关闭。`);
  if (bot !== null) {
    try {
      await bot.close();
    } catch (failure) {
      warn("机器人停机失败：" + errorText(failure));
    }
  }
  server.close(() => process.exit(0));
  // 兜底：10 秒内没关完就直接退出（长连接可能挂着）。
  setTimeout(() => process.exit(0), 10_000).unref();
}
process.once("SIGINT", () => void shutdown("SIGINT"));
process.once("SIGTERM", () => void shutdown("SIGTERM"));

void path;
