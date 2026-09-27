"use client";

/**
 * 控制台栏（`/logs`）：真 CLI —— 默认全屏、提示符与日志同一股流。
 *
 * 交互与 `webui/app.js` 对齐：
 * - `#logs-source` / `#logs-filter` / `#logs-follow` / `#logs-reload` / `#terminal-clear`
 *   都由 `Terminal` 渲染并生效（来源与跟随会触发增量重读，关键字是纯前端过滤）；
 * - `#terminal-fullscreen` 切换 `body.console-full`（默认开，和 Java 版一样全屏进控制台）；
 * - Esc 退出全屏；离开本栏目时把 `console-full` 摘掉。
 */
import { useEffect, useState } from "react";
import { useConsole } from "../client/store";
import { Terminal } from "../shell/Terminal";

export function LogsPanel() {
  const { unlocked } = useConsole();
  const [source, setSource] = useState("all");
  const [filter, setFilter] = useState("");
  const [follow, setFollow] = useState(true);
  const [fullscreen, setFullscreen] = useState(true);

  /** 控制台栏默认全屏：进栏目开、离开摘掉（对应 app.js `syncConsoleFullscreen`）。 */
  useEffect(() => {
    if (!unlocked) return;
    document.body.classList.toggle("console-full", fullscreen);
    return () => document.body.classList.remove("console-full");
  }, [fullscreen, unlocked]);

  useEffect(() => {
    if (!fullscreen) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        setFullscreen(false);
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [fullscreen]);

  return (
    <section className="panel terminal-panel active" id="panel-logs">
      <Terminal
        mode="logs"
        active
        source={source}
        filter={filter}
        follow={follow}
        onSourceChange={(next) => setSource(next)}
        onFilterChange={(next) => setFilter(next)}
        onFollowChange={(next) => setFollow(next)}
        fullscreen={fullscreen}
        onToggleFullscreen={() => setFullscreen((value) => !value)}
      />
    </section>
  );
}
