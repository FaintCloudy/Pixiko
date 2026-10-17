package cn.szu.bot.testsupport;

import cn.szu.bot.chat.DeepSeekPrompts;
import cn.szu.bot.civitai.CivitaiClient;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 run 的假服务集合：假 OneBot（WebSocket）、假 SD（HTTP）、假 DeepSeek（HTTP + transport）、
 * 假 Civitai（transport + 本机 HTTP）。
 *
 * <p>所有真实套接字**只绑 {@code 127.0.0.1}**、端口绑 0 后读实际值（第 4、5、6 条）；每个实例独立，
 * 端口/目录/静态状态都不共享（第 7 条）。注入测试配置时用 {@link #config()}，它把四个假服务的地&#22336;
 * 写进本次 run 的 {@code config.json}。
 *
 * <p>Civitai 特殊一点：{@code CivitaiClient} 要求 {@code base_url} 是 https 且端口只能是 443，所以配置里
 * 保留 {@code https://civitai.com} 这个**不会被真的访问**的地址，实际流量由注入的 transport 转到本机
 * 假站点（{@link #civitaiTransport()}）。这样既有真实的本机套接字，又不会往 civitai.com 发一个字节。
 */
public final class TestRuntime implements AutoCloseable {

    /** 假 DeepSeek 的固定回答（JSON 里带一个 content 字段，够验证"请求确实打到了假服务"）。 */
    public static final String DEEPSEEK_ANSWER = "1girl, solo, offlinetest_marker";

    private final TestEnvironment env;
    private final FakeOneBot oneBot;
    private final FakeHttpService sd;
    private final FakeHttpService deepSeek;
    private final FakeHttpService civitai;
    private final Map<String, Integer> ports = new LinkedHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private boolean closed;

    public TestRuntime(TestEnvironment env) throws IOException {
        this.env = env;
        this.oneBot = env.register(new FakeOneBot());
        this.sd = env.register(new FakeHttpService("假SD"));
        this.deepSeek = env.register(new FakeHttpService("假DeepSeek"));
        this.civitai = env.register(new FakeHttpService("假Civitai"));

        ports.put("onebot-ws", oneBot.port());
        ports.put("sd-http", sd.port());
        ports.put("deepseek-http", deepSeek.port());
        ports.put("civitai-http", civitai.port());
        for (Map.Entry<String, Integer> entry : ports.entrySet()) env.claimPort(entry.getKey(), entry.getValue());

        sd.route("/sdapi/v1/sd-models", FakeHttpService.Reply.json(
                "[{\"title\":\"fake-model.safetensors\",\"model_name\":\"fake-model\",\"hash\":\"0000000000\","
                        + "\"sha256\":\"0000000000000000000000000000000000000000000000000000000000000000\","
                        + "\"filename\":\"fake-model.safetensors\"}]"));
        sd.route("/sdapi/v1/prompt-styles", FakeHttpService.Reply.json(
                "[{\"name\":\"假样式\",\"prompt\":\"masterpiece, best quality\",\"negative_prompt\":\"lowres\"}]"));
        sd.route("/sdapi/v1/options", FakeHttpService.Reply.json(
                "{\"sd_model_checkpoint\":\"fake-model.safetensors\"}"));
        sd.route("/sdapi/v1/loras", FakeHttpService.Reply.json("[]"));
        sd.route("/sdapi/v1/vaes", FakeHttpService.Reply.json("[]"));
        sd.route("/sdapi/v1/progress", FakeHttpService.Reply.json(
                "{\"progress\":0.0,\"eta_relative\":0.0,\"state\":{},\"current_image\":null,\"textinfo\":\"\"}"));
        sd.route("/sdapi/v1/txt2img", FakeHttpService.Reply.json("{\"images\":[],\"info\":\"{}\"}"));
        sd.route("/internal/ping", FakeHttpService.Reply.json("{\"status\":\"ok\"}"));

        deepSeek.route("/chat/completions", FakeHttpService.Reply.json(
                "{\"id\":\"fake\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,"
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"" + DEEPSEEK_ANSWER + "\"},"
                        + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"));
        civitai.route("/api/v1/models/", FakeHttpService.Reply.json(
                "{\"id\":10,\"name\":\"假 LoRA\",\"type\":\"LORA\",\"modelVersions\":[]}"));
        civitai.route("/api/v1/models", FakeHttpService.Reply.json("{\"items\":[],\"metadata\":{}}"));
    }

    public TestEnvironment environment() { return env; }
    public FakeOneBot oneBot() { return oneBot; }
    public FakeHttpService sd() { return sd; }
    public FakeHttpService deepSeek() { return deepSeek; }
    public FakeHttpService civitai() { return civitai; }

    /** 每个假服务的实际端口（交付报告要打印的就是它）。 */
    public Map<String, Integer> ports() { return new LinkedHashMap<>(ports); }

    /** 打印假服务端口。 */
    public void printPorts(String title) {
        StringBuilder line = new StringBuilder("ISOLATION PORTS [" + title + "] run-id=" + env.runId() + "：");
        ports.forEach((service, port) -> line.append(' ').append(service).append("=127.0.0.1:").append(port));
        System.out.println(line);
    }

    /** 假 WebUI 端口：属性里给了正数就用它，{@code 0}（默认）交给操作系统分配。 */
    public int webUiPort() { return TestEnvironment.requestedPort(TestEnvironment.PROP_WEB_PORT); }

    /** 假 OneBot 端口（同上）。 */
    public int oneBotPort() { return TestEnvironment.requestedPort(TestEnvironment.PROP_ONEBOT_PORT); }

    /** 假 SD 端口（同上；0 表示用本机临时分配的那个）。 */
    public int sdPort() { return TestEnvironment.requestedPort(TestEnvironment.PROP_SD_PORT); }

    /**
     * 生成并落盘本次 run 的测试配置：QQ 指假 OneBot、SD 指假 SD、两条 DeepSeek 通道指假 DeepSeek、
     * Civitai 只留 https 校验地址（真实流量走 transport）、WebUI 只绑 127.0.0.1 且端口动态。
     */
    public JsonObject config() throws IOException {
        JsonObject config = env.baseConfig();
        JsonObject qq = config.getAsJsonObject("qq");
        qq.addProperty("ws_url", oneBot.url());
        JsonObject sdSection = config.getAsJsonObject("sd");
        sdSection.addProperty("base_url", sd.baseUrl());
        JsonObject progen = config.getAsJsonObject("progen");
        progen.addProperty("api_base", deepSeek.baseUrl());
        JsonObject chatApi = config.getAsJsonObject("chat_api");
        chatApi.addProperty("api_base", deepSeek.baseUrl());
        JsonObject webui = config.getAsJsonObject("webui");
        webui.addProperty("port", 0);
        JsonObject appUpdate = new JsonObject();
        appUpdate.addProperty("remote_check", false);   // 测试绝不问 GitHub
        appUpdate.addProperty("channel", "dev");
        webui.add("app_update", appUpdate);
        env.writeConfig(config);
        return config;
    }

    /** 假 DeepSeek transport：把请求真的打到本机假服务上（不注入的话客户端会去 api.deepseek.com）。 */
    public DeepSeekPrompts.Transport deepSeekTransport() {
        return (body, key, timeout) -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(deepSeek.baseUrl() + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .timeout(timeout == null ? Duration.ofSeconds(10) : timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
            if (key != null && !key.isBlank()) builder.header("Authorization", "Bearer " + key);
            HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new DeepSeekPrompts.Response(response.statusCode(), response.body());
        };
    }

    /**
     * 假 Civitai transport：URI 是 https://civitai.com/...（客户端只接受 https），实际请求转到本机
     * {@code 127.0.0.1:<假 Civitai 端口>}，一个字节都不会出网。
     */
    public CivitaiClient.Transport civitaiTransport() {
        return (uri, headers, timeout) -> {
            URI local = URI.create(civitai.baseUrl() + uri.getRawPath()
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
            env.claimPort("civitai-transport", civitai.port());
            HttpRequest.Builder builder = HttpRequest.newBuilder(local)
                    .timeout(timeout == null ? Duration.ofSeconds(10) : timeout).GET();
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : response.headers().map().entrySet()) {
                if (entry.getKey() == null) continue;
                responseHeaders.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
            return new CivitaiClient.Response(response.statusCode(), responseHeaders,
                    new ByteArrayInputStream(response.body()));
        };
    }

    /** 假 DNS：假站点解析成一个公网地址（CivitaiClient 会拒绝私有地址），但请求永远不会真的出网。 */
    public CivitaiClient.Resolver civitaiResolver() {
        return host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")};
    }

    /** 反序关掉四个假服务（{@link TestEnvironment#cleanup()} 也会兜底）。 */
    @Override public void close() {
        if (closed) return;
        closed = true;
        for (AutoCloseable resource : List.<AutoCloseable>of(civitai, deepSeek, sd, oneBot)) {
            try { resource.close(); }
            catch (Exception error) { System.err.println("警告：假服务关闭失败：" + error); }
        }
    }
}
