package cn.szu.bot.web;

import cn.szu.bot.Json;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 {@code webui/index.html} 拆成「公共外壳 + 每个栏目自己的一块」，按路径拼出该栏目的页面。
 *
 * <p>页面结构只维护一份：{@code index.html} 里每个面板用
 * {@code <!--#panel:gen--> … <!--#/panel:gen-->} 标出边界，其余部分（顶栏、页签、页脚、
 * 弹窗与图片查看器、脚本）就是外壳。请求 {@code /gen} 时只把 gen 这一块放回外壳，
 * 于是每条路径的 DOM 里都不含别的栏目，前端也只需要加载这个栏目要用的数据。
 */
final class WebPages {

    private static final String LAYOUT = "index.html";

    private WebPages() { }

    /** 栏目 id → （面板 HTML 块）。 */
    static Map<String, String> blocks(Path webRoot) throws IOException {
        String html = read(webRoot);
        Map<String, String> result = new LinkedHashMap<>();
        int at = 0;
        while (true) {
            int open = html.indexOf("<!--#panel:", at);
            if (open < 0) break;
            int nameEnd = html.indexOf("-->", open);
            String panel = html.substring(open + "<!--#panel:".length(), nameEnd).strip();
            String close = "<!--#/panel:" + panel + "-->";
            int closeAt = html.indexOf(close, nameEnd);
            if (closeAt < 0) break;
            result.put(panel, html.substring(nameEnd + 3, closeAt).strip());
            at = closeAt + close.length();
        }
        return result;
    }

    /** 该栏目的整页 HTML：外壳去掉所有面板块 → 放回请求的这一块 → 标出当前页签 → 注入页面标记。 */
    static String render(Path webRoot, String panel) throws IOException {
        String html = read(webRoot);
        String block = blocks(webRoot).get(panel);
        if (block == null) block = "";
        StringBuilder shell = new StringBuilder();
        int at = 0;
        while (true) {
            int open = html.indexOf("<!--#panel:", at);
            if (open < 0) { shell.append(html, at, html.length()); break; }
            int nameEnd = html.indexOf("-->", open);
            if (nameEnd < 0) { shell.append(html, at, html.length()); break; }
            String name = html.substring(open + "<!--#panel:".length(), nameEnd).strip();
            int closeAt = html.indexOf("<!--#/panel:" + name + "-->", nameEnd);
            if (closeAt < 0) { shell.append(html, at, html.length()); break; }
            shell.append(html, at, open);
            if (name.equals(panel)) shell.append(block);   // 本栏目放回原位
            at = closeAt + ("<!--#/panel:" + name + "-->").length();
        }
        String page = shell.toString();
        // 当前页签高亮（页签是 <a href>，服务端决定谁是 active）
        page = page.replace("<a class=\"tab\" data-tab=\"" + panel + "\"",
                "<a class=\"tab active\" data-tab=\"" + panel + "\"");
        // 这一块面板也要是 active：别的栏目已经不在了，但 .panel 默认 display:none，
        // 没有 active 的话整页元素尺寸都是 0（终端那种按高度算的布局会直接塌掉）。
        // 注意面板的 class 不只有 "panel"（还有 panel terminal-panel 之类），不能用固定串替换。
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("class=\"panel([^\"]*)\" id=\"panel-" + java.util.regex.Pattern.quote(panel) + "\"")
                .matcher(page);
        StringBuffer withActive = new StringBuffer();
        if (matcher.find()) {
            String extra = matcher.group(1).replace("active", "").strip();
            matcher.appendReplacement(withActive, java.util.regex.Matcher.quoteReplacement(
                    "class=\"panel" + (extra.isEmpty() ? "" : " " + extra) + " active\" id=\"panel-" + panel + "\""));
        }
        matcher.appendTail(withActive);
        page = withActive.toString();
        // 页面标记要在 app.js 之前：前端据此只加载本栏目的数据
        page = page.replace("<script src=\"/app.js\"></script>",
                "<script>window.PIXIKO_PAGE = " + Json.GSON.toJson(panel) + ";</script>\n<script src=\"/app.js\"></script>");
        if (!page.contains("window.PIXIKO_PAGE")) {
            // 兜底：脚本标签形式变了也要能标出页面
            page = page.replace("</head>", "<script>window.PIXIKO_PAGE = " + Json.GSON.toJson(panel) + ";</script>\n</head>");
        }
        return page;
    }

    private static String read(Path webRoot) throws IOException {
        return Files.readString(webRoot.resolve(LAYOUT), StandardCharsets.UTF_8);
    }

    /** 请求体解析（控制器与 Cookie 回填共用）：空体当空对象，非法 JSON 明确报错。 */
    static JsonObject parseBody(String raw) {
        if (raw == null) return new JsonObject();
        if (raw.getBytes(StandardCharsets.UTF_8).length > 4 * 1024 * 1024) throw new IllegalArgumentException("请求体过大。");
        String text = raw.strip();
        if (text.isEmpty()) return new JsonObject();
        try { return Json.parse(text); }
        catch (Exception error) { throw new IllegalArgumentException("请求体不是合法 JSON。"); }
    }
}
