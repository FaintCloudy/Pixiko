"use client";

import type { ReactNode } from "react";
import { CaptureProvider, RECEIPT_BOXES } from "../client/capture";
import { ConsoleProvider } from "../client/store";
import { ViewerProvider } from "../client/viewer";
import { Shell } from "./Shell";
import type { PanelId } from "./panels";

/**
 * 一个栏目的整页：外壳 + **只属于本栏目**的那一块面板。
 *
 * 面板必须是 `<section class="panel …" id="panel-<栏目>">` 的形式，并且带 `active`：
 * `.panel` 默认 `display:none`，少了 `active` 整页元素尺寸算出来是 0
 * （终端那种按高度算的布局会直接塌掉）。这与 Java 版 `WebPages.render` 的做法一致。
 *
 * 三层 Provider 的职责（都对应用户在 Java 版里看到的行为，不是实现细节）：
 * - `ConsoleProvider`：令牌、顶栏状态轮询、横幅、轻提示、同风格确认框；
 * - `ViewerProvider`：点图放大的查看器；
 * - `CaptureProvider`：网页指令通道与回执（盒子按栏目分，对应 Java 的 `#<栏目>-receipts`）。
 *
 * 子节点仍然是服务端组件（`children` 作为 props 传进来，不会把面板拖进客户端包）。
 */
export function PanelPage({ id, children }: { id: PanelId; children: ReactNode }) {
  return (
    <ConsoleProvider>
      <ViewerProvider>
        <CaptureProvider box={RECEIPT_BOXES[id] ?? null}>
          <Shell active={id}>{children}</Shell>
        </CaptureProvider>
      </ViewerProvider>
    </ConsoleProvider>
  );
}
