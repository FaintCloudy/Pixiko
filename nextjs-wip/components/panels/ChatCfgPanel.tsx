"use client";

/**
 * 聊天配置栏（`/chatcfg`）：开关、频道（聊天/生图各自独立）、性格设定、通知。
 *
 * 已接（对应 app.js `loadStatus` 里的同名片段）：
 * - `#set-chat-global` / `#set-chat-thinking` / `#set-image-thinking` / `#set-frequency` /
 *   `#apply-frequency` / `#apply-chat-model` / `#apply-image-model` / `#apply-personality`
 *   → `POST /api/settings`，接口返回的就是新状态，**就地更新**（不整页刷新）；
 * - `#chatcfg-summary` / `#channel-info` / `#personality-chars` / `#set-personality` 由
 *   `/api/status` 填。
 *
 * 指令通道（对应 app.js 1627–1630，文案逐字照抄）：
 * - `#chat-model-info`「查看当前模型」→ `runCommands([".chat model"])`；
 * - `#personality-add` + `#apply-personality-add`「追加」→ `runCommands([".chat add " + 内容])`；
 * - `#personality-infix` + `#apply-personality-infix`「智能修改性格」→ `runCommands([".chat infix " + 要求])`；
 *   这三条都要过 DeepSeek，所以走指令通道，跑完（`runCommands` 的 promise）再 `refresh()` 刷状态，
 *   等价 Java 的 `.then(loadStatus)`；
 * - `#chatcfg-receipts` → `<Receipts id="chatcfg-receipts" />`。
 *
 * 保持不动（Java 也走本机接口 / 状态里读不到）：`#set-notice` / `#set-logmirror` 仍禁用
 * （`/api/status` 没有这两个字段，勾选状态无从判断）；`#apply-image-model` 继续走 `/api/settings`。
 */
import { useEffect, useRef, useState } from "react";
import { apiRequest, errorText } from "../client/api";
import { Receipts, useCapture } from "../client/capture";
import { useConsole } from "../client/store";
import type { StatusJson } from "../client/types";

export function ChatCfgPanel() {
  const { unlocked, status, toast, showBanner, updateStatus, refresh } = useConsole();
  const { runCommands } = useCapture();
  const [frequency, setFrequency] = useState("");
  const [chatModel, setChatModel] = useState("");
  const [imageModel, setImageModel] = useState("");
  const [personality, setPersonality] = useState("");
  /** `#personality-add` / `#personality-infix`：Java 版提交后不清空，用非受控 + ref 对齐。 */
  const personalityAdd = useRef<HTMLInputElement | null>(null);
  const personalityInfix = useRef<HTMLInputElement | null>(null);

  /** 状态里来的值同步到本地输入（用户正在输入时每 8 秒会被覆盖一次，和 Java 版行为一致）。 */
  useEffect(() => {
    if (!status) return;
    setFrequency(status.chat?.frequency === undefined ? "" : String(status.chat.frequency));
    setChatModel(status.chatChannel?.model ?? "");
    setImageModel(status.imageChannel?.model ?? "");
    setPersonality(status.chat?.personality ?? "");
  }, [status]);

  /** 改一项设置：接口返回新的整份状态，就地更新（不整页刷新）。 */
  async function applySetting(key: string, value: string, label: string) {
    try {
      const next = await apiRequest<StatusJson>("/api/settings", { method: "POST", body: { key, value } });
      updateStatus(next);
      toast("已保存：" + label);
    } catch (error) {
      showBanner("保存失败：" + errorText(error));
    }
  }

  /** `app.js` 1627：`#chat-model-info`「查看当前模型」→ `.chat model`（纯查询，不用刷状态）。 */
  const showChatModel = () => {
    void runCommands([".chat model"]);
  };

  /** `app.js` 1629：`#apply-personality-add` → `.chat add <内容>`，跑完 `.then(loadStatus)`。 */
  const appendPersonality = async () => {
    const value = personalityAdd.current?.value.trim() ?? "";
    if (!value) return;
    await runCommands([".chat add " + value]);
    await refresh();
  };

  /** `app.js` 1630：`#apply-personality-infix` → `.chat infix <要求>`（要过 DeepSeek），跑完刷状态。 */
  const rewritePersonality = async () => {
    const value = personalityInfix.current?.value.trim() ?? "";
    if (!value) return;
    await runCommands([".chat infix " + value]);
    await refresh();
  };

  const channelInfo = [status?.chatChannel, status?.imageChannel]
    .map((channel) => {
      if (!channel) return null;
      return (
        (channel.label ?? "") + "：" + (channel.model ?? "") +
        (channel.keyConfigured ? " · 密钥已配置" : " · 密钥未配置") +
        " · " + String(channel.thinking ?? false)
      );
    })
    .filter(Boolean)
    .join("　");

  return (
    <section className="panel active" id="panel-chatcfg">
      <div className="card">
        <div className="card-head"><b>开关</b></div>
        <label className="check">
          <input
            type="checkbox"
            id="set-chat-global"
            checked={!!status?.chat?.global}
            disabled={!unlocked}
            onChange={(event) => void applySetting("chatGlobal", event.target.checked ? "on" : "off", "聊天全局开关")}
          />
          全局聊天开关
        </label>
        <div className="row wrap">
          <span className="muted">每分钟回复上限</span>
          <input
            id="set-frequency"
            type="number"
            min="0"
            inputMode="numeric"
            placeholder="每分钟上限"
            value={frequency}
            disabled={!unlocked}
            onChange={(event) => setFrequency(event.target.value)}
          />
          <button
            type="button"
            id="apply-frequency"
            disabled={!unlocked || frequency === ""}
            onClick={() => void applySetting("chatFrequency", frequency, "每分钟上限")}
          >
            应用频率
          </button>
        </div>
        <div className="muted" id="chatcfg-summary">
          {status ? `全局聊天 ${status.chat?.global ? "开启" : "关闭"}　每分钟上限 ${status.chat?.frequency ?? "-"}` : ""}
        </div>
      </div>

      <div className="card">
        <div className="card-head"><b>频道</b><span className="muted">聊天与生图各自独立</span></div>
        <div className="grid-2">
          <label className="field">
            <span>聊天模型</span>
            <input id="set-chat-model" value={chatModel} disabled={!unlocked} onChange={(event) => setChatModel(event.target.value)} />
          </label>
          <label className="field">
            <span>生图模型</span>
            <input id="set-image-model" value={imageModel} disabled={!unlocked} onChange={(event) => setImageModel(event.target.value)} />
          </label>
        </div>
        <div className="row wrap">
          <label className="check">
            <input
              type="checkbox"
              id="set-chat-thinking"
              checked={!!status?.chatChannel?.thinking}
              disabled={!unlocked}
              onChange={(event) => void applySetting("chatThinking", event.target.checked ? "on" : "off", "聊天思考模式")}
            />
            聊天思考模式
          </label>
          <label className="check">
            <input
              type="checkbox"
              id="set-image-thinking"
              checked={!!status?.imageChannel?.thinking}
              disabled={!unlocked}
              onChange={(event) => void applySetting("imageThinking", event.target.checked ? "on" : "off", "生图思考模式")}
            />
            生图思考模式
          </label>
        </div>
        <div className="row wrap">
          <button
            type="button"
            id="apply-chat-model"
            disabled={!unlocked || !chatModel.trim()}
            onClick={() => void applySetting("chatModel", chatModel, "聊天模型")}
          >
            应用聊天模型
          </button>
          <button
            type="button"
            id="apply-image-model"
            disabled={!unlocked || !imageModel.trim()}
            onClick={() => void applySetting("imageModel", imageModel, "生图模型")}
          >
            应用生图模型
          </button>
          <button
            className="ghost"
            type="button"
            id="chat-model-info"
            disabled={!unlocked}
            title="走指令通道：.chat model"
            onClick={showChatModel}
          >
            查看当前模型
          </button>
        </div>
        <div className="muted" id="channel-info">{channelInfo}</div>
      </div>

      <div className="card">
        <div className="card-head"><b>性格设定</b><span className="muted" id="personality-chars">{(status?.chat?.personalityChars ?? 0) + " 字符"}</span></div>
        <textarea
          id="set-personality"
          rows={7}
          value={personality}
          disabled={!unlocked}
          onChange={(event) => setPersonality(event.target.value)}
        />
        <div className="row wrap">
          <button
            type="button"
            id="apply-personality"
            disabled={!unlocked}
            onClick={() => void applySetting("personality", personality, "性格设定")}
          >
            保存性格
          </button>
          <input
            id="personality-add"
            ref={personalityAdd}
            placeholder="追加内容"
            disabled={!unlocked}
            title="走指令通道：.chat add <内容>"
          />
          <button
            className="ghost"
            type="button"
            id="apply-personality-add"
            disabled={!unlocked}
            title="走指令通道：.chat add <内容>"
            onClick={() => void appendPersonality()}
          >
            追加
          </button>
        </div>
        <div className="row wrap">
          <input
            id="personality-infix"
            ref={personalityInfix}
            placeholder="让 DeepSeek 改性格，例如：更活泼一些"
            disabled={!unlocked}
            title="走指令通道：.chat infix <要求>"
          />
          <button
            type="button"
            id="apply-personality-infix"
            disabled={!unlocked}
            title="走指令通道：.chat infix <要求>"
            onClick={() => void rewritePersonality()}
          >
            智能修改性格
          </button>
        </div>
      </div>

      <div className="card">
        <div className="card-head"><b>通知</b></div>
        <div className="row wrap">
          {/* `app.js` 1631/1632：`setOption('notice'|'logMirror', 'on'|'off')`。
              Java 的 `/api/status` 不给这两个值，勾选框只能从"未勾选"开始；Next 版状态里有
              （`lib/web/status.ts` 的 `noticeEnabled`/`logMirror`），所以这里如实回显。 */}
          <label className="check">
            <input
              type="checkbox"
              id="set-notice"
              checked={!!status?.noticeEnabled}
              disabled={!unlocked}
              onChange={(event) => void applySetting("notice", event.target.checked ? "on" : "off", "上/下线播报")}
            />
            上/下线播报
          </label>
          <label className="check">
            <input
              type="checkbox"
              id="set-logmirror"
              checked={!!status?.logMirror}
              disabled={!unlocked}
              onChange={(event) => void applySetting("logMirror", event.target.checked ? "on" : "off", "日志同步到主群")}
            />
            日志同步到主群
          </label>
        </div>
      </div>

      <Receipts id="chatcfg-receipts" />
    </section>
  );
}
