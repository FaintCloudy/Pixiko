import { ChatPanel } from "@/components/panels/ChatPanel";
import { PanelPage } from "@/components/shell/PanelPage";

/** 对话栏（`/`）——与 Java 版 `WebPageController.PAGES` 的 chat 路径一致。 */
export default function ChatPage() {
  return (
    <PanelPage id="chat">
      <ChatPanel />
    </PanelPage>
  );
}
