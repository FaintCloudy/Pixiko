"use client";

/**
 * 控制台的前端会话状态：令牌 / 是否已解锁 / 顶栏状态 / 横幅 / 轻提示。
 *
 * 对应 Java 版 `webui/app.js` 的 `lock()` / `unlock()` / `loadStatus()` / `banner()` / `toast()`：
 * - 没填令牌 → 页面可见但未登录（显示登录框，顶栏状态点是灰的），**不发任何 /api 请求**；
 * - 填了令牌 → 用 `/api/status` 验证，通过就存 localStorage 并隐藏登录框；
 * - 401/503 → 清掉令牌、回到登录框、给一句提示；429 → 冷却提示；
 * - 顶栏状态点与服务端状态 8 秒轮询一次，页面隐藏时停。
 *
 * 令牌始终只在 localStorage 与请求头里，不进代码、不进日志。
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { apiRequest, errorText, RateLimited, saveToken, storedToken, Unauthorized } from "./api";
import type { StatusJson } from "./types";

export type Health = "unknown" | "ok" | "bad";

/** 确认框的内容（对应 Java 版 `webui/app.js` 的 `askDialog` / `askConfirm`）。 */
export type DialogRequest = {
  title?: string;
  text: string;
  confirmText?: string;
  danger?: boolean;
  /** 有值时显示输入框，确认返回去掉首尾空格的文本；否则确认返回 true。 */
  value?: string;
  label?: string;
  /** 等宽正文（对应 `app.js showInfo` 的 `#dialog-pre`）：样式/提示词集/预设的「查看原文」用它。 */
  pre?: string;
  /** 用 `pre` 时是否连"取消"也藏起来（只看一眼的信息框）。 */
  onlyClose?: boolean;
};

type ConsoleContext = {
  /** 有令牌且至少验证过一次。 */
  unlocked: boolean;
  /** 首次读 localStorage 完成（避免闪一下"未登录"）。 */
  ready: boolean;
  status: StatusJson | null;
  health: Health;
  banner: string;
  /** 登录框下面那句提示（令牌错 / 冷却中）。 */
  authNotice: string;
  busy: boolean;
  /** 执行中标记：指令通道（`CaptureProvider`）会置位，面板据此禁用按钮。 */
  setBusy: (busy: boolean) => void;
  toast: (message: string) => void;
  showBanner: (message: string) => void;
  unlock: (token: string) => Promise<boolean>;
  lock: (notice?: string) => void;
  refresh: () => Promise<void>;
  /** 设置项就地更新（`/api/settings` 的返回就是新状态，不用整页刷新）。 */
  updateStatus: (next: StatusJson) => void;
  /** 弹窗当前内容（null = 收起）。 */
  dialog: DialogRequest | null;
  /** 同风格确认框：确定返回 true（输入框模式返回文本），取消返回 null。不用原生 confirm。 */
  askDialog: (request: DialogRequest) => Promise<string | true | null>;
  /** 信息框（`app.js showInfo`）：等宽正文 + 一个「关闭」，不改任何数据。 */
  showInfo: (title: string, content: string, options?: { text?: string }) => Promise<void>;
  /** 由 ShellDialog 调用：结束当前弹窗。 */
  finishDialog: (result: string | true | null) => void;
};

const Context = createContext<ConsoleContext | null>(null);

export function ConsoleProvider({ children }: { children: React.ReactNode }) {
  const [ready, setReady] = useState(false);
  const [unlocked, setUnlocked] = useState(false);
  const [status, setStatus] = useState<StatusJson | null>(null);
  const [health, setHealth] = useState<Health>("unknown");
  const [banner, setBanner] = useState("");
  const [authNotice, setAuthNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [toastText, setToastText] = useState("");
  const [dialog, setDialog] = useState<DialogRequest | null>(null);
  const dialogResolve = useRef<((result: string | true | null) => void) | null>(null);
  const unlockedRef = useRef(false);
  const toastTimer = useRef<number | null>(null);

  /** 同风格确认框：把 `#overlay` 打开，等用户点确定/取消（对应 app.js `askDialog`）。 */
  const askDialog = useCallback(
    (request: DialogRequest) =>
      new Promise<string | true | null>((resolve) => {
        dialogResolve.current = resolve;
        setDialog(request);
      }),
    [],
  );

  const finishDialog = useCallback((result: string | true | null) => {
    const resolve = dialogResolve.current;
    dialogResolve.current = null;
    setDialog(null);
    if (resolve) resolve(result);
  }, []);

  /** 信息框：等宽正文 + 一个「关闭」（`app.js` 的 `showInfo`，不看返回值）。 */
  const showInfo = useCallback(
    async (title: string, content: string, options: { text?: string } = {}) => {
      await askDialog({ title, text: options.text ?? "", pre: content, confirmText: "关闭", onlyClose: true });
    },
    [askDialog],
  );

  const toast = useCallback((message: string) => {
    setToastText(message);
    if (toastTimer.current !== null) window.clearTimeout(toastTimer.current);
    // 与 Java 版一致：2.6 秒后自己收起
    toastTimer.current = window.setTimeout(() => {
      setToastText("");
      toastTimer.current = null;
    }, 2600);
  }, []);

  const applyStatus = useCallback((next: StatusJson) => {
    setStatus(next);
    setHealth("ok");
    setBanner("");
  }, []);

  const lockWith = useCallback((notice: string) => {
    saveToken("");
    unlockedRef.current = false;
    setUnlocked(false);
    setStatus(null);
    setHealth("bad");
    setBanner("");
    setAuthNotice(notice);
  }, []);

  const lock = useCallback(
    (notice = "") => {
      lockWith(notice);
    },
    [lockWith],
  );

  /** 拉一次 /api/status：顶栏、chatcfg 摘要、系统页都吃这一份。 */
  const refresh = useCallback(async () => {
    try {
      applyStatus(await apiRequest<StatusJson>("/api/status"));
    } catch (error) {
      if (error instanceof Unauthorized) {
        lockWith(error.message);
        return;
      }
      setHealth("bad");
      setBanner("状态读取失败：" + errorText(error) + "（可点顶栏「刷新」重试）");
    }
  }, [applyStatus, lockWith]);

  const unlock = useCallback(
    async (token: string): Promise<boolean> => {
      const trimmed = token.trim();
      if (!trimmed) {
        setAuthNotice("请先填写访问令牌。");
        return false;
      }
      saveToken(trimmed);
      setBusy(true);
      try {
        const next = await apiRequest<StatusJson>("/api/status");
        unlockedRef.current = true;
        setUnlocked(true);
        setAuthNotice("");
        applyStatus(next);
        return true;
      } catch (error) {
        saveToken("");
        setHealth("bad");
        setAuthNotice(
          error instanceof RateLimited || error instanceof Unauthorized ? error.message : "验证失败：" + errorText(error),
        );
        return false;
      } finally {
        setBusy(false);
      }
    },
    [applyStatus],
  );

  /** 挂载后才读 localStorage：服务端渲染时不知道令牌，避免水合不一致。 */
  useEffect(() => {
    const token = storedToken();
    if (!token) {
      setReady(true);
      setAuthNotice("");
      return;
    }
    let cancelled = false;
    void (async () => {
      try {
        const next = await apiRequest<StatusJson>("/api/status");
        if (cancelled) return;
        unlockedRef.current = true;
        setUnlocked(true);
        applyStatus(next);
      } catch (error) {
        if (cancelled) return;
        saveToken("");
        setHealth("bad");
        setAuthNotice(error instanceof Unauthorized ? "令牌已失效，请重新输入。" : "连不上机器人：" + errorText(error));
      } finally {
        if (!cancelled) setReady(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [applyStatus]);

  /** 顶栏状态轮询：8 秒一次，页面隐藏时停。 */
  useEffect(() => {
    if (!unlocked) return;
    let timer: number | null = null;
    const stop = () => {
      if (timer !== null) window.clearInterval(timer);
      timer = null;
    };
    const start = () => {
      stop();
      timer = window.setInterval(() => {
        if (unlockedRef.current) void refresh();
      }, 8000);
    };
    start();
    const onVisibility = () => {
      if (document.visibilityState === "hidden") stop();
      else {
        void refresh();
        start();
      }
    };
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      stop();
    };
  }, [unlocked, refresh]);

  const value = useMemo<ConsoleContext>(
    () => ({
      unlocked,
      ready,
      status,
      health,
      banner,
      authNotice,
      busy,
      setBusy,
      toast,
      showBanner: setBanner,
      unlock,
      lock,
      refresh,
      updateStatus: applyStatus,
      dialog,
      askDialog,
      showInfo,
      finishDialog,
    }),
    [unlocked, ready, status, health, banner, authNotice, busy, toast, unlock, lock, refresh, applyStatus, dialog, askDialog, showInfo, finishDialog],
  );

  return (
    <Context.Provider value={value}>
      {children}
      {/* 轻提示容器：Shell 里那个 #toast，由这里统一控制显隐 */}
      <div id="toast" className={toastText ? "toast show" : "toast"} hidden={!toastText}>
        {toastText}
      </div>
    </Context.Provider>
  );
}

export function useConsole(): ConsoleContext {
  const value = useContext(Context);
  if (!value) throw new Error("useConsole 必须在 ConsoleProvider 内使用。");
  return value;
}

/**
 * 轮询钩子：间隔执行一次，页面隐藏时停、回来立刻补一次。
 * `enabled=false` 时什么都不做（未登录 / 接口还没迁移的面板）。
 */
export function usePoll(callback: () => void | Promise<void>, intervalMs: number, enabled: boolean): void {
  const saved = useRef(callback);
  saved.current = callback;
  useEffect(() => {
    if (!enabled) return;
    let running = false;
    const tick = async () => {
      if (running || document.visibilityState === "hidden") return;
      running = true;
      try {
        await saved.current();
      } finally {
        running = false;
      }
    };
    void tick();
    const timer = window.setInterval(() => void tick(), intervalMs);
    const onVisibility = () => {
      if (document.visibilityState === "visible") void tick();
    };
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [intervalMs, enabled]);
}
