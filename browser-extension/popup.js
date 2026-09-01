import { sendMessage } from "./common.js";

const status = document.querySelector("#status");
const badge = document.querySelector("#connectionBadge");

function show(message, error = false) {
  status.textContent = message;
  status.classList.toggle("error", error);
}

async function activeTabId() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  return tab?.id;
}

async function runCapture(type) {
  show("正在准备……");
  try {
    await sendMessage({ type, tabId: await activeTabId() });
    window.close();
  } catch (error) {
    show(error.message, true);
  }
}

async function quickIdea() {
  const key = `draft:${crypto.randomUUID()}`;
  await chrome.storage.session.set({
    [key]: {
      mode: "quick",
      createdAt: new Date().toISOString(),
      tab: { title: "即时想法", url: "" },
      selection: { exact: "" }
    }
  });
  await chrome.tabs.create({ url: chrome.runtime.getURL(`editor.html?draft=${encodeURIComponent(key)}`) });
  window.close();
}

document.querySelector("#screenshotButton").addEventListener("click", () => runCapture("captureScreenshot"));
document.querySelector("#selectionButton").addEventListener("click", () => runCapture("captureSelection"));
document.querySelector("#ideaButton").addEventListener("click", () => quickIdea().catch((error) => show(error.message, true)));
document.querySelector("#inboxButton").addEventListener("click", () => chrome.tabs.create({ url: chrome.runtime.getURL("inbox.html") }));
document.querySelector("#optionsButton").addEventListener("click", () => chrome.runtime.openOptionsPage());

sendMessage({ type: "status" }).then((response) => {
  badge.textContent = response.configured ? "已配置" : "仅本地";
  show(response.pending ? `${response.pending} 条记录等待同步` : response.serverUrl);
}).catch((error) => {
  badge.textContent = "不可用";
  show(error.message, true);
});
