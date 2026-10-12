// Isolated, local-only browser QA using the existing headless Chromium service.
// Creates its own browser context; never reads other tabs, cookies, or profiles.
import { mkdir, writeFile } from 'node:fs/promises';

const url = process.argv[2] || 'http://127.0.0.1:5173/';
const output = process.argv[3];
if (!output || !['127.0.0.1', 'localhost'].includes(new URL(url).hostname)) {
  throw new Error('Pass a local preview URL and an absolute screenshot directory.');
}
const browser = await fetch('http://127.0.0.1:9222/json/version').then(r => r.json());
const socket = new WebSocket(browser.webSocketDebuggerUrl);
await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
let id = 0;
const pending = new Map();
socket.onmessage = event => {
  const message = JSON.parse(event.data);
  if (!message.id) return;
  const call = pending.get(message.id);
  if (!call) return;
  pending.delete(message.id);
  clearTimeout(call.timer);
  if (message.error) call.reject(new Error(`${call.method}: ${message.error.message}`)); else call.resolve(message.result);
};
function call(method, params = {}, sessionId) {
  return new Promise((resolve, reject) => {
    const next = ++id;
    const timer = setTimeout(() => { pending.delete(next); reject(new Error(`${method} timed out`)); }, 30000);
    pending.set(next, { resolve, reject, timer, method });
    socket.send(JSON.stringify({ id: next, method, params, ...(sessionId ? { sessionId } : {}) }));
  });
}
const { browserContextId } = await call('Target.createBrowserContext');
try {
  const { targetId } = await call('Target.createTarget', { url: 'about:blank', browserContextId });
  const { sessionId } = await call('Target.attachToTarget', { targetId, flatten: true });
  const evaluate = async expression => {
    const result = await call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true }, sessionId);
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
    return result.result.value;
  };
  await call('Page.enable', {}, sessionId);
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1024, deviceScaleFactor: 1, mobile: false }, sessionId);
  await call('Page.navigate', { url }, sessionId);
  let state;
  for (let attempt = 0; attempt < 30; attempt++) {
    try { state = await evaluate('({text:document.body?.innerText || "",ready:document.readyState})'); }
    catch (error) { if (!error.message.includes('navigated')) throw error; state = {text:'',ready:''}; }
    if (state.ready === 'complete' && state.text.includes('使用')) break;
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  await mkdir(output, { recursive: true });
  const desktop = await call('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true }, sessionId);
  await writeFile(`${output}/desktop.png`, Buffer.from(desktop.data, 'base64'));
  const desktopLayout = await evaluate('({viewport:innerWidth, width:document.documentElement.scrollWidth,checkboxes:[...document.querySelectorAll("input[type=checkbox]")].map(x=>x.checked),buttons:[...document.querySelectorAll("button")].map(x=>({text:x.textContent.trim(),disabled:x.disabled})),headings:[...document.querySelectorAll("h1,h2")].map(x=>x.textContent)})');
  await call('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true }, sessionId);
  const mobile = await call('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true }, sessionId);
  await writeFile(`${output}/mobile.png`, Buffer.from(mobile.data, 'base64'));
  const mobileLayout = await evaluate('({viewport:innerWidth,width:document.documentElement.scrollWidth})');
  const config = await evaluate('fetch("/api/transcripts/config").then(async r=>({status:r.status,body:await r.json()}))');
  const originGuard = await evaluate('fetch("/api/transcripts/jobs",{method:"POST",headers:{"Content-Type":"text/plain"},body:"{}"}).then(r=>r.status)');
  console.log(JSON.stringify({ desktop: desktopLayout, mobile: mobileLayout, config, invalidContentTypeStatus: originGuard, screenshotDirectory: output }, null, 2));
  if (desktopLayout.width > desktopLayout.viewport || mobileLayout.width > mobileLayout.viewport) throw new Error('Horizontal layout overflow');
  if (desktopLayout.checkboxes.some(Boolean)) throw new Error('Consent must start unchecked');
  if (config.status !== 200 || config.body.ready !== true) throw new Error('Backend readiness check failed');
  if (originGuard !== 415) throw new Error('JSON-only mutation guard failed');
  // Exercise the actual UI, using only the project's original CC0 test speech.
  for (let attempt = 0; attempt < 30; attempt++) {
    if (await evaluate('[...document.querySelectorAll("button")].some(x=>x.textContent.includes("用测试音频体验"))')) break;
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  await evaluate('[...document.querySelectorAll("button")].find(x=>x.textContent.includes("用测试音频体验")).click()');
  await new Promise(resolve => setTimeout(resolve, 200));
  const sampleForm = await evaluate('({url:document.querySelector("#share-text").value,basis:document.querySelector("#rights-basis").value,consent:document.querySelector("input[type=checkbox]").checked,disabled:document.querySelector("button[type=submit]").disabled})');
  if (sampleForm.url !== config.body.demo_sample.url || sampleForm.basis !== 'open_license' || sampleForm.consent || !sampleForm.disabled) throw new Error('Sample must fill form without granting consent');
  await evaluate('document.querySelector("input[type=checkbox]").click()');
  await new Promise(resolve => setTimeout(resolve, 150));
  await evaluate('document.querySelector("button[type=submit]").click()');
  let result, jobId;
  try {
    for (let attempt = 0; attempt < 90; attempt++) {
      result = await evaluate('({text:document.querySelector("#panel-text")?.textContent || "",error:document.querySelector(".inline-error")?.textContent || document.querySelector(".error-code")?.textContent || "",id:sessionStorage.getItem("mnote-transcript-job-id"),progress:document.querySelector("[role=progressbar]")?.getAttribute("aria-valuenow")})');
      jobId = result.id || jobId;
      if (result.error) throw new Error(`UI submission failed: ${result.error}`);
      if (result.text) break;
      await new Promise(resolve => setTimeout(resolve, 2000));
    }
    if (!result.text.toLowerCase().includes('recording') || !jobId) throw new Error('Actual UI transcription did not complete');
    const completed = await call('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true }, sessionId);
    await writeFile(`${output}/mobile-result.png`, Buffer.from(completed.data, 'base64'));
    await evaluate('[...document.querySelectorAll("[role=tab]")].find(x=>x.textContent==="时间轴").click()');
    await new Promise(resolve => setTimeout(resolve, 150));
    const segmentCount = await evaluate('document.querySelectorAll("#panel-segments .segment-row").length');
    if (!segmentCount) throw new Error('Timeline not rendered');
    const exports = await evaluate(`Promise.all(["txt","md","srt"].map(async format=>{const response=await fetch("/api/transcripts/jobs/${jobId}/download?format="+format);return {format,status:response.status,bytes:(await response.arrayBuffer()).byteLength}}))`);
    if (exports.some(x=>x.status !== 200 || !x.bytes)) throw new Error('Browser export failed');
    await call('Page.navigate', { url }, sessionId);
    for (let attempt = 0; attempt < 30; attempt++) {
      try { if (await evaluate('!!document.querySelector("#panel-text")')) break; }
      catch (error) { if (!error.message.includes('navigated')) throw error; }
      await new Promise(resolve => setTimeout(resolve, 500));
    }
    if (!await evaluate('!!document.querySelector("#panel-text")')) throw new Error('Refresh did not restore result');
    await evaluate('[...document.querySelectorAll("button")].find(x=>x.getAttribute("aria-label")==="删除当前任务和转写记录").click()');
    await new Promise(resolve => setTimeout(resolve, 150));
    if (!await evaluate('!!document.querySelector("[role=alertdialog]")')) throw new Error('Delete confirmation missing');
    await evaluate('[...document.querySelectorAll("[role=alertdialog] button")].find(x=>x.textContent==="确认删除").click()');
    for (let attempt = 0; attempt < 20; attempt++) {
      if (!await evaluate('sessionStorage.getItem("mnote-transcript-job-id")')) break;
      await new Promise(resolve => setTimeout(resolve, 250));
    }
    const deletedStatus = await evaluate(`fetch("/api/transcripts/jobs/${jobId}").then(r=>r.status)`);
    if (deletedStatus !== 404) throw new Error('Deleted UI record remains accessible');
    console.log(JSON.stringify({ actualUITranscription: result.text, timelineSegments: segmentCount, exports, restoredAfterRefresh: true, deletedStatus }, null, 2));
    jobId = null;
  } finally {
    if (jobId) await evaluate(`fetch("/api/transcripts/jobs/${jobId}",{method:"DELETE"}).then(r=>r.status)`);
  }
} finally {
  await call('Target.disposeBrowserContext', { browserContextId });
  socket.close();
}
