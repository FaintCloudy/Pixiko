/**
 * 网页控制台的全部 `/api/**` 接口。
 *
 * 这里刻意保留 Java 版 `WebApiController` 的**一个 switch** 结构：分支顺序、状态码、
 * POST 守卫和错误映射一一对应，迁移期间对照着看不会走样（自检脚本也按同一张表核对）。
 *
 * 分三层：
 * 1. **配置/日志类**：只依赖 `Settings` 与文件系统（status/settings/logs/help/sd/chat 历史/image）；
 * 2. **网页控制台纯本机接口**：走 `lib/core/web-console.ts`（提示词/提示词集/样式/预置/释义/Civitai 账号）；
 * 3. **需要组装根的接口**：动态 `import("@/lib/core/bot")` —— 指令回执、生成队列、LoRA、出图投递。
 *    动态 import 是为了把整台机器人的模块图排除在 Next 构建期的静态文件追踪之外。
 */
import type { NextRequest } from "next/server";
import { NextResponse } from "next/server";
import { started } from "@/lib/core/runtime";
import { parse, str, bool, num, type JsonArray, type JsonObject } from "@/lib/util/json.ts";
import { BadInput, Conflict, errorText, isBadInput, isConflict } from "@/lib/util/errors.ts";
import { javaIsBlank, javaStrip } from "@/lib/util/text.ts";
import * as Log from "@/lib/util/log.ts";
import { authorize } from "@/lib/web/auth.ts";
import { bytesResult, jsonError, jsonResult, okResult, type ApiResult } from "@/lib/web/response.ts";
import { toNextResponse } from "@/lib/web/next-adapter.ts";
import { CIVITAI_LINK_MINUTES, statusJson } from "@/lib/web/status.ts";
import { applySetting } from "@/lib/web/settings-apply.ts";
import { logTail, normalizeSource } from "@/lib/web/logs.ts";
import { serveImage } from "@/lib/web/files.ts";
import { sdStatusJson, startSdInBackground } from "@/lib/web/sd-status.ts";
import { chatHistory, rememberChat, writeChat } from "@/lib/web/chat-history.ts";
import { webConsole } from "@/lib/core/instances";
import { consoleCommand, HELP } from "@/lib/core/commands.ts";
import { botInstance } from "@/lib/core/bot-holder.ts";

/** 组装根的最小结构（只是给路由用的类型；运行时由 instrumentation.ts 放进来）。 */
interface PixikoBot {
  generation: {
    tasksJson(): unknown;
    taskActionJson(action: string, number: string): unknown;
    generationJson(body: JsonObject): Promise<Record<string, unknown>>;
    progressJson(): Promise<unknown>;
    generationSummary(): Promise<unknown>;
  };
  lora: {
    lorasJson(): Promise<unknown>;
    civitaiSearchJson(scope: string, query: string): Promise<unknown>;
    status(): string;
  };
  images: { imagesJson(limit: number): unknown };
  captures: {
    webCommand(scope: string, commands: readonly string[]): { json(busy: boolean): unknown };
    webCapture(id: string): { json(busy: boolean): unknown } | null;
    webClose(id: string): void;
  };
  /** 网页对话（Java `Bot.webChat`）：返回 `{ reply, commands, interest[, captureId] }`。 */
  webChat(scope: string, message: string, history: JsonArray, execute: boolean): Promise<JsonObject>;
  busy(): boolean;
}

/**
 * 取机器人实例。
 *
 * ⚠️ 这里**绝不能** `import("@/lib/core/bot")`：Next 构建期收集页面数据时会**求值**路由所在的
 * chunk，而机器人那堆模块里有 BigInt 运算，静态求值器会在「BigInt 与数字混用」上崩掉
 * （`Cannot mix BigInt and other types`），build 直接失败 —— 实测 instrumentation 静态 import、
 * 路由静态 import、路由动态 import 三种写法都会崩。
 *
 * 所以机器人必须跑在 **Next 的模块图之外**（`server.mjs` 自定义服务器或独立进程，见
 * docs/MIGRATION.md 的"进程边界"一节）。在那之前，需要机器人的接口明确回 503。
 */
function requireBot(): PixikoBot {
  const instance = botInstance<PixikoBot>();
  if (instance === null) {
    throw new Conflict("机器人尚未启动：需要机器人的接口暂时不可用（迁移中，见 docs/MIGRATION.md）。");
  }
  return instance;
}

export const dynamic = "force-dynamic";

/** 还没迁移的分支：接口 → 说明（501 提示与自检对照用）。当前已全部落地，保留这张表给后续分支用。 */
const PENDING: Record<string, string> = {};

async function handle(request: NextRequest): Promise<NextResponse> {
  const runtime = started();
  const { settings } = runtime;

  const authFailure = await authorize(request, settings);
  if (authFailure) return toNextResponse(authFailure);

  // 用请求原始路径（与 Java 的 request.getRequestURI() 等价），不用已经解码的分段。
  const apiPath = request.nextUrl.pathname.replace(/\/+$/, "") || "/api";

  return Log.runAsWeb(async () => {
    try {
      const body = await parseBody(request);
      return toNextResponse(await route(request, apiPath, body, runtime.startedAt));
    } catch (error) {
      if (isBadInput(error)) return toNextResponse(jsonResult(400, jsonError(errorText(error))));
      if (isConflict(error)) return toNextResponse(jsonResult(409, jsonError(errorText(error))));
      Log.warn(`WebUI 请求失败（${apiPath}）：` + errorText(error));
      return toNextResponse(jsonResult(500, jsonError(errorText(error))));
    }
  });
}

async function route(
  request: NextRequest,
  apiPath: string,
  body: JsonObject,
  startedAt: number,
): Promise<ApiResult> {
  const { settings } = started();
  const method = request.method;

  /**
   * 整份状态：配置类字段来自 `lib/web/status.ts`，生成队列摘要与 LoRA 状态要组装根。
   * `/api/status` 与 `/api/settings` 共用它 —— Java 的 `/api/settings` 回的也是 `bot.webStatus()`，
   * 少几个字段的话，网页改完设置就会把队列摘要从本地状态里抹掉。
   */
  async function fullStatus(): Promise<Record<string, unknown>> {
    const base = await statusJson(settings, startedAt);
    const instance = botInstance<PixikoBot>();
    if (instance === null) return base;
    return {
      ...base,
      generation: await instance.generation.generationSummary(),
      loraStatus: instance.lora.status(),
      pendingFields: [],
    };
  }

  // ── 1. 配置 / 日志 / 页面数据 ────────────────────────────────────────────────
  switch (apiPath) {
    case "/api/status":
      return okResult(await fullStatus());
    case "/api/settings": {
      requirePost(method);
      await applySetting(settings, body);
      return okResult(await fullStatus());
    }
    case "/api/help":
      return okResult({ help: HELP });
    case "/api/logs": {
      // 日志分两路：all（全部）/ qq（QQ 侧）/ web（网页侧）；面板可以随意切换。
      const source = str(body, "source", "all");
      const wanted = Math.trunc(Number(str(body, "lines", "200")));
      const lines = logTail(settings.root, source, Number.isFinite(wanted) && wanted > 0 ? wanted : 200);
      return okResult({ lines, source: normalizeSource(source) });
    }
    case "/api/sd/status":
      return okResult(await sdStatusJson());
    case "/api/sd/start": {
      // 「启动 SD」按钮：拉起 SD 要等模型加载（几十秒到几分钟），后台跑，网页轮询状态即可。
      requirePost(method);
      startSdInBackground();
      return jsonResult(202, { started: true, sd: await sdStatusJson() });
    }
    case "/api/chat/history":
      return okResult(chatHistory(settings.root, str(body, "scope", await settings.webScope())));
    case "/api/chat/reset": {
      // 清空网页会话历史；返回 `{}`（与 Java 版一致，前端只看状态码）。
      writeChat(settings.root, str(body, "scope", await settings.webScope()), []);
      return okResult({});
    }
    case "/api/image":
      return serveImage(settings.root, imageQuery(request, body));
    default:
      break;
  }

  // ── 2. 网页控制台的纯本机接口（提示词 / 提示词集 / 样式 / 预置 / 释义 / Civitai 账号）──
  const console_ = webConsole();
  const scope = str(body, "scope", await settings.webScope());
  switch (apiPath) {
    case "/api/prompt":
      return okResult(await console_.webPrompt(scope));
    case "/api/meanings": {
      requirePost(method);
      const terms = Array.isArray(body["terms"]) ? (body["terms"] as unknown[]).map((item) => String(item)) : [];
      return okResult(await console_.webMeanings(terms, bool(body, "learn", true)));
    }
    case "/api/prompt/edit":
      requirePost(method);
      return okResult(
        await console_.webPromptEdit(scope, str(body, "side", "positive"), str(body, "action", ""), str(body, "value", "")),
      );
    case "/api/functions":
      return okResult(await console_.webFunctions(scope));
    case "/api/functions/edit":
      requirePost(method);
      return okResult(
        await console_.webFunctionsEdit(
          scope,
          str(body, "action", ""),
          str(body, "name", ""),
          str(body, "newName", ""),
          bool(body, "overwrite", false),
        ),
      );
    case "/api/styles":
      return okResult(await console_.webStyles());
    case "/api/styles/edit":
      requirePost(method);
      return okResult(
        await console_.webStylesEdit(
          scope,
          str(body, "action", ""),
          str(body, "name", ""),
          str(body, "newName", ""),
          bool(body, "overwrite", false),
          bool(body, "noLora", false),
        ),
      );
    case "/api/presets":
      return okResult(await console_.webPresets());
    case "/api/presets/edit":
      requirePost(method);
      return okResult(await console_.webPresetsEdit(str(body, "action", ""), str(body, "name", ""), bool(body, "overwrite", false)));
    case "/api/options":
      return okResult(await console_.webOptions());
    case "/api/usage":
      return okResult(await console_.webUsage(str(body, "query", "")));
    case "/api/civitai/status":
      return okResult(console_.civitaiStatus());
    case "/api/civitai/login-link": {
      // 一次性登录链接：在浏览器里登录 Civitai 后由登录页把 Cookie 交回来（不用手爬 token）。
      const token = console_.civitaiIssueLoginToken();
      return okResult({
        url: `http://${localHost(request)}:${settings.webPort()}/civitai-login?token=${token}`,
        expiresMinutes: CIVITAI_LINK_MINUTES,
      });
    }
    case "/api/civitai/cookie":
      requirePost(method);
      return okResult(await console_.civitaiSaveCookie(str(body, "token", ""), str(body, "cookie", "")));
    case "/api/civitai/link": {
      // 「Civitai 账号」卡只有一个输入框：粘贴邮件里的一次性登录链接，机器人自己跟完整条跳转
      // 并把 Cookie 抓回来（对应 Java `WebApiController` 的 `/api/civitai/link` 分支）。
      requirePost(method);
      // 动态 import：这条例行接口要真的发请求，链路里会牵进 civitai 那套模块图；与 `/api/civitai/thumb`
      // 一样避免让构建期「Collecting page data」去静态求值它（见 lib/core/bot-holder.ts 的注释）。
      const { saveFromLoginLink } = await import("@/lib/civitai/civitai-link.ts");
      const result = await saveFromLoginLink(settings.root, str(body, "url", ""));
      // config.json 是 `lib/util/json.ts` 的 update() 直接写的（不走 Settings.write），
      // 重读一次内存快照，紧接着的 `/api/civitai/status`（webConsole 用的是同一个 Settings 实例）
      // 才能立刻看到新 Cookie，而不是等下次重启。
      await settings.reload();
      return okResult(result);
    }
    case "/api/civitai/cookie/clear":
      return okResult(await console_.civitaiClearCookie());
    default:
      break;
  }

  // ── 3. 需要组装根的接口（QQ 链路、生成队列、LoRA、出图投递、网页回执）──────────
  // 首次调用时按需把整台机器人拉起来（实例放持有器；instrumentation.ts 绝不能 import 它，\n  // 否则 Next 构建期收集页面数据时会去求值那些 BigInt 代码并崩 —— 见 lib/core/bot-holder.ts）。
  const instance = await requireBot();
  switch (apiPath) {
    case "/api/command": {
      requirePost(method);
      const raw = str(body, "command", "");
      if (raw.trim().length === 0) throw new BadInput("指令不能为空。");
      // 控制台不用加点和斜杠：help / gen 2 / style list 都直接认（按 Java 的 `\R` 语义拆行）。
      const commands = raw
        .split(/\r\n|[\n\r\u0085\u2028\u2029\u000B\u000C]/)
        .map((line) => consoleCommand(line))
        .filter((line) => line.length > 0);
      if (commands.length > 20) throw new BadInput("一次最多 20 条指令。");
      const capture = instance.captures.webCommand(scope, commands);
      return jsonResult(202, capture.json(instance.busy()));
    }
    case "/api/capture": {
      const capture = instance.captures.webCapture(str(body, "id", ""));
      if (capture === null) throw new BadInput("回执已结束或不存在。");
      return okResult(capture.json(instance.busy()));
    }
    case "/api/capture/close":
      instance.captures.webClose(str(body, "id", ""));
      return okResult({ closed: true });
    case "/api/chat": {
      // 网页对话（Java `WebApiController` L139 分支）：消息空白 → 400；`execute` 默认 true。
      const message = javaStrip(str(body, "message", ""));
      if (javaIsBlank(message)) throw new BadInput("消息不能为空。");
      const history = chatHistory(settings.root, scope);
      const result = await instance.webChat(scope, message, history, bool(body, "execute", true));
      // 对话历史由**这一层**记（Java 是 controller 记的，`webChat` 自己不碰历史文件）：
      // 一条消息恰好产生"用户 + 助手"一轮，绝不会重复记。
      rememberChat(settings.root, scope, message, str(result, "reply", ""));
      return okResult(result);
    }
    case "/api/tasks":
      return okResult(instance.generation.tasksJson());
    case "/api/tasks/action": {
      requirePost(method);
      return okResult(instance.generation.taskActionJson(str(body, "action", ""), str(body, "number", "")));
    }
    case "/api/generation": {
      requirePost(method);
      // Java 也是「先应用改动，再回整份状态」。
      const patch = await instance.generation.generationJson(body);
      return okResult({ ...(await statusJson(settings, startedAt)), ...patch });
    }
    case "/api/progress":
      return okResult(await instance.generation.progressJson());
    case "/api/loras":
      return okResult(await instance.lora.lorasJson());
    case "/api/images":
      return okResult({ images: instance.images.imagesJson(num(body, "limit", 60)) });
    case "/api/civitai/search": {
      requirePost(method);
      return okResult(await instance.lora.civitaiSearchJson(scope, str(body, "query", "")));
    }
    case "/api/civitai/thumb": {
      const url = str(body, "url", "") || (request.nextUrl.searchParams.get("url") ?? "");
      // 动态 import：`CivitaiClient` 里有大量 BigInt 运算，静态进图会让构建期的静态求值器崩
      // （见 lib/core/bot-holder.ts 与 docs/MIGRATION.md 的记录）。
      const { civitaiCover } = await import("@/lib/web/civitai-cover.ts");
      try {
        const image = await civitaiCover(settings.root, url);
        return bytesResult(200, image.bytes, image.contentType, "private, max-age=3600");
      } catch (error) {
        // 地址不合法是 **400**：Java 这里只 catch `IOException`（取图失败才 502），
        // `IllegalArgumentException`（缺地址 / 不是 Civitai 图床）会交给外层统一映射成 400。
        if (isBadInput(error)) return jsonResult(400, jsonError(errorText(error)));
        Log.warn("Civitai 封面抓取失败：" + errorText(error));
        return jsonResult(502, jsonError("封面抓取失败：" + errorText(error)));
      }
    }
    default:
      break;
  }

  const stage = PENDING[apiPath];
  if (stage) {
    return jsonResult(501, {
      error: `接口尚未迁移：${apiPath}（${stage}，见 docs/MIGRATION.md）`,
      migrating: true,
    });
  }
  return jsonResult(404, jsonError(`未知接口：${apiPath}`));
}

/** 本机访问时用的主机名：优先回环地址，避免给出 0.0.0.0 这种点不开的链接。 */
function localHost(request: NextRequest): string {
  const host = request.headers.get("host");
  if (host && host.trim().length > 0) {
    const colon = host.lastIndexOf(":");
    const name = colon > 0 ? host.slice(0, colon) : host;
    if (name.length > 0 && name !== "0.0.0.0" && name !== "::" && name !== "[::]") return name;
  }
  return "127.0.0.1";
}

function requirePost(method: string): void {
  if (method !== "POST") throw new BadInput("请使用 POST。");
}

/** 请求体：最多 4MB，必须是合法 JSON 对象（空体当空对象）。 */
async function parseBody(request: NextRequest): Promise<JsonObject> {
  if (request.method === "GET" || request.method === "HEAD") return {};
  const raw = await request.text();
  if (raw.length === 0) return {};
  if (Buffer.byteLength(raw, "utf8") > 4 * 1024 * 1024) throw new BadInput("请求体过大。");
  const text = raw.trim();
  if (text.length === 0) return {};
  try {
    return parse(text);
  } catch {
    throw new BadInput("请求体不是合法 JSON。");
  }
}

/** 图片路径可以放 JSON 体里，也可以放查询串里（`<img src>` 只能带查询串）。 */
function imageQuery(request: NextRequest, body: JsonObject): string {
  const fromBody = str(body, "path", "");
  if (fromBody.trim().length > 0) return fromBody;
  return request.nextUrl.searchParams.get("path") ?? "";
}

export async function GET(request: NextRequest): Promise<NextResponse> {
  return handle(request);
}

export async function POST(request: NextRequest): Promise<NextResponse> {
  return handle(request);
}

/** 其余方法一律 405（Java 的 Spring 映射只挂了 GET/POST）。 */
export async function PUT(): Promise<NextResponse> {
  return toNextResponse(jsonResult(405, jsonError("不支持该请求方法。")));
}

export const DELETE = PUT;
export const PATCH = PUT;
