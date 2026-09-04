import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import http from "node:http";
import test from "node:test";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";

function listen(server) {
  return new Promise((resolve) => server.listen(0, "127.0.0.1", () => resolve(server.address().port)));
}

function close(server) {
  return new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
}

function json(res, value) {
  const body = Buffer.from(JSON.stringify(value));
  res.writeHead(200, { "content-type": "application/json", "content-length": body.byteLength });
  res.end(body);
}

function waitFor(predicate, timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const check = () => predicate() ? resolve() : Date.now() >= deadline ? reject(new Error("timed out")) : setTimeout(check, 25);
    check();
  });
}

test("get_phone_state_lite returns only the foreground-lite cache", { timeout: 15000 }, async () => {
  const state = {
    updated_at_local: "2026-09-04 13:42:10", updated_at_ms: 1788500530000,
    current_app: "小红书", current_package: "com.xingin.xhs", screen_on: true,
    screen_text: "完整屏幕与历史内容", weather_state: { city: "杭州" }, wallet_state: { balance: 99 },
    guidian_state: { history: ["不应返回"] }, top_apps_today: [{ app: "小红书" }], now_state: { latitude: 30.2 }
  };
  const lite = {
    ok: true, updated_at_local: state.updated_at_local, updated_at_ms: state.updated_at_ms,
    current_app: state.current_app, current_package: state.current_package, screen_on: state.screen_on,
    screen_text_lite: "首页 | 推荐 | 搜索"
  };
  const backend = http.createServer((req, res) => {
    const url = new URL(req.url, "http://localhost");
    if (url.pathname === "/api/device/state") return json(res, { ok: true, device_id: "android-phone", state, life_state: state });
    if (url.pathname === "/api/device/state_lite") return json(res, lite);
    if (url.pathname === "/api/companion/action" || url.pathname === "/api/activity/events") return json(res, { ok: true });
    return json(res, { ok: false });
  });
  const backendPort = await listen(backend);
  const probe = http.createServer();
  const mcpPort = await listen(probe);
  await close(probe);
  const child = spawn(process.execPath, ["server.js"], {
    cwd: new URL("..", import.meta.url),
    env: { ...process.env, PORT: String(mcpPort), LINJIAN_URL: `http://127.0.0.1:${backendPort}`, LINJIAN_TOKEN: "test-token" },
    stdio: ["ignore", "pipe", "pipe"]
  });
  const output = [];
  child.stdout.on("data", (chunk) => output.push(chunk.toString()));
  child.stderr.on("data", (chunk) => output.push(chunk.toString()));
  let client;
  try {
    await waitFor(() => output.join("").includes("unified MCP listening"));
    client = new Client({ name: "phone-state-lite-test", version: "1" });
    await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${mcpPort}/mcp`)));
    const tool = (await client.listTools()).tools.find((item) => item.name === "get_phone_state_lite");
    assert.ok(tool);
    assert.equal(tool.description, "轻量读取当前前台 App、当前屏幕可见文本和屏幕状态，适合高频主动联系，不返回生活状态全量数据。");
    const full = JSON.parse((await client.callTool({ name: "get_phone_state", arguments: { device_id: "android-phone" } })).content[0].text);
    const result = await client.callTool({ name: "get_phone_state_lite", arguments: { device_id: "android-phone" } });
    assert.equal(result.isError, undefined);
    const actual = JSON.parse(result.content[0].text);
    assert.deepEqual(Object.keys(actual).sort(), ["current_app", "current_package", "ok", "screen_on", "screen_text_lite", "updated_at_local", "updated_at_ms"]);
    for (const key of ["weather_state", "calendar_state", "wallet_state", "guidian_state", "cycle_state", "media_state", "wearable_state", "known_apps", "app_gate", "top_apps_today", "state", "life_state", "current_state", "now_state", "events", "latitude", "longitude"]) {
      assert.equal(Object.hasOwn(actual, key), false, key);
    }
    for (const key of ["updated_at_local", "updated_at_ms", "current_app", "current_package"]) {
      assert.equal(actual[key], full.state[key], key);
    }
    const fullBytes = Buffer.byteLength(JSON.stringify(full));
    const liteBytes = Buffer.byteLength(JSON.stringify(actual));
    assert.ok(liteBytes < fullBytes);
    console.log(`full=${fullBytes}B lite=${liteBytes}B`, JSON.stringify(actual));
  } finally {
    await client?.close().catch(() => null);
    child.kill();
    await new Promise((resolve) => child.once("exit", resolve));
    await close(backend);
  }
});
