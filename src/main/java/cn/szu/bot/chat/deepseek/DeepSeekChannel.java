package cn.szu.bot.chat.deepseek;

/**
 * 一条 DeepSeek 调用属于哪条**连接通道**：生图、聊天、画面检查。
 *
 * <p>画面检查（{@code REVIEW}）单独算一条通道，不是为了配置分段，而是为了**连接隔离**：它有自己的
 * {@link DeepSeekTransport} 实例（生产实现各自包着一个独立的 {@link java.net.http.HttpClient}，也就是
 * 独立的连接池），所以改写/聊天把连接用到什么状态，都不会污染紧接着发出的检查请求，反之亦然。
 *
 * <p>注意这不是配置意义上的"频道"：配置分段仍是 {@code progen} / {@code chat_api} 两段
 * （见 {@code DeepSeekPrompts.Channel}），画面检查用的是生图频道的配置与密钥。
 */
public enum DeepSeekChannel {
    IMAGE("生图频道"),
    CHAT("聊天频道"),
    REVIEW("画面检查通道");

    private final String label;

    DeepSeekChannel(String label) { this.label = label; }

    /** 日志里显示的名字（不含任何密钥/请求内容）。 */
    public String label() { return label; }
}
