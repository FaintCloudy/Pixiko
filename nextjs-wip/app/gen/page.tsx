import { GenPanel } from "@/components/panels/GenPanel";
import { PanelPage } from "@/components/shell/PanelPage";

/** 出图栏（`/gen`）。 */
export default function GenPage() {
  return (
    <PanelPage id="gen">
      <GenPanel />
    </PanelPage>
  );
}
