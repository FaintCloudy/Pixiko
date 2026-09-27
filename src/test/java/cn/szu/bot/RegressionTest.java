package cn.szu.bot;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import cn.szu.bot.sd.SdClient;

/** Offline regressions for bounded image batches and cross-process map configuration locking. */
public final class RegressionTest {
    private static final AtomicInteger IDS = new AtomicInteger();
    private record ImageSend(int eventId, String encoded, CompletableFuture<Void> acknowledgement) {}

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work")).toAbsolutePath().normalize();
        Files.createDirectories(work);
        imageBatchLimit(Files.createTempDirectory(work, "regression-batches-"));
        cliMapLock(Files.createTempDirectory(work, "regression-map-lock-"));
        System.out.println("RegressionTest PASS: four-batch bound, busy response, lazy images after ACK, recovery, CLI process lock, unchanged config on denial, persistence after release");
    }

    private static void imageBatchLimit(Path root) throws Exception {
        JsonObject cfg = config(root);
        byte[] firstBytes = png(0x112233), oldSecond = png(0x334455), newSecond = png(0x778899);
        Files.write(root.resolve("maps/yh/01.png"), firstBytes);
        Path secondPath = root.resolve("maps/yh/02.png");
        Files.write(secondPath, oldSecond);
        Files.write(root.resolve("maps/liv/01.png"), firstBytes);
        BlockingQueue<ImageSend> images = new LinkedBlockingQueue<>();
        BlockingQueue<String> texts = new LinkedBlockingQueue<>();
        List<CompletableFuture<Void>> outstanding = new CopyOnWriteArrayList<>();
        Bot.Sender sender = (event, segments) -> {
            JsonObject segment = segments.get(0).getAsJsonObject();
            if (segment.get("type").getAsString().equals("image")) {
                CompletableFuture<Void> ack = new CompletableFuture<>();
                outstanding.add(ack);
                images.add(new ImageSend(event.get("message_id").getAsInt(),
                        segment.getAsJsonObject("data").get("file").getAsString(), ack));
                return ack;
            }
            texts.add(Bot.messageText(segments));
            return CompletableFuture.completedFuture(null);
        };
        try (Bot bot = new Bot(new Settings(root), new SdClient(root, Json.obj(cfg, "sd")), sender)) {
            Set<Integer> expected = new HashSet<>();
            for (int i = 0; i < 4; i++) {
                JsonObject event = event(i % 2 == 0 ? "group" : "private", ".yh");
                expected.add(event.get("message_id").getAsInt());
                bot.accept(event);
            }
            List<ImageSend> first = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                ImageSend send = take(images);
                check(expected.remove(send.eventId()), "batch duplicated or wrong event routed");
                check(Arrays.equals(decode(send), firstBytes), "first file ordering incorrect");
                first.add(send);
            }
            check(expected.isEmpty(), "some admitted batches were not processed");
            check(images.poll(150, TimeUnit.MILLISECONDS) == null, "second image sent before first acknowledgement");
            // 每条地图指令都会先回一句说明；先把这 4 条排空，才能确认第五条拿到的是"正在发送图片"。
            for (int i = 0; i < 4; i++) {
                String caption = texts.poll(3, TimeUnit.SECONDS);
                check(caption != null && caption.contains("地图"), "each map reply explains what is sent: " + caption);
            }
            bot.accept(event("private", ".liv"));
            String busy = texts.poll(3, TimeUnit.SECONDS);
            check(busy != null && busy.contains("正在发送图片"), "fifth batch must receive busy response");
            check(images.poll(150, TimeUnit.MILLISECONDS) == null, "fifth batch bypassed the limit");

            // Changing file two before ACK proves it was not eagerly encoded with file one.
            Files.write(secondPath, newSecond);
            first.forEach(send -> send.acknowledgement().complete(null));
            List<ImageSend> seconds = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                ImageSend send = take(images);
                check(Arrays.equals(decode(send), newSecond), "second image was eagerly loaded before acknowledgement");
                seconds.add(send);
            }
            seconds.forEach(send -> send.acknowledgement().complete(null));

            JsonObject recoveredEvent = event("group", ".liv");
            bot.accept(recoveredEvent);
            ImageSend recovered = take(images);
            check(recovered.eventId() == recoveredEvent.get("message_id").getAsInt(), "capacity did not recover after batch completion");
            check(texts.poll(3, TimeUnit.SECONDS) != null, "recovered batch also carries its caption");
            recovered.acknowledgement().completeExceptionally(new IOException("offline simulated send failure"));
            String failure = texts.poll(3, TimeUnit.SECONDS);
            check(failure != null && failure.contains("图片发送失败"), "failed send did not produce a useful error");

            JsonObject afterFailure = event("private", ".liv");
            bot.accept(afterFailure);
            ImageSend retry = take(images);
            check(retry.eventId() == afterFailure.get("message_id").getAsInt(), "capacity did not recover after failed batch");
            retry.acknowledgement().complete(null);
            texts.clear();   // 说明性文本不算异常回执：这里只确认没有多出来的 busy/error
            check(texts.poll(150, TimeUnit.MILLISECONDS) == null, "unexpected busy/error response after recovery");
        } finally {
            outstanding.forEach(ack -> ack.complete(null));
        }
    }

    private static void cliMapLock(Path root) throws Exception {
        config(root);
        Path newMap = root.resolve("replacement maps");
        Files.createDirectories(newMap);
        byte[] before = Files.readAllBytes(root.resolve("config.json"));
        try (FileChannel channel = FileChannel.open(root.resolve("data/bot.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            ProcessResult denied = setMap(root, newMap);
            check(denied.exitCode() != 0, "CLI map setting unexpectedly succeeded while instance lock was held");
            check(denied.output().contains("--set-map"), "CLI lock error did not explain the blocked command");
            check(Arrays.equals(before, Files.readAllBytes(root.resolve("config.json"))), "denied CLI modified configuration");
        }
        ProcessResult allowed = setMap(root, newMap);
        check(allowed.exitCode() == 0, "CLI setting failed after releasing lock: " + allowed.output());
        Settings restored = new Settings(root);
        check(restored.mapPath("yh").equals(newMap), "CLI map setting did not persist after lock release");
        check(restored.mapPath("liv").equals(root.resolve("maps/liv")), "CLI setting overwrote the other campus map");
    }

    private record ProcessResult(int exitCode, String output) {}
    private static ProcessResult setMap(Path root, Path map) throws Exception {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        // Every argument is supplied separately; paths with spaces never pass through a shell.
        Process process = new ProcessBuilder(java.toString(), "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8",
                "-Dsun.stderr.encoding=UTF-8", "-Dbot.home=" + root, "-cp", System.getProperty("java.class.path"),
                "cn.szu.bot.Main", "--set-map", "yh", map.toString()).redirectErrorStream(true).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("CLI process timed out");
        }
        return new ProcessResult(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static JsonObject config(Path root) throws Exception {
        Files.createDirectories(root.resolve("maps/yh"));
        Files.createDirectories(root.resolve("maps/liv"));
        Files.createDirectories(root.resolve("data"));
        JsonObject cfg = Json.parse("{\"maps\":{\"yh\":\"maps/yh\",\"liv\":\"maps/liv\"},\"sd\":{}}");
        Json.atomicWrite(root.resolve("config.json"), cfg);
        return cfg;
    }
    private static JsonObject event(String type, String command) {
        JsonObject event = new JsonObject();
        event.addProperty("post_type", "message");
        event.addProperty("message_type", type);
        event.addProperty("self_id", 999);
        event.addProperty("user_id", 123);
        event.addProperty("message_id", IDS.incrementAndGet());
        if (type.equals("group")) event.addProperty("group_id", 456);
        event.add("message", Maps.text(command));
        return event;
    }
    private static byte[] png(int color) throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
    private static byte[] decode(ImageSend send) { return Base64.getDecoder().decode(send.encoded().substring("base64://".length())); }
    private static ImageSend take(BlockingQueue<ImageSend> queue) throws InterruptedException {
        ImageSend send = queue.poll(3, TimeUnit.SECONDS);
        if (send == null) throw new AssertionError("Image dispatch timed out");
        return send;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
