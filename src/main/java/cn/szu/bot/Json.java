package cn.szu.bot;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class Json {
    private Json() {}
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static JsonObject parse(String value) { return JsonParser.parseString(value.replaceFirst("^\\uFEFF", "")).getAsJsonObject(); }
    public static JsonObject obj(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject(); }
    public static String str(JsonObject o, String key, String fallback) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback; }
    public static int num(JsonObject o, String key, int fallback) { return o.has(key) ? o.get(key).getAsInt() : fallback; }
    /** 小数型字段（CFG、蒸馏 CFG 这类）。 */
    public static double decimal(JsonObject o, String key, double fallback) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return fallback;
        try { return o.get(key).getAsDouble(); } catch (Exception error) { return fallback; }
    }
    /** Epoch milliseconds do not fit in an int, so timestamps need the long overload. */
    public static long num(JsonObject o, String key, long fallback) { return o.has(key) ? o.get(key).getAsLong() : fallback; }
    public static boolean bool(JsonObject o, String key, boolean fallback) { return o.has(key) ? o.get(key).getAsBoolean() : fallback; }
    /** One lock per target file: concurrent conversations otherwise collide on the atomic rename. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> LOCKS = new java.util.concurrent.ConcurrentHashMap<>();
    public static void atomicWrite(Path path, Object value) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Object lock = LOCKS.computeIfAbsent(absolute.toString(), key -> new Object());
        synchronized (lock) {
            Files.createDirectories(absolute.getParent());
            Path temp = Files.createTempFile(absolute.getParent(), absolute.getFileName().toString(), ".tmp");
            try {
                Files.writeString(temp, GSON.toJson(value) + System.lineSeparator(), StandardCharsets.UTF_8);
                // Windows can still refuse the replace while another handle is open: retry briefly.
                IOException last = null;
                for (int attempt = 1; attempt <= 5; attempt++) {
                    try {
                        try { Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                        catch (AtomicMoveNotSupportedException e) { Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING); }
                        return;
                    } catch (IOException failure) {
                        last = failure;
                        try { Thread.sleep(50L * attempt); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                    }
                }
                throw last == null ? new IOException("写入失败：" + absolute) : last;
            } finally { Files.deleteIfExists(temp); }
        }
    }
}
