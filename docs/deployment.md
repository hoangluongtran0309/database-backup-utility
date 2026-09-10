# Deployment

One image, one compose file. See
[ADR-009](adr/009-one-image-that-carries-the-mysql-client.md) for why they are
shaped this way.

## What the image contains

A JRE, the application jar, and Ubuntu's `mysql-client` — Oracle's MySQL 8.4,
not MariaDB. That distinction is load-bearing: MariaDB's `mysqldump` rejects
`--set-gtid-purged`, which this tool always passes, so every backup would fail.
Anyone changing the base image must check it again.

It runs as an unprivileged user, `dbbackup` (uid 10001), and its healthcheck
asks `/actuator/health`, so it only reports healthy once the application is up
and the metadata store answers. That endpoint needs no sign-in and says UP or
DOWN, nothing more.

## What an operator has to decide

**The encryption key.** `ENCRYPTION_SECRET_KEY` has no default and the
application will not start without it. Losing it makes every stored target
password unrecoverable; there is no rotation mechanism.

**The console password.** There is one account
([ADR-011](adr/011-one-operator-account-from-the-environment.md)):
`OPERATOR_USERNAME`, `admin` unless set, and `OPERATOR_PASSWORD_HASH`, which
has no default. It is a bcrypt hash, never the password:

```bash
docker run --rm -it httpd:2.4-alpine htpasswd -nBC 12 ""
```

prints `:$2y$12$…` — drop the leading colon. In `.env`, put it in single
quotes, `OPERATOR_PASSWORD_HASH='$2y$12$…'`; unquoted, compose reads each `$`
as the start of a variable. Anything that is not a bcrypt hash of cost 10 or
more stops the application at startup. Unlike the encryption key, losing it
costs nothing: generate a new hash and restart.

**Where the backups go.** The compose file uses a named volume, which is enough
to survive the container but not the machine. These artifacts are the reason the
tool exists — getting them somewhere else is outside this tool's job, and
whatever already backs up that host should be pointed at the volume.

**Nothing prunes them.** There is no retention policy
([ADR-008](adr/008-deleting-a-backup-takes-its-history-with-it.md)). The volume
grows until somebody deletes backups through the console.

**Reaching the databases to be backed up.** A MySQL on the Docker host is
`host.docker.internal` from inside the container; compose maps that name
explicitly because on Linux it does not otherwise exist. A MySQL elsewhere just
needs to be routable from the container.

## What it does and does not protect

Every page needs a sign-in, and every form carries a CSRF token, so neither a
visitor to port 8080 nor a page open in an operator's browser can start a
restore or delete a backup ([ADR-011](adr/011-one-operator-account-from-the-environment.md)).
What is still up to the deployment:

**No TLS.** The console speaks plain HTTP, so the password and the session
cookie cross the network in clear text unless something encrypts them. Put a
TLS-terminating reverse proxy in front, and then set:

- `SESSION_COOKIE_SECURE=true`, so the browser sends the session cookie only
  over HTTPS;
- `SERVER_FORWARD_HEADERS_STRATEGY=native`, so the application believes the
  proxy's `X-Forwarded-*` headers. Without it the application thinks every
  request arrived over plain HTTP: its redirects — to the sign-in page, and
  after every form — point at `http://`, HSTS is never sent, and the sign-in
  log shows the proxy's address instead of the client's. Only set it when the
  proxy is the only way in; otherwise a client can claim any address.

**No limit on sign-in attempts.** Each guess costs a bcrypt check, which is
slow, but nothing stops a patient attacker. Failed attempts are logged as
`Sign-in failed for '…' from <address>`; rate limiting belongs in the proxy.

**One shared account.** The history says a restore was started, not by whom.

Even signed in, this is a tool that can overwrite production data. Keep it
somewhere only trusted operators can reach — behind a VPN, or bound to a
private interface — and do not expose it to the internet.
