package cn.szu.bot.civitai;

import java.io.InputStream;

/**
 * 一次 LoRA 下载的暂停/取消开关。
 *
 * <p>下载线程在流式拷贝的每一块（64 KB）之间查一次：取消 → 抛 {@link CancelledException}
 * （调用方据此回"已取消"，而不是"下载失败"）；暂停 → 阻塞在 {@link #awaitResume()}，用
 * {@code wait/notify} 等待，不忙等、不烧 CPU。
 *
 * <p>取消还会顺手关掉当前正在读的那个网络流：不然线程卡在一个读不动的 socket 上，得等到
 * socket 超时才会醒，"马上停止"就成了空话。
 *
 * <p>每次下载都 new 一个新对象，任务结束即作废（见 {@code Bot.startLoraJob} 的生命周期），
 * 所以上一次下载的取消标记绝不会影响下一次下载。
 */
public final class DownloadControl {
    /** 下载被取消：与网络/校验失败区分开，调用方据此回"已取消"。 */
    public static final class CancelledException extends Exception {
        private static final long serialVersionUID = 1L;
        public CancelledException(String message) { super(message); }
    }
    private final Object lock = new Object();
    private volatile boolean cancelled, paused, finished;
    /** 暂停累计时长（含正在进行的这次）：暂停不该算进下载总超时。 */
    private long pausedNanos;
    /** 本次暂停的开始时刻（0 = 当前没在暂停）。 */
    private long pauseStarted;
    /** 当前正在读的网络流：取消时关掉它，让卡住的 read 立刻退出。 */
    private volatile InputStream stream;

    /** 取消：唤醒暂停中的线程、关掉正在读的流；重复调用安全。 */
    public void cancel() {
        InputStream current;
        synchronized (lock) {
            cancelled = true;
            stopPause();
            lock.notifyAll();
            current = stream;
        }
        close(current);
    }
    /** 暂停（已取消或已结束的下载不再暂停）。 */
    public void pause() {
        synchronized (lock) {
            if (cancelled || finished || paused) return;
            paused = true;
            pauseStarted = System.nanoTime();
        }
    }
    /** 继续；没在暂停时也安全（幂等）。 */
    public void resume() {
        synchronized (lock) { stopPause(); lock.notifyAll(); }
    }
    public boolean isCancelled() { return cancelled; }
    /** 取消过的下载一律不再算"暂停中"（取消要能立刻穿出暂停）。 */
    public boolean isPaused() { return paused && !cancelled; }
    /** 传输阶段是否已经结束（之后是"加载模型/抓展示图"，取消不了下载了）。 */
    public boolean isFinished() { return finished; }
    /** 传输阶段收尾（成功、失败、取消都算）：此后 {@link #isFinished()} 为真，暂停也一并结束。 */
    public void markFinished() {
        synchronized (lock) { finished = true; stopPause(); lock.notifyAll(); }
    }
    /**
     * 暂停时阻塞，被 {@link #resume()}、{@link #cancel()} 或 {@link #markFinished()} 唤醒。
     * 返回后调用方还得自己查一次 {@link #isCancelled()}：取消也会唤醒。
     */
    public void awaitResume() throws InterruptedException {
        synchronized (lock) { while (paused && !cancelled) lock.wait(); }
    }
    /** 暂停累计时长（纳秒，含正在进行的这次暂停）：下载总超时据此顺延。 */
    public long pausedNanos() {
        synchronized (lock) { return pauseStarted == 0 ? pausedNanos : pausedNanos + (System.nanoTime() - pauseStarted); }
    }
    /** 登记当前正在读的网络流；传输结束（finally）传 null。只有传输线程会调用。 */
    public void attach(InputStream source) { stream = source; }
    /** 取消已生效就立刻抛：供拷贝循环之外的地方（发布文件前）复用同一套判断。 */
    public void checkCancelled() throws CancelledException {
        if (cancelled) throw new CancelledException("LoRA 下载已取消。");
    }
    /** 只在持锁时调用：结束当前这次暂停并把它计入累计时长。 */
    private void stopPause() {
        if (pauseStarted != 0) { pausedNanos += System.nanoTime() - pauseStarted; pauseStarted = 0; }
        paused = false;
    }
    private static void close(InputStream source) {
        if (source == null) return;
        try { source.close(); } catch (Exception ignored) { /* 取消路径：关不掉也不影响判断 */ }
    }
}
