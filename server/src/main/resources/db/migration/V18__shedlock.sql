-- ShedLock distributed scheduler lock table.
-- Ensures that scheduled jobs (expiry sweep, offline check, stale job reset, etc.)
-- run on exactly one replica at a time in a multi-instance deployment.
CREATE TABLE IF NOT EXISTS shedlock (
  name       VARCHAR(64)  NOT NULL PRIMARY KEY,
  lock_until TIMESTAMPTZ  NOT NULL,
  locked_at  TIMESTAMPTZ  NOT NULL,
  locked_by  VARCHAR(255) NOT NULL
);
