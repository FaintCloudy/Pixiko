"use client";

/**
 * 系统栏（`/system`）：运行状态、高级指令执行、接口地址、Stable Diffusion、Civitai 账号。
 *
 * 已接：
 * - `#settings-bot` / `#endpoint-list` ← `/api/status`（与 app.js `loadStatus` / `renderEndpoints` 同一份字段）；
 * - `#sd-state` / `#sd-detail` / 两个自启动开关 ← `/api/sd/status`（每 8 秒轮询，与状态同频）；
 * - `#sd-start-btn` → `POST /api/sd/start`（202 立即返回，之后轮询状态直到 `reachable`），
 *   按钮在 `sd.available === false` 时禁用（与 app.js 的 `renderSd` 一致）；
 * - Civitai 账号：`#civitai-state` ← `/api/status` 的 `civitai` 段；
 *   `#civitai-link`（textarea）+ `#civitai-link-save`「保存并验证」→ 先走 `ShellDialog` 确认框
 *   （一次性链接只能用一次，**不用原生 confirm**），再 `POST /api/civitai/link { url }`，
 *   把返回的 `message` 用 toast（成功）/ 顶栏 banner（失败）展示，成功后就地刷新 `GET /api/civitai/status`；
 *   「清除已保存的 Cookie」→ 同样是确认框 → `POST /api/civitai/cookie/clear` 并就地刷新状态。
 *   与 Java 版现在的面板一致：**不再**引导「生成一次性登录链接」（`/api/civitai/login-link`
 *   与 `/civitai-login` 页面都还在，只是面板不再指向它们）。
 *
 * 指令通道（对应 app.js 1690 / 1730–1732）：
 * - `#console-form` 提交 → `runCommands([整段文本])`（Java 就是把 textarea 的原文整条传给
 *   `runCommands`，按行拆开是服务端 `/api/command` 的事：`\R` 拆行、最多 20 条）；
 * - `#shortcuts` 的 9 个常用指令按钮 → `runCommands([该条指令])`（文案与顺序照 app.js 1731）；
 * - `#system-receipts` → `<Receipts id="system-receipts" />`（文本 + 图片回执 + 执行中）。
 */
import { Fragment, useCallback, useRef, useState } from "react";
import { apiRequest, errorText } from "../client/api";
import { Receipts, useCapture } from "../client/capture";
import { useConsole, usePoll } from "../client/store";
import type { CivitaiLinkResult, CivitaiStatus, SdStatus, StatusJson } from "../client/types";

/** `app.js` 1731：高级指令面板下方的常用指令按钮（顺序、文案逐字照抄）。 */
const SHORTCUTS = [".style list", ".lora list", ".function list", ".preset list", ".settings", ".gen status", ".progress", ".chat", ".help"];

export function SystemPanel() {
  const { unlocked, status, toast, showBanner, updateStatus, askDialog, refresh } = useConsole();
  const { runCommands } = useCapture();
  /** `#console-input`：Java 版是普通 textarea（提交后不清空），这里用非受控 + ref 对齐。 */
  const consoleInput = useRef<HTMLTextAreaElement | null>(null);
  const [sd, setSd] = useState<SdStatus | null>(null);
  const [starting, setStarting] = useState(false);
  const [busy, setBusy] = useState(false);
  const [civitaiUrl, setCivitaiUrl] = useState("");
  const [savingLink, setSavingLink] = useState(false);
  /** 刚提交过的那条链接与它的脱敏提示：同一条再点一次基本只会白用掉一次性凭据。 */
  const lastLink = useRef<{ url: string; hint: string }>({ url: "", hint: "" });

  const loadSd = useCallback(async () => {
    try {
      setSd(await apiRequest<SdStatus>("/api/sd/status"));
    } catch (error) {
      // 读不到状态不是错误：保留上一次显示（与 app.js `loadSd` 一致）
      if (errorText(error)) setSd((previous) => previous);
    }
  }, []);

  usePoll(loadSd, 8000, unlocked);

  /**
   * 「保存并验证」：把整条一次性登录链接交给机器人，它自己跟跳转、抓 Cookie、存盘并验证
   * （对应 Java `webui/app.js` 的 `#civitai-link-form` 提交）。
   *
   * 同一封邮件的链接是「一次性」的，重复提交会消耗掉重试机会，所以先确认再发；刚提交过的那条
   * 直接拦下来。失败原因按 Java 的做法给红字（这里用顶栏 banner），成功用绿色 toast。
   */
  const saveCivitaiLink = useCallback(
    async (event: React.FormEvent<HTMLFormElement>) => {
      event.preventDefault();
      const url = civitaiUrl.trim();
      if (!url) {
        showBanner("请先粘贴 Civitai 发来的登录链接。");
        return;
      }
      const hint = status?.civitai?.cookieHint ?? lastLink.current.hint;
      if (lastLink.current.url !== "" && url === lastLink.current.url && status?.civitai?.hasCookie) {
        toast("这条链接刚刚已经保存过了（" + hint + "）；登录凭据是一次性的，重发同一条会用掉它。");
        return;
      }
      const confirmed = await askDialog({
        title: "保存 Civitai 登录链接",
        text: "把这条一次性登录链接交给机器人？\n它会立刻打开该链接；同一封邮件的链接只能用一次。",
        confirmText: "保存并验证",
      });
      if (confirmed !== true) return;
      setSavingLink(true);
      try {
        const result = await apiRequest<CivitaiLinkResult>("/api/civitai/link", { method: "POST", body: { url } });
        lastLink.current = { url, hint: result.cookieHint ?? hint };
        setCivitaiUrl("");
        toast(result.message || "已保存 Civitai 登录凭据。");
        // 成功后刷新 Civitai 那一格：`#civitai-state` 吃的就是 `/api/status` 里的 civitai 段，
        // 这里用同形的 `/api/civitai/status` 就地替换它（其它字段由外壳每 8 秒轮询）。
        const civitai = await apiRequest<CivitaiStatus>("/api/civitai/status");
        if (status) updateStatus({ ...status, civitai });
        else await refresh();
      } catch (error) {
        showBanner(errorText(error));
      } finally {
        setSavingLink(false);
      }
    },
    [askDialog, civitaiUrl, refresh, showBanner, status, toast, updateStatus],
  );

  /** 「清除已保存的 Cookie」：先走同风格确认框（不用原生 confirm），确认后就地刷新状态。 */
  const clearCivitai = useCallback(async () => {
    const confirmed = await askDialog({
      title: "清除 Civitai Cookie",
      text: "清除已保存的 Civitai 账号？\n之后只能搜索公开内容，成人内容与部分模型不可见。",
      confirmText: "清除",
      danger: true,
    });
    if (confirmed !== true) return;
    setBusy(true);
    try {
      await apiRequest("/api/civitai/cookie/clear", { method: "POST", body: {} });
      toast("已清除 Civitai Cookie");
      lastLink.current = { url: "", hint: "" };
      await refresh();
    } catch (error) {
      showBanner("清除失败：" + errorText(error));
    } finally {
      setBusy(false);
    }
  }, [askDialog, refresh, showBanner, toast]);

  const startSd = useCallback(async () => {
    setStarting(true);
    try {
      const result = await apiRequest<{ sd?: SdStatus }>("/api/sd/start", { method: "POST", body: {} });
      if (result.sd) setSd(result.sd);
      toast(result.sd?.reachable ? "SD 已经在运行" : "正在启动 SD WebUI…");
      if (result.sd?.reachable) return;
      // 启动要等模型加载（几十秒到几分钟）：每 3 秒看一次，就绪就停
      const deadline = Date.now() + 6 * 60 * 1000;
      while (Date.now() < deadline) {
        await new Promise((resolve) => window.setTimeout(resolve, 3000));
        const next = await apiRequest<SdStatus>("/api/sd/status");
        setSd(next);
        if (next.reachable) {
          toast("SD WebUI 已就绪");
          return;
        }
      }
    } catch (error) {
      showBanner("启动 SD 失败：" + errorText(error));
    } finally {
      setStarting(false);
    }
  }, [showBanner, toast]);

  async function applySetting(key: string, value: string, label: string) {
    try {
      updateStatus(await apiRequest<StatusJson>("/api/settings", { method: "POST", body: { key, value } }));
      toast("已保存：" + label);
      void loadSd();
    } catch (error) {
      showBanner("保存失败：" + errorText(error));
    }
  }

  const pending = new Set(status?.pendingFields ?? []);
  const botRows: Array<[string, string]> = status
    ? [
        ["机器人", status.botName ?? ""],
        ["提示词归属", status.scope ?? ""],
        ["监听端口", String(status.webPort ?? "")],
        ["待领取图片", String(status.pendingImages ?? 0)],
        ["聊天全局", status.chat?.global ? "开启" : "关闭"],
        ["每分钟上限", String(status.chat?.frequency ?? "")],
        ["性格字数", String(status.chat?.personalityChars ?? 0)],
        ["提示词词条", pending.has("promptTerms") ? "待接入" : String(status.promptTerms ?? 0)],
        ["LoRA", pending.has("loraStatus") ? "待接入" : status.loraStatus ?? ""],
      ]
    : [];

  const endpointRows: Array<[string, string]> = [
    ["DeepSeek 聊天 API", status?.endpoints?.chatApi ?? ""],
    ["DeepSeek 生图 API", status?.endpoints?.imageApi ?? ""],
    ["Stable Diffusion", status?.endpoints?.sd ?? ""],
    ["NapCat（QQ）", status?.endpoints?.napcat ?? ""],
  ];

  const civitai = status?.civitai;

  return (
    <section className="panel active" id="panel-system">
      <div className="card">
        <div className="card-head"><b>运行状态</b></div>
        <div className="kv" id="settings-bot">
          {/* `.kv > div:nth-child(odd)` 靠直接子元素算「标签列」，所以不能用包裹层 */}
          {botRows.map(([label, value]) => (
            <Fragment key={label}>
              <div>{label}</div>
              <div>{value}</div>
            </Fragment>
          ))}
        </div>
      </div>

      <div className="card">
        <div className="card-head"><b>高级：直接执行指令</b><span className="muted">每行一条，最多 20 条</span></div>
        <form
          className="composer"
          id="console-form"
          onSubmit={(event) => {
            event.preventDefault();
            // `app.js` 1690：整段文本作为一条指令交给 runCommands（拆行、限 20 条是服务端的事）。
            const text = consoleInput.current?.value.trim() ?? "";
            if (text) void runCommands([text]);
          }}
        >
          <textarea
            id="console-input"
            name="console"
            rows={3}
            ref={consoleInput}
            placeholder={".style list\n.infix 换成草地\n.gen 1"}
          />
          <button type="submit" id="console-send">执行</button>
        </form>
        <div className="shortcuts" id="shortcuts">
          {/* `app.js` 1731–1732：每条常用指令一个 `button.ghost`，点了走同一条指令通道。 */}
          {SHORTCUTS.map((command) => (
            <button className="ghost" type="button" key={command} onClick={() => void runCommands([command])}>
              {command}
            </button>
          ))}
        </div>
      </div>

      <div className="card">
        <div className="card-head"><b>接口地址</b><span className="muted">本机连接的服务（不含密钥）</span></div>
        <div className="kv" id="endpoint-list">
          {endpointRows.map(([label, value]) => (
            <Fragment key={label}>
              <div>{label}</div>
              <div>{value || "（未配置）"}</div>
            </Fragment>
          ))}
        </div>
      </div>

      <div className="card">
        <div className="card-head"><b>Stable Diffusion</b><span className="muted" id="sd-state">{sd ? (sd.reachable ? "运行中" : "未运行") : ""}</span></div>
        <div className="muted" id="sd-detail">
          {[
            sd?.root ? "目录：" + sd.root : "目录未配置（config.json 的 sd.root）",
            sd?.launcher ? "启动入口：" + sd.launcher : "启动入口：" + (sd?.available ? "-" : "没找到"),
            sd?.args ? "启动参数：" + sd.args : "",
          ]
            .filter(Boolean)
            .join("\n")}
        </div>
        <div className="row wrap">
          <button
            type="button"
            id="sd-start-btn"
            disabled={!unlocked || !sd?.available || starting}
            title={sd?.available ? "拉起 SD WebUI（后台启动，之后轮询状态）" : "没找到启动入口（config.json 的 sd.root / sd.start_command）"}
            onClick={() => void startSd()}
          >
            {starting ? "启动中…" : "启动 SD"}
          </button>
          <label className="check">
            <input
              type="checkbox"
              id="sd-auto-start"
              checked={!!sd?.autoStart}
              disabled={!unlocked}
              onChange={(event) => void applySetting("sdAutoStart", event.target.checked ? "on" : "off", "SD 自动启动")}
            />
            生成前自动启动
          </label>
          <label className="check">
            <input
              type="checkbox"
              id="sd-start-on-boot"
              checked={!!sd?.startOnBoot}
              disabled={!unlocked}
              onChange={(event) => void applySetting("sdStartOnBoot", event.target.checked ? "on" : "off", "开机自启动 SD")}
            />
            机器人启动时启动
          </label>
        </div>
      </div>

      <div className="card">
        <div className="card-head"><b>Civitai 账号</b><span className="muted">可稍后配置</span></div>
        <div className="muted" id="civitai-state">
          {civitai
            ? civitai.hasCookie
              ? "已保存 " + (civitai.host ?? "") + " 的登录 Cookie（" + (civitai.cookieHint ?? "") + "）"
              : "未保存 Cookie：搜索与下载会使用 " + (civitai.fallbackHost ?? "civitai.com") + "，成人内容与部分模型不可见。"
            : ""}
        </div>
        <form className="composer" id="civitai-link-form" onSubmit={(event) => void saveCivitaiLink(event)}>
          <textarea
            id="civitai-link"
            rows={2}
            value={civitaiUrl}
            placeholder="粘贴 Civitai 发来的一次性登录链接（https://auth.civitai.com/…）"
            disabled={!unlocked || savingLink}
            onChange={(event) => setCivitaiUrl(event.target.value)}
          />
          <button type="submit" id="civitai-link-save" disabled={!unlocked || savingLink || busy}>
            {savingLink ? "保存中…" : "保存并验证"}
          </button>
        </form>
        <div className="muted">
          打开邮箱里那封登录邮件，把里面的登录链接整条复制过来；机器人会自己跟着跳转、抓回凭据并验证。
        </div>
        <div className="row wrap">
          <button
            className="ghost"
            type="button"
            id="civitai-clear-btn"
            disabled={!unlocked || busy || savingLink}
            title={civitai?.hasCookie ? "清除本机保存的 Civitai 账号（会先弹确认框）" : "当前没有保存账号，点了也只会显示确认框"}
            onClick={() => void clearCivitai()}
          >
            清除已保存的 Cookie
          </button>
        </div>
      </div>

      <Receipts id="system-receipts" />
    </section>
  );
}
