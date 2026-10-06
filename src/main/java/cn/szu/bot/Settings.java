package cn.szu.bot;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;

public final class Settings {
    public final Path root;
    private JsonObject data;
    public Settings(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        data = Json.parse(Files.readString(this.root.resolve("config.json")));
        for (String key : new String[]{"allowed_group_ids", "allowed_user_ids", "admin_user_ids"}) {
            if (data.has(key) && !data.get(key).isJsonArray()) throw new IllegalArgumentException(key + " 必须是数组");
        }
    }
    public synchronized JsonObject snapshot() { return data.deepCopy(); }
    /** 重新读一遍 config.json（配置向导写完盘之后调用）。 */
    public synchronized void reload() throws IOException {
        data = Json.parse(Files.readString(root.resolve("config.json")));
    }
    public Path resolve(String name) { return root.resolve(name).normalize().toAbsolutePath(); }
    public synchronized Path mapPath(String campus) {
        return resolve(Json.str(Json.obj(data, "maps"), campus, "maps/" + campus));
    }
    public synchronized void setMapPath(String campus, String name) throws IOException {
        if (!campus.equals("yh") && !campus.equals("liv")) throw new IllegalArgumentException("地图类型应为 yh 或 liv");
        Path path = resolve(name);
        if (!Files.isDirectory(path) && !(Files.isRegularFile(path) && Maps.supported(path)))
            throw new IllegalArgumentException("路径必须是存在的图片文件或文件夹：" + path);
        JsonObject updated = freshSnapshot();
        JsonObject maps = Json.obj(updated, "maps");
        maps.addProperty(campus, path.toString()); updated.add("maps", maps);
        Json.atomicWrite(root.resolve("config.json"), updated);
        data = updated;
    }
    /** Global master switch for everyday chat. */
    public synchronized boolean chatEnabled() { return Json.bool(Json.obj(data, "chat"), "enabled", true); }
    /** Effective switch for one conversation key: the global master switch AND the per-conversation switch. */
    public synchronized boolean chatEnabled(String conversation) {
        return chatEnabled() && !disabledChats(Json.obj(data, "chat")).contains(conversation);
    }
    /** Flips the switch of a single conversation; returns the new effective state for that conversation. */
    public synchronized boolean toggleChat(String conversation) throws IOException {
        JsonObject next = freshSnapshot(), chat = Json.obj(next, "chat");
        java.util.TreeSet<String> disabled = new java.util.TreeSet<>(disabledChats(chat));
        boolean enabled = disabled.remove(conversation);
        if (!enabled) disabled.add(conversation);
        JsonArray updated = new JsonArray();
        for (String key : disabled) updated.add(key);
        chat.add("disabled_conversations", updated); next.add("chat", chat);
        Json.atomicWrite(root.resolve("config.json"), next); data = next;
        return chatEnabled(conversation);
    }
    /** Sets the global master switch; returns the new global state. */
    public synchronized boolean setChatEnabled(boolean enabled) throws IOException {
        JsonObject next = freshSnapshot(), chat = Json.obj(next, "chat");
        chat.addProperty("enabled", enabled); next.add("chat", chat);
        Json.atomicWrite(root.resolve("config.json"), next); data = next; return enabled;
    }
    private static java.util.List<String> disabledChats(JsonObject chat) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        JsonElement value = chat.get("disabled_conversations");
        if (value != null && value.isJsonArray())
            for (JsonElement item : value.getAsJsonArray())
                if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) keys.add(item.getAsString());
        return keys;
    }
    /**
     * Whether "/infix" enforces the standard dictionary in one conversation. Off by default: the rewrite is
     * free-form (natural language allowed), which measured better than forcing canonical tags. Switching it on
     * restricts newly introduced terms to data/prompt-tags.txt (words already in the user's prompt, including
     * LoRA tags, are always allowed).
     */
    public synchronized boolean infixFilterEnabled(String conversation) {
        return enabledInfixFilter(Json.obj(data, "infix")).contains(conversation);
    }
    /** Turns the dictionary constraint on or off for one conversation; returns the new state. */
    public synchronized boolean setInfixFilterEnabled(String conversation, boolean enabled) throws IOException {
        JsonObject next = freshSnapshot(), infix = Json.obj(next, "infix");
        java.util.TreeSet<String> keys = new java.util.TreeSet<>(enabledInfixFilter(infix));
        if (enabled) keys.add(conversation); else keys.remove(conversation);
        JsonArray updated = new JsonArray();
        for (String key : keys) updated.add(key);
        infix.add("filter_enabled_conversations", updated); next.add("infix", infix);
        Json.atomicWrite(root.resolve("config.json"), next); data = next;
        return enabled;
    }
    private static java.util.List<String> enabledInfixFilter(JsonObject infix) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        JsonElement value = infix.get("filter_enabled_conversations");
        if (value != null && value.isJsonArray())
            for (JsonElement item : value.getAsJsonArray())
                if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) keys.add(item.getAsString());
        return keys;
    }
    /**
     * 「全局 infix」改造档位，按会话持久化（{@code infix_mode.modes.<会话键>}）。
     *
     * <p>实测 bug（logs/bot-20261006.log 15:30、data/quests/{78,252,255}.json）：用户一条复合语句
     * （一段消息里同时含"改提示词"和"出图"）被拆成 **3 个 .infix 步骤 + 1 个 .gen**，每一步都拿**整份**
     * 提示词去改写一次——前一步刚写进去的内容会被后一步"没提到它"而删掉，几步互相打架。
     * 现在默认 {@link #GLOBAL}：一条指令里的所有改写诉求**合成一次**交给 DeepSeek，只改一次、只落地一次；
     * {@link #PARTS} 保留旧的拆分/多步实现，供用户切回去。
     */
    public enum InfixMode {
        GLOBAL("global", "全局"), PARTS("parts", "分组");
        private final String key, label;
        InfixMode(String key, String label) { this.key = key; this.label = label; }
        /** 存进 config.json 的取值。 */
        public String key() { return key; }
        /** 回执里给用户看的中文说法。 */
        public String label() { return label; }
        /**
         * 从 config.json 里读到的值：大小写不敏感，缺失/未知/类型不对一律当 global（默认档）。
         * 手写的、被改坏的或旧版本的配置都不该让机器人读配置失败，更不该悄悄退回"多组各自改写"。
         */
        public static InfixMode stored(String value) {
            if (value == null) return GLOBAL;
            return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "parts", "part", "split", "分组", "多组", "拆分" -> PARTS;
                default -> GLOBAL;
            };
        }
        /** 用户输入的参数（含中文别名）；认不出来返回 null，由调用方给出用法。 */
        public static InfixMode parse(String value) {
            if (value == null || value.isBlank()) return null;
            return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "global", "one", "once", "whole", "all", "全局", "整体", "一次" -> GLOBAL;
                case "parts", "part", "split", "step", "steps", "分组", "多组", "拆分", "逐步" -> PARTS;
                default -> null;
            };
        }
    }
    /** 一个会话的 infix 档位；没有设置过（含老配置）就是 global。 */
    public synchronized InfixMode infixMode(String conversation) {
        JsonElement modes = Json.obj(data, "infix_mode").get("modes");
        if (modes == null || !modes.isJsonObject()) return InfixMode.GLOBAL;
        JsonElement value = modes.getAsJsonObject().get(conversation);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return InfixMode.GLOBAL;
        return InfixMode.stored(value.getAsString());
    }
    /**
     * 保存一个会话的 infix 档位；{@link InfixMode#GLOBAL} 是默认值，等于删掉这条设置
     * （与 {@code qq_mode} / {@code image_send} 同一套写法：config.json 里只留显式设过 parts 的会话）。
     * 返回保存后的值。
     */
    public synchronized InfixMode setInfixMode(String conversation, InfixMode mode) throws IOException {
        if (mode == null) throw new IllegalArgumentException("infix 档位不能为空。");
        if (conversation == null || conversation.isBlank()) throw new IllegalArgumentException("会话键不能为空。");
        JsonObject next = freshSnapshot(), section = Json.obj(next, "infix_mode"), modes = Json.obj(section, "modes");
        // 逐键写回：别的会话（以及别的代理写进同一段的键）都不会被这次保存抹掉。
        java.util.LinkedHashMap<String, String> stored = new java.util.LinkedHashMap<>();
        for (String key : modes.keySet()) {
            JsonElement value = modes.get(key);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    && InfixMode.stored(value.getAsString()) != InfixMode.GLOBAL) stored.put(key, value.getAsString());
        }
        if (mode == InfixMode.GLOBAL) stored.remove(conversation);
        else stored.put(conversation, mode.key());
        JsonObject updated = new JsonObject();
        for (java.util.Map.Entry<String, String> entry : stored.entrySet()) updated.addProperty(entry.getKey(), entry.getValue());
        section.add("modes", updated); next.add("infix_mode", section);
        Json.atomicWrite(root.resolve("config.json"), next); data = next;
        return mode;
    }
    /**
     * 是否启用**传统正反义词排斥器**（本地互斥词表：姿势/视角/载具/室内外 + 反义词对）。
     *
     * <p>按会话持久化（{@code infix_mode.conflict_enabled.<会话键>}，存的是**打开**的会话）。
     * <b>默认关闭</b>：用户实测 {@code cross-section view} 与 {@code front view} 被判成互斥而被删掉一个，
     * 但剖面图与正面视角并不冲突——这类判断全权交给改写之后那次"整份提示词画面检查"（DeepSeek）。
     * 打开时恢复旧的本地行为（秒杀同族词条，不问模型）。
     */
    public synchronized boolean infixConflictEnabled(String conversation) {
        return enabledInfixConflict(Json.obj(data, "infix_mode")).contains(conversation);
    }
    /** 打开/关闭一个会话的传统正反义词排斥器；返回新状态。 */
    public synchronized boolean setInfixConflictEnabled(String conversation, boolean enabled) throws IOException {
        if (conversation == null || conversation.isBlank()) throw new IllegalArgumentException("会话键不能为空。");
        JsonObject next = freshSnapshot(), section = Json.obj(next, "infix_mode");
        java.util.TreeSet<String> keys = new java.util.TreeSet<>(enabledInfixConflict(section));
        if (enabled) keys.add(conversation); else keys.remove(conversation);
        JsonArray updated = new JsonArray();
        for (String key : keys) updated.add(key);
        section.add("conflict_enabled", updated); next.add("infix_mode", section);
        Json.atomicWrite(root.resolve("config.json"), next); data = next;
        return enabled;
    }
    private static java.util.List<String> enabledInfixConflict(JsonObject section) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        JsonElement value = section.get("conflict_enabled");
        if (value != null && value.isJsonArray())
            for (JsonElement item : value.getAsJsonArray())
                if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) keys.add(item.getAsString());
        return keys;
    }
    /**
     * Whether "/prompt drop|keep" may fall back to the composite-phrase classification model
     * ({@link cn.szu.bot.prompt.CategoryModel}) for a phrase it cannot classify locally. On by default:
     * the model only ever removes the fragments belonging to the requested category, its answers are cached
     * on disk, and a failure leaves the phrase untouched. Off means every decision stays local.
     *
     * <p>按会话持久化，与 {@link #infixFilterEnabled} 同一套写法（存的是**关掉**的会话，默认开启）。
     */
    public synchronized boolean categoryModelEnabled(String conversation) {
        return !disabledCategoryModel(Json.obj(data, "category_model")).contains(conversation);
    }
    /** Turns the fallback model on or off for one conversation; returns the new state. */
    public synchronized boolean setCategoryModelEnabled(String conversation, boolean enabled) throws IOException {
        JsonObject next = freshSnapshot(), section = Json.obj(next, "category_model");
        java.util.TreeSet<String> keys = new java.util.TreeSet<>(disabledCategoryModel(section));
        if (enabled) keys.remove(conversation); else keys.add(conversation);
        JsonArray updated = new JsonArray();
        for (String key : keys) updated.add(key);
        section.add("disabled_conversations", updated); next.add("category_model", section);
        Json.atomicWrite(root.resolve("config.json"), next); data = next;
        return enabled;
    }
    private static java.util.List<String> disabledCategoryModel(JsonObject section) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        JsonElement value = section.get("disabled_conversations");
        if (value != null && value.isJsonArray())
            for (JsonElement item : value.getAsJsonArray())
                if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) keys.add(item.getAsString());
        return keys;
    }
    /**
     * 出图的发送形式，按会话持久化（{@code image_send.modes.<会话键>}）。
     *
     * <p>{@link #AUTO} 是默认值，也是老配置（没有 {@code image_send} 段）读出来的样子：
     * 一批多于一张时合成一条「合并转发」，单张保持普通发送。{@link #RECORD} 一律合并转发（一张也合并），
     * {@link #SINGLE} 一律逐张普通发送（多张也逐张）。传输层不会发合并转发时，record 会如实回退普通发送。
     */
    public enum ImageSendMode {
        AUTO("auto", "自动"), RECORD("record", "合并转发"), SINGLE("single", "普通发送");
        private final String key, label;
        ImageSendMode(String key, String label) { this.key = key; this.label = label; }
        /** 存进 config.json 的取值。 */
        public String key() { return key; }
        /** 回执里给用户看的中文说法。 */
        public String label() { return label; }
        /**
         * 从 config.json 里读到的值：大小写不敏感，缺失/未知/类型不对一律当 auto。
         * 手写的、被改坏的或旧版本的配置都不该让机器人读配置失败。
         */
        public static ImageSendMode stored(String value) {
            if (value == null) return AUTO;
            return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "record", "forward" -> RECORD;
                case "single", "plain" -> SINGLE;
                default -> AUTO;
            };
        }
        /** 用户输入的参数（含中文别名）；认不出来返回 null，由调用方给出用法。 */
        public static ImageSendMode parse(String value) {
            if (value == null) return null;
            return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "record", "forward", "聊天记录", "合并转发", "转发" -> RECORD;
                case "single", "plain", "普通", "普通发送", "单张", "逐张" -> SINGLE;
                case "auto", "自动", "默认" -> AUTO;
                default -> null;
            };
        }
    }
    /** 一个会话的出图发送形式；没有设置过（含老配置）就是 auto。 */
    public synchronized ImageSendMode imageSendMode(String conversation) {
        JsonElement modes = Json.obj(data, "image_send").get("modes");
        if (modes == null || !modes.isJsonObject()) return ImageSendMode.AUTO;
        JsonElement value = modes.getAsJsonObject().get(conversation);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return ImageSendMode.AUTO;
        return ImageSendMode.stored(value.getAsString());
    }
    /** 保存一个会话的出图发送形式；auto 就是删掉这条设置（回到默认）。返回保存后的值。 */
    public synchronized ImageSendMode setImageSendMode(String conversation, ImageSendMode mode) throws IOException {
        if (mode == null) throw new IllegalArgumentException("发送形式不能为空。");
        JsonObject next = freshSnapshot(), section = Json.obj(next, "image_send"), modes = Json.obj(section, "modes");
        // 逐键写回：别的会话（以及别的代理写进同一段的键）都不会被这次保存抹掉。
        java.util.TreeMap<String, String> stored = new java.util.TreeMap<>();
        for (String key : modes.keySet()) {
            JsonElement value = modes.get(key);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    && ImageSendMode.stored(value.getAsString()) != ImageSendMode.AUTO) stored.put(key, value.getAsString());
        }
        if (mode == ImageSendMode.AUTO) stored.remove(conversation);
        else stored.put(conversation, mode.key());
        JsonObject updated = new JsonObject();
        for (java.util.Map.Entry<String, String> entry : stored.entrySet()) updated.addProperty(entry.getKey(), entry.getValue());
        section.add("modes", updated); next.add("image_send", section);
        Json.atomicWrite(root.resolve("config.json"), next); data = next;
        return mode;
    }
    /**
     * QQ 侧的回执档位，按会话持久化（{@code qq_mode.modes.<会话键>}）。
     *
     * <p>{@link #DEBUG} 是默认值，也是老配置（没有 {@code qq_mode} 段）读出来的样子：回执就是现状的完整形态，
     * 一字不变。{@link #NORMAL} 只留"肯定"与图片——不显示生成参数，也不发多步执行、入队与领取计数这类过程回执；
     * <b>失败、被拒绝、权限不足、用法错误与需要用户决定的说明在 normal 下照旧发出</b>（安全底线，
     * 落点见 {@code Bot} 里各处回执的组装点）。
     */
    public enum QqMode {
        DEBUG("debug", "调试"), NORMAL("normal", "常规");
        private final String key, label;
        QqMode(String key, String label) { this.key = key; this.label = label; }
        /** 存进 config.json 的取值。 */
        public String key() { return key; }
        /** 回执里给用户看的中文说法。 */
        public String label() { return label; }
        /**
         * 从 config.json 里读到的值：大小写不敏感，缺失/未知/类型不对一律当 debug（默认档）。
         * 手写的、被改坏的或旧版本的配置都不该让机器人读配置失败，更不该悄悄改变现状行为。
         */
        public static QqMode stored(String value) {
            if (value == null) return DEBUG;
            return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "normal", "quiet", "简洁" -> NORMAL;
                default -> DEBUG;
            };
        }
        /** 用户输入的参数（含中文别名）；认不出来返回 null，由调用方给出用法。 */
        public static QqMode parse(String value) {
            if (value == null) return null;
            return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "normal", "quiet", "简洁", "常规" -> NORMAL;
                case "debug", "verbose", "full", "调试", "详细", "完整" -> DEBUG;
                default -> null;
            };
        }
    }
    /** 一个会话的回执档位；没有设置过（含老配置）就是 debug。 */
    public synchronized QqMode qqMode(String conversation) {
        JsonElement modes = Json.obj(data, "qq_mode").get("modes");
        if (modes == null || !modes.isJsonObject()) return QqMode.DEBUG;
        JsonElement value = modes.getAsJsonObject().get(conversation);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return QqMode.DEBUG;
        return QqMode.stored(value.getAsString());
    }
    /**
     * 保存一个会话的回执档位；{@link QqMode#DEBUG} 是默认值，等于删掉这条设置（与 {@code image_send} 同一套写法：
     * config.json 里只留显式设过 normal 的会话）。返回保存后的值。
     */
    public synchronized QqMode setQqMode(String conversation, QqMode mode) throws IOException {
        if (mode == null) throw new IllegalArgumentException("回执档位不能为空。");
        JsonObject next = freshSnapshot(), section = Json.obj(next, "qq_mode"), modes = Json.obj(section, "modes");
        // 逐键写回：别的会话（以及别的代理写进同一段的键）都不会被这次保存抹掉。
        java.util.TreeMap<String, String> stored = new java.util.TreeMap<>();
        for (String key : modes.keySet()) {
            JsonElement value = modes.get(key);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    && QqMode.stored(value.getAsString()) != QqMode.DEBUG) stored.put(key, value.getAsString());
        }
        if (mode == QqMode.DEBUG) stored.remove(conversation);
        else stored.put(conversation, mode.key());
        JsonObject updated = new JsonObject();
        for (java.util.Map.Entry<String, String> entry : stored.entrySet()) updated.addProperty(entry.getKey(), entry.getValue());
        section.add("modes", updated); next.add("qq_mode", section);
        Json.atomicWrite(root.resolve("config.json"), next); data = next;
        return mode;
    }
    /**
     * How long a numbered list stays usable for "#编号". This is separate from the chat topic window on
     * purpose: planning a multi-step request takes seconds, and a list the user just asked for must not
     * expire while the plan is still being built.
     */
    public synchronized int selectionGapSeconds() {
        return Math.max(5, Math.min(86400, (int) Json.num(Json.obj(data, "chat"), "selection_gap_seconds", 300)));
    }
    /**
     * Seconds the invited member has to accept a marriage proposal (".结婚 @某人"). The invitation is only
     * valid inside this window; it is 180 by default and configurable for tests.
     */
    public synchronized int marriageProposalSeconds() {
        return Math.max(5, Math.min(86400, (int) Json.num(Json.obj(data, "marriage"), "proposal_seconds", 180)));
    }
    /** Whether the online/offline announcements are posted to the main group. */
    /**
     * WebUI（网页控制台）配置。它只是机器人的另一个入口，读写的是同一份个人提示词、样式与生成队列。
     */
    public synchronized boolean webEnabled() { return Json.bool(Json.obj(data, "webui"), "enabled", true); }
    public synchronized String webHost() {
        String host = Json.str(Json.obj(data, "webui"), "host", "0.0.0.0").strip();
        return host.isBlank() ? "0.0.0.0" : host;
    }
    public synchronized int webPort() { return Math.max(1, Math.min(65535, Json.num(Json.obj(data, "webui"), "port", 8787))); }
    /** 网页控制台自己的提示词归属：与 QQ 完全分开，不依赖、也不记录任何 QQ 号。 */
    public static final String WEB_SCOPE = "web";
    /**
     * 网页控制台操作的个人提示词归属：固定用 {@link #WEB_SCOPE}，与 QQ 侧的 owner 提示词互不影响。
     * 旧配置里写过的 "owner"（或具体 QQ 号）会迁移成 web scope，避免网页继续改到 QQ 那份提示词。
     */
    public synchronized String webScope() throws IOException {
        String scope = Json.str(Json.obj(data, "webui"), "scope", "").strip();
        if (scope.equals(WEB_SCOPE)) return WEB_SCOPE;
        JsonObject next = freshSnapshot(), group = Json.obj(next, "webui");
        group.addProperty("scope", WEB_SCOPE);
        next.add("webui", group);
        Json.atomicWrite(root.resolve("config.json"), next);
        data = next;
        Log.info("网页控制台已改用独立的提示词归属「" + WEB_SCOPE + "」（原来跟着 QQ：" + (scope.isBlank() ? "未设置" : scope) + "）。");
        return WEB_SCOPE;
    }
    /** 远程访问令牌；没有配置时自动生成并保存，日志里给出一次，之后从 config.json 读取。 */
    public synchronized String webToken() throws IOException {
        String token = Json.str(Json.obj(data, "webui"), "access_token", "").strip();
        if (!token.isBlank()) return token;
        token = java.util.UUID.randomUUID().toString().replace("-", "");
        webSetting("access_token", new JsonPrimitive(token));
        Log.warn("WebUI 未配置访问令牌，已自动生成：" + token + "（保存在 config.json 的 webui.access_token）");
        return token;
    }
    /** 修改 webui 段里的一个键，其余配置保持不变。 */
    public synchronized void webSetting(String key, JsonElement value) throws IOException {
        JsonObject next = freshSnapshot(), group = Json.obj(next, "webui");
        group.add(key, value); next.add("webui", group);
        Json.atomicWrite(root.resolve("config.json"), next); data = next;
    }
    /** 修改生图频道（progen 段）里的一个键，聊天频道不受影响。 */
    public synchronized void progenSetting(String key, JsonElement value) throws IOException {
        JsonObject next = freshSnapshot(), group = Json.obj(next, "progen");
        group.add(key, value); next.add("progen", group);
        Json.atomicWrite(root.resolve("config.json"), next); data = next;
    }
    /** 修改 sd 段里的一个键（自启动开关、启动参数、SD 目录…）。 */
    public synchronized void sdSetting(String key, JsonElement value) throws IOException {
        JsonObject next = freshSnapshot(), group = Json.obj(next, "sd");
        group.add(key, value); next.add("sd", group);
        Json.atomicWrite(root.resolve("config.json"), next); data = next;
    }
    /** 修改 civitai 段里的一个键（登录 Cookie、镜像地址、LoRA 目录…）。 */
    public synchronized void civitaiSetting(String key, JsonElement value) throws IOException {
        JsonObject next = freshSnapshot(), group = Json.obj(next, "civitai");
        group.add(key, value); next.add("civitai", group);
        Json.atomicWrite(root.resolve("config.json"), next); data = next;
    }
    public synchronized boolean startupNoticeEnabled() { return Json.bool(data, "startup_notice_enabled", true); }    public synchronized void setStartupNoticeEnabled(boolean enabled) throws IOException { rootSetting("startup_notice_enabled", new JsonPrimitive(enabled)); }
    /** Whether WARN/ERROR log lines are mirrored into the main group for remote monitoring. */
    public synchronized boolean logMirrorEnabled() { return Json.bool(data, "log_mirror_enabled", false); }
    public synchronized void setLogMirrorEnabled(boolean enabled) throws IOException { rootSetting("log_mirror_enabled", new JsonPrimitive(enabled)); }
    /** Writes one top-level key and persists it. */
    /**
     * Re-reads config.json before merging a change. A long-running instance otherwise writes back the
     * snapshot it started with, silently deleting keys that were added to the file in the meantime
     * (this is how bot_name / owner_user_id / progen.thinking were lost before).
     */
    private synchronized JsonObject freshSnapshot() throws IOException {
        try { return Json.parse(Files.readString(root.resolve("config.json"))); }
        catch (Exception error) { return data.deepCopy(); }
    }
    public synchronized void rootSetting(String key, JsonElement value) throws IOException {
        JsonObject next = freshSnapshot(); next.add(key, value);
        Json.atomicWrite(root.resolve("config.json"), next); data = next;
    }
    /** Writes one key inside the chat channel's own API section (chat_api); the image channel is untouched. */
    public synchronized void chatApiSetting(String key, JsonElement value) throws IOException {
        JsonObject next = freshSnapshot(), api = Json.obj(next, "chat_api");
        api.add(key, value); next.add("chat_api", api);
        Json.atomicWrite(root.resolve("config.json"), next); data = next;
    }
    public synchronized int chatFrequency() { return (int) Json.num(Json.obj(data, "chat"), "frequency", 6); }
    /** 原作文本复现开关（chat.corpus_replay，默认开）：命中原作问答时参考原句。 */
    public synchronized boolean corpusReplay() { return Json.bool(Json.obj(data, "chat"), "corpus_replay", true); }
    /** 命中时最多注入几条原作问答（chat.corpus_top_k，默认 3）。 */
    public synchronized int corpusTopK() { return Math.max(1, Math.min(5, (int) Json.num(Json.obj(data, "chat"), "corpus_top_k", 3))); }
    public synchronized int chatContextSeconds() { return Math.max(300, Math.min(86400, Json.num(Json.obj(data, "chat"), "context_seconds", 1800))); }
    /**
     * 生效的人设文本。
     *
     * <p><b>优先读文件</b> {@code data/chat-personality-kotori.txt}：那份是随仓库同步的（换台机器接着干），
     * config.json 里的 {@code chat.personality} 只是它不存在时的退路。改人设请改文件，或者用
     * {@code .chat personality}／{@code .chat infix}——它们会写回文件。
     */
    public synchronized String chatPersonality() {
        String fromFile = readPersonalityFile();
        return fromFile.isEmpty() ? Json.str(Json.obj(data, "chat"), "personality", DEFAULT_PERSONALITY) : fromFile;
    }
    /** 人设文本文件（不存在就退回 config.json）。 */
    public Path chatPersonalityFile() { return root.resolve("data/chat-personality-kotori.txt"); }
    /** 写人设：有文件就写文件（同步用），否则写 config.json。 */
    public synchronized void setChatPersonality(String text) throws IOException {
        String value = text == null ? "" : text.strip();
        if (value.isEmpty()) throw new IllegalArgumentException("人设文本不能为空。");
        Path file = chatPersonalityFile();
        if (Files.isRegularFile(file)) {
            // 原子替换：先写同目录临时文件再 move，避免写到一半停电把同步用的人设弄坏。
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, value + System.lineSeparator(), java.nio.charset.StandardCharsets.UTF_8);
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            personalityStamp = stamp(file);
        } else {
            chatSetting("personality", new JsonPrimitive(value));
        }
    }
    /** 文件内容按 mtime 缓存：聊天每轮都要读人设，别每次都去碰磁盘。 */
    private String readPersonalityFile() {
        Path file = chatPersonalityFile();
        try {
            if (!Files.isRegularFile(file)) return "";
            long current = stamp(file);
            if (current == personalityStamp && personalityCache != null) return personalityCache;
            String text = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8).strip();
            personalityStamp = current;
            personalityCache = text;
            return text;
        } catch (Exception error) {
            return personalityCache == null ? "" : personalityCache;
        }
    }
    private static long stamp(Path file) {
        try { return Files.getLastModifiedTime(file).toMillis(); } catch (IOException error) { return -1; }
    }
    private static final String DEFAULT_PERSONALITY = "你是一个友善、自然、简洁的聊天机器人，用对方使用的语言交流。";
    private String personalityCache;
    private long personalityStamp = Long.MIN_VALUE;
    public synchronized void chatSetting(String key, JsonElement value) throws IOException {
        JsonObject next = freshSnapshot(), chat = Json.obj(next, "chat"); chat.add(key, value); next.add("chat", chat);
        Json.atomicWrite(root.resolve("config.json"), next); data = next;
    }
    public synchronized int imageCount() { return data.has("imgcnt") ? data.get("imgcnt").getAsInt() : 300; }
    public synchronized void imageCount(int count) throws IOException {
        if (count < 1) throw new IllegalArgumentException("图片上限须为正整数。");
        JsonObject next = freshSnapshot(); next.addProperty("imgcnt", count);
        Json.atomicWrite(root.resolve("config.json"), next); data = next;
    }
    public synchronized boolean autoGet() { return Json.bool(data, "gen_auto_get", true); }
    public synchronized boolean toggleAutoGet() throws IOException {
        JsonObject updated = freshSnapshot(); boolean enabled = !autoGet();
        updated.addProperty("gen_auto_get", enabled);
        Json.atomicWrite(root.resolve("config.json"), updated); data = updated; return enabled;
    }
    public synchronized boolean isAdmin(String user) { return contains("admin_user_ids", user, false); }
    /** True once any admin_user_ids entry exists; an empty list means the operator has not been configured yet. */
    public synchronized boolean hasAdmin() {
        JsonElement value = data.get("admin_user_ids");
        return value != null && value.isJsonArray() && !value.getAsJsonArray().isEmpty();
    }
    /**
     * The single owner account. Persisted on first use, so the default below is only ever a bootstrap value
     * written into config.json; afterwards the local file is authoritative.
     */
    public synchronized String ownerId() throws IOException {
        String configured = Json.str(data, "owner_user_id", "").strip();
        if (isUserId(configured)) return configured;
        // No shipped default owner: return empty rather than rewriting config.json on every call.
        if (DEFAULT_OWNER.isBlank()) return "";
        JsonObject updated = freshSnapshot();
        updated.addProperty("owner_user_id", DEFAULT_OWNER);
        Json.atomicWrite(root.resolve("config.json"), updated);
        data = updated;
        Log.info("已写入默认 owner_user_id：" + DEFAULT_OWNER + "（可在 config.json 中修改）");
        return DEFAULT_OWNER;
    }
    public synchronized boolean isOwner(String user) {
        String configured = Json.str(data, "owner_user_id", DEFAULT_OWNER).strip();
        if (!isUserId(configured)) configured = DEFAULT_OWNER;
        return configured.equals(user);
    }
    /** Owner or admin: the roles that may operate on someone else's conversation. */
    public synchronized boolean isStaff(String user) { return isOwner(user) || isAdmin(user); }
    public synchronized java.util.List<String> adminIds() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        JsonElement value = data.get("admin_user_ids");
        if (value != null && value.isJsonArray())
            for (JsonElement item : value.getAsJsonArray())
                if (item.isJsonPrimitive() && isUserId(item.getAsString())) ids.add(item.getAsString());
        return java.util.List.copyOf(ids);
    }
    /** Adds one admin; the owner cannot be added and duplicates are ignored. Returns the resulting list. */
    public synchronized java.util.List<String> addAdmin(String user) throws IOException {
        if (isUserId(user) == false) throw new IllegalArgumentException("请提供有效的 QQ 号。");
        if (isOwner(user)) throw new IllegalArgumentException("owner 已经是最高权限，无需加入 admin。");
        java.util.TreeSet<String> ids = new java.util.TreeSet<>(adminIds());
        ids.add(user);
        return writeAdmins(ids);
    }
    public synchronized java.util.List<String> removeAdmin(String user) throws IOException {
        java.util.TreeSet<String> ids = new java.util.TreeSet<>(adminIds());
        if (!ids.remove(user)) throw new IllegalArgumentException("该 QQ 号不在 admin 名单中：" + user);
        return writeAdmins(ids);
    }
    private java.util.List<String> writeAdmins(java.util.TreeSet<String> ids) throws IOException {
        JsonObject updated = freshSnapshot();
        JsonArray values = new JsonArray();
        for (String id : ids) values.add(id);
        updated.add("admin_user_ids", values);
        Json.atomicWrite(root.resolve("config.json"), updated);
        data = updated;
        return java.util.List.copyOf(ids);
    }
    /** QQ numbers only: they are also used as the per-user prompt scope and as file names. */
    public static boolean isUserId(String value) { return value != null && value.matches("[1-9][0-9]{0,19}"); }
    /** Display name for forward-message cards and help text; persisted locally on first use. */
    public synchronized String botName() throws IOException {
        if (isBotName(Json.str(data, "bot_name", ""))) return Json.str(data, "bot_name", "").strip();
        JsonObject updated = freshSnapshot();
        updated.addProperty("bot_name", DEFAULT_BOT_NAME);
        Json.atomicWrite(root.resolve("config.json"), updated);
        data = updated;
        Log.info("已写入默认 bot_name：" + DEFAULT_BOT_NAME + "（可在 config.json 中修改）");
        return DEFAULT_BOT_NAME;
    }
    public static boolean isBotName(String value) {
        return value != null && !value.isBlank() && value.length() <= 100
                && value.strip().equals(value) && value.codePoints().noneMatch(Character::isISOControl);
    }
    public static final String DEFAULT_BOT_NAME = "神户小鸟";
    /**
     * Owner QQ. Deliberately empty in the public source tree: a real QQ number is personal data and is
     * not published here. A fresh checkout therefore has no owner until you set {@code owner_user_id}
     * in config.json (see config.example.json); until then owner-only commands are refused.
     */
    public static final String DEFAULT_OWNER = "";
    public synchronized boolean allowed(JsonObject e) {
        return switch (Json.str(e, "message_type", "")) {
            case "group" -> contains("allowed_group_ids", Json.str(e, "group_id", ""), true);
            case "private" -> contains("allowed_user_ids", Json.str(e, "user_id", ""), true);
            default -> false;
        };
    }
    private boolean contains(String key, String value, boolean emptyAllows) {
        JsonArray values = data.has(key) ? data.getAsJsonArray(key) : new JsonArray();
        if (values.isEmpty()) return emptyAllows;
        for (JsonElement item : values) if (item.getAsString().equals(value)) return true;
        return false;
    }
}
