"use client";

/**
 * 图片查看器 + 统一的图片节点（对应 Java 版 `webui/app.js` 的
 * `openViewer` / `closeViewer` / `toggleViewerScale` / `imageNode` / `imageUrl`）。
 *
 * 行为逐条对齐：
 * - 点图打开查看器（背景压暗、锁住页面滚动），点图在「适应屏幕 / 1:1 原图」之间切换，
 *   `Esc`、点背景或「关闭」收起；收起时清掉 `src`（不留内存里的老图）；
 * - `Ctrl` / `Shift` / 中键点击仍然交给浏览器新标签打开（`ImageNode` 是个真 `<a href>`）；
 * - 唯一的全局状态就是"当前在看哪张图"，所以放在一个 Provider 里，任何面板都能调 `useViewer()`。
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { storedToken } from "./api";

/** `<img src>` 只能带查询串：把令牌拼进去（与 `app.js` 的 `imageUrl` 等价）。 */
export function imageUrl(file: string): string {
  const path = String(file).replace(/\\/g, "/").replace(/^.*?(data\/generated\/)/, "$1");
  const token = storedToken();
  return "/api/image?token=" + encodeURIComponent(token) + "&path=" + encodeURIComponent(path);
}

type ViewerState = { src: string; caption: string } | null;

type ViewerContext = {
  state: ViewerState;
  /** 1:1 原图模式（`app.js` 的 `viewer.actual`）。 */
  actual: boolean;
  open: (src: string, caption?: string) => void;
  close: () => void;
  toggleScale: () => void;
};

const Context = createContext<ViewerContext | null>(null);

export function ViewerProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<ViewerState>(null);
  const [actual, setActual] = useState(false);

  const open = useCallback((src: string, caption = "") => {
    if (!src) return;
    setActual(false);
    setState({ src, caption });
    document.body.classList.add("viewer-open"); // 查看器打开时锁住页面滚动
  }, []);

  const close = useCallback(() => {
    setState(null);
    setActual(false);
    document.body.classList.remove("viewer-open");
  }, []);

  const toggleScale = useCallback(() => setActual((value) => !value), []);

  // Esc 收起（对应 app.js 的 keydown 处理）。
  useEffect(() => {
    if (state === null) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") close();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [state, close]);

  // 卸载时确保不把 body 留在"锁滚动"状态。
  useEffect(() => () => document.body.classList.remove("viewer-open"), []);

  const value = useMemo<ViewerContext>(() => ({ state, actual, open, close, toggleScale }), [state, actual, open, close, toggleScale]);
  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function useViewer(): ViewerContext {
  const value = useContext(Context);
  if (value === null) throw new Error("useViewer 必须在 ViewerProvider 里使用。");
  return value;
}

/**
 * 统一的图片节点：包一层链接（中键/`Ctrl` 可以新标签打开），左键点击打开查看器。
 *
 * `file` 可以是：
 * - 服务端给的相对路径（`data/generated/…`）→ 走 `imageUrl()` 拼令牌；
 * - 已经带令牌的代理地址（`/api/…`，例如 Civitai 封面 `/api/civitai/thumb?…`）；
 * - `data:` URL 或完整的 `http(s)://` 地址 → 原样使用。
 */
export function ImageNode({
  file,
  caption,
  className,
  children,
}: {
  file: string;
  caption?: string;
  className?: string;
  /** 挂在 `<a class="image-link">` 里的其它内容（例如出图网格的 `.meta` 文件名行）。 */
  children?: ReactNode;
}) {
  const { open } = useViewer();
  const src = /^(data:|https?:|\/\/|\/api\/)/.test(file) ? file : imageUrl(file);
  const label = caption ?? String(file).replace(/^.*[\\/]/, "");
  return (
    <a
      className="image-link"
      href={src}
      title={(caption ? caption + " · " : "") + "点击放大（Esc 关闭）"}
      onClick={(event) => {
        if (event.metaKey || event.ctrlKey || event.shiftKey) return; // 保留浏览器的新标签行为
        event.preventDefault();
        open(src, caption);
      }}
    >
      <img className={className} loading="lazy" src={src} alt={caption || "图片"} />
      {children}
    </a>
  );
}
