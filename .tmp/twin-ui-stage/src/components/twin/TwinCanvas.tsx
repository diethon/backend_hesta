import { useRef, useState, type PointerEvent } from 'react';
import { useAppSelector } from '../../store/hooks';
import type { TwinLayoutGeometry, TwinNodeLayout, TwinRoomLayout } from '../../types/twinLayout';
import { clampRoom, moveLayoutNode, nodeKey, resizeRoom } from './layoutGeometry';
import { TwinHealthBadge } from './TwinHealthBadge';
import { DeviceGlyph, RoomGlyph, SensorGlyph } from './TwinVisualIcon';

export type LayoutSelection = { kind: 'room'; id: string } | { kind: 'node'; id: string };
type Interaction = { pointerId: number; startX: number; startY: number; width: number; height: number; selection: LayoutSelection; resize: boolean; origin: TwinLayoutGeometry; preview: TwinLayoutGeometry };

export function TwinNodeSummary({ node }: { node: TwinNodeLayout }) {
  const device = useAppSelector((state) => node.nodeType === 'DEVICE' ? state.twin.devicesById[node.nodeId] : undefined);
  const sensor = useAppSelector((state) => node.nodeType === 'SENSOR' ? state.twin.sensorsById[node.nodeId] : undefined);
  if (!device && !sensor) return <span className="text-xs text-muted">Không còn trong dữ liệu</span>;
  const power = device?.currentState && typeof device.currentState === 'object' && !Array.isArray(device.currentState) ? device.currentState.power : null;
  if (device) return <span className="flex min-w-0 flex-col items-center gap-1">
    <DeviceGlyph deviceType={device.deviceType} size={20} />
    <span className="max-w-28 truncate text-center text-xs font-semibold text-text">{device.name}</span>
    <span className="text-[11px] font-medium text-muted">{power === true ? 'ON' : power === false ? 'OFF' : typeof power === 'string' ? power : device.status}</span>
    <TwinHealthBadge compact healthStatus={device.healthStatus} />
  </span>;
  return <span className="flex min-w-0 flex-col items-center gap-1">
    <SensorGlyph metricType={sensor!.metricType} size={20} />
    <span className="text-sm font-bold text-text">{sensor!.latestValue ?? '—'}{sensor!.unit ? <span className="ml-0.5 text-[10px] font-medium">{sensor!.unit}</span> : null}</span>
    <span className="max-w-24 truncate text-[11px] font-medium text-muted">{sensor!.metricType}</span>
    <TwinHealthBadge compact healthStatus={sensor!.healthStatus} />
  </span>;
}

export function TwinCanvas({ geometry, editable, selection, onSelect, onChange }: {
  geometry: TwinLayoutGeometry; editable: boolean; selection: LayoutSelection | null;
  onSelect: (selection: LayoutSelection) => void; onChange: (geometry: TwinLayoutGeometry) => void;
}) {
  const roomsById = useAppSelector((state) => state.twin.roomsById);
  const canvas = useRef<HTMLDivElement>(null);
  const interaction = useRef<Interaction | null>(null);
  const [preview, setPreview] = useState<TwinLayoutGeometry | null>(null);
  const shown = preview ?? geometry;
  const begin = (event: PointerEvent<HTMLButtonElement>, selected: LayoutSelection, resize = false) => {
    if (event.button !== 0 || interaction.current) return;
    onSelect(selected);
    if (!editable || !canvas.current) return;
    event.preventDefault();
    event.currentTarget.focus();
    const bounds = canvas.current.getBoundingClientRect();
    event.currentTarget.setPointerCapture(event.pointerId);
    interaction.current = { pointerId: event.pointerId, startX: event.clientX, startY: event.clientY, width: bounds.width, height: bounds.height, selection: selected, resize, origin: geometry, preview: geometry };
  };
  const move = (event: PointerEvent<HTMLButtonElement>) => {
    const drag = interaction.current;
    if (!drag || event.pointerId !== drag.pointerId) return;
    const dx = (event.clientX - drag.startX) / drag.width;
    const dy = (event.clientY - drag.startY) / drag.height;
    const next = { ...drag.origin };
    if (drag.selection.kind === 'room') {
      next.rooms = drag.origin.rooms.map((room) => room.roomId !== drag.selection.id ? room
        : drag.resize ? resizeRoom(room, room.width + dx, room.height + dy) : clampRoom({ ...room, x: room.x + dx, y: room.y + dy }));
    } else {
      next.nodes = drag.origin.nodes.map((node) => nodeKey(node) !== drag.selection.id ? node : moveLayoutNode(node, node.x + dx, node.y + dy, drag.origin.rooms));
    }
    drag.preview = next;
    setPreview(next);
  };
  const finish = (event: PointerEvent<HTMLButtonElement>, cancel = false) => {
    const drag = interaction.current;
    if (!drag || event.pointerId !== drag.pointerId) return;
    interaction.current = null;
    setPreview(null);
    if (!cancel) onChange(drag.preview);
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
  };
  const handlers = { onPointerMove: move, onPointerUp: (event: PointerEvent<HTMLButtonElement>) => finish(event), onPointerCancel: (event: PointerEvent<HTMLButtonElement>) => finish(event, true), onLostPointerCapture: (event: PointerEvent<HTMLButtonElement>) => finish(event, true) };
  const roomStyle = ({ x, y, width, height }: TwinRoomLayout) => ({ left: `${x * 100}%`, top: `${y * 100}%`, width: `${width * 100}%`, height: `${height * 100}%` });
  return <div ref={canvas} aria-label="Sơ đồ nhà 2D" className="twin-canvas relative isolate overflow-hidden rounded-2xl border border-line bg-surface shadow-soft">
    {shown.rooms.map((room) => {
      const selected = selection?.kind === 'room' && selection.id === room.roomId;
      const name = roomsById[room.roomId]?.name ?? 'Phòng không còn trong dữ liệu';
      const normalizedName = name.toLowerCase();
      const roomTint = normalizedName.includes('living') ? 'border-warning bg-warning-soft' : normalizedName.includes('bed') ? 'border-info bg-info-soft' : normalizedName.includes('kitchen') ? 'border-success bg-success-soft' : normalizedName.includes('bath') ? 'border-error bg-error-soft' : 'border-primary bg-info-soft';
      return <div key={room.roomId} data-room-id={room.roomId} style={roomStyle(room)} className={`absolute rounded-xl border-2 shadow-soft ${selected ? 'border-primary bg-surface ring-2 ring-primary/30' : roomTint}`}>
        <button type="button" aria-pressed={selected} aria-label={`Chọn phòng ${roomsById[room.roomId]?.name ?? room.roomId}`} onClick={() => onSelect({ kind: 'room', id: room.roomId })}
          onPointerDown={(event) => begin(event, { kind: 'room', id: room.roomId })} {...handlers}
          className={`h-full w-full overflow-hidden rounded-xl p-3 text-left align-top text-sm font-semibold ${editable ? 'touch-none cursor-move' : ''}`}>
          <span className="absolute left-3 top-3 flex items-center gap-2"><RoomGlyph name={name} size={18} /><span className="max-w-40 truncate text-base text-text">{name}</span></span>
          <span className="absolute left-3 top-12 text-xs font-medium text-muted">{Math.round(room.width * room.height * 1000) / 10} m²</span>
        </button>
        {editable ? <button type="button" aria-label={`Đổi kích thước ${roomsById[room.roomId]?.name ?? room.roomId}`}
          onClick={() => onSelect({ kind: 'room', id: room.roomId })} onPointerDown={(event) => begin(event, { kind: 'room', id: room.roomId }, true)} {...handlers}
          className="absolute bottom-0 right-0 h-11 w-11 touch-none cursor-se-resize rounded-br-xl rounded-tl-xl border border-primary bg-surface text-lg text-text">↘</button> : null}
      </div>;
    })}
    {shown.nodes.map((node) => <button type="button" key={nodeKey(node)} data-node-key={nodeKey(node)} data-x={node.x} data-y={node.y}
      aria-pressed={selection?.kind === 'node' && selection.id === nodeKey(node)}
      style={{ left: `${node.x * 100}%`, top: `${node.y * 100}%`, transform: `translate(-${node.x * 100}%, -${node.y * 100}%)` }}
      onClick={() => onSelect({ kind: 'node', id: nodeKey(node) })}
      onPointerDown={(event) => begin(event, { kind: 'node', id: nodeKey(node) })} {...handlers}
      className={`twin-canvas-node absolute z-10 rounded-2xl p-1 text-left text-xs sm:text-sm ${editable ? 'touch-none cursor-move' : ''} ${selection?.kind === 'node' && selection.id === nodeKey(node) ? 'bg-surface/90 ring-2 ring-primary/70' : ''}`}>
      <TwinNodeSummary node={node} />
    </button>)}
    {!shown.rooms.length && !shown.nodes.length ? <div className="pointer-events-none absolute inset-0 flex items-center justify-center p-6 text-center"><div><p className="text-lg font-semibold">Chưa có sơ đồ</p><p className="mt-2 text-sm text-muted">Chủ nhà có thể đặt phòng, thiết bị và cảm biến từ bảng bên dưới.</p></div></div> : null}
  </div>;
}
