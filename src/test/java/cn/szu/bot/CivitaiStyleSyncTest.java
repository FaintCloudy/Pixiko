package cn.szu.bot;
import java.nio.file.*;
import java.util.*;
import java.io.IOException;
import cn.szu.bot.civitai.CivitaiClient;
import cn.szu.bot.civitai.CivitaiStyleSync;
import cn.szu.bot.sd.SdClient;
public final class CivitaiStyleSyncTest {
    public static void main(String[] args) throws Exception {
        try (var fixture = new GenerationPresetTest.Fixture()) {
            Map<String,SdClient.StylePrompt> values=new LinkedHashMap<>();
            values.put("model 1",new SdClient.StylePrompt("model 1","portrait, <lora:wrong:0.4>, <lora:other:2>","original negative"));
            values.put("model 2",new SdClient.StylePrompt("model 2","user unrelated","keep negative"));
            var download=new CivitaiClient.DownloadedLora("model","v1","",List.of(),fixture.root.resolve("real.safetensors"),true,1,2,
                    List.of(new CivitaiClient.ShowcasePrompt(1,"portrait, <lora:wrong:0.4>, <lora:other:2>","original negative",true,""),
                            new CivitaiClient.ShowcasePrompt(2,"landscape","bad",true,"")));
            CivitaiStyleSync.Store store=new CivitaiStyleSync.Store() {
                public List<SdClient.StylePrompt> list(){return List.copyOf(values.values());}
                public void save(String n,String p,String r,boolean overwrite){ assert overwrite==values.containsKey(n); values.put(n,new SdClient.StylePrompt(n,p,r)); }
            };
            String report=CivitaiStyleSync.sync(fixture.root,download,"<lora:Natsume_Ai_1_nai:1>",store,true);
            assert report.contains("修正 1") && report.contains("新增 1") : report;
            assert values.get("model 1").positive().equals("portrait, <lora:Natsume_Ai_1_nai:1>");
            assert values.get("model 1").negative().equals("original negative");
            assert values.get("model 2").positive().equals("user unrelated");
            assert values.get("model 3").positive().equals("landscape, <lora:Natsume_Ai_1_nai:1>");
            try(var backups=Files.list(fixture.root.resolve("data/civitai-style-backups"))){assert backups.count()==1;}
            assert CivitaiStyleSync.sync(fixture.root,download,"<lora:Natsume_Ai_1_nai:1>",store,true).contains("复用 2");
            values.put("model 1",new SdClient.StylePrompt("model 1","edited portrait, <lora:stale:1>","edited negative"));
            CivitaiStyleSync.sync(fixture.root,download,"<lora:Natsume_Ai_1_nai:0.8>",store,true);
            assert values.get("model 1").positive().equals("edited portrait, <lora:Natsume_Ai_1_nai:0.8>");
            assert values.get("model 1").negative().equals("edited negative");
            assert CivitaiStyleSync.correct("(blue eyes:1.2), <lora:bad:1>, , portrait","<lora:real:1>").equals("(blue eyes:1.2), portrait, <lora:real:1>");
        }
        System.out.println("CivitaiStyleSyncTest PASS: original migration, verified tag, duplicates removed, backups, collision protection, idempotency, edits retained.");
    }
}
