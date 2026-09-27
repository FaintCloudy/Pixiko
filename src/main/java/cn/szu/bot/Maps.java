package cn.szu.bot;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public final class Maps {
    private Maps() {}
    public static boolean supported(Path p) { return p.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|gif|webp|bmp)$"); }
    public static List<Path> files(Path p) throws IOException {
        if (!Files.exists(p)) throw new IOException("地图路径不存在：" + p);
        if (Files.isRegularFile(p) && supported(p)) return List.of(p);
        if (!Files.isDirectory(p)) throw new IOException("地图路径不是图片或文件夹：" + p);
        try (Stream<Path> stream = Files.list(p)) {
            List<Path> images = stream.filter(Files::isRegularFile).filter(Maps::supported)
                .sorted(Comparator.comparing(x -> x.getFileName().toString().toLowerCase(Locale.ROOT))).toList();
            if (images.isEmpty()) throw new IOException("还没有地图图片，请放入：" + p);
            if (images.size() > 10) throw new IOException("目录中的地图超过 10 张，请精简图片或指定单张图片文件。");
            return images;
        }
    }
    public static JsonArray image(Path path) throws IOException {
        long size = Files.size(path);
        if (size == 0 || size > 20L * 1024 * 1024) throw new IOException("图片为空或超过 20MB：" + path.getFileName());
        JsonObject data = new JsonObject(); data.addProperty("file", "base64://" + Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
        JsonObject segment = new JsonObject(); segment.addProperty("type", "image"); segment.add("data", data);
        JsonArray result = new JsonArray(); result.add(segment); return result;
    }
    /** Local NapCat reads files without embedding all image bytes into WebSocket JSON. */
    public static JsonArray localImages(List<Path> paths) throws IOException {
        JsonArray result = new JsonArray();
        for (Path path : paths) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) == 0)
                throw new IOException("图片不存在或为空：" + path.getFileName());
            JsonObject data = new JsonObject();
            data.addProperty("file", path.toRealPath().toUri().toASCIIString());
            JsonObject segment = new JsonObject(); segment.addProperty("type", "image"); segment.add("data", data);
            result.add(segment);
        }
        return result;
    }
    public static JsonArray text(String text) {
        JsonObject data = new JsonObject(); data.addProperty("text", text);
        JsonObject segment = new JsonObject(); segment.addProperty("type", "text"); segment.add("data", data);
        JsonArray result = new JsonArray(); result.add(segment); return result;
    }
}
