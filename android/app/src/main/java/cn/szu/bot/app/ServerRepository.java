package cn.szu.bot.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务器配置仓储：多服务器（名称/地址/令牌）存在 SharedPreferences 的一段 JSON 里。
 *
 * <p><b>为什么用 SharedPreferences 而不是文件/外部存储</b>：令牌是敏感信息，外部存储（哪怕是
 * app 私有目录）在部分 ROM 上会被备份/同步出去；SharedPreferences 属于应用私有数据，
 * 卸载即随包清除，也是 Android 上最短路径的做法。代价是令牌<b>明文</b>落盘（见 assets/DESIGN.md 的说明），
 * 所以只在设备本地使用，不要 root 后到处拷。
 *
 * <p>存的是 {@link ServerConfig} 列表 + 一个「上次用的 id」。列表为空视为没配过 → 进设置页。
 */
public final class ServerRepository {

    private static final String PREFS = "pixiko_android";
    private static final String KEY_SERVERS = "servers_json";
    private static final String KEY_LAST_ID = "last_server_id";

    private final SharedPreferences prefs;

    public ServerRepository(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 全部服务器，按用户添加顺序返回（不会返回 null）。 */
    public List<ServerConfig> list() {
        List<ServerConfig> out = new ArrayList<>();
        String raw = prefs.getString(KEY_SERVERS, "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                ServerConfig config = ServerConfig.fromJson(item);
                if (config != null) out.add(config);
            }
        } catch (JSONException error) {
            // 存储内容坏了就当没配过：不要因为一段坏 JSON 让 app 打不开。
            Log.w("服务器配置解析失败，按空列表处理", error);
        }
        return out;
    }

    /** 覆盖式保存整份列表。 */
    public void save(List<ServerConfig> servers) {
        JSONArray array = new JSONArray();
        for (ServerConfig config : servers) array.put(config.toJson());
        prefs.edit().putString(KEY_SERVERS, array.toString()).apply();
    }

    /** 新增或按 id 替换，返回落库后的配置。 */
    public ServerConfig upsert(ServerConfig config) {
        if (config.id == null || config.id.isEmpty()) config.id = UUID.randomUUID().toString();
        List<ServerConfig> servers = list();
        boolean replaced = false;
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).id.equals(config.id)) { servers.set(i, config); replaced = true; break; }
        }
        if (!replaced) servers.add(config);
        save(servers);
        return config;
    }

    public void delete(String id) {
        List<ServerConfig> servers = list();
        for (int i = servers.size() - 1; i >= 0; i--) {
            if (servers.get(i).id.equals(id)) servers.remove(i);
        }
        save(servers);
        if (id != null && id.equals(lastId())) prefs.edit().remove(KEY_LAST_ID).apply();
    }

    public String lastId() { return prefs.getString(KEY_LAST_ID, ""); }

    public void setLastId(String id) { prefs.edit().putString(KEY_LAST_ID, id == null ? "" : id).apply(); }

    /** 上次用的服务器；没有或已被删除则退回列表第一台；一台都没有时返回 null（→ 进设置页）。 */
    public ServerConfig current() {
        List<ServerConfig> servers = list();
        if (servers.isEmpty()) return null;
        String last = lastId();
        for (ServerConfig config : servers) {
            if (config.id.equals(last)) return config;
        }
        return servers.get(0);
    }

    public boolean isEmpty() { return list().isEmpty(); }
}
