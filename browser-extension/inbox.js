import { formatTime, readSettings, sendMessage } from "./common.js";

const list = document.querySelector("#recordList");
const status = document.querySelector("#status");
const query = document.querySelector("#query");

function show(message, error = false) {
  status.textContent = message;
  status.classList.toggle("error", error);
}

function text(tag, value, className = "") {
  const element = document.createElement(tag);
  element.textContent = value || "";
  if (className) element.className = className;
  return element;
}

function render(records) {
  list.replaceChildren();
  if (!records.length) {
    list.append(text("div", "还没有记录", "card muted"));
    return;
  }
  for (const record of records) {
    const card = document.createElement("article");
    card.className = "card record";
    const source = record.source || {};
    const title = record.comment || source.text || source.title || "无文字记录";
    card.append(text("h2", title));
    card.append(text("p", source.text && source.text !== title ? source.text.slice(0, 600) : "", "muted"));
    const meta = `${record.kind || "capture"} · ${formatTime(record.created_at)} · ${source.app_name || source.type || "未知来源"}`;
    card.append(text("span", meta, "muted"));
    if (record.sync_state) card.append(text("span", record.sync_state === "synced" ? "已同步" : `待同步：${record.sync_detail || ""}`, "badge"));
    if (source.url) {
      const link = document.createElement("a");
      link.href = source.url;
      link.target = "_blank";
      link.rel = "noreferrer";
      link.textContent = source.url;
      card.append(link);
    }
    list.append(card);
  }
}

async function requestRecords(search = "") {
  const settings = await readSettings();
  if (!settings.readToken) {
    const stored = await chrome.storage.local.get({ mnoteRecent: [] });
    const records = stored.mnoteRecent || [];
    render(search ? records.filter((item) => JSON.stringify(item).toLowerCase().includes(search.toLowerCase())) : records);
    show("当前显示浏览器本地记录；配置同步后可搜索完整知识库");
    return;
  }
  const endpoint = search
    ? `/v1/search?q=${encodeURIComponent(search)}&limit=100`
    : "/v1/captures?limit=100";
  const response = await fetch(settings.serverUrl + endpoint, {
    headers: { Authorization: `Bearer ${settings.readToken}` },
    cache: "no-store",
    redirect: "error"
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.detail || `读取失败（HTTP ${response.status}）`);
  render(body.records || []);
  show(`已显示 ${(body.records || []).length} 条记录`);
}

document.querySelector("#searchForm").addEventListener("submit", (event) => {
  event.preventDefault();
  requestRecords(query.value.trim()).catch((error) => show(error.message, true));
});
document.querySelector("#recentButton").addEventListener("click", () => {
  query.value = "";
  requestRecords().catch((error) => show(error.message, true));
});
document.querySelector("#syncButton").addEventListener("click", async () => {
  try {
    const result = await sendMessage({ type: "syncOutbox" });
    show(`已发送 ${result.sent} 条，仍有 ${result.pending} 条待处理`);
    await requestRecords(query.value.trim());
  } catch (error) {
    show(error.message, true);
  }
});
document.querySelector("#exportButton").addEventListener("click", async () => {
  show("正在生成导出包……");
  try {
    const settings = await readSettings();
    if (!settings.readToken) throw new Error("请先配置第一方只读 Token");
    const response = await fetch(`${settings.serverUrl}/v1/export`, {
      headers: { Authorization: `Bearer ${settings.readToken}` },
      cache: "no-store",
      redirect: "error"
    });
    if (!response.ok) throw new Error(`导出失败（HTTP ${response.status}）`);
    const url = URL.createObjectURL(await response.blob());
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `mnote-export-${new Date().toISOString().slice(0, 10)}.zip`;
    anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 30_000);
    show("导出完成");
  } catch (error) {
    show(error.message, true);
  }
});

requestRecords().catch(async (error) => {
  show(`服务不可用，改为显示本地记录：${error.message}`, true);
  const stored = await chrome.storage.local.get({ mnoteRecent: [] });
  render(stored.mnoteRecent || []);
});
