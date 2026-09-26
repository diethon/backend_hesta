import type { TwinLayoutGeometry, TwinLayoutSaveRequest, TwinNodeLayout, TwinRoomLayout } from '../../types/twinLayout';

export const nodeKey = (node: Pick<TwinNodeLayout, 'nodeType' | 'nodeId'>) => `${node.nodeType}:${node.nodeId}`;
const round = (value: number) => Math.round(value * 1000) / 1000;
export const clampCoordinate = (value: number, max = 1) => round(Math.max(0, Math.min(max, Number.isFinite(value) ? value : 0)));

export function clampRoom(room: TwinRoomLayout): TwinRoomLayout {
  const width = Math.max(0.001, clampCoordinate(room.width));
  const height = Math.max(0.001, clampCoordinate(room.height));
  return { roomId: room.roomId, width, height, x: clampCoordinate(room.x, 1 - width), y: clampCoordinate(room.y, 1 - height) };
}

export function resizeRoom(room: TwinRoomLayout, width: number, height: number) {
  return { ...room, width: Math.max(0.001, clampCoordinate(width, 1 - room.x)), height: Math.max(0.001, clampCoordinate(height, 1 - room.y)) };
}

export function defaultRoom(roomId: string, count: number): TwinRoomLayout {
  return { roomId, x: round(0.04 + (count % 2) * 0.48), y: round(0.04 + (Math.floor(count / 2) % 2) * 0.46), width: 0.44, height: 0.4 };
}

// All nodes use full-canvas coordinates. Last rendered room wins an overlapping drop.
export function moveLayoutNode(node: TwinNodeLayout, x: number, y: number, rooms: TwinRoomLayout[]): TwinNodeLayout {
  const point = { x: clampCoordinate(x), y: clampCoordinate(y) };
  const target = [...rooms].reverse().find((room) => point.x >= room.x && point.y >= room.y
    && point.x <= room.x + room.width && point.y <= room.y + room.height);
  return { ...node, ...point, roomId: target?.roomId ?? null };
}

export function geometryError(geometry: TwinLayoutGeometry): string | null {
  const coordinate = (value: number) => Number.isFinite(value) && value >= 0 && value <= 1 && round(value) === value;
  if (new Set(geometry.rooms.map((room) => room.roomId)).size !== geometry.rooms.length
    || new Set(geometry.nodes.map(nodeKey)).size !== geometry.nodes.length) return 'Sơ đồ có vị trí trùng lặp.';
  for (const room of geometry.rooms) {
    if (![room.x, room.y, room.width, room.height].every(coordinate) || room.width <= 0 || room.height <= 0
      || Math.round(room.x * 1000) + Math.round(room.width * 1000) > 1000
      || Math.round(room.y * 1000) + Math.round(room.height * 1000) > 1000) return 'Phòng phải nằm trong sơ đồ và có kích thước lớn hơn 0.';
  }
  if (geometry.nodes.some((node) => !coordinate(node.x) || !coordinate(node.y))) return 'Vị trí phải nằm trong sơ đồ (0–100%).';
  return null;
}

// Explicit whitelist: runtime state and viewport pixels can never enter the PUT body.
export function layoutRequest(geometry: TwinLayoutGeometry, expectedRevision: number): TwinLayoutSaveRequest {
  return {
    expectedRevision,
    rooms: geometry.rooms.map(({ roomId, x, y, width, height }) => ({ roomId, x, y, width, height })),
    nodes: geometry.nodes.map(({ nodeType, nodeId, roomId, x, y }) => ({ nodeType, nodeId, roomId, x, y })),
  };
}

export function sameGeometry(a: TwinLayoutGeometry, b: TwinLayoutGeometry) {
  const canonical = (value: TwinLayoutGeometry) => layoutRequest({
    rooms: [...value.rooms].sort((x, y) => x.roomId.localeCompare(y.roomId)),
    nodes: [...value.nodes].sort((x, y) => nodeKey(x).localeCompare(nodeKey(y))),
  }, 0);
  return JSON.stringify(canonical(a)) === JSON.stringify(canonical(b));
}
