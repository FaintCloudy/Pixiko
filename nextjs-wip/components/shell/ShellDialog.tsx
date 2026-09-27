"use client";

/**
 * 弹窗容器：同风格的确认框（**替代浏览器自带的 `confirm`/`prompt`**，弹出时整页压暗）。
 *
 * 结构照抄 `webui/index.html` 里的 `<div class="overlay" id="overlay">`，
 * 行为照 `webui/app.js` 的 `askDialog`：Esc / 点背景 / 取消 → null；确定 → true（输入框模式返回文本）；
 * 收起后把焦点还给打开它的那个元素。
 */
import { useEffect, useRef } from "react";
import { useConsole } from "../client/store";

export function ShellDialog() {
  const { dialog, finishDialog } = useConsole();
  const inputRef = useRef<HTMLInputElement | null>(null);
  const restoreRef = useRef<Element | null>(null);
  const withInput = dialog?.value !== undefined && dialog?.value !== null;

  /** 打开时记住焦点、按输入框/确定键聚焦；收起时还原（与 app.js 一致）。 */
  useEffect(() => {
    if (!dialog) return;
    restoreRef.current = document.activeElement;
    const timer = window.setTimeout(() => {
      if (withInput) inputRef.current?.focus();
      else document.getElementById("dialog-ok")?.focus();
    }, 20);
    return () => {
      window.clearTimeout(timer);
      const restore = restoreRef.current;
      if (restore instanceof HTMLElement) restore.focus();
    };
  }, [dialog, withInput]);

  useEffect(() => {
    if (!dialog) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        finishDialog(null);
      } else if (event.key === "Enter") {
        event.preventDefault();
        accept();
      }
    };
    document.addEventListener("keydown", onKey, true);
    return () => document.removeEventListener("keydown", onKey, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dialog, finishDialog]);

  function accept() {
    const input = inputRef.current;
    finishDialog(withInput ? (input?.value ?? "").trim() : true);
  }

  return (
    <div
      className="overlay"
      id="overlay"
      hidden={!dialog}
      onClick={(event) => {
        if (event.target === event.currentTarget) finishDialog(null);
      }}
    >
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="dialog-title">
        <div className="dialog-head"><b id="dialog-title">{dialog?.title || "确认"}</b></div>
        <div className={dialog?.danger ? "dialog-text danger" : "dialog-text"} id="dialog-text">{dialog?.text ?? ""}</div>
        {dialog?.pre ? <pre className="dialog-pre" id="dialog-pre">{dialog.pre}</pre> : <pre className="dialog-pre" id="dialog-pre" hidden />}
        <label className="field" id="dialog-field" hidden={!withInput}>
          <span id="dialog-label">{dialog?.label || "名称"}</span>
          <input id="dialog-input" ref={inputRef} defaultValue={withInput ? dialog?.value : ""} />
        </label>
        <div className="row wrap dialog-acts">
          <button className="ghost" type="button" id="dialog-cancel" hidden={Boolean(dialog?.onlyClose)} onClick={() => finishDialog(null)}>取消</button>
          <button
            className={dialog?.danger ? "danger" : ""}
            type="button"
            id="dialog-ok"
            onClick={accept}
          >
            {dialog?.confirmText || "确定"}
          </button>
        </div>
      </div>
    </div>
  );
}
