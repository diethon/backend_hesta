import { useAppSelector } from '../../store/hooks';
import type { TwinLayoutGeometry } from '../../types/twinLayout';
import type { LayoutSelection } from './TwinCanvas';
import { clampRoom, moveLayoutNode, nodeKey, resizeRoom } from './layoutGeometry';
import { TwinDeviceCard } from './TwinDeviceCard';
import { TwinSensorCard } from './TwinSensorCard';
import { TwinLayoutButton } from './TwinLayoutButton';
import { DeviceGlyph, RoomGlyph, SensorGlyph } from './TwinVisualIcon';

export function TwinLayoutInspector({ geometry, selection, editable, onChange }: {
  geometry: TwinLayoutGeometry; selection: LayoutSelection | null; editable: boolean; onChange: (geometry: TwinLayoutGeometry) => void;
}) {
  const twin = useAppSelector((state) => state.twin);
  const roomsById = useAppSelector((state) => state.twin.roomsById);
  const room = selection?.kind === 'room' ? geometry.rooms.find((item) => item.roomId === selection.id) : undefined;
  const node = selection?.kind === 'node' ? geometry.nodes.find((item) => nodeKey(item) === selection.id) : undefined;
  if (!room && !node) return <p className="text-sm text-muted">Chọn một phòng hoặc đối tượng để xem chi tiết{editable ? ' và chỉnh vị trí bằng các ô phần trăm bên dưới' : ''}.</p>;
  const target = (room ?? node)!;
  const change = (field: 'x' | 'y' | 'width' | 'height', value: number) => {
    if (!Number.isFinite(value)) return;
    if (room) {
      const next = field === 'width' || field === 'height'
        ? resizeRoom(room, field === 'width' ? value : room.width, field === 'height' ? value : room.height)
        : clampRoom({ ...room, [field]: value });
      onChange({ ...geometry, rooms: geometry.rooms.map((item) => item.roomId === room.roomId ? next : item) });
    } else if (node) {
      const next = moveLayoutNode(node, field === 'x' ? value : node.x, field === 'y' ? value : node.y, geometry.rooms);
      onChange({ ...geometry, nodes: geometry.nodes.map((item) => nodeKey(item) === nodeKey(node) ? next : item) });
    }
  };
  const fields = room ? ['x', 'y', 'width', 'height'] as const : ['x', 'y'] as const;
  const labels = { x: 'Ngang (%)', y: 'Dọc (%)', width: 'Rộng (%)', height: 'Cao (%)' };
  const roomName = room ? roomsById[room.roomId]?.name ?? 'Phòng' : '';
  const device = node?.nodeType === 'DEVICE' ? twin.devicesById[node.nodeId] : undefined;
  const sensor = node?.nodeType === 'SENSOR' ? twin.sensorsById[node.nodeId] : undefined;
  return <section aria-label="Đối tượng đang chọn" className="surface-card space-y-4 p-4 sm:p-5">
    <div className="flex items-center gap-3"><span className="flex h-11 w-11 items-center justify-center rounded-xl bg-info-soft">{room ? <RoomGlyph name={roomName} size={22} /> : device ? <DeviceGlyph deviceType={device.deviceType} size={22} /> : <SensorGlyph metricType={sensor?.metricType ?? ''} size={22} />}</span><div className="min-w-0"><p className="text-xs font-semibold uppercase tracking-wider text-muted">Selected Item</p><h3 className="truncate font-semibold text-text">{room ? roomName : device?.name ?? sensor?.metricType ?? 'Đối tượng'}</h3><p className="text-xs text-muted">{room ? 'Room' : node?.nodeType === 'DEVICE' ? device?.deviceType : 'Sensor'}</p></div></div>
    {node ? <p className="text-sm text-muted">Phòng hiển thị: {node.roomId ? roomsById[node.roomId]?.name ?? 'Không còn trong dữ liệu' : 'Ngoài các phòng'}</p> : null}
    {editable ? <>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">{fields.map((field) => <label key={field} className="min-w-0 text-sm text-muted">
        {labels[field]}<input type="number" min={field === 'width' || field === 'height' ? 0.1 : 0} max={100} step={0.1}
          value={Math.round((field === 'width' || field === 'height' ? room![field] : target[field]) * 1000) / 10}
          onChange={(event) => change(field, event.currentTarget.valueAsNumber / 100)}
          className="mt-1 min-h-11 w-full rounded-xl border border-line bg-surface px-3 text-text" />
      </label>)}</div>
      <TwinLayoutButton variant="danger" onClick={() => onChange(room ? {
        rooms: geometry.rooms.filter((item) => item.roomId !== room.roomId),
        nodes: geometry.nodes.map((item) => item.roomId === room.roomId ? { ...item, roomId: null } : item),
      } : { ...geometry, nodes: geometry.nodes.filter((item) => nodeKey(item) !== nodeKey(node!)) })}>Bỏ vị trí khỏi sơ đồ</TwinLayoutButton>
      <p className="text-xs text-muted">Chỉ thay đổi vị trí hiển thị. Các phòng, thiết bị và cảm biến vẫn được giữ trong nhà.</p>
    </> : null}
    {node?.nodeType === 'DEVICE' ? <TwinDeviceCard deviceId={node.nodeId} /> : node ? <TwinSensorCard sensorId={node.nodeId} /> : null}
  </section>;
}
