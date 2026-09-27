package cn.szu.bot;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/**
 * 今日老婆 / 强娶 / 离婚 的持久状态。每个群（私聊按用户）各自一份文件，互不影响：
 * data/marriage/&lt;会话&gt;.json 里记录今日抽取、婚配关系、每日强娶与离婚次数。
 */
public final class Marriage {
    /** 群友信息，用于回执与婚配展示。 */
    public record Member(String qq, String name, String avatar) {}
    public record Couple(String a, String b, String since) {}
    /** 一次等待回应的求婚：发起者、被邀请者与截止时刻（epoch 毫秒）。 */
    public record Proposal(String from, String to, long deadline) {
        public long remainingMillis() { return deadline - System.currentTimeMillis(); }
    }
    /** 求婚等待回应的默认秒数；被邀请者必须在这段时间内同意。 */
    public static final int DEFAULT_PROPOSAL_SECONDS = 180;

    private final Path root;

    public Marriage(Path root) { this.root = root.toAbsolutePath().normalize(); }

    private Path file(String conversation) { return root.resolve("data/marriage").resolve(safe(conversation) + ".json"); }

    private static String safe(String value) {
        String clean = value == null ? "unknown" : value.replaceAll("[^0-9A-Za-z_-]", "_");
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }

    public static String today() { return LocalDate.now().toString(); }

    private JsonObject load(String conversation) {
        try {
            Path path = file(conversation);
            if (!Files.isRegularFile(path)) return fresh();
            JsonObject stored = Json.parse(Files.readString(path, StandardCharsets.UTF_8));
            JsonObject data = fresh();
            for (String key : List.of("draws", "marriages", "forced", "divorced", "proposals")) if (stored.has(key)) data.add(key, stored.get(key));
            data.addProperty("date", today());
            return data;
        } catch (Exception error) {
            Log.warn("婚配数据读取失败，按空数据处理：" + Bot.error(error));
            return fresh();
        }
    }

    private static JsonObject fresh() {
        JsonObject data = new JsonObject();
        data.addProperty("date", today());
        data.add("draws", new JsonObject());
        data.add("marriages", new JsonArray());
        data.add("forced", new JsonObject());
        data.add("divorced", new JsonObject());
        data.add("proposals", new JsonObject());
        return data;
    }

    private void save(String conversation, JsonObject data) throws IOException {
        data.addProperty("date", today());
        Json.atomicWrite(file(conversation), data);
    }

    /** 今日抽到的对象（同一天重复抽取时以最后一次为准）。 */
    public synchronized String todayDraw(String conversation, String qq) {
        return Json.str(Json.obj(load(conversation), "draws"), qq, "");
    }

    public synchronized String recordDraw(String conversation, String qq, String target) throws IOException {
        JsonObject data = load(conversation);
        Json.obj(data, "draws").addProperty(qq, target);
        save(conversation, data);
        return target;
    }

    /** 记录信息（供回执展示）：couple 是已成婚的组合，proposal 是仍在等待回应的求婚。 */
    public record Outcome(boolean ok, String reason, Couple couple, Proposal proposal) {
        static Outcome fail(String reason) { return new Outcome(false, reason, null, null); }
        static Outcome done(Couple couple) { return new Outcome(true, "", couple, null); }
        static Outcome waiting(Proposal proposal) { return new Outcome(true, "", null, proposal); }
    }

    /** 与 qq 有婚配关系的另一方，没有则返回空串。 */
    public synchronized String partnerOf(String conversation, String qq) {
        for (JsonElement element : load(conversation).getAsJsonArray("marriages")) {
            JsonObject couple = element.getAsJsonObject();
            String a = Json.str(couple, "a", ""), b = Json.str(couple, "b", "");
            if (a.equals(qq)) return b;
            if (b.equals(qq)) return a;
        }
        return "";
    }

    public synchronized List<Couple> couples(String conversation) {
        List<Couple> result = new ArrayList<>();
        for (JsonElement element : load(conversation).getAsJsonArray("marriages")) {
            JsonObject couple = element.getAsJsonObject();
            result.add(new Couple(Json.str(couple, "a", ""), Json.str(couple, "b", ""), Json.str(couple, "since", "")));
        }
        return result;
    }

    private static Proposal proposal(JsonObject stored, String invitee) {
        if (stored == null || !stored.has(invitee) || !stored.get(invitee).isJsonObject()) return null;
        JsonObject entry = stored.getAsJsonObject(invitee);
        String from = Json.str(entry, "from", "");
        long deadline = Json.num(entry, "deadline", 0L);        if (from.isBlank() || deadline <= 0) return null;
        return new Proposal(from, invitee, deadline);
    }

    /** 等待 invitee 回应的求婚；已过期的一律视为不存在。 */
    public synchronized Proposal proposalFor(String conversation, String invitee) {
        Proposal pending = proposal(Json.obj(load(conversation), "proposals"), invitee);
        return pending == null || pending.remainingMillis() <= 0 ? null : pending;
    }

    /** proposer 发出、仍在等待回应的求婚；用于查看自己发出的邀请。 */
    public synchronized Proposal proposalFrom(String conversation, String proposer) {
        JsonObject stored = Json.obj(load(conversation), "proposals");
        for (String invitee : stored.keySet()) {
            Proposal pending = proposal(stored, invitee);
            if (pending != null && pending.from().equals(proposer) && pending.remainingMillis() > 0) return pending;
        }
        return null;
    }

    /** 求婚：双方都必须未婚配；被邀请者须在 seconds 秒内同意。 */
    public synchronized Outcome propose(String conversation, String proposer, String invitee, int seconds) throws IOException {
        if (proposer.isBlank() || invitee.isBlank()) return Outcome.fail("没有指定要结婚的对象。");
        if (proposer.equals(invitee)) return Outcome.fail("不能向自己求婚。");
        long window = Math.max(1, seconds) * 1000L;
        JsonObject data = load(conversation);
        String own = partnerOf(conversation, proposer);
        if (!own.isEmpty()) return Outcome.fail("你已经和 " + own + " 婚配了，先离婚才能再求婚。");
        String theirs = partnerOf(conversation, invitee);
        if (!theirs.isEmpty()) return Outcome.fail("对方已经和 " + theirs + " 婚配了，只能向未婚配的人求婚。");
        Proposal pending = new Proposal(proposer, invitee, System.currentTimeMillis() + window);
        JsonObject entry = new JsonObject();
        entry.addProperty("from", proposer);
        entry.addProperty("deadline", pending.deadline());
        Json.obj(data, "proposals").add(invitee, entry);
        save(conversation, data);
        return Outcome.waiting(pending);
    }

    /** 被邀请者同意：只在窗口内有效，过期或对方已变动都会失败。 */
    public synchronized Outcome accept(String conversation, String invitee, int windowSeconds) throws IOException {
        JsonObject data = load(conversation);
        JsonObject stored = Json.obj(data, "proposals");
        Proposal pending = proposal(stored, invitee);
        if (pending == null) return Outcome.fail("当前没有等待你回应的求婚。");
        stored.remove(invitee);
        if (pending.remainingMillis() <= 0) {
            save(conversation, data);
            return Outcome.fail("这次求婚已经超过 " + Math.max(1, windowSeconds) + " 秒，邀请已失效；请让对方重新求婚。");
        }
        String proposer = pending.from();
        String own = partnerOf(conversation, invitee);
        if (!own.isEmpty()) { save(conversation, data); return Outcome.fail("你已经和 " + own + " 婚配了。"); }
        String theirs = partnerOf(conversation, proposer);
        if (!theirs.isEmpty()) { save(conversation, data); return Outcome.fail("对方已经和 " + theirs + " 婚配了，这次求婚作废。"); }
        Couple couple = new Couple(proposer, invitee, today());
        JsonObject entry = new JsonObject();
        entry.addProperty("a", proposer); entry.addProperty("b", invitee); entry.addProperty("since", today());
        data.getAsJsonArray("marriages").add(entry);
        clearProposals(stored, proposer, invitee);
        save(conversation, data);
        return Outcome.done(couple);
    }

    /** 被邀请者拒绝：移除这次邀请。 */
    public synchronized Outcome reject(String conversation, String invitee) throws IOException {
        JsonObject data = load(conversation);
        JsonObject stored = Json.obj(data, "proposals");
        Proposal pending = proposal(stored, invitee);
        if (pending == null) return Outcome.fail("当前没有等待你回应的求婚。");
        stored.remove(invitee);
        save(conversation, data);
        return Outcome.waiting(pending);
    }

    /** 成婚后清掉双方所有待回应的邀请，避免有人还挂在等待状态。 */
    private static void clearProposals(JsonObject stored, String first, String second) {
        List<String> expired = new ArrayList<>();
        for (String invitee : stored.keySet()) {
            Proposal pending = proposal(stored, invitee);
            if (pending == null) { expired.add(invitee); continue; }
            if (pending.from().equals(first) || pending.from().equals(second)
                    || invitee.equals(first) || invitee.equals(second)) expired.add(invitee);
        }
        for (String invitee : expired) stored.remove(invitee);
    }

    /** 强娶：每人每天一次，双方都必须未婚配。 */
    public synchronized Outcome forceMarry(String conversation, String requester, String target) throws IOException {
        if (requester.isBlank() || target.isBlank()) return Outcome.fail("没有指定要强娶的对象。");
        if (requester.equals(target)) return Outcome.fail("不能强娶自己。");
        JsonObject data = load(conversation);
        if (today().equals(Json.str(Json.obj(data, "forced"), requester, ""))) return Outcome.fail("今天已经强娶过一次了，明天再来。");
        String own = partnerOf(conversation, requester);
        if (!own.isEmpty()) return Outcome.fail("你已经和 " + own + " 婚配了，先离婚才能强娶。");
        String theirs = partnerOf(conversation, target);
        if (!theirs.isEmpty()) return Outcome.fail("对方已经和 " + theirs + " 婚配了，只能强娶未婚配的人。");
        Couple couple = new Couple(requester, target, today());
        JsonObject entry = new JsonObject();
        entry.addProperty("a", requester); entry.addProperty("b", target); entry.addProperty("since", today());
        data.getAsJsonArray("marriages").add(entry);
        clearProposals(Json.obj(data, "proposals"), requester, target);
        Json.obj(data, "forced").addProperty(requester, today());
        save(conversation, data);
        return Outcome.done(couple);
    }

    /** 离婚：每人每天一次。 */
    public synchronized Outcome divorce(String conversation, String requester) throws IOException {
        JsonObject data = load(conversation);
        if (today().equals(Json.str(Json.obj(data, "divorced"), requester, ""))) return Outcome.fail("今天已经离过一次了，冷静一下，明天再说。");
        String partner = partnerOf(conversation, requester);
        if (partner.isEmpty()) return Outcome.fail("你目前没有婚配对象，不需要离婚。");
        JsonArray kept = new JsonArray();
        for (JsonElement element : data.getAsJsonArray("marriages")) {
            JsonObject couple = element.getAsJsonObject();
            String a = Json.str(couple, "a", ""), b = Json.str(couple, "b", "");
            if (a.equals(requester) || b.equals(requester)) continue;
            kept.add(couple);
        }
        data.add("marriages", kept);
        Json.obj(data, "divorced").addProperty(requester, today());
        save(conversation, data);
        return Outcome.done(new Couple(requester, partner, today()));
    }

    /** QQ 头像地址：QQ 实现（NapCat）通常可以直接按 URL 取图。 */
    public static String avatarUrl(String qq) {
        return "https://q1.qlogo.cn/g?b=qq&nk=" + qq + "&s=640";
    }
}
