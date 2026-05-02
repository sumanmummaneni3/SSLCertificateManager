-- Removes the symbolic mTLS columns from the agents table.
-- The AgentCertificateAuthority issued certs whose private keys were immediately
-- discarded, making mTLS non-functional. Auth is provided by bearer agentKey +
-- HMAC + TLS fingerprint pinning which is already in place.
ALTER TABLE agents DROP COLUMN IF EXISTS client_cert_pem;
ALTER TABLE agents DROP COLUMN IF EXISTS client_cert_fingerprint;
