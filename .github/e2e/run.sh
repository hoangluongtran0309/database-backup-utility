#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly COMPOSE_FILE="${SCRIPT_DIR}/compose.yml"
readonly PASSWORD='correct horse battery staple'
readonly PASSWORD_HASH="\$2a\$10\$GBoqx2dLOGXvTrLAxfpxOe8TRLOBQUfAFHKh.DYi1nWt0BRyCALTy"

for command in docker jq openssl; do
  command -v "$command" >/dev/null || { echo "Missing required command: $command" >&2; exit 1; }
done

: "${DBBACKUP_E2E_IMAGE:?Build the application image and set DBBACKUP_E2E_IMAGE}"
DBBACKUP_E2E_WORK_DIR="$(mktemp -d)"
export DBBACKUP_E2E_WORK_DIR
export DBBACKUP_E2E_LOG_DIR="${DBBACKUP_E2E_LOG_DIR:-${DBBACKUP_E2E_WORK_DIR}/logs}"
DBBACKUP_E2E_ENCRYPTION_KEY="$(openssl rand -base64 32)"
export DBBACKUP_E2E_ENCRYPTION_KEY
export DBBACKUP_E2E_PASSWORD_HASH="$PASSWORD_HASH"
export COMPOSE_PROJECT_NAME="dbbackup-e2e-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-$$}"

mkdir -p "$DBBACKUP_E2E_WORK_DIR/sqlite" "$DBBACKUP_E2E_WORK_DIR/backups" "$DBBACKUP_E2E_LOG_DIR"
# The runtime image deliberately uses uid 10001. These disposable directories
# carry no host secrets and must be writable from that unprivileged container.
chmod 0777 "$DBBACKUP_E2E_WORK_DIR" \
  "$DBBACKUP_E2E_WORK_DIR/sqlite" \
  "$DBBACKUP_E2E_WORK_DIR/backups"

dc() {
  docker compose -f "$COMPOSE_FILE" "$@"
}

cleanup() {
  local status=$?
  set +e
  if (( status != 0 )); then
    dc ps --all >"$DBBACKUP_E2E_LOG_DIR/compose-ps.txt" 2>&1
    dc logs --no-color >"$DBBACKUP_E2E_LOG_DIR/compose.log" 2>&1
  fi
  dc down --volumes --remove-orphans >/dev/null 2>&1
  rm -rf "$DBBACKUP_E2E_WORK_DIR"
  exit "$status"
}

report_error() {
  local status=$?
  # Expected failure-path assertions temporarily disable errexit. Do not add
  # diagnostics to their captured JSON stderr.
  if [[ $- == *e* ]]; then
    echo "E2E command failed at line ${BASH_LINENO[0]} (exit ${status})" >&2
  fi
  return "$status"
}

trap cleanup EXIT
trap report_error ERR
trap 'exit 130' INT TERM

cli() {
  dc exec -T \
    -e DBBACKUP_API_USERNAME=operator \
    -e DBBACKUP_API_PASSWORD="$PASSWORD" \
    app dbbackup "$@" --output json
}

assert_json() {
  local document=$1
  local expression=$2
  jq -e "$expression" <<<"$document" >/dev/null
}

dc up -d --wait --wait-timeout 180

# Authentication failures stay JSON and never become browser redirects.
anonymous_status="$(dc exec -T app curl -sS -o /tmp/anonymous.json -w '%{http_code}' \
  http://localhost:8080/api/v1/targets)"
[[ "$anonymous_status" == 401 ]]
dc exec -T app grep -q '"code":"UNAUTHORIZED"' /tmp/anonymous.json

set +e
wrong_password="$(dc exec -T \
  -e DBBACKUP_API_USERNAME=operator \
  -e DBBACKUP_API_PASSWORD=wrong \
  app dbbackup target list --output json 2>&1)"
wrong_password_status=$?
set -e
[[ $wrong_password_status -eq 5 ]]
assert_json "$wrong_password" '.ok == false and .error.code == "UNAUTHORIZED"'

# Use the same sqlite3 binary and mount as the application; no host database
# tool and no Docker socket are involved in this E2E path.
dc exec -T app sqlite3 /var/lib/dbbackup/sqlite/source.db \
  "CREATE TABLE orders(id INTEGER PRIMARY KEY, reference TEXT NOT NULL); INSERT INTO orders VALUES (1, 'slice-30');"
dc exec -T app sqlite3 /var/lib/dbbackup/sqlite/destination.db \
  "CREATE TABLE old_data(value TEXT); INSERT INTO old_data VALUES ('replace-me');"

source_target="$(cli target add --name e2e-source --engine SQLITE --database source.db \
  --verify-after-backup false)"
destination_target="$(cli target add --name e2e-destination --engine SQLITE --database destination.db \
  --verify-after-backup false)"
assert_json "$source_target" '.ok == true and .data.engine == "SQLITE"'
assert_json "$destination_target" '.ok == true and .data.engine == "SQLITE"'
source_id="$(jq -er '.data.id' <<<"$source_target")"
destination_id="$(jq -er '.data.id' <<<"$destination_target")"

backup="$(cli backup run --target-id "$source_id")"
assert_json "$backup" '.ok == true and .data.execution.status == "SUCCEEDED"'
backup_id="$(jq -er '.data.execution.id' <<<"$backup")"
recorded_sha="$(jq -er '.data.execution.sha256' <<<"$backup")"

checksum="$(cli backup verify --id "$backup_id")"
assert_json "$checksum" '.ok == true and .data.integrity == "INTACT" and .data.recorded == .data.actual'

download="$(cli backup download --id "$backup_id" --file /tmp/e2e-artifact.gz)"
assert_json "$download" '.ok == true and .data.file == "/tmp/e2e-artifact.gz"'
downloaded_sha="$(dc exec -T app sha256sum /tmp/e2e-artifact.gz | awk '{print $1}')"
[[ "$downloaded_sha" == "$recorded_sha" ]]

verification="$(cli backup test-restore --id "$backup_id")"
assert_json "$verification" '.ok == true and .data.status == "SUCCEEDED" and .data.checkedObjects == 1'

restore="$(cli restore run \
  --backup-execution-id "$backup_id" \
  --target-id "$destination_id" \
  --confirmation e2e-destination)"
assert_json "$restore" '.ok == true and .data.status == "SUCCEEDED"'
restored_value="$(dc exec -T app sqlite3 /var/lib/dbbackup/sqlite/destination.db \
  "SELECT reference FROM orders WHERE id = 1;")"
[[ "$restored_value" == slice-30 ]]

# A queued operation that reaches FAILED must also make the waiting CLI fail.
missing_target="$(cli target add --name e2e-missing --engine SQLITE --database missing.db \
  --verify-after-backup false)"
missing_id="$(jq -er '.data.id' <<<"$missing_target")"
set +e
failed_backup="$(cli backup run --target-id "$missing_id" 2>&1)"
failed_backup_status=$?
set -e
[[ $failed_backup_status -eq 5 ]]
assert_json "$failed_backup" '.ok == true and .data.execution.status == "FAILED"'

# Corruption is reported by both byte verification and recovery verification,
# while the original successful backup remains available for the restore above.
corrupt_backup="$(cli backup run --target-id "$source_id")"
corrupt_id="$(jq -er '.data.execution.id' <<<"$corrupt_backup")"
corrupt_locator="$(jq -er '.data.execution.artifactLocator' <<<"$corrupt_backup")"
# The single quotes deliberately defer $1 expansion to the container shell.
# shellcheck disable=SC2016
dc exec -T app sh -c 'printf corrupt >> "$1"' _ "$corrupt_locator"
corrupt_checksum="$(cli backup verify --id "$corrupt_id")"
assert_json "$corrupt_checksum" '.ok == true and .data.integrity == "MISMATCH"'
set +e
corrupt_verification="$(cli backup test-restore --id "$corrupt_id" 2>&1)"
corrupt_verification_status=$?
set -e
[[ $corrupt_verification_status -eq 5 ]]
assert_json "$corrupt_verification" '.ok == true and .data.status == "FAILED"'

# A failed HTTP download never replaces the caller's existing destination.
dc exec -T app sh -c 'printf sentinel > /tmp/protected-download'
set +e
missing_download="$(cli backup download \
  --id 00000000-0000-0000-0000-000000000000 \
  --file /tmp/protected-download 2>&1)"
missing_download_status=$?
set -e
if [[ $missing_download_status -ne 3 ]]; then
  echo "Expected missing download to exit 3, got ${missing_download_status}:" >&2
  echo "$missing_download" >&2
  exit 1
fi
assert_json "$missing_download" '.ok == false and .error.code == "RESOURCE_NOT_FOUND"'
[[ "$(dc exec -T app cat /tmp/protected-download)" == sentinel ]]

echo "API/CLI E2E completed successfully"
