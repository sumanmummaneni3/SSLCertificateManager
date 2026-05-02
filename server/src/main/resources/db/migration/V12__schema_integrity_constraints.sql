-- V12: Add missing unique and foreign-key-equivalent constraints for schema integrity

-- 1. Unique constraint on targets(org_id, host, port)
--    Prevents two concurrent inserts for the same endpoint within the same org.
ALTER TABLE targets ADD CONSTRAINT uq_targets_org_host_port UNIQUE (org_id, host, port);

-- 2. Index on certificate_records(org_id)
--    org_id is a denormalized column (no FK to avoid cascade-delete ordering issues).
--    This index speeds up org-scoped certificate queries.
CREATE INDEX IF NOT EXISTS idx_cert_records_org_id ON certificate_records(org_id);

-- 3. Index on agent_scan_jobs(org_id)
--    Same rationale as certificate_records — denormalized org_id, indexed for query performance.
CREATE INDEX IF NOT EXISTS idx_scan_jobs_org_id ON agent_scan_jobs(org_id);

-- 4. Unique index on certificate_records(target_id, serial_number)
--    Prevents duplicate cert rows from concurrent FULL scans of the same target.
CREATE UNIQUE INDEX IF NOT EXISTS uq_cert_records_target_serial
    ON certificate_records(target_id, serial_number);

-- 5. Unique constraint on org_notification_channels(org_id, channel_type)
--    Prevents two ADMINs from creating duplicate channels of the same type per org.
ALTER TABLE org_notification_channels
    ADD CONSTRAINT uq_org_channel_type UNIQUE (org_id, channel_type);

-- 6. Composite index on agent_scan_jobs(agent_id, status)
--    Speeds up findPendingJobsForAgent queries which filter on both columns.
CREATE INDEX IF NOT EXISTS idx_scan_jobs_agent_status ON agent_scan_jobs(agent_id, status);
