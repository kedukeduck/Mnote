// Dedicated anonymous resolver. Never attach to an existing browser or user profile.
import http from 'node:http';
import net from 'node:net';
import {spawn} from 'node:child_process';
import {timingSafeEqual} from 'node:crypto';
import {mkdtemp, rm, lstat, unlink, chmod} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

export const BASE_HOSTS = new Set([
  'www.douyin.com', 'lf-security.bytegoofy.com', 'lf-douyin-pc-web.douyinstatic.com',
  'lf-cdn-tos.bytescm.com', 'sf1-cdn-tos.douyinstatic.com', 'lf9-sec.bytetos.com',
  'lf-c-flwb.bytetos.com', 'lf-rc1.yhgfb-cn-static.com', 'lf-ucenter-web.yhgfb-cn-static.com',
  'lf-static.applogcdn.com', 'lf3-short.ibytedapm.com', 'lf3-webcast.bytetos.com',
  'p-pc-weboff.byteimg.com', 'fonts.bytedance.com',
]);
const ID = /^[0-9]{10,24}$/;
const LIMIT = 2 * 1024 * 1024;
const ERRORS = new Set(['platform_restricted', 'browser_unavailable', 'browser_busy', 'timeout', 'image_post_unsupported', 'too_long']);
export class ResolveError extends Error {
  constructor(code) { super(code); this.code = ERRORS.has(code) ? code : 'browser_unavailable'; }
}

export function allowedURL(value, hosts) {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && !url.username && !url.password && !url.hash &&
      (!url.port || url.port === '443') && hosts.has(url.hostname);
  } catch { return false; }
}

export function findItem(data, id) {
  const stack = [data];
  let visited = 0;
  while (stack.length && visited++ < 2500) {
    const node = stack.pop();
    if (!node || typeof node !== 'object') continue;
    if (!Array.isArray(node) && String(node.aweme_id ?? node.awemeId ?? '') === id && node.video && typeof node.video === 'object') return node;
    for (const child of Object.values(node)) if (child && typeof child === 'object') stack.push(child);
  }
  return null;
}

export function sanitizeItem(item, id) {
  if (!ID.test(id) || String(item?.aweme_id ?? item?.awemeId ?? '') !== id) throw new ResolveError('platform_restricted');
  if ([68, 150].includes(item.aweme_type) || (Array.isArray(item.images) && item.images.length)) throw new ResolveError('image_post_unsupported');
  const video = item.video;
  const duration = video?.duration;
  if (typeof duration !== 'number' || !Number.isFinite(duration) || duration <= 0) throw new ResolveError('platform_restricted');
  if (duration > 1800000) throw new ResolveError('too_long');
  const address = value => {
    if (!value || typeof value !== 'object') return undefined;
    const urls = (value.url_list ?? value.urlList);
    if (!Array.isArray(urls)) return undefined;
    return {url_list: urls.filter(x => typeof x === 'string' && x.length <= 4096).slice(0, 8),
      ...(Number.isSafeInteger(value.data_size) && value.data_size > 0 ? {data_size: value.data_size} : {})};
  };
  const result = {aweme_id: id, aweme_type: item.aweme_type ?? 0,
    desc: typeof item.desc === 'string' ? item.desc.slice(0, 200) : '抖音视频',
    video: {duration}};
  for (const key of ['play_addr', 'play_addr_h264', 'play_addr_265']) {
    const cleaned = address(video[key]);
    if (cleaned) result.video[key] = cleaned;
  }
  if (Array.isArray(video.bit_rate)) result.video.bit_rate = video.bit_rate
    .filter(x => x && typeof x === 'object' && Number.isFinite(x.bit_rate) && x.bit_rate > 0)
    .sort((a, b) => a.bit_rate - b.bit_rate).slice(0, 8)
    .map(x => ({bit_rate: x.bit_rate, play_addr: address(x.play_addr)}));
  return result;
}

class PipeCDP {
  constructor(child) {
    this.child = child; this.next = 0; this.pending = new Map(); this.buffer = Buffer.alloc(0); this.onEvent = () => {}; this.closed = false;
    child.stdio[3].on('error', () => this.fail());
    child.stdio[4].on('error', () => this.fail());
    child.stdio[4].on('close', () => this.fail());
    child.stdio[4].on('data', data => {
      this.buffer = Buffer.concat([this.buffer, data]);
      if (this.buffer.length > 4 * LIMIT) { this.fail(); return; }
      let end;
      while ((end = this.buffer.indexOf(0)) >= 0) {
        const raw = this.buffer.subarray(0, end); this.buffer = this.buffer.subarray(end + 1);
        let message;
        try { message = JSON.parse(raw.toString('utf8')); } catch { this.fail(); return; }
        if (!message.id) { this.onEvent(message); continue; }
        const waiter = this.pending.get(message.id);
        if (!waiter) continue;
        this.pending.delete(message.id); clearTimeout(waiter.timer);
        if (message.error) waiter.reject(new ResolveError('browser_unavailable')); else waiter.resolve(message.result);
      }
    });
    child.on('exit', () => this.fail());
    child.on('error', () => this.fail());
  }
  fail() {
    this.closed = true; this.buffer = Buffer.alloc(0);
    for (const waiter of this.pending.values()) { clearTimeout(waiter.timer); waiter.reject(new ResolveError('browser_unavailable')); }
    this.pending.clear();
  }
  call(method, params = {}, sessionId) {
    return new Promise((resolve, reject) => {
      if (this.closed || this.child.exitCode !== null || this.child.killed) return reject(new ResolveError('browser_unavailable'));
      const id = ++this.next;
      const timer = setTimeout(() => { this.pending.delete(id); reject(new ResolveError('browser_unavailable')); }, 8000);
      this.pending.set(id, {resolve, reject, timer});
      this.child.stdio[3].write(JSON.stringify({id, method, params, ...(sessionId ? {sessionId} : {})}) + '\0', error => {
        if (error) this.fail();
      });
    });
  }
}

export async function resolveVideo(id, config, signal) {
  if (!ID.test(id)) throw new ResolveError('platform_restricted');
  const profile = await mkdtemp(path.join(config.runtime, 'profile-'));
  let child;
  let cdp;
  let killTimer;
  let timedOut = false;
  const deadlineAt = Date.now() + 60000;
  const observations = {blockedHosts: new Set(), proxyChallenges: 0, proxyCredentialsUsed: 0,
    documentStatuses: [], scriptResponses: 0, detailResponses: 0, renderBytes: 0, scriptCount: 0, failures: new Set()};
  const stop = () => {
    if (child?.pid) {
      try { process.kill(-child.pid, 'SIGTERM'); } catch {}
      killTimer ??= setTimeout(() => { try { process.kill(-child.pid, 'SIGKILL'); } catch {} }, 1000);
    }
  };
  signal.addEventListener('abort', stop, {once: true});
  const timeout = setTimeout(() => { timedOut = true; stop(); }, 60000);
  try {
    if (signal.aborted) throw new ResolveError('timeout');
    child = spawn(config.chrome, [
      '--headless=new', '--remote-debugging-pipe', '--no-first-run', '--no-default-browser-check',
      '--disable-background-networking', '--disable-component-update', '--disable-sync',
      '--disable-dev-shm-usage', '--disable-gpu', '--disable-quic', '--dns-prefetch-disable',
      '--disable-features=EncryptedClientHello', '--disable-extensions',
      '--proxy-server=http://127.0.0.1:8794', '--proxy-bypass-list=<-loopback>',
      '--user-data-dir=' + profile, 'about:blank',
    ], {detached: true, stdio: ['ignore', 'ignore', 'ignore', 'pipe', 'pipe'],
      env: {PATH: '/usr/bin:/bin', HOME: profile, TMPDIR: profile, LANG: 'C.UTF-8'}});
    cdp = new PipeCDP(child);
    const requests = new Set();
    const seen = new Set();
    let blocked = false;
    cdp.onEvent = message => {
      const params = message.params;
      if (message.method === 'Fetch.authRequired') {
        const challenge = params.authChallenge;
        const ownProxy = challenge?.source === 'Proxy' && /^http:\/\/127\.0\.0\.1:8794\/?$/.test(challenge.origin);
        observations.proxyChallenges++;
        if (ownProxy) observations.proxyCredentialsUsed++;
        cdp.call('Fetch.continueWithAuth', {requestId: params.requestId, authChallengeResponse: ownProxy
          ? {response: 'ProvideCredentials', username: 'mnote', password: config.proxyPassword}
          : {response: 'CancelAuth'}}, message.sessionId).catch(() => {});
      }
      if (message.method === 'Fetch.requestPaused') {
        const permitted = allowedURL(params.request.url, config.hosts) &&
          !['Media', 'Image', 'Font', 'WebSocket'].includes(params.resourceType);
        if (!permitted) {
          try { const host = new URL(params.request.url).hostname;
            if (!config.hosts.has(host) && /^[a-z0-9.-]{1,253}$/.test(host) && observations.blockedHosts.size < 20) observations.blockedHosts.add(host);
          } catch {}
        }
        cdp.call(permitted ? 'Fetch.continueRequest' : 'Fetch.failRequest',
          permitted ? {requestId: params.requestId} : {requestId: params.requestId, errorReason: 'BlockedByClient'}, message.sessionId).catch(() => {});
      }
      if (message.method === 'Network.responseReceived') {
        const response = params.response;
        let url;
        try { url = new URL(response.url); } catch { return; }
        if (params.type === 'Document' && observations.documentStatuses.length < 5) observations.documentStatuses.push(response.status);
        if (params.type === 'Script') observations.scriptResponses++;
        const isDetail = url.protocol === 'https:' && url.hostname === 'www.douyin.com' &&
          url.pathname === '/aweme/v1/web/aweme/detail/' && url.searchParams.get('aweme_id') === id;
        if (url.hostname === 'www.douyin.com' && (params.type === 'Document' || isDetail) && [401, 403, 412, 429].includes(response.status)) blocked = true;
        if (isDetail) observations.detailResponses++;
        if (isDetail && response.status === 200) requests.add(params.requestId);
      }
      if (message.method === 'Network.loadingFailed' && /^net::ERR_[A-Z_]+$/.test(params.errorText)) observations.failures.add(params.errorText);
    };
    const {targetId} = await cdp.call('Target.createTarget', {url: 'about:blank'});
    const {sessionId} = await cdp.call('Target.attachToTarget', {targetId, flatten: true});
    await cdp.call('Page.enable', {}, sessionId);
    await cdp.call('Network.enable', {maxTotalBufferSize: 4 * LIMIT, maxResourceBufferSize: LIMIT}, sessionId);
    await cdp.call('Fetch.enable', {patterns: [{urlPattern: '*', requestStage: 'Request'}], handleAuthRequests: true}, sessionId);
    await cdp.call('Page.navigate', {url: 'https://www.douyin.com/video/' + id}, sessionId);
    const deadline = Math.min(deadlineAt, Date.now() + 52000);
    while (Date.now() < deadline && !signal.aborted && !timedOut) {
      if (cdp.closed) throw new ResolveError('browser_unavailable');
      if (blocked) throw new ResolveError('platform_restricted');
      for (const requestId of requests) {
        if (seen.has(requestId)) continue;
        let body;
        try { body = await cdp.call('Network.getResponseBody', {requestId}, sessionId); } catch { continue; }
        seen.add(requestId);
        const raw = body.base64Encoded ? Buffer.from(body.body, 'base64').toString('utf8') : body.body;
        if (raw.length > LIMIT) continue;
        try {
          const data = JSON.parse(raw);
          const item = String(data.aweme_detail?.aweme_id) === id ? data.aweme_detail : null;
          if (item) return sanitizeItem(item, id);
        } catch (error) { if (error instanceof ResolveError) throw error; }
      }
      try {
        const evaluated = await cdp.call('Runtime.evaluate', {expression:
          `({render:document.querySelector('#RENDER_DATA')?.textContent||'',scripts:document.scripts.length,challenge:/验证码|安全验证|请完成验证|登录后才能观看/.test(document.body?.innerText||'')})`, returnByValue: true}, sessionId);
        const page = evaluated.result.value;
        observations.renderBytes = page?.render?.length ?? 0;
        observations.scriptCount = page?.scripts ?? 0;
        if (page?.challenge) throw new ResolveError('platform_restricted');
        if (page?.render && page.render.length <= LIMIT) {
          let data;
          try { data = JSON.parse(page.render); } catch {
            if (/^%7[bB]/.test(page.render)) try { data = JSON.parse(decodeURIComponent(page.render)); } catch {}
          }
          const item = findItem(data, id);
          if (item) return sanitizeItem(item, id);
        }
      } catch (error) { if (error instanceof ResolveError && error.code === 'platform_restricted') throw error; }
      await new Promise(resolve => setTimeout(resolve, 350));
    }
    throw new ResolveError(signal.aborted || timedOut ? 'timeout' : 'platform_restricted');
  } catch (error) {
    if (timedOut || signal.aborted) error = new ResolveError('timeout');
    if (config.diagnostics && error instanceof ResolveError) error.diagnostics = {...observations,
      blockedHosts: [...observations.blockedHosts].sort(), failures: [...observations.failures].slice(0, 12)};
    throw error;
  } finally {
    clearTimeout(timeout); signal.removeEventListener('abort', stop); stop();
    cdp?.fail();
    if (child?.pid) {
      await new Promise(resolve => setTimeout(resolve, 1100));
      try { process.kill(-child.pid, 'SIGKILL'); } catch {}
    }
    if (killTimer) clearTimeout(killTimer);
    // Only a directory returned by mkdtemp beneath this private runtime is removed.
    await rm(profile, {recursive: true, force: true});
  }
}

export function createAPI(config, resolver = resolveVideo) {
  let active = false;
  const api = http.createServer({maxHeaderSize: 8192}, async (request, response) => {
    const reply = (status, body) => {
      if (response.destroyed) return;
      const raw = JSON.stringify(body);
      response.writeHead(status, {'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(raw), 'Cache-Control': 'no-store'}); response.end(raw);
    };
    if (request.method === 'GET' && request.url === '/health') return reply(200, {ok: true});
    const actual = Buffer.from(request.headers.authorization ?? '');
    const expected = Buffer.from('Bearer ' + config.token);
    if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) return reply(401, {error: {code: 'browser_unavailable'}});
    if (request.method !== 'POST' || request.url !== '/resolve' || request.headers['content-type']?.split(';')[0] !== 'application/json') return reply(400, {error: {code: 'platform_restricted'}});
    if (active) return reply(429, {error: {code: 'browser_busy'}});
    let raw = '';
    try {
      for await (const chunk of request) { raw += chunk; if (Buffer.byteLength(raw) > 4096) return reply(413, {error: {code: 'platform_restricted'}}); }
      const body = JSON.parse(raw);
      if (!body || Object.keys(body).length !== 1 || typeof body.video_id !== 'string' || !ID.test(body.video_id)) return reply(400, {error: {code: 'platform_restricted'}});
      // Body reads await; another request may have reserved the single slot meanwhile.
      if (active) return reply(429, {error: {code: 'browser_busy'}});
      active = true;
      const controller = new AbortController();
      response.on('close', () => { if (!response.writableEnded) controller.abort(); });
      try { reply(200, {item: await resolver(body.video_id, config, controller.signal)}); }
      finally { active = false; }
    } catch (error) {
      const code = error instanceof ResolveError ? error.code : 'browser_unavailable';
      reply(502, {error: {code}, ...(config.diagnostics && error.diagnostics ? {diagnostics: error.diagnostics} : {})});
    }
  });
  api.headersTimeout = 10000; api.requestTimeout = 10000; api.keepAliveTimeout = 2000;
  api.maxConnections = 8;
  return api;
}

async function main() {
  const runtime = process.env.MNOTE_BROWSER_RUNTIME ?? '/run/mnote-transcription-browser';
  const hosts = new Set([...BASE_HOSTS, ...(process.env.MNOTE_BROWSER_EXTRA_HOSTS ?? '').split(',').filter(Boolean)]);
  for (const host of hosts) if (!/^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$/.test(host) || host.includes('..') || net.isIP(host)) throw new Error('invalid configuration');
  const config = {runtime, hosts, chrome: process.env.MNOTE_BROWSER_CHROME ?? '/opt/mnote-transcription-browser/chromium/chrome',
    token: process.env.MNOTE_TRANSCRIPT_BROWSER_TOKEN ?? '', proxyPassword: process.env.MNOTE_BROWSER_PROXY_PASSWORD ?? '',
    diagnostics: process.env.MNOTE_BROWSER_DIAGNOSTICS === '1'};
  if (config.token.length < 32 || config.proxyPassword.length < 32 || !existsSync(config.chrome)) throw new Error('invalid configuration');
  // The only TCP listener lives inside PrivateNetwork. Its upstream is a pathname Unix socket.
  const bridge = net.createServer(client => {
    const upstream = net.connect('/run/mnote-transcription-egress/proxy.sock');
    client.setTimeout(65000, () => client.destroy()); upstream.setTimeout(65000, () => upstream.destroy());
    client.on('error', () => upstream.destroy()); upstream.on('error', () => client.destroy());
    client.on('close', () => upstream.destroy()); upstream.on('close', () => client.destroy());
    client.pipe(upstream); upstream.pipe(client);
  });
  bridge.maxConnections = 16;
  await new Promise((resolve, reject) => { bridge.once('error', reject); bridge.listen(8794, '127.0.0.1', resolve); });
  const socketPath = path.join(runtime, 'api.sock');
  try { const existing = await lstat(socketPath); if (!existing.isSocket()) throw new Error('invalid runtime'); await unlink(socketPath); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  const api = createAPI(config);
  await new Promise((resolve, reject) => { api.once('error', reject); api.listen(socketPath, resolve); });
  await chmod(socketPath, 0o666);
}
if (process.argv[1] === fileURLToPath(import.meta.url)) main().catch(() => { process.exitCode = 1; });
