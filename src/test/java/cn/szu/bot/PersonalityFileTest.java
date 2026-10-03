package cn.szu.bot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * 人设以 **文件** 为准：{@code data/chat-personality-kotori.txt} 随仓库同步，config.json 里那份只是退路。
 *
 * <p>这条约定是「换台机器接着干」的关键：人设不该只活在被 gitignore 掉的 config.json 里。
 * Run with java -ea.
 */
public final class PersonalityFileTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        fileWinsOverConfig();
        writesGoToFileWhenPresent();
        configIsTheFallback();
        System.out.println("PersonalityFileTest: " + assertions + " assertions passed.");
    }

    private static void fileWinsOverConfig() throws Exception {
        Path root = workspace("file-wins");
        try {
            write(root.resolve("config.json"), "{\"chat\":{\"personality\":\"配置里那份\"}}");
            Settings settings = new Settings(root);
            equal("配置里那份", settings.chatPersonality(), "没有文件时用 config.json");
            write(settings.chatPersonalityFile(), "文件里那份\n第二行\n");
            equal("文件里那份\n第二行", settings.chatPersonality(), "有文件时以文件为准（去掉尾部空行）");
            // 改文件要能立刻生效：按 mtime 缓存，不能一直用旧内容。
            Thread.sleep(10);
            write(settings.chatPersonalityFile(), "改过的那份");
            equal("改过的那份", settings.chatPersonality(), "改完文件立刻生效");
        } finally {
            delete(root);
        }
    }

    private static void writesGoToFileWhenPresent() throws Exception {
        Path root = workspace("write-back");
        try {
            write(root.resolve("config.json"), "{\"chat\":{\"personality\":\"配置里那份\"}}");
            Settings settings = new Settings(root);
            equal("配置里那份", settings.chatPersonality(), "起点是 config.json");
            // 没有文件时写 config.json
            settings.setChatPersonality("网页改的");
            equal("网页改的", settings.chatPersonality(), "没文件就写 config.json");
            // 有了文件之后，写入都进文件（这样才会被 git 同步）
            write(settings.chatPersonalityFile(), "文件初始");
            settings.setChatPersonality("网页又改的");
            equal("网页又改的", settings.chatPersonality(), "有文件时写入立即生效");
            check(Files.readString(settings.chatPersonalityFile(), StandardCharsets.UTF_8).strip().equals("网页又改的"),
                    "写入落在人设文件里");
            check(!Files.readString(root.resolve("config.json"), StandardCharsets.UTF_8).contains("网页又改的"),
                    "config.json 不再被人设写入污染");
            check(!Files.exists(settings.chatPersonalityFile().resolveSibling("chat-personality-kotori.txt.tmp")),
                    "原子写入不留临时文件");
        } finally {
            delete(root);
        }
    }

    private static void configIsTheFallback() throws Exception {
        Path root = workspace("blank-file");
        try {
            write(root.resolve("config.json"), "{\"chat\":{\"personality\":\"配置里那份\"}}");
            Settings settings = new Settings(root);
            write(settings.chatPersonalityFile(), "   \n\n");
            equal("配置里那份", settings.chatPersonality(), "文件是空白时退回 config.json，而不是用空人设");
            expectFailure(() -> settings.setChatPersonality("  "), "不能为空", "拒绝写空人设");
        } finally {
            delete(root);
        }
    }

    private static Path workspace(String name) throws Exception {
        Path work = Path.of("work", "personality-tests").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, name + "-");
        Files.createDirectories(root.resolve("data"));
        return root;
    }

    private static void write(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private static void delete(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        assertions++;
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError(message + "（期望 " + expected + "，实际 " + actual + "）");
    }

    private static void expectFailure(ThrowingRunnable action, String contains, String message) throws Exception {
        assertions++;
        try {
            action.run();
        } catch (Exception error) {
            String text = String.valueOf(error.getMessage());
            if (text != null && text.contains(contains)) return;
            throw new AssertionError(message + "（错误信息是 " + text + "）");
        }
        throw new AssertionError(message + "（居然成功了）");
    }

    private interface ThrowingRunnable { void run() throws Exception; }
}
