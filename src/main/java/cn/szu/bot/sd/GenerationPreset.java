package cn.szu.bot.sd;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import cn.szu.bot.Json;

/** A parameter preset deliberately excludes prompt text and prompt-style selection. */
public record GenerationPreset(String sampler, int width, int height, GenerationParameters parameters) {
    public GenerationPreset {
        if (sampler == null || sampler.isBlank() || width < 64 || width > 2048 || height < 64 || height > 2048
                || width % 8 != 0 || height % 8 != 0 || parameters == null || parameters.checkpoint().isBlank())
            throw new IllegalArgumentException("预设参数无效，必须包含有效尺寸、采样方法和明确的基础模型。");
    }
    public String describe() {
        return "图片尺寸：" + width + " × " + height + " 像素\n采样方法：" + sampler + "\n" + parameters.describe();
    }
    public JsonObject json() {
        JsonObject result = parameters.json();
        result.addProperty("sampler_name", sampler); result.addProperty("width", width); result.addProperty("height", height);
        return result;
    }
    public static GenerationPreset read(JsonObject value) throws IOException {
        try {
            return new GenerationPreset(value.get("sampler_name").getAsString(), value.get("width").getAsBigDecimal().intValueExact(),
                    value.get("height").getAsBigDecimal().intValueExact(), GenerationParameters.read(value));
        } catch (Exception e) { throw new IOException("参数预设文件包含无效参数，请检查 data/sd-presets.json。", e); }
    }

    public static final class Store {
        private final Path path;
        private final LinkedHashMap<String, GenerationPreset> values = new LinkedHashMap<>();
        public Store(Path root) throws IOException {
            path = root.resolve("data/sd-presets.json");
            if (!Files.exists(path)) return;
            try {
                JsonObject saved = Json.parse(Files.readString(path, StandardCharsets.UTF_8));
                if (saved.get("version").getAsInt() != 1) throw new IOException("Unsupported version");
                for (var entry : saved.getAsJsonObject("presets").entrySet())
                    values.put(name(entry.getKey()), read(entry.getValue().getAsJsonObject()));
            } catch (Exception e) { throw new IOException("无法读取 data/sd-presets.json，原文件未修改。", e); }
        }
        public List<String> names() { return List.copyOf(values.keySet()); }
        public GenerationPreset get(String name) throws IOException {
            GenerationPreset result = values.get(name(name));
            if (result == null) throw new IOException("参数预设不存在；请用 .preset list 查看。");
            return result;
        }
        public void save(String name, SdClient.GenerationRequest snapshot, boolean overwrite) throws IOException {
            name = name(name);
            if (!overwrite && values.containsKey(name)) throw new IOException("同名参数预设已存在；覆盖请用 .preset overwrite " + name);
            if (snapshot.parameters() == null || snapshot.parameters().checkpoint().isBlank())
                throw new IOException("未读取到明确的基础模型，预设未保存；请启动 SD 或用 .model set <完整名称> 指定模型。");
            var basic = snapshot.settings();
            var updated = new LinkedHashMap<>(values);
            updated.put(name, new GenerationPreset(basic.samplerName(), basic.width(), basic.height(), snapshot.parameters()));
            persist(updated);
        }
        public void remove(String name) throws IOException {
            name = name(name); get(name);
            var updated = new LinkedHashMap<>(values); updated.remove(name); persist(updated);
        }
        private void persist(LinkedHashMap<String, GenerationPreset> next) throws IOException {
            JsonObject state = new JsonObject(), records = new JsonObject(); state.addProperty("version", 1);
            next.forEach((key, value) -> records.add(key, value.json())); state.add("presets", records);
            Json.atomicWrite(path, state); values.clear(); values.putAll(next);
        }
        public static String name(String name) throws IOException {
            if (name == null || name.isBlank() || name.length() > 200 || name.codePoints().anyMatch(Character::isISOControl))
                throw new IOException("参数预设名称须为 1–200 个字符且不能包含控制字符。");
            return name.strip();
        }
    }
}
