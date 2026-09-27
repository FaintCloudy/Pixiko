import { StylesPanel } from "@/components/panels/StylesPanel";
import { PanelPage } from "@/components/shell/PanelPage";

/** 样式栏（`/styles`）。 */
export default function StylesPage() {
  return (
    <PanelPage id="styles">
      <StylesPanel />
    </PanelPage>
  );
}
