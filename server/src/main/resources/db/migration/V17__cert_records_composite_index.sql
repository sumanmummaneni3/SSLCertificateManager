-- Composite index to speed up the daily certificate expiry scheduler query
-- (CertificateExpiryScheduler) and the per-org certificate list API which
-- both filter by org_id and order/filter by expiry_date.
CREATE INDEX IF NOT EXISTS idx_cert_records_org_expiry
  ON certificate_records(org_id, expiry_date);
