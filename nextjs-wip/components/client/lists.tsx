"use client";

/**
 * 面板通用小件（对应 Java 版 `webui/app.js` 里的
 * `chips` / `fillMeanings` / `inlineRename`）。
 *
 * 为什么样式要一模一样：`.terms > span`、`.name > .editable`、`.rename-input` 这些
 * 都是控制台样式表（`app/console.css`，从 Java 版 `webui/app.css` 原样搬来）里写好的，
 * 也是控制台自检和 DOM 探针认的锚点。
 */
import { useCallback, useEffect, useRef, useState } from "react";
import { apiRequest } from "./api";

export type Term = { term: string; meaning?: string; number?: number };

/**
 * 词条 chip：`#编号 英文 中文`（中文只是颜色淡一点，不另开元素），点一下按原文移除。
 *
 * 释义先看服务端词库；词库里没有的，渲染完再问一次 DeepSeek（`learn=true`，后端会写进
 * `data/prompt-meanings.json`，同一个词条之后不再问），回来后就地补上，**不整块重画**。
 */
export function Terms({ id, list, onRemove }: { id: string; list: Term[]; onRemove?: (number: number, term: string) => void }) {
  const [meanings, setMeanings] = useState<Record<string, string>>({});
  const asked = useRef(new Set<string>());

  const missing = list
    .map((entry) => entry.term)
    .filter((term, index, all) => Boolean(term) && !list[index]?.meaning && !meanings[term] && all.indexOf(term) === index);

  const ask = useCallback(async (terms: string[]) => {
    for (const term of terms) asked.current.add(term);
    try {
      const data = await apiRequest<{ meanings?: Record<string, string> }>("/api/meanings", {
        method: "POST",
        body: { terms, learn: true },
      });
      const found = data?.meanings ?? {};
      if (Object.keys(found).length > 0) setMeanings((current) => ({ ...current, ...found }));
    } catch {
      // 释义只是锦上添花：拿不到就把省略号去掉，别打扰用户。
      setMeanings((current) => {
        const next = { ...current };
        for (const term of terms) if (next[term] === undefined) next[term] = "";
        return next;
      });
    }
  }, []);

  useEffect(() => {
    const pending = missing.filter((term) => !asked.current.has(term));
    if (pending.length === 0) return;
    void ask(pending);
    // `missing` 每次渲染都是新数组，这里只关心"还有没有没问过的词条"。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [missing.join("\u0000")]);

  return (
    <div className="terms" id={id}>
      {list.map((entry, index) => {
        const number = entry.number ?? index + 1;
        const meaning = entry.meaning || meanings[entry.term] || "";
        const settled = Boolean(entry.meaning) || meanings[entry.term] !== undefined;
        return (
          <span
            key={number + ":" + entry.term}
            data-term={entry.term}
            title={meaning ? entry.term + "（" + meaning + "，点击移除）" : entry.term + "（点击移除）"}
            style={onRemove ? { cursor: "pointer" } : undefined}
            onClick={onRemove ? () => onRemove(number, entry.term) : undefined}
          >
            <b>#{number}</b> <span className="term">{entry.term}</span>
            <span className={settled ? "meaning" : "meaning pending"}>{settled ? (meaning ? " " + meaning : "") : " …"}</span>
          </span>
        );
      })}
    </div>
  );
}

/**
 * 就地改名：把文字变成输入框，回车提交、Esc 取消、失焦提交（`app.js inlineRename`）。
 *
 * 名称前后的空格要原样保留（样式名前面留了一个），所以 `prefix` 单独传进来。
 */
export function InlineRename({
  value,
  prefix = "",
  className = "editable",
  title = "点一下直接改名",
  onCommit,
  onCancel,
}: {
  value: string;
  prefix?: string;
  className?: string;
  title?: string;
  onCommit: (next: string) => void;
  onCancel?: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(value);
  const done = useRef(false);

  const finish = (save: boolean) => {
    if (done.current) return;
    done.current = true;
    const next = draft.trim();
    setEditing(false);
    if (save && next && next !== value) onCommit(next);
    else onCancel?.();
  };

  if (!editing) {
    return (
      <span
        className={className}
        title={title}
        onClick={() => {
          done.current = false;
          setDraft(value);
          setEditing(true);
        }}
      >
        {prefix + value}
      </span>
    );
  }
  return (
    <span className={className}>
      {prefix}
      <input
        className="rename-input"
        value={draft}
        autoFocus
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Enter") {
            event.preventDefault();
            finish(true);
          } else if (event.key === "Escape") {
            event.preventDefault();
            finish(false);
          }
        }}
        onBlur={() => finish(true)}
        onClick={(event) => event.stopPropagation()}
      />
    </span>
  );
}
