package cn.szu.bot.app;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import java.io.File;
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

    /**
     * 从「发现新版本」横幅跳过来时置 true：进页面就自动开始下载（用户在那里已经点过一次「下载更新」了，
     * 到这边不该再让他点第二次）。
     */
    public static final String EXTRA_AUTO_DOWNLOAD = "auto_download";

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

    // ---- 应用更新区 ----
    private TextView updateBadge;
    private TextView updateCurrent;
    private CheckBox updateAuto;
    private Button updateCheck;
    private ProgressBar updateProgress;
    private TextView updateInfoView;
    private LinearLayout updateActions;
    private Button updateDownload;
    private Button updateReleasePage;
    private TextView updateStatusText;

    /** 最近一次查到的更新（没有就是 null）。下载按钮要用它。 */
    private UpdateInfo availableUpdate;
    /** 更新相关动作都在子线程做，结果贴回主线程用这个 Handler。 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 已下好并校验通过的 APK（进程内记忆；落库的那份在 UpdatePrefs.downloadedPath()）。 */
    private File downloadedApk;
    /** 「用户被引导去开『安装未知应用』，回来要自动接着装」的标记。 */
    private boolean awaitingInstallPermission;
    /** 下载进行中：挡掉重复点击。 */
    private boolean downloading;

    /** 正在编辑的服务器 id；空串表示「新增」。 */
    private String editedId = "";

    private LanScanner scanner;
    /** 扫描发现但还没保存的地址，点一下就能填进地址框。 */
    private final java.util.List<String> discovered = new java.util.ArrayList<>();

    /** 主界面横幅 ↔ 本页红点的广播（见 MainActivity.pushUpdateStatus）。 */
    private final BroadcastReceiver updateStatusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            boolean good = intent.getBooleanExtra(MainActivity.EXTRA_UPDATE_STATUS_GOOD, false);
            UpdateInfo info = MainActivity.updateInfoFrom(intent);
            if (good && info != null) showAvailableUpdate(info);
            String text = intent.getStringExtra(MainActivity.EXTRA_UPDATE_STATUS_TEXT);
            if (text != null && !text.isEmpty()) setUpdateStatus(text, !good);
        }
    };

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

        bindUpdateSection(savedInstanceState);
        // 新版本起来后把上一版留下的安装包/半成品清掉（这时才确定它们没用了）。
        cleanUpdateWorkFilesIfUpgraded();

        applyBuildDefaultHost();
        renderList();
    }

    // ------------------------------------------------------------------ 应用更新区

    private void bindUpdateSection(Bundle savedInstanceState) {
        updateBadge = findViewById(R.id.update_badge);
        updateCurrent = findViewById(R.id.update_current);
        updateAuto = findViewById(R.id.update_auto);
        updateCheck = findViewById(R.id.update_check);
        updateProgress = findViewById(R.id.update_progress);
        updateInfoView = findViewById(R.id.update_info);
        updateActions = findViewById(R.id.update_actions);
        updateDownload = findViewById(R.id.update_download);
        updateReleasePage = findViewById(R.id.update_release_page);
        updateStatusText = findViewById(R.id.update_status);

        UpdatePrefs prefs = new UpdatePrefs(this);
        if (updateAuto != null) {
            updateAuto.setChecked(prefs.autoCheck());
            updateAuto.setOnClickListener(view -> {
                boolean enabled = updateAuto.isChecked();
                prefs.setAutoCheck(enabled);
                Log.i("自动检查更新：" + (enabled ? "已打开" : "已关闭"));
                setUpdateStatus(enabled
                        ? "已打开自动检查：每次启动 app 后会自动查一次（不阻塞首屏）。"
                        : "已关闭自动检查：只能靠下面的「检查更新」按钮手动查。", false);
            });
        }
        if (updateCurrent != null) {
            updateCurrent.setText(getString(R.string.settings_update_current,
                    Prefs.installedVersionName(this), Prefs.installedVersionCode(this)));
        }
        if (updateCheck != null) updateCheck.setOnClickListener(view -> manualCheckForUpdate());
        if (updateDownload != null) updateDownload.setOnClickListener(view -> beginDownload());
        if (updateReleasePage != null) {
            updateReleasePage.setOnClickListener(view -> openReleasePage());
        }

        // 上次已经下好并校验过的包还在吗？在就直接给「立即安装」，不用重下几十 MB。
        String savedPath = prefs.downloadedPath();
        if (!savedPath.isEmpty()) {
            File saved = new File(savedPath);
            if (saved.isFile() && saved.length() > 0) {
                downloadedApk = saved;
                setUpdateStatus("上次下载的安装包还在（" + saved.getName() + "，"
                        + humanBytes(saved.length()) + "），点「下载并安装」可以直接装，不用重下。", false);
            } else {
                prefs.clearDownloadedPath();
            }
        }

        // 旋转/重建后不要把正在展示的更新信息丢掉。
        if (savedInstanceState != null && savedInstanceState.containsKey("update_info_version_code")) {
            restoreUpdateInfo(savedInstanceState);
        }

        // 从横幅跳过来（用户已经点过「下载更新」）：进页面就开始下载。
        Intent intent = getIntent();
        if (intent != null && intent.getBooleanExtra(EXTRA_AUTO_DOWNLOAD, false)) {
            UpdateInfo fromIntent = MainActivity.updateInfoFrom(intent);
            if (fromIntent != null) showAvailableUpdate(fromIntent);
            // 等一下再开始：让这一屏先画出来，用户能看见进度条从 0 开始。
            mainHandler.postDelayed(this::beginDownload, 250L);
        } else if (intent != null) {
            UpdateInfo fromIntent = MainActivity.updateInfoFrom(intent);
            if (fromIntent != null) showAvailableUpdate(fromIntent);
        }

        registerStatusReceiver();
    }

    private void registerStatusReceiver() {
        IntentFilter filter = new IntentFilter(MainActivity.ACTION_UPDATE_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateStatusReceiver, filter);
        }
    }

    private void restoreUpdateInfo(Bundle state) {
        UpdateInfo info = new UpdateInfo(
                state.getString("update_info_version", ""),
                state.getInt("update_info_version_code", -1),
                state.getLong("update_info_size", -1L),
                state.getString("update_info_sha256", ""),
                state.getString("update_info_apk_url", ""),
                state.getString("update_info_release_url", ""),
                "",
                state.getString("update_info_notes", ""),
                state.getString("update_info_channel", ""),
                state.getString("update_info_channel_note", ""));
        if (info.versionCode > 0) showAvailableUpdate(info);
    }

    /**
     * 手动检查：先清掉「稍后」记录（用户主动来查，说明他想看到提示），再在子线程查一次。
     */
    private void manualCheckForUpdate() {
        ServerConfig current = repository.current();
        if (current == null || current.base == null || current.base.isBlank()) {
            setUpdateStatus("还没配置服务器地址，先去上面填一个。", true);
            return;
        }
        new UpdatePrefs(this).clearIgnoredVersion();
        setUpdateStatus(getString(R.string.settings_update_checking) + "\n" + current.base, false);
        if (updateCheck != null) updateCheck.setEnabled(false);
        final String base = current.base;
        final String token = current.token;
        new Thread(() -> {
            UpdateChecker.Outcome outcome = UpdateChecker.check(base, token, null);
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (updateCheck != null) updateCheck.setEnabled(true);
                applyOutcome(outcome, true);
            });
        }, "pixiko-update-check").start();
    }

    /** 把一次检查结论落到界面上（手动/自动共用）。 */
    private void applyOutcome(UpdateChecker.Outcome outcome, boolean manual) {
        if (outcome == null) return;
        if (!outcome.ok || outcome.info == null) {
            setUpdateStatus(outcome.message, true);
            broadcastStatus(outcome.message, false, null);
            return;
        }
        UpdateInfo info = outcome.info;
        if (!info.isNewerThan(Prefs.installedVersionCode(this))) {
            String text = getString(R.string.settings_update_up_to_date, info.version);
            setUpdateStatus(text, false);
            if (manual) broadcastStatus(text, false, null);
            return;
        }
        showAvailableUpdate(info);
        broadcastStatus(describeUpdateLine(info), true, info);
    }

    /** 「发现新版本 1.6.1（当前 1.6.0，6.0 MB）。」——设置页与横幅共用同一套措辞。 */
    private String describeUpdateLine(UpdateInfo info) {
        String version = info.version.isEmpty() ? ("versionCode " + info.versionCode) : info.version;
        String size = info.sizeText().isEmpty() ? "大小未知" : info.sizeText();
        return getString(R.string.settings_update_available, version, Prefs.installedVersionName(this), size);
    }

    /** 有新版本：亮红点 + 显示版本/大小/渠道/说明 + 显示「下载并安装」。 */
    private void showAvailableUpdate(UpdateInfo info) {
        availableUpdate = info;
        if (updateBadge != null) updateBadge.setVisibility(View.VISIBLE);

        StringBuilder text = new StringBuilder(describeUpdateLine(info));
        text.append('\n').append(info.channelText());
        if (!info.notes.isEmpty()) {
            text.append("\n\n").append(getString(R.string.settings_update_notes_title)).append("：\n").append(info.notes);
        }
        String blocked = info.blockReason();
        if (!blocked.isEmpty()) text.append("\n\n").append(blocked);
        if (updateInfoView != null) {
            updateInfoView.setText(text.toString());
            updateInfoView.setVisibility(View.VISIBLE);
        }
        if (updateActions != null) updateActions.setVisibility(View.VISIBLE);
        if (updateDownload != null) {
            boolean installable = info.canInstall();
            updateDownload.setEnabled(installable);
            updateDownload.setAlpha(installable ? 1f : 0.5f);
        }
        if (updateReleasePage != null) {
            updateReleasePage.setVisibility(info.releaseUrl.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (downloadedApk == null) setUpdateStatus(blocked.isEmpty() ? "可以下载了。" : blocked, !blocked.isEmpty());
    }

    private void openReleasePage() {
        UpdateInfo info = availableUpdate;
        if (info == null || info.releaseUrl.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(info.releaseUrl)));
        } catch (Exception error) {
            setUpdateStatus("打不开发行页：" + Log.describe(error), true);
        }
    }

    /**
     * 下载 + 校验。整个过程都在子线程，进度回主线程刷进度条。
     *
     * <p>下载目录用 {@link Prefs#updateDir}（{@code cacheDir/update}），FileProvider 白名单里
     * 只放了这一个子目录（见 {@code res/xml/file_paths.xml}）。
     */
    private void beginDownload() {
        if (downloading) return;
        final UpdateInfo info = availableUpdate;
        if (info == null) {
            setUpdateStatus("还没有查到新版本，先点「检查更新」。", true);
            return;
        }
        if (!info.canInstall()) {
            setUpdateStatus(info.blockReason(), true);
            return;
        }
        // 已经下好且校验过：直接进安装流程（用户按的是「下载并安装」，这一步就是「安装」）。
        if (downloadedApk != null && downloadedApk.isFile()) {
            promptInstall(downloadedApk);
            return;
        }

        downloading = true;
        if (updateDownload != null) updateDownload.setEnabled(false);
        if (updateProgress != null) {
            updateProgress.setVisibility(View.VISIBLE);
            updateProgress.setProgress(0);
        }
        setUpdateStatus("开始下载（" + (info.sizeText().isEmpty() ? "大小未知" : info.sizeText()) + "）…", false);

        final File dir = Prefs.updateDir(this);
        new Thread(() -> {
            SelfUpdate.Result result = SelfUpdate.download(info, dir, (downloaded, total) ->
                    mainHandler.post(() -> showDownloadProgress(downloaded, total)));
            mainHandler.post(() -> {
                downloading = false;
                if (updateDownload != null) updateDownload.setEnabled(true);
                if (updateProgress != null) updateProgress.setVisibility(View.GONE);
                if (isFinishing() || isDestroyed()) return;
                finishDownload(info, result);
            });
        }, "pixiko-update-download").start();
    }

    private void finishDownload(UpdateInfo info, SelfUpdate.Result result) {
        if (result == null || !result.ok || result.apk == null) {
            String message = result == null ? "下载失败（没有结果）" : result.message;
            setUpdateStatus(message + "\n可以点「下载并安装」重试（会从断点接着下）。", true);
            Log.w("自我更新下载失败：" + message);
            return;
        }
        downloadedApk = result.apk;
        new UpdatePrefs(this).setDownloadedPath(result.apk.getAbsolutePath());
        Log.i("自我更新：下载并校验通过，" + result.apk.getAbsolutePath() + "，sha256=" + result.sha256);

        if (result.message.startsWith("已复用")) {
            setUpdateStatus("已在本地找到上次校验通过的安装包，跳过下载。", false);
        } else {
            setUpdateStatus(getString(R.string.settings_update_verified,
                    result.sha256.substring(0, Math.min(16, result.sha256.length())) + "…"), false);
        }
        promptInstall(result.apk);
    }

    /** 进度条：有总长度就按百分比，没有就转不确定态（不能让用户盯着一个永远 0% 的条）。 */
    private void showDownloadProgress(long downloaded, long total) {
        if (updateProgress == null || isFinishing()) return;
        if (total > 0) {
            int percent = (int) Math.min(100L, downloaded * 100L / total);
            updateProgress.setIndeterminate(false);
            updateProgress.setProgress(percent);
            setUpdateStatus(getString(R.string.settings_update_downloading, percent,
                    humanBytes(downloaded), humanBytes(total)), false);
        } else {
            updateProgress.setIndeterminate(true);
            setUpdateStatus(getString(R.string.settings_update_downloading_unknown, humanBytes(downloaded)), false);
        }
    }

    /**
     * 走到最后一步：交给系统安装器。
     *
     * <p><b>先说清楚这里为什么不能自动装完</b>：普通 app 没有静默安装的权力
     * （那要 root 或 device-owner/系统签名），所以这里只能拉起系统安装器，由用户在系统弹窗里
     * 点一下「安装」。缺「安装未知应用」授权时，先把用户引到系统设置去开，回来再自动继续。
     */
    private void promptInstall(File apk) {
        if (!UpdateInstaller.canRequestPackageInstalls(this)) {
            awaitingInstallPermission = true;
            setUpdateStatus(getString(R.string.settings_update_unknown_sources), true);
            new AlertDialog.Builder(this, R.style.Theme_Pixiko_Dialog)
                    .setTitle("需要「安装未知应用」授权")
                    .setMessage("下载与 sha256 校验已经完成，但系统还不允许本 app 安装应用。\n\n"
                            + "点「去设置」打开「允许来自此来源的应用」，回来会自动接着装。\n\n"
                            + "顺带说清楚：即便有这个权限，最后一下「安装」也必须你在系统弹窗里点 —— "
                            + "静默替换需要 root 或系统签名，普通 app 做不到。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("去设置", (dialog, which) -> UpdateInstaller.openUnknownSourcesSettings(this))
                    .show();
            return;
        }
        try {
            Intent intent = UpdateInstaller.buildInstallIntent(this, apk);
            startActivity(intent);
            Log.i("已把安装包交给系统安装器（ACTION_VIEW + " + SelfUpdate.APK_MIME + "），等用户点「安装」");
            setUpdateStatus("已拉起系统安装器：请在系统弹窗里点「安装」。\n"
                    + "装好后重开本 app，版本号会变成新版。", false);
            // 安装器已经拿到授权（FLAG_GRANT_READ_URI_PERMISSION），可以把缓存里的包删掉了。
            // 但我们**保留**到下次启动再清：万一用户这次没装成功，还能直接再点一次而不用重下。
        } catch (Exception error) {
            Log.w("拉起系统安装器失败", error);
            setUpdateStatus("拉起系统安装器失败：" + Log.describe(error)
                    + "\n（如果提示 ActivityNotFound，说明这台设备的系统里没有安装器。）", true);
        }
    }

    /** 新版本起来后清理上一版留下的安装包/半成品（这时才确定不需要它们了）。 */
    private void cleanUpdateWorkFilesIfUpgraded() {
        File dir = Prefs.updateDir(this);
        int installed = Prefs.installedVersionCode(this);
        UpdatePrefs prefs = new UpdatePrefs(this);
        String savedPath = prefs.downloadedPath();
        if (!savedPath.isEmpty() && installed >= 0) {
            File saved = new File(savedPath);
            // 文件名里带着「下载时那个版本的 versionCode」；本机 versionCode 已经比它大
            // ⇒ 说明这次安装真的成功了，那份 APK 没用了。
            int downloadedCode = versionCodeFromName(saved.getName());
            if (downloadedCode > 0 && installed > downloadedCode) {
                Log.i("检测到已升级到 versionCode=" + installed + "，清理上一版的安装包：" + saved.getName());
                if (saved.isFile()) saved.delete();
                prefs.clearDownloadedPath();
            } else if (!saved.isFile()) {
                prefs.clearDownloadedPath();
            }
        }
        // 半成品（.part）无论什么情况都可以清掉：要接着下的话重新下一段也不亏。
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().endsWith(".part")) {
                    if (file.delete()) Log.d("已清理未完成的下载：" + file.getName());
                }
            }
        }
    }

    /**
     * 从下载时用的文件名里抠出「当时那个 versionCode」：
     * {@code pixiko-update-1.6.1-161.verified.apk} → 161，{@code …-161.part} → 161。
     *
     * <p>用 {@code Matcher.find()}（不是 {@code matches()}）并允许 {@code .part} / {@code .verified.apk}
     * 两种后缀：只有 {@code find()} 才不用把整串钉死，将来改文件名规则不会静默失效。
     * 抠不到返回 -1（调用方按「不知道」处理，宁可不删）。
     */
    static int versionCodeFromName(String name) {
        if (name == null) return -1;
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("-(\\d+)(?:\\.verified)?\\.(?:apk|part)$").matcher(name);
        if (!matcher.find()) return -1;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private void setUpdateStatus(String text, boolean error) {
        if (updateStatusText == null) return;
        updateStatusText.setText(text == null ? "" : text);
        updateStatusText.setTextColor(ContextCompat.getColor(this,
                error ? android.R.color.holo_red_light : R.color.pixiko_text));
    }

    /** 把状态同步给主界面横幅。 */
    private void broadcastStatus(String text, boolean good, UpdateInfo info) {
        Intent intent = new Intent(MainActivity.ACTION_UPDATE_STATUS)
                .setPackage(getPackageName())
                .putExtra(MainActivity.EXTRA_UPDATE_STATUS_TEXT, text)
                .putExtra(MainActivity.EXTRA_UPDATE_STATUS_GOOD, good);
        MainActivity.putUpdateInfoExtra(intent, info);
        sendBroadcast(intent);
    }

    /** 人类可读的字节数（进度条文案用）。 */
    static String humanBytes(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从「安装未知应用」设置页回来：授权开了就自动接着装，用户不用再点一次。
        if (awaitingInstallPermission) {
            if (UpdateInstaller.canRequestPackageInstalls(this)) {
                awaitingInstallPermission = false;
                File apk = downloadedApk != null ? downloadedApk
                        : (new UpdatePrefs(this).downloadedPath().isEmpty()
                        ? null : new File(new UpdatePrefs(this).downloadedPath()));
                if (apk != null && apk.isFile()) {
                    Log.i("「安装未知应用」已授权，自动接着安装");
                    promptInstall(apk);
                }
            } else {
                setUpdateStatus(getString(R.string.settings_update_unknown_sources), true);
            }
        }
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
        // 更新信息也要活过旋转/重建，否则用户刚看到「发现新版本」一转身就没了。
        if (availableUpdate != null) {
            outState.putInt("update_info_version_code", availableUpdate.versionCode);
            outState.putString("update_info_version", availableUpdate.version);
            outState.putLong("update_info_size", availableUpdate.sizeBytes);
            outState.putString("update_info_sha256", availableUpdate.sha256);
            outState.putString("update_info_apk_url", availableUpdate.apkUrl);
            outState.putString("update_info_release_url", availableUpdate.releaseUrl);
            outState.putString("update_info_notes", availableUpdate.notes);
            outState.putString("update_info_channel", availableUpdate.channel);
            outState.putString("update_info_channel_note", availableUpdate.channelNote);
        }
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
        mainHandler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(updateStatusReceiver);
        } catch (IllegalArgumentException error) {
            Log.d("更新状态广播接收器未注册，无需反注册");
        }
        super.onDestroy();
    }
}
