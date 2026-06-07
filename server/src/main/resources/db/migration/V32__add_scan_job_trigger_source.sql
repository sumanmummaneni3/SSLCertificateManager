-- ============================================================
-- V32 — Add trigger_source to agent_scan_jobs (RFC 0008 §6.3)
-- ============================================================
-- Distinguishes user-triggered (FORCE) scan jobs from scheduler-triggered
-- (SCHEDULED) jobs so submitResult can choose the correct EvaluationMode.
-- Values: 'SCHEDULED' (default, sweep/system origin) | 'USER' (manual/force scan).
-- NOT NULL with a DEFAULT keeps the migration additive — existing rows and
-- new rows from unchanged call-sites get 'SCHEDULED' automatically.
-- ============================================================

ALTER TABLE agent_scan_jobs
    ADD COLUMN trigger_source VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED';
