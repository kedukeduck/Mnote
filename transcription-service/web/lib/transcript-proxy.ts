import { env } from "cloudflare:workers";
import { getChatGPTUser } from "../app/chatgpt-auth";

const MAX_REQUEST_BYTES = 16_384;
const MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
const JOB_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function problem(status: number, code: string, message: string) {
  return Response.json({ error: { code, message } }, {
    status, headers: { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" },
  });
}

export async function transcriptProxy(request: Request, parts: string[]) {
  const user = await getChatGPTUser();
  if (!user) return problem(401, "sign_in_required", "请先登录后再使用转写服务。");
  const method = request.method;
  const config = parts.length === 1 && parts[0] === "config";
  const create = parts.length === 1 && parts[0] === "jobs";
  const job = parts.length >= 2 && parts[0] === "jobs" && JOB_ID.test(parts[1]);
  const download = job && parts.length === 3 && parts[2] === "download";
  const allowed = (config && method === "GET") || (create && method === "POST") ||
    (job && parts.length === 2 && ["GET", "DELETE"].includes(method)) || (download && method === "GET");
  if (!allowed) return problem(404, "not_found", "没有这个转写接口。");

  if (["POST", "DELETE"].includes(method)) {
    const origin = request.headers.get("Origin");
    if (!origin || origin !== new URL(request.url).origin) {
      return problem(403, "origin_mismatch", "请在本页面提交操作。");
    }
  }
  const runtime = env as unknown as Record<string, string | undefined>;
  const token = runtime.MNOTE_TRANSCRIPT_TOKEN;
  const base = runtime.MNOTE_TRANSCRIPT_API_URL;
  if (!token || !base) return problem(503, "service_unconfigured", "转写服务尚未配置，请稍后重试。");
  let target: URL;
  try {
    const root = new URL(base);
    if (root.protocol !== "https:" || root.username || root.password || root.search || root.hash) throw new Error();
    target = new URL(`${root.href.replace(/\/$/, "")}/v1/${parts.join("/")}`);
    if (download) {
      const format = new URL(request.url).searchParams.get("format") || "txt";
      if (!["txt", "md", "srt"].includes(format)) return problem(400, "invalid_format", "请选择 TXT、Markdown 或 SRT 格式。");
      target.searchParams.set("format", format);
    }
  } catch {
    return problem(503, "service_unconfigured", "转写服务地址配置不正确。");
  }
  const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(user.userId));
  const owner = Array.from(new Uint8Array(hash), value => value.toString(16).padStart(2, "0")).join("");
  const headers = new Headers({ "Authorization": `Bearer ${token}`, "X-Owner-Id": owner, "Accept": "application/json" });
  let body: string | undefined;
  if (method === "POST") {
    if (request.headers.get("Content-Type")?.split(";", 1)[0].trim().toLowerCase() !== "application/json") {
      return problem(415, "invalid_content_type", "提交内容必须是 JSON。");
    }
    const length = Number(request.headers.get("Content-Length") || "0");
    if (!Number.isFinite(length) || length < 0 || length > MAX_REQUEST_BYTES) return problem(413, "body_too_large", "链接和声明内容过长。");
    const reader = request.body?.getReader();
    const chunks: Uint8Array[] = [];
    let size = 0;
    if (reader) {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > MAX_REQUEST_BYTES) { await reader.cancel(); return problem(413, "body_too_large", "链接和声明内容过长。"); }
        chunks.push(value);
      }
    }
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    try {
      body = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
      const value = JSON.parse(body);
      if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error();
    } catch { return problem(400, "invalid_json", "提交内容格式不正确。"); }
    headers.set("Content-Type", "application/json");
  }
  try {
    // Cloudflare supports manual/follow; reject redirects explicitly so the
    // private Bearer token can never be forwarded to a different endpoint.
    const upstream = await fetch(target, { method, headers, body, redirect: "manual", signal: AbortSignal.timeout(25_000) });
    if (upstream.status >= 300 && upstream.status < 400) {
      await upstream.body?.cancel();
      return problem(502, "unexpected_redirect", "转写服务地址发生了意外跳转，请联系管理员。");
    }
    const reader = upstream.body?.getReader();
    const chunks: Uint8Array[] = [];
    let size = 0;
    if (reader) {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > MAX_RESPONSE_BYTES) { await reader.cancel(); return problem(502, "response_too_large", "转写结果超过页面接收限制。"); }
        chunks.push(value);
      }
    }
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    const output = new Headers({ "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" });
    output.set("Content-Type", upstream.headers.get("Content-Type") || "application/json; charset=utf-8");
    if (download && upstream.ok) {
      const format = target.searchParams.get("format")!;
      output.set("Content-Disposition", `attachment; filename="mnote-transcript.${format}"`);
    }
    return new Response(bytes, { status: upstream.status, headers: output });
  } catch (error) {
    // Log only the bounded failure category; never credentials or source URLs.
    const reason = error instanceof Error ? error.name : "unknown";
    console.error("transcript upstream failure", reason);
    return problem(502, "backend_unreachable", "暂时无法连接转写服务，请稍后重试。");
  }
}
