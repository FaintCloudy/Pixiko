/**
 * `/api/status`、`/api/sd/status`、`/api/logs`、`/api/chat/history` 的返回结构。
 *
 * 字段名与 Java 版 `Bot.webStatus` 一一对应（见 `lib/web/status.ts`）；
 * 还没迁移的字段（`generation` / `promptTerms` / `loraStatus`）在 Java 里就有，
 * TS 侧目前放在 `pendingFields` 里明示，所以这里全部标成可选。
 */

export type ChannelStatus = {
  label?: string;
  section?: string;
  model?: string;
  thinking?: boolean;
  keyFile?: string;
  keyConfigured?: boolean;
};

export type ChatStatus = {
  global?: boolean;
  frequency?: number;
  contextSeconds?: number;
  personalityChars?: number;
  personality?: string;
};

export type CivitaiStatus = {
  hasCookie?: boolean;
  host?: string;
  cookieHint?: string;
  fallbackHost?: string;
  linkMinutes?: number;
};

/**
 * `POST /api/civitai/link` 的返回体（一次性登录链接 → 抓 Cookie 并验证）。
 *
 * 服务端那份是 `lib/civitai/civitai-link.ts` 的 `CivitaiLinkResult`（字段名与 Java
 * `Bot.civitaiSaveFromLink` 逐字一致）；这里是面板侧的只读视图，全部可选。
 */
export type CivitaiLinkResult = {
  ok?: boolean;
  verified?: boolean;
  hasCookie?: boolean;
  host?: string;
  cookieHint?: string;
  models?: number;
  message?: string;
};

export type Endpoints = {
  chatApi?: string;
  imageApi?: string;
  chatApiUrl?: string;
  imageApiUrl?: string;
  sd?: string;
  napcat?: string;
};

export type SdStatus = {
  reachable?: boolean;
  autoStart?: boolean;
  startOnBoot?: boolean;
  available?: boolean;
  root?: string;
  launcher?: string;
  args?: string;
  text?: string;
};

export type StatusJson = {
  botName?: string;
  scope?: string;
  webPort?: number;
  path?: string;
  time?: string;
  uptimeSeconds?: number;
  chat?: ChatStatus;
  chatChannel?: ChannelStatus;
  imageChannel?: ChannelStatus;
  infixFilter?: boolean;
  imageCount?: number;
  autoGet?: boolean;
  /** 上/下线播报与日志镜像（Java 的 `/api/status` 没有这两个字段，Next 版补上以便勾选框如实回显）。 */
  noticeEnabled?: boolean;
  logMirror?: boolean;
  civitai?: CivitaiStatus;
  sd?: SdStatus;
  endpoints?: Endpoints;
  pendingImages?: number;
  pendingFields?: string[];
  generation?: {
    sampler?: string;
    width?: number;
    height?: number;
    model?: string;
    steps?: number;
    cfg?: number;
    seed?: number;
    status?: string;
  };
  promptTerms?: number;
  loraStatus?: string;
};

export type LogsPayload = { lines?: string[]; source?: string };

/** `/api/chat/history` 直接返回数组（不是对象）。 */
export type ChatEntry = { role?: string; content?: string };

/** `/api/status` 的模型 / 采样器等下拉还没有接口（`/api/options` 未迁移）。 */
export type SettingsKey =
  | "chatGlobal"
  | "chatFrequency"
  | "personality"
  | "chatModel"
  | "imageModel"
  | "infixFilter"
  | "chatThinking"
  | "imageThinking"
  | "sdAutoStart"
  | "sdStartOnBoot"
  | "notice"
  | "logMirror"
  | "autoGet"
  | "token";
