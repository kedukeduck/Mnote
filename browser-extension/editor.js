import { captureId, dataUrlAsset, readSettings, sendMessage } from "./common.js";

const canvas = document.querySelector("#annotationCanvas");
const context = canvas.getContext("2d", { alpha: false });
const canvasStage = document.querySelector("#canvasStage");
const selectionStage = document.querySelector("#selectionStage");
const drawingTools = document.querySelector("#drawingTools");
const status = document.querySelector("#status");
const topStatus = document.querySelector("#topStatus");
const saveButton = document.querySelector("#saveButton");
const comment = document.querySelector("#comment");
const kind = document.querySelector("#kind");
const aiAccess = document.querySelector("#aiAccess");
const colorInput = document.querySelector("#color");
const widthInput = document.querySelector("#width");

let draft;
let draftKey;
let baseImage = null;
let originalDataUrl = "";
let operations = [];
let activeOperation = null;
let tool = "pen";

function showStatus(message, error = false) {
  status.textContent = message || "";
  status.classList.toggle("error", error);
  topStatus.textContent = error ? "保存失败" : "";
}

function pointFromEvent(event) {
  const rect = canvas.getBoundingClientRect();
  return {
    x: (event.clientX - rect.left) * canvas.width / rect.width,
    y: (event.clientY - rect.top) * canvas.height / rect.height
  };
}

function drawOperation(operation) {
  if (!operation?.points?.length) return;
  context.save();
  context.strokeStyle = operation.color;
  context.lineWidth = operation.width;
  context.lineJoin = "round";
  context.lineCap = "round";
  if (operation.type === "highlighter") {
    context.globalAlpha = 0.32;
    context.globalCompositeOperation = "source-over";
  }
  const first = operation.points[0];
  const last = operation.points[operation.points.length - 1];
  context.beginPath();
  if (operation.type === "ellipse") {
    context.ellipse(
      (first.x + last.x) / 2,
      (first.y + last.y) / 2,
      Math.abs(last.x - first.x) / 2,
      Math.abs(last.y - first.y) / 2,
      0,
      0,
      Math.PI * 2
    );
  } else if (operation.type === "rectangle") {
    context.rect(first.x, first.y, last.x - first.x, last.y - first.y);
  } else {
    context.moveTo(first.x, first.y);
    for (const point of operation.points.slice(1)) context.lineTo(point.x, point.y);
    if (operation.points.length === 1) context.lineTo(first.x + 0.01, first.y + 0.01);
  }
  context.stroke();
  context.restore();
}

function redraw() {
  if (!baseImage || !canvas.width) return;
  context.globalCompositeOperation = "source-over";
  context.globalAlpha = 1;
  context.clearRect(0, 0, canvas.width, canvas.height);
  context.drawImage(baseImage, 0, 0, canvas.width, canvas.height);
  for (const operation of operations) drawOperation(operation);
  drawOperation(activeOperation);
}

function boundedImageData(type = "image/png") {
  let value = canvas.toDataURL(type, type === "image/jpeg" ? 0.9 : undefined);
  if (value.length > 21_000_000 && type !== "image/jpeg") value = canvas.toDataURL("image/jpeg", 0.88);
  if (value.length > 21_000_000) throw new Error("图片超过 16 MB，请缩小浏览器窗口后重新截图");
  return value;
}

async function loadScreenshot(dataUrl) {
  const image = new Image();
  await new Promise((resolve, reject) => {
    image.onload = resolve;
    image.onerror = () => reject(new Error("截图无法读取"));
    image.src = dataUrl;
  });
  const scale = Math.min(1, 4096 / Math.max(image.naturalWidth, image.naturalHeight));
  canvas.width = Math.max(1, Math.round(image.naturalWidth * scale));
  canvas.height = Math.max(1, Math.round(image.naturalHeight * scale));
  baseImage = image;
  context.drawImage(image, 0, 0, canvas.width, canvas.height);
  originalDataUrl = boundedImageData();
  // Use the downscaled image as the immutable redraw source as well.
  if (scale < 1) {
    const normalized = new Image();
    await new Promise((resolve) => {
      normalized.onload = resolve;
      normalized.src = originalDataUrl;
    });
    baseImage = normalized;
  }
  redraw();
}

function normalizedAnnotations() {
  return operations.map((operation) => ({
    type: operation.type,
    color: operation.color,
    width: operation.width / Math.max(canvas.width, canvas.height),
    points: operation.points.map((point) => [point.x / canvas.width, point.y / canvas.height])
  }));
}

function boundedText(value, maximum) {
  return String(value || "").replaceAll("\u0000", " ").trim().slice(0, maximum);
}

function startDrawing(event) {
  if (!baseImage || event.button !== 0) return;
  canvas.setPointerCapture(event.pointerId);
  activeOperation = {
    type: tool,
    color: colorInput.value,
    width: Number(widthInput.value),
    points: [pointFromEvent(event)]
  };
  redraw();
}

function moveDrawing(event) {
  if (!activeOperation) return;
  const point = pointFromEvent(event);
  if (["ellipse", "rectangle"].includes(activeOperation.type)) {
    activeOperation.points[1] = point;
  } else {
    const last = activeOperation.points.at(-1);
    if (Math.hypot(point.x - last.x, point.y - last.y) >= 1) activeOperation.points.push(point);
  }
  redraw();
}

function endDrawing(event) {
  if (!activeOperation) return;
  moveDrawing(event);
  if (activeOperation.points.length === 1) activeOperation.points.push(activeOperation.points[0]);
  operations.push(activeOperation);
  activeOperation = null;
  redraw();
}

function buildRecord() {
  const id = captureId("web");
  const selection = draft.selection || null;
  const record = {
    schema_version: 1,
    id,
    created_at: draft.createdAt || new Date().toISOString(),
    kind: kind.value,
    comment: boundedText(comment.value, 20_000),
    source: {
      type: draft.mode === "selection" ? "web_selection" : draft.mode === "screenshot" ? "web_screenshot" : "quick_note",
      fidelity_level: draft.mode === "selection"
        ? (selection?.anchor_unique && !selection?.page_text_truncated && !selection?.selection_truncated ? "L4" : "L3")
        : draft.mode === "screenshot" ? "L2" : "user",
      degradation_reason: draft.mode === "selection" && (!selection?.anchor_unique || selection?.page_text_truncated || selection?.selection_truncated)
        ? (selection?.selection_truncated
          ? "selection_truncated"
          : selection?.page_text_truncated ? "source_snapshot_truncated" : "anchor_not_unique")
        : "",
      app_name: "Chrome/Edge extension",
      title: boundedText(draft.tab?.title, 2_000),
      url: boundedText(draft.tab?.url, 8_192),
      text: boundedText(selection?.exact, 200_000),
      selectors: selection ? {
        text_quote: { exact: selection.exact, prefix: selection.prefix, suffix: selection.suffix },
        dom_range: {
          start_path: selection.start_path,
          start_offset: selection.start_offset,
          end_path: selection.end_path,
          end_offset: selection.end_offset
        },
        rects: selection.rects || [],
        validation_status: selection.anchor_unique ? "validated_at_capture" : "not_unique",
        page_text_sha256: selection.page_text_sha256 || "",
        page_text_truncated: Boolean(selection.page_text_truncated),
        selection_truncated: Boolean(selection.selection_truncated)
      } : {}
    },
    ocr: [],
    annotations: draft.mode === "screenshot" ? normalizedAnnotations() : [],
    ai_access: aiAccess.value,
    client: { name: "mnote-browser", version: "0.1.0" },
    assets: {}
  };
  if (draft.mode === "screenshot") {
    record.assets.original = dataUrlAsset(originalDataUrl);
    record.assets.annotated = dataUrlAsset(boundedImageData());
  }
  return record;
}

async function save() {
  saveButton.disabled = true;
  showStatus("正在保存……");
  try {
    const record = buildRecord();
    const response = await sendMessage({ type: "saveCapture", record });
    await chrome.storage.session.remove(draftKey);
    if (response.state === "synced") {
      showStatus("已保存并同步到知识库");
      topStatus.textContent = "已同步";
    } else {
      showStatus(`已安全保存到本地待同步箱${response.error ? `：${response.error}` : ""}`);
      topStatus.textContent = "待同步";
    }
    saveButton.textContent = "已保存";
  } catch (error) {
    showStatus(error.message, true);
    saveButton.disabled = false;
  }
}

async function initialize() {
  draftKey = new URLSearchParams(location.search).get("draft");
  if (!draftKey) throw new Error("缺少捕获草稿");
  const stored = await chrome.storage.session.get(draftKey);
  draft = stored[draftKey];
  if (!draft) throw new Error("捕获草稿已过期，请重新截图或选择文字");
  const settings = await readSettings();
  aiAccess.value = settings.aiAccess;
  document.querySelector("#sourceLine").textContent = `${draft.tab?.title || "未命名来源"} · ${draft.tab?.url || ""}`;
  if (draft.mode === "screenshot") {
    await loadScreenshot(draft.image);
  } else {
    canvasStage.hidden = true;
    selectionStage.hidden = false;
    drawingTools.hidden = true;
    document.querySelector("#pageHeading").textContent = draft.mode === "selection" ? "记录选中文字" : "记录灵感";
    document.querySelector("#selectionText").textContent = draft.selection?.exact || "写下刚刚出现的想法";
  }
}

document.querySelectorAll(".tool").forEach((button) => button.addEventListener("click", () => {
  tool = button.dataset.tool;
  document.querySelectorAll(".tool").forEach((item) => item.classList.toggle("active", item === button));
}));
document.querySelector("#undoButton").addEventListener("click", () => { operations.pop(); redraw(); });
document.querySelector("#clearButton").addEventListener("click", () => { operations = []; redraw(); });
canvas.addEventListener("pointerdown", startDrawing);
canvas.addEventListener("pointermove", moveDrawing);
canvas.addEventListener("pointerup", endDrawing);
canvas.addEventListener("pointercancel", endDrawing);
saveButton.addEventListener("click", save);

initialize().catch((error) => {
  showStatus(error.message, true);
  saveButton.disabled = true;
});
