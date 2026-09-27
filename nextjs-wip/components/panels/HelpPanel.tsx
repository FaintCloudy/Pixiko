"use client";

/**
 * 帮助栏（`/help`）：指令帮助，与控制台、QQ 侧完全同源。
 *
 * `/api/help` 直接返回 130 行帮助文本；`#help-body` 是 `.logs`（等宽 pre），
 * `white-space: pre-wrap` 由样式负责，原文的换行与缩进原样保留。
 *
 * 对应 `webui/app.js` 的 `loadHelp`(1459)：进帮助页拉一次，`data.help` 填进 `#help-body`。
 * Java 版读取失败是静默忽略（帮助文本不参与任何写操作）；这里沿用骨架的 `showBanner`，便于排查。
 */
import { useCallback, useEffect, useState } from "react";
import { apiRequest, errorText } from "../client/api";
import { useConsole } from "../client/store";

export function HelpPanel() {
  const { unlocked, showBanner } = useConsole();
  const [help, setHelp] = useState("");

  const load = useCallback(async () => {
    try {
      const data = await apiRequest<{ help?: string }>("/api/help");
      setHelp(data.help ?? "");
    } catch (error) {
      showBanner("帮助读取失败：" + errorText(error));
    }
  }, [showBanner]);

  useEffect(() => {
    if (!unlocked) return;
    void load();
  }, [unlocked, load]);

  return (
    <section className="panel active" id="panel-help">
      <div className="card">
        <div className="card-head"><b>指令帮助</b><span className="muted">与控制台、QQ 侧完全同源</span></div>
        <pre className="logs" id="help-body">{help}</pre>
      </div>
    </section>
  );
}
