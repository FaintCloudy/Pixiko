package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.StackClassifier;

/**
 * {@code /api/styles} 的**读时重判**（手机端报的「识别 lora、style 依旧会识别出 model ckpt」的样式侧）：
 * 存量样式里写了通用底模名（{@code model.ckpt}）时，出口必须与 {@code /api/loras} 同一套判据——
 * 底模名成「SD 1.5（原底模名 model.ckpt）」（与 LoRA 侧**逐字相同**）、栈成 {@code sd}、绝不再冒出
 * {@code model-ckpt} 这种伪栈名；具体底模名（SDXL / Anima / Illustrious …）一字不变；新增的
 * {@code baseModelLabel} / {@code baseModelGroupKey} / {@code evidence} 有值；坏数据/缺字段按"未知"处理且不崩。
 * **数据文件 {@code data/local-styles.json} 一个字都不改。**
 */
public final class StyleBaseModelTest {
    /**
     * 通用底模名给人看的写法。**必须与 LoRA 侧逐字相同**——那就是
     * {@code LoraBaseModelTest} 里 {@code ss_sd_model_name=model.ckpt} 的 LoRA 的期望值（178-179 行：
     * {@code … base.name()} 是这一串、{@code base.stack()} 是 {@code sd}），这里不改那边的文件，
     * 直接引用同一串字面量来钉住"两边一模一样"。
     */
    private static final String GENERIC_LABEL = "SD 1.5（原底模名 model.ckpt）";

    private static int assertions;

    public static void main(String[] args) throws Exception {
        try (Fixture fixture = new Fixture()) {
            storedGenericIsRederived(fixture);
            specificNamesUntouched(fixture);
            newFieldsAndBrokenData(fixture);
        }
        System.out.println("StyleBaseModelTest: " + assertions + " assertions passed: 存量样式的底模/栈读时重判"
                + "（model.ckpt → SD 1.5 / sd 栈）、具体底模名一字不变、新字段与坏数据。");
    }

    /** ① 存量 {@code baseModel=model.ckpt} / {@code stack=model-ckpt} 的样式：出口是人话 + sd 栈，且不改文件。 */
    private static void storedGenericIsRederived(Fixture fixture) throws Exception {
        fixture.seed(List.of(
                // 真机 #37 同形：底模是通用名、栈是伪栈名、来源写的是 safetensors-header。
                fixture.style("春日野穹 2", fixture.model("baseModel", "model.ckpt", "stack", "model-ckpt",
                        "baseModelSource", "safetensors-header", "stackSource", "inferred",
                        "forge_preset", "xl", "width", 1024, "height", 1536)),
                fixture.style("SDXL 风格", fixture.model("baseModel", "SDXL", "stack", "xl")),
                fixture.style("NoobAI 风格", fixture.model("baseModel", "NoobAI", "stack", "xl")),
                fixture.style("Anima 风格", fixture.model("baseModel", "Anima", "stack", "anima"))));
        Path file = fixture.root.resolve("data/local-styles.json");
        String before = Files.readString(file, StandardCharsets.UTF_8);
        JsonObject item = fixture.item(fixture.bot().webStyles(), "春日野穹 2");
        String summary = Json.str(item, "modelSummary", "");
        check(!Json.str(item, "baseModel", "").contains("model-ckpt") && !Json.str(item, "stack", "").contains("model-ckpt")
                        && !Json.str(item, "stackLabel", "").contains("model-ckpt") && !summary.contains("model-ckpt"),
                "① 存量 model.ckpt 不再变成 model-ckpt 伪栈：" + item);
        equal(GENERIC_LABEL, Json.str(item, "baseModel", ""), "① 底模名与 LoRA 侧逐字相同");
        equal("sd", Json.str(item, "stack", ""), "① 栈重判成 sd");
        equal("SD 1.5 栈", Json.str(item, "stackLabel", ""), "① 栈标签是 SD 1.5 栈");
        check(Json.str(item, "baseModelLabel", "").equals("底模 " + GENERIC_LABEL) && summary.contains(GENERIC_LABEL),
                "① baseModelLabel 与摘要都用同一句（与 LoRA 侧一致）：" + summary);
        equal(before, Files.readString(file, StandardCharsets.UTF_8), "① 数据文件一个字都不改（只读重判）");
    }

    /** ② 本来就是具体底模名的样式：底模名/栈一字不变（回归重点）。 */
    private static void specificNamesUntouched(Fixture fixture) throws Exception {
        JsonObject payload = fixture.bot().webStyles();
        for (String[] expected : List.of(new String[] {"SDXL 风格", "SDXL", "xl"},
                new String[] {"NoobAI 风格", "NoobAI", "xl"}, new String[] {"Anima 风格", "Anima", "anima"})) {
            JsonObject item = fixture.item(payload, expected[0]);
            check(expected[1].equals(Json.str(item, "baseModel", "")) && expected[2].equals(Json.str(item, "stack", "")),
                    "② 「" + expected[0] + "」的底模名与栈一字不变：" + item);
        }
    }

    /** ③ 新字段与 /api/loras 同形；④⑤ 坏数据/缺字段：按"未知"处理、不崩、不编。 */
    private static void newFieldsAndBrokenData(Fixture fixture) throws Exception {
        fixture.seed(List.of(
                fixture.style("春日野穹 2", fixture.model("baseModel", "model.ckpt", "stack", "model-ckpt",
                        "baseModelSource", "safetensors-header")),
                fixture.style("SDXL 风格", fixture.model("baseModel", "SDXL", "stack", "xl")),
                // 坏数据/缺字段：只有伪栈名、底模名是占位值、model 都缺。
                fixture.style("只有栈 1", fixture.model("stack", "model-ckpt")),
                fixture.style("占位底模 1", fixture.model("baseModel", "model", "stack", "unknown")),
                fixture.style("没有 model 1", null)));
        JsonObject payload = fixture.bot().webStyles();
        JsonObject generic = fixture.item(payload, "春日野穹 2");
        // 与 LoRA 侧**逐字相同**：同一个 model.ckpt 在两边必须是同一句，否则用户以为是两码事。
        // 下面这串就是 LoraBaseModelTest（178-179 行）对 ss_sd_model_name=model.ckpt 的 LoRA 的期望值，
        // 而它也是 SdClient.resolveBaseModel 走 StackClassifier.genericBaseModelLabel 时给出的同一个标签
        // （SdClientTest 用桩 WebUI 的 /sdapi/v1/loras 走的就是这一条）。
        equal(GENERIC_LABEL, StackClassifier.genericBaseModelLabel("model.ckpt"), "③ 标签本身就是 LoRA 侧那一串");
        equal("底模 " + GENERIC_LABEL, Json.str(generic, "baseModelLabel", ""),
                "③ 样式侧的 baseModelLabel 也是同一句（/api/loras 形制）");
        equal("sd 1.5", Json.str(generic, "baseModelGroupKey", ""), "③ 分组键＝归一后的底模名（与 LoRA 侧 groupKey 同形）");
        check(!Json.str(generic, "evidence", "").isBlank() && Json.str(generic, "evidence", "").contains("model.ckpt"),
                "③ evidence 如实写出判据（不编）：" + Json.str(generic, "evidence", ""));
        equal("", Json.str(fixture.item(payload, "SDXL 风格"), "evidence", ""), "③ 存量没记判据时 evidence 留空（不编）");
        // ④ 坏数据/伪栈名：不崩，也不许漏出 model-ckpt。
        List<String> leaked = new ArrayList<>();
        for (String name : List.of("只有栈 1", "占位底模 1", "没有 model 1")) {
            JsonObject item = fixture.item(payload, name);
            for (String key : List.of("baseModel", "stack", "stackLabel", "modelSummary", "baseModelLabel")) {
                if (Json.str(item, key, "").contains("model-ckpt")) leaked.add(name + "." + key + "=" + Json.str(item, key, ""));
            }
        }
        check(leaked.isEmpty(), "④ 坏数据里的伪栈名一个都不许漏出去：" + leaked);
        JsonObject bare = fixture.item(payload, "只有栈 1");
        check("sd".equals(Json.str(bare, "stack", "")) && "SD 1.5".equals(Json.str(bare, "baseModel", "")),
                "④ 只剩伪栈名时按名字重判成 sd 栈、并按栈补底模名：" + bare);
        JsonObject placeholder = fixture.item(payload, "占位底模 1");
        equal("", Json.str(placeholder, "baseModel", ""), "⑤ 占位值（model）当成没有底模，不编");
        equal("底模未识别", Json.str(placeholder, "baseModelLabel", ""), "⑤ 未识别就如实说未识别");
        JsonObject none = fixture.item(payload, "没有 model 1");
        check(!none.has("baseModel") && !none.has("stackLabel"), "⑤ 完全没有 model 的样式不给这些字段：" + none);
    }

    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }

    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }

    /** 一个可用的机器人（真 SdClient + 桩 WebUI），样式库是临时目录里的真文件。 */
    private static final class Fixture implements AutoCloseable {
        final Path root;
        final HttpServer server;
        final SdClient client;

        Fixture() throws Exception {
            Path work = Path.of(System.getProperty("bot.test.work", "work"), "style-base-model-tests").toAbsolutePath();
            Files.createDirectories(work);
            root = Files.createTempDirectory(work, "case-");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(404, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Exception ignored) { /* 桩服务：读请求体失败也不影响测试 */ }
            });
            server.start();
            JsonObject sd = new JsonObject();
            sd.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort());
            sd.addProperty("timeout_seconds", 2);
            JsonObject config = new JsonObject();
            config.add("sd", sd);
            Json.atomicWrite(root.resolve("config.json"), config);
            client = new SdClient(root, sd);
        }

        Bot bot() throws Exception { return new Bot(new Settings(root), client, (event, segments) -> null); }

        /** 直接写一份 data/local-styles.json（模拟用户的真实数据文件）。 */
        void seed(List<JsonObject> styles) throws Exception {
            JsonObject data = new JsonObject();
            data.addProperty("version", 1);
            JsonArray array = new JsonArray();
            styles.forEach(array::add);
            data.add("styles", array);
            Json.atomicWrite(root.resolve("data/local-styles.json"), data);
        }

        JsonObject style(String name, JsonObject model) {
            JsonObject item = new JsonObject();
            item.addProperty("name", name);
            item.addProperty("positive", name + " 提示词");
            item.addProperty("negative", "");
            item.addProperty("updated_at", "2026-01-01T00:00:00Z");
            if (model != null) item.add("model", model);
            return item;
        }

        /** 键值对拼一份 model（数字按数字写，其余按字符串）。 */
        JsonObject model(Object... pairs) {
            JsonObject model = new JsonObject();
            for (int index = 0; index + 1 < pairs.length; index += 2) {
                String key = String.valueOf(pairs[index]);
                Object value = pairs[index + 1];
                if (value instanceof Number number) model.addProperty(key, number);
                else model.addProperty(key, String.valueOf(value));
            }
            return model;
        }

        /** /api/styles 里那条样式（找不到就报错）。 */
        JsonObject item(JsonObject payload, String name) {
            for (JsonElement element : payload.getAsJsonArray("styles")) {
                JsonObject item = element.getAsJsonObject();
                if (name.equals(Json.str(item, "name", ""))) return item;
            }
            throw new AssertionError("/api/styles 里没有「" + name + "」：" + payload);
        }

        public void close() { server.stop(0); }
    }
}
