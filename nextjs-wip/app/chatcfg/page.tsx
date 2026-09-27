import { ChatCfgPanel } from "@/components/panels/ChatCfgPanel";
import { PanelPage } from "@/components/shell/PanelPage";

/** 聊天配置栏（`/chatcfg`）。 */
export default function ChatCfgPage() {
  return (
    <PanelPage id="chatcfg">
      <ChatCfgPanel />
    </PanelPage>
  );
}
