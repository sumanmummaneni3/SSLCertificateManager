-- V30 — Drop renewal domain tables and types from core
-- These move to certguard-renewal-service (separate PostgreSQL instance).
-- agent_jobs.renewal_id FK was dropped in V29.

DROP TABLE IF EXISTS certificate_packages CASCADE;
DROP TABLE IF EXISTS certificate_renewal_requests CASCADE;
DROP TYPE IF EXISTS renewal_status;
DROP TYPE IF EXISTS ca_provider_type;
