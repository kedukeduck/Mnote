import { ArrowDownToLine, ArrowRight, AudioLines, Check, ChevronDown, CircleAlert, Copy, FileText, Link2, LoaderCircle, LockKeyhole, RotateCcw, Trash2, X } from "lucide-react";
import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";

type RightsBasis = "owned" | "authorized" | "open_license";
type ReadMode = "text" | "segments";
type ExportFormat = "txt" | "md" | "srt";
type Segment = { start: number; end: number; text: string };
type Job = {
  id: string; status: string; stage?: string; progress?: number;
  error?: { code?: string; message?: string } | null;
  source?: { platform?: string; host?: string; title?: string };
  segments?: Segment[]; text?: string; created_at?: string;
  rights?: { basis: RightsBasis; note?: string; agreement_version?: string; verified: false };
};
type Configuration = {
  ready: boolean; retention_hours?: number; agreement_version?: string; max_duration_seconds?: number; message?: string;
  demo_sample?: { url: string; license: string; license_url: string };
};
type ServiceState = "checking" | "ready" | "unavailable";
const STORAGE_KEY = "mnote-transcript-job-id";
const AGREEMENT_VERSION = "2026-10-12-v1";
const APP_BASE = "/mnote-transcript/";
const API_BASE = `${APP_BASE}api/transcripts`;
const COMPLETE = new Set(["completed", "succeeded", "success", "done"]);
const FAILED = new Set(["failed", "error", "cancelled", "canceled", "deleted"]);
const isComplete = (job: Job | null) => !!job && COMPLETE.has(job.status.toLowerCase());
const isFailed = (job: Job | null) => !!job && FAILED.has(job.status.toLowerCase());
const isRunning = (job: Job | null) => !!job && !isComplete(job) && !isFailed(job);

function timeLabel(value: number) {
  const total = Number.isFinite(value) ? Math.max(0, Math.floor(value)) : 0;
  const seconds = String(total % 60).padStart(2, "0");
  const minutes = String(Math.floor(total / 60) % 60).padStart(2, "0");
  const hours = Math.floor(total / 3600);
  return hours ? `${String(hours).padStart(2, "0")}:${minutes}:${seconds}` : `${minutes}:${seconds}`;
}
function platformLabel(source?: Job["source"]) {
  const platform = source?.platform?.toLowerCase() ?? "";
  if (platform.includes("bili") || source?.host?.includes("bili")) return "哔哩哔哩";
  if (platform.includes("douyin") || source?.host?.includes("douyin")) return "抖音";
  return source?.platform || source?.host || "视频来源";
}
function stageLabel(job: Job) {
  const stage = job.stage || job.status;
  const labels: Record<string, string> = {
    queued: "已进入队列", pending: "已进入队列", validating: "检查分享链接", parsing: "解析分享链接",
    resolving: "解析分享链接", fetching: "读取公开音频", downloading: "读取公开音频", extracting: "提取音频",
    transcribing: "正在转写语音", processing: "正在处理视频", running: "正在处理视频", formatting: "整理文字与时间轴",
    completed: "转写完成", succeeded: "转写完成", failed: "转写未完成",
  };
  return labels[stage.toLowerCase()] || (/[^\x00-\x7F]/.test(stage) ? stage : "正在处理视频");
}
function progressValue(job: Job) {
  return typeof job.progress === "number" && Number.isFinite(job.progress) ? Math.min(100, Math.max(0, job.progress)) : null;
}
function errorMessage(value: unknown, fallback: string) { return value instanceof Error ? value.message : fallback; }
async function responseJSON(response: Response) {
  const raw: unknown = await response.json().catch(() => null);
  const payload = raw && typeof raw === "object" ? raw as Record<string, unknown> : null;
  if (!response.ok) {
    const error = payload?.error && typeof payload.error === "object" ? payload.error as Record<string, unknown> : null;
    const message = error?.message || payload?.message;
    throw new Error(typeof message === "string" ? message : `请求未完成（${response.status}），请稍后重试。`);
  }
  return payload;
}
function jobFromPayload(payload: unknown): Job {
  const value = (payload as { job?: Job } | null)?.job;
  if (!value || typeof value.id !== "string" || typeof value.status !== "string") throw new Error("服务返回了不完整的任务数据，请稍后重试。");
  return value;
}
function rememberJob(id: string | null) {
  try {
    if (id) window.sessionStorage.setItem(STORAGE_KEY, id);
    else window.sessionStorage.removeItem(STORAGE_KEY);
  } catch { /* Storage is optional. Share text and transcripts are never stored here. */ }
}

export default function TranscriptWorkspace() {
  const [shareText, setShareText] = useState("");
  const [rightsBasis, setRightsBasis] = useState<RightsBasis>("owned");
  const [rightsNote, setRightsNote] = useState("");
  const [consent, setConsent] = useState(false);
  const [configuration, setConfiguration] = useState<Configuration | null>(null);
  const [service, setService] = useState<ServiceState>("checking");
  const [serviceMessage, setServiceMessage] = useState("");
  const [job, setJob] = useState<Job | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [resuming, setResuming] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [readMode, setReadMode] = useState<ReadMode>("text");
  const [formError, setFormError] = useState("");
  const [pollError, setPollError] = useState("");
  const [retryPoll, setRetryPoll] = useState(0);
  const [notice, setNotice] = useState("");
  const [exporting, setExporting] = useState<ExportFormat | null>(null);
  const [copied, setCopied] = useState(false);
  const generation = useRef(0);
  const activeJobId = useRef<string | null>(null);
  const mutationController = useRef<AbortController | null>(null);
  const submitLock = useRef(false);
  const tabRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const shareInputRef = useRef<HTMLTextAreaElement | null>(null);
  const deleteButtonRef = useRef<HTMLButtonElement | null>(null);
  const cancelDeleteRef = useRef<HTMLButtonElement | null>(null);
  const running = isRunning(job), completed = isComplete(job), failed = isFailed(job);
  const jobId = job?.id, jobStatus = job?.status;
  const busy = submitting || resuming || running || deleting;
  const transcript = completed && typeof job?.text === "string" ? job.text : "";
  const segments = completed && Array.isArray(job?.segments) ? job.segments : [];
  const hasResult = completed && (transcript.trim().length > 0 || segments.length > 0);
  const plainText = transcript || segments.map((segment) => segment.text).join("\n");
  const retention = configuration?.retention_hours ?? 24;
  const progress = job ? progressValue(job) : null;
  const noteRequired = rightsBasis !== "owned";
  const canSubmit = service === "ready" && !busy && !!shareText.trim() && consent && (!noteRequired || rightsNote.trim().length >= 8);

  useEffect(() => {
    const controller = new AbortController();
    let disposed = false;
    const fetchConfig = async () => {
      try {
        const response = await fetch(`${API_BASE}/config`, { signal: controller.signal, credentials: "same-origin", cache: "no-store" });
        const data = await responseJSON(response) as Configuration | null;
        if (typeof data?.ready !== "boolean") throw new Error("服务状态暂不可用。");
        if (disposed) return;
        setConfiguration(data); setService(data.ready ? "ready" : "unavailable");
        setServiceMessage(data.message || (data.ready ? "转写服务已就绪" : "转写服务尚未就绪，请稍后再试。"));
      } catch (error) {
        if (disposed || controller.signal.aborted) return;
        setService("unavailable"); setServiceMessage(errorMessage(error, "暂时无法连接转写服务。"));
      }
    };
    const resumeJob = async () => {
      let id: string | null = null;
      try { id = window.sessionStorage.getItem(STORAGE_KEY); } catch { return; }
      if (!id || !/^[A-Za-z0-9_-]{1,128}$/.test(id)) return;
      const token = ++generation.current;
      activeJobId.current = id; setResuming(true);
      try {
        const response = await fetch(`${API_BASE}/jobs/${encodeURIComponent(id)}`, { signal: controller.signal, credentials: "same-origin", cache: "no-store" });
        if (response.status === 404 || response.status === 410) {
          rememberJob(null); activeJobId.current = null;
          if (!disposed) setNotice("上一条记录已删除或到期，可以开始新的转写。");
          return;
        }
        const restored = jobFromPayload(await responseJSON(response));
        if (!disposed && token === generation.current) setJob(restored);
      } catch (error) {
        if (!disposed && !controller.signal.aborted) setNotice(`暂时无法恢复上一条记录：${errorMessage(error, "请刷新页面重试。")}`);
      } finally {
        if (!disposed && token === generation.current) setResuming(false);
      }
    };
    void fetchConfig(); void resumeJob();
    return () => { disposed = true; controller.abort(); mutationController.current?.abort(); };
  }, []);

  useEffect(() => {
    if (!jobId || !jobStatus || COMPLETE.has(jobStatus.toLowerCase()) || FAILED.has(jobStatus.toLowerCase()) || deleting) return;
    const id = jobId, token = generation.current;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let disposed = false;
    const poll = async () => {
      try {
        const response = await fetch(`${API_BASE}/jobs/${encodeURIComponent(id)}`, { signal: controller.signal, credentials: "same-origin", cache: "no-store" });
        if (response.status === 404 || response.status === 410) {
          if (!disposed && token === generation.current && activeJobId.current === id) {
            activeJobId.current = null; rememberJob(null); setJob(null); setPollError(""); setNotice("这条记录已删除或到期，可以重新提交。");
          }
          return;
        }
        const nextJob = jobFromPayload(await responseJSON(response));
        if (disposed || token !== generation.current || activeJobId.current !== id) return;
        setPollError(""); setJob(nextJob);
        if (isRunning(nextJob)) timer = setTimeout(poll, 2000);
      } catch (error) {
        if (disposed || controller.signal.aborted || token !== generation.current || activeJobId.current !== id) return;
        setPollError(errorMessage(error, "暂时无法更新进度。")); timer = setTimeout(poll, 5000);
      }
    };
    timer = setTimeout(poll, 2000);
    return () => { disposed = true; controller.abort(); if (timer) clearTimeout(timer); };
  }, [jobId, jobStatus, deleting, retryPoll]);

  useEffect(() => { if (!notice) return; const timer = setTimeout(() => setNotice(""), 7000); return () => clearTimeout(timer); }, [notice]);
  useEffect(() => { if (!copied) return; const timer = setTimeout(() => setCopied(false), 2200); return () => clearTimeout(timer); }, [copied]);
  useEffect(() => { if (confirmDelete) cancelDeleteRef.current?.focus(); }, [confirmDelete]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitLock.current || busy) return;
    if (service !== "ready") { setFormError("转写服务尚未就绪，请稍后再试。"); return; }
    if (!shareText.trim()) { setFormError("请先粘贴完整的视频分享文案。"); shareInputRef.current?.focus(); return; }
    if (noteRequired && rightsNote.trim().length < 8) { setFormError("请具体说明授权来源或开放许可，至少 8 个字符。"); return; }
    if (!consent) { setFormError("提交前，请阅读并勾选下方使用声明。"); return; }
    submitLock.current = true;
    const controller = new AbortController(); mutationController.current = controller;
    const token = ++generation.current;
    activeJobId.current = null; rememberJob(null); setJob(null); setFormError(""); setPollError(""); setSubmitting(true); setConsent(false); setReadMode("text"); setCopied(false);
    try {
      const response = await fetch(`${API_BASE}/jobs`, {
        method: "POST", signal: controller.signal, credentials: "same-origin", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ share_text: shareText.trim(), rights_basis: rightsBasis,
          rights_note: rightsNote.trim() || (rightsBasis === "owned" ? "我声明此素材由我创作并拥有转写处理权利。" : ""),
          agreement_version: configuration?.agreement_version || AGREEMENT_VERSION, consent: true }),
      });
      const nextJob = jobFromPayload(await responseJSON(response));
      if (token !== generation.current || controller.signal.aborted) return;
      activeJobId.current = nextJob.id; rememberJob(nextJob.id); setJob(nextJob);
    } catch (error) {
      if (!controller.signal.aborted && token === generation.current) setFormError(errorMessage(error, "提交未完成，请重试。"));
    } finally { submitLock.current = false; if (token === generation.current) setSubmitting(false); }
  }
  function clearInput() { setShareText(""); setRightsBasis("owned"); setRightsNote(""); setConsent(false); setFormError(""); shareInputRef.current?.focus(); }
  function useDemoSample() {
    const sample = configuration?.demo_sample;
    if (!sample || busy) return;
    setShareText(sample.url); setRightsBasis("open_license");
    setRightsNote(`Mnote 原创合成语音测试素材，${sample.license} 许可，说明：${sample.license_url}`);
    setConsent(false); setFormError(""); shareInputRef.current?.focus();
  }
  async function deleteJob() {
    if (!job || deleting) return;
    const id = job.id, token = ++generation.current;
    const controller = new AbortController(); mutationController.current = controller;
    setDeleting(true); setConfirmDelete(false); setPollError("");
    try {
      const response = await fetch(`${API_BASE}/jobs/${encodeURIComponent(id)}`, { method: "DELETE", signal: controller.signal, credentials: "same-origin" });
      if (!response.ok && response.status !== 404 && response.status !== 410) await responseJSON(response);
      if (token !== generation.current || controller.signal.aborted) return;
      activeJobId.current = null; rememberJob(null); setJob(null); setCopied(false); setConsent(false); setNotice("当前任务与转写记录已删除。");
    } catch (error) {
      if (!controller.signal.aborted && token === generation.current) { setNotice(`删除未完成：${errorMessage(error, "请稍后重试。")}`); deleteButtonRef.current?.focus(); }
    } finally { if (token === generation.current) setDeleting(false); }
  }
  async function copyTranscript() {
    if (!plainText || deleting) return;
    try { await navigator.clipboard.writeText(plainText); setCopied(true); setNotice("全文已复制到剪贴板。"); }
    catch { setNotice("浏览器未允许复制。你可以选中文字手动复制，或下载文本。"); }
  }
  async function download(format: ExportFormat) {
    if (!job || !hasResult || exporting || deleting) return;
    const id = job.id, token = generation.current;
    setExporting(format);
    try {
      const response = await fetch(`${API_BASE}/jobs/${encodeURIComponent(id)}/download?format=${format}`, { credentials: "same-origin", cache: "no-store" });
      if (!response.ok) await responseJSON(response);
      const blob = await response.blob();
      if (token !== generation.current || activeJobId.current !== id) return;
      const objectUrl = URL.createObjectURL(blob), link = document.createElement("a");
      const safeTitle = (job.source?.title || "Mnote-视频转写").replace(/[<>:"/\\|?*\u0000-\u001f]/g, "-").slice(0, 100);
      link.href = objectUrl; link.download = `${safeTitle}.${format}`;
      document.body.appendChild(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
      setNotice(`${format.toUpperCase()} 文件已开始下载。`);
    } catch (error) { if (token === generation.current) setNotice(errorMessage(error, "下载未完成，请重试。")); }
    finally { setExporting(null); }
  }
  function handleTabKey(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    const next = event.key === "Home" ? 0 : event.key === "End" ? 1 : index === 0 ? 1 : 0;
    setReadMode(next === 0 ? "text" : "segments"); tabRefs.current[next]?.focus();
  }
  const statusLabel = deleting ? "正在删除" : submitting ? "提交中" : resuming ? "恢复记录中" : completed ? "转写完成" : failed ? "转写未完成" : running ? stageLabel(job!) : "等待转写";

  return (
    <div className="site-shell">
      <header className="site-header">
        <a className="brand" href={APP_BASE} aria-label="Mnote 视频转写首页"><span className="brand-symbol" aria-hidden="true">m</span><span className="brand-name">Mnote<span className="brand-divider">/</span><span className="brand-product">视频转写</span></span></a>
        <div className="header-meta"><span className="private-label">无需登录</span><span className="header-isolation">当前浏览器隔离</span><span className="demo-label">DEMO</span></div>
      </header>
      <main className="main-content">
        <section className="intro" aria-labelledby="page-title"><div><p className="eyebrow"><span />FROM VIDEO TO WORDS</p><h1 id="page-title">把视频，留成文字<span className="title-period">。</span></h1><p className="intro-description">让值得记住的声音，成为可以阅读、检索和收藏的笔记。</p></div><div className="intro-note"><AudioLines size={24} strokeWidth={1.25} aria-hidden="true" /><span>少一点回放<br />多一点思考</span></div></section>
        <div className="workspace">
          <section className="capture-panel" aria-labelledby="capture-title">
            <div className="panel-heading"><div className="section-index">01 <span>/ CAPTURE</span></div><button className="text-button" type="button" onClick={clearInput} disabled={submitting || deleting}>清空输入</button></div>
            <h2 id="capture-title">从一个分享开始</h2><p className="panel-description">粘贴完整分享文案，链接会自动识别。</p>
            <form onSubmit={submit} className="capture-form">
              <label className="field-label" htmlFor="share-text"><Link2 size={15} aria-hidden="true" />视频分享文案</label>
              <div className="share-field"><textarea id="share-text" ref={shareInputRef} value={shareText} onChange={(event) => { setShareText(event.target.value); setFormError(""); }} maxLength={6000} disabled={submitting || deleting} placeholder={"粘贴从哔哩哔哩或抖音复制的分享文案…\n\n不需要手动提取链接。"} aria-describedby="share-help" /><span className="field-count">{shareText.length.toLocaleString()} / 6,000</span></div>
              <div id="share-help" className="platform-help"><span className="platform-tag"><span className="platform-mark bili-mark" aria-hidden="true">b</span>哔哩哔哩</span><span className="platform-tag"><span className="platform-mark douyin-mark" aria-hidden="true">♪</span>抖音</span><span className="help-end">仅处理可公开访问的内容</span></div>
              {configuration?.demo_sample && <button type="button" className="sample-button" onClick={useDemoSample} disabled={busy}><AudioLines size={12} aria-hidden="true" />用测试音频体验<span>原创 · CC0</span><ArrowRight size={12} aria-hidden="true" /></button>}
              <div className="form-rule" />
              <label className="field-label" htmlFor="rights-basis">你处理这段视频的依据</label>
              <div className="select-field"><select id="rights-basis" value={rightsBasis} onChange={(event) => { setRightsBasis(event.target.value as RightsBasis); setConsent(false); }} disabled={submitting || deleting}><option value="owned">这是我自己的作品</option><option value="authorized">我已获得权利人的授权</option><option value="open_license">作品有允许此用途的开放许可</option></select><ChevronDown size={15} aria-hidden="true" /></div>
              <label className="note-label" htmlFor="rights-note">{noteRequired ? (rightsBasis === "authorized" ? "授权说明" : "开放许可说明") : "补充说明"}<span>{noteRequired ? "必填，至少 8 个字符" : "选填"}</span></label>
              <textarea className="rights-note" id="rights-note" value={rightsNote} onChange={(event) => setRightsNote(event.target.value)} maxLength={1000} disabled={submitting || deleting} required={noteRequired} minLength={noteRequired ? 8 : undefined} placeholder={rightsBasis === "authorized" ? "例如：已获作者书面授权，仅供个人学习整理。" : rightsBasis === "open_license" ? "请注明许可名称、出处及允许的用途。" : "例如：本人创作的视频，用于整理自己的笔记。"} />
              <label className="consent-label"><input type="checkbox" checked={consent} onChange={(event) => setConsent(event.target.checked)} disabled={submitting || deleting} /><span>我确认有权处理此内容，并同意仅用于合法的个人整理；我已阅读下方使用说明。</span></label>
              {formError && <p className="inline-error" role="alert"><CircleAlert size={15} aria-hidden="true" />{formError}</p>}
              <button className="submit-button" type="submit" disabled={!canSubmit}>{submitting || running || resuming ? <LoaderCircle size={17} className="spin" aria-hidden="true" /> : <AudioLines size={17} aria-hidden="true" />}<span>{submitting ? "正在提交" : running ? "正在转写" : resuming ? "正在恢复记录" : "开始转写"}</span>{!busy && <ArrowRight size={17} aria-hidden="true" />}</button>
              <p className="submit-note"><LockKeyhole size={12} aria-hidden="true" />当前浏览器会话隔离 · 保留 {retention} 小时 · 可随时删除</p>
            </form>
            <details className="usage-details"><summary>使用与隐私说明<ChevronDown size={13} aria-hidden="true" /></summary><div className="usage-content"><p>公开可见不等于允许转写。请确认你拥有作品、已获授权，或其开放许可允许此用途。你的声明不会被视为已验证的权利证明，也不会自动赋予任何使用许可。</p><p>B 站支持可公开读取的视频；抖音分享链接能否处理取决于平台访问限制，不保证成功。服务不会绕过登录、付费、访问控制或内容保护。</p><p>转写仅供你个人整理，请勿未经许可传播。语音识别可能有误，请对照原视频核对。无需登录，任务按当前浏览器会话隔离，并在 {retention} 小时后清理；浏览器只临时保存任务编号，不保存分享文案或转写正文。</p><p>会话隔离不等于账号保护。使用共用浏览器时请及时下载并删除记录；清理 Cookie 或换浏览器后，之前的任务可能无法恢复。</p>{configuration?.max_duration_seconds ? <p>单段视频时长上限：{timeLabel(configuration.max_duration_seconds)}。</p> : null}</div></details>
          </section>
          <section className={`reader-panel ${hasResult ? "has-result" : ""}`} aria-labelledby="reader-title" aria-busy={submitting || resuming || running || deleting}>
            <div className="reader-heading"><div><div className="section-index">02 <span>/ TRANSCRIPT</span></div><h2 id="reader-title">文字，在这里沉淀</h2></div><span className={`result-status ${completed ? "is-complete" : failed ? "is-failed" : ""}`} aria-live="polite"><span />{statusLabel}</span></div>
            {hasResult ? <>
              <div className="reader-toolbar"><div className="read-tabs" role="tablist" aria-label="转写阅读方式">{(["text", "segments"] as const).map((mode, index) => <button key={mode} type="button" role="tab" id={`tab-${mode}`} aria-controls={`panel-${mode}`} aria-selected={readMode === mode} tabIndex={readMode === mode ? 0 : -1} className={readMode === mode ? "active" : ""} ref={(element) => { tabRefs.current[index] = element; }} onClick={() => setReadMode(mode)} onKeyDown={(event) => handleTabKey(event, index)}>{mode === "text" ? "全文" : "时间轴"}</button>)}</div><button className="copy-button" type="button" onClick={copyTranscript} disabled={deleting || !plainText}>{copied ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}{copied ? "已复制" : "复制全文"}</button></div>
              <div className="transcript-scroll"><div className="source-overline">{platformLabel(job?.source)}<span>·</span>{plainText.replace(/\s/g, "").length.toLocaleString()} 字{segments.length > 0 && <><span>·</span>{timeLabel(segments[segments.length - 1].end)}</>}</div><h3 className="transcript-title">{job?.source?.title || "视频转写"}</h3><p className="transcript-caution">AI 语音转写，请对照原视频核对。</p><div className="transcript-rule" />{readMode === "text" ? <article id="panel-text" role="tabpanel" aria-labelledby="tab-text" tabIndex={0} className="transcript-text">{plainText}</article> : <div id="panel-segments" role="tabpanel" aria-labelledby="tab-segments" tabIndex={0} className="segment-list">{segments.length ? segments.map((segment, index) => <div className="segment-row" key={`${segment.start}-${index}`}><div className="segment-time"><span>{timeLabel(segment.start)}</span><span>{timeLabel(segment.end)}</span></div><p>{segment.text}</p></div>) : <p className="no-segments">这条记录没有返回时间轴，全文仍可阅读与下载。</p>}</div>}</div>
            </> : <div className="reader-content">
              {submitting || resuming || running || deleting ? <div className="processing-state"><div className="processing-symbol"><AudioLines size={32} strokeWidth={1.25} aria-hidden="true" /><span className="processing-orbit" /></div><p className="state-overline">{deleting ? "REMOVING YOUR RECORD" : "A LITTLE PATIENCE"}</p><h3>{deleting ? "正在删除这条记录" : resuming ? "正在找回你的记录" : submitting ? "正在送出这段声音" : "声音正在变成文字"}</h3><p className="state-description">{deleting ? "删除完成后，当前文字与任务都会移除。" : "处理时间取决于视频长度与服务队列。\n你可以留在这里，进度会自动更新。"}</p>{job && running && !deleting && <div className="progress-section"><div className="progress-label"><span>{stageLabel(job)}</span><span>{progress === null ? "处理中" : `${Math.round(progress)}%`}</span></div><div className={`progress-track ${progress === null ? "indeterminate" : ""}`} role="progressbar" aria-label="转写任务进度" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress ?? undefined}><span style={progress === null ? undefined : { width: `${progress}%` }} /></div></div>}{pollError && <div className="poll-warning" role="status"><CircleAlert size={14} aria-hidden="true" /><span>进度暂未更新：{pollError} 系统会自动重试。</span><button type="button" onClick={() => { setPollError(""); setRetryPoll((value) => value + 1); }}>立即重试</button></div>}</div>
                : failed ? <div className="failed-state"><div className="failure-symbol"><CircleAlert size={31} strokeWidth={1.4} aria-hidden="true" /></div><p className="state-overline">NOT THIS TIME</p><h3>这次没能完成转写</h3><p className="state-description">{job?.error?.message || "视频可能无法公开访问，或转写服务暂不可用。请确认链接与访问权限后重试。"}</p>{job?.error?.code && <span className="error-code">错误标识：{job.error.code}</span>}<button type="button" className="retry-button" onClick={() => { setConsent(false); shareInputRef.current?.focus(); }}><RotateCcw size={14} aria-hidden="true" />检查输入后重新提交</button><p className="state-small">重新提交前，请再次确认使用声明。</p></div>
                  : completed ? <div className="failed-state"><div className="failure-symbol"><FileText size={31} strokeWidth={1.4} aria-hidden="true" /></div><h3>暂时没有可读的文字</h3><p className="state-description">任务已结束，但没有返回转写正文。视频可能没有可识别的语音，你可以检查后重新提交。</p></div>
                    : <div className="empty-state"><div className="paper-illustration" aria-hidden="true"><span className="paper-back" /><div className="paper-front"><span className="paper-kicker">A NOTE TO KEEP</span><AudioLines size={36} strokeWidth={1.2} /><span className="paper-line line-one" /><span className="paper-line line-two" /><span className="paper-line line-three" /><span className="paper-margin">m.</span></div><span className="paper-spark spark-one" /><span className="paper-spark spark-two" /></div><p className="state-overline">MAKE ROOM FOR A GOOD IDEA</p><h3>好的片段，值得留下。</h3><p className="state-description">在左侧放入一段视频。<br />转写完成后，全文和时间轴会出现在这里。</p><div className="empty-formats"><span>自由阅读</span><span className="format-dot" /><span>一键复制</span><span className="format-dot" /><span>多格式下载</span></div></div>}
            </div>}
            <div className="reader-footer"><div className="export-actions"><ArrowDownToLine size={15} aria-hidden="true" /><span className="export-label">下载</span>{(["txt", "md", "srt"] as const).map((format) => <button key={format} type="button" onClick={() => download(format)} disabled={!hasResult || !!exporting || deleting} aria-label={`下载 ${format.toUpperCase()} 文件`}>{exporting === format ? <LoaderCircle size={12} className="spin" aria-hidden="true" /> : null}{format.toUpperCase()}</button>)}</div><button ref={deleteButtonRef} type="button" className="delete-button" onClick={() => setConfirmDelete(true)} disabled={!job || deleting || submitting || resuming} aria-label="删除当前任务和转写记录"><Trash2 size={15} aria-hidden="true" /><span>删除记录</span></button></div>
          </section>
        </div>
        <footer className="site-footer"><p>Mnote<span>留下证据，继续思考。</span></p><div className={`service-status ${service}`} role="status"><span className="service-dot" />{service === "checking" ? "正在连接转写服务" : service === "ready" ? "转写服务已就绪" : "转写服务暂不可用"}{service === "unavailable" && <button type="button" className="text-button" onClick={() => window.location.reload()}>重试</button>}</div></footer>
        {service === "unavailable" && <p className="service-detail" role="status">{serviceMessage || "暂时无法连接转写服务，请稍后重试。"} 当前页面不会生成模拟转写结果。</p>}
      </main>
      <div className="notice-region" role="status" aria-live="polite" aria-atomic="true">{notice && <div className="notice-toast"><span>{notice}</span><button type="button" onClick={() => setNotice("")} aria-label="关闭通知"><X size={15} aria-hidden="true" /></button></div>}</div>
      {confirmDelete && <div className="dialog-backdrop" onClick={(event) => { if (event.target === event.currentTarget) { setConfirmDelete(false); deleteButtonRef.current?.focus(); } }}><div className="delete-dialog" role="alertdialog" aria-modal="true" aria-labelledby="delete-title" aria-describedby="delete-description" onKeyDown={(event) => { if (event.key === "Escape") { setConfirmDelete(false); deleteButtonRef.current?.focus(); } else if (event.key === "Tab") { const buttons = event.currentTarget.querySelectorAll<HTMLButtonElement>("button"); const first = buttons[0], last = buttons[buttons.length - 1]; if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); } else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); } } }}><span className="dialog-icon"><Trash2 size={21} aria-hidden="true" /></span><h2 id="delete-title">删除这条记录？</h2><p id="delete-description">当前任务与转写文字会从服务中删除，这个操作无法撤销。需要保留的内容，请先下载。</p><div className="dialog-actions"><button type="button" ref={cancelDeleteRef} onClick={() => { setConfirmDelete(false); deleteButtonRef.current?.focus(); }}>先保留</button><button type="button" className="destructive-button" onClick={deleteJob}>确认删除</button></div></div></div>}
    </div>
  );
}
