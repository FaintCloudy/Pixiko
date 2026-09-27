"use client";

/**
 * 网页指令通道与回执（对应 Java 版 `webui/app.js` 的
 * `runCommands` / `pollCapture` / `renderCapture` / `RECEIPT_BOXES`）。
 *
 * 语义逐条对齐：
 * - `runCommands` 把多条指令用 `\n` 合成一次 `POST /api/command`，拿到回执 id 后按
 *   `pollCapture` 的节奏轮询（忙时 900ms、等待外部时 2500ms），最多 2400 次；
 * - 「忙」的判定和 Java 完全一样：`capture.busy || (!closed && ageMillis < 1200 && attempt < 3)`
 *   —— 回复与图片是异步回执，比指令执行体晚一两百毫秒到，只看 `busy` 会把图吞掉；
 * - `follow`（出图/领取这类要等的指令）会跟踪到图片回来：20 分钟窗口内即使一串回执都不忙也继续跟，
 *   并顺带把 SD 生成进度轮询起来；
 * - 回执**增量追加**，不整块重画：同一 `id` 只补新到的文本/图片，换一条指令才清空；
 * - 文本卡片按 Java 的正则判定成败（`操作失败|失败|未完成|错误|不正确` → `.receipt.err`）；
 * - 回执里的图片按增量补成一条聊天消息（`图片好了，直接发在这里：`），不用去图片页自己找。
 *
 * 与 Java 的唯一结构性差别：Java 靠"当前哪个 `.panel` 是 active"决定回执写进哪个盒子，
 * Next 版每个栏目一个页面，所以由 `PanelPage` 把本栏目的盒子 id 传给 Provider，语义等价。
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { apiRequest, errorText, Unauthorized } from "./api";
import { useConsole } from "./store";
import { ImageNode } from "./viewer";

export type ReceiptImage = { file: string };

export type Receipt =
  | { key: string; kind: "text"; command: string; text: string; error: boolean }
  | { key: string; kind: "image"; command: string; file: string };

type Capture = {
  id: string;
  command?: string;
  texts?: string[];
  images?: ReceiptImage[];
  busy?: boolean;
  closed?: boolean;
  done?: boolean;
  ageMillis?: number;
};

/** 回执事件：终端、对话页、出图页各自订阅自己关心的那部分。 */
export type CaptureEvent =
  | { type: "text"; command: string; text: string; error: boolean }
  | { type: "images"; command: string; images: ReceiptImage[] }
  | { type: "busy"; busy: boolean };

/** 栏目 id → 回执盒子 id（与 Java `RECEIPT_BOXES` 一致；对话/出图/提示词… 各一个）。 */
export const RECEIPT_BOXES: Record<string, string> = {
  chat: "chat-receipts",
  gen: "gen-receipts",
  prompt: "prompt-receipts",
  styles: "style-receipts",
  loras: "lora-receipts",
  functions: "function-receipts",
  chatcfg: "chatcfg-receipts",
  system: "system-receipts",
};

type CaptureContext = {
  /** 本栏目的回执盒子 id（`logs` / `help` 没有盒子时为 null，回执只走事件）。 */
  box: string | null;
  receipts: Receipt[];
  /** 有指令正在执行（回执里显示「执行中…」）。 */
  pending: boolean;
  /** 跑一条或多条指令；`after` 在回执结束（或跟丢）之后执行，`follow` 见文件头。 */
  runCommands: (commands: string | string[], options?: { after?: () => void | Promise<void>; follow?: boolean }) => Promise<Capture | null>;
  /** 只跟随一个已有回执（例如 `/api/chat` 返回的 `captureId`）。 */
  followCapture: (id: string, follow?: boolean) => Promise<Capture | null>;
  /** 清空本栏目的回执区（Java 的 `#…-receipts` 在换指令时自动清空）。 */
  clearReceipts: () => void;
  /** 订阅回执事件（终端/对话页用）。返回取消订阅函数。 */
  subscribe: (listener: (event: CaptureEvent) => void) => () => void;
  /** 登记一个"刷新器"：`status` / `tasks` / `images` / `progress-start`，指令跑完按需触发。 */
  register: (name: RefreshName, refresh: () => void | Promise<void>) => () => void;
  /** 手动触发某个刷新器。 */
  refresh: (name: RefreshName) => Promise<void>;
};

export type RefreshName = "status" | "tasks" | "images" | "progress-start";

const Context = createContext<CaptureContext | null>(null);

const FAILURE = /操作失败|失败|未完成|错误|不正确/;

export function CaptureProvider({
  box,
  children,
}: {
  box: string | null;
  children: ReactNode;
}) {
  const { status, setBusy, toast } = useConsole();
  const [receipts, setReceipts] = useState<Receipt[]>([]);
  const [pending, setPending] = useState(false);

  const timer = useRef<number | null>(null);
  const followUntil = useRef(0);
  const keyRef = useRef("");
  const counts = useRef({ texts: 0, images: 0 });
  const seenImages = useRef(new Map<string, number>());
  const toasted = useRef("");
  const listeners = useRef(new Set<(event: CaptureEvent) => void>());
  const refreshers = useRef(new Map<RefreshName, Set<() => void | Promise<void>>>());
  const statusRef = useRef(status);
  statusRef.current = status;

  useEffect(
    () => () => {
      if (timer.current !== null) window.clearTimeout(timer.current);
    },
    [],
  );

  const emit = useCallback((event: CaptureEvent) => {
    for (const listener of listeners.current) {
      try {
        listener(event);
      } catch {
        /* 某个订阅者出错不影响回执本身 */
      }
    }
  }, []);

  const subscribe = useCallback((listener: (event: CaptureEvent) => void) => {
    listeners.current.add(listener);
    return () => listeners.current.delete(listener);
  }, []);

  const refresh = useCallback(async (name: RefreshName) => {
    const set = refreshers.current.get(name);
    if (set === undefined) return;
    for (const fn of set) {
      try {
        await fn();
      } catch {
        /* 刷新失败不打断回执 */
      }
    }
  }, []);

  const register = useCallback((name: RefreshName, fn: () => void | Promise<void>) => {
    let set = refreshers.current.get(name);
    if (set === undefined) {
      set = new Set();
      refreshers.current.set(name, set);
    }
    set.add(fn);
    return () => {
      set.delete(fn);
    };
  }, []);

  /** `app.js renderCapture`：增量追加，换指令才清空。 */
  const render = useCallback(
    (capture: Capture, command: string) => {
      const key = String(capture.id ?? capture.command ?? "");
      if (keyRef.current !== key) {
        keyRef.current = key;
        counts.current = { texts: 0, images: 0 };
        setReceipts([]);
        toasted.current = "";
      }
      const texts = capture.texts ?? [];
      const images = capture.images ?? [];
      const added: Receipt[] = [];
      for (let index = counts.current.texts; index < texts.length; index += 1) {
        const text = texts[index];
        const receipt: Receipt = { key: key + ":t" + index, kind: "text", command, text, error: FAILURE.test(text) };
        added.push(receipt);
        emit({ type: "text", command, text, error: receipt.error });
      }
      for (let index = counts.current.images; index < images.length; index += 1) {
        added.push({ key: key + ":i" + index, kind: "image", command, file: images[index].file });
      }
      counts.current = { texts: Math.max(counts.current.texts, texts.length), images: Math.max(counts.current.images, images.length) };
      if (added.length > 0) setReceipts((current) => [...current, ...added]);
      setPending(Boolean(capture.busy));
      emit({ type: "busy", busy: Boolean(capture.busy) });
      if (texts.length > 0 && toasted.current !== key) {
        toasted.current = key;
        toast(texts[texts.length - 1].split("\n")[0]);
      }
    },
    [emit, toast],
  );

  const pollCapture = useCallback(
    async (id: string, attempt = 0, follow = false, command = ""): Promise<Capture | null> => {
      if (timer.current !== null) window.clearTimeout(timer.current);
      let capture: Capture;
      try {
        capture = await apiRequest<Capture>("/api/capture", { method: "POST", body: { id } });
      } catch (error) {
        if (!(error instanceof Unauthorized)) toast("读取回执失败：" + errorText(error));
        setPending(false);
        return null;
      }
      render(capture, command || capture.command || "");
      // 生成好的图片必须直接回到对话里：按增量补成一条聊天消息。
      const images = capture.images ?? [];
      const seen = seenImages.current.get(id) ?? 0;
      if (images.length > seen) {
        seenImages.current.set(id, images.length);
        emit({ type: "images", command: command || capture.command || "", images: images.slice(seen) });
        void refresh("images");
      }
      const busy = Boolean(capture.busy) || (capture.closed !== true && (capture.ageMillis ?? 0) < 1200 && attempt < 3);
      if (attempt % 4 === 0) void refresh("status");
      const generating = Boolean(statusRef.current?.generation?.status);
      const followOn = follow && (busy || generating || Date.now() < followUntil.current);
      if ((busy || followOn) && attempt < 2400) {
        timer.current = window.setTimeout(() => {
          void pollCapture(id, attempt + 1, follow, command);
        }, busy ? 900 : 2500);
      }
      return capture;
    },
    [emit, refresh, render, toast],
  );

  const runCommands = useCallback(
    async (commands: string | string[], options: { after?: () => void | Promise<void>; follow?: boolean } = {}) => {
      const list = Array.isArray(commands) ? commands : [commands];
      const command = list.join(" ; ");
      try {
        setBusy(true);
        // 先按 Java 的做法把回执区切成"这一条指令 + 执行中"（key 变化 → 清空旧回执）。
        keyRef.current = "";
        counts.current = { texts: 0, images: 0 };
        setReceipts([]);
        toasted.current = "";
        setPending(true);
        const started = await apiRequest<Capture>("/api/command", { method: "POST", body: { command: list.join("\n") } });
        if (options.follow) {
          followUntil.current = Date.now() + 20 * 60 * 1000;
          void refresh("progress-start");
        }
        if (/\b(gen|get|rg)\b/i.test(list.join(" "))) void refresh("tasks");
        await pollCapture(started.id, 0, Boolean(options.follow), command);
        if (options.after) await options.after();
        if (/\b(gen|get|rg)\b/i.test(list.join(" "))) void refresh("tasks");
        return started;
      } catch (error) {
        // 指令被拒（空指令 / 超过 20 条 / 机器人没起来）时 `apiRequest` 抛的就是那一句服务端文案：
        // 除了轻提示，还要**回执一条 + 抛一次事件**，否则终端里敲的指令失败后什么都看不到
        // （Java 的 `runTerminal` 会把错误打成一行 err）。
        const message = errorText(error);
        if (!(error instanceof Unauthorized)) {
          toast(message);
          const receipt: Receipt = { key: "error:" + Date.now(), kind: "text", command, text: message, error: true };
          setReceipts((current) => [...current, receipt]);
          emit({ type: "text", command, text: message, error: true });
        }
        setPending(false);
        return null;
      } finally {
        setBusy(false);
      }
    },
    [pollCapture, refresh, setBusy, toast],
  );

  const followCapture = useCallback(
    async (id: string, follow = false) => {
      if (follow) followUntil.current = Date.now() + 20 * 60 * 1000;
      return await pollCapture(id, 0, follow);
    },
    [pollCapture],
  );

  const clearReceipts = useCallback(() => {
    setReceipts([]);
    keyRef.current = "";
    counts.current = { texts: 0, images: 0 };
  }, []);

  const value = useMemo<CaptureContext>(
    () => ({ box, receipts, pending, runCommands, followCapture, clearReceipts, subscribe, register, refresh }),
    [box, receipts, pending, runCommands, followCapture, clearReceipts, subscribe, register, refresh],
  );
  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function useCapture(): CaptureContext {
  const value = useContext(Context);
  if (value === null) throw new Error("useCapture 必须在 CaptureProvider 里使用。");
  return value;
}

/**
 * 回执区（Java 的 `<div class="receipts" id="…-receipts">`）：文本卡片 + 图片卡片 + 执行中。
 *
 * `id` 必须用 Java 的盒子 id，样式与控制台自检都认它。
 */
export function Receipts({ id }: { id: string }) {
  const { receipts, pending } = useCapture();
  return (
    <div className="receipts" id={id}>
      {receipts.map((receipt) =>
        receipt.kind === "text" ? (
          <div className={"receipt " + (receipt.error ? "err" : "ok")} key={receipt.key}>
            <div className="head">{receipt.command ? "指令 · " + receipt.command : "执行回执"}</div>
            <div>{receipt.text}</div>
          </div>
        ) : (
          <div className="receipt ok image-receipt" key={receipt.key}>
            <ImageNode file={receipt.file} className="receipt-image" />
          </div>
        ),
      )}
      {pending ? (
        <div className="receipt pending">
          <span className="spin" /> 执行中…
        </div>
      ) : null}
    </div>
  );
}
