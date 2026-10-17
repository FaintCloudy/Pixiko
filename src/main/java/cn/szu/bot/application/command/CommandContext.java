package cn.szu.bot.application.command;

import cn.szu.bot.domain.ConversationId;
import cn.szu.bot.domain.Scope;
import cn.szu.bot.domain.UserId;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 一条指令被派发时需要的全部上下文。
 *
 * <p>字段是**当前真的用得上**的最小集合：谁发的（{@link UserId}）、在哪个会话（{@link ConversationId}）、
 * 原文（{@code raw}，已把 {@code .help} 归一成 {@code /help}）、参数、是不是网页控制台发来的、
 * 以及一个"说一句话"的出口。
 *
 * <p>{@code event} 是过渡期字段：Phase 2 只做"委托既有实现"，handler 需要原始事件才能调
 * {@code qqMode}/{@code imageMode} 这些还没搬家的方法。等指令体真正搬进来之后它就可以去掉，
 * 那时 handler 只依赖本 record 的字段。
 *
 * <p>不可变：{@code args} 是 {@link List#copyOf} 的只读副本，{@code replySink} 为 {@code null} 时
 * 归一成"什么都不发"。
 */
public record CommandContext(UserId sender, ConversationId conversation, String raw, List<String> args,
                             boolean fromWeb, JsonObject event, Consumer<String> replySink) {

    public CommandContext {
        sender = sender == null ? UserId.of(null) : sender;
        conversation = conversation == null ? ConversationId.of(Scope.DEVICE, null) : conversation;
        raw = raw == null ? "" : raw;
        args = args == null ? List.of() : List.copyOf(args);
        replySink = replySink == null ? ignored -> { } : replySink;
    }

    /**
     * 由一条 QQ/网页事件造出上下文。**只读事件、不抛**：会话/用户的 id 取不到时归一成占位值，
     * 指令的走向绝不会因为"消息里少了个字段"而改变。
     *
     * @param event     原始事件（允许为 {@code null}，此时一切归一成占位值）
     * @param raw       已经归一化的指令原文（{@code .help} → {@code /help}）
     * @param replySink 这条指令要说话时用的出口（允许为 {@code null}）
     */
    public static CommandContext of(JsonObject event, String raw, Consumer<String> replySink) {
        JsonObject source = event == null ? new JsonObject() : event;
        boolean web = bool(source, "webui");
        String kind = string(source, "message_type");
        Scope scope = web ? Scope.WEB : switch (kind) {
            case "group" -> Scope.GROUP;
            case "private" -> Scope.PRIVATE;
            default -> Scope.DEVICE;
        };
        String senderId = string(source, "user_id");
        String conversationId = "group".equals(kind) ? string(source, "group_id") : senderId;
        return new CommandContext(UserId.of(senderId), ConversationId.of(scope, conversationId),
                raw, argumentsOf(raw), web, event, replySink);
    }

    /**
     * 注册表用的指令名：原文第一个空白分隔的词的正文，去掉前导 {@code /} 或 {@code .}。
     * 大小写保持原样——{@code /HELP} 不是 {@code /help}，这样"注册表命中"与原来 dispatch 里的
     * 字符串比较/正则判据完全一致。
     */
    public String command() {
        String text = raw.strip();
        if (text.isEmpty()) return "";
        String first = text.split("\\s+", 2)[0];
        if (first.startsWith("/") || first.startsWith(".")) return first.substring(1);
        return first;
    }

    /** 第一个词之后的其余部分，按空白切成词（{@code /help} 为空）。 */
    public static List<String> argumentsOf(String raw) {
        String text = raw == null ? "" : raw.strip();
        String[] parts = text.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) return List.of();
        return List.of(parts[1].strip().split("\\s+"));
    }

    /** 说一句话（{@code replySink} 为空时是空操作）。 */
    public void reply(String text) {
        replySink.accept(text);
    }

    private static String string(JsonObject object, String key) {
        return cn.szu.bot.Json.str(object, key, "");
    }

    private static boolean bool(JsonObject object, String key) {
        return cn.szu.bot.Json.bool(object, key, false);
    }

    /** 日志用：会话 + 掩码后的用户，不带任何提示词/令牌。 */
    @Override
    public String toString() {
        return "CommandContext(" + conversation + ", " + sender + ", raw=" + raw.length() + " 字符"
                + (fromWeb ? ", web" : "") + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CommandContext that
                && fromWeb == that.fromWeb
                && sender.equals(that.sender)
                && conversation.equals(that.conversation)
                && raw.equals(that.raw)
                && args.equals(that.args)
                && Objects.equals(event, that.event);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sender, conversation, raw, args, fromWeb, event);
    }
}
