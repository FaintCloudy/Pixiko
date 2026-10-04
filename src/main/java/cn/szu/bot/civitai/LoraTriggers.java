package cn.szu.bot.civitai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import cn.szu.bot.Json;
import cn.szu.bot.Log;

/**
 * 本机 LoRA 的触发词（Civitai 的 trained words）耐久存储：{@code data/lora-triggers.json}。
 *
 * <p><b>为什么单独存一份</b>：触发词在下载时是拿得到的，但以前只用来生成一条"触发词 N 条"的回执，
 * 用完即弃；下载记录（{@code data/civitai/<文件名>.json}）里的 {@code trained_words} 又只服务于展示图样式，
 * 界面上看不到。这里把它按"本机 LoRA 文件名"落成一份可随时查的记录。
 *
 * <p>结构（UTF-8 无 BOM、纯 LF、原子写）：
 * <pre>
 * {
 *   "version": 1,
 *   "updatedAt": "2026-01-01T00:00:00Z",
 *   "triggers": {
 *     "Kanbe_Kotori_1_nai-000034": {          // 键 = LoRA 文件名去掉 .safetensors
 *       "loraName": "Kanbe_Kotori_1_nai-000034",
 *       "fileName": "Kanbe_Kotori_1_nai-000034.safetensors",
 *       "trainedWords": ["Kanbe Kotori,twin braids,hair flower,", "official art,"],   // 有序、原样
 *       "sourceUrl": "https://civitai.com/models/1599346?modelVersionId=1809851",
 *       "civitaiModelId": 1599346,
 *       "civitaiVersionId": 1809851,
 *       "baseModel": "NoobAI",                 // Civitai 页面上的 Base Model 原样字符串
 *       "stack": "SDXL",                       // 本机家族/栈判断（可空，只作参考）
 *       "recordedAt": "2026-10-03T21:16:05.949261100Z",
 *       "source": "civitai-download"
 *     }
 *   }
 * }
 * </pre>
 *
 * <p><b>读坏文件不崩、也不吞数据</b>：坏 JSON、缺字段、条目类型不对一律按"没有记录"处理；
 * 真要写的时候会先把原件另存成 {@code lora-triggers.json.corrupt-<时间戳>}，绝不静默覆盖用户数据。
 */
public final class LoraTriggers {
    /** 存储文件（相对机器人数据目录）。 */
    public static final String RELATIVE_PATH = "data/lora-triggers.json";
    /** 格式版本：加字段不改版本，改语义才加。 */
    public static final int FORMAT_VERSION = 1;
    /** 触发词来源：Civitai 下载成功那一刻写入。 */
    public static final String SOURCE_DOWNLOAD = "civitai-download";
    /** 触发词来源：从本机 Civitai 下载记录（data/civitai/<文件名>.json）只读补齐。 */
    public static final String SOURCE_MANIFEST = "civitai-record-backfill";
    /** 触发词来源：管理员手动补录（此前没有记录）。 */
    public static final String SOURCE_MANUAL = "manual";
    /** 触发词来源：管理员手动补录或修改（此前已有记录）。 */
    public static final String SOURCE_MANUAL_EDIT = "manual-edit";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    /**
     * 一个 LoRA 的触发词记录。
     *
     * <p>{@code trainedWords} 一律有序、原样（大小写、空格、逗号、换行都不动）；Civitai 没给时是**空列表**
     * 而不是 {@code null}——"有记录但 0 条"与"压根没记录"是两件事，回执要分得清。
     */
    public record Trigger(String loraName, String fileName, List<String> trainedWords, String sourceUrl,
                          long civitaiModelId, long civitaiVersionId, String baseModel, String stack,
                          String recordedAt, String source) {
        public Trigger {
            loraName = blank(loraName);
            fileName = blank(fileName);
            List<String> words = new ArrayList<>();
            if (trainedWords != null) for (String word : trainedWords) if (word != null) words.add(word);
            trainedWords = List.copyOf(words);
            sourceUrl = blank(sourceUrl);
            baseModel = blank(baseModel);
            stack = blank(stack);
            recordedAt = blank(recordedAt);
            source = blank(source);
        }

        public int count() { return trainedWords.size(); }
        /** 有 Civitai 页面地址（模型 id 拿到了）才算有来源页面。 */
        public boolean hasPage() { return !sourceUrl.isBlank() && civitaiModelId > 0; }
        /** 人话的来源说明；未知来源如实说"未记录"，不编。 */
        public String sourceLabel() {
            return switch (source) {
                case SOURCE_DOWNLOAD -> "Civitai 下载时记录";
                case SOURCE_MANIFEST -> "从本机 Civitai 下载记录补齐";
                case SOURCE_MANUAL -> "手动补录";
                case SOURCE_MANUAL_EDIT -> "手动补录/修改";
                default -> source.isBlank() ? "来源未记录" : source;
            };
        }
        /**
         * 页面地址（回执/日志用）：去掉协议，并抹掉任何看起来像凭据的查询参数——回执是给用户看的，
         * 宁可少一个参数，也不让令牌有露出来的机会。
         */
        public String pageAddress() {
            String value = sourceUrl.replaceFirst("(?i)^https?://", "");
            value = value.replaceAll("(?i)([?&])(token|api[_-]?key|apikey|key|password|sig|signature)=[^&]*", "$1");
            value = value.replaceAll("([?&])+$", "");
            return value;
        }
    }

    /** 一次读取的结果：条目 + 这次读取是否遇到坏文件（坏文件按"没有记录"处理）。 */
    private record Snapshot(Map<String, Trigger> entries, boolean corrupt) { }

    private final Path root, file;
    private volatile boolean corrupt;
    private volatile int skipped;

    public LoraTriggers(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.file = this.root.resolve(RELATIVE_PATH);
    }

    /** 存储文件路径（回执与日志里写出来，用户能自己去看）。 */
    public Path path() { return file; }

    /** 最近一次读取是否遇到坏 JSON / 缺字段（如实告知用；不影响查询结果）。 */
    public boolean corrupt() { return corrupt; }

    /** 最近一次读取里被跳过（类型不对）的条目数。 */
    public int skippedEntries() { return skipped; }

    /**
     * 按名字取一条记录：先精确匹配键（文件名去掉扩展名），再大小写不敏感、再按记录里的 loraName/fileName，
     * 最后忽略空格/下划线/连字符/点做宽松匹配。找不到返回 {@code null}（"没有记录"，不是"0 条"）。
     */
    public Trigger get(String name) {
        String wanted = key(name);
        if (wanted.isEmpty()) return null;
        Map<String, Trigger> entries = read().entries();
        Trigger exact = entries.get(wanted);
        if (exact != null) return exact;
        String lower = wanted.toLowerCase(Locale.ROOT), loose = looseKey(wanted);
        for (Map.Entry<String, Trigger> entry : entries.entrySet()) {
            Trigger trigger = entry.getValue();
            if (entry.getKey().toLowerCase(Locale.ROOT).equals(lower)
                    || key(trigger.loraName()).toLowerCase(Locale.ROOT).equals(lower)
                    || key(trigger.fileName()).toLowerCase(Locale.ROOT).equals(lower)) return trigger;
        }
        for (Map.Entry<String, Trigger> entry : entries.entrySet())
            if (looseKey(entry.getKey()).equals(loose)) return entry.getValue();
        return null;
    }

    /** 全部条目（键顺序 = 写入顺序）。坏文件/坏条目按"没有记录"处理，绝不抛错。 */
    public Map<String, Trigger> all() { return new LinkedHashMap<>(read().entries()); }

    /** 现在一共有多少条记录（不含坏条目）。 */
    public int size() { return read().entries().size(); }

    /**
     * 写入/更新一条记录：与已有条目合并（不会丢掉别的 LoRA 的记录）。坏文件先备份再重建。
     *
     * @return 写进去的那条记录
     */
    public synchronized Trigger put(Trigger trigger) throws IOException {
        Objects.requireNonNull(trigger, "trigger");
        String name = key(trigger.loraName().isBlank() ? trigger.fileName() : trigger.loraName());
        if (name.isEmpty()) throw new IOException("触发词记录缺少 LoRA 名称，无法写入。");
        Snapshot snapshot = read();
        if (snapshot.corrupt()) {
            preserveCorrupt();
            snapshot = new Snapshot(new LinkedHashMap<>(), false);
        }
        LinkedHashMap<String, Trigger> entries = new LinkedHashMap<>(snapshot.entries());
        entries.put(name, trigger);
        write(entries);
        return trigger;
    }

    /**
     * 手动补录（管理员）：只改触发词，原来记着的页面地址/底模/栈尽力保留；此前没有记录就新建。
     *
     * @param words 用户给的触发词，顺序与写法原样保留
     */
    public synchronized Trigger setWords(String loraName, String fileName, List<String> words) throws IOException {
        Trigger existing = get(loraName == null || loraName.isBlank() ? fileName : loraName);
        String name = existing == null ? key(loraName == null || loraName.isBlank() ? fileName : loraName) : existing.loraName();
        String file = existing == null ? blank(fileName) : existing.fileName();
        return put(new Trigger(name, file, words,
                existing == null ? "" : existing.sourceUrl(),
                existing == null ? 0 : existing.civitaiModelId(),
                existing == null ? 0 : existing.civitaiVersionId(),
                existing == null ? "" : existing.baseModel(),
                existing == null ? "" : existing.stack(),
                Instant.now().toString(),
                existing == null ? SOURCE_MANUAL : SOURCE_MANUAL_EDIT));
    }

    /**
     * 历史 LoRA 的**只读补齐**：把本机 Civitai 下载记录（{@code data/civitai/<文件名>.json}）里已经存下来的
     * {@code trained_words} 搬进本存储。不联网、不下载权重、不改 LoRA 文件、不改下载记录；
     * 已有记录一律不覆盖。补不齐的（没有下载记录）只是记一行日志，交给调用方如实汇报。
     *
     * @param localFileNames 本机 LoRA 文件名（含 .safetensors）
     * @param baseUrl        Civitai 站点地址（用来拼页面地址；空则用官方站）
     * @return 本次新写入的记录
     */
    public synchronized List<Trigger> backfill(Collection<String> localFileNames, String baseUrl) {
        List<Trigger> added = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String fileName : new LinkedHashSet<>(localFileNames == null ? List.<String>of() : localFileNames)) {
            try {
                if (get(fileName) != null) continue;
                Trigger record = fromCivitaiRecord(root, fileName, baseUrl);
                if (record == null) { missing.add(blank(fileName)); continue; }
                put(record);
                added.add(record);
            } catch (Exception error) {
                missing.add(blank(fileName) + "（" + error(error) + "）");
            }
        }
        if (!missing.isEmpty())
            Log.warn("LoRA 触发词补齐：以下 LoRA 没有可用的 Civitai 下载记录，留空：" + String.join("、", missing));
        return added;
    }

    /**
     * 只读：本机 Civitai 下载记录（{@code data/civitai/<文件名>.json}）里的触发词与来源信息。
     * 没有记录、读不出来都返回 {@code null}（调用方按"没有记录"处理，绝不猜）。
     */
    public static Trigger fromCivitaiRecord(Path root, String fileName, String baseUrl) {
        String name = blank(fileName);
        if (name.isEmpty()) return null;
        Path record = root.toAbsolutePath().normalize().resolve("data/civitai").resolve(name + ".json");
        if (!Files.isRegularFile(record)) return null;
        try {
            JsonObject data = Json.parse(Files.readString(record, StandardCharsets.UTF_8));
            long modelId = number(data.get("model_id")), versionId = number(data.get("version_id"));
            String recordedAt = Json.str(data, "verified_at", "");
            return new Trigger(key(name), name, strings(data.get("trained_words")),
                    sourceUrl(baseUrl, modelId, versionId), modelId, versionId,
                    baseModel(Json.str(data, "base_model", "")), "",
                    recordedAt.isBlank() ? Instant.now().toString() : recordedAt, SOURCE_MANIFEST);
        } catch (Exception error) {
            Log.warn("读取 Civitai 下载记录失败（" + name + "）：" + error(error));
            return null;
        }
    }

    /** 拼 Civitai 模型页地址（拿不到模型 id 就给空串：宁可没有来源，也不编一个地址）。 */
    public static String sourceUrl(String baseUrl, long modelId, long versionId) {
        if (modelId <= 0) return "";
        String base = blank(baseUrl);
        if (base.isEmpty()) base = CivitaiClient.DEFAULT_BASE_URL;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/models/" + modelId + (versionId > 0 ? "?modelVersionId=" + versionId : "");
    }

    /** 存储键：本机 LoRA 文件名去掉目录与 .safetensors（和 data/civitai/<文件名>.json 同一套键）。 */
    public static String key(String name) {
        String value = blank(name).replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1);
        return value.replaceFirst("(?i)\\.safetensors$", "").strip();
    }

    /**
     * Civitai 的 Base Model 原样字符串（例如 {@code SD 1.5} / {@code SDXL 1.0} / {@code Illustrious} /
     * {@code NoobAI} / {@code Pony} / {@code Flux.1 D}）。下载链路上的"未知/未标注"占位不算值，按空处理。
     */
    public static String baseModel(String value) {
        String text = blank(value);
        if (text.equals("未知") || text.equals("未标注") || text.equalsIgnoreCase("unknown")) return "";
        return text;
    }

    // ---- 读写 ----

    private Snapshot read() {
        corrupt = false;
        skipped = 0;
        LinkedHashMap<String, Trigger> entries = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return new Snapshot(entries, false);
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception error) {
            corrupt = true;
            Log.warn("读取 LoRA 触发词存储失败（按没有记录处理）：" + error(error));
            return new Snapshot(entries, true);
        }
        // 空文件 = 还没写过任何东西，不算坏。
        if (text.replaceFirst("^\\uFEFF", "").isBlank()) return new Snapshot(entries, false);
        JsonObject data;
        try {
            JsonElement parsed = JsonParser.parseString(text.replaceFirst("^\\uFEFF", ""));
            if (parsed == null || !parsed.isJsonObject()) {
                corrupt = true;
                Log.warn("LoRA 触发词存储不是 JSON 对象（按没有记录处理）：" + file);
                return new Snapshot(entries, true);
            }
            data = parsed.getAsJsonObject();
        } catch (Exception error) {
            corrupt = true;
            Log.warn("LoRA 触发词存储不是有效 JSON（按没有记录处理）：" + file + "：" + error(error));
            return new Snapshot(entries, true);
        }
        JsonElement triggers = data.get("triggers");
        if (triggers == null) {
            corrupt = true;
            Log.warn("LoRA 触发词存储缺少 triggers 字段（按没有记录处理，写入前会先备份原件）：" + file);
            return new Snapshot(entries, true);
        }
        if (triggers.isJsonNull()) return new Snapshot(entries, false);
        if (!triggers.isJsonObject()) {
            corrupt = true;
            Log.warn("LoRA 触发词存储的 triggers 不是对象（按没有记录处理）：" + file);
            return new Snapshot(entries, true);
        }
        for (Map.Entry<String, JsonElement> entry : triggers.getAsJsonObject().entrySet()) {
            Trigger trigger = parse(entry.getKey(), entry.getValue());
            if (trigger == null) skipped++;
            else entries.put(entry.getKey(), trigger);
        }
        return new Snapshot(entries, false);
    }

    /** 一条记录：条目类型不对、缺 trainedWords、trainedWords 不是数组 → 按"没有记录"处理（返回 null）。 */
    private Trigger parse(String key, JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            Log.warn("LoRA 触发词存储里的条目不是对象，按没有记录处理：" + key);
            return null;
        }
        JsonObject row = value.getAsJsonObject();
        JsonElement words = row.get("trainedWords");
        if (words == null || !words.isJsonArray()) {
            Log.warn("LoRA 触发词存储里的条目缺少 trainedWords 数组，按没有记录处理：" + key);
            return null;
        }
        List<String> list = new ArrayList<>();
        for (JsonElement word : words.getAsJsonArray()) {
            if (word != null && word.isJsonPrimitive() && word.getAsJsonPrimitive().isString()) list.add(word.getAsString());
            else {
                skipped++;
                Log.warn("LoRA 触发词存储里的触发词不是字符串，已跳过这一条：" + key);
            }
        }
        String name = Json.str(row, "loraName", key);
        return new Trigger(name.isBlank() ? key : name, Json.str(row, "fileName", name + ".safetensors"), list,
                Json.str(row, "sourceUrl", ""), number(row.get("civitaiModelId")), number(row.get("civitaiVersionId")),
                baseModel(Json.str(row, "baseModel", "")), Json.str(row, "stack", ""),
                Json.str(row, "recordedAt", ""), Json.str(row, "source", ""));
    }

    private void write(Map<String, Trigger> entries) throws IOException {
        JsonObject data = new JsonObject();
        data.addProperty("version", FORMAT_VERSION);
        data.addProperty("updatedAt", Instant.now().toString());
        data.addProperty("note", "本机 LoRA 触发词（Civitai trained words）记录：键 = LoRA 文件名去掉 .safetensors；"
                + "trainedWords 原样保留顺序、大小写与空格，Civitai 没给就是空数组。");
        JsonObject triggers = new JsonObject();
        for (Map.Entry<String, Trigger> entry : entries.entrySet()) triggers.add(entry.getKey(), toJson(entry.getValue()));
        data.add("triggers", triggers);
        // 显式用 "\n"：Json.atomicWrite 走的是平台换行（Windows 上是 CRLF），这份存储要求纯 LF。
        Json.atomicWriteText(file, Json.GSON.toJson(data) + "\n");
    }

    private static JsonObject toJson(Trigger trigger) {
        JsonObject row = new JsonObject();
        row.addProperty("loraName", trigger.loraName());
        row.addProperty("fileName", trigger.fileName());
        JsonArray words = new JsonArray();
        for (String word : trigger.trainedWords()) words.add(word);
        row.add("trainedWords", words);
        row.addProperty("sourceUrl", trigger.sourceUrl());
        if (trigger.civitaiModelId() > 0) row.addProperty("civitaiModelId", trigger.civitaiModelId());
        else row.add("civitaiModelId", JsonNull.INSTANCE);
        if (trigger.civitaiVersionId() > 0) row.addProperty("civitaiVersionId", trigger.civitaiVersionId());
        else row.add("civitaiVersionId", JsonNull.INSTANCE);
        row.addProperty("baseModel", trigger.baseModel());
        row.addProperty("stack", trigger.stack());
        row.addProperty("recordedAt", trigger.recordedAt());
        row.addProperty("source", trigger.source());
        return row;
    }

    /** 坏文件不静默覆盖：先把原始字节另存一份（带时间戳），再重建存储。 */
    private void preserveCorrupt() {
        try {
            Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + STAMP.format(Instant.now()));
            Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
            Log.warn("LoRA 触发词存储损坏，原件已备份为 " + backup.getFileName() + " 后重建（内容没有丢）。");
        } catch (Exception error) {
            Log.warn("LoRA 触发词存储损坏且备份失败（不覆盖原件）：" + error(error));
            throw new IllegalStateException("LoRA 触发词存储损坏且无法备份，拒绝写入以免覆盖原始文件。", error);
        }
    }

    // ---- 小工具 ----

    private static List<String> strings(JsonElement value) {
        List<String> list = new ArrayList<>();
        if (value == null || !value.isJsonArray()) return list;
        for (JsonElement word : value.getAsJsonArray())
            if (word != null && word.isJsonPrimitive() && word.getAsJsonPrimitive().isString()) list.add(word.getAsString());
        return list;
    }

    private static long number(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return 0;
        try { return value.getAsLong(); } catch (Exception error) { return 0; }
    }

    /** 宽松匹配用：忽略大小写、空格、下划线、连字符、点等分隔符（中日文照旧保留）。 */
    public static String looseKey(String value) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char letter = value.charAt(index);
            if (Character.isLetterOrDigit(letter)) text.append(Character.toLowerCase(letter));
        }
        return text.toString();
    }

    private static String blank(String value) { return value == null ? "" : value.strip(); }

    private static String error(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
