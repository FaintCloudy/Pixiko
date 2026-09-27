// 下线播报：进程被强杀前，先经 OneBot 正向 WebSocket 发一条下线消息。
//
// 为什么需要它：Windows 上 `Stop-Process` 是强杀，进程拿不到 SIGINT/SIGTERM，
// 关闭钩子（`server.mjs` 里的 SIGINT/SIGTERM → `bot.close()`）不会运行，
// 所以由 `stop-bot.ps1` 在杀进程之前单独把这条消息发出去。
//
// 用法：node tools/offline-notice.mjs [根目录]
import { readFileSync } from "node:fs";

const root = process.argv[2] ?? ".";
let config;
try {
  config = JSON.parse(readFileSync(`${root}/config.json`, "utf8"));
} catch {
  process.exit(0); // 没有配置就没什么可播报的
}
const group = String(config.startup_group_id ?? "");
if (!/^[1-9][0-9]{0,19}$/.test(group)) process.exit(0);
const text = config.shutdown_notice ?? "我先下线啦，稍后再见。";
const url = config.qq?.ws_url ?? config.onebot?.ws_url ?? "ws://127.0.0.1:3001";

let finished = false;
const finish = (code, note) => {
  if (finished) return;
  finished = true;
  if (note) console.log(note);
  try {
    socket.close();
  } catch {}
  process.exit(code);
};

const socket = new WebSocket(url);
const timer = setTimeout(() => finish(2, "下线播报超时（未收到 OneBot 响应）。"), 8000);
socket.addEventListener("open", () =>
  socket.send(
    JSON.stringify({
      action: "send_group_msg",
      params: { group_id: group, message: text },
      echo: "shutdown-notice",
    }),
  ),
);
socket.addEventListener("message", () => {
  clearTimeout(timer);
  finish(0, `下线播报已发送到群 ${group}。`);
});
socket.addEventListener("error", () => {
  clearTimeout(timer);
  finish(1, "下线播报失败：OneBot 连接不可用。");
});
