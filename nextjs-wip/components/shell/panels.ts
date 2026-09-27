/**
 * 10 个栏目的路径与页签文案。
 *
 * 路径必须与 Java 版 `WebPageController.PAGES` 完全一致（见 docs/MIGRATION.md）：
 * `/`(chat) `/gen` `/prompt` `/styles` `/loras` `/functions` `/chatcfg` `/system` `/logs` `/help`。
 * 页签文案逐字照抄 `webui/index.html` 的 `<nav class="tabs">`。
 */

export type PanelId =
  | "chat"
  | "gen"
  | "prompt"
  | "styles"
  | "loras"
  | "functions"
  | "chatcfg"
  | "system"
  | "logs"
  | "help";

export type PanelTab = {
  /** 栏目 id，同时也是面板的 `id="panel-<id>"` 与 `data-tab`。 */
  id: PanelId;
  /** 该栏目的 URL（Java 版与 Next 版共用同一套路径）。 */
  href: string;
  /** 页签文案。 */
  label: string;
};

/** 页签顺序与 `webui/index.html` 里的 `<nav class="tabs">` 一致。 */
export const PANEL_TABS: readonly PanelTab[] = [
  { id: "chat", href: "/", label: "对话" },
  { id: "gen", href: "/gen", label: "出图" },
  { id: "prompt", href: "/prompt", label: "提示词" },
  { id: "styles", href: "/styles", label: "样式" },
  { id: "loras", href: "/loras", label: "LoRA" },
  { id: "functions", href: "/functions", label: "提示词集" },
  { id: "chatcfg", href: "/chatcfg", label: "聊天" },
  { id: "system", href: "/system", label: "系统" },
  { id: "logs", href: "/logs", label: "控制台" },
  { id: "help", href: "/help", label: "帮助" },
] as const;
