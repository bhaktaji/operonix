# Operonix — Google Cloud deployment scripts

Operational walkthrough for `deploy.sh` (bash) and `deploy.ps1` (Windows PowerShell). They perform
the same steps; use whichever matches your shell. Architecture, sizing and rollback details are in
[docs/GOOGLE_CLOUD_DEPLOYMENT.md](../../docs/GOOGLE_CLOUD_DEPLOYMENT.md).

```
Windows PowerShell              bash / Cloud Shell / macOS
------------------------------  ------------------------------------------
.\deploy\gcp\deploy.ps1 plan    ./deploy/gcp/deploy.sh plan
```

## 0. Prerequisites

| Requirement | Notes |
| --- | --- |
| `gcloud` CLI, authenticated | `gcloud auth login` then `gcloud config set project <PROJECT_ID>` |
| A Google Cloud project with billing enabled | Creating Cloud SQL and Cloud Run is billable |
| Owner or equivalent IAM on the project | Needed once, for the bootstrap commands |
| `git` (optional) | Used only to tag the image with the commit short SHA |
| `openssl` (bash only) | Generates the JWT signing key / database password locally |

The scripts never create resources without `--yes` / `-Yes`, and always print the plan first.

## 1. Configure

```bash
cp deploy/gcp/.env.example deploy/gcp/.env     # PowerShell: Copy-Item
```

Edit `deploy/gcp/.env`: set `PROJECT_ID`, choose `REGION` (default `asia-south1`) and `ENV`
(`staging` or `production`). `.env` is git-ignored — no secret value belongs in it.

## 2. Bootstrap (once per environment)

```powershell
.\deploy\gcp\deploy.ps1 plan            # prints exactly what will be created; changes nothing
.\deploy\gcp\deploy.ps1 bootstrap -Yes  # APIs, Artifact Registry, Cloud SQL, secrets, service account
```

```bash
./deploy/gcp/deploy.sh plan
./deploy/gcp/deploy.sh bootstrap --yes
```

What bootstrap creates:

1. Enables `run`, `sqladmin`, `artifactregistry`, `cloudbuild`, `secretmanager`, `logging`,
   `monitoring` and `iam` APIs.
2. Artifact Registry Docker repository `${AR_REPO}` in `${REGION}`.
3. Cloud SQL **PostgreSQL 16** instance `${DB_INSTANCE}` (tier `${DB_TIER}`, daily backups,
   Sunday maintenance window) plus the `${DB_NAME}` database and `${DB_USER}` user. No authorized
   networks are configured: the application reaches the database through Cloud Run's built-in
   connector with IAM.
4. Secret Manager secrets `${DB_PASSWORD_SECRET}` and `${JWT_SECRET_NAME}`. The JWT key is
   generated locally (48 random bytes) and piped in; the database password is generated locally
   when the user is created. Neither value is ever printed.
5. The runtime service account `${SERVICE}-${ENV}` with only `roles/cloudsql.client`,
   `roles/secretmanager.secretAccessor`, `roles/logging.logWriter`,
   `roles/monitoring.metricWriter`.

Individual steps are available when you need to re-run just one:
`apis`, `artifacts`, `sql`, `secrets`, `service-account`.

> Rotating the database password or the JWT key is `gcloud secrets versions add ...`. The next
> deployment/revision picks the `latest` version. Rotating the JWT key invalidates live session
> tokens, so schedule it during a maintenance window.

## 3. Build and ship

```powershell
.\deploy\gcp\deploy.ps1 build       # Cloud Build: docker build -> Artifact Registry
.\deploy\gcp\deploy.ps1 job -Yes    # create/update the migrate-only Cloud Run job
.\deploy\gcp\deploy.ps1 migrate     # run Flyway against Cloud SQL and wait for completion
.\deploy\gcp\deploy.ps1 deploy -Yes # deploy the Cloud Run revision
.\deploy\gcp\deploy.ps1 smoke       # verify the live service
```

`all -Yes` runs build → job → migrate → deploy → smoke in one go. Migrations are also applied by the
service at startup (Flyway is idempotent and lock-protected), so a direct `deploy` is safe; running
`migrate` first only makes the schema change observable before traffic moves.

## 4. Verify

`smoke` checks the live URL: readiness `UP`, the SPA shell at `/`, a client-side deep link returning
the shell, `401` for an unauthenticated API call, `401` for an unknown API path (proving the API
answers it rather than the SPA shell — with a valid token the same path is a `404` JSON problem), and
`404` for `/application-prod.yml` so no packaged configuration is served.

Deeper verification of the running service:

```bash
gis="gcloud run services describe operonix-api-staging --region asia-south1 --format=value(status.url)"
cd frontend && OPERONIX_BASE_URL="$(gcloud run services describe operonix-api-staging \
  --region asia-south1 --format=value(status.url))" node scripts/smoke.mjs
```

`frontend/scripts/smoke.mjs` walks the whole lifecycle (workspace → customer → quote → job →
invoice → payment → dashboard → audit) and is the strongest end-to-end check available.

## 5. Roll back

```bash
gcloud run revisions list --service operonix-api-staging --region asia-south1
gcloud run services update-traffic operonix-api-staging \
  --region asia-south1 --to-revisions=<previous-revision>=100
```

Migrations are forward-only: roll the *application* back, then apply a corrective migration rather
than reverting the schema. See [docs/DISASTER_RECOVERY.md](../../docs/DISASTER_RECOVERY.md).

## 6. Staging vs production

Copy the pattern per environment; every name is suffixed with `${ENV}`:

| | staging | production |
| --- | --- | --- |
| `.env` | `ENV=staging` | `ENV=production` |
| Cloud Run service | `operonix-api-staging` | `operonix-api-production` |
| Cloud Run job | `operonix-migrate-staging` | `operonix-migrate-production` |
| Cloud SQL instance | shared `operonix-postgres` with a separate database, or its own instance | dedicated instance recommended |
| Min instances | `0` | `0` or `1` (see runbook) |

Keep staging on a smaller tier and with `MIN_INSTANCES=0`; production should use a dedicated Cloud
SQL instance and a maintenance window agreed with the business.

## 7. Automated deployment (GitHub Actions)

`.github/workflows/deploy.yml` performs the same build → migrate → deploy sequence using Workload
Identity Federation (no service-account keys). It needs these repository variables and secrets:

| Name | Kind | Example |
| --- | --- | --- |
| `GCP_PROJECT_ID` | variable | `my-operonix-project` |
| `GCP_REGION` | variable | `asia-south1` |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | secret | `projects/123456789/locations/global/workloadIdentityPools/github/providers/github` |
| `GCP_DEPLOY_SERVICE_ACCOUNT` | secret | `operonix-deployer@my-operonix-project.iam.gserviceaccount.com` |

Bootstrap the pool and provider once with the Terraform module in
[infra/terraform](../../infra/terraform) (`enable_github_oidc = true`), which also grants the
deployer service account only the roles a deployment needs.
