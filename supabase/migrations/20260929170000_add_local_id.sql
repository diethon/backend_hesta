-- Migration: Add local_id to devices
-- Description: Standardize Edge-Driven architecture by allowing devices to use local identifiers mapped to a node.

ALTER TABLE devices ADD COLUMN IF NOT EXISTS local_id VARCHAR(50);
-- Add a unique constraint to ensure a node doesn't have duplicate local IDs
ALTER TABLE devices ADD CONSTRAINT devices_node_id_local_id_key UNIQUE (node_id, local_id);
