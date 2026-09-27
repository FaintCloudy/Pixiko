import { HelpPanel } from "@/components/panels/HelpPanel";
import { PanelPage } from "@/components/shell/PanelPage";

/** 帮助栏（`/help`）。 */
export default function HelpPage() {
  return (
    <PanelPage id="help">
      <HelpPanel />
    </PanelPage>
  );
}
