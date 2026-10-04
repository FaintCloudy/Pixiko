package cn.szu.bot.app;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * 服务器设置页：多服务器（名称/地址/令牌）的增删改，以及「测试连接」与「扫描局域网」。
 *
 * <p>这是<a href="#">网页端完全没有</a>的能力——网页在浏览器里只知道一个当前地址，
 * 而手机上要能换机器、要能在不知道 IP 的时候扫出来。设计取舍：
 * <ul>
 *   <li>就地编辑 + 保存（{@link ServerRepository#upsert}）：不搞「编辑模式」这种隐式状态，
 *       列表每一项都有「使用/编辑/删除」，点「编辑」只是把值灌回上面的输入框；</li>
 *   <li>测试连接走 {@link Probe}：先 /healthz（免令牌）再 /api/status（验令牌），
 *       所以能区分「连不上」与「令牌不对」；</li>
 *   <li>扫描局域网走 {@link LanScanner}：并发 32、单个 400ms、总 10s、可取消；
 *       onDestroy 里必须 cancel，否则线程会拖着 IO 活到进程结束。</li>
 * </ul>
 */
public class SettingsActivity extends AppCompatActivity {

    private ServerRepository repository;

    private EditText nameField;
    private EditText addressField;
    private EditText tokenField;
    private CheckBox activeField;
    private TextView statusText;
    /** 「这是构建时烧进去的测试地址」那行小字（没有烧地址的包里永远 GONE）。 */
    private TextView buildHostHint;
    private ProgressBar scanProgress;
    private LinearLayout serverList;
    private TextView serverEmpty;
    private Button actionSave;
    private Button actionCancelEdit;

    /** 正在编辑的服务器 id；空串表示「新增」。 */
    private String editedId = "";

    private LanScanner scanner;
    /** 扫描发现但还没保存的地址，点一下就能填进地址框。 */
    private final java.util.List<String> discovered = new java.util.ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        repository = new ServerRepository(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            // 用 Toolbar 自己的关闭按钮，不依赖 ActionBar 的返回箭头（主题是 NoActionBar）。
            toolbar.setNavigationOnClickListener(view -> finish());
        }

        nameField = findViewById(R.id.field_name);
        addressField = findViewById(R.id.field_address);
        tokenField = findViewById(R.id.field_token);
        activeField = findViewById(R.id.field_active);
        statusText = findViewById(R.id.status_text);
        buildHostHint = findViewById(R.id.build_host_hint);
        scanProgress = findViewById(R.id.scan_progress);
        serverList = findViewById(R.id.server_list);
        serverEmpty = findViewById(R.id.server_empty);
        actionSave = findViewById(R.id.action_save);
        actionCancelEdit = findViewById(R.id.action_cancel_edit);

        // 旋转/重建后保留「正在编辑哪一台」的状态，别让用户白填一遍。
        if (savedInstanceState != null) editedId = savedInstanceState.getString("edited_id", "");
        if (activeField != null) {
            ServerConfig active = repository.current();
            activeField.setChecked(active == null || isCurrent(active) || editedId.isEmpty());
        }

        findViewById(R.id.action_test).setOnClickListener(view -> testConnection());
        findViewById(R.id.action_scan).setOnClickListener(view -> toggleScan());
        actionSave.setOnClickListener(view -> saveCurrentInput());
        if (actionCancelEdit != null) actionCancelEdit.setOnClickListener(view -> resetInput(true));

        applyBuildDefaultHost();
        renderList();
    }

    /**
     * 首次运行时把「构建时烧进去的测试默认地址」预填到地址输入框里。
     *
     * <p>地址来源：构建脚本 <code>android/build-apk.ps1</code> 每次编包前探测本机局域网 IP，
     * 用 <code>-PpixikoDefaultHost=192.168.x.y:8787</code> 交给 Gradle，
     * <code>app/build.gradle</code> 再把它变成 {@link BuildConfig#PIXIKO_DEFAULT_HOST}
     * （源码里<b>没有</b>任何写死的 IP，只有构建属性这一条路）。
     *
     * <p><b>只是预填</b>，所以这里刻意什么都不做别的：
     * <ul>
     *   <li><b>不</b>写 SharedPreferences、<b>不</b>设为当前服务器、<b>不</b>自动发起连接——用户仍要自己点「保存」；</li>
     *   <li><b>不</b>填令牌：令牌仍然必须由用户从 <code>config.json → webui.access_token</code>
     *       抄过来手填，这里一个字都不写（不绕过令牌，也不降低令牌的必要性）；</li>
     *   <li>只在「一台服务器都没保存过」<b>且</b>地址框本来就是空的时候才填，
     *       所以<b>永远不会覆盖用户已保存或已经敲进去的内容</b>；</li>
     *   <li>用 <code>-NoDefaultHost</code> 构建的包里这个常量是空串 → 本方法第一步就返回，
     *       「服务器设置」的行为与加这个功能之前<b>完全一致</b>。</li>
     * </ul>
     */
    private void applyBuildDefaultHost() {
        // 这是构建时烧进去的测试默认值，可直接改成别的；没有烧地址的包里它是空串。
        final String preset = BuildConfig.PIXIKO_DEFAULT_HOST == null ? "" : BuildConfig.PIXIKO_DEFAULT_HOST.trim();
        if (preset.isEmpty()) return;
        // 存过服务器就绝不插手（用户的数据永远优先于构建默认值）。
        if (!repository.list().isEmpty()) return;
        // 输入框里已经有东西（用户自己敲的、或旋转重建恢复回来的）也不覆盖。
        if (!addressField.getText().toString().trim().isEmpty()) return;

        String normalized = UrlHelper.normalizeBase(preset);
        addressField.setText(normalized == null ? preset : normalized);
        if (buildHostHint != null) {
            buildHostHint.setText(getString(R.string.settings_build_default_hint, preset));
            buildHostHint.setVisibility(View.VISIBLE);
        }
        Log.d("已预填构建时烧进去的测试默认地址：" + preset + "（仅预填：未保存、未连接、未填令牌）");
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("edited_id", editedId);
    }

    private boolean isCurrent(ServerConfig config) {
        String lastId = repository.lastId();
        return config != null && config.id.equals(lastId);
    }

    // ------------------------------------------------------------------ 保存 / 列表

    private void saveCurrentInput() {
        String rawAddress = addressField.getText().toString();
        String normalized = UrlHelper.normalizeBase(rawAddress);
        if (normalized == null) {
            setStatus("地址填得不对。示例：192.168.1.5:8787 或 http://192.168.1.5:8787/", true);
            return;
        }
        String token = tokenField.getText().toString().trim();
        String name = nameField.getText().toString().trim();

        ServerConfig config = new ServerConfig(editedId, name, normalized, token);
        if (editedId.isEmpty()) {
            // 新增：同名同地址就别重复存，直接把令牌更新过去。
            for (ServerConfig existing : repository.list()) {
                if (existing.base.equals(normalized)) {
                    config.id = existing.id;
                    if (config.name.isEmpty()) config.name = existing.name;
                    break;
                }
            }
        }
        repository.upsert(config);
        // 「设为当前服务器」勾上就切过去；新增第一台时无论勾没勾都切（否则用户没地址可用）。
        boolean makeCurrent = activeField != null && activeField.isChecked();
        if (repository.current() == null || repository.list().size() == 1) makeCurrent = true;
        if (makeCurrent) repository.setLastId(config.id);
        Log.i("已保存服务器配置：" + UrlHelper.hostOf(normalized) + "，令牌=" + Log.mask(token));
        setStatus("已保存：" + UrlHelper.hostOf(normalized) + (makeCurrent ? "（设为当前服务器）" : ""), false);
        editedId = config.id;
        // 地址已经落库了，「构建时带的测试地址」这行提示就没意义了，收起来。
        if (buildHostHint != null) buildHostHint.setVisibility(View.GONE);
        renderList();
    }

    /** {@code clearFields=true} 时连输入一起清空（新增模式）。 */
    private void resetInput(boolean clearFields) {
        editedId = "";
        if (clearFields) {
            nameField.setText("");
            addressField.setText("");
            tokenField.setText("");
        }
        if (activeField != null) activeField.setChecked(true);
        if (actionSave != null) actionSave.setText(R.string.settings_save);
        setStatus("", false);
    }

    /** 重建已保存服务器列表（每台一张卡片：使用 / 编辑 / 删除）。 */
    private void renderList() {
        serverList.removeAllViews();
        List<ServerConfig> servers = repository.list();
        serverEmpty.setVisibility(servers.isEmpty() ? View.VISIBLE : View.GONE);

        for (final ServerConfig config : servers) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(ContextCompat.getColor(this, R.color.pixiko_bar));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = dp(8);
            card.setLayoutParams(cardParams);
            card.setPadding(dp(12), dp(10), dp(12), dp(6));

            TextView title = new TextView(this);
            title.setText(config.displayName() + (isCurrent(config) ? "   ● 当前" : ""));
            title.setTextColor(ContextCompat.getColor(this, R.color.pixiko_text));
            title.setTextSize(15f);
            card.addView(title);

            TextView subtitle = new TextView(this);
            subtitle.setText(UrlHelper.hostOf(config.base) + "　令牌：" + (config.token.isEmpty() ? "（未填）" : Log.mask(config.token)));
            subtitle.setTextColor(ContextCompat.getColor(this, R.color.pixiko_muted));
            subtitle.setTextSize(12f);
            card.addView(subtitle);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            actions.addView(button("使用", view -> {
                repository.setLastId(config.id);
                setStatus("当前服务器已切换为 " + UrlHelper.hostOf(config.base), false);
                renderList();
            }));
            actions.addView(button("测试", view -> {
                fillInput(config);
                testConnection();
            }));
            actions.addView(button("编辑", view -> fillInput(config)));
            actions.addView(button("删除", view -> confirmDelete(config)));
            card.addView(actions);

            serverList.addView(card);
        }
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        button.setOnClickListener(listener);
        return button;
    }

    /** 把某台服务器灌回输入框，进入「编辑」状态（保存时会按 id 覆盖而不是新增）。 */
    private void fillInput(ServerConfig config) {
        editedId = config.id;
        nameField.setText(config.name);
        addressField.setText(config.base);
        tokenField.setText(config.token);
        if (actionSave != null) actionSave.setText("保存修改");
        // 编辑的是已保存的服务器，构建时的测试地址提示不再适用。
        if (buildHostHint != null) buildHostHint.setVisibility(View.GONE);
        Log.d("载入到编辑框：" + UrlHelper.hostOf(config.base) + "，令牌=" + Log.mask(config.token));
    }

    private void confirmDelete(final ServerConfig config) {
        new AlertDialog.Builder(this, R.style.Theme_Pixiko_Dialog)
                .setTitle("删除服务器")
                .setMessage("确定删除「" + config.displayName() + "」（" + UrlHelper.hostOf(config.base) + "）吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    repository.delete(config.id);
                    if (editedId.equals(config.id)) resetInput(false);
                    setStatus("已删除：" + UrlHelper.hostOf(config.base), false);
                    renderList();
                })
                .show();
    }

    // ------------------------------------------------------------------ 测试连接

    /** 在后台线程跑 {@link Probe}，结果写回状态栏（区分连不上 / 令牌不对）。 */
    private void testConnection() {
        String normalized = UrlHelper.normalizeBase(addressField.getText().toString());
        if (normalized == null) {
            setStatus("地址填得不对。示例：192.168.1.5:8787", true);
            return;
        }
        final String token = tokenField.getText().toString().trim();
        setStatus(getString(R.string.probe_testing) + "\n" + normalized, false);

        new Thread(() -> {
            Probe.Outcome outcome = Probe.test(normalized, token);
            StringBuilder text = new StringBuilder(outcome.message);
            if (!outcome.detail.isEmpty()) text.append("\n").append(outcome.detail);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                setStatus(text.toString(), !outcome.ok());
                Log.i("连接自检：" + outcome.kind + " / " + outcome.message);
            });
        }, "pixiko-probe").start();
    }

    // ------------------------------------------------------------------ 扫描局域网

    private void toggleScan() {
        if (scanner != null) {
            scanner.cancel();
            scanner = null;
            scanProgress.setVisibility(View.GONE);
            setStatus("已取消扫描。", false);
            return;
        }
        discovered.clear();
        final Button scanButton = findViewById(R.id.action_scan);
        scanButton.setText("取消扫描");
        scanProgress.setVisibility(View.VISIBLE);
        scanProgress.setProgress(0);
        List<String> prefixes = LanScanner.localPrefixes();
        setStatus(getString(R.string.scan_running) + "\n网段：" + (prefixes.isEmpty() ? "（没找到局域网网卡，先连上 Wi-Fi）" : String.join("、", prefixes) + "x"), false);

        scanner = new LanScanner();
        scanner.start(new LanScanner.Listener() {
            @Override public void onProgress(int done, int total) {
                if (isFinishing()) return;
                scanProgress.setMax(total);
                scanProgress.setProgress(done);
            }

            @Override public void onFound(String base, String host) {
                if (isFinishing() || discovered.contains(base)) return;
                discovered.add(base);
                setStatus(getString(R.string.scan_running) + "\n已发现 " + discovered.size() + " 台：\n"
                        + String.join("\n", discovered) + "\n\n点下面的「填入」把地址写进输入框。", false);
            }

            @Override public void onFinished(List<String> found, boolean cancelled) {
                scanner = null;
                if (isFinishing()) return;
                scanButton.setText(R.string.settings_scan);
                scanProgress.setVisibility(View.GONE);
                if (found.isEmpty()) {
                    setStatus(getString(R.string.scan_none), true);
                } else {
                    setStatus((cancelled ? "扫描已中断。" : "扫描完成。") + "发现 " + found.size() + " 台：\n"
                            + String.join("\n", found), false);
                }
                renderDiscovered(found);
            }
        });
    }

    /** 扫描结果做成「一键填入」的按钮，比让用户手抄 IP 靠谱。 */
    private void renderDiscovered(List<String> found) {
        // 结果直接追加在状态栏下面，不进服务器列表（服务器列表只放已保存的）。
        for (String base : found) {
            if (findViewById(("discovered_" + base).hashCode()) != null) continue;
            Button fill = new Button(this);
            fill.setId(("discovered_" + base).hashCode());
            fill.setText("填入 " + UrlHelper.hostOf(base));
            fill.setAllCaps(false);
            fill.setOnClickListener(view -> addressField.setText(base));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(4);
            fill.setLayoutParams(params);
            // 插在状态栏之后（列表容器之前）。
            ViewGroup parent = (ViewGroup) statusText.getParent();
            parent.addView(fill, parent.indexOfChild(statusText) + 1);
        }
    }

    // ------------------------------------------------------------------ 杂项

    private void setStatus(String text, boolean error) {
        statusText.setText(text == null ? "" : text);
        statusText.setTextColor(ContextCompat.getColor(this, error ? android.R.color.holo_red_light : R.color.pixiko_text));
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    @Override
    protected void onDestroy() {
        // 扫描线程必须停：不然退出设置页后它还在往 254 个地址发包。
        if (scanner != null) {
            scanner.cancel();
            scanner = null;
        }
        super.onDestroy();
    }
}
