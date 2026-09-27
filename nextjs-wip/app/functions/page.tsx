import { FunctionsPanel } from "@/components/panels/FunctionsPanel";
import { PanelPage } from "@/components/shell/PanelPage";

/** 提示词集栏（`/functions`）。 */
export default function FunctionsPage() {
  return (
    <PanelPage id="functions">
      <FunctionsPanel />
    </PanelPage>
  );
}
