package cn.szu.bot.sd;

import com.google.gson.*;
import java.io.IOException;
import cn.szu.bot.Bot;
import cn.szu.bot.Json;

/** Bot generation values, separate from prompt styles and the browser's prompt state. */
public record GenerationParameters(int steps, double cfgScale, long seed, String checkpoint) {
    public GenerationParameters {
        if (steps < 1 || steps > 1000) throw new IllegalArgumentException("迭代步数须为 1–1000 的整数。");
        if (!Double.isFinite(cfgScale) || cfgScale < 0 || cfgScale > 100)
            throw new IllegalArgumentException("CFG 须为 0–100 的数字。");
        if (seed < -1) throw new IllegalArgumentException("种子须为 -1（随机）或非负整数。");
        if (checkpoint == null || checkpoint.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("基础模型名称无效。");
        checkpoint = checkpoint.strip();
    }
    public JsonObject json() {
        JsonObject value = new JsonObject();
        value.addProperty("steps", steps); value.addProperty("cfg_scale", cfgScale);
        value.addProperty("seed", seed); value.addProperty("checkpoint", checkpoint);
        return value;
    }
    public static GenerationParameters read(JsonObject value) throws IOException {
        try {
            return new GenerationParameters(value.get("steps").getAsBigDecimal().intValueExact(),
                    value.get("cfg_scale").getAsDouble(), value.get("seed").getAsBigDecimal().longValueExact(),
                    value.get("checkpoint").getAsString());
        } catch (Exception e) { throw new IOException("生成参数记录无效（steps/cfg_scale/seed/checkpoint）。", e); }
    }
    public String describe() {
        return "迭代步数：" + steps + "\nCFG：" + cfgScale + "\n种子：" + seed
                + "\n基础模型：" + (checkpoint.isBlank() ? "跟随 WebUI 当前模型" : checkpoint);
    }
}
