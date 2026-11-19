-- Migration: Allow Unclaimed Edge Nodes (nullable home_id)
-- Description: Allow EdgeNode to exist before being paired to a home during factory/admin creation.

ALTER TABLE edge_nodes ALTER COLUMN home_id DROP NOT NULL;
