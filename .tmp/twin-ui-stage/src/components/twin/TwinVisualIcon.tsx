import type { LucideIcon } from 'lucide-react';
import { Activity, Armchair, Bath, BedDouble, Camera, CookingPot, DoorClosed, Droplets, Fan, Flame, House, Lightbulb, PlugZap, Snowflake, Thermometer, Tv, Wind } from 'lucide-react';
import type { TwinDeviceSnapshotResponse } from '../../types/twin';

const roomIcons: Array<[string, LucideIcon]> = [
  ['living', Armchair], ['bed', BedDouble], ['kitchen', CookingPot], ['bath', Bath],
];

const iconFrame = {
  room: 'bg-info-soft text-primary-hover',
  device: 'bg-success-soft text-success',
  sensor: 'bg-warning-soft text-warning',
};

export function RoomGlyph({ name, size = 20 }: { name: string; size?: number }) {
  const normalized = name.toLowerCase();
  const Icon = roomIcons.find(([key]) => normalized.includes(key))?.[1] ?? House;
  return <span aria-hidden="true" className={`inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-xl ${iconFrame.room}`}><Icon size={size} strokeWidth={1.8} /></span>;
}

export function DeviceGlyph({ deviceType, size = 19 }: { deviceType: TwinDeviceSnapshotResponse['deviceType']; size?: number }) {
  const deviceIcons: Partial<Record<TwinDeviceSnapshotResponse['deviceType'], LucideIcon>> = {
    LIGHT: Lightbulb, AC: Snowflake, FAN: Fan, LOCK: DoorClosed, CAMERA: Camera, SOCKET: PlugZap, SENSOR: Activity, MICROPHONE: Wind,
  };
  const Icon = deviceIcons[deviceType] ?? Tv;
  return <span aria-hidden="true" className={`inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-xl ${iconFrame.device}`}><Icon size={size} strokeWidth={1.8} /></span>;
}

export function SensorGlyph({ metricType, size = 19 }: { metricType: string; size?: number }) {
  const normalized = metricType.toLowerCase();
  const Icon = normalized.includes('temperature') ? Thermometer
    : normalized.includes('humidity') ? Droplets
      : normalized.includes('smoke') ? Flame
        : normalized.includes('motion') ? Activity : Activity;
  return <span aria-hidden="true" className={`inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-xl ${iconFrame.sensor}`}><Icon size={size} strokeWidth={1.8} /></span>;
}
