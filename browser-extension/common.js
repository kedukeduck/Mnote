export const DEFAULT_SETTINGS = Object.freeze({
  serverUrl: "http://127.0.0.1:8787",
  writeToken: "",
  readToken: "",
  aiAccess: "local_only"
});

function isPrivateHttpHost(hostname) {
  const host = String(hostname || "").toLowerCase().replace(/^\[|\]$/g, "");
  if (host === "localhost" || host === "::1") return true;
  const ipv4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(host);
  if (ipv4) {
    const parts = ipv4.slice(1).map(Number);
    if (parts.some((part) => part > 255)) return false;
    return parts[0] === 10
      || parts[0] === 127
      || (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31)
      || (parts[0] === 192 && parts[1] === 168)
      || (parts[0] === 169 && parts[1] === 254);
  }
  return /^(?:fc|fd)[0-9a-f:]*$/i.test(host) || /^fe[89ab][0-9a-f:]*$/i.test(host);
}

export function captureId(prefix = "web") {
  const random = crypto.getRandomValues(new Uint32Array(2));
  return `${prefix}-${Date.now()}-${random[0].toString(36)}${random[1].toString(36)}`;
}

export function normalizeServerUrl(value) {
  const candidate = String(value || "").trim().replace(/\/+$/, "");
  if (!candidate) return "";
  const url = new URL(candidate);
  if (!/^https?:$/.test(url.protocol) || url.username || url.password || url.search || url.hash) {
    throw new Error("服务地址必须是不含凭证、查询参数或片段的 HTTP(S) 地址");
  }
  if (url.protocol === "http:" && !isPrivateHttpHost(url.hostname)) {
    throw new Error("公网或域名服务必须使用 HTTPS；HTTP 只允许本机或私有 IP");
  }
  return url.origin + url.pathname.replace(/\/+$/, "");
}

export async function readSettings() {
  const stored = await chrome.storage.local.get(DEFAULT_SETTINGS);
  return {
    serverUrl: normalizeServerUrl(stored.serverUrl || DEFAULT_SETTINGS.serverUrl),
    writeToken: String(stored.writeToken || ""),
    readToken: String(stored.readToken || ""),
    aiAccess: ["deny", "local_only", "remote_no_memory", "remote_memory"].includes(stored.aiAccess)
      ? stored.aiAccess
      : DEFAULT_SETTINGS.aiAccess
  };
}

export function dataUrlAsset(dataUrl) {
  if (!dataUrl) return null;
  const match = /^data:(image\/(?:png|jpeg|webp));base64,([A-Za-z0-9+/=]+)$/.exec(dataUrl);
  if (!match) throw new Error("图片数据格式无效");
  return { content_type: match[1], data_base64: match[2] };
}

export function compactRecord(record) {
  const copy = structuredClone(record);
  delete copy.assets;
  return copy;
}

export function formatTime(value) {
  try {
    return new Intl.DateTimeFormat("zh-CN", {
      dateStyle: "medium",
      timeStyle: "short"
    }).format(new Date(value));
  } catch (_) {
    return value || "";
  }
}

export async function sendMessage(message) {
  const response = await chrome.runtime.sendMessage(message);
  if (!response?.ok) throw new Error(response?.error || "操作失败");
  return response;
}
