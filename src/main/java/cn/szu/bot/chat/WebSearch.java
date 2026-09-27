package cn.szu.bot.chat;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import com.google.gson.*;
import cn.szu.bot.Json;
import cn.szu.bot.Settings;

/** Small, bounded web-grounding client. Search-page content is returned as untrusted text for the model. */
public final class WebSearch {
    private record Cached(long time,String text) {}
    private static final Map<String,Cached> CACHE=new LinkedHashMap<>(64,.75f,true);
    private static long lastRequest;
    private final Path root;
    public WebSearch(Path root) { this.root=root.toAbsolutePath().normalize(); }

    public String search(String query) throws Exception {
        query=query==null ? "" : query.replaceAll("[\\p{Cntrl}\\p{Cf}]"," ").replaceAll("\\s+"," ").strip();
        if(query.isEmpty() || query.length()>300) throw new IOException("联网检索词须为 1–300 个字符。");
        long now=System.currentTimeMillis();
        synchronized(WebSearch.class) {
            Cached cached=CACHE.get(query.toLowerCase(Locale.ROOT));
            if(cached!=null && now-cached.time()<Duration.ofMinutes(10).toMillis()) return cached.text();
            long wait=3000-(System.nanoTime()-lastRequest)/1_000_000L;
            if(wait>0) Thread.sleep(wait);
            lastRequest=System.nanoTime();
        }
        JsonObject settings=Json.parse(Files.readString(root.resolve("config.json")));
        String proxy=Json.str(Json.obj(settings,"chat"),"search_proxy_url",Json.str(Json.obj(settings,"civitai"),"proxy_url",""));
        HttpClient.Builder builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL);
        if(!proxy.isBlank()) {
            URI value=URI.create(proxy);
            if(!"http".equalsIgnoreCase(value.getScheme()) || value.getHost()==null || value.getPort()<1) throw new IOException("chat.search_proxy_url 必须是 http://主机:端口。");
            builder.proxy(ProxySelector.of(new InetSocketAddress(value.getHost(),value.getPort())));
        }
        String encoded=URLEncoder.encode(query,StandardCharsets.UTF_8).replace("+","%20");
        URI endpoint=URI.create("https://r.jina.ai/http://www.bing.com/search?q="+encoded);
        HttpRequest request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(25))
                .header("Accept","text/plain").header("User-Agent","Pixiko/1.0").GET().build();
        HttpResponse<String> response;
        try { response=builder.build().send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IOException("联网检索已取消。"); }
        catch(Exception e) { throw new IOException("联网检索连接失败。"); }
        if(response.statusCode()!=200) throw new IOException("联网检索失败（HTTP "+response.statusCode()+"）。");
        if(response.body().length()>2_000_000) throw new IOException("联网检索结果过大。");
        String result=parse(response.body());
        synchronized(WebSearch.class) {
            CACHE.put(query.toLowerCase(Locale.ROOT),new Cached(System.currentTimeMillis(),result));
            while(CACHE.size()>64) CACHE.remove(CACHE.keySet().iterator().next());
        }
        return result;
    }

    public static String parse(String markdown) throws IOException {
        Pattern heading=Pattern.compile("(?m)^## \\[(.+?)]\\((https?://[^)]+)\\)\\s*$");
        Matcher matcher=heading.matcher(markdown);List<String> items=new ArrayList<>();
        while(matcher.find() && items.size()<5) {
            String title=matcher.group(1).replace("**","").replaceAll("[\\p{Cntrl}\\p{Cf}]"," ").strip();
            String url=target(matcher.group(2));
            int from=matcher.end(),to=markdown.length();Matcher next=heading.matcher(markdown);if(next.find(from))to=next.start();
            String block=markdown.substring(from,to).replaceAll("!\\[[^]]*]\\([^)]*\\)"," ")
                    .replaceAll("\\[[^]]*]\\([^)]*\\)"," ").replaceAll("[#*_`|]"," ")
                    .replaceAll("\\s+"," ").strip();
            if(block.length()>600) block=block.substring(0,600)+"…";
            if(title.isBlank() || !url.startsWith("http")) continue;
            items.add("["+(items.size()+1)+"] "+title+"\nURL: "+url+(block.isBlank()?"":"\n摘要: "+block));
        }
        if(items.isEmpty()) throw new IOException("联网检索没有返回可用结果。");
        return "检索日期："+LocalDate.now()+"\n"+String.join("\n\n",items);
    }

    private static String target(String value) {
        try {
            URI uri=URI.create(value);String raw=uri.getRawQuery();
            if(raw!=null) for(String part:raw.split("&")) if(part.startsWith("u=a1")) {
                String encoded=URLDecoder.decode(part.substring(4),StandardCharsets.UTF_8);
                int padding=(4-encoded.length()%4)%4;encoded+= "=".repeat(padding);
                String decoded=new String(Base64.getUrlDecoder().decode(encoded),StandardCharsets.UTF_8);
                if(decoded.startsWith("http://") || decoded.startsWith("https://")) return decoded;
            }
        } catch(Exception ignored) {}
        return value;
    }
}
