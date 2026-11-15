#!/usr/bin/env bash
# Book Buddy - example MongoDB restore from an archive produced by scripts/backup-mongo.sh.
#
# SAFE BY DEFAULT: it restores into a *scratch* database name (bookbuddy_restore_test) so you can verify a backup
# without touching production data. To restore over the real database you must pass --target-db <name> AND
# --i-know-this-overwrites (which adds --drop).
#
# Usage:
#   MONGODB_URI=<target cluster uri> ./restore-mongo.sh <backup-file> [--target-db NAME] [--i-know-this-overwrites]
#
# Decryption:
#   *.age -> AGE_IDENTITY=/path/to/age-key.txt   (private key; keep it OFF the server, bring it only for a restore)
#   *.gpg -> uses the gpg secret key in your keyring
#   *.archive.gz (no suffix) -> already plain
#
# Requires mongorestore (mongodb-database-tools) and age or gpg as appropriate.

set -Eeuo pipefail
umask 077

usage() { sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

[[ $# -ge 1 ]] || usage
FILE="$1"; shift
TARGET_DB="bookbuddy_restore_test"
OVERWRITE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --target-db) TARGET_DB="${2:?--target-db needs a value}"; shift 2 ;;
    --i-know-this-overwrites) OVERWRITE=1; shift ;;
    -h|--help) usage ;;
    *) die "unknown argument: $1" ;;
  esac
done

: "${MONGODB_URI:?MONGODB_URI (target cluster) is required}"
[[ -f "$FILE" ]] || die "backup file not found: $FILE"
command -v mongorestore >/dev/null 2>&1 || die "mongorestore not found (install mongodb-database-tools)"

# Verify the checksum when it sits next to the file.
if [[ -f "$FILE.sha256" ]]; then
  ( cd "$(dirname "$FILE")" && sha256sum -c "$(basename "$FILE").sha256" ) || die "checksum mismatch"
else
  log "no .sha256 next to the file; skipping checksum verification"
fi

DROP_ARGS=()
if [[ "$OVERWRITE" == "1" ]]; then
  [[ "$TARGET_DB" != "bookbuddy_restore_test" ]] || die "--i-know-this-overwrites requires an explicit --target-db"
  log "WARNING: --drop is set; existing collections in '$TARGET_DB' will be replaced"
  DROP_ARGS=(--drop)
fi

# The archive records the source namespaces (e.g. bookbuddy.*); map them onto the target database.
SRC_DB="${SOURCE_DB:-bookbuddy}"

TMP_CFG="$(mktemp)"
trap 'rm -f "$TMP_CFG"' EXIT
printf 'uri: "%s"\n' "$MONGODB_URI" > "$TMP_CFG"

decrypt() {
  case "$FILE" in
    *.age) command -v age >/dev/null 2>&1 || die "age not found"
           : "${AGE_IDENTITY:?AGE_IDENTITY (path to the age private key) is required for .age files}"
           age -d -i "$AGE_IDENTITY" "$FILE" ;;
    *.gpg) command -v gpg >/dev/null 2>&1 || die "gpg not found"
           gpg --batch --decrypt "$FILE" ;;
    *)     cat "$FILE" ;;
  esac
}

log "restoring $FILE into database '$TARGET_DB' (source namespace '$SRC_DB.*')"
decrypt | mongorestore --config="$TMP_CFG" --archive --gzip \
  --nsFrom="${SRC_DB}.*" --nsTo="${TARGET_DB}.*" "${DROP_ARGS[@]}"

log "restore finished. Next: run the verification checklist in docs/deploy/backup.md (document counts, login, chat)."
log "Remember to drop the scratch DB afterwards:  mongosh \"\$MONGODB_URI\" --eval 'db.getSiblingDB(\"$TARGET_DB\").dropDatabase()'"
