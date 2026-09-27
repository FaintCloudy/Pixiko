import { LorasPanel } from "@/components/panels/LorasPanel";
import { PanelPage } from "@/components/shell/PanelPage";

/** LoRA 栏（`/loras`）。 */
export default function LorasPage() {
  return (
    <PanelPage id="loras">
      <LorasPanel />
    </PanelPage>
  );
}
