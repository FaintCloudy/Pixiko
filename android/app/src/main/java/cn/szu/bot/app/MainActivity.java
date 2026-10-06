package cn.szu.bot.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;

/**
 * 主界面：把网页控制台整站装进 WebView。
 *
 * <p><b>设计立场</b>：网页端有的功能（出图、对话、提示词、风格、LoRA、功能、聊天设置、系统、
 * 首次配置、日志、回执、帮助——共 13 个栏目）一律<b>不重新实现</b>，WebView 原样加载网页即可。
 * 默认打开的是<b>手机端界面</b> {@code /m}（另一套从零写的 App 风格页面，复用同一套 {@code /api/*}）；
 * 完整网页控制台 {@code /}（出图页，右栏常驻对话）保留为可切换的第二入口（见 {@link #toggleUi()}）。
 * 原生只补网页做不到的部分：服务器配置、局域网扫描、图片保存/分享、下拉刷新、原生错误页、
 * 屏幕常亮、加载进度。
 *
 * <p>几个关键实现点（细节见各方法注释）：
 * <ul>
 *   <li><b>默认入口 {@code /m} ＋ 菜单一键切回 {@code /}</b>（选择记在 SharedPreferences，见 {@link #uiPath()}）；</li>
 *   <li><b>{@code /m} 下隐藏原生 ActionBar</b>（网页自带 app bar ＋ 底部 tab，叠起来是两条栏），
 *       app 级入口改由 {@link PixikoBridge} 的四个桥方法从网页里调回来（见 {@link #applyActionBarVisibility()}）；</li>
 *   <li>令牌自动注入 + 只 reload 一次（{@link #maybeInjectToken}）；</li>
 *   <li>图片长按/右键 → 原生菜单（{@link NativeHook#hookScript()} + {@link PixikoBridge}）；</li>
 *   <li>返回键三级：WebView 回退 → 回首页 → 双击退出；</li>
 *   <li>旋转不重建（manifest 的 configChanges）+ onSaveInstanceState 双保险。</li>
 * </ul>
 */
public class MainActivity extends AppCompatActivity {

    /** 服务器列表页返回时的请求码。 */
    private static final int REQUEST_SETTINGS = 1001;

    /** 网页 console 输出的 logcat tag（与原生日志的 PixikoApp 分开，见 onConsoleMessage）。 */
    private static final String WEB_TAG = "PixikoWeb";

    /**
     * 自动检查更新的延迟。
     *
     * <p>为什么要延迟：启动那几秒是 WebView 建进程、解析网页、拉首屏数据的时候，更新检查虽然已经在
     * 子线程上，但会跟首屏抢带宽/CPU。等 6 秒再发那一个几百字节的 POST，用户完全感觉不到，
     * 首屏却干净了。这是本功能<b>不阻塞首屏</b>的具体做法（另一层保证：整个检查都在子线程）。
     */
    private static final long AUTO_UPDATE_CHECK_DELAY_MS = 6_000L;

    /** 跨 Activity 通知「有新版本」的广播（MainActivity 横幅 ↔ SettingsActivity 红点）。 */
    static final String ACTION_UPDATE_STATUS = "cn.szu.bot.app.action.UPDATE_STATUS";

    /** extra：一句话结论。 */
    static final String EXTRA_UPDATE_STATUS_TEXT = "status_text";
    /** extra：这个结论要不要按「好消息」显示（有新版本 = true）。 */
    static final String EXTRA_UPDATE_STATUS_GOOD = "status_good";
    /** extra：有新版本时把 UpdateInfo 的关键字段带过去，设置页不用再查一次。 */
    static final String EXTRA_UPDATE_VERSION = "version";
    static final String EXTRA_UPDATE_VERSION_CODE = "versionCode";
    static final String EXTRA_UPDATE_SIZE = "sizeBytes";
    static final String EXTRA_UPDATE_SHA256 = "sha256";
    static final String EXTRA_UPDATE_APK_URL = "apkUrl";
    static final String EXTRA_UPDATE_RELEASE_URL = "releaseUrl";
    static final String EXTRA_UPDATE_PUBLISHED_AT = "publishedAt";
    static final String EXTRA_UPDATE_NOTES = "notes";
    static final String EXTRA_UPDATE_CHANNEL = "channel";
    static final String EXTRA_UPDATE_CHANNEL_NOTE = "channelNote";

    private ServerRepository repository;
    private ServerConfig current;

    private Toolbar toolbar;
    private ProgressBar progress;
    private SwipeRefreshLayout swipe;
    private WebView webView;
    private View errorPage;
    private TextView errorDetail;

    /** 「发现新版本」横幅及其控件（默认整条 GONE，只有真查到更新才显示）。 */
    private View updateBanner;
    private TextView updateBannerText;
    private TextView updateBannerChannel;
    /** 最近一次检查到的更新；用户在横幅上点「下载更新」时要用它。 */
    private UpdateInfo pendingUpdate;

    /** 主线程 Handler：延迟检查更新、以及把子线程结果贴回 UI。 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 自动检查的延迟任务，onDestroy 里要撤掉（否则 Activity 没了还在跑）。 */
    private Runnable autoCheckTask;

    /** 更新横幅 ↔ 设置页红点的广播（见 onUpdateStatus 与 update_status_receiver）。 */
    private final BroadcastReceiver updateStatusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            onUpdateStatusBroadcast(intent);
        }
    };

    /** 这次运行是否已经排期过自动检查（旋转/重建/多次调用只查一次）。 */
    private boolean autoCheckScheduled;

    /** 网页令牌是否已经注入过（同一轮加载只处理一次，见 {@link #maybeInjectToken}）。 */
    private boolean tokenInjected;
    /** 用户点了「清除登录状态」：这一轮不再自动注入令牌，让网页老老实实回到锁屏。 */
    private boolean suppressTokenInjection;
    /** 当前页面是不是我们自己那台服务器的页面（决定桥挂不挂）。 */
    private boolean bridgeAttached;
    /** 最近一次加载失败是不是「主文档」失败（子资源失败不弹错误页）。 */
    private boolean mainFrameFailed;
    /** 回到首页时不要再把首页记成「上次页面」。 */
    private String currentUrl = "";

    private PixikoBridge bridge;
    private ValueCallback<Uri[]> pendingFileCallback;

    private static final String PREF_UI = "pixiko_ui";
    private static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";

    /**
     * 本机的设备 scope（{@code dev-xxxxxxxxxxxx}，见 {@link DeviceScope}）。
     * {@code null} ＝ 还没取过；{@link #deviceScope()} 里懒取一次就缓存下来。
     */
    private String deviceScope;

    /**
     * 用户选的是「手机界面（/m）」还是「完整控制台（/）」。
     * 缺省值取 {@link UrlHelper#PATH_MOBILE}：也就是<b>没存过任何值时默认打开 /m</b>。
     */
    private static final String KEY_UI_PATH = "ui_path";

    /**
     * 「切换界面」这一项的两种文案（随当前状态二选一，见 {@link #onPrepareOptionsMenu}）。
     *
     * <p>刻意写成 Java 字面量而不是 {@code strings.xml}：这样「默认走 /m」这件事连同菜单文案
     * 都会出现在 {@code classes.dex} 的字符串常量池里，可以被静态核验（见交付说明里的做法），
     * 不必反编译字节码。
     */
    private static final String TITLE_SWITCH_TO_CONSOLE = "切换到完整控制台（/）";
    private static final String TITLE_SWITCH_TO_MOBILE = "切换到手机界面（/m）";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 设备身份要**尽早**落实：它决定 /m 的 scope（每台手机各自一段对话），
        // 而且要在任何 loadUrl / 保存恢复之前就已生成，免得异常路径上漏掉。
        deviceScope();

        repository = new ServerRepository(this);
        current = repository.current();
        if (current == null) {
            // 一台都没配：直接去设置页，别给用户看一个必然失败的错误页。
            ToastBus.shortToast(this, getString(R.string.no_server));
            openServerSettings();
            finish();
            return;
        }
        // 把「上次用的服务器」落实，下次启动直接进主界面。
        repository.setLastId(current.id);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(true);
            getSupportActionBar().setTitle(R.string.app_name);
        }
        // 这里也要同步一次：走 onSaveInstanceState 恢复（进程被回收后重建）时下面走的是 loadUrl(restore)，
        // 不会经过 loadHome()，光靠 loadHome() 里的那次同步会漏掉这条路径。
        applyActionBarVisibility();
        progress = findViewById(R.id.progress);
        swipe = findViewById(R.id.swipe);
        webView = findViewById(R.id.webview);
        errorPage = findViewById(R.id.error_page);
        errorDetail = findViewById(R.id.error_detail);

        bindErrorPage();
        bindUpdateBanner();
        configureWebView();
        configureSwipe();
        applyKeepScreenOnState();
        requestLegacyStoragePermissionIfNeeded();
        // 跨 Activity 的更新状态通道（设置页点完「检查更新」后横幅要能跟着变）。
        // Android 13+ 起 registerReceiver 要显式写 exported，这里只收自己进程内的广播，所以是 NOT_EXPORTED。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateStatusReceiver, new IntentFilter(ACTION_UPDATE_STATUS), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateStatusReceiver, new IntentFilter(ACTION_UPDATE_STATUS));
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { handleBack(); }
        });

        String restore = savedInstanceState == null ? null : savedInstanceState.getString("webview_state_url");
        if (restore != null && restore.startsWith(current.base)) {
            // 旋转/重建后回到刚才那一页，而不是回首页。
            loadUrl(restore);
        } else {
            loadHome();
        }
        // 从这里就开始计时（而不是等 onPageFinished）：首屏慢的时候也要保证「启动后几秒就检查」这个语义。
        // 重复调用是安全的 —— scheduleAutoUpdateCheck 里的 autoCheckScheduled 保证只排一次。
        scheduleAutoUpdateCheck();
    }

    // ------------------------------------------------------------------ WebView 配置

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        // 网页控制台（webui/app.js）重度依赖 JS / fetch / localStorage，这些一个都不能少。
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);          // localStorage：令牌与前端状态都在这
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        // 网页是响应式的：用手机宽度当 CSS 宽度，别去覆盖 user-agent 的 mobile 标记。
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);                // 网页自带图片查看器，不需要 WebView 缩放
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        // 不新开窗口：配合 WebViewClient 的 shouldOverrideUrlLoading，target=_blank 的链接也在当前
        // WebView 里打开（网页里 /quest 之类的链接就是这种，开第二个窗口反而会让状态栏变黑）。
        settings.setSupportMultipleWindows(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // 局域网是 http，网页里的图标等资源可能来自 https 图床；兼容模式避免被拦。
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        // 深色网页 + 深色外壳：让 WebView 自己的底色也是暗的，页面切换时不会闪白。
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.pixiko_bg));

        // localStorage 默认在 WebView 重建时可能被清；这里显式打开持久化。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }

        webView.setWebViewClient(new PixikoWebViewClient());
        webView.setWebChromeClient(new PixikoChromeClient());

        // 长按 WebView 本身会选中文字；图片的长按由注入的 JS 处理（见 NativeHook）。
        webView.setLongClickable(true);

        // 下载链接 → 系统 DownloadManager 存到 Downloads。
        webView.setDownloadListener(new PixikoDownloadListener());
    }

    private void configureSwipe() {
        swipe.setColorSchemeColors(ContextCompat.getColor(this, R.color.pixiko_accent));
        swipe.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this, R.color.pixiko_bar));
        swipe.setOnRefreshListener(() -> {
            // 下拉刷新＝重新加载当前栏目（保留滚动位置由网页自己记忆，这里只管重新拉数据）。
            hideErrorPage();
            webView.reload();
        });
    }

    private void bindErrorPage() {
        findViewById(R.id.error_retry).setOnClickListener(view -> {
            hideErrorPage();
            webView.reload();
        });
        // 错误页这三个按钮都挂在内容区（不是 ActionBar/菜单上），所以 /m 隐藏原生栏以后照样能用。
        findViewById(R.id.error_settings).setOnClickListener(view -> openServerSettings());
        findViewById(R.id.error_browser).setOnClickListener(view -> openInBrowser());
    }

    // ------------------------------------------------------------------ 检查更新（横幅）

    private void bindUpdateBanner() {
        updateBanner = findViewById(R.id.update_banner);
        updateBannerText = findViewById(R.id.update_banner_text);
        updateBannerChannel = findViewById(R.id.update_banner_channel);
        if (updateBanner == null) return;

        Button download = findViewById(R.id.update_banner_download);
        Button detail = findViewById(R.id.update_banner_detail);
        Button later = findViewById(R.id.update_banner_later);
        if (download != null) download.setOnClickListener(view -> startUpdateDownload());
        if (detail != null) detail.setOnClickListener(view -> showUpdateDetail());
        // 「稍后」：记下这个 versionCode，本次运行内不再弹（下个版本出来时会重新提示）。
        if (later != null) {
            later.setOnClickListener(view -> {
                if (pendingUpdate != null) {
                    new UpdatePrefs(this).ignoreVersion(pendingUpdate.versionCode);
                    Log.i("用户点了「稍后」，本版本不再提示：versionCode=" + pendingUpdate.versionCode);
                }
                hideUpdateBanner();
            });
        }
    }

    /**
     * 安排一次自动检查：**延迟若干秒**、在**子线程**上跑，绝不阻塞首屏。
     *
     * <p>三个前置条件都满足才发这一次请求，省掉没意义的网络动作与日志：
     * <ol>
     *   <li>设置里那个开关是开的（默认开）；</li>
     *   <li>已经配了服务器（一台都没配时连地址都没有，检查必然失败）；</li>
     *   <li>这次运行还没检查过（{@code autoCheckScheduled} 挡住旋转/重入）。</li>
     * </ol>
     */
    private void scheduleAutoUpdateCheck() {
        if (autoCheckScheduled) return;
        if (current == null) return;
        if (!new UpdatePrefs(this).autoCheck()) {
            Log.d("自动检查更新：设置里关掉了，跳过");
            return;
        }
        autoCheckScheduled = true;
        final String base = current.base;
        final String token = current.token;
        autoCheckTask = () -> checkForUpdate(base, token, false);
        mainHandler.postDelayed(autoCheckTask, AUTO_UPDATE_CHECK_DELAY_MS);
        Log.d("自动检查更新已排期：" + AUTO_UPDATE_CHECK_DELAY_MS / 1000 + " 秒后在子线程检查 " + base);
    }

    /**
     * 真的去查一次。<b>必须在子线程调用</b>（网络 IO）。
     *
     * @param manual 是不是用户手动点的（手动时忽略「稍后」记录，并把结果也发广播给设置页）
     */
    private void checkForUpdate(final String base, final String token, final boolean manual) {
        UpdateChecker.Outcome outcome = UpdateChecker.check(base, token, null);
        if (outcome.ok && outcome.info != null) {
            UpdateInfo info = outcome.info;
            if (!info.isNewerThan(Prefs.installedVersionCode(this))) {
                // 没有更新（或本机反而更新）：安静收场。手动检查时给一句「已是最新」的反馈。
                Log.d("更新检查完成：没有比本机 versionCode=" + Prefs.installedVersionCode(this)
                        + " 更新的版本（服务端 version=" + info.version + " code=" + info.versionCode + "）");
                if (manual) {
                    pushUpdateStatus(getString(R.string.settings_update_up_to_date, info.version), false, null);
                }
                return;
            }
            // 有新版本：用户点过「稍后」的**同一个** versionCode 不再打扰（手动检查不受此限）。
            int ignored = new UpdatePrefs(this).ignoredVersionCode();
            if (!manual && ignored == info.versionCode) {
                Log.d("新版本 versionCode=" + info.versionCode + " 已被用户「稍后」，本次不再提示");
                return;
            }
            Log.i("发现新版本：" + info.version + "（versionCode " + info.versionCode + "，本机 "
                    + Prefs.installedVersionCode(this) + "），" + info.sizeText() + "，渠道="
                    + (info.channel.isEmpty() ? "(未标注)" : info.channel)
                    + (info.canInstall() ? "，可下载" : "，但服务端没给可下载的包"));
            pushUpdateStatus(describeUpdate(info), true, info);
            return;
        }
        Log.w("更新检查没成功：" + outcome.message);
        if (manual) pushUpdateStatus(outcome.message, false, null);
    }

    /** 「发现新版本 1.7.0（当前 1.6.0）· 6.0 MB」这句横幅主文案。 */
    private String describeUpdate(UpdateInfo info) {
        StringBuilder text = new StringBuilder();
        text.append(getString(R.string.update_banner_title, displayVersion(info), Prefs.installedVersionName(this)));
        String size = info.sizeText();
        if (!size.isEmpty()) text.append(" · ").append(size);
        return text.toString();
    }

    /** 服务端没给 version 字符串时退回 versionCode 显示，别显示一个空白。 */
    private String displayVersion(UpdateInfo info) {
        return info.version.isEmpty() ? ("versionCode " + info.versionCode) : info.version;
    }

    private void showUpdateBanner(UpdateInfo info) {
        if (updateBanner == null) return;
        if (isFinishing() || isDestroyed()) return;
        pendingUpdate = info;
        if (updateBannerText != null) updateBannerText.setText(describeUpdate(info));
        if (updateBannerChannel != null) {
            // 渠道那一行很重要：现阶段服务端下发的是「带局域网地址的测试包」，必须让用户看见。
            updateBannerChannel.setText(info.channelText());
            updateBannerChannel.setVisibility(View.VISIBLE);
        }
        // 文案由更新状态机给出（与设置页那个一键按钮同一个函数、同一份 UpdateInfo）：
        // 没有可下载的包时退化成「详情」（详见 UpdateInfo.blockReason()），
        // 本地已经有当前版本的校验通过包时是「安装 X」—— 点下去的行为与这句话一致（见 startUpdateDownload）。
        Button download = findViewById(R.id.update_banner_download);
        if (download != null) {
            UpdateUiState.State state = bannerState(info);
            download.setEnabled(state.enabled);
            download.setAlpha(state.enabled ? 1f : 0.5f);
            download.setText(getString(state.bannerRes, state.bannerArgs));
        }
        updateBanner.setVisibility(View.VISIBLE);
    }

    /**
     * 横幅按钮的状态：<b>与设置页同源</b> —— 同一份 {@code UpdatePrefs.downloadedPath()} 记录、
     * 同一份 {@link UpdateInfo}、同一个 {@link UpdateUiState#derive}。
     *
     * <p>本地那份缓存是不是「就是服务端当前这一版」用 {@link UpdateCache#looksVerifiedFor} 判
     * （只比文件名，几微秒，绝不在主线程哈希几 MB 的文件）；真正安装之前，
     * 设置页还会用实测 sha256 再核一遍（{@code promptInstall → checkInstallable}）。
     */
    private UpdateUiState.State bannerState(UpdateInfo info) {
        String savedPath = new UpdatePrefs(this).downloadedPath();
        File saved = savedPath.isEmpty() ? null : new File(savedPath);
        boolean cacheReady = UpdateCache.looksVerifiedFor(info, saved);
        return UpdateUiState.derive(true, true, false, info, Prefs.installedVersionCode(this),
                cacheReady, false, 0, "");
    }

    private void hideUpdateBanner() {
        pendingUpdate = null;
        if (updateBanner != null) updateBanner.setVisibility(View.GONE);
    }

    /** 「详情」：把版本号 / 大小 / 渠道 / 更新说明摊开给用户看（不打断，弹窗可关）。 */
    private void showUpdateDetail() {
        UpdateInfo info = pendingUpdate;
        if (info == null) return;
        StringBuilder message = new StringBuilder();
        message.append("新版本：").append(displayVersion(info))
                .append("（versionCode ").append(info.versionCode).append("）\n");
        message.append("当前版本：").append(Prefs.installedVersionName(this))
                .append("（versionCode ").append(Prefs.installedVersionCode(this)).append("）\n");
        if (!info.sizeText().isEmpty()) message.append("安装包大小：").append(info.sizeText()).append('\n');
        message.append("渠道：").append(info.channelText()).append('\n');
        if (!info.publishedAt.isEmpty()) message.append("发布时间：").append(info.publishedAt).append('\n');
        if (!info.sha256.isEmpty()) message.append("sha256：").append(info.sha256.substring(0, Math.min(16, info.sha256.length()))).append("…\n");
        if (!info.notes.isEmpty()) message.append("\n").append(getString(R.string.settings_update_notes_title)).append("：\n").append(info.notes);
        String blocked = info.blockReason();
        if (!blocked.isEmpty()) message.append("\n\n").append(blocked);

        new AlertDialog.Builder(this, R.style.Theme_Pixiko_Dialog)
                .setTitle(R.string.settings_update_title)
                .setMessage(message.toString())
                .setNegativeButton(R.string.update_banner_later, (dialog, which) -> {
                    new UpdatePrefs(MainActivity.this).ignoreVersion(info.versionCode);
                    hideUpdateBanner();
                })
                .setPositiveButton(info.canInstall() ? R.string.update_banner_action : R.string.about_ok,
                        (dialog, which) -> {
                            if (info.canInstall()) startUpdateDownload();
                        })
                .show();
    }

    /**
     * 用户点了「下载更新」：把下载/校验/安装这一整套交给 {@link SettingsActivity} 的「应用更新」区去做。
     *
     * <p>为什么不就地下载：下载进度、校验结果、失败重试、以及「缺安装权限时去系统设置」这些都需要
     * 一块稳定的 UI 落点，而横幅在 {@code /m} 下是很窄的一条、还可能被网页内容挤走。
     * 更新区在设置页里是常驻的，进程被杀也能从那里接着装。
     */
    private void startUpdateDownload() {
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.putExtra(SettingsActivity.EXTRA_AUTO_DOWNLOAD, true);
        putUpdateInfoExtra(intent, pendingUpdate);
        startActivityForResult(intent, REQUEST_SETTINGS);
    }

    /** 把 UpdateInfo 拆成 Intent extras（能塞的字段都塞，设置页就不用再查一次）。 */
    static void putUpdateInfoExtra(Intent intent, UpdateInfo info) {
        if (intent == null || info == null) return;
        intent.putExtra(EXTRA_UPDATE_VERSION, info.version);
        intent.putExtra(EXTRA_UPDATE_VERSION_CODE, info.versionCode);
        intent.putExtra(EXTRA_UPDATE_SIZE, info.sizeBytes);
        intent.putExtra(EXTRA_UPDATE_SHA256, info.sha256);
        intent.putExtra(EXTRA_UPDATE_APK_URL, info.apkUrl);
        intent.putExtra(EXTRA_UPDATE_RELEASE_URL, info.releaseUrl);
        intent.putExtra(EXTRA_UPDATE_NOTES, info.notes);
        intent.putExtra(EXTRA_UPDATE_CHANNEL, info.channel);
        intent.putExtra(EXTRA_UPDATE_CHANNEL_NOTE, info.channelNote);
    }

    /** 从 extras 还原一个 UpdateInfo（缺字段时按空处理，绝不因为 extra 缺失而崩）。 */
    static UpdateInfo updateInfoFrom(Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_UPDATE_VERSION_CODE)) return null;
        return new UpdateInfo(
                intent.getStringExtra(EXTRA_UPDATE_VERSION),
                intent.getIntExtra(EXTRA_UPDATE_VERSION_CODE, -1),
                intent.getLongExtra(EXTRA_UPDATE_SIZE, -1L),
                intent.getStringExtra(EXTRA_UPDATE_SHA256),
                intent.getStringExtra(EXTRA_UPDATE_APK_URL),
                intent.getStringExtra(EXTRA_UPDATE_RELEASE_URL),
                intent.getCharSequenceExtra(EXTRA_UPDATE_PUBLISHED_AT) == null
                        ? "" : intent.getCharSequenceExtra(EXTRA_UPDATE_PUBLISHED_AT).toString(),
                intent.getStringExtra(EXTRA_UPDATE_NOTES),
                intent.getStringExtra(EXTRA_UPDATE_CHANNEL),
                intent.getStringExtra(EXTRA_UPDATE_CHANNEL_NOTE));
    }

    /** 子线程算完 → 主线程贴横幅（并广播给设置页）。 */
    private void pushUpdateStatus(final String statusText, final boolean good, final UpdateInfo info) {
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (good && info != null) showUpdateBanner(info);
            Intent broadcast = new Intent(ACTION_UPDATE_STATUS)
                    .setPackage(getPackageName())
                    .putExtra(EXTRA_UPDATE_STATUS_TEXT, statusText)
                    .putExtra(EXTRA_UPDATE_STATUS_GOOD, good);
            putUpdateInfoExtra(broadcast, info);
            sendBroadcast(broadcast);
        });
    }

    /** 收到设置页发来的更新状态：同步横幅。 */
    private void onUpdateStatusBroadcast(Intent intent) {
        if (intent == null) return;
        boolean good = intent.getBooleanExtra(EXTRA_UPDATE_STATUS_GOOD, false);
        UpdateInfo info = updateInfoFrom(intent);
        if (good && info != null) {
            showUpdateBanner(info);
        } else {
            // 设置页里点了「检查更新」但没查到更新/查失败了 → 横幅收起来（避免显示过期信息）。
            hideUpdateBanner();
        }
        String text = intent.getStringExtra(EXTRA_UPDATE_STATUS_TEXT);
        if (text != null && !text.isEmpty()) ToastBus.shortToast(this, text);
    }

    /** onDestroy：把延迟任务与广播接收器都撤掉，别让它们拖着已销毁的 Activity。 */
    private void releaseUpdateWiring() {
        if (autoCheckTask != null) {
            mainHandler.removeCallbacks(autoCheckTask);
            autoCheckTask = null;
        }
        try {
            unregisterReceiver(updateStatusReceiver);
        } catch (IllegalArgumentException error) {
            // 没注册成功就退出（例如 onCreate 提前 return 的那条路径）—— 这是正常的，不用报错。
            Log.d("更新状态广播接收器未注册，无需反注册");
        }
    }

    // ------------------------------------------------------------------ 加载与状态

    /**
     * 当前选择的界面路径：{@link UrlHelper#PATH_MOBILE}（手机界面，默认）或
     * {@link UrlHelper#PATH_CONSOLE}（完整控制台）。用户切过就记住（SharedPreferences），
     * 下次启动、以及「回到首页」都按这个选择走。
     *
     * <p>只有明确存成 {@code "/"} 才算完整控制台，其余任何情况（没存过、值坏了）一律回落 {@code /m}。
     */
    private String uiPath() {
        String saved = getSharedPreferences(PREF_UI, MODE_PRIVATE).getString(KEY_UI_PATH, UrlHelper.PATH_MOBILE);
        return UrlHelper.PATH_CONSOLE.equals(saved) ? UrlHelper.PATH_CONSOLE : UrlHelper.PATH_MOBILE;
    }

    /** 现在选的是不是手机界面（默认就是）。 */
    private boolean mobileUiSelected() {
        return !UrlHelper.PATH_CONSOLE.equals(uiPath());
    }

    private void setUiPath(String path) {
        getSharedPreferences(PREF_UI, MODE_PRIVATE).edit()
                .putString(KEY_UI_PATH, UrlHelper.PATH_CONSOLE.equals(path) ? UrlHelper.PATH_CONSOLE : UrlHelper.PATH_MOBILE)
                .apply();
    }

    /**
     * 首页地址：服务器根地址不变，只换路径后缀——默认 {@code http://host:8787/m}，
     * 切到完整控制台后是 {@code http://host:8787/}。
     *
     * <p>手机界面（{@code /m}）要带上<b>本机的设备 scope</b>：{@code /m?scope=dev-xxxxxxxxxxxx}。
     * 这是「每台手机的对话各自独立」的入口——{@code webui/m/app.js} 只认这个 scope，
     * 于是这台手机的对话落在服务端 {@code data/webui/dev-xxxxxxxxxxxx-chat-log.json}，
     * 与控制台的 {@code web} 互不可见。见 {@link #deviceScope} 与 {@link DeviceScope#pageUrl}。
     *
     * <p>完整控制台（{@code /}）<b>不带</b>这个参数：它的 scope 来自 {@code /api/status} 回的
     * {@code settings.webScope()}（{@code "web"}），保持原样。
     */
    private String homeUrl() {
        String path = uiPath();
        if (UrlHelper.PATH_CONSOLE.equals(path)) return UrlHelper.join(current.base, path);
        return DeviceScope.pageUrl(current.base, path, deviceScope());
    }

    /**
     * 本机的设备 scope（形如 {@code dev-1a2b3c4d5e6f}）。首次调用时生成并落进
     * SharedPreferences（{@link DeviceScope#deviceId}），之后每次启动都是同一个值 ——
     * 卸载重装才会换一个，也就是"重装后重新开一段自己的对话"。
     *
     * <p>调用时机：{@link #onCreate} 里立刻取一次（同步读一个 UUID，开销可忽略），
     * 这样 {@code homeUrl()}、{@link #onSaveInstanceState} 恢复路径、
     * {@code openInBrowser()} 拿到的都是同一个值，也不会因为异常路径漏生成。
     */
    private String deviceScope() {
        if (deviceScope == null) deviceScope = DeviceScope.scope(this);
        return deviceScope;
    }

    /** 去掉查询串/锚点与尾斜杠，专供地址比较（{@code /m} 与 {@code /m/}、{@code /} 与无尾斜杠等价）。 */
    private static String trimUrl(String url) {
        if (url == null) return "";
        String text = url;
        int at = text.indexOf('#');
        if (at >= 0) text = text.substring(0, at);
        at = text.indexOf('?');
        if (at >= 0) text = text.substring(0, at);
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text;
    }

    /**
     * 这一页是不是两个入口之一？是就返回对应的路径（{@code /m} 或 {@code /}），否则返回 {@code null}。
     *
     * <p>用「和首页地址比字符串」而不是解析路径，这样部署在子路径下
     * （{@code http://host:8787/sub} → 手机界面是 {@code /sub/m}）也算得对；
     * 只有 {@code {base}/m} 与 {@code {base}/} 两种写法算数，{@code /gen} {@code /logs}
     * 这类站内页一律返回 null（原生栏维持现状，不跟着乱跳）。
     */
    private String uiPathOf(String url) {
        String here = trimUrl(url);
        if (here.isEmpty()) return null;
        if (here.equals(trimUrl(UrlHelper.join(current.base, UrlHelper.PATH_MOBILE)))) return UrlHelper.PATH_MOBILE;
        if (here.equals(trimUrl(UrlHelper.join(current.base, UrlHelper.PATH_CONSOLE)))) return UrlHelper.PATH_CONSOLE;
        return null;
    }

    /**
     * 网页自己导航到两个入口路径之一时，把「当前界面」这个选择跟着纠正过来，并同步原生栏。
     *
     * <p>正常切换走 {@link #toggleUi()}，pref 与页面本来就一致；但网页里也可能直接放一条指回
     * {@code /}（或 {@code /m}）的链接 —— 那时 pref 还停在旧值，会出现「显示完整控制台却没有原生顶栏」
     * （完整控制台自己没有顶栏，会很难用）。这里只认这两个入口路径，其余站内页不动。
     */
    private void adoptUiPathFrom(String url) {
        String path = uiPathOf(url);
        if (path == null) return;
        if (!path.equals(uiPath())) {
            setUiPath(path);
            Log.d("网页自己跳到了 " + path + "，已把「当前界面」的选择跟过来");
        }
        applyActionBarVisibility();
    }

    /**
     * 原生 ActionBar 只在「完整控制台（{@code /}）」下显示，进 {@code /m} 就收起来。
     *
     * <p>为什么：{@code /m} 那套手机端界面自带顶部 app bar ＋ 底部 tab，再叠一条原生栏就是<b>两条栏</b>，
     * 不符合手机 app 的习惯。收起原生栏 = 把整屏高度让给网页（{@code activity_main.xml} 里工具栏是
     * {@code LinearLayout} 的第一个子节点，置 GONE 后那一条的高度会立刻被下面收回）。
     *
     * <p>收起之后 app 级入口不丢：{@link PixikoBridge} 暴露了
     * {@code openServerSettings / clearWebCache / clearLoginState / openInBrowser} 四个桥方法给网页调用；
     * 原生错误页上的「重试 / 去设置 / 用浏览器打开」三个按钮也不依赖这条栏，所以照样能用。
     *
     * <p>调用时机：{@link #onCreate}（覆盖恢复路径）、{@link #loadHome()}（覆盖启动、
     * {@link #toggleUi()}、换服务器、从设置页回来这几条路径）、
     * {@link #adoptUiPathFrom(String)}（网页自己跳到 /m 或 / 时）。
     */
    private void applyActionBarVisibility() {
        boolean mobile = mobileUiSelected();
        ActionBar bar = getSupportActionBar();
        if (bar != null) {
            if (mobile) bar.hide(); else bar.show();
        }
        // 双保险：AppCompat 的 hide()/show() 本来就会把这个 Toolbar 置成 GONE/VISIBLE，
        // 这里显式再同步一次，免得个别 ROM / AppCompat 版本上出现「栏还在、只是内容空了」。
        if (toolbar != null) toolbar.setVisibility(mobile ? View.GONE : View.VISIBLE);
        Log.d("原生 ActionBar " + (mobile ? "已隐藏（当前是手机界面 /m，让位给网页自带的 app bar）"
                : "已显示（当前是完整控制台 /，它没有自己的顶栏）"));
    }

    /** 加载首页（默认＝手机界面 {@code /m}；切到完整控制台后＝{@code /}）。 */
    private void loadHome() {
        applyActionBarVisibility();
        loadUrl(homeUrl());
    }

    /**
     * 在「手机界面（/m）」与「完整控制台（/）」之间切换，并把选择记进 SharedPreferences。
     *
     * <p>切换时清掉 WebView 历史：否则用户按返回键会回到上一个界面，而 SharedPreferences 还停在
     * 新选择上，「返回键三级」与菜单文案就会和眼前这一页对不上。清完历史后，返回键在首页＝双击退出。
     *
     * <p>刻意<b>不动</b>令牌注入状态（tokenInjected / suppressTokenInjection）：
     * 两个路径同源，localStorage 里的令牌本来就共用，用户在网页里手动清除登录的意图也不该被这一步覆盖。
     */
    private void toggleUi() {
        boolean toConsole = mobileUiSelected();
        setUiPath(toConsole ? UrlHelper.PATH_CONSOLE : UrlHelper.PATH_MOBILE);
        hideErrorPage();
        webView.clearHistory();
        loadHome();
        ToastBus.shortToast(this, toConsole ? TITLE_SWITCH_TO_CONSOLE : TITLE_SWITCH_TO_MOBILE);
        invalidateOptionsMenu();
    }

    private void loadUrl(String url) {
        hideErrorPage();
        currentUrl = url;
        webView.loadUrl(url);
    }

    private void showErrorPage(String detail) {
        errorDetail.setText(detail == null ? "" : detail);
        errorPage.setVisibility(View.VISIBLE);
        swipe.setRefreshing(false);
    }

    private void hideErrorPage() {
        if (errorPage.getVisibility() != View.GONE) errorPage.setVisibility(View.GONE);
    }

    /**
     * 「在浏览器打开」当前页（拿不到 currentUrl 就开当前界面的首页）。
     *
     * <p>包级可见（非 private）：{@link PixikoBridge#openInBrowser()} 从网页里调回来，见「给网页桥用的入口」。
     */
    void openInBrowser() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl != null && !currentUrl.isEmpty()
                    ? currentUrl : homeUrl()));
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            ToastBus.shortToast(this, getString(R.string.no_browser));
        }
    }

    /**
     * 令牌注入：把用户配置的令牌写进 {@code localStorage['kotori-webui-token']}（键名见 webui/app.js
     * 的 TOKEN_KEY），这样网页启动时就认为自己已登录，用户不用在锁屏里再敲一遍。
     *
     * <p><b>只在真的不一样时才写、写完只 reload 一次</b>：{@code tokenInjected} 是「本轮已处理」标志，
     * 不是「值已经是令牌」的意思——网页自己也可能更新 localStorage（例如用户在锁屏里手敲了令牌），
     * 那时值会不一致，但我们不再 reload，避免和网页互相刷成死循环。
     *
     * <p>唯一的例外是「清除登录状态」之后（{@code suppressTokenInjection}）：那时要尊重用户的意图，
     * 不注入、让网页显示锁屏。
     */
    private void maybeInjectToken() {
        if (tokenInjected || suppressTokenInjection) return;
        tokenInjected = true;
        String token = current.token == null ? "" : current.token;
        webView.evaluateJavascript(NativeHook.tokenScript(token), value -> {
            // evaluateJavascript 的回调在 UI 线程；返回值是带引号的 JSON 字符串字面量。
            String result = value == null ? "" : value.replace("\"", "");
            if (result.startsWith("written")) {
                Log.d("令牌已注入 localStorage，reload 一次让网页进入已登录状态");
                webView.reload();
            } else {
                Log.d("令牌注入无需改动（网页 localStorage 里已经是同一个值），结果=" + result);
            }
        });
    }

    /**
     * 挂图片长按/右键的钩子。只对我们自己那台服务器的页面挂（见 {@link NativeHook#isTrustedHost}）：
     * 免得用户在网页里点开外链后，第三方站点也能指挥 app 下载文件。
     */
    private void attachNativeBridgeIfTrusted(String url) {
        boolean trusted = NativeHook.isTrustedHost(current.base, url);
        if (trusted == bridgeAttached) return;
        if (trusted) {
            if (bridge == null) bridge = new PixikoBridge(this);
            webView.addJavascriptInterface(bridge, NativeHook.INTERFACE_NAME);
            bridgeAttached = true;
            Log.d("已挂原生图片桥（PixikoNative）");
        } else {
            webView.removeJavascriptInterface(NativeHook.INTERFACE_NAME);
            bridgeAttached = false;
            Log.d("当前页面不是配置的服务器，已卸下原生桥");
        }
    }

    private void injectHookScript() {
        if (!bridgeAttached) return;
        webView.evaluateJavascript(NativeHook.hookScript(), value -> Log.d("图片长按钩子注入结果：" + value));
    }

    /**
     * 备用通道：把设备 scope 注入成 {@code window.__PIXIKO_SCOPE}。
     *
     * <p>主通道是地址上的 {@code ?scope=…}（见 {@link #homeUrl()}），注入这份是为了
     * "外壳越过地址栏直接说话"这一条路也通（网页端两条都认，见 {@code webui/m/app.js}）。
     * 只在手机界面（{@code /m}）注入：完整控制台（{@code /}）有自己的 scope 来源，别去打扰它。
     *
     * <p>刻意<b>不</b>用返回值触发 reload：地址上本来就带着同一个值，值一样时脚本回 {@code 'same'}，
     * 不一样时网页自己会把本机镜像改过来，不需要整页刷新（刷新反而会让用户看到闪一下）。
     */
    private void injectDeviceScope(String url) {
        if (!bridgeAttached) return;
        String path = UrlHelper.stripQuery(url == null ? "" : url);
        String mobile = UrlHelper.stripQuery(UrlHelper.join(current.base, UrlHelper.PATH_MOBILE));
        if (!path.equals(mobile)) return;
        String scope = deviceScope();
        webView.evaluateJavascript(NativeHook.deviceScopeScript(scope),
                value -> Log.d("设备 scope 注入结果：" + value + "（scope=" + scope + "）"));
    }

    // ------------------------------------------------------------------ WebViewClient

    private class PixikoWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request.getUrl().toString());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(url);
        }

        /**
         * http/https 一律留在 WebView 里（含 target=_blank：我们没开多窗口，新窗口请求也走这里），
         * 其它 scheme（tel:/mailto:/微信 等）交回系统处理，免得 WebView 报 ERR_UNKNOWN_URL_SCHEME。
         */
        private boolean handleUrl(String url) {
            if (url == null) return false;
            String lower = url.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                currentUrl = url;
                return false;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException error) {
                Log.w("没有能处理这个链接的应用：" + UrlHelper.stripQuery(url));
            }
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            currentUrl = url;
            mainFrameFailed = false;
            hideErrorPage();
            progress.setVisibility(View.VISIBLE);
            progress.setProgress(0);
            attachNativeBridgeIfTrusted(url);
            maybeInjectToken();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            progress.setVisibility(View.INVISIBLE);
            swipe.setRefreshing(false);
            if (mainFrameFailed) return;      // 失败页由 onReceivedError 负责，别把它当成正常页面
            currentUrl = url;
            attachNativeBridgeIfTrusted(url);
            injectHookScript();
            injectDeviceScope(url);
            // 有些 ROM 的 WebView 在 onPageStarted 时 localStorage 还没准备好，这里补一次注入。
            if (!tokenInjected) maybeInjectToken();
            Log.d("页面加载完成：" + UrlHelper.stripQuery(url));
            // 首屏已经好了，这才安排自动检查更新（延迟 + 子线程，见 scheduleAutoUpdateCheck）。
            scheduleAutoUpdateCheck();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            // 只有主文档失败才弹原生错误页：图标/接口失败不该糊住整个界面。
            if (request == null || !request.isForMainFrame()) return;
            mainFrameFailed = true;
            CharSequence description = error == null ? "" : error.getDescription();
            progress.setVisibility(View.INVISIBLE);
            swipe.setRefreshing(false);
            showErrorPage("加载失败：" + description + "\n" + UrlHelper.stripQuery(request.getUrl().toString()));
            Log.w("主文档加载失败：" + description + " → " + UrlHelper.stripQuery(request.getUrl().toString()));
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            // 兼容 Android 5.x 的老回调（SDK_INT < 23 只会走这个）。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return;
            mainFrameFailed = true;
            progress.setVisibility(View.INVISIBLE);
            swipe.setRefreshing(false);
            showErrorPage("加载失败：" + description + "\n" + UrlHelper.stripQuery(failingUrl));
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse errorResponse) {
            super.onReceivedHttpError(view, request, errorResponse);
            if (request == null || !request.isForMainFrame()) return;
            // 4xx/5xx 也要有原生交代（比如服务端 404 或 500 的错误页）。
            int code = errorResponse == null ? 0 : errorResponse.getStatusCode();
            if (code >= 400) {
                mainFrameFailed = true;
                showErrorPage("服务端返回 HTTP " + code + "\n" + UrlHelper.stripQuery(request.getUrl().toString()));
            }
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            super.doUpdateVisitedHistory(view, url, isReload);
            if (url != null && !url.equals(currentUrl)) currentUrl = url;
            // 历史变化时状态栏标题跟着变，方便一眼看出现在在哪台服务器。
            if (getSupportActionBar() != null) {
                getSupportActionBar().setSubtitle(UrlHelper.hostOf(current.base));
            }
            // 网页自己跳到 /m 或 / 时（例如 /m 里放了一条指回 "/" 的链接）把界面选择与原生栏一起纠正。
            adoptUiPathFrom(url);
        }
    }

    // ------------------------------------------------------------------ WebChromeClient

    private class PixikoChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            progress.setProgress(newProgress);
            progress.setVisibility(newProgress >= 100 ? View.INVISIBLE : View.VISIBLE);
        }

        /**
         * 网页里的 console.*：转 logcat，排查「点了没反应」时最有用的一手资料。
         *
         * <p>tag 固定用 {@code PixikoWeb}（而不是 app 的 PixikoApp）：
         * {@code adb logcat -s PixikoWeb} 就能只看网页侧的输出，和原生日志分开。
         * <b>网页输出的内容可能含令牌</b>（app.js 从不打印令牌，但万一）——
         * 这一行只做转发，转发本身不改写内容；排查完记得别再往外贴这行日志。
         */
        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
            if (message == null) return false;
            String text = message.message() + " @" + message.sourceId() + ":" + message.lineNumber();
            if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                android.util.Log.w(WEB_TAG, text);
            } else {
                android.util.Log.d(WEB_TAG, text);
            }
            return true;   // 已处理，别再打一份系统日志
        }

        /** &lt;input type="file"&gt;：样式 LoRA、参考图这些地方要用（网页里是真实存在的）。 */
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (pendingFileCallback != null) {
                pendingFileCallback.onReceiveValue(null);
                pendingFileCallback = null;
            }
            pendingFileCallback = callback;
            boolean multiple = params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
            String[] accept = params == null ? null : params.getAcceptTypes();
            String mime = "*/*";
            if (accept != null && accept.length > 0 && accept[0] != null && !accept[0].isBlank()) {
                // 网页可能给 ".png,.jpg" 这种扩展名列表；GET_CONTENT 只认 MIME，认得就转，认不出就放开。
                String first = accept[0].split(",")[0].trim();
                mime = first.contains("/") ? first : "*/*";
            }
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mime);
            if (multiple) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                fileChooserLauncher.launch(Intent.createChooser(intent, "选择文件"));
                return true;
            } catch (ActivityNotFoundException error) {
                ToastBus.shortToast(MainActivity.this, "系统里没有文件选择器");
                pendingFileCallback = null;
                return false;
            }
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            // 网页控制台不应该要摄像头/麦克风；保守起见直接拒绝，别让页面卡在等权限上。
            request.deny();
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }
    }

    /** 文件选择的回调：把选中的 Uri 交回网页。 */
    private final ActivityResultLauncher<Intent> fileChooserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (pendingFileCallback == null) return;
                Uri[] uris = null;
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        uris = new Uri[count];
                        for (int i = 0; i < count; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
                    } else if (data.getData() != null) {
                        uris = new Uri[]{data.getData()};
                    }
                }
                pendingFileCallback.onReceiveValue(uris);
                pendingFileCallback = null;
            });

    // ------------------------------------------------------------------ 下载

    /** 网页里指向文件的链接（日志、导出之类）交给系统 DownloadManager。 */
    private class PixikoDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimeType, long contentLength) {
            try {
                String fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                request.addRequestHeader("User-Agent", userAgent);
                request.setTitle(fileName);
                request.setDescription(UrlHelper.hostOf(current.base));
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager == null) throw new IllegalStateException("没有 DownloadManager");
                manager.enqueue(request);
                ToastBus.shortToast(MainActivity.this, "已交给系统下载：" + fileName);
            } catch (Exception error) {
                Log.w("下载失败", error);
                ToastBus.shortToast(MainActivity.this, "下载失败：" + Log.describe(error));
            }
        }
    }

    // ------------------------------------------------------------------ 菜单

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem keep = menu.findItem(R.id.action_keep_screen_on);
        if (keep != null) keep.setChecked(keepScreenOnEnabled());
        // 「切换界面」的文案随当前状态变：现在在 /m 就显示「切换到完整控制台（/）」，反之亦然。
        MenuItem toggle = menu.findItem(R.id.action_toggle_ui);
        if (toggle != null) toggle.setTitle(mobileUiSelected() ? TITLE_SWITCH_TO_CONSOLE : TITLE_SWITCH_TO_MOBILE);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_refresh) {
            hideErrorPage();
            webView.reload();
            return true;
        }
        if (id == R.id.action_home) {
            loadHome();
            return true;
        }
        if (id == R.id.action_toggle_ui) {
            toggleUi();
            return true;
        }
        if (id == R.id.action_switch) {
            showSwitchServerDialog();
            return true;
        }
        if (id == R.id.action_settings) {
            openServerSettings();
            return true;
        }
        if (id == R.id.action_browser) {
            openInBrowser();
            return true;
        }
        if (id == R.id.action_keep_screen_on) {
            boolean next = !keepScreenOnEnabled();
            setKeepScreenOn(next);
            item.setChecked(next);
            ToastBus.shortToast(this, getString(next ? R.string.toast_keep_screen_on : R.string.toast_keep_screen_off));
            return true;
        }
        if (id == R.id.action_clear_cache) {
            clearWebCache();
            return true;
        }
        if (id == R.id.action_clear_login) {
            clearLoginState();
            return true;
        }
        if (id == R.id.action_about) {
            showAbout();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** 切换服务器：单选列表，选中即换基地址并加载首页。 */
    private void showSwitchServerDialog() {
        final java.util.List<ServerConfig> servers = repository.list();
        if (servers.size() <= 1) {
            ToastBus.shortToast(this, servers.isEmpty() ? getString(R.string.no_server) : "只配了一台服务器，去「服务器设置」里可以再加");
            if (servers.isEmpty()) openServerSettings();
            return;
        }
        final String[] labels = new String[servers.size()];
        int checked = 0;
        for (int i = 0; i < servers.size(); i++) {
            ServerConfig config = servers.get(i);
            labels[i] = config.displayName() + "\n" + UrlHelper.hostOf(config.base);
            if (config.id.equals(current.id)) checked = i;
        }
        new AlertDialog.Builder(this, R.style.Theme_Pixiko_Dialog)
                .setTitle(R.string.menu_switch_server)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    ServerConfig picked = servers.get(which);
                    dialog.dismiss();
                    if (picked.id.equals(current.id)) return;
                    current = picked;
                    repository.setLastId(picked.id);
                    // 换服务器 → 令牌重新注入，用户之前手动清除登录的意图不再延续。
                    tokenInjected = false;
                    suppressTokenInjection = false;
                    bridgeAttached = false;
                    webView.clearHistory();
                    loadHome();
                    if (getSupportActionBar() != null) getSupportActionBar().setSubtitle(UrlHelper.hostOf(current.base));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------------ 给网页桥用的入口
    //
    // /m 下原生 ActionBar 是隐藏的（见 applyActionBarVisibility），菜单点不到，所以下面这几个
    // app 级动作必须能从网页里调回来。实现就是菜单里那几段逻辑本身（包级可见，不另写一份），
    // 由 PixikoBridge 的 @JavascriptInterface 方法在主线程调用。
    //   网页侧调用名       ← 这里的方法
    //   openServerSettings → openServerSettings()
    //   clearWebCache      → clearWebCache()
    //   clearLoginState    → clearLoginState()
    //   openInBrowser      → openInBrowser()

    /**
     * 打开服务器设置页。用 {@code startActivityForResult}（而不是 {@code startActivity}）是为了让
     * {@link #onActivityResult} 里「换了服务器 / 令牌 → 重新加载首页」那套既有逻辑继续生效，
     * 和菜单里「服务器设置」走的是同一条路。
     */
    void openServerSettings() {
        startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS);
    }

    /**
     * 清空网页缓存：WebView 的 HTTP 缓存 + 各种 DOM 存储。刻意<b>不动 localStorage 里的令牌</b>
     * （那是「清除登录状态」那一项的事），两个动作分开才对用户可预期。
     *
     * <p>包级可见：{@link PixikoBridge#clearWebCache()} 从网页里调回来。
     */
    void clearWebCache() {
        webView.clearCache(true);
        webView.clearFormData();
        WebStorage.getInstance().deleteAllData();
        ToastBus.shortToast(this, getString(R.string.toast_cache_cleared));
    }

    /**
     * 清除登录状态：删掉 localStorage 的令牌，然后 reload，网页会自己回到锁屏。
     *
     * <p>包级可见：{@link PixikoBridge#clearLoginState()} 从网页里调回来（桥保证在主线程执行，
     * {@code evaluateJavascript} 必须在 UI 线程）。
     */
    void clearLoginState() {
        webView.evaluateJavascript(NativeHook.clearTokenScript(), value -> {
            Log.d("已清除网页登录状态，锁屏本轮不再自动注入令牌");
            // 这一轮别再自动注入：用户点「清除登录状态」就是想在网页里重新登录一次。
            suppressTokenInjection = true;
            tokenInjected = false;
            webView.reload();
            ToastBus.shortToast(this, getString(R.string.toast_login_cleared));
        });
    }

    private void showAbout() {
        String message = getString(R.string.about_message) + "\n\n版本 " + versionName();
        new AlertDialog.Builder(this, R.style.Theme_Pixiko_Dialog)
                .setTitle(R.string.about_title)
                .setMessage(message)
                .setPositiveButton(R.string.about_ok, null)
                .show();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ 常亮

    private boolean keepScreenOnEnabled() {
        return getSharedPreferences(PREF_UI, MODE_PRIVATE).getBoolean(KEY_KEEP_SCREEN_ON, false);
    }

    private void setKeepScreenOn(boolean enabled) {
        getSharedPreferences(PREF_UI, MODE_PRIVATE).edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply();
        applyKeepScreenOnState();
    }

    private void applyKeepScreenOnState() {
        if (keepScreenOnEnabled()) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ------------------------------------------------------------------ 返回键 / 生命周期

    private long lastBackAt = 0L;

    /**
     * 返回键三级：WebView 能回退就回退；不能回退且不在首页 → 回首页；已经在首页 → 双击退出。
     */
    private void handleBack() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        String home = homeUrl();
        String here = currentUrl == null ? "" : UrlHelper.stripQuery(currentUrl);
        if (!here.equals(UrlHelper.stripQuery(home)) && !here.isEmpty()) {
            loadHome();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackAt < 2000) {
            finish();
        } else {
            lastBackAt = now;
            ToastBus.shortToast(this, getString(R.string.toast_press_again));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // manifest 里已经用 configChanges 挡掉了旋转重建，这里是双保险：
        // 真发生了重建（比如系统回收后恢复）也能回到刚才那一页。
        if (currentUrl != null) outState.putString("webview_state_url", currentUrl);
    }

    /**
     * WebView 生命周期：<b>必须成对</b>（{@code onPause} → {@code webView.onPause()}、
     * {@code onResume} → {@code webView.onResume()}，见下面两个方法）。这里记两条判断依据：
     *
     * <p><b>① 刻意不调 {@code pauseTimers()}。</b> {@code WebView.onPause()} 按官方文档只暂停
     * "可以安全暂停的处理"（动画、定位等），**不暂停 JavaScript**；{@code pauseTimers()} 才是
     * "把这台设备上所有 WebView 的 JS 定时器一起停掉"。而手机端收图靠的正是网页里的轮询
     * （{@code webui/m/app.js} 的 {@code pollWhileVisible} 在跟 {@code /api/capture} 与回执）——
     * 停掉它等于把"挂起期间尽量把图收下来"这条路直接掐死，与用户的要求相反。
     *
     * <p><b>② 系统层面的冻结是边界，不是这里的 bug。</b> Android 12+ 会把缓存进程整体冻住
     * （app freezer）、Doze 也会掐网络：那时网页里的定时器与 {@code fetch} 什么都不会发生，
     * 这不是网页或外壳能绕开的（要真在后台持续收图，得有服务端推送或前台服务，不在本轮范围）。
     * 这里能做、也必须做到的是：**回来这一下立刻让网页接着跑** —— 见 {@link #onResume()} 的
     * {@link NativeHook#resumeScript()}，以及网页侧的补拉链 {@code catchUpReceipts}。
     *
     * <p>另外这里**不清任何会话状态**（没有 onStop 清理、也没有"回前台就 reload"）：
     * "正在跟哪条回执"的内存现场丢了也没关系，网页侧已经落盘（{@code FOLLOW_KEY}）
     * 并在重建后从服务端补回来。
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView == null) return;
        webView.onResume();
        wakeWebPage();
    }

    /**
     * 回到前台：主动叫网页立刻补一拍（补拉链见 {@code webui/m/app.js} 的 {@code catchUpReceipts}）。
     *
     * <p>为什么不只靠网页自己的看门狗：网页的兜底是每 1.5 秒自查一次"定时器还在不在"，
     * 最坏要等 1.5 秒；而安卓壳可能根本不派发 {@code visibilitychange}。
     * 外壳在 onResume 里补这一句，网页的唤醒通道（{@code focus} / {@code resume}）当帧就触发补拉。
     *
     * <p>只对我们自己那台服务器的页面说（{@link #bridgeAttached}）：用户点开外链后不必去打扰人家。
     */
    private void wakeWebPage() {
        if (!bridgeAttached) return;
        webView.evaluateJavascript(NativeHook.resumeScript(),
                value -> Log.d("回前台唤醒网页：" + value));
    }

    @Override
    protected void onDestroy() {
        releaseUpdateWiring();
        if (bridge != null) bridge.shutdown();
        if (webView != null) {
            webView.removeJavascriptInterface(NativeHook.INTERFACE_NAME);
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SETTINGS) return;
        // 从设置页回来：可能换了服务器、或者第一次才配上。
        ServerConfig fresh = new ServerRepository(this).current();
        if (fresh == null) return;
        current = fresh;
        repository.setLastId(fresh.id);
        String home = homeUrl();
        if (!home.equals(currentUrl)) {
            tokenInjected = false;
            suppressTokenInjection = false;
            bridgeAttached = false;
            webView.clearHistory();
            loadHome();
        }
        if (getSupportActionBar() != null) getSupportActionBar().setSubtitle(UrlHelper.hostOf(current.base));
    }

    /**
     * Android 9 及以下：保存图片要写公共 Pictures 目录，需要 WRITE_EXTERNAL_STORAGE。
     * 提前要一次（而不是等用户长按图片时才要），这样保存动作是「立即成功」而不是「先弹权限框」。
     */
    private void requestLegacyStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return;
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2001);
    }
}
