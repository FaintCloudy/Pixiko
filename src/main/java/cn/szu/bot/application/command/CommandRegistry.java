package cn.szu.bot.application.command;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * 指令名 → 处理函数的注册表。
 *
 * <p><b>线程安全只读快照</b>：{@link #register} 在锁内复制一份新的有序 Map，整份替换
 * {@link AtomicReference} 里的快照；{@link #find}/{@link #entry}/{@link #names} 只读当前快照，
 * 不加锁、不会看到半成品。注册发生在启动期（单线程），派发发生在任意线程，这个形状刚合适。
 *
 * <p>两条注册约定：
 * <ul>
 *   <li>名字是**不带前缀**的指令名（{@code help}，不是 {@code /help} 或 {@code .help}），大小写敏感——
 *       {@code /HELP} 在原来的 dispatch 里也不是 {@code /help}，不能因为进了注册表就变成同一条指令；</li>
 *   <li>可选的门（{@link Predicate}）：同一个名字下还要再确认"这次文本真的是这条指令的写法"时用
 *       （例如 {@code /mode normal} 与 {@code /mode} 都是 {@code mode}，但 {@code /modeX} 不是）。
 *       门不通过就当作**没命中**，回落老路。</li>
 * </ul>
 */
public final class CommandRegistry {

    /** 一条登记：名字、可选的门、处理函数。 */
    public record Entry(String name, Predicate<CommandContext> gate, CommandHandler handler) {
        public Entry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(gate, "gate");
            Objects.requireNonNull(handler, "handler");
        }

        /** 这次派发是不是这条登记管。门自己抛异常一律当作"不管"（宁可回落老路，也不改变指令走向）。 */
        public boolean matches(CommandContext context) {
            try {
                return gate.test(context);
            } catch (RuntimeException ignored) {
                return false;
            }
        }
    }

    private static final Predicate<CommandContext> ALWAYS = context -> true;

    private final AtomicReference<Map<String, Entry>> snapshot = new AtomicReference<>(Map.of());

    /** 登记一条指令（没有门，名字命中就算）。 */
    public CommandRegistry register(String name, CommandHandler handler) {
        return register(name, ALWAYS, handler);
    }

    /** 登记一条指令，附带"这次文本是不是它"的门。同名的旧登记会被替换。 */
    public synchronized CommandRegistry register(String name, Predicate<CommandContext> gate, CommandHandler handler) {
        String key = requireName(name);
        Objects.requireNonNull(handler, "handler");
        Map<String, Entry> next = new LinkedHashMap<>(snapshot.get());
        next.put(key, new Entry(key, gate == null ? ALWAYS : gate, handler));
        snapshot.set(Collections.unmodifiableMap(next));
        return this;
    }

    /** 名字对应的处理函数；没登记返回 {@code null}。 */
    public CommandHandler find(String name) {
        Entry entry = entry(name);
        return entry == null ? null : entry.handler();
    }

    /** 名字对应的完整登记（含门）；没登记返回 {@code null}。 */
    public Entry entry(String name) {
        return name == null ? null : snapshot.get().get(name);
    }

    /** 已登记的名字（注册顺序，只读快照）。 */
    public Set<String> names() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(snapshot.get().keySet()));
    }

    /** 已登记条数。 */
    public int size() {
        return snapshot.get().size();
    }
    /** 名字写错（带前缀、带空白、空）当场报错，不要等到派发时才发现。 */
    private static String requireName(String name) {
        String key = name == null ? "" : name.strip();
        if (key.isEmpty()) throw new IllegalArgumentException("指令名不能为空");
        if (key.startsWith("/") || key.startsWith(".")) throw new IllegalArgumentException("指令名不要带前缀：" + name);
        if (key.chars().anyMatch(Character::isWhitespace)) throw new IllegalArgumentException("指令名不能含空白：" + name);
        if (!key.equals(name)) throw new IllegalArgumentException("指令名不要带首尾空白：" + name);
        return key;
    }
}
