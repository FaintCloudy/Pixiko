package cn.szu.bot.app;

import android.content.Context;

/**
 * 一点点「当前正在用哪台服务器」的全局读法。
 *
 * <p>为什么需要它：{@link PixikoBridge} 拿到的是一个 {@code img.src} 相对地址，
 * 要拼绝对地址就得知道当前基地址；而桥不该持有 Activity 的字段（WebView 会在页面里长期持有桥对象）。
 * 所以从 {@link ServerRepository} 现读一次。
 */
public final class Prefs {

    private Prefs() { }

    /** 当前服务器的基地址；没配过返回 null。 */
    public static String currentBase(Context context) {
        ServerConfig config = new ServerRepository(context).current();
        return config == null ? null : config.base;
    }
}
