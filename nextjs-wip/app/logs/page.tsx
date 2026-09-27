import { LogsPanel } from "@/components/panels/LogsPanel";
import { PanelPage } from "@/components/shell/PanelPage";

/** 控制台栏（`/logs`）：真 CLI，默认全屏（全屏切换属 Phase 5 后续）。 */
export default function LogsPage() {
  return (
    <PanelPage id="logs">
      <LogsPanel />
    </PanelPage>
  );
}
