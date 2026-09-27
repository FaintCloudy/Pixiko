import { PromptPanel } from "@/components/panels/PromptPanel";
import { PanelPage } from "@/components/shell/PanelPage";

/** 提示词栏（`/prompt`）。 */
export default function PromptPage() {
  return (
    <PanelPage id="prompt">
      <PromptPanel />
    </PanelPage>
  );
}
