import { NextResponse } from "next/server";
import { okResult } from "@/lib/web/response.ts";
import { toNextResponse } from "@/lib/web/next-adapter.ts";

/**
 * 免令牌健康检查（对应 Java 版 `WebPageController.healthz`）。
 *
 * 路径必须保持 `/healthz`（不带 /api 前缀）：`tools/webui-selfcheck.mjs`、`stop-bot.ps1`
 * 和浏览器探针都按这个地址判断服务在不在，返回体固定是 `{"ok":true}`（沿用 Java 版的缩进格式）。
 */
export const dynamic = "force-dynamic";

export async function GET(): Promise<NextResponse> {
  return toNextResponse(okResult({ ok: true }));
}

export const POST = GET;
