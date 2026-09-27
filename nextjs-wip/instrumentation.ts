/**
 * Next.js 启动钩子：只做**基础设施初始化**（日志、单实例锁、数据目录）。
 *
 * ⚠️ 这里**绝对不能 import 机器人本体**（哪怕动态 import）：instrumentation 是 Next 构建期
 * 会追踪/求值的模块，而机器人那一堆文件里有 BigInt 运算，构建期的静态求值器会在
 * 「BigInt 与数字混用」上崩掉（`Cannot mix BigInt and other types`），整个 build 会失败。
 *
 * 机器人的启动放在 `server.mjs`（自定义服务器，见 package.json 的 start 脚本）：
 * 它先 `bootstrap()`，再按 `PIXIKO_BOT=on` 决定是否连 QQ，最后把请求交给 Next。
 */
export async function register(): Promise<void> {
  if (process.env.NEXT_RUNTIME !== "nodejs") return;
  const { bootstrap } = await import("./lib/core/runtime");
  await bootstrap();
  const Log = await import("./lib/util/log");
  const enabled = (process.env.PIXIKO_BOT ?? "").trim().toLowerCase();
  Log.info(
    enabled === "on" || enabled === "1" || enabled === "true"
      ? "QQ 连接将由 server.mjs 拉起（PIXIKO_BOT=on）。"
      : "QQ 连接未启用（PIXIKO_BOT=on 可开启）；Web 控制台照常可用。",
  );
}
