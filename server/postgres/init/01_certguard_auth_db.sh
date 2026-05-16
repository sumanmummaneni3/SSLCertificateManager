#!/bin/bash
# NOTE: This file must have execute permissions on the VPS.
#       Run: chmod +x server/postgres/init/01_certguard_auth_db.sh
#
# Creates the separate auth-service database and its application user.
# The main certguard database and certguard_user are created automatically
# by the POSTGRES_DB / POSTGRES_USER env vars on the postgres container.
# AUTH_DB_PASSWORD is injected via the postgres container's environment block.

set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE certguard_auth;
    CREATE USER auth_user WITH ENCRYPTED PASSWORD '${AUTH_DB_PASSWORD}';
    GRANT ALL PRIVILEGES ON DATABASE certguard_auth TO auth_user;
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "certguard_auth" <<-EOSQL
    GRANT ALL ON SCHEMA public TO auth_user;
EOSQL
