package cn.szu.bot.app;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 一台被控服务器的配置：名称（给人看的）、规范化后的地址、访问令牌。
 *
 * <p>地址一律用 {@link UrlHelper#normalizeBase(String)} 规范化后再存，避免「同一台服务器存成三条记录」。
 */
public final class ServerConfig {

    public String id = "";
    public String name = "";
    /** 规范化后的基地址，形如 {@code http://192.168.1.5:8787}（无尾部斜杠）。 */
    public String base = "";
    public String token = "";

    public ServerConfig() { }

    public ServerConfig(String id, String name, String base, String token) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.base = base == null ? "" : base;
        this.token = token == null ? "" : token;
    }

    /** 列表里显示的名字：用户没填名字就用主机名顶上。 */
    public String displayName() {
        if (name != null && !name.isBlank()) return name;
        return UrlHelper.hostOf(base);
    }

    public boolean isValid() { return base != null && !base.isBlank(); }

    public JSONObject toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("id", id);
            object.put("name", name);
            object.put("base", base);
            object.put("token", token);
        } catch (JSONException error) {
            Log.w("ServerConfig 序列化失败", error);
        }
        return object;
    }

    public static ServerConfig fromJson(JSONObject object) {
        if (object == null) return null;
        ServerConfig config = new ServerConfig();
        config.id = object.optString("id", "");
        config.name = object.optString("name", "");
        config.base = object.optString("base", "");
        config.token = object.optString("token", "");
        if (config.id.isEmpty()) config.id = java.util.UUID.randomUUID().toString();
        return config.isValid() ? config : null;
    }
}
