#!/usr/bin/env bash
# Book Buddy - example MongoDB backup (mongodump -> gzip archive -> age/gpg encryption -> optional S3 copy).
#
# Run daily from cron or a systemd timer on the EC2 host (see docs/deploy/backup.md). This is an EXAMPLE: read it,
# adjust paths, and test a restore (scripts/restore-mongo.sh) before trusting it.
#
# Required environment (normally sourced from /etc/bookbuddy/backup.env, chmod 600):
#   MONGODB_URI          connection string of the database to dump (the same Atlas URI the app uses, or a
#                        read-only backup user's URI)
# Encryption (one is required; backups are never left unencrypted unless ALLOW_UNENCRYPTED=1):
#   AGE_RECIPIENT        age public key ("age1...") - preferred; the private key is NOT stored on the server
#   GPG_RECIPIENT        gpg key id/email already imported on the server (alternative)
# Optional:
#   BACKUP_DIR           local staging dir (default /var/backups/bookbuddy)
#   RETENTION_DAYS       delete local copies older than this (default 7)
#   S3_URI               e.g. s3://my-bookbuddy-backups/mongo  (requires the aws cli + an instance role / profile
#                        with s3:PutObject on that prefix only)
#   EXCLUDE_COLLECTIONS  space-separated list to skip, e.g. "llm_usage"
#   HEALTHCHECK_URL      URL to GET after a successful run (healthchecks.io style dead-man's switch)
#
# Exit code is non-zero on any failure so cron/systemd can alert.

set -Eeuo pipefail
umask 077

: "${MONGODB_URI:?MONGODB_URI is required}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/bookbuddy}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"
EXCLUDE_COLLECTIONS="${EXCLUDE_COLLECTIONS:-}"

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

command -v mongodump >/dev/null 2>&1 || die "mongodump not found (install mongodb-database-tools)"

if [[ -n "${AGE_RECIPIENT:-}" ]]; then
  command -v age >/dev/null 2>&1 || die "age not found"
  ENC=age; EXT=".age"
elif [[ -n "${GPG_RECIPIENT:-}" ]]; then
  command -v gpg >/dev/null 2>&1 || die "gpg not found"
  ENC=gpg; EXT=".gpg"
elif [[ "${ALLOW_UNENCRYPTED:-0}" == "1" ]]; then
  log "WARNING: writing an UNENCRYPTED backup (ALLOW_UNENCRYPTED=1). Do not copy it off-box."
  ENC=none; EXT=""
else
  die "set AGE_RECIPIENT or GPG_RECIPIENT (backups contain user data and must be encrypted)"
fi

mkdir -p "$BACKUP_DIR"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$BACKUP_DIR/bookbuddy-$STAMP.archive.gz$EXT"
TMP_CFG="$(mktemp)"
TMP_OUT="$OUT.partial"
trap 'rm -f "$TMP_CFG" "$TMP_OUT"' EXIT

# Pass the URI through a 0600 config file so the password never shows up in `ps` output or shell history.
printf 'uri: "%s"\n' "$MONGODB_URI" > "$TMP_CFG"

DUMP_ARGS=(--config="$TMP_CFG" --archive --gzip)
if [[ -n "$EXCLUDE_COLLECTIONS" ]]; then
  # The database name comes from the URI path; --nsExclude takes "db.collection" patterns.
  for c in $EXCLUDE_COLLECTIONS; do DUMP_ARGS+=(--nsExclude="*.$c"); done
fi

log "dumping to $OUT"
case "$ENC" in
  age)  mongodump "${DUMP_ARGS[@]}" | age -r "$AGE_RECIPIENT" > "$TMP_OUT" ;;
  gpg)  mongodump "${DUMP_ARGS[@]}" | gpg --batch --yes --trust-model always -e -r "$GPG_RECIPIENT" -o "$TMP_OUT" ;;
  none) mongodump "${DUMP_ARGS[@]}" > "$TMP_OUT" ;;
esac

[[ -s "$TMP_OUT" ]] || die "backup file is empty"
mv "$TMP_OUT" "$OUT"
( cd "$BACKUP_DIR" && sha256sum "$(basename "$OUT")" > "$(basename "$OUT").sha256" )
log "backup written: $(du -h "$OUT" | cut -f1)"

if [[ -n "${S3_URI:-}" ]]; then
  command -v aws >/dev/null 2>&1 || die "aws cli not found but S3_URI is set"
  aws s3 cp "$OUT" "${S3_URI%/}/$(basename "$OUT")" --only-show-errors --sse AES256
  aws s3 cp "$OUT.sha256" "${S3_URI%/}/$(basename "$OUT").sha256" --only-show-errors --sse AES256
  log "uploaded to $S3_URI"
fi

# Local retention (keep S3 retention in a bucket lifecycle rule, not here).
find "$BACKUP_DIR" -maxdepth 1 -name 'bookbuddy-*.archive.gz*' -type f -mtime "+$RETENTION_DAYS" -delete

if [[ -n "${HEALTHCHECK_URL:-}" ]] && command -v curl >/dev/null 2>&1; then
  curl -fsS --retry 3 -m 10 "$HEALTHCHECK_URL" >/dev/null || log "WARNING: healthcheck ping failed"
fi

log "done"
