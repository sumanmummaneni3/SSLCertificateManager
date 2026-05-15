Create the next Flyway migration file for the CertGuard server.

## What to do

The user has provided a description for a new migration: $ARGUMENTS

1. List `server/src/main/resources/db/migration/` and find the highest `V{N}__*.sql` version number.
2. The new file is `V{N+1}__{description}.sql` where `{description}` is `$ARGUMENTS` with spaces replaced by underscores and lowercased.
3. Create the file with this header and an empty body ready to edit:

```sql
-- Migration V{N+1}: {original description from $ARGUMENTS}
-- Hibernate ddl-auto=validate — add a corresponding @Entity change if needed.

```

4. Print the full path of the file that was created.
5. If $ARGUMENTS is empty, ask the user for a description before proceeding.

## Rules
- Never modify existing migration files.
- Never reuse a version number — always check the actual directory first.
- The migration directory is always `server/src/main/resources/db/migration/` relative to the repo root (`/home/msuman/git/SSLCertificateManager`).
