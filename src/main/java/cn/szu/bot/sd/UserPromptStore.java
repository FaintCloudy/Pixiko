package cn.szu.bot.sd;

import cn.szu.bot.Json;
import cn.szu.bot.Log;
import cn.szu.bot.prompt.PromptEditor;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Per-user prompt copies. Every scope (a QQ number) owns one positive/negative pair plus its own undo
 * history under {@code data/prompts/}; nothing is shared between users, and the WebUI page copy owned by
 * {@link SdClient} is never overwritten by these commands.
 *
 * <p>历史是一个栈（最多 {@link #MAX_HISTORY} 步）：每次修改压入修改前的状态，".prompt undo" 弹出栈顶，
 * 所以可以连续回退多步；被弹出的状态不会再入栈，因此不会来回互换。
 */
public final class UserPromptStore {
    public static final String PERSONAL_SOURCE = "个人 prompt（仅本人可见，已持久化）";
    /** 最多保留多少步可回退历史。 */
    public static final int MAX_HISTORY = 20;
    /** 一次回退的结果：恢复后的提示词 + 还剩多少步可回退。 */
    public record Undo(SdClient.Prompts prompts, int remaining) {}
    private final Path directory;

    public UserPromptStore(Path root) { this.directory = root.toAbsolutePath().normalize().resolve("data/prompts"); }

    /** QQ numbers become file names; non-QQ identities (web console, eval scopes) keep their own name. */
    public static String scopeOf(String userId) {
        if (userId == null) return "default";
        String value = userId.strip();
        if (value.matches("[1-9][0-9]{0,19}")) return value;
        // 非 QQ 身份（网页控制台 web、临时评测 scope）原样保留，避免全都挤进 default 互相覆盖。
        if (value.matches("[A-Za-z][A-Za-z0-9_-]{0,31}")) return value;
        return "default";
    }

    public SdClient.Prompts prompts(String scope) throws IOException {
        Path file = file(scope);
        if (!Files.isRegularFile(file)) return new SdClient.Prompts("", "", PERSONAL_SOURCE);
        try {
            JsonObject saved = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (saved.get("version").getAsInt() != 1) throw new IOException("Unknown version");
            return new SdClient.Prompts(string(saved, "positive"), string(saved, "negative"), PERSONAL_SOURCE);
        } catch (Exception e) {
            throw new IOException("无法读取个人 prompt（" + file.getFileName() + "）；本次未修改。", e);
        }
    }

    public boolean exists(String scope) { return Files.isRegularFile(file(scope)); }

    /** Writes the bootstrap copy once, so the operator keeps the prompt that predates per-user storage. */
    public void seed(String scope, SdClient.Prompts seed) throws IOException {
        if (exists(scope) || seed == null) return;
        if (seed.positive().isBlank() && seed.negative().isBlank()) return;
        persist(scope, new SdClient.Prompts(seed.positive(), seed.negative(), PERSONAL_SOURCE));
        Log.info("已为 scope " + scope + " 建立个人 prompt 副本（来自原有共享 prompt）");
    }

    /** Vocabulary for add/remove completion: the standard dictionary plus any extra (style) terms. */
    public List<String> vocabulary(List<String> extra) throws IOException {
        List<String> terms = new ArrayList<>();
        Path dictionary = directory.getParent().resolve("prompt-tags.txt");
        if (Files.isRegularFile(dictionary)) {
            if (Files.size(dictionary) > 16L * 1024 * 1024) throw new IOException("prompt-tags.txt 超过 16MB。");
            for (String line : Files.readAllLines(dictionary, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.strip().startsWith("#")) continue;
                try { terms.addAll(PromptEditor.parts(line)); } catch (IllegalArgumentException ignored) { /* one malformed line */ }
            }
        }
        if (extra != null) for (String value : extra) {
            if (value == null || value.isBlank()) continue;
            try { terms.addAll(PromptEditor.parts(value)); } catch (IllegalArgumentException ignored) { /* unrelated malformed text */ }
        }
        return terms;
    }

    public SdClient.PromptChange change(String scope, boolean negative, String operation, String text, List<String> vocabulary) throws IOException {
        if (!Set.of("add", "remove", "edit").contains(operation)) throw new IOException("未知提示词操作：" + operation);
        if (!operation.equals("edit") && text.isBlank()) throw new IOException("add/remove 后必须提供提示词。");
        SdClient.Prompts current = prompts(scope);
        String before = negative ? current.negative() : current.positive();
        PromptEditor.Result result = operation.equals("edit")
                ? new PromptEditor.Result(text, List.of(), List.of())
                : PromptEditor.apply(before, operation, text, vocabulary);
        String after = result.text();
        SdClient.Prompts updated = new SdClient.Prompts(negative ? current.positive() : after,
                negative ? after : current.negative(), PERSONAL_SOURCE);
        replace(scope, updated);
        return new SdClient.PromptChange(updated, result.changed(), result.unchanged());
    }

    /** Rewrites <lora:old:...> into <lora:new:...> inside this scope's positive prompt. */
    public SdClient.Prompts renameLoraTag(String scope, String oldName, String newName) throws IOException {
        SdClient.Prompts current = prompts(scope);
        String pattern = "(?i)<lora:" + java.util.regex.Pattern.quote(oldName) + ":";
        String positive = current.positive().replaceAll(pattern, "<lora:" + newName.replace("$", "\\$") + ":");
        if (positive.equals(current.positive())) return current;
        return replace(scope, new SdClient.Prompts(positive, current.negative(), PERSONAL_SOURCE));
    }

    /** Adds or updates one LoRA tag inside this scope's positive prompt. */
    public SdClient.Prompts withLoraTag(String scope, String tag) throws IOException {
        SdClient.Prompts current = prompts(scope);
        String positive = SdClient.applyLoraTag(current.positive(), tag);
        if (positive.equals(current.positive())) return current;
        return replace(scope, new SdClient.Prompts(positive, current.negative(), PERSONAL_SOURCE));
    }

    /** Journals the current value as the undo target and stores the new one. */
    public SdClient.Prompts replace(String scope, SdClient.Prompts updated) throws IOException {
        SdClient.Prompts current = prompts(scope);
        remember(scope, current);
        persist(scope, updated);
        return updated;
    }

    /**
     * Restores the newest journaled pair and drops it, so repeated ".prompt undo" keeps walking back through
     * the history instead of swapping between two states. Identical states are skipped, so an edit that did
     * not actually change anything cannot swallow an undo.
     */
    public Undo undo(String scope) throws IOException {
        List<SdClient.Prompts> history = history(scope);
        if (history.isEmpty()) throw new IOException("没有可回退的上一次 prompt（已经回到最早的记录）。");
        SdClient.Prompts current = prompts(scope);
        SdClient.Prompts restored = null;
        while (!history.isEmpty()) {
            SdClient.Prompts candidate = history.remove(history.size() - 1);
            if (!same(candidate, current)) { restored = candidate; break; }
        }
        if (restored == null) {
            writeHistory(scope, history);
            throw new IOException("没有可回退的上一次 prompt（已经回到最早的记录）。");
        }
        persist(scope, restored);
        writeHistory(scope, history);
        return new Undo(restored, history.size());
    }

    /** 还能回退多少步。 */
    public int historyDepth(String scope) throws IOException { return history(scope).size(); }

    private static boolean same(SdClient.Prompts first, SdClient.Prompts second) {
        return first.positive().equals(second.positive()) && first.negative().equals(second.negative());
    }

    /** 读取回退栈（最新的在最后）；兼容旧版本的单步 .previous.json。 */
    private List<SdClient.Prompts> history(String scope) throws IOException {
        List<SdClient.Prompts> states = new ArrayList<>();
        Path file = historyFile(scope);
        if (Files.isRegularFile(file)) {
            try {
                JsonObject saved = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
                if (saved.get("version").getAsInt() != 1) throw new IOException("Unknown version");
                if (saved.has("states") && saved.get("states").isJsonArray())
                    for (com.google.gson.JsonElement item : saved.getAsJsonArray("states")) {
                        if (item == null || !item.isJsonObject()) continue;
                        JsonObject state = item.getAsJsonObject();
                        states.add(new SdClient.Prompts(string(state, "positive"), string(state, "negative"), PERSONAL_SOURCE));
                    }
            } catch (Exception e) {
                throw new IOException("无法读取个人 prompt 的回退记录；当前 prompt 未修改。", e);
            }
        }
        Path legacy = previousFile(scope);
        if (states.isEmpty() && Files.isRegularFile(legacy)) {
            try {
                JsonObject saved = Json.parse(Files.readString(legacy, StandardCharsets.UTF_8));
                states.add(new SdClient.Prompts(string(saved, "positive"), string(saved, "negative"), PERSONAL_SOURCE));
                Files.deleteIfExists(legacy);
            } catch (Exception ignored) { }
        }
        return states;
    }

    private void writeHistory(String scope, List<SdClient.Prompts> states) throws IOException {
        if (states.isEmpty()) { Files.deleteIfExists(historyFile(scope)); return; }
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (SdClient.Prompts state : states) {
            JsonObject item = new JsonObject();
            item.addProperty("positive", state.positive());
            item.addProperty("negative", state.negative());
            array.add(item);
        }
        JsonObject data = new JsonObject();
        data.addProperty("version", 1);
        data.addProperty("scope", scope);
        data.add("states", array);
        Json.atomicWrite(historyFile(scope), data);
    }

    private void remember(String scope, SdClient.Prompts before) throws IOException {
        List<SdClient.Prompts> states;
        try { states = history(scope); }
        catch (IOException error) { Log.warn("回退记录损坏，已重置：" + error.getMessage()); states = new ArrayList<>(); }
        if (!states.isEmpty() && same(states.get(states.size() - 1), before)) states.remove(states.size() - 1);
        states.add(before);
        while (states.size() > MAX_HISTORY) states.remove(0);
        writeHistory(scope, states);
    }

    private Path historyFile(String scope) { return directory.resolve(scopeOf(scope) + ".history.json"); }

    private void persist(String scope, SdClient.Prompts prompts) throws IOException {
        JsonObject state = new JsonObject();
        state.addProperty("version", 1);
        state.addProperty("scope", scope);
        state.addProperty("positive", prompts.positive());
        state.addProperty("negative", prompts.negative());
        state.addProperty("source", PERSONAL_SOURCE);
        state.addProperty("updated_at", Instant.now().toString());
        Json.atomicWrite(file(scope), state);
    }

    private Path file(String scope) { return directory.resolve(scopeOf(scope) + ".json"); }
    private Path previousFile(String scope) { return directory.resolve(scopeOf(scope) + ".previous.json"); }
    private static String string(JsonObject object, String key) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) throw new IOException("Invalid prompt field");
        return object.get(key).getAsString();
    }
}
