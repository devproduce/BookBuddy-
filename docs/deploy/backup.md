# Backups and restore drill

PRD §15 target: **backups at least daily, and one tested restore.** A backup you have never restored is a hope, not a
backup.

## What needs backing up

| Data | Where | Backup? |
| --- | --- | --- |
| Users, books metadata, conversations, messages, memories, jobs, `llm_usage`, `book_chunks` | MongoDB Atlas (`bookbuddy` DB) | **Yes** — `mongodump` |
| Uploaded PDFs | `UPLOAD_DIR` | No — transient, deleted after ingestion. The original PDFs stay on the user's device |
| `/etc/bookbuddy/bookbuddy.env` | EC2 | Yes, **separately**, in a password manager (not in the DB backup) |
| Embedding model files | `/opt/bookbuddy/models` | Keep the download source/checksum; they are re-downloadable |
| Code | git | Already versioned |

`book_chunks` (text + 384-d vectors) is the bulk of the data and can be rebuilt by re-ingesting books, but restoring
it is far faster. If size is a concern, set `EXCLUDE_COLLECTIONS="llm_usage"` (and accept re-ingesting books) — never
exclude `users`, `conversations`, `messages` or `user_memories`.

## Option A — `mongodump` script (works on every Atlas tier, including free M0)

Atlas **M0/Flex free tiers do not include Atlas-managed backups**, so use [`scripts/backup-mongo.sh`](../../scripts/backup-mongo.sh):
`mongodump --archive --gzip` → encrypt with **age** (or gpg) → local staging copy → optional copy to S3 → prune local
copies older than `RETENTION_DAYS`.

### Install tooling on the EC2 host

```bash
# MongoDB Database Tools (mongodump/mongorestore): download the package for your distro/arch from
# https://www.mongodb.com/try/download/database-tools  and install it, e.g.:
sudo apt-get install -y ./mongodb-database-tools-*.deb
sudo apt-get install -y age awscli          # age for encryption; awscli only if you copy to S3
```

### Create an encryption key pair (do this on your laptop, not the server)

```bash
age-keygen -o bookbuddy-backup-key.txt     # prints:  Public key: age1xxxxxxxx...
```

- Put the **public key** (`age1...`) on the server as `AGE_RECIPIENT`. It can encrypt but not decrypt, so a hacked
  server cannot read old backups.
- Store the **private key file** (`bookbuddy-backup-key.txt`) in at least two places off the server (password manager
  plus an offline copy). **Lose it and the backups are unreadable; leak it and they are public.**

### Configure and schedule

`/etc/bookbuddy/backup.env` (`chmod 600`, owner `root`):

```ini
MONGODB_URI=mongodb+srv://bookbuddy_backup:URL_ENCODED_PASSWORD@cluster0.xxxxx.mongodb.net/bookbuddy
AGE_RECIPIENT=age1xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
BACKUP_DIR=/var/backups/bookbuddy
RETENTION_DAYS=7
S3_URI=s3://YOUR-BACKUP-BUCKET/mongo
HEALTHCHECK_URL=https://hc-ping.com/your-uuid
```

Use a **separate, read-only Atlas user** (`read` on `bookbuddy`) for backups. The URI may not contain `"` or `\`.

```bash
sudo install -m 750 scripts/backup-mongo.sh /usr/local/bin/bookbuddy-backup
sudo install -d -m 700 /var/backups/bookbuddy
```

Cron (`sudo crontab -e`) — daily at 02:30 UTC, off-peak and after the Groq quota reset at 00:00 UTC:

```cron
30 2 * * * set -a; . /etc/bookbuddy/backup.env; set +a; /usr/local/bin/bookbuddy-backup >> /var/log/bookbuddy-backup.log 2>&1
```

Or use a systemd timer if you prefer logs in the journal (`OnCalendar=*-*-* 02:30:00 UTC`, `Persistent=true`).

Run it once by hand and confirm the files exist:

```bash
sudo bash -c 'set -a; . /etc/bookbuddy/backup.env; set +a; /usr/local/bin/bookbuddy-backup'
ls -lh /var/backups/bookbuddy        # bookbuddy-<timestamp>.archive.gz.age  and  .sha256
aws s3 ls s3://YOUR-BACKUP-BUCKET/mongo/
```

### Off-box, encrypted copy

The local copy shares a failure domain with the instance, so the **S3 copy (or any other off-box location) is the one
that counts.** Recommended bucket settings:

- Block all public access; default encryption on (SSE-S3 or SSE-KMS); **versioning on**.
- Lifecycle rule: expire current objects after 30 days (daily) and noncurrent versions after 7 days; optionally
  transition older ones to Glacier Instant Retrieval.
- A **different AWS account or at least a bucket the instance role cannot delete from** (grant `s3:PutObject` only,
  no `s3:DeleteObject`), so a compromised server cannot erase its own backups. Deletion happens through the
  lifecycle rule.
- Files are already age-encrypted before upload; SSE is a second layer, not a substitute.

A free alternative to S3: `rclone copy` the encrypted file to a personal cloud drive. Because the file is encrypted
with a key the cloud provider does not have, that is acceptable.

## Option B — Atlas Cloud Backup (paid tiers M10+)

If you upgrade, enable **Cloud Backup** in Atlas (continuous snapshots, point-in-time restore, snapshot retention
policy). It is excellent, but keep the encrypted `mongodump` as well: it is portable, independent of the Atlas
account, and the only thing that also works on M0/Flex.

## Retention suggestion

| Copy | Keep |
| --- | --- |
| Local staging (`/var/backups/bookbuddy`) | 7 days (`RETENTION_DAYS`) |
| Off-box daily | 30 days |
| Off-box monthly (copy the first of the month to a `monthly/` prefix) | 12 months |

## Restore drill checklist

Do this **before launch**, then **quarterly** and after any change to the backup script, keys, or Atlas setup.
Record the date and how long it took (that is your real recovery time).

Preparation

- [ ] Fetch the newest off-box backup **from S3** (not the local copy) to a scratch machine or the server
- [ ] Have the age **private key** available (this proves you can actually get at it)
- [ ] Have a **scratch target**: a separate Atlas free cluster, a local `mongod`, or the `mongo` service in
      `docker-compose.yml`. Never practise by overwriting production.

Restore

- [ ] Verify the checksum: `sha256sum -c bookbuddy-<ts>.archive.gz.age.sha256`
- [ ] Run the restore script into a scratch database:
      ```bash
      export MONGODB_URI='mongodb://localhost:27017'            # scratch target
      export AGE_IDENTITY=./bookbuddy-backup-key.txt
      ./scripts/restore-mongo.sh bookbuddy-<ts>.archive.gz.age   # restores into bookbuddy_restore_test
      ```
- [ ] It exits 0 and `mongorestore` reports no failures

Verify

- [ ] Compare document counts with production for `users`, `books`, `conversations`, `messages`,
      `user_memories`, `book_chunks` (small differences are expected for anything written since the backup):
      ```js
      // mongosh
      ['users','books','conversations','messages','user_memories','book_chunks','jobs_queue','llm_usage']
        .forEach(c => print(c, db.getSiblingDB('bookbuddy_restore_test')[c].countDocuments()))
      ```
- [ ] Indexes exist (the app recreates them at start via `auto-index-creation`); the Atlas Vector Search index has to
      be recreated manually on a *new* cluster (`docs/spikes/atlas-vector-index.json`)
- [ ] Boot a **local** backend against the restored DB (`MONGODB_URI=.../bookbuddy_restore_test`, `JOBS_ENABLED=false`
      so background workers do not call Groq), then log in with a known test account, open an old conversation,
      list memories, and ask a question about an ingested book
- [ ] `GET /api/v1/health` shows `mongo: true`
- [ ] Drop the scratch database: `db.getSiblingDB('bookbuddy_restore_test').dropDatabase()`

Wrap-up

- [ ] Write down: backup timestamp used, size, download time, restore time, anything surprising
- [ ] Fix whatever failed and **repeat the drill** until it passes cleanly

## Disaster recovery (real incident)

1. Stop the API: `sudo systemctl stop bookbuddy` (prevents new writes while you restore).
2. Provision/choose the target Atlas cluster; recreate the DB user, allow-list the Elastic IP, recreate the Vector
   Search index.
3. `./scripts/restore-mongo.sh <backup> --target-db bookbuddy --i-know-this-overwrites` (the flag enables
   `mongorestore --drop`, replacing any existing collections in the target database).
4. Point `MONGODB_URI` at the restored cluster, start the API, run the "Verify" steps above, then announce recovery.
5. Rotate any credential that might have been exposed (JWT secret, Groq key, Atlas passwords, backup keys) if the
   cause was a compromise rather than an accident.

## Account deletion and backups

`DELETE /api/v1/account/data` removes a user's data from the live database only. Deleted data persists in backups until
they expire (≤ 30 days daily, ≤ 12 months monthly). If you promise stricter erasure, shorten retention or exclude the
monthly copies, and state this in the privacy text. After restoring a backup, **re-apply any deletions made since**.
