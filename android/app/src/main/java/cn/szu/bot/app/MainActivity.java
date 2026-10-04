package cn.szu.bot.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/**
 * 主界面：把网页控制台整站装进 WebView。
 *
 * <p><b>设计立场</b>：网页端有的功能（出图、对话、提示词、风格、LoRA、功能、聊天设置、系统、
 * 首次配置、日志、回执、帮助——共 13 个栏目）一律<b>不重新实现</b>，WebView 原样加载
 * {@code /}（出图页，右栏常驻对话）即可；网页本身就是响应式的，≤1000px 会自己变成单列堆叠，
 * 390×844 的手机宽度已经被真机浏览器验收过（无横向滚动）。原生只补网页做不到的部分：
 * 服务器配置、局域网扫描、图片保存/分享、下拉刷新、原生错误页、屏幕常亮、加载进度。
 *
 * <p>几个关键实现点（细节见各方法注释）：
 * <ul>
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

    private ServerRepository repository;
    private ServerConfig current;

    private Toolbar toolbar;
    private ProgressBar progress;
    private SwipeRefreshLayout swipe;
    private WebView webView;
    private View errorPage;
    private TextView errorDetail;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        repository = new ServerRepository(this);
        current = repository.current();
        if (current == null) {
            // 一台都没配：直接去设置页，别给用户看一个必然失败的错误页。
            ToastBus.shortToast(this, getString(R.string.no_server));
            startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS);
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
        progress = findViewById(R.id.progress);
        swipe = findViewById(R.id.swipe);
        webView = findViewById(R.id.webview);
        errorPage = findViewById(R.id.error_page);
        errorDetail = findViewById(R.id.error_detail);

        bindErrorPage();
        configureWebView();
        configureSwipe();
        applyKeepScreenOnState();
        requestLegacyStoragePermissionIfNeeded();

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
        findViewById(R.id.error_settings).setOnClickListener(view ->
                startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS));
        findViewById(R.id.error_browser).setOnClickListener(view -> openInBrowser());
    }

    // ------------------------------------------------------------------ 加载与状态

    /** 加载首页（＝出图页，右栏常驻对话；/chat 与它同构，所以首页就是「全部功能」的入口）。 */
    private void loadHome() {
        loadUrl(UrlHelper.join(current.base, "/"));
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

    private void openInBrowser() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl != null && !currentUrl.isEmpty()
                    ? currentUrl : UrlHelper.join(current.base, "/")));
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
            // 有些 ROM 的 WebView 在 onPageStarted 时 localStorage 还没准备好，这里补一次注入。
            if (!tokenInjected) maybeInjectToken();
            Log.d("页面加载完成：" + UrlHelper.stripQuery(url));
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
        if (id == R.id.action_switch) {
            showSwitchServerDialog();
            return true;
        }
        if (id == R.id.action_settings) {
            startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS);
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
            if (servers.isEmpty()) startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS);
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

    /**
     * 清空网页缓存：WebView 的 HTTP 缓存 + 各种 DOM 存储。刻意<b>不动 localStorage 里的令牌</b>
     * （那是「清除登录状态」那一项的事），两个动作分开才对用户可预期。
     */
    private void clearWebCache() {
        webView.clearCache(true);
        webView.clearFormData();
        WebStorage.getInstance().deleteAllData();
        ToastBus.shortToast(this, getString(R.string.toast_cache_cleared));
    }

    /** 清除登录状态：删掉 localStorage 的令牌，然后 reload，网页会自己回到锁屏。 */
    private void clearLoginState() {
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
        String home = UrlHelper.join(current.base, "/");
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

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
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
        String home = UrlHelper.join(current.base, "/");
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
