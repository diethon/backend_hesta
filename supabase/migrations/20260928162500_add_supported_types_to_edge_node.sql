-- Migration: Add supported_types to edge_nodes
-- Description: Supports the Edge-Driven Catalog architecture by allowing ESP32 to declare supported devices.

ALTER TABLE edge_nodes ADD COLUMN IF NOT EXISTS supported_types JSONB;
