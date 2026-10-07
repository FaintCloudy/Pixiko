package cn.szu.bot.prompt;

import java.util.*;

/** Comma-delimited prompt items; nested emphasis, schedules and LoRA syntax stay intact. */
public final class PromptEditor {
    public record Result(String text, List<String> changed, List<String> unchanged) {}

    public static List<String> parts(String text) {
        List<String> result = new ArrayList<>();
        Deque<Character> stack = new ArrayDeque<>();
        int start = 0; boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (ch == '\\') { escaped = true; continue; }
            if (ch == '(' || ch == '[' || ch == '<') stack.push(ch);
            else if (ch == ')' || ch == ']' || ch == '>') {
                char expected = ch == ')' ? '(' : ch == ']' ? '[' : '<';
                if (stack.isEmpty() || stack.pop() != expected) throw new IllegalArgumentException("提示词括号不匹配，请修正后重试。");
            } else if (isSeparator(ch) && stack.isEmpty()) {
                add(result, text.substring(start, i)); start = i + 1;
            }
        }
        if (!stack.isEmpty() || escaped) throw new IllegalArgumentException("提示词括号或转义不完整，请修正后重试。");
        add(result, text.substring(start));
        return result;
    }
    /**
     * 词条分隔符：半角逗号、全角逗号 {@code ，}、中文顿号 {@code 、}。
     *
     * <p>模型经常用中文标点写词条列表（用户实测那次是 {@code "Yasaka Menoa、1girl、skirt lift…"}）：
     * 只认半角逗号时整段会被当成**一个**词条，LoRA 标签也会跟着被判成"不在提示词里"。
     */
    public static boolean isSeparator(char ch) { return ch == ',' || ch == '，' || ch == '、'; }
    /**
     * 把全角逗号/顿号统一成半角逗号，并把**原样保留**的全角引号折成半角：
     * 这是模型输出落地前的规范化（见 Bot 的落地路径），保证多写的分隔符不会变成一个巨型词条。
     * 括号/转义写坏时原样返回，交给调用方既有的校验去拒绝。
     */
    public static String normalizeSeparators(String text) {
        if (text == null || text.isBlank()) return text;
        if (text.indexOf('，') < 0 && text.indexOf('、') < 0
                && text.indexOf('\u201c') < 0 && text.indexOf('\u201d') < 0) return text;
        List<String> parts;
        try { parts = parts(text); } catch (IllegalArgumentException broken) { return text; }
        return String.join(", ", parts).replace('\u201c', '"').replace('\u201d', '"');
    }
    private static void add(List<String> result, String value) { if (!value.isBlank()) result.add(value.strip()); }
    private static String unwrapped(String text) {
        String value = text.strip();
        // An emphasis wrapper identifies the same term while preserving its original weight in output.
        while ((value.startsWith("(") && value.endsWith(")")) || (value.startsWith("[") && value.endsWith("]"))) {
            String inner = value.substring(1, value.length() - 1);
            if (inner.contains(",") || inner.contains("(") || inner.contains("[") || inner.contains("|")) break;
            if (value.startsWith("(")) inner = inner.replaceFirst(":[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)$", "");
            else if (inner.contains(":")) break;
            value = inner.strip();
        }
        return value;
    }
    public static String key(String text) {
        return unwrapped(text).replace("\\(", "(").replace("\\)", ")").replace("\\[", "[").replace("\\]", "]")
                .replace('_', ' ').replaceAll("(?U)\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }
    private static String resolve(String query, Collection<String> candidates, boolean required) {
        String wanted = key(query);
        Map<String, String> unique = new LinkedHashMap<>();
        for (String value : candidates) unique.putIfAbsent(key(value), value);
        // Fully specified input wins; do not strip supplied emphasis/weight on add.
        if (unique.containsKey(wanted)) return unique.get(wanted);
        List<String> matches = unique.entrySet().stream().filter(e -> e.getKey().contains(wanted)).map(Map.Entry::getValue).toList();
        if (matches.size() > 1) throw new IllegalArgumentException("提示词“" + query + "”匹配多个候选，未修改。请提供更完整内容：\n" + String.join("\n", matches.subList(0, Math.min(12, matches.size()))));
        if (matches.size() == 1) return matches.get(0);
        if (required) throw new IllegalArgumentException("未找到提示词：“" + query + "”，未修改。");
        return query;
    }
    public static Result apply(String before, String operation, String input, Collection<String> vocabulary) {
        List<String> current = new ArrayList<>(parts(before));
        List<String> queries = parts(input);
        if (queries.isEmpty()) throw new IllegalArgumentException("add/remove 后必须提供有效提示词，不能只有逗号。");
        if (queries.size() > 100) throw new IllegalArgumentException("一次最多处理 100 项提示词。");
        List<String> changed = new ArrayList<>(), unchanged = new ArrayList<>();
        if (operation.equals("remove")) {
            Set<String> keys = new LinkedHashSet<>();
            for (String query : queries) keys.add(key(resolve(query, current, true)));
            current.removeIf(value -> { if (keys.contains(key(value))) { changed.add(value); return true; } return false; });
        } else {
            List<String> candidates = new ArrayList<>(current); candidates.addAll(vocabulary);
            List<String> resolved = new ArrayList<>();
            for (String query : queries) {
                String matched = resolve(query, candidates, false);
                // Explicit structured input carries intentional weights and must not inherit a template's weight.
                if (query.startsWith("<")) matched = query;
                else if (query.startsWith("(") || query.startsWith("[")) {
                    String inner = unwrapped(query);
                    if (!inner.equals(query)) matched = query.replace(inner, unwrapped(matched));
                }
                resolved.add(matched);
            }
            for (String value : resolved) {
                if (current.stream().anyMatch(existing -> key(existing).equals(key(value)))) { unchanged.add(value); continue; }
                current.add(value); changed.add(value);
            }
        }
        return new Result(String.join(", ", current), List.copyOf(changed), List.copyOf(unchanged));
    }
}
