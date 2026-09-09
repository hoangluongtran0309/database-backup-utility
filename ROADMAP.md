# Roadmap

One vertical slice at a time: domain, adapter, use case, web, migration, tests
and documentation land together in a single commit. A slice is not started
until the previous one is finished, and nothing is built ahead of the slice
that needs it.

- [x] **1. Register and list MySQL targets.** Immutable `DatabaseTarget`,
      JPA persistence, AES-256-GCM encryption of the stored password,
      registration form and list page.
- [x] **2. Test connection to a target.** `mysql --execute="SELECT 1"` through
      a shared `ProcessRunner`; the result and MySQL's own error text are
      stored and shown.
- [x] **3. Run a full logical backup.** `mysqldump` to local disk, execution
      history, started in the background so a long dump does not hold an HTTP
      request open.
- [ ] **4. Compress the dump.** Stream `mysqldump` output through gzip.
- [ ] **5. Restore a backup into a target.** With an explicit overwrite
      confirmation, and a round-trip integration test.
- [ ] **6. Download and delete artifacts.** Deleting an execution deletes its
      file, which is also what makes a used target removable.
- [ ] **7. Package.** One Dockerfile, one compose file, quickstart.

## Deliberately out of scope

Not "later" — absent, and not to be reintroduced without a decision that
supersedes this line:

- MySQL physical backup, XtraBackup, binlog point-in-time recovery
- PostgreSQL and MongoDB as backup engines
- Differential and incremental backups, backup chains, retention cascades
- A scheduler; backups run when asked
- Notification channels; results are read from the execution history
- Cloud storage backends
- A CLI; the web console is the only inbound adapter
