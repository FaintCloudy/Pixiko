package cn.szu.bot.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/**
 * 修正了 {@code canChildScrollUp()} 的下拉刷新容器（{@code activity_main.xml} 里 {@code @+id/swipe} 的类）。
 *
 * <p><b>为什么要这个子类（这是用户报的「上滑刷新 bug」的原生那一半）</b>：
 * {@link SwipeRefreshLayout} 判断"子视图还能不能往上滚"用的是
 * {@code getChildAt(0).canScrollVertically(-1)}。而在 {@code activity_main.xml} 里它的
 * <b>直接子视图</b>是为了往上面叠一层原生错误页才加的 {@code FrameLayout} —— 那个 FrameLayout
 * 自己不滚动，真正滚动的是网页内部的滚动容器。于是父类<b>永远</b>认为"已经到顶"，
 * 列表滚在中间时手指往上滑也被当成下拉，一松手就 {@code reload()}。
 *
 * <p>这里改成问真正会滚的那个 {@link WebView}（{@code getScrollY() > 0} 或
 * {@code canScrollVertically(-1)}）。
 *
 * <p><b>注意这一层只是兜底，不是主修</b>：手机界面 {@code /m} 的滚动是网页自己
 * {@code overflow:auto} 的容器（{@code #m-main}），WebView 本身常常不滚，
 * 所以这里也可能答"到顶了"。{@code /m} 下真正生效的是 {@code MainActivity} 里的
 * {@code applySwipeAvailability(true)} → {@code swipe.setEnabled(false)}
 * （{@code /m} 自带一套判据更准的网页版下拉刷新，见 {@code webui/m/app.js} 的 {@code PixikoM.ptrInstall}）。
 * <b>两处都要在</b>，别只留一处。
 */
public class PixikoSwipeRefreshLayout extends SwipeRefreshLayout {

    public PixikoSwipeRefreshLayout(@NonNull Context context) {
        super(context);
    }

    public PixikoSwipeRefreshLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * 父类问的是不滚动的 FrameLayout，所以永远回 false（＝"在顶部，可以下拉"）。
     * 这里换成问子树里真正的 {@link WebView}；找不到就老实回退父类行为。
     */
    @Override
    public boolean canChildScrollUp() {
        WebView webView = findWebView(this);
        if (webView != null) {
            if (webView.getScrollY() > 0) return true;
            return webView.canScrollVertically(-1);
        }
        return super.canChildScrollUp();
    }

    /** 在子树里找第一个 WebView（层级由 activity_main.xml 决定：FrameLayout ▸ WebView + 错误页）。 */
    private static WebView findWebView(View root) {
        if (root instanceof WebView) return (WebView) root;
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            WebView found = findWebView(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
}
