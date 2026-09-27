import { SystemPanel } from "@/components/panels/SystemPanel";
import { PanelPage } from "@/components/shell/PanelPage";

/** 系统栏（`/system`）。 */
export default function SystemPage() {
  return (
    <PanelPage id="system">
      <SystemPanel />
    </PanelPage>
  );
}
