package cn.szu.bot;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/** Shared judging and diagnostic helpers for the chain evaluations. */
final class ChainEvalSupport {
    private ChainEvalSupport() {}

    /**
     * A complete hit on the plan: every requested step present exactly once, in chain order, with
     * nothing extra — one basis, all edits covered by .infix, one trailing .gen, no duplicates.
     */
    static List<String> judgePlan(ChainTaskSet.ScaleTask task, List<String> commands) {
        ChainTaskSet.Spec spec = task.spec();
        List<String> problems = new ArrayList<>();
        int bases = 0, generations = 0, generationIndex = -1;
        List<String> infix = new ArrayList<>();
        for (int i = 0; i < commands.size(); i++) {
            String command = commands.get(i) == null ? "" : commands.get(i).strip();
            if (isBasis(command)) {
                bases++;
                if (spec.basisAlternatives().stream().noneMatch(alternative -> mentions(command, alternative)))
                    problems.add("基底指错了：" + command);
            } else if (command.matches("(?is)^[./]infix(?:\\s+.*)?$")) {
                infix.add(command);
            } else if (command.matches("(?is)^[./]gen(?:\\s+.*)?$")) {
                generations++;
                generationIndex = i;
            } else if (!command.matches("(?is)^[./]style\\s+clear$")) {
                problems.add("多余指令：" + command);
            }
        }
        if (spec.kind().isEmpty()) {
            if (bases > 0) problems.add("用户说不用样式，却加载了基底");
        } else if (bases != 1) {
            problems.add("基底指令 " + bases + " 条（应为 1）");
        }
        String joined = String.join(" \n ", infix);
        for (ChainTaskSet.Edit edit : spec.edits())
            if (edit.keys().stream().noneMatch(joined::contains)) problems.add("漏掉「" + edit.category() + edit.value() + "」");
        for (String command : infix)
            if (spec.edits().stream().noneMatch(edit -> edit.keys().stream().anyMatch(command::contains)))
                problems.add("改写与要求无关：" + command);
        if (spec.generate()) {
            if (generations != 1) problems.add(".gen " + generations + " 条（应为 1）");
            else {
                if (generationIndex != commands.size() - 1) problems.add("生成不在最后一步");
                if (spec.strictCount() && spec.count() > 1
                        && !commands.get(generationIndex).matches("(?is)^[./]gen\\s+" + spec.count() + "$"))
                    problems.add(".gen 张数不对：" + commands.get(generationIndex));
            }
        } else if (generations > 0) {
            problems.add("用户说不要生成，却出现 .gen");
        }
        Set<String> seen = new HashSet<>();
        for (String command : commands)
            if (!seen.add(command == null ? "" : command.strip())) problems.add("重复指令：" + command);
        return problems;
    }

    /** A #number must match exactly (#1 must not be satisfied by #16); names ignore spacing. */
    static boolean mentions(String command, String alternative) {
        if (alternative == null || alternative.isBlank()) return false;
        if (alternative.startsWith("#"))
            return Pattern.compile(Pattern.quote(alternative) + "(?![0-9])").matcher(command).find();
        return command.contains(alternative) || squash(command).contains(squash(alternative));
    }

    static boolean isBasis(String command) {
        return command.matches("(?is)^[./](?:style|lora|function)\\s+load(?:\\s+.*)?$")
                || command.matches("(?is)^[./]char\\s+apply(?:\\s+.*)?$");
    }

    private static String squash(String text) { return text.replaceAll("\\s+", ""); }

    static String classify(String problem) {
        if (problem.startsWith("漏掉")) return "漏掉某项修改";
        if (problem.contains("基底")) return "基底缺失或指错";
        if (problem.startsWith(".gen") || problem.contains("生成")) return "生成步骤错误";
        if (problem.startsWith("多余") || problem.startsWith("改写与要求无关")) return "多余指令";
        if (problem.startsWith("重复")) return "重复指令";
        return "其他";
    }

    static String truncate(String raw) {
        if (raw == null || raw.isBlank()) return "(空)";
        return raw.length() > 700 ? raw.substring(0, 700) + "…" : raw;
    }

    static String cell(String text) {
        if (text == null || text.isBlank()) return "—";
        return text.replace("|", "\\|").replace("\n", " ");
    }

    static String requestOf(JsonObject body) {
        if (!body.has("messages") || !body.get("messages").isJsonArray()) return "(无消息)";
        JsonArray messages = body.getAsJsonArray("messages");
        String content = Json.str(messages.get(messages.size() - 1).getAsJsonObject(), "content", "");
        try {
            JsonObject user = Json.parse(content);
            String message = Json.str(user, "current_message", "");
            if (!message.isBlank()) return message;
        } catch (Exception ignored) { }
        return content.length() > 120 ? content.substring(0, 120) + "…" : content;
    }

    static String responseOf(int status, String body) {
        try {
            JsonObject choice = Json.parse(body).getAsJsonArray("choices").get(0).getAsJsonObject();
            return Json.str(choice.getAsJsonObject("message"), "content", "");
        } catch (Exception error) {
            return "HTTP " + status + " " + (body.length() > 200 ? body.substring(0, 200) : body);
        }
    }

    static String fingerprint(JsonObject body) {
        if (!body.has("messages") || !body.get("messages").isJsonArray()) return null;
        for (JsonElement element : body.getAsJsonArray("messages")) {
            if (!element.isJsonObject()) continue;
            JsonObject message = element.getAsJsonObject();
            if (!"system".equals(Json.str(message, "role", ""))) continue;
            String content = Json.str(message, "content", "");
            if (content.contains("interest")) {
                try {
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
                    StringBuilder hex = new StringBuilder();
                    for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i]));
                    return hex + "（长度 " + content.length() + "）";
                } catch (Exception error) { return "(哈希失败)"; }
            }
        }
        return null;
    }
}
