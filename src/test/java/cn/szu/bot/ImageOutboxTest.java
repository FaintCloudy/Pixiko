package cn.szu.bot;

import com.google.gson.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import cn.szu.bot.sd.ImageOutbox;

/** Persistent state and real tiny image fixtures; no SD or QQ network access. */
public final class ImageOutboxTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        orderedAppendRestartAndAcknowledgement();
        migrationOccursExactlyOnce();
        failedWritesPreserveQueueAndDisk();
        invalidBatchIsAllOrNothing();
        corruptedStateIsNeverCleared();
        missingImagesRemainQueued();
        pathBoundariesAndFileTypes();
        immutableSnapshots();
        System.out.println("ImageOutboxTest: " + assertions + " assertions passed.");
    }

    private static void orderedAppendRestartAndAcknowledgement() throws Exception {
        try (Fixture f = new Fixture()) {
            ImageOutbox outbox = new ImageOutbox(f.root);
            equal(List.of(), outbox.pending(), "new outbox starts empty");
            check(Files.exists(f.state()), "empty outbox persisted immediately");
            Path first = f.image("batch-a/image-01.png"), second = f.image("batch-a/image-02.png"), third = f.image("batch-b/image-01.jpg");
            outbox.append(List.of(first, second)); outbox.append(List.of(third));
            equal(List.of(first, second, third), outbox.pending(), "all successful batches accumulate in insertion order");
            outbox.append(List.of(first, first.resolveSibling("../batch-a/image-01.png"), f.root.relativize(second)));
            equal(List.of(first, second, third), outbox.pending(), "duplicate normalized absolute and relative paths are idempotent");
            equal(1, Json.parse(Files.readString(f.state())).get("version").getAsInt(), "schema version persisted");
            equal(List.of(first, second, third), new ImageOutbox(f.root).pending(), "order persists across restart");
            outbox.acknowledge(second);
            equal(List.of(first, third), outbox.pending(), "ack only removes confirmed item");
            equal(List.of(first, third), new ImageOutbox(f.root).pending(), "ack persists before restart");
            check(Files.isRegularFile(second), "ack never deletes actual image");
            outbox.acknowledge(second);
            equal(List.of(first, third), outbox.pending(), "duplicate ack harmless");
            outbox.acknowledge(first); outbox.acknowledge(third);
            equal(List.of(), new ImageOutbox(f.root).pending(), "empty consumed queue persists");
        }
    }

    private static void migrationOccursExactlyOnce() throws Exception {
        try (Fixture f = new Fixture()) {
            Path first = f.image("old/image-01.png"), second = f.image("old/image-02.png");
            f.latest(List.of(first, second));
            String original = Files.readString(f.latest());
            ImageOutbox outbox = new ImageOutbox(f.root);
            equal(List.of(first, second), outbox.pending(), "migrate old latest.images when no outbox exists");
            equal(original, Files.readString(f.latest()), "migration never changes latest manifest");
            outbox.acknowledge(first); outbox.acknowledge(second);
            equal(List.of(), new ImageOutbox(f.root).pending(), "persisted empty outbox does not reimport already sent images");
            Path newer = f.image("newer/image.png"); f.latest(List.of(newer));
            equal(List.of(), new ImageOutbox(f.root).pending(), "later latest updates cannot resurrect migration");
        }
        try (Fixture f = new Fixture()) {
            new ImageOutbox(f.root);
            Path image = f.image("later/image.png"); f.latest(List.of(image));
            equal(List.of(), new ImageOutbox(f.root).pending(), "initial empty marker also prevents delayed migration");
        }
        try (Fixture f = new Fixture()) {
            f.latest(List.of());
            equal(List.of(), new ImageOutbox(f.root).pending(), "empty legacy array migrates safely once");
        }
    }

    private static void failedWritesPreserveQueueAndDisk() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicBoolean fail = new AtomicBoolean(); AtomicInteger writes = new AtomicInteger();
            ImageOutbox outbox = new ImageOutbox(f.root, (path, state) -> {
                writes.incrementAndGet();
                if (fail.get()) throw new IOException("simulated full disk");
                Json.atomicWrite(path, state);
            });
            Path first = f.image("a.png"), second = f.image("b.png");
            outbox.append(List.of(first));
            String baseline = Files.readString(f.state());
            fail.set(true);
            expectFailure(() -> { outbox.acknowledge(first); return null; }, "保持原状", "failed ack propagates disk error");
            equal(List.of(first), outbox.pending(), "failed ack preserves in-memory queue");
            equal(baseline, Files.readString(f.state()), "failed ack preserves exact disk content");
            check(Files.exists(first), "failed ack still never deletes image");
            expectFailure(() -> { outbox.append(List.of(second)); return null; }, "保持原状", "failed append propagates");
            equal(List.of(first), outbox.pending(), "failed append adds no in-memory item");
            equal(baseline, Files.readString(f.state()), "failed append preserves previous persisted queue");
            int before = writes.get(); outbox.append(List.of(first)); outbox.acknowledge(second);
            equal(before, writes.get(), "idempotent append and absent ack do not rewrite disk");
            fail.set(false); outbox.append(List.of(second)); outbox.acknowledge(first);
            equal(List.of(second), new ImageOutbox(f.root).pending(), "operations recover normally after disk restored");
        }
        try (Fixture f = new Fixture()) {
            Path image = f.image("old.png"); f.latest(List.of(image));
            String latest = Files.readString(f.latest());
            expectFailure(() -> new ImageOutbox(f.root, (path, state) -> { throw new IOException("read-only disk"); }), "保持原状", "migration cannot advertise success before persistence");
            equal(latest, Files.readString(f.latest()), "failed migration preserves latest");
            check(!Files.exists(f.state()), "failed migration creates no empty outbox");
            equal(List.of(image), new ImageOutbox(f.root).pending(), "migration can retry after disk fault");
        }
    }

    private static void invalidBatchIsAllOrNothing() throws Exception {
        try (Fixture f = new Fixture()) {
            ImageOutbox outbox = new ImageOutbox(f.root);
            Path valid = f.image("valid.png"), missing = f.root.resolve("data/generated/missing.png");
            String original = Files.readString(f.state());
            expectFailure(() -> { outbox.append(List.of(valid, missing)); return null; }, "不存在", "whole batch checked before persist");
            equal(List.of(), outbox.pending(), "partial valid batch not added");
            equal(original, Files.readString(f.state()), "bad batch makes no disk write");
            expectFailure(() -> { outbox.append(null); return null; }, "null", "null batch rejected");
            List<Path> withNull = new ArrayList<>(); withNull.add(valid); withNull.add(null);
            expectFailure(() -> { outbox.append(withNull); return null; }, "null", "null item rejected atomically");
            equal(List.of(), outbox.pending(), "null-containing batch adds nothing");
        }
    }

    private static void corruptedStateIsNeverCleared() throws Exception {
        for (String corrupt : List.of("not JSON", "{}", "{\"version\":2,\"images\":[]}", "{\"version\":\"1\",\"images\":[]}",
                "{\"version\":1,\"images\":null}", "{\"version\":1,\"images\":[null]}", "{\"version\":1,\"images\":[123]}", "{\"version\":1,\"images\":[\"\"]}")) {
            try (Fixture f = new Fixture()) {
                Files.writeString(f.state(), corrupt);
                expectFailure(() -> new ImageOutbox(f.root), "", "invalid persisted queue fails clearly");
                equal(corrupt, Files.readString(f.state()), "corrupt outbox never silently rewritten empty");
            }
        }
        try (Fixture f = new Fixture()) {
            String corrupt = "{\"positive\":\"prompt only\"}"; Files.writeString(f.latest(), corrupt);
            expectFailure(() -> new ImageOutbox(f.root), "images", "invalid latest manifest fails migration");
            equal(corrupt, Files.readString(f.latest()), "bad latest preserved");
            check(!Files.exists(f.state()), "invalid migration not recorded as empty success");
        }
        try (Fixture f = new Fixture()) {
            try (OutputStream output = Files.newOutputStream(f.state())) {
                byte[] padding = new byte[1024 * 1024];
                for (int i = 0; i < 17; i++) output.write(padding);
            }
            long bytes = Files.size(f.state());
            expectFailure(() -> new ImageOutbox(f.root), "过大", "oversized persisted state rejected before parsing");
            equal(bytes, Files.size(f.state()), "oversized state preserved for recovery");
        }
    }

    private static void missingImagesRemainQueued() throws Exception {
        try (Fixture f = new Fixture()) {
            ImageOutbox outbox = new ImageOutbox(f.root);
            Path image = f.image("disappears.png"); outbox.append(List.of(image)); Files.delete(image);
            equal(List.of(image), outbox.pending(), "missing queued file remains visible for send error");
            ImageOutbox restarted = new ImageOutbox(f.root);
            equal(List.of(image), restarted.pending(), "restart retains missing file record");
            restarted.acknowledge(image);
            equal(List.of(), restarted.pending(), "explicit acknowledgement only removes queue record");
        }
        try (Fixture f = new Fixture()) {
            Path missing = f.root.resolve("data/generated/removed-folder/image.png"); f.latest(List.of(missing));
            equal(List.of(missing), new ImageOutbox(f.root).pending(), "legacy missing image remains queued rather than silently dropped");
        }
    }

    private static void pathBoundariesAndFileTypes() throws Exception {
        try (Fixture f = new Fixture()) {
            ImageOutbox outbox = new ImageOutbox(f.root);
            Path outside = f.root.resolve("private.png"); Files.write(outside, f.png());
            Path sibling = Files.createDirectories(f.root.resolve("data/generated-other")).resolve("private.png"); Files.write(sibling, f.png());
            for (Path path : List.of(outside, sibling, Path.of("data/generated/../../private.png"), f.root.resolve("data/generated")))
                expectFailure(() -> { outbox.append(List.of(path)); return null; }, "路径无效", "outside or prefix-lookalike path rejected");
            Path secret = f.root.resolve("data/generated/secret.png"); Files.writeString(secret, "not an image; private credentials");
            expectFailure(() -> { outbox.append(List.of(secret)); return null; }, "PNG/JPEG", "non-image cannot enter queue under a PNG extension");
            equal(List.of(), outbox.pending(), "invalid paths never enqueue");
            JsonObject malicious = state(List.of(outside.toString())); Json.atomicWrite(f.state(), malicious);
            String before = Files.readString(f.state());
            expectFailure(() -> new ImageOutbox(f.root), "路径无效", "manually injected absolute outside image rejected");
            equal(before, Files.readString(f.state()), "malicious queue not rewritten");
        }
        try (Fixture f = new Fixture()) {
            Path valid = f.image("replace.png"); ImageOutbox outbox = new ImageOutbox(f.root); outbox.append(List.of(valid));
            Files.writeString(valid, "sensitive non-image replacement");
            expectFailure(outbox::pending, "PNG/JPEG", "pending revalidates files changed after enqueue");
            equal(1, Json.parse(Files.readString(f.state())).getAsJsonArray("images").size(), "invalid replacement not silently removed");
        }
        try (Fixture f = new Fixture()) {
            Path image = f.image("valid.png"), alias = f.root.resolve("data/generated/alias.png");
            try {
                Files.createSymbolicLink(alias, image);
            } catch (UnsupportedOperationException | FileSystemException unavailable) {
                // Windows requires Developer Mode or a symlink privilege. The
                // unconditional traversal/real-path tests above still execute.
                return;
            }
            ImageOutbox outbox = new ImageOutbox(f.root);
            expectFailure(() -> { outbox.append(List.of(alias)); return null; }, "链接", "even in-tree symlink aliases rejected");
            Json.atomicWrite(f.state(), state(List.of("data/generated/alias.png")));
            expectFailure(() -> new ImageOutbox(f.root), "链接", "symlink injected through persisted JSON rejected");
        }
    }

    private static void immutableSnapshots() throws Exception {
        try (Fixture f = new Fixture()) {
            ImageOutbox outbox = new ImageOutbox(f.root); Path image = f.image("a.png");
            List<Path> batch = new ArrayList<>(List.of(image)); outbox.append(batch); batch.clear();
            equal(List.of(image), outbox.pending(), "append does not retain mutable caller list");
            List<Path> pending = outbox.pending();
            try { pending.clear(); throw new AssertionError("pending snapshot should be immutable"); }
            catch (UnsupportedOperationException expected) { check(true, "returned list immutable"); }
            outbox.acknowledge(image);
            equal(List.of(image), pending, "earlier pending snapshot unchanged after ack");
        }
    }

    private static JsonObject state(List<String> images) { JsonObject state = new JsonObject(); state.addProperty("version", 1); state.add("images", Json.GSON.toJsonTree(images)); return state; }
    private interface Operation { Object run() throws Exception; }
    private static void expectFailure(Operation operation, String fragment, String message) throws Exception {
        try { operation.run(); throw new AssertionError(message + ": expected exception"); }
        catch (IOException expected) { check(expected.getMessage().contains(fragment), message + ": " + expected.getMessage()); }
    }
    private static void equal(Object expected, Object actual, String message) { check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual); }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }

    private static final class Fixture implements AutoCloseable {
        final Path root;
        Fixture() throws IOException {
            Path work = Path.of(System.getProperty("bot.test.work", "work"), "image-outbox-tests").toAbsolutePath();
            Files.createDirectories(work); root = Files.createTempDirectory(work, "case-"); Files.createDirectories(root.resolve("data/generated"));
        }
        Path state() { return root.resolve("data/sd-outbox.json"); }
        Path latest() { return root.resolve("data/sd-latest.json"); }
        byte[] png() throws IOException { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", bytes); return bytes.toByteArray(); }
        Path image(String name) throws IOException {
            Path image = root.resolve("data/generated").resolve(name).normalize();
            if (!image.startsWith(root.resolve("data/generated"))) throw new IOException("bad test path");
            Files.createDirectories(image.getParent());
            ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), name.endsWith(".jpg") ? "jpg" : "png", image.toFile());
            return image;
        }
        void latest(List<Path> images) throws IOException {
            JsonObject latest = new JsonObject(); JsonArray paths = new JsonArray();
            for (Path path : images) paths.add(root.relativize(path).toString().replace('\\', '/'));
            latest.add("images", paths); latest.addProperty("positive", "unchanged legacy prompt"); Json.atomicWrite(latest(), latest);
        }
        public void close() throws IOException {
            // Only this precise generated fixture is removed. Files.walk does not follow symbolic links.
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
}
