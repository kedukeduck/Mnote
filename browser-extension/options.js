import { DEFAULT_SETTINGS, normalizeServerUrl, readSettings, sendMessage } from "./common.js";

const form = document.querySelector("#settingsForm");
const serverUrl = document.querySelector("#serverUrl");
const writeToken = document.querySelector("#writeToken");
const readToken = document.querySelector("#readToken");
const aiAccess = document.querySelector("#aiAccess");
const status = document.querySelector("#status");

function show(message, error = false) {
  status.textContent = message;
  status.classList.toggle("error", error);
}

function permissionPattern(value) {
  const parsed = new URL(value);
  return `${parsed.origin}/*`;
}

async function initialize() {
  const settings = await readSettings();
  serverUrl.value = settings.serverUrl || DEFAULT_SETTINGS.serverUrl;
  writeToken.value = settings.writeToken;
  readToken.value = settings.readToken;
  aiAccess.value = settings.aiAccess;
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  show("正在保存……");
  try {
    const normalized = normalizeServerUrl(serverUrl.value);
    const pattern = permissionPattern(normalized);
    const loopback = /^http:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/i.test(new URL(normalized).origin);
    if (!loopback) {
      const granted = await chrome.permissions.request({ origins: [pattern] });
      if (!granted) throw new Error("未授予此服务地址的访问权限");
    }
    await chrome.storage.local.set({
      serverUrl: normalized,
      writeToken: writeToken.value.trim(),
      readToken: readToken.value.trim(),
      aiAccess: aiAccess.value
    });
    const response = await fetch(`${normalized}/health`, { cache: "no-store", redirect: "error" });
    if (!response.ok) throw new Error(`服务健康检查失败（HTTP ${response.status}）`);
    show("设置已保存，服务连接正常");
  } catch (error) {
    show(`设置可能已保存，但连接检查未通过：${error.message}`, true);
  }
});

document.querySelector("#syncButton").addEventListener("click", async () => {
  show("正在同步……");
  try {
    const response = await sendMessage({ type: "syncOutbox" });
    show(`已发送 ${response.sent} 条，仍有 ${response.pending} 条待处理`);
  } catch (error) {
    show(error.message, true);
  }
});

initialize().catch((error) => show(error.message, true));
