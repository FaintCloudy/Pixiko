"use client";

/**
 * 终端：真 CLI 面板的公共容器（对话栏与控制台栏共用）。
 *
 * 结构照抄 `webui/index.html` 里的：
 *   `<div class="terminal">` → `.terminal-head` + `.terminal-body`
 * 其中 `.terminal-body` 里是「日志区 + 提示符 + 可增长的空白」三件套：
 *   - 日志区 `#terminal`：日志行排在提示符**之前**（app.js `appendTerminal` 就是这么插的），
 *     所以提示符始终紧跟最新一行日志；
 *   - 提示符 `#terminal-form`（`.prompt-line`）：输入不带 `.` / `/` 前缀；
 *   - `#terminal-tail`：提示符下面那片空白，**只有用户手势**（滚轮 / 触摸 / PageDown / End）
 *     滚到底时才长一段，进栏目或「清屏」时复位 —— 语义照搬 app.js 的
 *     `growTerminalTail` / `resetTerminalTail`。
 *
 * 日志来自 `/api/logs`（POST `{source, lines}`），**增量**追加：记住上次的最后一行，
 * 每 4 秒只补新行，键（来源 + 关键字）变了才整屏重来。
 *
 * 输入行的三条去向照 `app.js` 的 `terminalCommand`(1353) / `runTerminal`(1362) / `submitTerminal`(1443)：
 *   - `.` / `/` 开头，或首词命中 `CLI_COMMANDS` → `useCapture().runCommands([指令])`（和 QQ、
 *     控制台按钮完全同源）；要等图的 `gen/get/rg` 传 `follow`，跟到图片回来；
 *   - 其余文本 → `POST /api/chat {message, execute:true}`，`reply` 与「执行指令：…」打进终端，
 *     有 `captureId` 时 `followCapture(captureId, true)`（照 `runTerminal` 1377–1381）；
 *   - 回执文本/图片通过 `useCapture().subscribe` 增量打进终端：文本按行判成败（`err`/`out`），
 *     图片用 `<ImageNode>` 追加成一行（点图仍是查看器）—— 这就是 `app.js followTerminal`(1422) 的内容。
 *
 * ↑/↓ 翻历史（最近 100 条，`terminalHistoryMove`(1452) 语义）、`Ctrl+L` 清屏、点终端任意位置
 * 聚焦到提示符（`app.js` 1716 的 `#terminal-body` click）。
 */
import { useCallback, useEffect, useRef, useState } from "react";
import { apiRequest, errorText, Unauthorized } from "../client/api";
import { useCapture } from "../client/capture";
import { useConsole, usePoll } from "../client/store";
import { ImageNode } from "../client/viewer";
import type { LogsPayload } from "../client/types";

export type TerminalMode = "chat" | "logs";

/** 终端里最多留多少行（提示符与底部空白不算）：与 app.js 的 TERMINAL_LIMIT 同量级。 */
const LINE_LIMIT = 2000;
/** 每次拉多少行日志（Java 版日志栏用 400）。 */
const FETCH_LINES = 400;
/** 提示符下面那片空白一次长多少：至少 240px，或当前可视高度的 90%。 */
const TAIL_STEP_MIN = 240;
/** 距底部多少像素以内算「在底部」。 */
const BOTTOM_GAP = 4;
/** 历史最多记多少条（`app.js runTerminal` 1367 的 100）。 */
const HISTORY_LIMIT = 100;

/** 控制台里不用加点和斜杠：首词命中这些名字就当指令（`app.js CLI_COMMANDS`(1226) 逐字照抄）。 */
const CLI_COMMANDS = new Set([
  "help", "yh", "liv", "get", "settings", "chat", "admin", "char", "batch",
  "sampler", "style", "size", "steps", "cfg", "seed", "model", "prompt", "promptr", "preset", "function",
  "lora", "gen", "rg", "imgcnt", "usage", "map", "progen", "infix", "progress", "sd", "jrlp",
  "进度", "帮助", "老婆", "今日老婆", "强娶", "离婚",
]);

/** `app.js followTerminal`(1432)：回执文本逐行判成败用的正则（比回执卡片多一个「无效」）。 */
const TERMINAL_FAILURE = /操作失败|失败|错误|不正确|无效/;
/** 要等图的指令（`.gen` / `.get` / `.rg`）：跟到图片回来。 */
const FOLLOW_COMMAND = /\b(gen|get|rg)\b/i;

type LineKind = "log" | "cmd" | "out" | "err" | "sys";
/** `image` 有值就是 `app.js appendTerminal(kind, '', 图片路径)` 那种「图片行」。 */
type Line = { key: number; kind: LineKind; text: string; image?: string };

/** `POST /api/chat` 的返回（`lib/web/web-chat.ts`：`{reply, commands, interest[, captureId]}`）。 */
type ChatReply = { reply?: string; commands?: string[]; interest?: number; captureId?: string };

const PLACEHOLDER: Record<TerminalMode, string> = {
  chat: "和机器人说话，或直接写指令，例如：help / gen 2 / style list …（↑↓ 翻历史）",
  logs: "help / gen 2 / style list …（指令不用加点和斜杠，↑↓ 翻历史，Esc 退出全屏）",
};

/** `app.js terminalCommand`(1353)：`help` → `.help`；已带前缀原样；认不出的返回 null（交给聊天）。 */
function terminalCommand(text: string): string | null {
  const trimmed = text.trim();
  if (!trimmed) return null;
  if (/^[./]/.test(trimmed)) return trimmed;
  const first = trimmed.split(/\s+/)[0].toLowerCase();
  return CLI_COMMANDS.has(first) ? "." + trimmed : null;
}

export type TerminalProps = {
  mode: TerminalMode;
  /** 轮询 /api/logs 的开关（未登录时为 false）。 */
  active: boolean;
  /** 日志来源：all / qq / web。 */
  source?: string;
  /** 关键字过滤（纯前端）。 */
  filter?: string;
  /** 是否自动跟随（`#logs-follow` 未勾选时只手动刷新）。 */
  follow?: boolean;
  onSourceChange?: (source: string) => void;
  onFilterChange?: (filter: string) => void;
  onFollowChange?: (follow: boolean) => void;
  /** 全屏切换（只有控制台栏有）。 */
  fullscreen?: boolean;
  onToggleFullscreen?: () => void;
  onCleared?: () => void;
};

export function Terminal(props: TerminalProps) {
  const { mode, active, source = "all", filter = "", follow = true } = props;
  const { unlocked } = useConsole();
  const { runCommands, followCapture, subscribe } = useCapture();

  const [lines, setLines] = useState<Line[]>([]);
  const [tailHeight, setTailHeight] = useState<number | null>(null);
  const bodyRef = useRef<HTMLDivElement | null>(null);
  const atBottomRef = useRef(true);
  const anchorRef = useRef<string | null>(null);
  const keyRef = useRef("");
  const nextKey = useRef(1);
  const historyRef = useRef<string[]>([]);
  const cursorRef = useRef(0);
  const inputRef = useRef<HTMLInputElement | null>(null);

  /** 追加日志行（增量）。`restart=true` 时先清屏（键变了 / 清屏 / 换来源）。 */
  const appendLogs = useCallback(
    (incoming: string[], restart: boolean) => {
      setLines((previous) => {
        const base = restart ? [] : previous;
        const fresh = incoming.map((text) => ({ key: nextKey.current++, kind: "log" as LineKind, text }));
        const merged = base.concat(fresh);
        return merged.length > LINE_LIMIT ? merged.slice(merged.length - LINE_LIMIT) : merged;
      });
    },
    [],
  );

  /** 追加一行（`app.js appendTerminal`(1238)）。传了 `image` 就是「图片行」（文本一般为空）。 */
  const appendLine = useCallback((kind: LineKind, text: string, image?: string) => {
    setLines((previous) => {
      const merged = previous.concat([{ key: nextKey.current++, kind, text, image }]);
      return merged.length > LINE_LIMIT ? merged.slice(merged.length - LINE_LIMIT) : merged;
    });
  }, []);

  /** 拉一次日志：key（来源 + 关键字）不变就只补新行（与 app.js `loadLogs` 同一套语义）。 */
  const loadLogs = useCallback(
    async (force = false) => {
      const key = source + "\u0000" + filter;
      let incoming: string[] = [];
      try {
        const data = await apiRequest<LogsPayload>("/api/logs", { method: "POST", body: { lines: FETCH_LINES, source } });
        incoming = (data.lines ?? []).filter((line) => !filter || line.includes(filter));
      } catch (error) {
        appendLine("err", "读取日志失败：" + errorText(error));
        return;
      }
      const restart = force || key !== keyRef.current;
      if (restart) {
        keyRef.current = key;
        anchorRef.current = null;
        appendLogs(incoming, true);
      } else {
        const anchor = anchorRef.current;
        let start = 0;
        if (anchor !== null) {
          const at = incoming.lastIndexOf(anchor);
          if (at >= 0) start = at + 1;
          else {
            appendLogs(incoming, true);
            start = incoming.length;
          }
        }
        appendLogs(incoming.slice(start), false);
      }
      if (incoming.length > 0) anchorRef.current = incoming[incoming.length - 1];
      else if (restart) appendLine("sys", "（这一路还没有日志）");
    },
    [appendLine, appendLogs, filter, source],
  );

  /** 日志轮询：只在控制台栏、已登录、且勾了「跟随」时跑；否则靠「刷新」按钮手动拉。 */
  usePoll(() => loadLogs(false), 4000, active && unlocked && (mode === "chat" || follow));

  /** 换来源 / 改关键字：key 变了，自动整屏重来。 */
  useEffect(() => {
    if (!active || !unlocked) return;
    const key = source + "\u0000" + filter;
    if (key === keyRef.current) return;
    void loadLogs(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [source, filter, active, unlocked]);

  /**
   * 指令通道的回执 → 终端日志流（`app.js followTerminal`(1422) 的等价物）。
   *
   * 回执文本按行判成败，失败走 `err`（`followTerminal` 就是逐行 `.err`/`.out`）；
   * 图片补成一行（`followTerminal` 的 `appendTerminal('out','',file)`），点开仍是查看器。
   */
  useEffect(
    () =>
      subscribe((event) => {
        if (event.type === "text") {
          event.text.split("\n").forEach((line) => appendLine(TERMINAL_FAILURE.test(line) ? "err" : "out", line));
        } else if (event.type === "images") {
          for (const image of event.images) appendLine("out", "", image.file);
        }
      }),
    [appendLine, subscribe],
  );

  /** 新行落地后：本来在底部就跟到底（程序自己的滚动不会触发空白增长）。 */
  useEffect(() => {
    const body = bodyRef.current;
    if (!body || !atBottomRef.current) return;
    body.scrollTop = body.scrollHeight;
  }, [lines]);

  /** 提示符下面那片空白：只有用户手势滚到底时才加一段。高度直接读 DOM，避免依赖 state 反复重挂监听。 */
  const growTail = useCallback(() => {
    const body = bodyRef.current;
    if (!body) return;
    if (body.scrollTop + body.clientHeight < body.scrollHeight - BOTTOM_GAP) return;
    const current = body.querySelector<HTMLElement>("#terminal-tail")?.getBoundingClientRect().height ?? 0;
    const step = Math.max(TAIL_STEP_MIN, Math.round(body.clientHeight * 0.9));
    setTailHeight(Math.round(current + step));
  }, []);

  useEffect(() => {
    const body = bodyRef.current;
    if (!body) return;
    const onScroll = () => {
      atBottomRef.current = body.scrollTop + body.clientHeight >= body.scrollHeight - 40;
    };
    const onWheel = (event: WheelEvent) => {
      if (event.deltaY > 0) growTail();
    };
    const onTouch = () => growTail();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "PageDown" || event.key === "End") growTail();
    };
    body.addEventListener("scroll", onScroll, { passive: true });
    body.addEventListener("wheel", onWheel, { passive: true });
    body.addEventListener("touchmove", onTouch, { passive: true });
    body.addEventListener("keydown", onKey);
    return () => {
      body.removeEventListener("scroll", onScroll);
      body.removeEventListener("wheel", onWheel);
      body.removeEventListener("touchmove", onTouch);
      body.removeEventListener("keydown", onKey);
    };
  }, [growTail]);

  /** 清屏：只清显示，不动日志文件；提示符下面那片空白也收回初始高度。 */
  const clearTerminal = useCallback(() => {
    anchorRef.current = null;
    keyRef.current = "";
    setLines([]);
    setTailHeight(null);
    atBottomRef.current = true;
    appendLine("sys", "已清屏（日志文件不受影响，刷新会重新读出来）");
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [appendLine]);

  /**
   * 提示符回车（`app.js runTerminal`(1362) + `submitTerminal`(1443)）：
   * 先回显 `pixiko@web:~$ 输入`、记一条历史；然后指令走指令通道、其余当作和机器人说话，
   * 回执由上面的订阅打进终端；最后补一行空行（Java 也是 `appendTerminal('out','')`）。
   */
  const submit = useCallback(
    async (raw: string) => {
      const text = raw.trim();
      if (!text) return;
      appendLine("cmd", "pixiko@web:~$ " + text);
      const history = historyRef.current;
      history.push(text);
      if (history.length > HISTORY_LIMIT) history.shift();
      cursorRef.current = history.length;
      const command = terminalCommand(text);
      try {
        if (command) {
          // 指令通道（和 QQ、控制台按钮、面板按钮完全同源）。要等图的跟到图片回来。
          await runCommands([command], { follow: FOLLOW_COMMAND.test(command) });
        } else {
          // 当作和机器人说话：`execute:true`（`runTerminal` 1377）。
          const result = await apiRequest<ChatReply>("/api/chat", {
            method: "POST",
            body: { message: text, execute: true },
          });
          (result.reply || "(空回复)").split("\n").forEach((line) => appendLine("out", line));
          if (result.commands && result.commands.length) appendLine("sys", "执行指令：" + result.commands.join("  "));
          // 网页对话里要图的请求：一直跟到图片回来（`runTerminal` 1380 的 followTerminal）。
          if (result.captureId) await followCapture(result.captureId, true);
        }
      } catch (error) {
        if (!(error instanceof Unauthorized)) appendLine("err", errorText(error));
      }
      appendLine("out", "");
    },
    [appendLine, followCapture, runCommands],
  );

  /** `app.js terminalHistoryMove`(1452)：上下翻最近 100 条；越到下界就是空串（可以继续输入新内容）。 */
  const historyMove = (delta: number): string | null => {
    const list = historyRef.current;
    if (!list.length) return null;
    cursorRef.current = Math.max(0, Math.min(list.length, cursorRef.current + delta));
    return cursorRef.current >= list.length ? "" : list[cursorRef.current];
  };

  const onKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "ArrowUp") {
      event.preventDefault();
      const next = historyMove(-1);
      if (next !== null) setInputValue(next);
    } else if (event.key === "ArrowDown") {
      event.preventDefault();
      const next = historyMove(1);
      if (next !== null) setInputValue(next);
    } else if (event.key === "l" && event.ctrlKey) {
      // `app.js` 1727：Ctrl+L 清屏（只清显示，不动日志文件）。
      event.preventDefault();
      clearTerminal();
    }
  };
  const setInputValue = (value: string) => {
    const input = inputRef.current;
    if (input) input.value = value;
  };

  /** `app.js` 1716：点终端任意位置都聚焦到提示符（真终端就是这样），但别抢图片链接的点击。 */
  const focusInput = (event: React.MouseEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement | null;
    if (target && (target.tagName === "A" || target.tagName === "IMG")) return;
    inputRef.current?.focus();
  };

  const filterVisible = lines.filter((line) => !filter || line.kind !== "log" || line.text.includes(filter));

  return (
    <div className="terminal">
      <div className="terminal-head">
        <span className="terminal-dot" />
        <b>pixiko://console</b>
        {mode === "logs" ? (
          <>
            <label className="field compact">
              <span>日志</span>
              <select id="logs-source" value={source} onChange={(event) => props.onSourceChange?.(event.target.value)}>
                <option value="all">全部</option>
                <option value="qq">QQ 侧</option>
                <option value="web">网页侧</option>
              </select>
            </label>
            <input
              id="logs-filter"
              placeholder="过滤日志关键字"
              value={filter}
              onChange={(event) => props.onFilterChange?.(event.target.value)}
            />
            <label className="check">
              <input
                type="checkbox"
                id="logs-follow"
                checked={follow}
                onChange={(event) => props.onFollowChange?.(event.target.checked)}
              />
              跟随
            </label>
            <button className="ghost small" type="button" id="logs-reload" disabled={!unlocked} onClick={() => void loadLogs(true)}>
              刷新
            </button>
            <button className="ghost small" type="button" id="terminal-clear" disabled={!unlocked} onClick={clearTerminal}>
              清屏
            </button>
            <button
              className="ghost small"
              type="button"
              id="terminal-fullscreen"
              disabled={!props.onToggleFullscreen}
              onClick={() => props.onToggleFullscreen?.()}
            >
              {props.fullscreen ? "退出全屏" : "全屏"}
            </button>
          </>
        ) : (
          <span className="muted">与 QQ 侧、控制台同源；日志实时跟到这里，输入行也可以直接敲指令</span>
        )}
      </div>
      {/* 提示符跟着日志流走（就在最新一行后面），它后面再留一片空白：
          日志把提示符压到底部时，这片空白会把它顶上去，不会贴死屏幕底边。 */}
      <div className="terminal-body" id="terminal-body" ref={bodyRef} onClick={focusInput}>
        <div id="terminal">
          {filterVisible.map((line) => (
            <div className={"line " + line.kind} key={line.key}>
              {line.text}
              {line.image ? <ImageNode file={line.image} caption={line.image.replace(/^.*[\\/]/, "")} /> : null}
            </div>
          ))}
        </div>
        <form
          className="prompt-line"
          id="terminal-form"
          onSubmit={(event) => {
            event.preventDefault();
            const input = inputRef.current;
            if (!input) return;
            const text = input.value;
            input.value = "";
            void submit(text);
            input.focus();
          }}
        >
          <span className="terminal-caret">pixiko@web:~$</span>
          <input
            id="terminal-input"
            ref={inputRef}
            autoComplete="off"
            autoCapitalize="off"
            spellCheck={false}
            placeholder={PLACEHOLDER[mode]}
            onKeyDown={onKeyDown}
          />
        </form>
        <div
          className="terminal-tail"
          id="terminal-tail"
          aria-hidden="true"
          style={tailHeight === null ? undefined : { height: tailHeight + "px" }}
        />
      </div>
    </div>
  );
}
