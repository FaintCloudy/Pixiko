package cn.szu.bot.prompt;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.UnaryOperator;
import cn.szu.bot.Json;
import cn.szu.bot.sd.GenerationPreset;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.UserPromptStore;

/**
 * Named prompt sets with exact inserted-token ownership and a recoverable write-ahead journal.
 * Definitions are shared, while the "active" ownership maps are stored per scope (per user), so one
 * person's load/remove can never erase another person's prompt terms.
 */
public final class PromptFunctions {
    public record Pair(String positive, String negative) {}
    public record Change(SdClient.Prompts prompts, Pair changed) {}
    /** Reads the prompt pair a function operation works on. */
    public interface Reader { SdClient.Prompts read() throws Exception; }
    /** Applies a prompt change; the supplied transform journals its pending state before writing. */
    public interface Writer { SdClient.Prompts write(UnaryOperator<SdClient.Prompts> transform) throws Exception; }
    /** The shared WebUI prompt copy, used by the legacy signatures and by single-user setups. */
    public static final String DEFAULT_SCOPE = "default";
    private final Path file;
    private JsonObject state;
    private Pair lastChanged = new Pair("", "");

    public PromptFunctions(Path root) throws IOException {
        file = root.resolve("data/sd-functions.json");
        if (Files.exists(file)) {
            try {
                state = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
                if (state.get("version").getAsInt() != 1) throw new IOException("Unknown version");
                for (String map : List.of("definitions", "active")) {
                    if (!state.has(map) || !state.get(map).isJsonObject()) throw new IOException("Missing map");
                    for (var entry : state.getAsJsonObject(map).entrySet()) { GenerationPreset.Store.name(entry.getKey()); pairs(entry.getValue()); }
                }
                if (state.has("pending")) scopes(state.getAsJsonObject("pending"));
            } catch (Exception e) { throw new IOException("无法读取 data/sd-functions.json，原文件未修改。", e); }
        } else {
            state = new JsonObject(); state.addProperty("version", 1);
            state.add("definitions", new JsonObject()); state.add("active", new JsonObject());
        }
    }
    /** Accepts both the per-scope shape and the pre-per-user shape (a flat name→pair map). */
    private static void pairs(JsonElement value) throws IOException {
        JsonObject object = value.getAsJsonObject();
        if (object.has("positive") || object.has("negative")) { pair(object); return; }
        for (var entry : object.entrySet()) pair(entry.getValue());
    }
    private static void scopes(JsonObject pending) throws IOException {
        if (pending.has("before") || pending.has("after") || pending.has("active")) {
            for (String field : List.of("before", "after", "active")) if (pending.has(field)) pairs(pending.get(field));
            return;
        }
        for (var entry : pending.entrySet()) pairs(entry.getValue());
    }
    private static Pair pair(JsonElement value) throws IOException {
        try {
            JsonObject object = value.getAsJsonObject();
            for (String field : List.of("positive", "negative"))
                if (!object.get(field).isJsonPrimitive() || !object.getAsJsonPrimitive(field).isString()) throw new IOException("Invalid string");
            return new Pair(object.get("positive").getAsString(), object.get("negative").getAsString());
        } catch (Exception e) { throw new IOException("提示词集记录格式无效。", e); }
    }
    private static JsonObject json(Pair value) {
        JsonObject result = new JsonObject(); result.addProperty("positive", value.positive()); result.addProperty("negative", value.negative()); return result;
    }
    private void write(JsonObject next) throws IOException { Json.atomicWrite(file, next); state = next; }
    private static boolean isLegacy(JsonObject value) {
        if (value.isEmpty()) return false;
        for (var entry : value.entrySet()) {
            JsonObject item = entry.getValue().isJsonObject() ? entry.getValue().getAsJsonObject() : new JsonObject();
            return item.has("positive") || item.has("negative");
        }
        return false;
    }
    /** Reads one scope's map, returning an empty object when that scope (or the container) has none yet. */
    private JsonObject scopeMap(JsonObject container, String scope) {
        if (container == null) return new JsonObject();
        JsonElement value = container.get(scope);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }
    /** One-time move of the pre-per-user maps into the owner's scope. */
    public void migrateLegacy(String scope) throws IOException {
        JsonObject next = state.deepCopy(); boolean changed = false;
        JsonObject active = next.getAsJsonObject("active");
        if (isLegacy(active)) { JsonObject wrapped = new JsonObject(); wrapped.add(scope, active); next.add("active", wrapped); changed = true; }
        if (next.has("pending") && next.get("pending").isJsonObject()) {
            JsonObject pending = next.getAsJsonObject("pending");
            if (pending.has("before") || pending.has("after") || pending.has("active")) {
                JsonObject wrapped = new JsonObject(); wrapped.add(scope, pending); next.add("pending", wrapped); changed = true;
            }
        }
        if (changed) write(next);
    }
    public List<String> names() { return List.copyOf(state.getAsJsonObject("definitions").keySet()); }
    /** Names loaded in one scope. */
    public List<String> active(String scope) { return List.copyOf(scopeMap(state.getAsJsonObject("active"), scope).keySet()); }
    /** Names loaded in any scope (used for shared-definition safety checks and the legacy API). */
    public List<String> active() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (var entry : state.getAsJsonObject("active").entrySet()) names.addAll(scopeMap(state.getAsJsonObject("active"), entry.getKey()).keySet());
        return List.copyOf(names);
    }
    public Pair get(String name) throws IOException {
        name = GenerationPreset.Store.name(name);
        JsonElement value = state.getAsJsonObject("definitions").get(name);
        if (value == null) throw new IOException("提示词集不存在；请用 .function list 查看。");
        return pair(value);
    }
    public void save(String name, SdClient.Prompts prompts, boolean overwrite) throws IOException {
        name = GenerationPreset.Store.name(name);
        if (hasPending()) throw new IOException("有待核对的提示词集操作，请先 .function active。");
        if (active().contains(name)) throw new IOException("该提示词集正在使用，请先 .function remove " + name);
        if (!overwrite && state.getAsJsonObject("definitions").has(name)) throw new IOException("同名提示词集已存在；覆盖请用 .function overwrite " + name);
        List<String> positive = PromptEditor.parts(prompts.positive()), negative = PromptEditor.parts(prompts.negative());
        if (positive.isEmpty() && negative.isEmpty()) throw new IOException("正反向提示词均为空，未保存提示词集。");
        JsonObject next = state.deepCopy(); next.getAsJsonObject("definitions").add(name, json(new Pair(String.join(", ", positive), String.join(", ", negative)))); write(next);
    }
    /** Renames one shared definition and carries every scope's ownership entry to the new name. */
    public void rename(String oldName, String newName, boolean overwrite) throws IOException {
        String from = GenerationPreset.Store.name(oldName), to = GenerationPreset.Store.name(newName);
        JsonObject definitions = state.getAsJsonObject("definitions");
        if (!definitions.has(from)) throw new IOException("提示词集不存在；请用 /function list 查看。");
        if (from.equals(to)) return;
        if (hasPending()) throw new IOException("有待核对的提示词集操作，请先 /function active。");
        if (definitions.has(to) && !overwrite) throw new IOException("同名提示词集已存在；覆盖请用 /function rename overwrite <旧名称> <新名称>。");
        JsonObject next = state.deepCopy();
        JsonObject nextDefinitions = next.getAsJsonObject("definitions");
        nextDefinitions.add(to, nextDefinitions.remove(from));
        JsonObject active = next.getAsJsonObject("active");
        for (String scope : List.copyOf(active.keySet())) {
            JsonObject scoped = active.getAsJsonObject(scope);
            if (!scoped.has(from)) continue;
            JsonElement owned = scoped.remove(from);
            if (!scoped.has(to)) scoped.add(to, owned);
        }
        write(next);
    }
    public void delete(String name) throws IOException {
        name = GenerationPreset.Store.name(name); get(name);
        if (hasPending() || active().contains(name)) throw new IOException("提示词集正在使用或待核对，请先 .function remove " + name);
        JsonObject next = state.deepCopy(); next.getAsJsonObject("definitions").remove(name); write(next);
    }
    private boolean hasPending() { return state.has("pending") && !state.getAsJsonObject("pending").entrySet().isEmpty(); }
    private JsonObject pendingFor(String scope) { return scopeMap(state.getAsJsonObject("pending"), scope); }
    /** Resolve an interrupted operation before touching either ownership or prompt text again. */
    public void recover(Reader reader, String scope) throws Exception {
        JsonObject pending = pendingFor(scope);
        if (pending.size() == 0) return;
        SdClient.Prompts current = reader.read(); Pair actual = new Pair(current.positive(), current.negative());
        JsonObject next = state.deepCopy();
        JsonObject target = scopeMap(next.getAsJsonObject("active"), scope);
        if (actual.equals(pair(pending.get("after")))) next.getAsJsonObject("active").add(scope, pending.get("active").deepCopy());
        else if (!actual.equals(pair(pending.get("before"))))
            throw new IOException("上次提示词集操作未确认，当前提示词又被修改；请核对后用 .function reset 清除关联记录（不删除提示词）。");
        else if (target.size() == 0) next.getAsJsonObject("active").remove(scope);
        JsonObject pendings = next.getAsJsonObject("pending"); pendings.remove(scope);
        if (pendings.isEmpty()) next.remove("pending");
        write(next);
    }
    /** Legacy shared-copy recovery. */
    public void recover(SdClient sd) throws Exception { recover(sd::prompts, DEFAULT_SCOPE); }
    public void reset(String scope) throws IOException {
        JsonObject next = state.deepCopy();
        next.getAsJsonObject("active").remove(scope);
        if (next.has("pending")) {
            JsonObject pendings = next.getAsJsonObject("pending"); pendings.remove(scope);
            if (pendings.isEmpty()) next.remove("pending");
        }
        write(next);
    }
    public void reset() throws IOException { resetAll(); }
    private void resetAll() throws IOException {
        JsonObject next = state.deepCopy(); next.remove("pending"); next.add("active", new JsonObject()); write(next);
    }
    public Change load(Reader reader, Writer writer, String scope, String requested) throws Exception {
        String name = GenerationPreset.Store.name(requested); recover(reader, scope); Pair template = get(name);
        if (active(scope).contains(name)) throw new IOException("该提示词集已加载；先 .function remove " + name + " 可整组移出。");
        return apply(reader, writer, scope, name, template, false);
    }
    public Change remove(Reader reader, Writer writer, String scope, String requested) throws Exception {
        recover(reader, scope);
        String name = requested == null ? null : GenerationPreset.Store.name(requested);
        if (name != null && !active(scope).contains(name)) throw new IOException("该提示词集尚未加载；.function active 查看已加载集合。");
        return apply(reader, writer, scope, name, null, true);
    }
    public Change load(SdClient sd, String requested) throws Exception { return load(sd::prompts, sd::transformPrompts, DEFAULT_SCOPE, requested); }
    public Change remove(SdClient sd, String requested) throws Exception { return remove(sd::prompts, sd::transformPrompts, DEFAULT_SCOPE, requested); }
    /** Personal prompt copies are isolated per user, so function operations follow the same scope. */
    public Change load(UserPromptStore store, String scope, String requested) throws Exception {
        return load(() -> store.prompts(scope), transform -> store.replace(scope, transform.apply(store.prompts(scope))), scope, requested);
    }
    public Change remove(UserPromptStore store, String scope, String requested) throws Exception {
        return remove(() -> store.prompts(scope), transform -> store.replace(scope, transform.apply(store.prompts(scope))), scope, requested);
    }
    private Change apply(Reader reader, Writer writer, String scope, String name, Pair template, boolean remove) throws Exception {
        JsonObject originalActive = scopeMap(state.getAsJsonObject("active"), scope).deepCopy();
        SdClient.Prompts result;
        try {
            result = writer.write(before -> {
                try {
                    JsonObject nextActive = originalActive.deepCopy();
                    List<String> positive = new ArrayList<>(PromptEditor.parts(before.positive()));
                    List<String> negative = new ArrayList<>(PromptEditor.parts(before.negative()));
                    // Forget ownership of any tokens already removed or edited outside this operation.
                    for (String key : new ArrayList<>(nextActive.keySet())) {
                        Pair owned = pair(nextActive.get(key));
                        nextActive.add(key, json(new Pair(retained(owned.positive(), positive), retained(owned.negative(), negative))));
                    }
                    List<String> changedPositive = new ArrayList<>(), changedNegative = new ArrayList<>();
                    if (remove) {
                        JsonObject removed = new JsonObject();
                        if (name == null) { removed = nextActive; nextActive = new JsonObject(); }
                        else removed.add(name, nextActive.remove(name));
                        erase(positive, owned(removed, true), owned(nextActive, true), changedPositive);
                        erase(negative, owned(removed, false), owned(nextActive, false), changedNegative);
                    } else {
                        List<String> posOwned = append(positive, PromptEditor.parts(template.positive()), owned(nextActive, true), changedPositive);
                        List<String> negOwned = append(negative, PromptEditor.parts(template.negative()), owned(nextActive, false), changedNegative);
                        nextActive.add(name, json(new Pair(String.join(", ", posOwned), String.join(", ", negOwned))));
                    }
                    Pair after = new Pair(String.join(", ", positive), String.join(", ", negative));
                    JsonObject pending = new JsonObject(); pending.add("before", json(new Pair(before.positive(), before.negative())));
                    pending.add("after", json(after)); pending.add("active", nextActive);
                    JsonObject journal = state.deepCopy();
                    if (!journal.has("pending") || !journal.get("pending").isJsonObject()) journal.add("pending", new JsonObject());
                    journal.getAsJsonObject("pending").add(scope, pending); write(journal);
                    lastChanged = new Pair(String.join(", ", changedPositive), String.join(", ", changedNegative));
                    return new SdClient.Prompts(after.positive(), after.negative(), before.source());
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
        } catch (UncheckedIOException e) { throw e.getCause(); }
        JsonObject next = state.deepCopy();
        JsonObject committed = next.getAsJsonObject("pending").getAsJsonObject(scope).getAsJsonObject("active").deepCopy();
        next.getAsJsonObject("active").add(scope, committed);
        JsonObject pendings = next.getAsJsonObject("pending"); pendings.remove(scope);
        if (pendings.isEmpty()) next.remove("pending");
        try { write(next); }
        catch (IOException e) { throw new IOException("提示词已更新，但提示词集关联保存未完成；已保留恢复记录，下次 .function active 会核对。", e); }
        return new Change(result, lastChanged);
    }
    private static String retained(String text, List<String> current) {
        return String.join(", ", PromptEditor.parts(text).stream().filter(current::contains).toList());
    }
    private static Set<String> owned(JsonObject active, boolean positive) throws IOException {
        Set<String> result = new LinkedHashSet<>();
        for (var value : active.entrySet()) { Pair p = pair(value.getValue()); result.addAll(PromptEditor.parts(positive ? p.positive() : p.negative())); }
        return result;
    }
    private static List<String> append(List<String> current, List<String> incoming, Set<String> owned, List<String> added) {
        List<String> result = new ArrayList<>();
        for (String term : incoming) {
            String existing = current.stream().filter(value -> PromptEditor.key(value).equals(PromptEditor.key(term))).findFirst().orElse(null);
            if (existing == null) { current.add(term); added.add(term); result.add(term); }
            else if (owned.contains(existing) || result.contains(existing)) result.add(existing);
        }
        return List.copyOf(new LinkedHashSet<>(result));
    }
    private static void erase(List<String> current, Set<String> removing, Set<String> retained, List<String> removed) {
        for (String term : removing) if (!retained.contains(term) && current.remove(term)) removed.add(term);
    }
    /** Manual edits relinquish ownership so later remove cannot erase newly authored replacement text. */
    public void forget(String scope, boolean negative, List<String> removed, boolean wholeField) throws IOException {
        if (!Files.exists(file)) return;
        if (hasPending()) throw new IOException("提示词已修改，但集合操作待核对；请 .function active 或 .function reset。");
        Set<String> keys = new HashSet<>(); removed.forEach(value -> keys.add(PromptEditor.key(value)));
        JsonObject next = state.deepCopy();
        JsonObject active = scopeMap(next.getAsJsonObject("active"), scope);
        for (String name : List.copyOf(active.keySet())) {
            Pair old = pair(active.get(name));
            String original = negative ? old.negative() : old.positive();
            String updated = wholeField ? "" : String.join(", ", PromptEditor.parts(original).stream().filter(value -> !keys.contains(PromptEditor.key(value))).toList());
            active.add(name, json(new Pair(negative ? old.positive() : updated, negative ? updated : old.negative())));
        }
        write(next);
    }
    public void forget(boolean negative, List<String> removed, boolean wholeField) throws IOException {
        forget(DEFAULT_SCOPE, negative, removed, wholeField);
    }
}
