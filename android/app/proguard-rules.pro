# 本工程 release 未启用混淆（minifyEnabled false），这里只留兜底规则。
# 万一之后打开混淆：WebView 的 @JavascriptInterface 方法名会被 JS 反射调用，
# 被混淆掉就会出现「PixikoNative is not defined / 方法不存在」。
-keepclassmembers class cn.szu.bot.app.PixikoBridge {
    public *;
}
-keepattributes JavascriptInterface
