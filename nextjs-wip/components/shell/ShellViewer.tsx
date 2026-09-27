"use client";

/**
 * 图片查看器容器：点图放大，背景压暗（不再跳到新标签页）。
 *
 * 结构照抄 `webui/index.html` 里的 `<div class="viewer" id="viewer">`，
 * 行为（打开/收起/1:1 切换/Esc/点背景收起/锁页面滚动）全在 `client/viewer.tsx` 里，
 * 这里只把状态映射成 Java 版那套 class 与 hidden。
 */
import { useViewer } from "../client/viewer";

export function ShellViewer() {
  const { state, actual, close, toggleScale } = useViewer();
  const open = state !== null;
  return (
    <div className={actual ? "viewer actual" : "viewer"} id="viewer" hidden={!open}>
      <div className="viewer-stage" id="viewer-stage" onClick={close}>
        {/* 收起时清掉 src：不留内存里的老图（app.js closeViewer 同款） */}
        <img
          id="viewer-image"
          alt="图片"
          src={open ? state.src : undefined}
          onClick={(event) => {
            event.stopPropagation();
            toggleScale();
          }}
        />
      </div>
      <div className="viewer-bar">
        <span className="viewer-caption" id="viewer-caption">{open ? state.caption : ""}</span>
        <span className="viewer-hint">点图切换 适应屏幕 / 原图 · Esc 关闭</span>
        <button className="ghost small" type="button" id="viewer-toggle" onClick={toggleScale}>
          {actual ? "适应屏幕" : "1:1 原图"}
        </button>
        <button className="ghost small" type="button" id="viewer-close" onClick={close} autoFocus={open}>
          关闭
        </button>
      </div>
    </div>
  );
}
