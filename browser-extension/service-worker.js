import { compactRecord, dataUrlAsset, readSettings } from "./common.js";

const MENU_SELECTION = "mnote-selection";
const MENU_SCREENSHOT = "mnote-screenshot";
const OUTBOX_KEY = "mnoteOutbox";
const RECENT_KEY = "mnoteRecent";
const MAX_RECENT = 200;
let flushPromise = null;

async function installMenus() {
  await chrome.contextMenus.removeAll();
  chrome.contextMenus.create({
    id: MENU_SELECTION,
    title: "记录选中文字到 Mnote",
    contexts: ["selection"]
  });
  chrome.contextMenus.create({
    id: MENU_SCREENSHOT,
    title: "截图、划线或圈选后记录",
    contexts: ["page", "selection", "image", "video"]
  });
  await chrome.alarms.create("mnote-sync", { periodInMinutes: 1 });
}

chrome.runtime.onInstalled.addListener(() => void installMenus());
chrome.runtime.onStartup.addListener(() => void chrome.alarms.create("mnote-sync", { periodInMinutes: 1 }));

async function selectionSnapshot() {
  const selection = window.getSelection();
  if (!selection || selection.rangeCount === 0 || selection.isCollapsed) return null;
  const range = selection.getRangeAt(0);
  const selectedText = selection.toString().trim();
  if (!selectedText) return null;
  const selectionTruncated = selectedText.length > 200_000;
  const exact = selectedText.slice(0, 200_000);

  function pathFor(node) {
    let element = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
    const path = [];
    while (element && element !== document.documentElement) {
      let segment = element.localName || "node";
      if (element.id) {
        segment += `#${CSS.escape(element.id)}`;
        path.unshift(segment);
        break;
      }
      const parent = element.parentElement;
      if (parent) {
        const siblings = [...parent.children].filter((item) => item.localName === element.localName);
        if (siblings.length > 1) segment += `:nth-of-type(${siblings.indexOf(element) + 1})`;
      }
      path.unshift(segment);
      element = parent;
    }
    return path.join(" > ");
  }

  const bodyText = document.body?.innerText || "";
  const index = bodyText.indexOf(exact);
  let occurrences = 0;
  let cursor = 0;
  while (occurrences < 2 && (cursor = bodyText.indexOf(exact, cursor)) >= 0) {
    occurrences += 1;
    cursor += Math.max(1, exact.length);
  }
  const pageBytes = new TextEncoder().encode(bodyText.slice(0, 4_000_000));
  const pageDigest = [...new Uint8Array(await crypto.subtle.digest("SHA-256", pageBytes))]
    .map((value) => value.toString(16).padStart(2, "0"))
    .join("");
  const prefix = index >= 0 ? bodyText.slice(Math.max(0, index - 64), index) : "";
  const suffix = index >= 0 ? bodyText.slice(index + exact.length, index + exact.length + 64) : "";
  const rects = [...range.getClientRects()].slice(0, 100).map((rect) => ({
    x: Math.round(rect.left + scrollX),
    y: Math.round(rect.top + scrollY),
    width: Math.round(rect.width),
    height: Math.round(rect.height)
  }));
  return {
    exact,
    prefix,
    suffix,
    start_path: pathFor(range.startContainer),
    start_offset: range.startOffset,
    end_path: pathFor(range.endContainer),
    end_offset: range.endOffset,
    rects,
    anchor_unique: occurrences === 1 && !selectionTruncated,
    selection_truncated: selectionTruncated,
    page_text_sha256: pageDigest,
    page_text_truncated: bodyText.length > 4_000_000,
    page_title: document.title,
    url: location.href
  };
}

async function activeTab(tabId) {
  if (tabId) return chrome.tabs.get(tabId);
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab) throw new Error("找不到当前标签页");
  return tab;
}

async function readSelection(tab) {
  if (!tab.id || !/^https?:|^file:/.test(tab.url || "")) {
    throw new Error("此浏览器内部页面不允许读取选中文字");
  }
  const [{ result }] = await chrome.scripting.executeScript({
    target: { tabId: tab.id },
    func: selectionSnapshot
  });
  if (!result?.exact) throw new Error("请先在网页中选中文字");
  return result;
}

async function putDraft(draft) {
  const key = `draft:${crypto.randomUUID()}`;
  await chrome.storage.session.set({ [key]: draft });
  await chrome.tabs.create({ url: chrome.runtime.getURL(`editor.html?draft=${encodeURIComponent(key)}`) });
}

async function openSelection(tabId, fallbackText = "") {
  const tab = await activeTab(tabId);
  let selection;
  try {
    selection = await readSelection(tab);
  } catch (error) {
    if (!fallbackText) throw error;
    selection = {
      exact: fallbackText,
      prefix: "",
      suffix: "",
      start_path: "",
      start_offset: 0,
      end_path: "",
      end_offset: 0,
      rects: [],
      page_title: tab.title || "",
      url: tab.url || ""
    };
  }
  await putDraft({
    mode: "selection",
    createdAt: new Date().toISOString(),
    tab: { title: tab.title || selection.page_title || "", url: tab.url || selection.url || "" },
    selection
  });
}

async function openScreenshot(tabId) {
  const tab = await activeTab(tabId);
  const image = await chrome.tabs.captureVisibleTab(tab.windowId, { format: "png" });
  await putDraft({
    mode: "screenshot",
    createdAt: new Date().toISOString(),
    tab: { title: tab.title || "", url: tab.url || "" },
    image
  });
}

async function storeRecent(record, state, detail = "") {
  const stored = await chrome.storage.local.get({ [RECENT_KEY]: [] });
  const recent = Array.isArray(stored[RECENT_KEY]) ? stored[RECENT_KEY] : [];
  const item = { ...compactRecord(record), sync_state: state, sync_detail: detail };
  const next = [item, ...recent.filter((entry) => entry.id !== record.id)].slice(0, MAX_RECENT);
  await chrome.storage.local.set({ [RECENT_KEY]: next });
}

async function uploadRecord(record, settings = null) {
  settings ||= await readSettings();
  if (!settings.serverUrl || !settings.writeToken) throw new Error("尚未配置同步服务");
  const response = await fetch(`${settings.serverUrl}/v1/captures/${encodeURIComponent(record.id)}`, {
    method: "PUT",
    headers: {
      Authorization: `Bearer ${settings.writeToken}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify(record),
    cache: "no-store",
    redirect: "error"
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.detail || `同步失败（HTTP ${response.status}）`);
  return body;
}

async function enqueue(record, reason) {
  const stored = await chrome.storage.local.get({ [OUTBOX_KEY]: [] });
  const outbox = Array.isArray(stored[OUTBOX_KEY]) ? stored[OUTBOX_KEY] : [];
  const next = [
    ...outbox.filter((item) => item.record?.id !== record.id),
    { record, attempts: 0, last_error: reason || "等待同步", queued_at: new Date().toISOString() }
  ];
  await chrome.storage.local.set({ [OUTBOX_KEY]: next });
  await storeRecent(record, "pending", reason || "等待同步");
}

async function save(record) {
  if (!record || typeof record !== "object" || !record.id) throw new Error("记录格式无效");
  const settings = await readSettings();
  if (!settings.writeToken) {
    await enqueue(record, "仅保存在浏览器本地：尚未配置写入 Token");
    return { state: "pending", localOnly: true };
  }
  try {
    const serverRecord = await uploadRecord(record, settings);
    await storeRecent(serverRecord, "synced");
    return { state: "synced", record: serverRecord };
  } catch (error) {
    await enqueue(record, error.message);
    return { state: "pending", error: error.message };
  }
}

async function flushOutboxOnce() {
  const settings = await readSettings();
  const stored = await chrome.storage.local.get({ [OUTBOX_KEY]: [] });
  const queue = Array.isArray(stored[OUTBOX_KEY]) ? stored[OUTBOX_KEY] : [];
  if (!queue.length || !settings.writeToken) return { sent: 0, pending: queue.length };
  const failed = new Map();
  const sentIds = new Set();
  let sent = 0;
  for (const item of queue.slice(0, 50)) {
    try {
      const serverRecord = await uploadRecord(item.record, settings);
      await storeRecent(serverRecord, "synced");
      sent += 1;
      sentIds.add(item.record.id);
    } catch (error) {
      failed.set(item.record.id, {
        ...item,
        attempts: Number(item.attempts || 0) + 1,
        last_error: error.message,
        last_attempt_at: new Date().toISOString()
      });
    }
  }
  // Re-read before committing so a capture queued while requests were in flight
  // is never overwritten by this flush.
  const latest = await chrome.storage.local.get({ [OUTBOX_KEY]: [] });
  const remaining = (Array.isArray(latest[OUTBOX_KEY]) ? latest[OUTBOX_KEY] : [])
    .filter((item) => !sentIds.has(item.record?.id))
    .map((item) => failed.get(item.record?.id) || item);
  await chrome.storage.local.set({ [OUTBOX_KEY]: remaining });
  return { sent, pending: remaining.length };
}

function flushOutbox() {
  if (!flushPromise) {
    flushPromise = flushOutboxOnce().finally(() => { flushPromise = null; });
  }
  return flushPromise;
}

chrome.contextMenus.onClicked.addListener((info, tab) => {
  const task = info.menuItemId === MENU_SELECTION
    ? openSelection(tab?.id, info.selectionText || "")
    : info.menuItemId === MENU_SCREENSHOT
      ? openScreenshot(tab?.id)
      : Promise.resolve();
  task.catch((error) => console.error("Mnote capture failed", error));
});

chrome.commands.onCommand.addListener((command) => {
  const task = command === "capture-selection" ? openSelection() : openScreenshot();
  task.catch((error) => console.error("Mnote command failed", error));
});

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === "mnote-sync") void flushOutbox();
});

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  const run = async () => {
    switch (message?.type) {
      case "captureScreenshot":
        await openScreenshot(message.tabId);
        return {};
      case "captureSelection":
        await openSelection(message.tabId);
        return {};
      case "saveCapture":
        return save(message.record);
      case "syncOutbox":
        return flushOutbox();
      case "status": {
        const settings = await readSettings();
        const stored = await chrome.storage.local.get({ [OUTBOX_KEY]: [] });
        return {
          configured: Boolean(settings.writeToken),
          readConfigured: Boolean(settings.readToken),
          serverUrl: settings.serverUrl,
          pending: Array.isArray(stored[OUTBOX_KEY]) ? stored[OUTBOX_KEY].length : 0
        };
      }
      default:
        throw new Error("未知操作");
    }
  };
  run().then((value) => sendResponse({ ok: true, ...value })).catch((error) => {
    sendResponse({ ok: false, error: error?.message || String(error) });
  });
  return true;
});
