import assert from "node:assert/strict";

const listeners = {};
const event = (name) => ({ addListener(callback) { listeners[name] = callback; } });
const localState = {};
globalThis.chrome = {
  contextMenus: {
    onClicked: event("contextClicked"),
    async removeAll() {},
    create() {}
  },
  alarms: {
    onAlarm: event("alarm"),
    async create() {}
  },
  runtime: {
    onInstalled: event("installed"),
    onStartup: event("startup"),
    onMessage: event("message"),
    getURL(path) { return `chrome-extension://test/${path}`; }
  },
  commands: { onCommand: event("command") },
  storage: {
    local: {
      async get(defaults) { return { ...defaults, ...localState }; },
      async set(values) { Object.assign(localState, values); }
    },
    session: {
      async set() {},
      async get() { return {}; },
      async remove() {}
    }
  },
  tabs: {
    async query() { return []; },
    async create() {},
    async get() { return {}; },
    async captureVisibleTab() { return "data:image/png;base64,iVBORw0KGgo="; }
  },
  scripting: { async executeScript() { return [{ result: null }]; } }
};

const common = await import("../common.js");
assert.equal(common.normalizeServerUrl("http://127.0.0.1:8787/"), "http://127.0.0.1:8787");
assert.throws(() => common.normalizeServerUrl("file:///tmp/data"));
assert.throws(() => common.normalizeServerUrl("https://name:secret@example.test"));
assert.throws(() => common.normalizeServerUrl("http://example.test"));
assert.throws(() => common.normalizeServerUrl("https://example.test/base?token=bad"));
assert.equal(common.normalizeServerUrl("http://192.168.1.8:8787/base/"), "http://192.168.1.8:8787/base");
assert.match(common.captureId(), /^web-[0-9]+-[a-z0-9]+$/);
assert.deepEqual(
  common.dataUrlAsset("data:image/png;base64,iVBORw0KGgo="),
  { content_type: "image/png", data_base64: "iVBORw0KGgo=" }
);
const original = { id: "capture-1", assets: { original: { data_base64: "secret" } } };
assert.deepEqual(common.compactRecord(original), { id: "capture-1" });
assert.ok(original.assets);

await import("../service-worker.js");
for (const name of ["installed", "startup", "message", "contextClicked", "alarm", "command"]) {
  assert.equal(typeof listeners[name], "function", `${name} listener was not registered`);
}

console.log("browser extension module smoke tests passed");
