package cn.szu.bot.sd;

import com.google.gson.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import cn.szu.bot.Json;

/** Persistent, ordered images awaiting confirmed delivery. Acknowledging never deletes image files. */
public final class ImageOutbox {
    private static final int VERSION = 1;
    private static final long MAX_STATE_BYTES = 16L * 1024 * 1024;
    private final Path root, generated, stateFile, latestFile;
    private final Persistence persistence;
    private List<Path> images;

    @FunctionalInterface public interface Persistence { void write(Path path, JsonObject state) throws IOException; }

    public ImageOutbox(Path root) throws IOException { this(root, Json::atomicWrite); }

    /** Package-private injection permits deterministic disk failure tests without changing production configuration. */
    public ImageOutbox(Path root, Persistence persistence) throws IOException {
        Objects.requireNonNull(root, "root");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        Path absolute = root.toAbsolutePath().normalize();
        Files.createDirectories(absolute);
        this.root = absolute.toRealPath();
        generated = this.root.resolve("data/generated");
        stateFile = this.root.resolve("data/sd-outbox.json");
        latestFile = this.root.resolve("data/sd-latest.json");
        checkDirectory(this.root.resolve("data"));
        Files.createDirectories(this.root.resolve("data"));
        checkDirectory(this.root.resolve("data"));
        if (Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            JsonObject state = read(stateFile, "待领取图片队列");
            JsonElement version = state.get("version");
            try {
                if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                        || version.getAsBigDecimal().intValueExact() != VERSION) throw new IllegalArgumentException();
            } catch (Exception e) { throw new IOException("data/sd-outbox.json 的队列版本无效或不受支持；请检查或恢复文件。"); }
            images = loadImages(state, "data/sd-outbox.json");
        } else {
            List<Path> initial = Files.exists(latestFile, LinkOption.NOFOLLOW_LINKS)
                    ? loadImages(read(latestFile, "旧版最新图片记录"), "data/sd-latest.json") : List.of();
            // Persist even an empty migration so a later restart cannot re-import
            // already delivered images from the compatibility latest manifest.
            persist(initial);
            images = List.copyOf(initial);
        }
    }

    /** Validate the complete batch before a single atomic write; normalized duplicate paths are harmless. */
    public synchronized void append(List<Path> batch) throws IOException {
        if (batch == null) throw new IOException("待领取图片列表不能为 null。");
        LinkedHashSet<Path> updated = new LinkedHashSet<>(images);
        for (Path image : batch) updated.add(validate(image, true));
        List<Path> next = List.copyOf(updated);
        if (next.equals(images)) return;
        persist(next);
        images = next;
    }

    /** Missing images remain visible to the sender so it can report an actionable failure instead of dropping them. */
    public synchronized List<Path> pending() throws IOException {
        for (Path image : images) validate(image, false);
        return List.copyOf(images);
    }

    /** Call only after the corresponding transport confirms delivery. Disk failure leaves the old queue intact. */
    public synchronized void acknowledge(Path image) throws IOException {
        acknowledgeAll(List.of(image));
    }

    /** Commit a whole forward-message snapshot only after its ACK. */
    public synchronized void acknowledgeAll(List<Path> batch) throws IOException {
        Set<Path> acknowledged = new HashSet<>();
        for (Path image : batch) acknowledged.add(normalize(image));
        List<Path> next = new ArrayList<>(images);
        if (!next.removeAll(acknowledged)) return;
        persist(next);
        images = List.copyOf(next);
    }

    private List<Path> loadImages(JsonObject state, String filename) throws IOException {
        JsonElement entries = state.get("images");
        if (entries == null || !entries.isJsonArray()) throw new IOException(filename + " 缺少有效的 images 数组；原文件未修改。");
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        for (JsonElement entry : entries.getAsJsonArray()) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString() || entry.getAsString().isBlank())
                throw new IOException(filename + " 包含无效图片路径；原文件未修改。");
            try { result.add(validate(Path.of(entry.getAsString()), false)); }
            catch (InvalidPathException e) { throw new IOException(filename + " 包含无效图片路径；原文件未修改。", e); }
        }
        return List.copyOf(result);
    }

    private Path normalize(Path image) throws IOException {
        if (image == null) throw new IOException("待领取图片路径不能为 null。");
        Path path = (image.isAbsolute() ? image : root.resolve(image)).toAbsolutePath().normalize();
        if (!path.startsWith(generated) || path.equals(generated))
            throw new IOException("待领取图片路径无效：只允许本机 data/generated 目录内的图片。");
        return path;
    }

    private Path validate(Path image, boolean requirePresent) throws IOException {
        Path path = normalize(image);
        Path cursor = root;
        for (Path segment : root.relativize(path)) {
            cursor = cursor.resolve(segment);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (requirePresent) throw new IOException("待领取图片不存在：" + root.relativize(path));
                return path;
            }
            if (Files.isSymbolicLink(cursor) || !cursor.toRealPath().equals(cursor))
                throw new IOException("待领取图片路径包含符号链接或目录跳转，已拒绝读取。");
            if (!cursor.equals(path) && !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("待领取图片的父路径不是文件夹。");
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("待领取图片路径不是普通文件。");
        validateImage(path, requirePresent);
        return path;
    }

    private static void validateImage(Path path, boolean decode) throws IOException {
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] magic = input.readNBytes(8);
            boolean png = magic.length == 8 && (magic[0] & 255) == 137 && magic[1] == 80 && magic[2] == 78 && magic[3] == 71
                    && magic[4] == 13 && magic[5] == 10 && magic[6] == 26 && magic[7] == 10;
            boolean jpeg = magic.length >= 3 && (magic[0] & 255) == 255 && (magic[1] & 255) == 216 && (magic[2] & 255) == 255;
            if (!png && !jpeg) throw new IOException("待领取文件不是可读取的 PNG/JPEG 图片：" + path.getFileName());
        }
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
             var stream = new MemoryCacheImageInputStream(input)) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("unknown image format");
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                if (reader.getWidth(0) < 1 || reader.getHeight(0) < 1) throw new IOException("invalid image dimensions");
                // Full decoding is only needed once before enqueueing a newly
                // generated file. Listing/restarting a long queue reads headers,
                // not every image's pixels, and still rejects non-image files.
                if (decode && reader.read(0) == null) throw new IOException("cannot decode");
            } finally { reader.dispose(); }
        } catch (IOException | RuntimeException e) { throw new IOException("待领取文件不是可读取的 PNG/JPEG 图片：" + path.getFileName(), e); }
    }

    private void checkDirectory(Path directory) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)
                    || !directory.toRealPath().equals(directory)))
            throw new IOException("待领取队列的数据目录无效，不能使用符号链接或目录跳转。");
    }

    private JsonObject read(Path path, String description) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
                || !path.toRealPath().equals(path) || Files.size(path) > MAX_STATE_BYTES)
            throw new IOException(description + "文件无效、过大或是链接；原文件未修改。");
        try { return Json.parse(Files.readString(path, StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IOException("无法读取 " + path.getFileName() + "；请检查或恢复此文件，原文件未修改。", e); }
    }

    private void persist(List<Path> next) throws IOException {
        checkDirectory(root.resolve("data"));
        JsonObject state = new JsonObject();
        state.addProperty("version", VERSION);
        JsonArray entries = new JsonArray();
        for (Path image : next) entries.add(root.relativize(image).toString().replace('\\', '/'));
        state.add("images", entries);
        state.addProperty("updated_at", Instant.now().toString());
        long serializedBytes = (long) Json.GSON.toJson(state).getBytes(StandardCharsets.UTF_8).length
                + System.lineSeparator().getBytes(StandardCharsets.UTF_8).length;
        if (serializedBytes > MAX_STATE_BYTES)
            throw new IOException("待领取图片队列过大，暂时无法追加；请先领取部分图片。现有队列保持原状。");
        try { persistence.write(stateFile, state); }
        catch (IOException e) { throw new IOException("无法保存 data/sd-outbox.json；待领取队列保持原状，请检查磁盘空间和文件权限。", e); }
    }
}
