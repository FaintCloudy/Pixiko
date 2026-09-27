/**
 * 一次性令牌回收 Civitai Cookie（对应 Java 版 `WebPageController.civitaiCookie`）。
 *
 * 由登录页的书签小工具跨域调用（`http://127.0.0.1:<端口>` 被浏览器当可信来源，
 * `text/plain` 属于简单请求、不触发预检），所以这里要回 CORS 头；**不走访问令牌**，
 * 靠一次性令牌鉴权（用掉即废，见 `WebConsole.civitaiSaveCookie`）。
 */
import { NextResponse } from "next/server";
import { webConsole } from "@/lib/core/instances";
import { errorText, isBadInput } from "@/lib/util/errors.ts";
import { jsonResult, prettyJson } from "@/lib/web/response.ts";
import { toNextResponse } from "@/lib/web/next-adapter.ts";

export const dynamic = "force-dynamic";

const CORS = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "Content-Type" };

export async function OPTIONS(): Promise<NextResponse> {
  return new NextResponse(null, { status: 204, headers: CORS });
}

export async function POST(request: Request): Promise<NextResponse> {
  try {
    const raw = await request.text();
    let body: Record<string, unknown> = {};
    try {
      body = raw.trim().length === 0 ? {} : (JSON.parse(raw) as Record<string, unknown>);
    } catch {
      return toNextResponse(jsonResult(400, { error: "请求体不是合法 JSON。" }));
    }
    const token = typeof body["token"] === "string" ? body["token"] : "";
    const cookie = typeof body["cookie"] === "string" ? body["cookie"] : "";
    const result = await webConsole().civitaiSaveCookie(token, cookie);
    return new NextResponse(prettyJson(result), {
      status: 200,
      headers: { ...CORS, "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
    });
  } catch (error) {
    const status = isBadInput(error) ? 400 : 500;
    return new NextResponse(prettyJson({ error: errorText(error) }), {
      status,
      headers: { ...CORS, "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
    });
  }
}
