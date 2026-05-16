-- Fix OAuth token columns from VARCHAR(2048) to TEXT.
-- Microsoft access tokens can exceed 2000 characters.

ALTER TABLE auth_users
    ALTER COLUMN access_token  TYPE TEXT,
    ALTER COLUMN refresh_token TYPE TEXT;

ALTER TABLE auth_user_sessions
    ALTER COLUMN refresh_token TYPE TEXT;
