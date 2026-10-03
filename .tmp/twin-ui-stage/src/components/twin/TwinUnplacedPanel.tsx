import { useState } from 'react';
import { useAppSelector } from '../../store/hooks';
import type { TwinLayoutGeometry, TwinNodeLayout } from '../../types/twinLayout';
import { defaultRoom, moveLayoutNode, nodeKey } from './layoutGeometry';
import { TwinLayoutButton } from './TwinLayoutButton';
import { DeviceGlyph, RoomGlyph, SensorGlyph } from './TwinVisualIcon';

export function TwinUnplacedPanel({ geometry, disabled, onChange }: { geometry: TwinLayoutGeometry; disabled: boolean; onChange: (geometry: TwinLayoutGeometry) => void }) {
  const [activeTab, setActiveTab] = useState<'rooms' | 'devices' | 'sensors'>('rooms');
  const twin = useAppSelector((state) => state.twin);
  const placedRooms = new Set(geometry.rooms.map((room) => room.roomId));
  const placedNodes = new Set(geometry.nodes.map(nodeKey));
  const rooms = twin.roomIds.filter((id) => !placedRooms.has(id));
  const devices = twin.deviceIds.filter((nodeId) => !placedNodes.has(nodeKey({ nodeType: 'DEVICE', nodeId })));
  const sensors = twin.sensorIds.filter((nodeId) => !placedNodes.has(nodeKey({ nodeType: 'SENSOR', nodeId })));
  const placeNode = (nodeType: TwinNodeLayout['nodeType'], nodeId: string) => {
    const node = moveLayoutNode({ nodeType, nodeId, roomId: null, x: 0.5, y: 0.5 }, 0.5, 0.5, geometry.rooms);
    onChange({ ...geometry, nodes: [...geometry.nodes, node] });
  };
  return <section aria-label="Chưa đặt" className="surface-card space-y-4 p-4 sm:p-5">
    <div><h3 className="font-semibold text-text">Unplaced Items</h3><p className="mt-1 text-sm text-muted">Chọn để đưa dữ liệu hiện có lên mặt bằng.</p></div>
    <div role="tablist" aria-label="Nhóm đối tượng chưa đặt" className="grid grid-cols-3 gap-1 rounded-xl bg-sidebar p-1">
      {([['rooms', 'Rooms'], ['devices', 'Devices'], ['sensors', 'Sensors']] as const).map(([id, label]) => <button key={id} type="button" role="tab" aria-selected={activeTab === id} onClick={() => setActiveTab(id)} className={`min-h-9 rounded-lg px-1 text-xs font-semibold ${activeTab === id ? 'bg-surface text-primary-hover shadow-soft' : 'text-muted hover:text-text'}`}>{label}</button>)}
    </div>
    <div className="space-y-4">
      <div className={activeTab === 'rooms' ? '' : 'hidden'}><h4 className="mb-2 text-xs font-bold uppercase tracking-wider text-muted">Phòng · {rooms.length}</h4><div className="space-y-2">{rooms.map((id) => <TwinLayoutButton key={id} disabled={disabled} leadingIcon={<RoomGlyph name={twin.roomsById[id].name} size={17} />} onClick={() => onChange({ ...geometry, rooms: [...geometry.rooms, defaultRoom(id, geometry.rooms.length)] })}>+ {twin.roomsById[id].name}</TwinLayoutButton>)}{!rooms.length ? <p className="text-sm text-muted">Đã đặt tất cả phòng.</p> : null}</div></div>
      <div className={activeTab === 'devices' ? '' : 'hidden'}><h4 className="mb-2 text-xs font-bold uppercase tracking-wider text-muted">Thiết bị · {devices.length}</h4><div className="space-y-2">{devices.map((id) => <TwinLayoutButton key={id} disabled={disabled} leadingIcon={<DeviceGlyph deviceType={twin.devicesById[id].deviceType} size={17} />} onClick={() => placeNode('DEVICE', id)}>+ {twin.devicesById[id].name}</TwinLayoutButton>)}{!devices.length ? <p className="text-sm text-muted">Đã đặt tất cả thiết bị.</p> : null}</div></div>
      <div className={activeTab === 'sensors' ? '' : 'hidden'}><h4 className="mb-2 text-xs font-bold uppercase tracking-wider text-muted">Cảm biến · {sensors.length}</h4><div className="space-y-2">{sensors.map((id) => <TwinLayoutButton key={id} disabled={disabled} leadingIcon={<SensorGlyph metricType={twin.sensorsById[id].metricType} size={17} />} onClick={() => placeNode('SENSOR', id)}>+ {twin.sensorsById[id].metricType}</TwinLayoutButton>)}{!sensors.length ? <p className="text-sm text-muted">Đã đặt tất cả cảm biến.</p> : null}</div></div>
    </div>
  </section>;
}
