package cn.szu.bot;
import com.google.gson.*;
import java.nio.file.Path;
import java.util.*;
import java.io.IOException;
import cn.szu.bot.civitai.CivitaiClient;
import cn.szu.bot.civitai.CivitaiStyles;
import cn.szu.bot.sd.SdClient;
public final class CivitaiStylesTest {
    public static void main(String[] args) {
        JsonObject version = Json.parse("{\"images\":[{\"meta\":{\"prompt\":\"  Chinese 中文, \\\"quoted\\\"\\n<lora:Test:1>  \",\"negativePrompt\":\"bad\\nblur\"}},{\"meta\":null},{\"meta\":{\"prompt\":\"second\"}},{\"meta\":{\"negative_prompt\":\"negative only\"}},{\"meta\":{\"prompt\":\"\",\"negativePrompt\":\"\"}},{\"meta\":{\"prompt\":{\"bad\":1}}}]}");
        var images = CivitaiClient.showcasePrompts(version);
        assert images.size() == 6;
        assert images.get(0).positive().startsWith("  Chinese") && images.get(0).positive().endsWith("  ");
        assert images.get(0).negative().equals("bad\nblur");
        assert images.get(2).negative().isEmpty() && !images.get(2).negativeProvided();
        assert images.get(3).positive().isEmpty() && images.get(3).negativeProvided();
        assert !images.get(4).skippedReason().isEmpty() && !images.get(5).skippedReason().isEmpty();
        var download = new CivitaiClient.DownloadedLora("模型名", "version", "SDXL", List.of(), Path.of("model.safetensors"), false, 1, 2, images);
        FakeStore store = new FakeStore();
        store.entries.put("模型名 1", new SdClient.StylePrompt("模型名 1", "user's existing style", "untouched"));
        String report = CivitaiStyles.importAll(download, store);
        assert report.contains("新增 3") && report.contains("跳过 3") && report.contains("展示图 1 → 模型名 2") : report;
        assert store.entries.get("模型名 1").negative().equals("untouched");
        assert store.entries.get("模型名 2").positive().equals(images.get(0).positive());
        int saves = store.saves;
        report = CivitaiStyles.importAll(download, store);
        assert report.contains("复用 3") && store.saves == saves : report;
        FakeStore partial = new FakeStore(); partial.fail = "模型名 3";
        report = CivitaiStyles.importAll(download, partial);
        assert report.contains("失败 1") && partial.entries.containsKey("模型名 4") : report;
        partial.fail = "";
        assert CivitaiStyles.importAll(download, partial).contains("新增 1");
        FakeStore unavailable = new FakeStore(); unavailable.listFails = true;
        report = CivitaiStyles.importAll(download, unavailable);
        assert report.contains("保存失败") && report.contains("data/civitai") && unavailable.saves == 0;
        assert CivitaiStyles.importAll(new CivitaiClient.DownloadedLora("none", "", "", List.of(), Path.of("file"), false, 1, 2), store).contains("未返回展示图");
        System.out.println("CivitaiStylesTest PASS: all previews, raw prompt pair, missing metadata, numbering, collision protection, reuse, partial failure and retry.");
    }
    static class FakeStore implements CivitaiStyles.Store {
        Map<String, SdClient.StylePrompt> entries = new LinkedHashMap<>(); String fail = ""; boolean listFails; int saves;
        public List<SdClient.StylePrompt> list() throws Exception { if (listFails) throw new IOException("offline"); return List.copyOf(entries.values()); }
        public void save(String name, String positive, String negative) throws Exception {
            if (name.equals(fail)) throw new IOException("fixture failure");
            assert !entries.containsKey(name) : "must not overwrite";
            entries.put(name, new SdClient.StylePrompt(name, positive, negative)); saves++;
        }
    }
}
