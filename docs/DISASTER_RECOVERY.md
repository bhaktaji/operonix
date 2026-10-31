# Operonix — Disaster Recovery

Targets, failure scenarios and the procedures to come back. Procedures are documented from the
actual deployment configuration; **no restore has been rehearsed** (that is checklist item 6.2).

---

## 1. Targets

| Metric | Target | Mechanism |
| --- | --- | --- |
| RPO (data loss) | ≤ 5 minutes | Cloud SQL point-in-time recovery (WAL) + daily automated backups |
| RTO (service restored) | 30–60 minutes | Restore/clone the instance, redeploy a known-good revision, verify |
| Maximum tolerable outage | 4 hours | — |

The application is stateless: nothing lives on the container filesystem, so **only the database needs
recovery**. The image is reproducible from a commit (`cloudbuild.yaml`), and configuration lives in
Terraform + Secret Manager.

## 2. What can fail, and what protects it

| Failure | Impact | Protection |
| --- | --- | --- |
| Bad application revision | 5xx or broken UI | Traffic rollback to the previous revision (seconds) |
| Failed migration | New revision unhealthy | Migrations run before traffic; roll back the revision and ship a corrective migration |
| Database corruption / bad write | Wrong or lost data | PITR to a timestamp just before the event |
| Accidental table/database drop | Total data loss for the tenant set | Daily backup restore + PITR; instance deletion protection is on |
| Region outage | Full outage | Rebuild in another region from the image + backup (§6) |
| Secret loss | Cannot start | Secrets are regenerable; a rotated JWT key invalidates sessions only |
| Leaked credentials | Unauthorized access | Rotate JWT key and DB password, review logs by `tenantId`/`userId` |
| Compromised build pipeline | Malicious image | WIF-scoped deployer, no long-lived keys, image tags pinned to commits, reviewable diffs |

## 3. Backups (configured, not yet proven)

Configured in `infra/terraform/platform.tf` and by `deploy.sh sql`:

* Automated daily backups at 02:00 with 14 retained backups.
* Point-in-time recovery (WAL) enabled — restore to any second within the retention window.
* `deletion_protection = true` on the instance and in the settings.
* 10 GB SSD with automatic growth; versioned Artifact Registry keeps the last 10 images.

Verify (do this during the first week of production):

```bash
gcloud sql backups list --instance operonix-postgres
gcloud sql instances describe operonix-postgres \
  --format='value(settings.backupConfiguration)'
```

## 4. Restore procedures

### 4.1 Point-in-time restore (data error, still-running instance)

Never restore over the live instance. Clone to a point in time, inspect, then cut over.

```bash
# 1. clone to the moment before the incident
gcloud sql instances clone operonix-postgres operonix-postgres-restore \
  --point-in-time "2026-10-31T09:00:00.000Z"

# 2. inspect the clone (read-only check with a throwaway job or the console)
#    e.g. SELECT count(*) FROM invoice WHERE created_at > '...';

# 3. if the data is correct, repoint the application at the clone and verify
gcloud sql databases list --instance operonix-postgres-restore
```

Cutover options, in order of preference:

1. **Rename/swap**: promote the clone by updating `OPERONIX_DB_INSTANCE` in the deploy scripts'
   `.env`/Terraform to the clone's connection name and deploy a new revision. Fastest and reversible
   (deploy again with the old name).
2. **Copy data**: `pg_dump` the clone and restore into the live instance if only some rows are wrong.

### 4.2 Restore a daily backup into a new instance

```bash
gcloud sql backups list --instance operonix-postgres          # note the backup ID
gcloud sql instances clone operonix-postgres operonix-postgres-restore --backup-id <BACKUP_ID>
```

Then follow the cutover in §4.1.

### 4.3 Deleted instance

With deletion protection enabled this should be impossible by accident. If it happened:

```bash
gcloud sql instances list                     # confirm it is gone
gcloud sql backups list --instance operonix-postgres
```

If backups were deleted with the instance, recovery is not possible from Cloud SQL — that is why
deletion protection is mandatory in production. Optionally add a scheduled export
(`gcloud sql export sql … gs://<bucket>/exports/…`) as an independent copy; the MVP does not
configure one.

### 4.4 After any restore

1. Run migrations against the restored instance: `./deploy/gcp/deploy.sh migrate`
   (Flyway reconciles `flyway_schema_history`; a restore from an older point simply applies later
   migrations).
2. Verify: `./deploy/gcp/deploy.sh smoke`, then `cd frontend && node scripts/smoke.mjs` against the
   live URL.
3. Confirm a real user can sign in and see their customers (tenant isolation intact).
4. Record the incident, the restored timestamp and the data-loss window in `memory.md` §8.

## 5. Recovering from a bad deployment

```bash
# 1. roll traffic back to the last known-good revision
gcloud run revisions list --service operonix-api-production --region $REGION
gcloud run services update-traffic operonix-api-production --region $REGION \
  --to-revisions=<good-revision>=100

# 2. confirm health and login
./deploy/gcp/deploy.sh smoke
```

If the bad release included a migration: the schema stays where it is. Write a corrective migration
(`V2__fix_….sql`), test it locally against a copy, deploy the fix, and keep the rolled-back revision
only until the fix is live. Do not edit or delete an applied migration.

## 6. Region outage

`asia-south1` unavailable, or the project loses access to it:

1. Recreate the platform in a second region (`REGION=asia-south2` in `.env`, or a Terraform workspace
   with `region` overridden). Artifact Registry and Cloud Run are regional.
2. Push the last known-good image tag to the new region's repository
   (`docker pull … && docker tag … && docker push …` via Cloud Build).
3. Restore the database from the most recent backup into an instance in the new region
   (`gcloud sql instances restore-backup` or a cross-region clone if supported by the instance).
4. Point `.env` at the new region/instance and run `deploy migrate`, `deploy deploy`, `deploy smoke`.
5. Update DNS / the public URL handed to customers.

Cross-region recovery is **not** automated and is expected to exceed the 1-hour RTO. If regional
continuity becomes a requirement, add a read replica in another region and a documented failover.

## 7. Security incidents

| Incident | Immediate action |
| --- | --- |
| JWT key leaked | `gcloud secrets versions add operonix-jwt-secret` with a fresh key, redeploy; all sessions end |
| Database password leaked | `gcloud sql users set-password`, add a new secret version, redeploy, remove the old version |
| Deployer identity compromised | Disable the service account, revoke WIF bindings, rotate, review Cloud Build history |
| Suspected tenant data access | Query logs by `tenantId`/`userId`/`requestId`; preserve evidence before rotating anything |
| Image tampering suspected | Roll back to a known commit, rebuild from source, compare digests |

Because access tokens are short-lived (15 min) and refresh tokens rotate with reuse detection, a
session theft is detectable and revocable: deleting a user's refresh tokens ends their access at the
next refresh.

## 8. Testing the plan

Quarterly, on staging:

1. `deploy deploy` a known-good revision, note the revision name.
2. Deploy a deliberately broken revision; confirm the startup probe fails and traffic stays on the old
   revision (or roll back manually and time it).
3. Clone the database to a point in time; verify row counts against a known reference; delete the
   clone.
4. Restore a daily backup into a new instance; run `smoke`; delete the instance.
5. Record timings here and adjust the RTO if reality disagrees.

| Rehearsal date | Scenario | RTO achieved | Notes |
| --- | --- | --- | --- |
| — | — | — | not yet rehearsed |