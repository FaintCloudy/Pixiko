import { PANEL_TABS, type PanelId } from "./panels";

/**
 * 页签：10 个栏目，**真正的 `<a href>` 链接**（每个栏目一个 URL，切栏目＝换页面）。
 *
 * 结构与 `webui/index.html` 的 `<nav class="tabs">` 逐项对应；当前栏目的页签带 `active`，
 * 与 Java 版 `WebPages.render` 的替换结果等价（那两处本来就是服务端决定谁是 active）。
 */
export function ShellTabs({ active }: { active: PanelId }) {
  return (
    <nav className="tabs" role="tablist">
      {PANEL_TABS.map((tab) => (
        <a
          key={tab.id}
          className={tab.id === active ? "tab active" : "tab"}
          data-tab={tab.id}
          role="tab"
          aria-selected={tab.id === active}
          href={tab.href}
        >
          {tab.label}
        </a>
      ))}
    </nav>
  );
}
