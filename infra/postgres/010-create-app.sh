#!/usr/bin/env bash
set -euo pipefail
# Runs only on an empty PostgreSQL volume; never print either password.
psql --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=app_password="$APP_DB_PASSWORD" <<'SQL'
CREATE ROLE minhaobra WITH LOGIN PASSWORD :'app_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER DATABASE minhaobra OWNER TO minhaobra;
REVOKE ALL ON DATABASE minhaobra FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA minhaobra AUTHORIZATION minhaobra;
SQL
