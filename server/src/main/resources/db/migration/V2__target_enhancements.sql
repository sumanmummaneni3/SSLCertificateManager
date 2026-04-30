-- ============================================================
-- V2 — Target Enhancements
-- ============================================================
ALTER TABLE targets ADD COLUMN IF NOT EXISTS tags JSONB DEFAULT '[]';
