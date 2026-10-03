package cn.szu.bot;

import cn.szu.bot.sd.LocalStyles;
import cn.szu.bot.sd.StylePreviews;

import java.util.Comparator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 样式的预览图：只给网页控制台看的那张图，按样式名的哈希落盘，改名/删除时跟着走。
 * 纯文件系统操作，不碰网络、不碰 WebUI。
 */
public final class StylePreviewsTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "style-previews").toAbsolutePath();
        Files.createDirectories(work);
        // 真样式名就是长这样：带空格、斜杠、括号——不能直接拿来当文件名。
        String name = "観月小鳥(mizuki kotori) 遊戯王ZEXAL / Tori Meadows Yu-Gi-Oh! ZEXAL 1";

        Path root = Files.createTempDirectory(work, "case-");
        try {
            check(!StylePreviews.has(root, name), "一开始没有预览图");
            check(StylePreviews.file(root, null) == null && StylePreviews.file(root, "  ") == null,
                    "空名字不给文件路径（不炸）");
            StylePreviews.save(root, name, new byte[64]);
            check(StylePreviews.has(root, name), "存完就能查到");
            check(StylePreviews.file(root, name).getFileName().toString().matches("[0-9a-f]{16}\\.png"),
                    "文件名是名字的哈希：" + StylePreviews.file(root, name).getFileName());
            check(StylePreviews.file(root, name).getParent().equals(StylePreviews.dir(root)),
                    "图都放在 data/style-previews 下");
            check(!StylePreviews.has(root, name + " 2"), "别的样式查不到这张图（哈希不同）");
            check(StylePreviews.file(root, name).toString().equals(StylePreviews.file(root, name).toString())
                            && StylePreviews.file(root, name).equals(StylePreviews.file(root, name)),
                    "同一个名字每次都算到同一个文件（不需要索引文件）");
            StylePreviews.save(root, "太小了", new byte[8]);
            check(!StylePreviews.has(root, "太小了"), "几十字节的响应体不算图片，不落盘");

            StylePreviews.rename(root, name, "新名字");
            check(!StylePreviews.has(root, name) && StylePreviews.has(root, "新名字"), "改名后图跟着搬");
            StylePreviews.rename(root, "根本没有这个样式", "随便");
            check(!StylePreviews.has(root, "随便"), "搬不存在的东西不留垃圾文件");
            StylePreviews.delete(root, "新名字");
            check(!StylePreviews.has(root, "新名字"), "删除后图没了");
            StylePreviews.delete(root, "压根没有");
            check(true, "删不存在的样式也不报错");
        } finally { deleteTree(root); }

        // 样式库自己的改名/删除要顺带把图带走——不然新名字查不到图，旧文件也永远没人删。
        Path library = Files.createTempDirectory(work, "case-");
        try {
            LocalStyles styles = new LocalStyles(library);
            styles.save("旧名字", "portrait", "bad", false);
            StylePreviews.save(library, "旧名字", new byte[64]);
            styles.rename("旧名字", "新名字", false);
            check(StylePreviews.has(library, "新名字") && !StylePreviews.has(library, "旧名字"),
                    "样式改名带着预览图一起走");
            check(styles.get("新名字") != null, "样式本身也改名成功");
            styles.delete("新名字");
            check(!StylePreviews.has(library, "新名字"), "样式删除时预览图一起删");
        } finally { deleteTree(library); }

        System.out.println("StylePreviewsTest: " + checks + " assertions passed: 按名哈希落盘、改名搬运、删除清理。");
    }

    private static void check(boolean condition, String detail) {
        checks++;
        if (!condition) throw new AssertionError(detail);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
