package cn.szu.bot;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import cn.szu.bot.sd.SdClient;
public final class PromptChangesTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("prompt-changes-");
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("data/prompt-tags.txt"), "blue_eyes\nblack_hair\n");
        AtomicReference<String> positive = new AtomicReference<>("cat,, (blue_eyes:1.2), dog,");
        AtomicInteger puts = new AtomicInteger(), revision = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sdapi/v1/prompt-styles", e -> { byte[] data = "[]".getBytes(); e.sendResponseHeaders(200,data.length); e.getResponseBody().write(data); e.close(); });
        server.createContext("/pixiko-bridge/v1/prompts", e -> {
            if (e.getRequestMethod().equals("PUT")) {
                JsonObject input = Json.parse(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                positive.set(input.get("positive").getAsString()); puts.incrementAndGet(); revision.incrementAndGet();
            }
            JsonObject state = new JsonObject(); state.addProperty("positive", positive.get()); state.addProperty("negative", "bad");
            state.addProperty("revision", revision.get()); state.addProperty("source", "webui-live"); state.addProperty("live", true);
            byte[] data = state.toString().getBytes(StandardCharsets.UTF_8); e.sendResponseHeaders(200,data.length); e.getResponseBody().write(data); e.close();
        });
        server.start();
        try {
            JsonObject cfg = new JsonObject(); cfg.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort());
            SdClient client = new SdClient(root, cfg);
            var removed = client.changeDetailed(false, "remove", "blue ey");
            assert removed.changed().equals(List.of("(blue_eyes:1.2)")); assert removed.prompts().positive().equals("cat, dog");
            var added = client.changeDetailed(false, "add", ",black hai,,");
            assert added.changed().equals(List.of("black_hair")); assert added.prompts().positive().equals("cat, dog, black_hair");
            assert client.changeDetailed(false, "add", "black hair").unchanged().equals(List.of("black_hair"));
            positive.set("blue eyes, blue hair"); int before = puts.get();
            try { client.changeDetailed(false, "remove", "blue"); throw new AssertionError(); }
            catch (IllegalArgumentException expected) { assert expected.getMessage().contains("多个候选"); }
            assert puts.get() == before : "ambiguous edit must not write bridge";
            assert client.prompts().negative().equals("bad");
            assert new SdClient(root, cfg).prompts().positive().equals(positive.get());
            System.out.println("PromptChangesTest PASS: dictionary completion, exact feedback, comma repair, ambiguity causes no write, persistence.");
        } finally { server.stop(0); }
    }
}
