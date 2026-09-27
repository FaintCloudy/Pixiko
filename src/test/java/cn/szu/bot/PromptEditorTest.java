package cn.szu.bot;
import java.util.*;
import cn.szu.bot.prompt.PromptEditor;
public final class PromptEditorTest {
    public static void main(String[] args) {
        var removed = PromptEditor.apply("cat, , blue_eyes, dog,", "remove", "blue ey", List.of());
        assert removed.text().equals("cat, dog"); assert removed.changed().equals(List.of("blue_eyes"));
        assert PromptEditor.apply("blue eyes, cat", "remove", "BLUE_EYES", List.of()).text().equals("cat");
        assert PromptEditor.apply("cat, (blue_eyes:1.2), dog", "remove", "blue eyes", List.of()).changed().equals(List.of("(blue_eyes:1.2)"));
        assert PromptEditor.apply("blue_eyes, blue eyes, dog", "remove", "blue ey", List.of()).changed().size() == 2;
        expect(() -> PromptEditor.apply("blue eyes, blue hair", "remove", "blue", List.of()), "多个候选");
        expect(() -> PromptEditor.apply("cat", "remove", "dog", List.of()), "未找到");
        assert PromptEditor.apply("cat", "remove", "cat", List.of()).text().isEmpty();
        assert PromptEditor.apply("a.*, dog", "remove", "a.*", List.of()).text().equals("dog");
        var added = PromptEditor.apply("cat,,", "add", ",blue ey,，", List.of("blue_eyes"));
        assert added.text().equals("cat, blue_eyes"); assert added.changed().equals(List.of("blue_eyes"));
        assert PromptEditor.apply("cat", "add", "blue_eyes", List.of("blue eyes")).text().equals("cat, blue eyes");
        assert PromptEditor.apply("blue eyes", "add", "blue_eyes", List.of()).unchanged().equals(List.of("blue eyes"));
        assert PromptEditor.apply("", "add", "invented custom phrase", List.of()).text().equals("invented custom phrase");
        assert PromptEditor.apply("", "add", "(blue ey:1.4)", List.of("blue_eyes")).text().equals("(blue_eyes:1.4)");
        expect(() -> PromptEditor.apply("cat", "add", "blue", List.of("blue eyes", "blue hair")), "多个候选");
        assert PromptEditor.parts("(red, blue:1.2), [a:b:0.5], <lora:Name:1>").size() == 3;
        assert PromptEditor.apply("(red, blue:1.2), cat", "add", "dog", List.of()).text().equals("(red, blue:1.2), cat, dog");
        assert PromptEditor.apply("", "add", "character_(series)", List.of("character_\\(series\\)")).text().equals("character_\\(series\\)");
        expect(() -> PromptEditor.apply("cat", "add", "(broken", List.of()), "不完整");
        expect(() -> PromptEditor.apply("cat", "add", "dog]", List.of()), "不匹配");
        expect(() -> PromptEditor.apply("cat", "add", ",,，", List.of()), "有效提示词");
        System.out.println("PromptEditorTest PASS: unique completion, ambiguity, separators, feedback, weights, escaping, duplicate protection.");
    }
    static void expect(Runnable action, String message) {
        try { action.run(); throw new AssertionError("expected rejection"); }
        catch (IllegalArgumentException expected) { assert expected.getMessage().contains(message) : expected.getMessage(); }
    }
}
