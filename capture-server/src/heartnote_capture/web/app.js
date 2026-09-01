"use strict";

const READ_TOKEN_KEY = "heartnote.capture.session-read-token";
const WRITE_TOKEN_KEY = "heartnote.capture.session-write-token";
const APP_BASE_PATH = window.location.pathname
  .replace(/index\.html$/, "")
  .replace(/\/$/, "");

function appPath(path) {
  if (path.startsWith("/v1/")) {
    return `${APP_BASE_PATH}${path}`;
  }
  return path;
}

const elements = {
  authPanel: document.querySelector("#auth-panel"),
  authForm: document.querySelector("#auth-form"),
  authMessage: document.querySelector("#auth-message"),
  readTokenInput: document.querySelector("#read-token-input"),
  writeTokenInput: document.querySelector("#write-token-input"),
  workspace: document.querySelector("#workspace"),
  searchForm: document.querySelector("#search-form"),
  searchInput: document.querySelector("#search-input"),
  trashToggle: document.querySelector("#trash-toggle"),
  refreshButton: document.querySelector("#refresh-button"),
  exportButton: document.querySelector("#export-button"),
  logoutButton: document.querySelector("#logout-button"),
  status: document.querySelector("#status"),
  recordList: document.querySelector("#record-list"),
  emptyState: document.querySelector("#empty-state"),
  detailPanel: document.querySelector("#detail-panel"),
  closeDetail: document.querySelector("#close-detail"),
  detailKind: document.querySelector("#detail-kind"),
  detailTitle: document.querySelector("#detail-title"),
  detailMeta: document.querySelector("#detail-meta"),
  detailComment: document.querySelector("#detail-comment"),
  commentSection: document.querySelector("#comment-section"),
  detailSource: document.querySelector("#detail-source"),
  detailUrl: document.querySelector("#detail-url"),
  sourceSection: document.querySelector("#source-section"),
  detailImages: document.querySelector("#detail-images"),
  detailJson: document.querySelector("#detail-json"),
  detailActions: document.querySelector("#detail-actions"),
};

const state = {
  readToken: sessionStorage.getItem(READ_TOKEN_KEY) || "",
  writeToken: sessionStorage.getItem(WRITE_TOKEN_KEY) || "",
  records: [],
  selectedId: null,
  imageUrls: [],
  loadingGeneration: 0,
};

const kindNames = {
  capture: "捕获",
  comment: "评论",
  thought: "想法",
  later: "稍后读",
  todo: "TODO",
  journal: "日志",
};

class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function setStatus(message, isError = false) {
  elements.status.textContent = message;
  elements.status.classList.toggle("error", isError);
}

function setAuthMessage(message, isError = false) {
  elements.authMessage.textContent = message;
  elements.authMessage.classList.toggle("error", isError);
}

function revokeImageUrls() {
  for (const url of state.imageUrls) {
    URL.revokeObjectURL(url);
  }
  state.imageUrls = [];
}

function lock(message = "") {
  state.readToken = "";
  state.writeToken = "";
  state.records = [];
  state.selectedId = null;
  sessionStorage.removeItem(READ_TOKEN_KEY);
  sessionStorage.removeItem(WRITE_TOKEN_KEY);
  revokeImageUrls();
  elements.workspace.hidden = true;
  elements.authPanel.hidden = false;
  elements.readTokenInput.value = "";
  elements.writeTokenInput.value = "";
  elements.detailPanel.hidden = true;
  setAuthMessage(message, Boolean(message));
  elements.readTokenInput.focus();
}

function unlock() {
  elements.authPanel.hidden = true;
  elements.workspace.hidden = false;
  setAuthMessage("");
}

async function authorizedFetch(path, options = {}, tokenKind = "read") {
  const token = tokenKind === "write" ? state.writeToken : state.readToken;
  if (!token) {
    throw new ApiError(
      401,
      tokenKind === "write"
        ? "此操作需要 Write Token。请锁定页面后同时输入 Read Token 和 Write Token。"
        : "此操作需要 Read Token。",
    );
  }
  const headers = new Headers(options.headers || {});
  headers.set("Authorization", `Bearer ${token}`);
  const response = await fetch(appPath(path), {
    ...options,
    headers,
    cache: "no-store",
    credentials: "same-origin",
  });
  if (!response.ok) {
    let detail = `${response.status} ${response.statusText}`;
    try {
      const problem = await response.json();
      detail = problem.detail || problem.error || detail;
    } catch (_ignored) {
      // Non-JSON errors retain the HTTP status message.
    }
    throw new ApiError(response.status, detail);
  }
  return response;
}

async function apiJson(path, options = {}, tokenKind = "read") {
  const request = {...options};
  if (request.body !== undefined) {
    request.headers = {...(request.headers || {}), "Content-Type": "application/json"};
    request.body = JSON.stringify(request.body);
  }
  const response = await authorizedFetch(path, request, tokenKind);
  return response.json();
}

function describeError(error) {
  if (error instanceof ApiError && error.status === 403) {
    return "当前令牌没有执行此操作的权限。浏览可使用读取令牌，删除和恢复需要写入令牌。";
  }
  if (error instanceof ApiError && error.status === 409) {
    return "记录已在其他设备更新，请刷新后重试。";
  }
  if (error instanceof TypeError) {
    return "无法连接到 Mnote 服务，请确认服务仍在运行。";
  }
  return error.message || "请求失败";
}

function formatDate(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value || "时间未知";
  }
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

function sourceOf(record) {
  return record.source && typeof record.source === "object" ? record.source : {};
}

function textPreview(record) {
  const source = sourceOf(record);
  return record.comment || source.text || (record.ocr || []).map((item) => item.text || "").join(" ") || "未添加文字";
}

function makePill(text, className) {
  const pill = document.createElement("span");
  pill.className = className;
  pill.textContent = text;
  return pill;
}

function renderList() {
  const showingTrash = elements.trashToggle.checked;
  const records = state.records.filter((record) => Boolean(record.deleted) === showingTrash);
  elements.recordList.replaceChildren();
  elements.emptyState.hidden = records.length !== 0;

  for (const record of records) {
    const source = sourceOf(record);
    const button = document.createElement("button");
    button.type = "button";
    button.className = "record-card";
    button.classList.toggle("selected", record.id === state.selectedId);
    button.dataset.captureId = record.id;

    const top = document.createElement("span");
    top.className = "card-top";
    top.append(makePill(kindNames[record.kind] || record.kind || "记录", "kind-pill"));
    const time = document.createElement("time");
    time.className = "card-time";
    time.dateTime = record.created_at || "";
    time.textContent = formatDate(record.created_at);
    top.append(time);

    const title = document.createElement("strong");
    title.className = "card-title";
    title.textContent = textPreview(record);

    const sourceText = document.createElement("span");
    sourceText.className = "card-source";
    sourceText.textContent = source.text || source.url || "无来源文字";

    const footer = document.createElement("span");
    footer.className = "card-footer";
    footer.append(makePill(record.ai_access || "local_only", "policy-pill"));
    if (record.deleted) {
      footer.append(makePill("已删除", "deleted-pill"));
    }
    const app = document.createElement("span");
    app.textContent = source.app_name || source.app_id || source.package || "未知应用";
    footer.append(app);

    button.append(top, title, sourceText, footer);
    button.addEventListener("click", () => showDetail(record.id));
    elements.recordList.append(button);
  }
}

function addMeta(text, className = "policy-pill") {
  elements.detailMeta.append(makePill(text, className));
}

async function loadImages(record, generation) {
  const assets = record.assets && typeof record.assets === "object" ? record.assets : {};
  const roles = ["annotated", "original"].filter((role) => assets[role]);
  if (roles.length === 0) {
    const message = document.createElement("p");
    message.className = "image-message";
    message.textContent = "这条记录没有截图资产。";
    elements.detailImages.append(message);
    return;
  }

  for (const role of roles) {
    const placeholder = document.createElement("p");
    placeholder.className = "image-message";
    placeholder.textContent = `正在加载${role === "annotated" ? "批注图" : "原图"}…`;
    elements.detailImages.append(placeholder);
    try {
      const separator = assets[role].href.includes("?") ? "&" : "?";
      const response = await authorizedFetch(`${assets[role].href}${separator}include_deleted=true`);
      const blob = await response.blob();
      if (generation !== state.loadingGeneration) {
        return;
      }
      const objectUrl = URL.createObjectURL(blob);
      state.imageUrls.push(objectUrl);
      const figure = document.createElement("figure");
      figure.className = "capture-figure";
      const image = document.createElement("img");
      image.src = objectUrl;
      image.alt = role === "annotated" ? "带批注的捕获截图" : "捕获原始截图";
      const caption = document.createElement("figcaption");
      caption.textContent = role === "annotated" ? "批注图" : "原图";
      figure.append(image, caption);
      placeholder.replaceWith(figure);
    } catch (error) {
      if (generation !== state.loadingGeneration) {
        return;
      }
      placeholder.textContent = `图片加载失败：${describeError(error)}`;
    }
  }
}

function showDetail(captureId) {
  const record = state.records.find((item) => item.id === captureId);
  if (!record) {
    return;
  }
  state.selectedId = captureId;
  state.loadingGeneration += 1;
  const generation = state.loadingGeneration;
  revokeImageUrls();
  renderList();

  const source = sourceOf(record);
  elements.detailPanel.hidden = false;
  elements.detailKind.textContent = kindNames[record.kind] || record.kind || "记录";
  elements.detailTitle.textContent = textPreview(record);
  elements.detailMeta.replaceChildren();
  addMeta(`创建于 ${formatDate(record.created_at)}`, "policy-pill");
  addMeta(`修订 ${record.revision}`, "policy-pill");
  addMeta(record.ai_access || "local_only", "policy-pill");
  if (record.deleted) {
    addMeta("回收站", "deleted-pill");
  }

  elements.detailComment.textContent = record.comment || "";
  elements.commentSection.hidden = !record.comment;
  elements.detailSource.textContent = source.text || "";
  const sourceUrl = typeof source.url === "string" ? source.url : "";
  const safeUrl = /^https?:\/\//i.test(sourceUrl) ? sourceUrl : "";
  elements.detailUrl.hidden = !safeUrl;
  elements.detailUrl.href = safeUrl || "#";
  elements.detailUrl.textContent = safeUrl;
  elements.sourceSection.hidden = !source.text && !safeUrl;
  elements.detailJson.textContent = JSON.stringify(record, null, 2);
  elements.detailImages.replaceChildren();
  elements.detailActions.replaceChildren();

  const action = document.createElement("button");
  action.type = "button";
  action.className = `action-button ${record.deleted ? "restore" : "delete"}`;
  action.textContent = record.deleted ? "恢复记录" : "移入回收站";
  action.addEventListener("click", () => record.deleted ? restoreRecord(record) : deleteRecord(record));
  elements.detailActions.append(action);
  loadImages(record, generation);

  if (window.matchMedia("(max-width: 920px)").matches) {
    elements.detailPanel.scrollIntoView({behavior: "smooth", block: "start"});
  }
}

function closeDetail() {
  state.selectedId = null;
  state.loadingGeneration += 1;
  revokeImageUrls();
  elements.detailPanel.hidden = true;
  renderList();
}

async function loadRecords() {
  setStatus("正在读取…");
  const query = elements.searchInput.value.trim();
  const parameters = new URLSearchParams({limit: query ? "100" : "200"});
  if (elements.trashToggle.checked) {
    parameters.set("include_deleted", "true");
  }
  if (query) {
    parameters.set("q", query);
  }
  const path = query ? `/v1/search?${parameters}` : `/v1/captures?${parameters}`;
  try {
    const result = await apiJson(path);
    state.records = Array.isArray(result.records) ? result.records : [];
    if (state.selectedId && !state.records.some((record) => record.id === state.selectedId)) {
      closeDetail();
    } else {
      renderList();
    }
    const visibleCount = state.records.filter(
      (record) => Boolean(record.deleted) === elements.trashToggle.checked,
    ).length;
    setStatus(`${elements.trashToggle.checked ? "回收站" : "收件箱"} · ${visibleCount} 条记录`);
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      lock("令牌无效或已失效，请重新输入。");
      return;
    }
    setStatus(describeError(error), true);
  }
}

async function deleteRecord(record) {
  if (!window.confirm("将这条记录移入回收站？之后可以恢复。")) {
    return;
  }
  setStatus("正在移入回收站…");
  try {
    await apiJson(`/v1/captures/${encodeURIComponent(record.id)}`, {
      method: "DELETE",
      headers: {"If-Match": `"revision:${record.revision}"`},
    }, "write");
    closeDetail();
    await loadRecords();
  } catch (error) {
    setStatus(describeError(error), true);
  }
}

async function restoreRecord(record) {
  setStatus("正在恢复…");
  try {
    await apiJson(`/v1/captures/${encodeURIComponent(record.id)}/restore`, {
      method: "POST",
      body: {base_revision: record.revision},
    }, "write");
    closeDetail();
    await loadRecords();
  } catch (error) {
    setStatus(describeError(error), true);
  }
}

async function exportVault() {
  elements.exportButton.disabled = true;
  setStatus("正在生成开放格式导出包…");
  try {
    const response = await authorizedFetch("/v1/export?include_deleted=true");
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `heartnote-export-${new Date().toISOString().slice(0, 10)}.zip`;
    document.body.append(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
    setStatus("导出完成。");
  } catch (error) {
    setStatus(describeError(error), true);
  } finally {
    elements.exportButton.disabled = false;
  }
}

elements.authForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const readToken = elements.readTokenInput.value.trim();
  const writeToken = elements.writeTokenInput.value.trim();
  if (!readToken) {
    setAuthMessage("请输入 Read Token。", true);
    return;
  }
  state.readToken = readToken;
  state.writeToken = writeToken;
  setAuthMessage("正在验证…");
  try {
    await apiJson("/v1/captures?limit=1");
    sessionStorage.setItem(READ_TOKEN_KEY, readToken);
    if (writeToken) {
      sessionStorage.setItem(WRITE_TOKEN_KEY, writeToken);
    } else {
      sessionStorage.removeItem(WRITE_TOKEN_KEY);
    }
    elements.readTokenInput.value = "";
    elements.writeTokenInput.value = "";
    unlock();
    await loadRecords();
  } catch (error) {
    state.readToken = "";
    state.writeToken = "";
    sessionStorage.removeItem(READ_TOKEN_KEY);
    sessionStorage.removeItem(WRITE_TOKEN_KEY);
    setAuthMessage(describeError(error), true);
  }
});

elements.searchForm.addEventListener("submit", (event) => {
  event.preventDefault();
  closeDetail();
  loadRecords();
});
elements.trashToggle.addEventListener("change", () => {
  closeDetail();
  loadRecords();
});
elements.refreshButton.addEventListener("click", loadRecords);
elements.exportButton.addEventListener("click", exportVault);
elements.logoutButton.addEventListener("click", () => lock());
elements.closeDetail.addEventListener("click", closeDetail);
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && !elements.detailPanel.hidden) {
    closeDetail();
  }
});

if (state.readToken) {
  unlock();
  loadRecords();
} else {
  lock();
}
