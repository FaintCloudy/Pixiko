package cn.szu.bot.app;

import java.net.URI;

/**
 * 注入网页的那部分 JS 与令牌注入脚本。集中放在一个类里，方便对照 {@link PixikoBridge} 的 Java 侧签名。
 *
 * <p>三件事：
 * <ol>
 *   <li><b>令牌注入</b>：把 {@code localStorage['kotori-webui-token']} 写成用户配置的令牌，
 *       用户就不用再在网页锁屏里敲一次（键名见 webui/app.js 顶部的 {@code TOKEN_KEY}）；</li>
 *   <li><b>图片长按/右键</b>：监听 {@code contextmenu} 与 {@code touchstart}+550ms 长按，
 *       命中 {@code <img>} 就 {@code preventDefault()} 并调原生保存；</li>
 *   <li><b>幂等</b>：整段脚本带 {@code window.__pixikoNativeHooked} 标志，
 *       页面内导航/重复 onPageFinished 都不会把监听器叠成两层。</li>
 * </ol>
 *
 * <p><b>为什么要做「同源判断」</b>：桥方法只对配置的那台服务器开放，
 * 避免用户在网页里点开一个外链（比如图床、Civitai）后，那个站点的 JS 也能指挥 app 下载文件。
 */
public final class NativeHook {

    /** addJavascriptInterface 的注册名，网页侧 {@code window.PixikoNative}。 */
    public static final String INTERFACE_NAME = "PixikoNative";

    /** localStorage 里存网页令牌的键，必须与 webui/app.js 的 TOKEN_KEY 完全一致。 */
    public static final String TOKEN_KEY = "kotori-webui-token";

    /**
     * localStorage 里存「本机设备 scope」镜像的键，必须与 webui/m/app.js 的 {@code DEVICE_SCOPE_KEY} 完全一致。
     *
     * <p>刻意<b>不</b>复用共享的 {@code pixiko-scope}：那个键是完整控制台（{@code /}）在用，
     * 两套界面同源（同一台服务器的 localStorage 是同一个），共用键会让设备 scope 漏进控制台、
     * 或者控制台手改的 scope 覆盖掉设备身份。
     */
    public static final String DEVICE_SCOPE_KEY = "pixiko-device-scope";

    /** 长按判定阈值：与需求一致，550ms。 */
    private static final int LONG_PRESS_MS = 550;

    private NativeHook() { }

    /** 当前页面是否属于我们配置的那台服务器（决定要不要挂桥与注入）。 */
    public static boolean isTrustedHost(String base, String pageUrl) {
        if (base == null || pageUrl == null) return false;
        String baseHost = host(base);
        String pageHost = host(pageUrl);
        if (baseHost == null || pageHost == null) return false;
        return baseHost.equalsIgnoreCase(pageHost);
    }

    private static String host(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null) return null;
            int port = uri.getPort();
            return port > 0 ? uri.getHost() + ":" + port : uri.getHost();
        } catch (Exception error) {
            return null;
        }
    }

    /** 转义成可以安全塞进单引号 JS 字符串的字面量。 */
    private static String jsString(String text) {
        if (text == null) return "''";
        StringBuilder out = new StringBuilder(text.length() + 16);
        out.append('\'');
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '\\': out.append("\\\\"); break;
                case '\'': out.append("\\'"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\u2028': out.append("\\u2028"); break;
                case '\u2029': out.append("\\u2029"); break;
                case '<': out.append("\\u003c"); break;   // 防止需要内联进 HTML 时提前闭合 script
                default:
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch)); else out.append(ch);
            }
        }
        out.append('\'');
        return out.toString();
    }

    /**
     * 令牌注入脚本。写得「问心无愧」：值一样就什么都不做（不reload、不写localStorage）。
     * 调用方（MainActivity）负责比较返回值决定要不要 reload。
     */
    public static String tokenScript(String token) {
        return "(function(){try{var want=" + jsString(token) + ";"
                + "var k=" + jsString(TOKEN_KEY) + ";"
                + "var now=window.localStorage.getItem(k);"
                + "if(now===want){return 'same';}"
                + "window.localStorage.setItem(k,want);return 'written';"
                + "}catch(e){return 'error:'+e;}})()";
    }

    /** 清除登录状态：删掉 localStorage 里的令牌（网页下次加载就会回锁屏）。 */
    public static String clearTokenScript() {
        return "(function(){try{window.localStorage.removeItem(" + jsString(TOKEN_KEY) + ");return 'cleared';}"
                + "catch(e){return 'error:'+e;}})()";
    }

    /**
     * 设备 scope 注入：把本机的 scope（{@code dev-xxxxxxxxxxxx}）写进 {@code window.__PIXIKO_SCOPE}。
     *
     * <p>这是<b>备用通道</b>：主通道是页面地址上的查询串（{@code /m?scope=…}，见
     * {@link DeviceScope#pageUrl}），它在页面第一行脚本执行之前就已经在了，
     * 刷新 / 重建 / 深链接都不会丢。注入这一份的用处是"外壳越过地址栏直接说话"：
     * 万一页面被别的路径加载（或将来换成不便于改地址的加载方式），网页端仍拿得到设备 scope。
     *
     * <p>幂等、定向、不 reload：值已经一样就直接回 {@code 'same'}；
     * 不一样时写进 {@code window.__PIXIKO_SCOPE} <b>并且</b>顺手落一份到 localStorage 的镜像键
     * （键名必须与 {@code webui/m/app.js} 的 {@code DEVICE_SCOPE_KEY} 完全一致），
     * 好让"外壳只说一句话"也能把网页端叫醒。刻意不去碰共享的 {@code pixiko-scope}：
     * 那个键是完整控制台（{@code /}）的地盘，两边不能互相污染。
     */
    public static String deviceScopeScript(String scope) {
        return "(function(){try{var want=" + jsString(scope) + ";"
                + "var k=" + jsString(DEVICE_SCOPE_KEY) + ";"
                + "var now=window.__PIXIKO_SCOPE;"
                + "if(now===want){return 'same';}"
                + "window.__PIXIKO_SCOPE=want;"
                + "try{if(!window.localStorage.getItem(k)){window.localStorage.setItem(k,want);}}catch(e2){}"
                + "return 'written';"
                + "}catch(e){return 'error:'+e;}})()";
    }

    /** 判断「清空了没有」用的读值脚本。 */
    public static String readTokenScript() {
        return "(function(){try{return String(window.localStorage.getItem(" + jsString(TOKEN_KEY) + ")||'');}"
                + "catch(e){return '';}})()";
    }

    /**
     * 图片长按/右键注入脚本（幂等）。整段是 IIFE，返回一句状态字符串，方便在 logcat 里确认注入成功。
     */
    public static String hookScript() {
        return "(function(){"
                + "if(window.__pixikoNativeHooked){return 'already';}"
                + "window.__pixikoNativeHooked=true;"
                + "var bridge=window." + INTERFACE_NAME + ";"
                + "if(!bridge){window.__pixikoNativeHooked=false;return 'no-bridge';}"
                + "try{window.__pixikoNativeVersion=(bridge.version&&bridge.version())||'';}catch(e){}"
                + "var HOLD=" + LONG_PRESS_MS + ";"
                + "function usable(img){"
                + "var src=img&&(img.currentSrc||img.src||'');"
                + "if(!src||src.indexOf('data:')===0||src.indexOf('blob:')===0){return '';}"
                + "var rect=img.getBoundingClientRect();"
                + "if(rect.width<40||rect.height<40){return '';}"
                + "return src;}"
                + "function alt(img){try{return String(img.getAttribute('alt')||img.getAttribute('title')||'');}catch(e){return '';}}"
                // 原生浮层菜单（由 MainActivity.onShowFileChooser 之外的另一个回调弹出）：
                + "function menu(src,label){try{bridge.showImageMenu(String(src),String(label||''));}catch(e){}}"
                // 右键（桌面/带鼠标的设备，以及部分浏览器的长按会派发 contextmenu）
                + "document.addEventListener('contextmenu',function(event){"
                + "var img=event.target&&event.target.tagName==='IMG'?event.target:null;"
                + "var src=usable(img);if(!src){return;}"
                + "event.preventDefault();event.stopPropagation();"
                + "menu(src,alt(img));},true);"
                // 触屏长按：550ms 不动才算长按；动了或者提前抬手就取消。
                + "var timer=null,startX=0,startY=0,target=null;"
                + "function clear(){if(timer){clearTimeout(timer);timer=null;}target=null;}"
                + "document.addEventListener('touchstart',function(event){"
                + "if(event.touches.length!==1){clear();return;}"
                + "var img=event.target&&event.target.tagName==='IMG'?event.target:null;"
                + "if(!usable(img)){clear();return;}"
                + "var touch=event.touches[0];startX=touch.clientX;startY=touch.clientY;target=img;"
                + "clearTimeout(timer);"
                + "timer=setTimeout(function(){"
                + "var src=usable(target);timer=null;if(!src){return;}"
                // 这里不能 preventDefault（已经晚了），靠 contextmenu 的 preventDefault 兜住默认菜单；
                // 真机表现是「图片可点开的查看器仍然工作，长按则弹原生菜单」。
                + "menu(src,alt(target));},HOLD);},true);"
                + "document.addEventListener('touchmove',function(event){"
                + "if(!timer||event.touches.length!==1){return;}"
                + "var touch=event.touches[0];"
                + "if(Math.abs(touch.clientX-startX)>10||Math.abs(touch.clientY-startY)>10){clear();}},true);"
                + "document.addEventListener('touchend',clear,true);"
                + "document.addEventListener('touchcancel',clear,true);"
                + "return 'hooked';})()";
    }
}
