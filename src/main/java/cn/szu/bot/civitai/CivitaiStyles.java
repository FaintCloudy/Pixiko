package cn.szu.bot.civitai;

import java.util.*;
import cn.szu.bot.Bot;
import cn.szu.bot.sd.SdClient;

/** Imports API-visible showcase metadata without overwriting existing styles or editing live prompts. */
public final class CivitaiStyles {
    public interface Store {
        List<SdClient.StylePrompt> list() throws Exception;
        void save(String name, String positive, String negative) throws Exception;
    }
    public static String importAll(CivitaiClient.DownloadedLora download, SdClient client) {
        return importAll(download, new Store() {
            public List<SdClient.StylePrompt> list() throws Exception { return client.stylePrompts(); }
            public void save(String name, String positive, String negative) throws Exception {
                client.saveStylePair(name, positive, negative, false, "Civitai 展示图");
            }
        });
    }
    public static String importAll(CivitaiClient.DownloadedLora download, Store store) {
        if (download.showcases().isEmpty()) return "展示图样式：API 未返回展示图，未创建样式。";
        Map<String, SdClient.StylePrompt> catalog = new LinkedHashMap<>();
        try { if (download.showcases().stream().anyMatch(image -> image.skippedReason().isEmpty()))
            for (var style : store.list()) catalog.put(style.name(), style); }
        catch (Exception e) { return "展示图样式保存失败：" + Bot.error(e) + "\n提示词已保存在 data/civitai 的模型记录中，再次下载同一链接可复用模型并重试。"; }
        String prefix = download.modelName().replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", " ").strip();
        if (prefix.isEmpty()) prefix = "模型" + download.modelId();
        if (prefix.startsWith("#")) prefix = "模型 " + prefix;
        if (prefix.codePointCount(0, prefix.length()) > 180) prefix = prefix.substring(0, prefix.offsetByCodePoints(0, 180));
        int saved = 0, reused = 0, skipped = 0, failed = 0, noNegative = 0;
        List<String> details = new ArrayList<>();
        for (var image : download.showcases()) {
            if (!image.skippedReason().isEmpty()) { skipped++; details.add("展示图 " + image.number() + " 跳过：" + image.skippedReason()); continue; }
            int number = image.number();
            String name;
            while (true) {
                name = prefix + " " + number;
                var existing = catalog.get(name);
                if (existing == null || (existing.positive().equals(image.positive()) && existing.negative().equals(image.negative()))) break;
                number++;
            }
            try {
                if (catalog.containsKey(name)) reused++;
                else {
                    store.save(name, image.positive(), image.negative()); saved++;
                    catalog.put(name, new SdClient.StylePrompt(name, image.positive(), image.negative()));
                }
                if (!image.negativeProvided()) noNegative++;
                details.add("展示图 " + image.number() + " → " + name);
            } catch (Exception e) { failed++; details.add("展示图 " + image.number() + " 保存失败：" + Bot.error(e)); }
        }
        return "展示图样式：新增 " + saved + "，复用 " + reused + "，跳过 " + skipped + "，失败 " + failed + "。\n"
                + String.join("\n", details)
                + (noNegative == 0 ? "" : "\n其中 " + noNegative + " 张未提供反向提示词，对应样式反向栏保存为空。")
                + (saved + reused == 0 ? "" : "\n使用 .style list 查看，.style prompt <名称> 查看提示词，.style load <名称> 使用。")
                + (failed == 0 ? "" : "\n可再次下载同一链接重试；已校验模型会复用，已有样式不会覆盖。");
    }
}
