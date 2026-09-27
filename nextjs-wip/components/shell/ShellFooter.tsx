/**
 * 页脚：版权与 powered by。
 *
 * 与 `webui/index.html` 的 `<footer class="footer">` 唯一差异：**去掉了 `· Java 17`** ——
 * Next.js 版跑在 Node 上，再写 Java 17 就是错的（迁移期必改的文案之一，其余逐字保留）。
 */
export function ShellFooter() {
  return (
    <footer className="footer">
      <span>Copyright © 2026 <b>loriko</b> · Pixiko（神户小鸟）· 保留所有权利</span>
      <span className="muted">
        作者 <b>loriko</b> · Powered by <b>DeepSeek</b> · <b>Stable Diffusion WebUI</b> · <b>NapCat</b>
      </span>
    </footer>
  );
}
