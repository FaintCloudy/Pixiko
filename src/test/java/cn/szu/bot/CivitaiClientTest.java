package cn.szu.bot;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import cn.szu.bot.civitai.CivitaiClient;

/** No real HTTP or DNS: all metadata, redirects, streams and resolvers are injected. */
public final class CivitaiClientTest {
    private static int assertions;
    private static final String API = "https://civitai.com";
    private static final String CDN = "https://civitai-delivery-worker-prod.5ac0637cfd0766c97916cefa3764fbdf.r2.cloudflarestorage.com/model/test?X-Amz-Signature=signed-secret";

    public static void main(String[] args) throws Exception {
        successfulDownloadAndReuse();
        originalFilenames();
        linksVersionAndFileSelection();
        invalidLinksAndModels();
        failedDownloadsCleanUp();
        safetensorsStructureChecks();
        existingAndConcurrentFilesNeverOverwritten();
        redirectsDnsAndTokenIsolation();
        proxyPolicy();
        deadlineInterruptsBlockedBody();
        previewPickingAndNaming();
        showcasePromptsFromA1111AndComfyUI();
        System.out.println("CivitaiClientTest: " + assertions + " assertions passed.");
    }

    /**
     * 展示图提示词：A1111 直接给 prompt；ComfyUI 只把提示词放在工作流里，必须能把它们挖出来，
     * 否则整个模型的展示图都会被判成"没有提示词"而跳过。
     */
    private static void showcasePromptsFromA1111AndComfyUI() {
        // A1111：老样子
        JsonObject a1111meta = new JsonObject();
        a1111meta.addProperty("prompt", "a1111 positive");
        a1111meta.addProperty("negativePrompt", "a1111 negative");
        var a1111 = CivitaiClient.showcasePrompts(imageWith(a1111meta));
        check(a1111.size() == 1 && a1111.get(0).positive().equals("a1111 positive")
                        && a1111.get(0).negative().equals("a1111 negative") && a1111.get(0).skippedReason().isEmpty(),
                "A1111 的 prompt/negativePrompt 照旧读得到");

        // 标准 ComfyUI：默认工作流把两个文本编码节点命名为 Positive / Negative
        JsonObject standard = imageWith(comfyMeta(comfyGraphOf(
                comfyNode("CLIPTextEncode", "Positive", "text", "comfy positive, masterpiece"),
                comfyNode("CLIPTextEncode", "Negative", "text", "comfy negative"))));
        var comfy = CivitaiClient.showcasePrompts(standard);
        check(comfy.size() == 1 && comfy.get(0).positive().equals("comfy positive, masterpiece")
                        && comfy.get(0).negative().equals("comfy negative") && comfy.get(0).skippedReason().isEmpty(),
                "标准 ComfyUI 工作流（Positive/Negative 节点）也能读出正反向：" + comfy);

        // 中文插件：正向在名为 positive 的输入里，负面在标题写着"负面"的节点里，另有 temp_str 这种长 JSON
        JsonObject editor = comfyNode("WeiLinPromptUIWithoutLora", "WeiLin 提示词编辑器", "positive", "1girl, silver hair, detailed");
        editor.getAsJsonObject("inputs").addProperty("temp_str", "[{\"id\":\"token_1888\",\"text\":\"noise\"}]");
        var chinese = CivitaiClient.showcasePrompts(imageWith(comfyMeta(comfyGraphOf(
                editor, comfyNode("ZML_TextInput", "负面", "文本", "worst quality, blurry")))));
        check(chinese.size() == 1 && chinese.get(0).positive().equals("1girl, silver hair, detailed")
                        && chinese.get(0).negative().equals("worst quality, blurry") && chinese.get(0).skippedReason().isEmpty(),
                "中文 ComfyUI 插件（positive 输入 + 标题「负面」）也能读出来：" + chinese);
        check(chinese.get(0).positive().indexOf("token_1888") < 0, "插件里的 temp_str 不会被误当成提示词");

        // 文本放在 PrimitiveStringMultiline 的 value 里，旁边还挂着一条"给模型的系统提示词"——
        // 那条通常比画面提示词更长，按长度挑必然挑错。
        var primitive = CivitaiClient.showcasePrompts(imageWith(comfyMeta(comfyGraphOf(
                comfyNode("PrimitiveStringMultiline", "Text String (System Prompt)", "value", "You are an expert prompt engineer for text-to-image models."),
                comfyNode("PrimitiveStringMultiline", "Text String (User Prompt)", "value", "1girl, blue hair, maid outfit"),
                comfyNode("PrimitiveStringMultiline", "Text String (LoRA Trigger Word)", "value", "deepseek_whale_girl")))));
        check(primitive.size() == 1 && primitive.get(0).positive().equals("1girl, blue hair, maid outfit")
                        && primitive.get(0).skippedReason().isEmpty(),
                "PrimitiveString 工作流读得出提示词，且不会把系统提示词当画面提示词：" + primitive);

        // Civitai 会写出非法的 "workflow": undefined，容错后仍要能解析
        JsonObject brokenMeta = new JsonObject();
        brokenMeta.addProperty("comfy", comfyGraphOf(
                comfyNode("CLIPTextEncode", "Positive", "text", "tolerated positive")).toString()
                .replaceFirst("\\}$", ", \"workflow\": undefined}"));
        JsonObject broken = imageWith(brokenMeta);
        var tolerated = CivitaiClient.showcasePrompts(broken);
        check(tolerated.size() == 1 && tolerated.get(0).positive().equals("tolerated positive"),
                "comfy 里出现非法的 undefined 时仍能读出提示词：" + tolerated);

        // 真的什么都没有（meta 为空）：保持跳过，但理由要说清查过哪里
        var empty = CivitaiClient.showcasePrompts(imageWith(new JsonObject()));
        check(empty.size() == 1 && !empty.get(0).skippedReason().isEmpty()
                        && empty.get(0).skippedReason().contains("ComfyUI"),
                "确实没有提示词时才跳过，并说明 A1111 与 ComfyUI 都查过：" + empty.get(0).skippedReason());

        // meta 整个缺失也不能炸
        var missing = CivitaiClient.showcasePrompts(imageWith(null));
        check(missing.size() == 1 && !missing.get(0).skippedReason().isEmpty(), "meta 缺失时按跳过处理，不抛异常");
    }

    /** 一个只含单张展示图的版本对象。 */
    private static JsonObject imageWith(JsonObject meta) {
        JsonObject image = new JsonObject();
        if (meta != null) image.add("meta", meta);
        JsonArray images = new JsonArray();
        images.add(image);
        JsonObject version = new JsonObject();
        version.add("images", images);
        return version;
    }

    /** Civitai 的 meta.comfy 是一个"装 JSON 的字符串"。 */
    private static JsonObject comfyMeta(JsonObject graph) {
        JsonObject meta = new JsonObject();
        meta.addProperty("comfy", graph.toString());
        return meta;
    }

    /** ComfyUI 工作流的节点表：{"prompt": {"1": 节点…}}。 */
    private static JsonObject comfyGraphOf(JsonObject... nodes) {
        JsonObject table = new JsonObject();
        for (int i = 0; i < nodes.length; i++) table.add(String.valueOf(i + 1), nodes[i]);
        JsonObject graph = new JsonObject();
        graph.add("prompt", table);
        return graph;
    }

    private static JsonObject comfyNode(String type, String title, String inputKey, String text) {
        JsonObject node = new JsonObject();
        node.addProperty("class_type", type);
        JsonObject meta = new JsonObject();
        meta.addProperty("title", title);
        node.add("_meta", meta);
        JsonObject inputs = new JsonObject();
        inputs.addProperty(inputKey, text);
        node.add("inputs", inputs);
        return node;
    }

    /** 展示图：挑哪一张、存成什么名字。纯函数，不碰网络。 */
    private static void previewPickingAndNaming() throws Exception {
        Path lora = Path.of("/models/Lora/My Lora.safetensors");
        check(CivitaiClient.previewPath(lora).getFileName().toString().equals("My Lora.preview.png"),
                "preview saved next to the model as <name>.preview.png");
        check(CivitaiClient.previewPath(lora).getParent().equals(lora.getParent()), "preview stays in the same directory");
        check(CivitaiClient.previewPath(Path.of("/models/Lora/NoExt")).getFileName().toString().equals("NoExt.preview.png"),
                "a name without .safetensors still gets a sane preview name");

        check(CivitaiClient.previewUrl(new JsonObject()).isEmpty(), "no images means no preview URL");
        check(CivitaiClient.previewUrl(Json.parse("{\"images\":[{\"url\":\"https://evil.example/x.png\"}]}")).isEmpty(),
                "non-Civitai hosts are refused (not an open image proxy)");
        check(CivitaiClient.previewUrl(Json.parse("{\"images\":[{\"url\":\"https://image.civitai.com/a.mp4\",\"type\":\"video\"}]}")).isEmpty(),
                "videos are skipped (saving one as .png would be a broken image)");
        check(CivitaiClient.previewUrl(Json.parse("{\"images\":[{\"url\":\"https://image.civitai.com/a.jpeg\"}]}"))
                        .equals("https://image.civitai.com/a.jpeg"),
                "a plain image on the Civitai CDN is used");
        check(CivitaiClient.previewUrl(Json.parse("{\"images\":[{\"url\":\"https://image.civitai.com/r18.jpeg\",\"nsfwLevel\":8},{\"url\":\"https://image.civitai.com/safe.jpeg\",\"nsfwLevel\":1}]}"))
                        .equals("https://image.civitai.com/safe.jpeg"),
                "the safe image wins over the R18 one");
        check(CivitaiClient.previewUrl(Json.parse("{\"images\":[{\"url\":\"https://image.civitai.com/only.jpeg\",\"nsfwLevel\":8}]}"))
                        .equals("https://image.civitai.com/only.jpeg"),
                "an R18-only model still gets a cover instead of nothing");
        check(CivitaiClient.previewUrl(Json.parse("{\"images\":[{\"url\":\"https://evil.example/x.png\"},{\"url\":\"https://image.civitai.com/good.png\"}]}"))
                        .equals("https://image.civitai.com/good.png"),
                "a bad host earlier in the list does not block a good one later");
    }

    private static void successfulDownloadAndReuse() throws Exception {
        try (Fixture f = new Fixture()) {
            f.version.add("images", JsonParser.parseString("[{\"meta\":{\"prompt\":\"showcase +\",\"negativePrompt\":\"showcase -\"}},{\"meta\":null}]"));
            List<String> progress = new ArrayList<>();
            CivitaiClient.DownloadedLora result = f.client().download(API + "/models/10/a-lora?modelVersionId=20", progress::add);
            equal("测试 LoRA", result.modelName(), "model name");
            equal("v1", result.versionName(), "version name");
            equal("SD 1.5", result.baseModel(), "base model reported honestly");
            equal(List.of("trigger", "中文 词"), result.trainedWords(), "trained words preserved");
            equal(2, result.showcases().size(), "all version showcase entries captured");
            equal("showcase +", result.showcases().get(0).positive(), "preview prompt is not replaced with trained words");
            check(!result.showcases().get(1).skippedReason().isEmpty(), "missing metadata reported");
            equal(10L, result.modelId(), "model ID"); equal(20L, result.versionId(), "version ID");
            equal("Natsume_Ai_1_Nai.safetensors", result.path().getFileName().toString(), "original filename including case and underscores");
            check(Arrays.equals(f.bytes, Files.readAllBytes(result.path())), "exact verified bytes published");
            check(!result.reused(), "first download new");
            equal(1, f.binaryRequests, "download one binary");
            check(progress.size() >= 4 && progress.stream().noneMatch(s -> s.contains("https://") || s.contains("configured-secret")), "progress contains no URLs or tokens");
            String manifest = Files.readString(f.root.resolve("data/civitai/Natsume_Ai_1_Nai.safetensors.json"));
            check(manifest.contains(f.sha()) && !manifest.contains("downloadUrl") && !manifest.contains("secret"), "manifest stores hash without secret URLs");
            check(manifest.contains("showcase +") && manifest.contains("showcase -"), "showcase prompts persisted with model");
            CivitaiClient.DownloadedLora reused = f.client().download(API + "/api/v1/model-versions/20", null);
            check(reused.reused(), "restart revalidates and reuses existing file");
            equal(1, f.binaryRequests, "verified reuse skips binary download");
            equal(result.path(), reused.path(), "reused same stable path");
            f.noParts();
        }
    }

    private static void originalFilenames() throws Exception {
        for (String name : List.of("中文 模型_V2.safetensors", "Natsume_Ai_1_Nai.safetensors", "Example.SAFETENSORS"))
            try (Fixture f = new Fixture()) {
                f.file.addProperty("name", name);
                var result = f.client().download(API + "/api/download/models/20", null);
                equal(name, result.path().getFileName().toString(), "valid original name preserved exactly");
                equal(f.lora.toRealPath(), result.path().getParent(), "download stays in model directory");
                var manifest = JsonParser.parseString(Files.readString(f.root.resolve("data/civitai/" + name + ".json"))).getAsJsonObject();
                equal(name, manifest.get("original_filename").getAsString(), "original name archived");
            }
        Map<String, String> unsafe = Map.of(
                "../outside.safetensors", "outside.safetensors",
                "C:\\outside\\model.safetensors", "model.safetensors",
                "bad:name?.safetensors", "bad_name_.safetensors",
                "CON.safetensors", "_CON.safetensors",
                "LPT1.extra.safetensors", "_LPT1.extra.safetensors",
                " .safetensors", "model.safetensors");
        for (var entry : unsafe.entrySet()) try (Fixture f = new Fixture()) {
            f.file.addProperty("name", entry.getKey());
            var result = f.client().download(API + "/api/download/models/20", null);
            equal(entry.getValue(), result.path().getFileName().toString(), "unsafe Windows name sanitized");
            equal(f.lora.toRealPath(), result.path().getParent(), "unsafe name cannot escape directory");
        }
        check(CivitaiClient.safeFilename("长".repeat(300) + ".safetensors").length() <= 162, "long filename bounded");
    }

    private static void linksVersionAndFileSelection() throws Exception {
        try (Fixture f = new Fixture()) {
            JsonObject secondary = f.file.deepCopy();
            secondary.addProperty("id", 31); secondary.addProperty("primary", false);
            secondary.addProperty("name", "secondary_fp32.safetensors");
            Json.obj(secondary, "metadata").addProperty("fp", "fp32");
            secondary.addProperty("downloadUrl", API + "/api/download/models/20?fileId=31&type=Model&format=SafeTensor&fp=fp32");
            f.version.getAsJsonArray("files").add(secondary);
            CivitaiClient.DownloadedLora result = f.client().download(API + "/api/download/models/20?fileId=31&type=Model&format=SafeTensor&fp=fp32&size=full", null);
            equal("secondary_fp32.safetensors", result.path().getFileName().toString(), "selected file's original name");
            equal(secondary.get("downloadUrl").getAsString(), f.lastBinary.toString(), "uses API file URL unchanged with complete query");
            f.noParts();
        }
        try (Fixture f = new Fixture()) {
            JsonObject unavailable = f.version.deepCopy(); unavailable.addProperty("id", 21); unavailable.add("files", new JsonArray());
            JsonArray versions = new JsonArray(); versions.add(unavailable); versions.add(f.version); f.model.add("modelVersions", versions);
            CivitaiClient.DownloadedLora result = f.client().download(API + "/models/10/slug?utm_source=share", null);
            equal(20L, result.versionId(), "first version with a compatible downloadable file");
            check(f.calls.stream().noneMatch(c -> c.uri().getPath().endsWith("/21")), "skip empty version without unrelated download");
        }
        try (Fixture f = new Fixture()) {
            f.model.addProperty("type", "LoCon"); f.version.getAsJsonObject("model").addProperty("type", "LoCon");
            check(Files.exists(f.client().download(API + "/api/download/models/20?format=SafeTensor", null).path()), "LoCon accepted");
        }
    }

    private static void invalidLinksAndModels() throws Exception {
        try (Fixture f = new Fixture()) {
            for (String url : List.of("http://civitai.com/models/10", "https://evil.example/models/10", "https://civitai.com.evil.example/models/10",
                    "https://civitai.com@127.0.0.1/models/10", "https://civitai.com:8443/models/10", "https://civitai.com./models/10",
                    API + "/models/0", API + "/models/999999999999999999999999", API + "/models/10?token=configured-secret",
                    API + "/models/10?format=PickleTensor", API + "/models/10?type=TrainingData", API + "/models/10?fp=wrong",
                    API + "/models/10?size=wrong", API + "/models/10?fileId=0", API + "/models/10?modelVersionId=20&modelVersionId=21",
                    API + "/api/download/models/20?modelVersionId=21", API + "/models/10#fragment")) {
                expectFailure(() -> f.client().download(url, null), "", "invalid or unsafe URL rejected");
            }
            equal(0, f.calls.size(), "invalid URL rejected before network");
        }
        try (Fixture f = new Fixture()) {
            f.version.addProperty("modelId", 99);
            expectFailure(() -> f.client().download(API + "/models/10?modelVersionId=20", null), "归属", "model/version mismatch rejected");
            equal(0, f.binaryRequests, "mismatch never downloads");
        }
        try (Fixture f = new Fixture()) {
            f.version.getAsJsonObject("model").addProperty("type", "Checkpoint");
            expectFailure(() -> f.client().download(API + "/api/v1/model-versions/20", null), "不是 LoRA", "checkpoint rejected");
            equal(0, f.binaryRequests, "non-LoRA never downloads");
        }
        try (Fixture f = new Fixture()) {
            expectFailure(() -> f.client().download(API + "/api/download/models/20?fileId=999", null), "符合链接", "unknown explicit file never falls back to primary");
            f.file.addProperty("name", "evil.ckpt");
            expectFailure(() -> f.client().download(API + "/api/download/models/20", null), "safetensors", "pickle extension rejected");
            equal(0, f.binaryRequests, "unsupported files never downloaded");
        }
        try (Fixture f = new Fixture()) {
            f.file.getAsJsonObject("hashes").remove("SHA256");
            expectFailure(() -> f.client().download(API + "/api/download/models/20", null), "SHA256", "missing SHA256 fails closed");
            equal(0, f.binaryRequests, "no hash no download");
        }
    }

    private static void failedDownloadsCleanUp() throws Exception {
        try (Fixture f = new Fixture()) {
            for (int status : new int[] {401, 403, 404, 410, 429, 500}) {
                f.binaryStatus = status;
                IOException error = expectFailure(() -> f.client().download(API + "/api/download/models/20", null), "HTTP " + status, "HTTP status explicit");
                check(!error.toString().contains("configured-secret") && !error.toString().contains("signed-secret") && error.getCause() == null, "HTTP errors never expose body, token or signed URL");
                f.noParts(); check(!Files.exists(f.target()), "HTTP failure no final model");
            }
        }
        try (Fixture f = new Fixture()) {
            f.contentType = "text/html";
            expectFailure(() -> f.client().download(API + "/api/download/models/20", null), "网页", "HTML response rejected"); f.noParts();
            f.contentType = "application/octet-stream";
            f.file.getAsJsonObject("hashes").addProperty("SHA256", "0".repeat(64));
            expectFailure(() -> f.client().download(API + "/api/download/models/20", null), "SHA256", "wrong hash rejected"); f.noParts();
        }
        try (Fixture f = new Fixture()) {
            f.config.addProperty("max_download_mb", 1); f.file.addProperty("sizeKB", 2048);
            expectFailure(() -> f.client().download(API + "/api/download/models/20", null), "大小限制", "metadata limit before download");
            equal(0, f.binaryRequests, "oversized metadata never starts binary");
            f.file.addProperty("sizeKB", 1); f.contentLength = 1024L * 1024 + 1;
            expectFailure(() -> f.client().download(API + "/api/download/models/20", null), "大小限制", "Content-Length enforced"); f.noParts();
            f.contentLength = null; f.bytes = new byte[1024 * 1024 + 1];
            expectFailure(() -> f.client().download(API + "/api/download/models/20", null), "大小限制", "stream limit without Content-Length"); f.noParts();
        }
        try (Fixture f = new Fixture()) {
            f.contentLength = (long) f.bytes.length + 1;
            expectFailure(() -> f.client().download(API + "/api/download/models/20", null), "截断", "truncated stream rejected"); f.noParts();
        }
        try (Fixture f = new Fixture()) {
            f.metadataStatus = 401;
            expectFailure(() -> f.client().download(API + "/api/download/models/20", null), "HTTP 401", "API authorization error");
            equal(0, f.binaryRequests, "metadata denied prevents file request");
            f.metadataStatus = 200; f.badJson = true;
            expectFailure(() -> f.client().download(API + "/api/download/models/20", null), "无效元数据", "HTML metadata not parsed as model");
        }
    }

    private static void safetensorsStructureChecks() throws Exception {
        List<byte[]> invalid = List.of("<html>error</html>".getBytes(StandardCharsets.UTF_8),
                tensor("{\"x\":{\"dtype\":\"F32\",\"shape\":[2],\"data_offsets\":[0,4]}}", 4),
                tensor("{\"x\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[1,5]}}", 5),
                tensor("{\"x\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]},\"y\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]}}", 4),
                tensor("{\"x\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]},\"x\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]}}", 4),
                tensor("{\"__metadata__\":{\"data\":[]},\"x\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]}}", 4),
                tensor("{\"x\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]}}", 5),
                tensor("{\"x\":{\"dtype\":\"UNKNOWN\",\"shape\":[1],\"data_offsets\":[0,4]}}", 4));
        for (byte[] bytes : invalid) try (Fixture f = new Fixture()) {
            f.bytes = bytes; f.file.getAsJsonObject("hashes").addProperty("SHA256", f.sha());
            expectFailure(() -> f.client().download(API + "/api/download/models/20", null), "safetensors", "matching checksum is insufficient without valid structure");
            f.noParts(); check(!Files.exists(f.target()), "invalid tensor structure never published");
        }
    }

    private static void existingAndConcurrentFilesNeverOverwritten() throws Exception {
        try (Fixture f = new Fixture()) {
            byte[] original = "user-owned bytes".getBytes(StandardCharsets.UTF_8); Files.write(f.target(), original);
            var result = f.client().download(API + "/api/download/models/20", null);
            equal("Natsume_Ai_1_Nai__civitai_10_20_30.safetensors", result.path().getFileName().toString(), "conflict adds identity to original stem");
            check(Arrays.equals(original, Files.readAllBytes(f.target())), "existing bytes unchanged");
            check(Arrays.equals(f.bytes, Files.readAllBytes(result.path())), "new model saved separately");
            var repeated = f.client().download(API + "/api/download/models/20", null);
            check(repeated.reused(), "repeat reuses conflict filename");
            equal(result.path(), repeated.path(), "stable collision path"); equal(1, f.binaryRequests, "no repeat binary download");
        }
        try (Fixture f = new Fixture()) {
            Files.createDirectories(f.target());
            Path second = f.lora.resolve("Natsume_Ai_1_Nai__civitai_10_20_30.safetensors");
            Files.writeString(second, "other version");
            var result = f.client().download(API + "/api/download/models/20", null);
            equal("Natsume_Ai_1_Nai__civitai_10_20_30_2.safetensors", result.path().getFileName().toString(), "directory and secondary conflict handled");
            check(Files.isDirectory(f.target()), "directory preserved");
            equal("other version", Files.readString(second), "secondary conflict preserved");
        }
        try (Fixture f = new Fixture()) {
            byte[] original = "concurrent user bytes".getBytes(StandardCharsets.UTF_8);
            f.afterBody = () -> Files.write(f.target(), original);
            expectFailure(() -> f.client().download(API + "/api/download/models/20", null), "不会覆盖", "publish race never overwrites another process file");
            check(Arrays.equals(original, Files.readAllBytes(f.target())), "concurrent file preserved"); f.noParts();
        }
        try (Fixture f = new Fixture()) {
            f.afterBody = () -> Files.write(f.target(), f.bytes);
            check(f.client().download(API + "/api/download/models/20", null).reused(), "matching concurrently published file reused"); f.noParts();
        }
    }

    private static void redirectsDnsAndTokenIsolation() throws Exception {
        try (Fixture f = new Fixture()) {
            f.redirect = CDN;
            f.client().download(API + "/api/download/models/20", null);
            check(f.calls.stream().filter(c -> c.uri().getHost().equals("civitai.com")).allMatch(c -> "Bearer environment-secret".equals(c.headers().get("Authorization"))), "environment token takes priority and sent only to official API");
            check(f.calls.stream().filter(c -> !c.uri().getHost().equals("civitai.com")).allMatch(c -> !c.headers().containsKey("Authorization")), "CDN never receives bearer token");
            check(f.calls.stream().allMatch(c -> !c.uri().toString().contains("environment-secret")), "token never appended to URLs");
        }
        try (Fixture f = new Fixture()) {
            f.redirect = "https://127.0.0.1/private";
            expectFailure(() -> f.client().download(API + "/api/download/models/20", null), "内网", "redirect to literal loopback rejected");
            check(f.calls.stream().noneMatch(c -> c.uri().getHost().equals("127.0.0.1")), "unsafe redirect never requested"); f.noParts();
        }
        try (Fixture f = new Fixture()) {
            f.redirect = "https://internal.example/file"; f.privateHost = "internal.example";
            expectFailure(() -> f.client().download(API + "/api/download/models/20", null), "内网", "redirect hostname resolving private rejected"); f.noParts();
            f.redirect = "http://cdn.civitai.com/file";
            expectFailure(() -> f.client().download(API + "/api/download/models/20", null), "跳转地址无效", "HTTPS downgrade rejected");
        }
        try (Fixture f = new Fixture()) {
            f.metadataRedirect = CDN;
            expectFailure(() -> f.client().download(API + "/api/download/models/20", null), "非官方 API", "metadata redirects never leave official API");
            equal(1, f.calls.size(), "metadata redirect stopped before CDN request");
        }
        try (Fixture f = new Fixture()) {
            f.redirect = API + "/api/download/models/20";
            expectFailure(() -> f.client().download(API + "/api/download/models/20", null), "次数过多", "redirect loop bounded");
            equal(6, f.binaryRequests, "at most six requests in redirect chain"); f.noParts();
        }
        try (Fixture f = new Fixture()) {
            f.privateHost = "civitai.com";
            expectFailure(() -> f.client().download(API + "/api/download/models/20", null), "内网", "initial official DNS still must be public"); equal(0, f.calls.size(), "blocked DNS never reaches transport");
        }
        for (String address : List.of("127.0.0.1", "0.0.0.0", "10.1.2.3", "172.16.0.1", "192.168.1.2", "169.254.169.254", "100.64.0.1", "198.18.1.94", "203.0.113.3", "::1", "fc00::1", "fe80::1", "2001:db8::1", "2001:2::1", "3fff::1", "2002:7f00:1::1"))
            check(!CivitaiClient.publicAddress(InetAddress.getByName(address)), "nonpublic address blocked: " + address);
        check(CivitaiClient.publicAddress(InetAddress.getByName("8.8.8.8")), "public IPv4 accepted");
        check(CivitaiClient.publicAddress(InetAddress.getByName("2606:4700:4700::1111")), "public IPv6 accepted");
    }

    private static void proxyPolicy() throws Exception {
        try (Fixture f = new Fixture()) {
            f.config.addProperty("proxy_url", "http://127.0.0.1:7890"); f.failDns = true; f.redirect = CDN;
            check(Files.exists(f.client().download(API + "/api/download/models/20", null).path()), "explicit local proxy delegates remote DNS and supports known Civitai CDN");
            equal(0, f.dnsRequests, "proxy mode does not trust fake-IP DNS");
        }
        try (Fixture f = new Fixture()) {
            f.config.addProperty("proxy_url", "http://127.0.0.1:7890"); f.redirect = "https://untrusted.example/file";
            expectFailure(() -> f.client().download(API + "/api/download/models/20", null), "代理模式拒绝", "proxy mode disallows arbitrary hosts"); f.noParts();
        }
        try (Fixture f = new Fixture()) {
            f.config.addProperty("proxy_url", "http://user:secret@127.0.0.1:7890");
            expectFailure(f::client, "proxy_url", "proxy credentials URL rejected without echo");
        }
        check(!CivitaiClient.trustedProxyHost("civitai.com.evil.example"), "lookalike proxy host blocked");
        check(!CivitaiClient.trustedProxyHost("civitai-delivery-worker-prod.attacker.r2.cloudflarestorage.com"), "R2 account pinned");
        check(!CivitaiClient.trustedProxyHost("127.0.0.1"), "proxy mode literal address blocked");
    }

    private static void deadlineInterruptsBlockedBody() throws Exception {
        try (Fixture f = new Fixture()) {
            f.config.addProperty("timeout_seconds", 1); f.blockBody = true;
            long start = System.nanoTime();
            expectFailure(() -> f.client().download(API + "/api/download/models/20", null), "总时间限制", "blocked streaming body interrupted by total deadline");
            check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(4), "deadline closes body instead of waiting forever");
            f.noParts(); check(!Files.exists(f.target()), "timeout never publishes partial file");
        }
    }

    private interface Operation { Object run() throws Exception; }
    private static IOException expectFailure(Operation operation, String fragment, String message) throws Exception {
        try { operation.run(); throw new AssertionError(message + ": expected exception"); }
        catch (IOException expected) { check(expected.getMessage().contains(fragment), message + ": " + expected.getMessage()); return expected; }
    }
    private static void equal(Object expected, Object actual, String message) { check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual); }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static byte[] tensor(String json, int dataSize) {
        byte[] header = json.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(8 + header.length + dataSize).order(ByteOrder.LITTLE_ENDIAN).putLong(header.length).put(header).put(new byte[dataSize]).array();
    }

    private static final class Fixture implements AutoCloseable {
        record Call(URI uri, Map<String, String> headers, Duration timeout) {}
        final Path root, lora;
        final JsonObject config = new JsonObject(), model = new JsonObject(), version = new JsonObject(), file = new JsonObject();
        final List<Call> calls = new ArrayList<>();
        byte[] bytes = tensor("{\"__metadata__\":{\"name\":\"test\"},\"lora_unet.weight\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]}}", 4);
        String redirect, metadataRedirect, privateHost, contentType = "application/octet-stream";
        int binaryStatus = 200, metadataStatus = 200, binaryRequests, dnsRequests;
        Long contentLength;
        boolean badJson, blockBody, failDns;
        Operation afterBody;
        URI lastBinary;

        Fixture() throws Exception {
            Path work = Path.of(System.getProperty("bot.test.work", "work"), "civitai-client-tests").toAbsolutePath();
            Files.createDirectories(work); root = Files.createTempDirectory(work, "case-"); lora = Files.createDirectories(root.resolve("models/Lora"));
            config.addProperty("lora_dir", lora.toString()); config.addProperty("max_download_mb", 2); config.addProperty("timeout_seconds", 5); config.addProperty("api_token", "configured-secret");
            // The fixtures intercept the civitai.com API host; production defaults to the civitai.red mirror.
            config.addProperty("base_url", API);
            file.addProperty("id", 30); file.addProperty("name", "Natsume_Ai_1_Nai.safetensors"); file.addProperty("type", "Model"); file.addProperty("sizeKB", bytes.length / 1024.0);
            file.addProperty("primary", true); file.addProperty("downloadUrl", API + "/api/download/models/20?type=Model&format=SafeTensor");
            JsonObject metadata = new JsonObject(); metadata.addProperty("format", "SafeTensor"); metadata.addProperty("fp", "fp16"); metadata.addProperty("size", "full"); file.add("metadata", metadata);
            JsonObject hashes = new JsonObject(); hashes.addProperty("SHA256", sha()); file.add("hashes", hashes);
            version.addProperty("id", 20); version.addProperty("modelId", 10); version.addProperty("name", "v1"); version.addProperty("baseModel", "SD 1.5");
            version.add("trainedWords", Json.GSON.toJsonTree(List.of("trigger", "中文 词")));
            JsonObject parent = new JsonObject(); parent.addProperty("type", "LORA"); parent.addProperty("name", "测试 LoRA"); version.add("model", parent);
            JsonArray files = new JsonArray(); files.add(file); version.add("files", files);
            model.addProperty("id", 10); model.addProperty("name", "测试 LoRA"); model.addProperty("type", "LORA");
            JsonArray versions = new JsonArray(); versions.add(version); model.add("modelVersions", versions);
        }
        String sha() throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        Path target() { return lora.resolve("Natsume_Ai_1_Nai.safetensors"); }
        CivitaiClient client() throws IOException {
            return new CivitaiClient(root, config, this::get, host -> {
                dnsRequests++;
                if (failDns) throw new IOException("DNS should not be queried in proxy mode");
                if (host.equals(privateHost) || host.equals("127.0.0.1")) return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
                return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
            }, "environment-secret");
        }
        CivitaiClient.Response get(URI uri, Map<String, String> headers, Duration timeout) throws Exception {
            calls.add(new Call(uri, headers, timeout));
            if (uri.getPath().startsWith("/api/v1/")) {
                if (metadataRedirect != null) return response(302, Map.of("Location", List.of(metadataRedirect)), new byte[0]);
                String body = badJson ? "<html>secret error</html>" : Json.GSON.toJson(uri.getPath().startsWith("/api/v1/models/") ? model : version);
                return response(metadataStatus, Map.of("Content-Type", List.of("application/json")), body.getBytes(StandardCharsets.UTF_8));
            }
            binaryRequests++; lastBinary = uri;
            if (redirect != null && uri.getHost().equals("civitai.com")) return response(302, Map.of("Location", List.of(redirect)), new byte[0]);
            Map<String, List<String>> responseHeaders = new HashMap<>(); responseHeaders.put("Content-Type", List.of(contentType));
            if (contentLength != null) responseHeaders.put("Content-Length", List.of(contentLength.toString()));
            if (blockBody) {
                InputStream input = new InputStream() {
                    final CountDownLatch closed = new CountDownLatch(1);
                    public int read() throws IOException { try { closed.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } throw new IOException("stream closed"); }
                    public int read(byte[] b, int off, int len) throws IOException { return read(); }
                    public void close() { closed.countDown(); }
                };
                return new CivitaiClient.Response(binaryStatus, responseHeaders, input);
            }
            InputStream input = new ByteArrayInputStream(bytes) {
                boolean finished;
                public synchronized int read(byte[] b, int off, int len) {
                    int result = super.read(b, off, len);
                    if (result < 0 && !finished && afterBody != null) {
                        finished = true;
                        try { afterBody.run(); } catch (Exception e) { throw new RuntimeException(e); }
                    }
                    return result;
                }
            };
            return new CivitaiClient.Response(binaryStatus, responseHeaders, input);
        }
        static CivitaiClient.Response response(int status, Map<String, List<String>> headers, byte[] bytes) { return new CivitaiClient.Response(status, headers, new ByteArrayInputStream(bytes)); }
        void noParts() throws IOException { try (var paths = Files.list(lora)) { check(paths.noneMatch(p -> p.getFileName().toString().endsWith(".part")), "no leaked .part files"); } }
        public void close() throws IOException {
            // Only this exact generated test directory is owned; Files.walk does not follow links.
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
}
