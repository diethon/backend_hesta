// Real Chromium Pointer Events, responsive layout, navigation and persistence checks.
// Start Vite first. No test dependency is installed. Node 20 needs --experimental-websocket.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

const evidence = resolve('docs/evidence/twin-layout');
const profile = resolve('.tmp/twin-layout-chrome-' + Date.now());
await mkdir(evidence, { recursive: true });
await mkdir(profile, { recursive: true });
const chrome = spawn(process.env.TEST_CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe', [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--remote-debugging-port=0',
  `--user-data-dir=${profile}`, 'about:blank',
], { windowsHide: true, stdio: 'ignore' });
let socket;
let sequence = 0;
const pending = new Map();
const results = [];
const check = (name, condition) => { assert.ok(condition, name); results.push(name); console.log('PASS', name); };
try {
  let port;
  for (let i = 0; i < 100; i++) {
    if (chrome.exitCode !== null) throw new Error('Chrome exited before starting');
    try { port = (await readFile(resolve(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]; break; } catch { await delay(100); }
  }
  assert.ok(port, 'Chrome debugging port');
  const pages = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  socket = new WebSocket(pages.find((page) => page.type === 'page').webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }); });
  const events = [];
  socket.addEventListener('message', ({ data }) => {
    const message = JSON.parse(data);
    if (message.id) {
      const task = pending.get(message.id); if (!task) return;
      pending.delete(message.id); clearTimeout(task.timeout);
      if (message.error) task.reject(new Error(JSON.stringify(message.error))); else task.resolve(message.result);
    } else events.push(message);
  });
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++sequence;
    const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)); }, 15000);
    pending.set(id, { resolve, reject, timeout }); socket.send(JSON.stringify({ id, method, params }));
  });
  const evaluate = async (expression) => {
    const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
    return result.result.value;
  };
  const waitFor = async (expression) => {
    for (let i = 0; i < 120; i++) { if (await evaluate(expression)) return; await delay(100); }
    console.error(await evaluate('document.body.innerText'));
    console.error(JSON.stringify(events.filter((event) => event.method === 'Runtime.exceptionThrown')));
    throw new Error(`Timed out: ${expression}`);
  };
  const click = async (text) => {
    const found = await evaluate(`(() => { const e = [...document.querySelectorAll('button,a')].find(e => e.textContent.trim() === ${JSON.stringify(text)}); if (!e) return false; e.click(); return true; })()`);
    assert.ok(found, `Control exists: ${text}`); await delay(60);
  };
  const fixtureOpen = (open) => evaluate(`document.querySelector('details').open = ${open}`);
  const inspect = async () => {
    await fixtureOpen(true); await click('Inspect geometry / requests');
    return evaluate(`JSON.parse(document.querySelector('details pre').textContent)`);
  };
  const field = async (label, value) => {
    await evaluate(`(() => { const input = [...document.querySelectorAll('label')].find(e => e.textContent.trim() === ${JSON.stringify(label)}).querySelector('input'); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, ${JSON.stringify(String(value))}); input.dispatchEvent(new Event('input', { bubbles: true })); })()`);
    await delay(60);
  };
  const rect = (selector) => evaluate(`(() => { const e = document.querySelector(${JSON.stringify(selector)}); e.scrollIntoView({block:'center'}); const b = e.getBoundingClientRect(); return {x:b.x,y:b.y,width:b.width,height:b.height}; })()`);
  const screenshot = async (name, fullPage = false) => {
    await fixtureOpen(false); await evaluate('window.scrollTo(0, 0)');
    const metrics = await send('Page.getLayoutMetrics');
    const { data } = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: fullPage,
      ...(fullPage ? { clip: { x: 0, y: 0, width: metrics.cssContentSize.width, height: metrics.cssContentSize.height, scale: 1 } } : {}),
    });
    await writeFile(resolve(evidence, name + '.png'), Buffer.from(data, 'base64'));
  };
  const mouse = (type, x, y) => send('Input.dispatchMouseEvent', { type, x, y, button: 'left', buttons: type === 'mouseReleased' ? 0 : 1, clickCount: 1 });
  const drag = async (selector, dx, dy, during) => {
    const b = await rect(selector);
    const x = b.x + Math.min(b.width / 2, 24), y = b.y + Math.min(b.height / 2, 18);
    await mouse('mousePressed', x, y);
    for (let i = 1; i <= 5; i++) await mouse('mouseMoved', x + dx * i / 5, y + dy * i / 5);
    if (during) await during();
    await mouse('mouseReleased', x + dx, y + dy);
    await delay(80);
  };
  await Promise.all([send('Page.enable'), send('Runtime.enable')]);
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false });
  await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
  await send('Page.navigate', { url: process.env.TEST_LAYOUT_URL ?? 'http://127.0.0.1:5173/tests/twin-layout-preview.html' });
  await waitFor(`document.querySelector('[data-node-key]') !== null`);
  check('desktop has no horizontal overflow', await evaluate('document.documentElement.scrollWidth <= innerWidth'));
  await screenshot('desktop-view');
  const initial = await inspect();
  await click('Chỉnh sửa sơ đồ');
  check('entering edit is clean', !(await inspect()).layout.dirty);
  await fixtureOpen(false);
  const initialRoom = initial.layout.confirmed.rooms[0];
  await drag(`[data-room-id="${initialRoom.roomId}"] > button`, 45, 20);
  let state = await inspect();
  check('real pointer drag moves draft room only', state.layout.draft.rooms[0].x !== initialRoom.x && state.layout.confirmed.rooms[0].x === initialRoom.x);
  const beforeResize = state.layout.draft.rooms[0].width;
  await fixtureOpen(false);
  await drag(`[data-room-id="${initialRoom.roomId}"] button[aria-label^="Đổi kích thước"]`, -50, -40);
  state = await inspect();
  check('real pointer resize changes draft dimensions', state.layout.draft.rooms[0].width < beforeResize);
  const sensor = state.layout.draft.nodes.find((node) => node.nodeType === 'SENSOR');
  await fixtureOpen(false);
  const sensorSelector = `[data-node-key="SENSOR:${sensor.nodeId}"]`;
  await drag(sensorSelector, -45, -40, async () => {
    const position = await evaluate(`document.querySelector(${JSON.stringify(sensorSelector)}).getAttribute('style')`);
    await click('Sensor → 30'); await click('Device → OFF'); await click('STALE');
    check('realtime during active pointer drag preserves preview geometry', position === await evaluate(`document.querySelector(${JSON.stringify(sensorSelector)}).getAttribute('style')`));
    check('active pointer drag displays newest sensor and health', await evaluate(`document.querySelector(${JSON.stringify(sensorSelector)}).textContent.includes('30 °C') && document.querySelector(${JSON.stringify(sensorSelector)}).textContent.includes('STALE')`));
  });
  state = await inspect();
  check('device/sensor realtime and geometry stay independent', state.layout.draft.nodes[1].x !== sensor.x && state.layout.confirmed.nodes[1].x === sensor.x);
  check('drag/resize/realtime produce no layout request', state.requests.filter((r) => r.url.endsWith('/twin-layout')).length === initial.requests.filter((r) => r.url.endsWith('/twin-layout')).length);
  await screenshot('desktop-edit-realtime');
  await click('Lưu sơ đồ'); await waitFor(`document.body.textContent.includes('Chế độ xem')`);
  state = await inspect();
  check('Save sends exactly one full PUT with expectedRevision', state.requests.filter((r) => r.method === 'put').length === 1 && state.requests.find((r) => r.method === 'put').body.expectedRevision === 3);
  check('successful save uses returned revision and clears dirty', state.layout.confirmed.revision === 4 && !state.layout.dirty && !state.layout.draft);
  const persisted = state.layout.confirmed;
  await send('Page.reload'); await waitFor(`document.querySelector('[data-node-key]') !== null`);
  check('browser refresh restores persisted fixture geometry', JSON.stringify((await inspect()).layout.confirmed) === JSON.stringify(persisted));
  await click('Chỉnh sửa sơ đồ'); await fixtureOpen(false);
  await drag(sensorSelector, 10, 15);
  await click('Sensor → 30');
  // Native confirmations are handled through CDP, exercising real page handlers.
  const clickWithDialog = async (text, accept) => {
    const start = events.length;
    const clicking = click(text);
    for (let i = 0; i < 80 && !events.slice(start).some((e) => e.method === 'Page.javascriptDialogOpening'); i++) await delay(50);
    assert.ok(events.slice(start).some((e) => e.method === 'Page.javascriptDialogOpening'), `Confirmation shown: ${text}`);
    await send('Page.handleJavaScriptDialog', { accept }); await clicking; await delay(100);
  };
  await clickWithDialog('Hủy', false);
  check('declining Cancel retains dirty draft', (await inspect()).layout.dirty);
  await clickWithDialog('Hủy', true);
  check('Cancel restores saved geometry and keeps latest realtime value', JSON.stringify((await inspect()).layout.confirmed) === JSON.stringify(persisted) && await evaluate(`document.querySelector(${JSON.stringify(sensorSelector)}).textContent.includes('30 °C')`));
  await click('Chỉnh sửa sơ đồ'); await fixtureOpen(false); await drag(sensorSelector, 12, 10);
  await clickWithDialog('Home B', false);
  check('declining home navigation keeps Home A draft', (await inspect()).layout.dirty);
  await clickWithDialog('Home B', true);
  await waitFor(`document.querySelector('h1')?.textContent === 'Nhà B'`);
  check('home switch loads B without A geometry', (await inspect()).layout.confirmed.homeId === 'home-b' && !await evaluate(`document.querySelector('[data-node-key]') !== null`));
  await click('Home A'); await waitFor(`document.querySelector('[data-node-key]') !== null`);
  await click('Chỉnh sửa sơ đồ'); await fixtureOpen(false); await drag(sensorSelector, 10, 10);
  await click('Simulate concurrent save'); await click('Lưu sơ đồ');
  await waitFor(`document.body.textContent.includes('phiên khác')`);
  check('conflict preserves work and offers explicit reload', (await inspect()).layout.dirty && await evaluate(`document.body.textContent.includes('Tải sơ đồ mới nhất')`));
  await screenshot('desktop-conflict');
  await clickWithDialog('Tải sơ đồ mới nhất', true);
  await waitFor(`document.body.textContent.includes('Chế độ xem')`);
  await click('Toggle OWNER / MEMBER');
  check('MEMBER views layout without edit control', await evaluate(`![...document.querySelectorAll('button')].some(e => e.textContent === 'Chỉnh sửa sơ đồ') && !!document.querySelector('[data-node-key]')`));
  await send('Emulation.setDeviceMetricsOverride', { width: 320, height: 844, deviceScaleFactor: 1, mobile: true });
  await fixtureOpen(false);
  await waitFor('innerWidth === 320 && document.documentElement.clientWidth === 320');
  check('320px view has no horizontal overflow', await evaluate('document.documentElement.scrollWidth <= innerWidth && innerWidth === 320'));
  await screenshot('mobile-view', true);
  await click('Toggle OWNER / MEMBER'); await click('Chỉnh sửa sơ đồ');
  await evaluate(`document.querySelector(${JSON.stringify(sensorSelector)}).click()`);
  await field('Ngang (%)', 60);
  state = await inspect();
  check('mobile numeric controls edit normalized position', state.layout.draft.nodes.find((node) => node.nodeType === 'SENSOR').x === 0.6);
  check('320px edit has no horizontal overflow', await evaluate('document.documentElement.scrollWidth <= innerWidth'));
  await screenshot('mobile-edit', true);
  await fixtureOpen(false);
  const beforeTouch = (await inspect()).layout.draft;
  await fixtureOpen(false);
  const touchRect = await rect(sensorSelector);
  await send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 1 });
  await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: touchRect.x + 20, y: touchRect.y + 20 }] });
  await send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: touchRect.x + 35, y: touchRect.y + 40 }] });
  await send('Input.dispatchTouchEvent', { type: 'touchCancel', touchPoints: [] });
  check('touch pointercancel discards only the active gesture', JSON.stringify((await inspect()).layout.draft) === JSON.stringify(beforeTouch));
  await clickWithDialog('Hủy', true);
  await click('Empty fixture layout'); await click('Tải sơ đồ mới nhất');
  await waitFor(`document.body.textContent.includes('Chưa có sơ đồ')`);
  await click('Chỉnh sửa sơ đồ');
  await click('+ Living Room'); await click('+ Bedroom');
  await click('+ Ceiling light'); await click('+ TEMPERATURE · Environment sensor');
  state = await inspect();
  check('owner places two existing rooms and exact DEVICE/SENSOR identities from empty layout', state.layout.draft.rooms.length === 2 && state.layout.draft.nodes.length === 2 && state.layout.draft.nodes[1].nodeId === sensor.nodeId && state.layout.confirmed.rooms.length === 0);
  await evaluate(`document.querySelector(${JSON.stringify(sensorSelector)}).click()`);
  await field('Ngang (%)', 98); await field('Dọc (%)', 98);
  check('numeric movement outside rooms uses null visual room', (await inspect()).layout.draft.nodes[1].roomId === null);
  await click('Bỏ vị trí khỏi sơ đồ');
  check('removing sensor placement restores unplaced palette and leaves runtime readable', await evaluate(`document.body.textContent.includes('+ TEMPERATURE · Environment sensor') && !document.querySelector(${JSON.stringify(sensorSelector)})`));
  await click('+ TEMPERATURE · Environment sensor');
  const beforeSaveCount = (await inspect()).requests.filter((r) => r.method === 'put').length;
  await fixtureOpen(false);
  await send('Page.bringToFront');
  await evaluate(`[...document.querySelectorAll('button')].find(e=>e.textContent==='Lưu sơ đồ').focus()`);
  check('Save is focusable and enabled for keyboard users', await evaluate(`document.activeElement.textContent === 'Lưu sơ đồ' && !document.activeElement.disabled`));
  await send('Emulation.setTouchEmulationEnabled', { enabled: false });
  await send('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: ' ', code: 'Space', windowsVirtualKeyCode: 32, nativeVirtualKeyCode: 32 });
  await send('Input.dispatchKeyEvent', { type: 'keyUp', key: ' ', code: 'Space', windowsVirtualKeyCode: 32, nativeVirtualKeyCode: 32 });
  await waitFor(`document.body.textContent.includes('Chế độ xem')`);
  state = await inspect();
  check('keyboard Save sends one normalized PUT for all placements', state.requests.filter((r) => r.method === 'put').length === beforeSaveCount + 1 && state.layout.confirmed.nodes.length === 2 && state.layout.confirmed.revision === 1);
  await click('Toggle layout failure'); await click('Tải sơ đồ mới nhất');
  check('layout load failure has retry UI', await evaluate(`document.body.textContent.includes('Thử tải lại sơ đồ')`));
  await click('Toggle layout failure'); await click('Thử tải lại sơ đồ');
  check('layout retry recovers without disturbing runtime', !(await inspect()).layout.error && await evaluate(`document.querySelector(${JSON.stringify(sensorSelector)}) !== null`));
  await click('Chỉnh sửa sơ đồ'); await evaluate(`document.querySelector(${JSON.stringify(sensorSelector)}).click()`); await field('Ngang (%)', 65);
  await click('Toggle 403'); await click('Lưu sơ đồ');
  check('stale OWNER receives safe 403 with draft retained', (await inspect()).layout.error.kind === 'forbidden' && (await inspect()).layout.dirty);
  await click('Toggle 403'); await clickWithDialog('Hủy', true);
  await click('Home B'); await waitFor(`document.querySelector('h1')?.textContent === 'Nhà B'`);
  await evaluate('history.back()'); await waitFor(`document.querySelector('h1')?.textContent === 'My Home'`);
  check('browser Back returns to Twin route with saved Home A layout', (await inspect()).layout.confirmed.homeId === initial.layout.homeId);
  await evaluate('history.forward()'); await waitFor(`document.querySelector('h1')?.textContent === 'Nhà B'`);
  check('browser Forward restores Home B without stale geometry', !(await inspect()).layout.confirmed.nodes.length);
  const errors = events.filter((event) => event.method === 'Runtime.exceptionThrown');
  check('browser reports no uncaught exceptions', errors.length === 0);
  await writeFile(resolve(evidence, 'browser-results.json'), JSON.stringify({ results, screenshots: ['desktop-view', 'desktop-edit-realtime', 'desktop-conflict', 'mobile-view', 'mobile-edit'], errors }, null, 2));
  await send('Browser.close');
} finally {
  socket?.close(); chrome.kill();
}
