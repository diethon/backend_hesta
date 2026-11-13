// Development-only fixture server adapter; never imported by the production app.
import { StrictMode, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Provider } from 'react-redux';
import { createHashRouter, Link, Navigate, Outlet, RouterProvider } from 'react-router';
import { createAppStore } from '../src/store/store';
import { apiClient } from '../src/services/apiClient';
import { DigitalTwinPage } from '../src/components/twin/DigitalTwinPage';
import { connectionStatusChanged, homeSubscriptionChanged, realtimeEventReceived } from '../src/store/realtimeSlice';
import { parseRealtimeEvent } from '../src/realtime/realtimeTypes';
import { loadLayoutRole } from '../src/store/twinLayoutSlice';
import type { TwinLayout } from '../src/types/twinLayout';
import snapshotFixture from './fixtures/twin/twin-snapshot.json';
import sensorFixture from './fixtures/twin/twin-sensor-event.json';
import deviceFixture from './fixtures/twin/twin-device-event.json';
import healthFixture from './fixtures/twin/twin-health-event.json';
import '../src/index.css';

const store = createAppStore();
const homeId = snapshotFixture.result.homeId;
const initial: TwinLayout = { homeId, revision: 3,
  rooms: snapshotFixture.result.rooms.map((room, index) => ({ roomId: room.roomId, x: index ? 0.53 : 0.03, y: 0.04, width: 0.44, height: 0.88 })),
  nodes: [
    { nodeType: 'DEVICE', nodeId: deviceFixture.data.deviceId, roomId: snapshotFixture.result.rooms[0].roomId, x: 0.23, y: 0.35 },
    { nodeType: 'SENSOR', nodeId: sensorFixture.data.sensorId, roomId: snapshotFixture.result.rooms[1].roomId, x: 0.75, y: 0.55 },
  ],
};
const key = 'hesta-layout-fixture-only';
let saved: TwinLayout = JSON.parse(localStorage.getItem(key) ?? 'null') ?? initial;
let role: 'OWNER' | 'MEMBER' = 'OWNER';
let fail = false;
let forbidden = false;
const requests: { method?: string; url?: string; body?: unknown }[] = [];
apiClient.defaults.adapter = async (config) => {
  requests.push({ method: config.method, url: config.url, body: config.data ? JSON.parse(config.data) : undefined });
  if (config.url?.endsWith('/my-homes')) return { config, data: { code: 1000, result: [{ homeId, role }, { homeId: 'home-b', role }] }, status: 200, statusText: 'OK', headers: {} };
  const id = config.url?.includes('/home-b/') ? 'home-b' : homeId;
  let result: unknown;
  if (config.url?.endsWith('/twin-layout')) {
    if (fail) throw new Error('Fixture network error');
    if (config.method === 'put') {
      const body = JSON.parse(config.data);
      if (forbidden || role === 'MEMBER') throw { response: { status: 403, data: { code: 1005, message: 'Forbidden' } } };
      if (body.expectedRevision !== saved.revision) throw { response: { status: 409, data: { code: 1130, message: 'Revision conflict' } } };
      saved = { homeId, revision: saved.revision + 1, rooms: body.rooms, nodes: body.nodes };
      localStorage.setItem(key, JSON.stringify(saved));
    }
    result = id === homeId ? saved : { homeId: id, revision: 0, rooms: [], nodes: [] };
  } else {
    result = id === homeId ? snapshotFixture.result : { ...snapshotFixture.result, homeId: id, name: 'Nhà B', rooms: [], unassignedDevices: [], unassignedSensors: [] };
    store.dispatch(connectionStatusChanged('connected'));
    store.dispatch(homeSubscriptionChanged(id));
  }
  return { config, data: { code: 1000, result }, status: 200, statusText: 'OK', headers: {} };
};

export function LayoutFixtureShell() {
  const [count, setCount] = useState('');
  const [json, setJson] = useState('');
  const emit = (event: unknown) => store.dispatch(realtimeEventReceived(parseRealtimeEvent(event)));
  return <><details className="border-b border-line bg-warning-soft p-4 text-sm lg:ml-64"><summary>Fixture controls · Không dùng backend thật</summary><div className="mt-3 flex flex-wrap gap-4">
    <button onClick={() => setCount(`${requests.length} REST; ${requests.filter((r) => r.method === 'put').length} PUT`)}>Count requests</button><output>{count}</output>
    <button onClick={() => setJson(JSON.stringify({ layout: store.getState().twinLayout, requests }, null, 2))}>Inspect geometry / requests</button>
    <button onClick={() => emit({ ...sensorFixture, data: { ...sensorFixture.data, latestValue: 30, observedAt: '2026-09-17T10:00:00Z' }, timestamp: '2026-09-17T10:00:00Z' })}>Sensor → 30</button>
    <button onClick={() => emit({ ...deviceFixture, data: { ...deviceFixture.data, currentState: { power: 'OFF' } } })}>Device → OFF</button>
    {(['STALE', 'OFFLINE', 'ACTIVE'] as const).map((healthStatus, index) => <button key={healthStatus} onClick={() => emit({ ...healthFixture, data: { ...healthFixture.data, healthStatus, referenceTime: '2026-09-17T10:00:00Z', evaluatedAt: `2026-09-17T10:0${index + 1}:00Z` } })}>{healthStatus}</button>)}
    <button onClick={() => { saved = { ...saved, revision: saved.revision + 1 }; }}>Simulate concurrent save</button>
    <button onClick={() => { fail = !fail; }}>Toggle layout failure</button>
    <button onClick={() => { forbidden = !forbidden; }}>Toggle 403</button>
    <button onClick={() => { role = role === 'OWNER' ? 'MEMBER' : 'OWNER'; void store.dispatch(loadLayoutRole(store.getState().twinLayout.homeId ?? homeId)); }}>Toggle OWNER / MEMBER</button>
    <button onClick={() => { saved = { homeId, revision: 0, rooms: [], nodes: [] }; localStorage.setItem(key, JSON.stringify(saved)); }}>Empty fixture layout</button>
    <button onClick={() => { saved = initial; localStorage.setItem(key, JSON.stringify(saved)); }}>Restore fixture layout</button>
    <Link to={`/home/${homeId}/digital-twin`}>Home A</Link><Link to="/home/home-b/digital-twin">Home B</Link><Link to="/home">Leave Twin</Link>
  </div>{json ? <pre className="mt-3 max-h-80 overflow-auto text-xs">{json}</pre> : null}</details><Outlet /></>;
}
const router = createHashRouter([{ element: <LayoutFixtureShell />, children: [
  { path: '/home/:homeId/digital-twin', element: <DigitalTwinPage /> },
  { path: '/home', element: <p className="p-8 lg:ml-64">Home selection fixture</p> },
  { path: '*', element: <Navigate to={`/home/${homeId}/digital-twin`} replace /> },
] }]);
const root = createRoot(document.getElementById('root')!);
import.meta.hot?.dispose(() => { root.unmount(); router.dispose(); });
root.render(<StrictMode><Provider store={store}><RouterProvider router={router} /></Provider></StrictMode>);
