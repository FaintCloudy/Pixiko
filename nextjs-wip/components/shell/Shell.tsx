"use client";

/**
 * 页面外壳（对应 `webui/index.html` 的 `<body>` 里除面板块以外的全部内容）：
 * 极光背景、顶栏、页签、横幅、页脚、登录页、弹窗、图片查看器。
 *
 * 每个栏目一个页面，页面上**只有**本栏目自己的面板 —— `children` 就是那一块面板，
 * 与 Java 版 `WebPages.render` 的拼装结果等价。
 *
 * 登录/锁定流程与 Java 版 `lock()` / `unlock()` 对齐：
 * - 没令牌（或点了「锁定」）→ `#app` 带 `hidden`、显示 `#login`，**不发任何 /api 请求**；
 * - 填令牌 → 用 `/api/status` 验证 → 存 localStorage → 隐藏 `#login`、显示 `#app`；
 * - 读 localStorage 在挂载之后做，`ready` 之前两边都收着，避免闪屏与水合不一致。
 */
import type { ReactNode } from "react";
import { useConsole } from "../client/store";
import { ShellDialog } from "./ShellDialog";
import { ShellFooter } from "./ShellFooter";
import { ShellHeader } from "./ShellHeader";
import { ShellTabs } from "./ShellTabs";
import { ShellViewer } from "./ShellViewer";
import type { PanelId } from "./panels";

export function Shell({ active, children }: { active: PanelId; children: ReactNode }) {
  const { ready, unlocked, banner, showBanner } = useConsole();
  const showLogin = ready && !unlocked;

  return (
    <>
      <div className="aurora" aria-hidden="true" />

      <ShellHeader />

      {/* `data-page` 就是 Java 版 `window.PIXIKO_PAGE` 的位置：服务端渲染时就知道当前栏目是哪一个，
          给探针（`tools/webui-selfcheck.mjs`）和调试留一个稳定的锚点，客户端逻辑不依赖它。 */}
      <main id="app" data-page={active} hidden={!unlocked}>
        <div className="banner error" id="banner" hidden={!banner}>
          {banner}
          {banner ? (
            <button className="ghost small" type="button" style={{ marginLeft: 10 }} onClick={() => showBanner("")}>
              知道了
            </button>
          ) : null}
        </div>

        <ShellTabs active={active} />

        {children}
      </main>

      <ShellFooter />

      <LoginSection hidden={!showLogin} />

      <ShellDialog />
      <ShellViewer />
    </>
  );
}

/** 登录页：填令牌 → `/api/status` 验证 → 存本机。提示语与 Java 版一致。 */
function LoginSection({ hidden }: { hidden: boolean }) {
  const { authNotice, busy, unlock } = useConsole();

  return (
    <section id="login" className="login" hidden={hidden}>
      <h1>Pixiko · 控制台</h1>
      <p className="muted">输入访问令牌（config.json 的 <code>webui.access_token</code>）。令牌只保存在本机浏览器。</p>
      <form
        id="login-form"
        onSubmit={(event) => {
          event.preventDefault();
          const input = event.currentTarget.elements.namedItem("token");
          const value = input instanceof HTMLInputElement ? input.value : "";
          void unlock(value);
        }}
      >
        <input id="token" name="token" type="password" autoComplete="current-password" placeholder="访问令牌" />
        <button type="submit" disabled={busy}>{busy ? "验证中…" : "进入"}</button>
      </form>
      <p className="error" id="login-error">{authNotice}</p>
    </section>
  );
}
