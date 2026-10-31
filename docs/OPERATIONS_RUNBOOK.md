# Operonix — Operations Runbook

Day-2 operations for the deployed application: logs, monitoring, scaling, connections, rollback,
routine tasks. Nothing here has been executed against a real project — treat commands as verified by
review and by local equivalents, not by a deployment.

Replace `ENV`, `REGION` and `PROJECT_ID` with your values (they come from `deploy/gcp/.env`).

---

## 1. Logging

The `prod` profile switches Logback to `LoggingEventCompositeJsonEncoder`: one JSON object per line
on stdout, which Cloud Run forwards to Cloud Logging.

```json
{"timestamp":"2026-10-31T09:12:44.512Z","severity":"INFO","logger_name":"c.o.billing.InvoiceService",
 "message":"Invoice INV-2026-0042 sent","requestId":"9f2c…","tenantId":"4b7a…","userId":"d1e3…"}
```

* `severity` (not `level`) so Cloud Logging classifies entries correctly.
* `requestId` is generated per request by `RequestIdFilter` and returned in the `X-Request-Id` header,
  so a user's report maps to log lines.
* `tenantId` / `userId` come from the security context via MDC; no email, password or token is logged.
* Stack traces are included as a field, which lets Error Reporting group them.

Queries:

```bash
# everything from one request
gcloud logging read 'resource.type="cloud_run_revision"
  resource.labels.service_name="operonix-api-staging"
  jsonPayload.requestId="9f2c5d0e"' --limit 100 --format json

# errors only
gcloud logging read 'resource.type="cloud_run_revision"
  resource.labels.service_name="operonix-api-staging"
  severity>=ERROR' --limit 50 --freshness=1h

# slow / failing requests seen by Cloud Run itself
gcloud logging read 'resource.type="cloud_run_revision"
  resource.labels.service_name="operonix-api-staging"
  httpRequest.status>=500' --limit 50 --freshness=24h
```

Retention: Cloud Logging keeps 30 days by default. Increase the `_Default` bucket retention only if a
compliance requirement demands it (it is billed).

## 2. Health monitoring

| Endpoint | Meaning | Used by |
| --- | --- | --- |
| `/actuator/health/liveness` | process is alive; **do not** gate restarts on the database | Cloud Run liveness probe |
| `/actuator/health/readiness` | ready to serve: application state **and** database | Cloud Run startup probe, uptime checks |
| `/actuator/health` | aggregate (details hidden) | manual checks |

Create an uptime check and alert:

```bash
URL=$(gcloud run services describe operonix-api-production --region $REGION --format='value(status.url)')

gcloud monitoring uptime create operonix-api-production \
  --resource-type=uptime-url --resource-labels=host=${URL#https://},project_id=$PROJECT_ID \
  --path=/actuator/health/readiness \
  --check-interval=5 --timeout=10 --project=$PROJECT_ID
```

A failing readiness probe makes Cloud Run stop sending traffic to that instance; the instance is
replaced. A failing uptime check should page a human.

## 3. Error reporting

Cloud Error Reporting groups exceptions from JSON logs. After the first deploy, confirm the service
appears in **Error Reporting → All errors**; if it does not, check that the log entry has
`severity=ERROR` and a `stack_trace`/`message`. Enable notifications: Error Reporting → Configure
notifications → email/Slack on new error groups. Application-level 4xx problems (validation,
authorization) are logged at `WARN`/`INFO` and are **not** errors to page on.

## 4. Scaling

```bash
# current shape
gcloud run services describe operonix-api-production --region $REGION \
  --format='value(template.scaling, template.maxInstanceRequestConcurrency)'

# adjust (keep the connection budget in mind, §5)
gcloud run services update operonix-api-production --region $REGION \
  --min-instances=1 --max-instances=5 --concurrency=80
```

Guidance:

* `min-instances=0` — cheapest, cold start ≈ 5–10 s (JVM + Flyway check) on 1 vCPU.
* `min-instances=1` — no cold start for the first user of the day; roughly one always-on instance of
  cost. Recommended once there is real traffic.
* Keep `concurrency` around 80. Lower it (e.g. 20) if a single request is CPU- or DB-heavy; raising it
  increases per-instance memory pressure before it increases throughput.
* Scale on CPU (default) unless latency is dominated by the database; do not add request-based
  auto-scaling before checking the connection budget.

## 5. Connection budget

Each instance holds its own Hikari pool (`OPERONIX_DB_POOL`, default 5), so:

```
max_instances × OPERONIX_DB_POOL + 1 (migration job) + headroom ≤ Cloud SQL max_connections
```

Defaults: `3 × 5 + 1 = 16`. Check the instance limit and current usage:

```bash
gcloud sql instances describe operonix-postgres --format='value(settings.tier)'
# then, from a one-off migrate job shell or the console:
#   SHOW max_connections;
#   SELECT count(*), state FROM pg_stat_activity GROUP BY state;
```

If you see `FATAL: remaining connection slots are reserved`, in order: reduce `max_instances`,
reduce `OPERONIX_DB_POOL`, raise the tier, or introduce a pooler. Never leave `minimum-idle` high
across many instances.

## 6. Deployment and rollback

```bash
# deploy the current commit
./deploy/gcp/deploy.sh build && ./deploy/gcp/deploy.sh job --yes \
  && ./deploy/gcp/deploy.sh migrate && ./deploy/gcp/deploy.sh deploy --yes \
  && ./deploy/gcp/deploy.sh smoke

# list revisions
gcloud run revisions list --service operonix-api-production --region $REGION

# roll traffic back to the previous good revision (keeps the new revision available for debugging)
gcloud run services update-traffic operonix-api-production --region $REGION \
  --to-revisions=operonix-api-production-00042-abc=100
```

Rollback rules:

* Roll back the **revision** first; it takes seconds and is reversible.
* Migrations are forward-only. If the new revision fails because of a schema problem, roll back the
  revision, then write a corrective migration and deploy it. Do not revert `V1__schema.sql` or delete
  Flyway history rows.
* After a rollback, confirm the old revision is healthy (`/actuator/health/readiness`) and that login
  still works — a JWT key rotation makes old tokens invalid regardless of revision.

Canary option (lower risk for risky changes):

```bash
gcloud run services update-traffic operonix-api-production --region $REGION \
  --to-revisions=<new-revision>=10,<old-revision>=90
# watch errors for 15 minutes, then move to 100 or back to 0
```

## 7. Environment-specific configuration

| What | Where | Notes |
| --- | --- | --- |
| Non-secret config (`OPERONIX_DB_*`, `OPERONIX_DB_POOL`, `OPERONIX_API_DOCS`) | Cloud Run revision env vars (Terraform or deploy script) | Changing it creates a new revision |
| Secrets | Secret Manager → env vars by reference | `gcloud secrets versions add` then redeploy to pick up `latest` |
| Service shape (CPU, memory, probes, Cloud SQL attachment) | Terraform `run.tf`, or `gcloud run services update` | Terraform ignores image changes only |
| Image | Cloud Build + deploy script / CI | Tagged by commit short SHA |

Never change a value in the Cloud Run console without recording it in `deploy/gcp/.env`/Terraform;
console-only changes are silently undone on the next deploy.

## 8. Cost and billing alerts

```bash
# create a monthly budget with 50/80/100% alerts (also possible in the console)
gcloud billing budgets create --billing-account=<BILLING_ACCOUNT_ID> \
  --display-name="Operonix monthly" --budget-amount=100USD \
  --threshold-rule=percent=0.5 --threshold-rule=percent=0.8 --threshold-rule=percent=1.0
```

Watch for: a forgotten staging Cloud SQL instance (`gcloud sql instances list`), Artifact Registry
growth (`gcloud artifacts docker images list <repo> --include-tags`), and log volume from DEBUG-level
logging (the prod profile logs at INFO; keep it that way).

## 9. Routine tasks

| Task | Cadence | Command |
| --- | --- | --- |
| Verify backups exist | weekly | `gcloud sql backups list --instance operonix-postgres` |
| Review error groups | weekly | Error Reporting |
| Check connection usage | monthly | `pg_stat_activity` (see §5) |
| Rotate the JWT key | on suspicion / quarterly | `gcloud secrets versions add operonix-jwt-secret …` then redeploy (signs users out) |
| Rotate the DB password | on suspicion / yearly | `gcloud sql users set-password` + new secret version + redeploy |
| Prune images | automatic | Artefact Registry keeps the last 10 tags |
| Review IAM | quarterly | `gcloud projects get-iam-policy $PROJECT_ID` |
| Rehearse a restore | quarterly | [DISASTER_RECOVERY.md](DISASTER_RECOVERY.md) §4 |

## 10. Incident quick reference

| Symptom | First check | Action |
| --- | --- | --- |
| 503 from Cloud Run | `gcloud run services describe … status.conditions` | No healthy revision: read logs from the failing revision, roll back |
| 500s from the API | Cloud Logging `severity>=ERROR` | If DB-related, see connection budget; else roll back |
| Everything slow | Cloud SQL CPU, p95 latency | Raise tier or pool; check slow queries in Query Insights |
| Login fails for everyone | Was the JWT secret rotated? | Redeploy with the intended version; users must sign in again |
| SPA loads but API calls fail | `/api/v1` status in devtools | Revision env/CORS; check `OPERONIX_CORS_ORIGINS` is empty for same-origin |
| Deep links 404 | Image built without the SPA bundle | Rebuild; the Dockerfile asserts the bundle exists |