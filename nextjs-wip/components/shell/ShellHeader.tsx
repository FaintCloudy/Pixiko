"use client";

/**
 * 顶栏：健康点 + 机器人名字 + 「刷新 / 锁定」。
 *
 * DOM 照抄 `webui/index.html` 的 `<header class="bar">`，样式全部来自 app.css。
 * 数据来自 `ConsoleProvider`（8 秒轮询 `/api/status`，页面隐藏时停）：
 * - 没登录 → 状态点灰、副标题「控制台」、两个按钮不可用（不发请求）；
 * - 已登录 → `#health` 变 `dot ok`、`#subtitle` 显示 `归属 · :端口`、`#botname` 用配置里的名字；
 * - 读取失败 → 状态点 `dot bad` + 顶部横幅说明原因。
 */
import { useConsole } from "../client/store";

export function ShellHeader() {
  const { unlocked, status, health, refresh, lock } = useConsole();
  const dotClass = health === "ok" ? "dot ok" : health === "bad" ? "dot bad" : "dot";
  const subtitle = status ? `${status.scope ?? ""} · :${status.webPort ?? ""}` : "控制台";

  return (
    <header className="bar">
      <div className="brand">
        <span className={dotClass} id="health" />
        <div className="brand-text">
          <b id="botname">{status?.botName || "神户小鸟"}</b>
          <span className="muted" id="subtitle">{subtitle}</span>
        </div>
      </div>
      <div className="bar-actions">
        <button
          className="ghost"
          id="refresh"
          type="button"
          disabled={!unlocked}
          title={unlocked ? "重新读取 /api/status" : "需要先填写访问令牌"}
          onClick={() => void refresh()}
        >
          刷新
        </button>
        <button
          className="ghost"
          id="lock"
          type="button"
          disabled={!unlocked}
          title={unlocked ? "清除本机保存的令牌" : "还没有登录"}
          onClick={() => lock("")}
        >
          锁定
        </button>
      </div>
    </header>
  );
}
