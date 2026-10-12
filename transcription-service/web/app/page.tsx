import { requireChatGPTUser } from "./chatgpt-auth";
import TranscriptWorkspace from "./transcript-workspace";

export default async function Home() {
  await requireChatGPTUser("/");
  return <TranscriptWorkspace />;
}
