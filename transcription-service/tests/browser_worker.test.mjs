import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import {allowedURL, findItem, sanitizeItem, createAPI, ResolveError, BASE_HOSTS} from '../browser_worker.mjs';

const id = '1234567890123456789';
const item = () => ({aweme_id: id, desc: 'synthetic', aweme_type: 0, cookie: 'must-not-leak',
  video: {duration: 1234, play_addr: {url_list: ['https://media.douyinvod.com/a?token=a%2Fb'], cookie: 'never'},
    bit_rate: [{bit_rate: 2000, play_addr: {url_list: ['https://media.douyinvod.com/b']}}]}});

test('request allowlist is exact HTTPS only', () => {
  assert.equal(allowedURL('https://www.douyin.com/video/' + id, BASE_HOSTS), true);
  for (const url of ['http://www.douyin.com/a', 'https://www.douyin.com.evil.org/a', 'https://127.0.0.1/a',
    'https://www.douyin.com:444/a', 'https://user:pass@www.douyin.com/a', 'https://www.douyin.com/a#x',
    'https://evil.akamaized.net/a']) assert.equal(allowedURL(url, BASE_HOSTS), false);
});
test('target matching cannot select a recommended video', () => {
  const recommendation = {...item(), aweme_id: '9999999999999999999'};
  assert.equal(findItem({other: recommendation}, id), null);
  assert.equal(findItem({other: recommendation, requested: item()}, id).aweme_id, id);
});
test('sanitized metadata preserves URL encoding, excludes secrets, bounds arrays', () => {
  const value = item();
  value.video.play_addr.url_list.push(...Array(20).fill('https://media.douyinvod.com/extra'));
  const sanitized = sanitizeItem(value, id);
  assert.equal(sanitized.video.play_addr.url_list.length, 8);
  assert.match(sanitized.video.play_addr.url_list[0], /a%2Fb/);
  assert.doesNotMatch(JSON.stringify(sanitized), /must-not-leak|cookie|never/);
});
test('image posts, mismatched identities, invalid duration and excessive length are rejected', () => {
  for (const value of [{...item(), images: [{}]}, {...item(), aweme_type: 68}, {...item(), aweme_id: '9999999999999999999'},
    {...item(), video: {duration: 0}}, {...item(), video: {duration: 1800001}}]) assert.throws(() => sanitizeItem(value, id), ResolveError);
});

async function fixture(t, resolver) {
  const server = createAPI({token: 'x'.repeat(40)}, resolver);
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  return 'http://127.0.0.1:' + server.address().port;
}
function options(body, token = 'x'.repeat(40)) {
  return {method: 'POST', headers: {'Content-Type': 'application/json', Authorization: 'Bearer ' + token}, body: JSON.stringify(body)};
}
test('private API requires its own credential and accepts only a video ID', async t => {
  let calls = 0;
  const base = await fixture(t, async videoId => { calls++; return sanitizeItem(item(), videoId); });
  assert.equal((await fetch(base + '/resolve', options({video_id: id}, 'bad'))).status, 401);
  for (const body of [{video_id: id, owner: 'forged'}, {url: 'http://127.0.0.1/'}, {video_id: '../123'}, {video_id: 12345678901234}])
    assert.equal((await fetch(base + '/resolve', options(body))).status, 400);
  assert.equal((await fetch(base + '/resolve?x=y', options({video_id: id}))).status, 400);
  const response = await fetch(base + '/resolve', options({video_id: id}));
  assert.equal(response.status, 200); assert.equal((await response.json()).item.aweme_id, id); assert.equal(calls, 1);
});
test('only one browser runs, and client disconnection cancels work', async t => {
  let started, stopped;
  const running = new Promise(resolve => { started = resolve; });
  const canceled = new Promise(resolve => { stopped = resolve; });
  const base = await fixture(t, async (videoId, config, signal) => {
    started(); await new Promise(resolve => signal.addEventListener('abort', resolve, {once: true})); stopped();
    throw new ResolveError('timeout');
  });
  const request = http.request(base + '/resolve', {method: 'POST', headers: options({}).headers});
  request.on('error', () => {}); request.end(JSON.stringify({video_id: id}));
  await running;
  const busy = await fetch(base + '/resolve', options({video_id: id}));
  assert.equal(busy.status, 429); assert.equal((await busy.json()).error.code, 'browser_busy');
  request.destroy(); await canceled;
});
test('exceptions never expose underlying paths, tokens or raw messages', async t => {
  const base = await fixture(t, async () => { throw new Error('/secret/path?token=PRIVATE'); });
  const response = await fetch(base + '/resolve', options({video_id: id}));
  assert.equal(response.status, 502);
  assert.deepEqual(await response.json(), {error: {code: 'browser_unavailable'}});
});
test('simultaneous slow bodies cannot reserve two browser slots', async t => {
  let finish, started;
  const running = new Promise(resolve => { started = resolve; });
  const base = await fixture(t, async () => { started(); await new Promise(resolve => { finish = resolve; }); return item(); });
  const make = () => {
    let resolve;
    const result = new Promise(r => { resolve = r; });
    const request = http.request(base + '/resolve', {method: 'POST', headers: options({}).headers}, response => {
      response.resume(); resolve(response.statusCode);
    });
    request.on('error', () => {}); request.write('{');
    return {request, result};
  };
  const first = make(), second = make();
  await new Promise(resolve => setTimeout(resolve, 30));
  first.request.end('"video_id":"' + id + '"}');
  await running;
  second.request.end('"video_id":"' + id + '"}');
  assert.equal(await second.result, 429);
  finish(); assert.equal(await first.result, 200);
});
