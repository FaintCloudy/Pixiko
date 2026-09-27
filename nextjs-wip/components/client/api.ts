/**
 * 网页控制台的接口客户端（客户端组件专用，不进 `lib/**`）。
 *
 * 对应 Java 版 `webui/app.js` 里的 `api()`：
 * - 令牌只从 localStorage 取（键名与 Java 版一致），**绝不写进代码**；三种带法里网页用
 *   `Authorization: Bearer <令牌>`（`<img>` 用 `?token=`，见 `imageUrl()`）；
 * - 401 → 抛 `Unauthorized`（调用方回到登录框）；429 → 抛 `RateLimited`（冷却提示）；
 * - 其余非 2xx 取 `{error}` 文案抛出，交给 toast / `#banner` 显示，**不用 alert**。
 *
 * 这一轮只允许调用已迁移的接口（见 `docs/MIGRATION.md` 的 Phase 4）；其余分支仍是 501，
 * 面板上保持 disabled，不要发请求。
 */

export const TOKEN_KEY = "kotori-webui-token";

export class ApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

/** 令牌不对 / 没填（401）或服务端没配令牌（503）。 */
export class Unauthorized extends ApiError {
  constructor(message: string, status = 401) {
    super(status, message);
    this.name = "Unauthorized";
  }
}

/** 连续失败太多，30 秒冷却中（429）。 */
export class RateLimited extends ApiError {
  constructor(message: string) {
    super(429, message);
    this.name = "RateLimited";
  }
}

export function storedToken(): string {
  if (typeof window === "undefined") return "";
  try {
    return window.localStorage.getItem(TOKEN_KEY) ?? "";
  } catch {
    return "";
  }
}

export function saveToken(token: string): void {
  try {
    if (token) window.localStorage.setItem(TOKEN_KEY, token);
    else window.localStorage.removeItem(TOKEN_KEY);
  } catch {
    /* 隐私模式下写不了 localStorage：本次会话仍可用 */
  }
}

export function authHeaders(extra?: Record<string, string>): Record<string, string> {
  const token = storedToken();
  return Object.assign(
    { "Content-Type": "application/json" },
    token ? { Authorization: "Bearer " + token } : {},
    extra ?? {},
  );
}

/** `<img src>` 只能带查询串：把当前令牌拼进去。 */
export function imageUrl(path: string, caption?: string): string {
  const params = new URLSearchParams({ path });
  const token = storedToken();
  if (token) params.set("token", token);
  return "/api/image?" + params.toString();
}

type Options = { method?: "GET" | "POST"; body?: unknown; signal?: AbortSignal };

export async function apiRequest<T>(path: string, options: Options = {}): Promise<T> {
  const method = options.method ?? (options.body === undefined ? "GET" : "POST");
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers: authHeaders(),
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
      cache: "no-store",
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw new ApiError(0, "连不上机器人：" + (error instanceof Error ? error.message : String(error)));
  }
  const text = await response.text();
  let payload: unknown = {};
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch {
      payload = { error: text };
    }
  }
  if (response.status === 401 || response.status === 503) {
    throw new Unauthorized(messageOf(payload) || "访问令牌不正确。", response.status);
  }
  if (response.status === 429) throw new RateLimited(messageOf(payload) || "尝试次数过多，请稍后再试。");
  if (!response.ok) throw new ApiError(response.status, messageOf(payload) || "HTTP " + response.status);
  return payload as T;
}

function messageOf(payload: unknown): string {
  if (payload && typeof payload === "object" && "error" in payload) {
    const value = (payload as { error?: unknown }).error;
    if (typeof value === "string") return value;
  }
  return "";
}

/** 调用方只需要"失败时给用户看哪句话"时的统一处理。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return String(error);
}
