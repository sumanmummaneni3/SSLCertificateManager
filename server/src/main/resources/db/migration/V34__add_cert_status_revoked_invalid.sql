-- RFC 0009: Add REVOKED and INVALID to the cert_status PostgreSQL enum.
--
-- IMPORTANT: ALTER TYPE ... ADD VALUE cannot run inside a transaction on PostgreSQL.
-- This script has executeInTransaction=false set in the companion .sql.conf file.
-- Keep this script isolated from column adds (handled in V35).
ALTER TYPE cert_status ADD VALUE IF NOT EXISTS 'INVALID';
ALTER TYPE cert_status ADD VALUE IF NOT EXISTS 'REVOKED';
