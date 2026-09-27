package cn.szu.bot;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import cn.szu.bot.civitai.CivitaiClient;
import cn.szu.bot.sd.SdClient;
public final class CivitaiQueryTest {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("live")) {
            Settings settings = new Settings(Path.of(System.getProperty("bot.home", ".")));
            var results = new CivitaiClient(settings.root, Json.obj(settings.snapshot(), "civitai")).query("Natsume Ai");
            if (results.isEmpty()) throw new AssertionError("No live results");
            System.out.println("Live Civitai search OK: " + results.size() + " results, " + results.stream().filter(r -> !r.cover().isEmpty()).count() + " covers."); return;
        }
        Path root = Files.createTempDirectory(Path.of(System.getProperty("bot.test.work", "work")), "query-test-");
        JsonObject config = new JsonObject(); config.addProperty("lora_dir", root.resolve("loras").toString());
        // Production defaults to the civitai.red mirror; this fixture asserts the civitai.com API shape.
        config.addProperty("base_url", "https://civitai.com");
        AtomicReference<URI> requested = new AtomicReference<>();
        String payload = """
            {"items":[{"id":12,"name":"example","type":"LORA","modelVersions":[{"id":34,"baseModel":"NoobAI","images":[{"url":"http://127.0.0.1/private"},{"url":"https://image.civitai.com/test.png"}]}]},
            {"id":99,"name":"checkpoint","type":"Checkpoint","modelVersions":[{"id":100}]},
            {"id":13,"name":"no cover","type":"LORA","modelVersions":[{"id":35}]}]}
            """;
        CivitaiClient client = new CivitaiClient(root, config, (uri, headers, timeout) -> {
            requested.set(uri); return new CivitaiClient.Response(200, Map.of(), new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)));
        }, host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")}, "");
        var results = client.query("中文 & name");
        assert results.size() == 2;
        assert results.get(0).cover().equals("https://image.civitai.com/test.png");
        assert results.get(1).cover().isEmpty();
        assert results.get(0).url().equals("https://civitai.com/models/12?modelVersionId=34");
        assert requested.get().getRawQuery().contains("types=LORA&limit=10&query=");
        assert requested.get().getRawQuery().contains("%26");
        JsonObject cfg = new JsonObject(); cfg.add("civitai", config); Json.atomicWrite(root.resolve("config.json"), cfg);
        Settings settings = new Settings(root); assert settings.autoGet();
        AtomicReference<String> downloaded = new AtomicReference<>(); CountDownLatch started = new CountDownLatch(1);
        List<String> replies = new CopyOnWriteArrayList<>();
        try (Bot bot = new Bot(settings, new SdClient(root, new JsonObject()), (event, segments) -> {
            replies.add(Bot.messageText(segments)); return CompletableFuture.completedFuture(null);
        }, (url, stage, meter) -> { downloaded.set(url); started.countDown(); throw new IOException("test download intercepted"); })) {
            var field = Bot.class.getDeclaredField("loraSearches"); field.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String,List<CivitaiClient.SearchResult>> searches = (Map<String,List<CivitaiClient.SearchResult>>) field.get(bot);
            searches.put("1:group:2:3", results);
            JsonObject e = Json.parse("{\"self_id\":1,\"message_type\":\"group\",\"group_id\":2,\"user_id\":4,\"raw_message\":\".lora download #1\"}");
            e.addProperty("post_type", "message"); bot.accept(e);
            assert downloaded.get() == null; assert replies.stream().anyMatch(t -> t.contains("编号无效"));
            e.addProperty("user_id",3); bot.accept(e); assert started.await(5, TimeUnit.SECONDS);
            assert downloaded.get().equals(results.get(0).url());
        }
        System.out.println("CivitaiQueryTest PASS: filtering, cover validation, query encoding, numbering isolation, exact version download, default auto-get.");
    }
}
