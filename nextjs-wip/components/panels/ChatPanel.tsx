"use client";

/**
 * 对话栏（`/`）。
 *
 * 已接（对照 `webui/app.js`）：
 * - `appendMessage`(318)：`#chat-log` 里的消息（用户右侧、`bot`/`sys` 左侧，图片用 `<ImageNode className="msg-image">`），
 *   追加后滚到底；
 * - `sendChat`(332)：`#chat-form` 提交（回车发送、`Shift+Enter` 换行）→ `POST /api/chat {message, execute}`，
 *   回复填进「正在思考…」那条；`commands` 非空补一条 `执行指令：…`；`captureId` 走 `followCapture(id, true)`
 *   把图片直接带回对话；`#chat-interest` 显示 `相关度 N`；
 * - `loadChatHistory`(365)：`GET /api/chat/history`（scope 由服务端按令牌决定），`#chat-reset` → `POST /api/chat/reset`；
 * - 订阅 `useCapture().subscribe`：`images` 事件 → 追加一条 `图片好了，直接发在这里：` + 图片（增量，不重复）。
 *
 * 与 Java 的差异（刻意）：
 * - Java 是「在对话页面才往 `#chat-log` 追加」，Next 版每个栏目一个页面、本组件只在对话页挂载，语义等价；
 * - Java 每次生成新回执都会把「这条回执里已经有的图片」重发一遍（`state.seenImages` 不按回执清理），
 *   Next 版把去重交给 `CaptureProvider`（每个回执 id 只发新增的图片），所以同一张图不会重复追加。
 */
import { useCallback, useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { apiRequest, errorText, Unauthorized } from "../client/api";
import { Receipts, useCapture, type ReceiptImage } from "../client/capture";
import { useConsole } from "../client/store";
import type { ChatEntry } from "../client/types";
import { ImageNode } from "../client/viewer";

/** `POST /api/chat` 的返回（`lib/web/web-chat.ts`：`{reply, commands, interest[, captureId]}`）。 */
type ChatReply = { reply?: string; commands?: string[]; interest?: number; captureId?: string };

/** `#chat-log` 里的一条消息（`app.js appendMessage` 的三种 kind）。 */
type Message = { key: number; role: "user" | "bot" | "sys"; text: string; images?: ReceiptImage[]; pending?: boolean };

/** 消息自增键：React 按 key 复用 DOM，不用数组下标（`docs/PANEL-PORT.md` 第七条的同一套规矩）。 */
let nextMessageKey = 1;

export function ChatPanel() {
  const { unlocked, toast, showBanner } = useConsole();
  const { followCapture, subscribe } = useCapture();

  const [messages, setMessages] = useState<Message[]>([]);
  const [interest, setInterest] = useState("");
  /** 正在等 `/api/chat` 回复（等价 Java 的 `#chat-send.disabled = true`）。 */
  const [sending, setSending] = useState(false);

  const logRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);

  /** `app.js appendMessage`(318) 的 `log.scrollTop = log.scrollHeight`。 */
  const scrollToBottom = useCallback(() => {
    const log = logRef.current;
    if (log) log.scrollTop = log.scrollHeight;
  }, []);

  const append = useCallback(
    (role: Message["role"], text: string, images?: ReceiptImage[]) => {
      const key = nextMessageKey++;
      setMessages((current) => [...current, { key, role, text, images }]);
      scrollToBottom();
      return key;
    },
    [scrollToBottom],
  );

  // 消息渲染落地后再滚一次（追加时 DOM 还没更新，`scrollHeight` 还是旧的）。
  useEffect(() => {
    scrollToBottom();
  }, [messages, scrollToBottom]);

  /** `app.js loadChatHistory`(365)：进对话页拉一次历史（scope 由服务端按令牌决定，前端不传）。 */
  const loadHistory = useCallback(async () => {
    try {
      const entries = await apiRequest<ChatEntry[]>("/api/chat/history");
      setMessages(
        (Array.isArray(entries) ? entries : []).map((entry) => ({
          key: nextMessageKey++,
          role: (entry.role === "user" ? "user" : "bot") as Message["role"],
          text: entry.content || "",
        })),
      );
    } catch (error) {
      // Java 是静默忽略（历史读不到不影响使用）；这里沿用骨架里的横幅提示，便于排查。
      showBanner("对话历史读取失败：" + errorText(error));
    }
  }, [showBanner]);

  useEffect(() => {
    if (!unlocked) return;
    void loadHistory();
  }, [unlocked, loadHistory]);

  /** `app.js sendChat`(332)：回车/「发送」→ `/api/chat`；回复填进「正在思考…」那条。 */
  const sendChat = useCallback(
    async (event?: FormEvent<HTMLFormElement>) => {
      if (event) event.preventDefault();
      if (!unlocked || sending) return;
      const input = inputRef.current;
      const message = (input?.value ?? "").trim();
      if (!message) return;
      if (input) {
        input.value = "";
        input.style.height = "auto"; // Java 版收回单行高度（rows=1 的 textarea）
      }
      append("user", message);
      // 「正在思考…」先占位（带 `.spin`），回来后原地换成回复。
      const pendingKey = append("bot", "");
      setMessages((current) => current.map((item) => (item.key === pendingKey ? { ...item, pending: true } : item)));
      setSending(true);
      const execute = document.querySelector<HTMLInputElement>("#chat-execute")?.checked ?? true;
      try {
        const result = await apiRequest<ChatReply>("/api/chat", { method: "POST", body: { message, execute } });
        setMessages((current) =>
          current.map((item) => (item.key === pendingKey ? { ...item, text: result.reply || "(空回复)", pending: false } : item)),
        );
        setInterest(result.interest != null ? "相关度 " + result.interest : "");
        const commands = result.commands ?? [];
        if (commands.length > 0) {
          append("sys", "执行指令：" + commands.join("  "));
          if (result.captureId) {
            // 网页对话里要图的请求：一直跟到图片回来，图片由下面的订阅直接发在对话里。
            await followCapture(result.captureId, true);
          }
        }
      } catch (error) {
        if (!(error instanceof Unauthorized)) {
          setMessages((current) =>
            current.map((item) => (item.key === pendingKey ? { ...item, text: "出错了：" + errorText(error), pending: false } : item)),
          );
        } else {
          setMessages((current) => current.map((item) => (item.key === pendingKey ? { ...item, pending: false } : item)));
        }
      } finally {
        setSending(false);
      }
    },
    [append, followCapture, sending, unlocked],
  );

  /** 回车发送、`Shift+Enter` 换行（`app.js` 里 textarea 上的 keydown 处理）。 */
  const onInputKeyDown = useCallback(
    (event: KeyboardEvent<HTMLTextAreaElement>) => {
      if (event.key !== "Enter" || event.shiftKey) return;
      event.preventDefault();
      void sendChat();
    },
    [sendChat],
  );

  /** `app.js` 的 `pollCapture`：图片按增量回到对话里（见文件头「与 Java 的差异」）。 */
  useEffect(
    () =>
      subscribe((event) => {
        if (event.type !== "images" || event.images.length === 0) return;
        append("bot", "图片好了，直接发在这里：", event.images);
      }),
    [append, subscribe],
  );

  /** `app.js` 的 `#chat-reset`：`POST /api/chat/reset` 后清空视图。 */
  const reset = useCallback(async () => {
    try {
      await apiRequest("/api/chat/reset", { method: "POST", body: {} });
      setMessages([]);
      setInterest("");
      toast("对话已清空");
    } catch (error) {
      showBanner("清空对话失败：" + errorText(error));
    }
  }, [showBanner, toast]);

  return (
    // Java 的对话面板就是 `<section class="panel active" id="panel-chat">`：消息区 + 输入框 + 回执，
    // **没有终端**（终端只属于「控制台」栏目）。早期骨架在这里挂过一个 `Terminal mode="chat"`，
    // 结果这一页看起来像控制台、还把消息区挤到屏幕下半截 —— 已按 Java 的结构去掉。
    <section className="panel active" id="panel-chat">
      <div className="messages" id="chat-log" ref={logRef}>
        {messages.map((item) => (
          <div className={"msg " + item.role} key={item.key}>
            {item.pending ? (
              <>
                <span className="spin" />
                {" 正在思考…"}
              </>
            ) : (
              item.text
            )}
            {(item.images ?? []).map((image) => (
              <ImageNode key={image.file} file={image.file} className="msg-image" />
            ))}
          </div>
        ))}
      </div>
      <form
        className="composer"
        id="chat-form"
        onSubmit={(event) => {
          void sendChat(event);
        }}
      >
        <textarea
          id="chat-input"
          rows={1}
          ref={inputRef}
          placeholder="和机器人说话，或直接写要求（例如：把地点换成草地，服饰换成军装，然后生成）"
          onKeyDown={onInputKeyDown}
        />
        <button type="submit" id="chat-send" disabled={!unlocked || sending}>发送</button>
      </form>
      <div className="row wrap">
        <label className="check"><input type="checkbox" id="chat-execute" defaultChecked /> 允许执行指令</label>
        <button className="ghost" id="chat-reset" type="button" disabled={!unlocked} onClick={() => void reset()}>清空对话</button>
        <span className="muted" id="chat-interest">{interest}</span>
      </div>
      <Receipts id="chat-receipts" />
    </section>
  );
}
