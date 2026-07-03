-- RFC 0013 §9: explicit scan-source provenance for the scanSource API field.
--
-- Recorded ONLY by post-migration writes (CertificatePersistenceService.persistFull/
-- persistDelta, called from both the agent-result path and the direct/fallback path).
-- Existing rows are intentionally left NULL and are NEVER backfilled — inferring
-- provenance from the pre-existing scanned_by_agent_id column would mislabel legacy
-- private-target certs (customer-agent-scanned, scanned_by_agent_id populated since V3)
-- and legacy public-target certs (direct-scanned, scanned_by_agent_id always NULL)
-- identically, since both look like "no agent" to a naive migration. The application
-- layer omits the scanSource field entirely when this column is NULL.
CREATE TYPE scan_source_type AS ENUM ('CLOUD_SCANNER', 'CUSTOMER_AGENT');

ALTER TABLE certificate_records ADD COLUMN scan_source_type scan_source_type;
