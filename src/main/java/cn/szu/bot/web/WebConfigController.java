package cn.szu.bot.web;

import cn.szu.bot.Bot;
import cn.szu.bot.Json;
import cn.szu.bot.Log;
import cn.szu.bot.Settings;
import cn.szu.bot.WebSetup;
import com.google.gson.JsonObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 网页端配置：配置页 {@code /setup} 与它用的三个接口。
 *
 * <ul>
 *   <li>{@code GET/POST /api/config/state} — 当前配置值 + 掩码密钥 + 还缺什么；</li>
 *   <li>{@code POST /api/config/apply} — 只改传来的字段（含两条 DeepSeek 通道的地址与密钥）；</li>
 *   <li>{@code POST /api/config/test} — 用当前地址与密钥发一条最小请求，验证通道能不能用。</li>
 * </ul>
 *
 * <p>首次配置期间（缺 DeepSeek 密钥）这三个接口<b>本机免令牌</b>，否则第一次运行会卡在"要先有令牌才能配置、
 * 要先配置才有令牌"上；判断与放行在 {@link WebAuthFilter}。配置完成后它们和别的接口一样要令牌。
 */
@RestController
public class WebConfigController {

    private final Settings settings;
    private final Path webRoot;

    public WebConfigController(Settings settings) {
        this.settings = settings;
        Path configured = settings.root.toAbsolutePath().normalize().resolve("webui");
        this.webRoot = Files.isDirectory(configured) ? configured : Path.of("webui").toAbsolutePath().normalize();
    }

    @RequestMapping(value = "/api/config/state", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<?> state() {
        Log.markWeb();
        try {
            return WebJson.ok(WebSetup.state(settings));
        } catch (Exception error) {
            return WebJson.of(HttpStatus.INTERNAL_SERVER_ERROR, WebJson.error(Bot.error(error)));
        } finally {
            Log.clearWeb();
        }
    }

    @RequestMapping(value = "/api/config/apply", method = RequestMethod.POST)
    public ResponseEntity<?> apply(@RequestBody(required = false) String raw) {
        Log.markWeb();
        try {
            JsonObject body = WebPages.parseBody(raw);
            JsonObject result = WebSetup.apply(settings, body);
            Log.info("网页端已更新配置：" + Json.str(result, "notice", ""));
            return WebJson.ok(result);
        } catch (IllegalArgumentException error) {
            return WebJson.of(HttpStatus.BAD_REQUEST, WebJson.error(error.getMessage()));
        } catch (Exception error) {
            Log.warn("网页端配置保存失败：" + Bot.error(error));
            return WebJson.of(HttpStatus.INTERNAL_SERVER_ERROR, WebJson.error(Bot.error(error)));
        } finally {
            Log.clearWeb();
        }
    }

    @RequestMapping(value = "/api/config/test", method = RequestMethod.POST)
    public ResponseEntity<?> test(@RequestBody(required = false) String raw) {
        Log.markWeb();
        try {
            JsonObject body = WebPages.parseBody(raw);
            return WebJson.ok(WebSetup.test(settings, Json.str(body, "channel", "image")));
        } catch (IllegalArgumentException error) {
            return WebJson.of(HttpStatus.BAD_REQUEST, WebJson.error(error.getMessage()));
        } catch (Exception error) {
            Log.warn("网页端通道测试失败：" + Bot.error(error));
            return WebJson.of(HttpStatus.INTERNAL_SERVER_ERROR, WebJson.error(Bot.error(error)));
        } finally {
            Log.clearWeb();
        }
    }

    /** 配置页：自带样式与脚本的单个 HTML，直接读 {@code webui/setup.html}（改完刷新即可，不用重新打包）。 */
    @RequestMapping(value = "/setup", method = RequestMethod.GET)
    public ResponseEntity<byte[]> page() {
        try {
            Path target = webRoot.resolve("setup.html").normalize();
            if (!target.startsWith(webRoot) || !Files.isRegularFile(target))
                return WebJson.bytes(HttpStatus.NOT_FOUND,
                        "配置页缺失：webui/setup.html 不存在。请用 run.bat --setup 在命令行配置。"
                                .getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8", "no-store");
            return WebJson.bytes(HttpStatus.OK, Files.readAllBytes(target), "text/html; charset=utf-8", "no-store");
        } catch (Exception error) {
            Log.warn("配置页读取失败：" + Bot.error(error));
            return WebJson.bytes(HttpStatus.INTERNAL_SERVER_ERROR, ("配置页读取失败：" + Bot.error(error))
                    .getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8", "no-store");
        }
    }
}
