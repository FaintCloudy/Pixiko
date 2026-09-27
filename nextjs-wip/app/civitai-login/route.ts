/**
 * Civitai 一次性登录页（对应 Java 版 `WebPageController.civitaiLogin`）。
 *
 * 目的：**不用手动去 F12 里爬 Cookie**。流程是
 *   1. 网页控制台点「生成登录链接」→ `/api/civitai/login-link` 拿到 `?token=…`；
 *   2. 在本页登录 Civitai（第一次点击会打开 civitai 站点）；
 *   3. 把「回填到 Pixiko」这个书签小工具拖到书签栏，**在 Civitai 页面上**点它 ——
 *      它读取 `document.cookie` 并 POST 回本机 `/civitai-cookie`，令牌一次性、用掉即废。
 * 手动粘贴那一段只是兜底，正常用不到。
 *
 * 本路由**不走访问令牌**（登录页得先能打开），靠一次性令牌鉴权；页面固定 `no-store`，
 * 免得浏览器把带 token 的页面缓存下来。
 */
import { NextResponse } from "next/server";
import { started } from "@/lib/core/runtime";
import { webConsole } from "@/lib/core/instances";
import { CIVITAI_LINK_MINUTES } from "@/lib/web/status.ts";
import { bytesResult } from "@/lib/web/response.ts";
import { toNextResponse } from "@/lib/web/next-adapter.ts";

export const dynamic = "force-dynamic";

function html(body: string, status: number): NextResponse {
  return toNextResponse(bytesResult(status, Buffer.from(body, "utf8"), "text/html; charset=utf-8", "no-store"));
}

/** 书签小工具：在 Civitai 页面上把 document.cookie 发回本机。 */
export function bookmarklet(token: string, port: number): string {
  return (
    "javascript:(function(){" +
    // 在控制台这一页点它时 document.cookie 里没有 Civitai 的东西：先拦下来说清该在哪点。
    "if(!document.cookie){alert('这个书签要在大站页面上点：先打开 civitai.red 并登录，再点它');return;}" +
    "fetch('http://127.0.0.1:" +
    port +
    "/civitai-cookie',{method:'POST',headers:{'Content-Type':'text/plain'}," +
    "body:JSON.stringify({token:" +
    JSON.stringify(token) +
    ",cookie:document.cookie})})" +
    ".then(r=>r.json()).then(d=>alert(d.ok?'Pixiko：Cookie 已保存':'Pixiko：'+(d.error||'保存失败')))" +
    ".catch(e=>alert('Pixiko：保存失败 '+e));})()"
  );
}

export async function GET(request: Request): Promise<NextResponse> {
  const { settings } = started();
  const token = new URL(request.url).searchParams.get("token") ?? "";
  const console_ = webConsole();

  if (!console_.civitaiLinkUsable(token)) {
    return html(
      '<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">' +
        "<title>Pixiko · 链接已失效</title></head><body style=\"background:#0b1220;color:#e9eff8;" +
        'font:15px/1.6 sans-serif;padding:32px"><h1>链接已失效</h1>' +
        "<p>一次性登录链接已过期或用过。请回到网页控制台「系统 → Civitai 账号」重新生成。</p>" +
        "</body></html>",
      410,
    );
  }

  const status = console_.civitaiStatus();
  const host = String(status["host"] ?? "");
  const page = [
    '<!DOCTYPE html>',
    '<html lang="zh-CN"><head><meta charset="utf-8">',
    '<meta name="viewport" content="width=device-width, initial-scale=1">',
    "<title>Pixiko · Civitai 登录</title>",
    "<style>",
    'body{margin:0;background:#0b1220;color:#e9eff8;font:15px/1.6 -apple-system,"Segoe UI","Microsoft YaHei",sans-serif}',
    "main{max-width:760px;margin:0 auto;padding:28px 18px 60px}",
    "h1{font-size:20px;margin:0 0 6px}",
    "a{color:#5aa2ff}",
    ".card{background:rgba(23,33,51,.82);border:1px solid rgba(120,150,200,.18);border-radius:20px;padding:16px;margin:14px 0}",
    "textarea{width:100%;min-height:120px;background:rgba(9,15,27,.6);color:inherit;border:1px solid rgba(120,150,200,.18);border-radius:14px;padding:10px;font:13px/1.5 ui-monospace,Consolas,monospace}",
    "button{background:linear-gradient(135deg,#5aa2ff,#b98bff);color:#05101d;border:0;border-radius:14px;padding:11px 18px;font-weight:650;cursor:pointer}",
    "code{background:rgba(120,160,220,.14);padding:1px 5px;border-radius:6px}",
    ".muted{color:#8ea2bf;font-size:13px}",
    ".ok{color:#4fd7a8}.bad{color:#ff7d8a}",
    ".bookmarklet{display:inline-block;background:#17213a;border:1px solid rgba(120,150,200,.28);border-radius:12px;padding:8px 12px;color:#e9eff8;text-decoration:none;font-weight:600}",
    "</style></head><body><main>",
    "<h1>Civitai 登录（一次性链接）</h1>",
    `<p class="muted">当前站点：<code>${host}</code>　令牌 ${CIVITAI_LINK_MINUTES} 分钟内有效，用掉即作废。</p>`,
    '<div class="card">',
    "  <b>第一步：登录 Civitai</b>",
    `  <p><a href="${host}" target="_blank" rel="noreferrer">在新标签页打开 ${host} 并登录</a></p>`,
    '  <p class="muted">没有账号 Cookie 时，机器人只能用 <code>civitai.com</code> 搜索和下载，成人内容与部分模型不可见。</p>',
    "</div>",
    '<div class="card">',
    "  <b>第二步：把 Cookie 交回来（两种任选）</b>",
    "  <p>① 把下面这个按钮拖到书签栏（或右键「收藏」），<b>回到刚登录的 civitai 页面</b>再点它，就会自动回填：</p>",
    `  <p><a class="bookmarklet" href="${bookmarklet(token, settings.webPort())}">回填到 Pixiko</a></p>`,
    '  <p class="muted">书签小工具读取 <code>document.cookie</code> 发给本机机器人；在控制台这一页点会提示"先打开 civitai 页面"，那是正常的。</p>',
    "  <p>② 或者手动：在 Civitai 页按 F12 → Network → 任选一个请求 → 复制请求头里的 <code>cookie</code> 整段，粘到下面。</p>",
    '  <textarea id="cookie" placeholder="粘贴浏览器 Cookie 里 civitai 相关的那几条 name=value"></textarea>',
    '  <p><button id="save">保存 Cookie</button> <span id="result"></span></p>',
    "</div>",
    '<p class="muted">保存成功后可以直接关掉本页；机器人下次搜索/下载就会用这个账号。</p>',
    "<script>",
    `const TOKEN = ${JSON.stringify(token)};`,
    'const ENDPOINT = "/civitai-cookie";',
    "async function send(cookie) {",
    "  const result = document.getElementById('result');",
    "  result.textContent = '正在保存…'; result.className = '';",
    "  try {",
    "    const response = await fetch(ENDPOINT, { method: 'POST', headers: {'Content-Type': 'text/plain'},",
    "      body: JSON.stringify({ token: TOKEN, cookie }) });",
    "    const data = await response.json();",
    "    if (data.ok) { result.textContent = '已保存（' + (data.cookieHint || '') + '）'; result.className = 'ok'; }",
    "    else { result.textContent = data.error || '保存失败'; result.className = 'bad'; }",
    "  } catch (error) { result.textContent = '保存失败：' + error.message; result.className = 'bad'; }",
    "}",
    "document.getElementById('save').onclick = () => send(document.getElementById('cookie').value);",
    "if (location.hash.startsWith('#cookie=')) send(decodeURIComponent(location.hash.slice(8)));",
    "</script>",
    "</main></body></html>",
  ].join("\n");

  return html(page, 200);
}