#!/usr/bin/env bash
#
# Operonix — Google Cloud deployment helper (bash).
#
# Run from the repository root on any machine with the gcloud CLI authenticated:
#
#   ./deploy/gcp/deploy.sh plan              # print what a full bootstrap would create
#   ./deploy/gcp/deploy.sh bootstrap --yes   # enable APIs, Artifact Registry, Cloud SQL, secrets
#   ./deploy/gcp/deploy.sh build             # build + push the image through Cloud Build
#   ./deploy/gcp/deploy.sh migrate           # run Flyway in the migrate-only Cloud Run job
#   ./deploy/gcp/deploy.sh deploy            # roll out the Cloud Run revision
#   ./deploy/gcp/deploy.sh smoke             # post-deployment checks against the live URL
#   ./deploy/gcp/deploy.sh all --yes         # build + migrate + deploy + smoke
#
# Configuration comes from deploy/gcp/.env (git-ignored; copy .env.example) or the environment.
# Commands that create billable resources require --yes and print their plan first.
# See deploy/gcp/README.md and docs/GOOGLE_CLOUD_DEPLOYMENT.md.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env"

# ------------------------------------------------------------------ configuration

# Load deploy/gcp/.env if present. Real environment variables win over the file.
if [[ -f "${ENV_FILE}" ]]; then
  while IFS= read -r line || [[ -n "${line}" ]]; do
    case "${line}" in
      ''|\#*) continue ;;
    esac
    key="${line%%=*}"
    value="${line#*=}"
    key="${key//[[:space:]]/}"
    if [[ -z "${!key:-}" ]]; then
      export "${key}=${value}"
    fi
  done < "${ENV_FILE}"
fi

PROJECT_ID="${PROJECT_ID:-$(gcloud config get-value project 2>/dev/null || true)}"
# asia-south1 is only the default; override REGION in the environment or .env.
REGION="${REGION:-asia-south1}"
ENV="${ENV:-staging}"
AR_REPO="${AR_REPO:-operonix}"
IMAGE="${IMAGE:-operonix}"
SERVICE="${SERVICE:-operonix-api}-${ENV}"
JOB="${JOB:-operonix-migrate}-${ENV}"
DB_INSTANCE="${DB_INSTANCE:-operonix-postgres}"
DB_NAME="${DB_NAME:-operonix}"
DB_USER="${DB_USER:-operonix}"
DB_TIER="${DB_TIER:-db-g1-small}"
DB_POOL="${DB_POOL:-5}"
DB_PASSWORD_SECRET="${DB_PASSWORD_SECRET:-operonix-db-password}"
JWT_SECRET_NAME="${JWT_SECRET_NAME:-operonix-jwt-secret}"
RUN_SA="${RUN_SA:-operonix-run-${ENV}@${PROJECT_ID}.iam.gserviceaccount.com}"
CPU="${CPU:-1}"
MEMORY="${MEMORY:-1Gi}"
MIN_INSTANCES="${MIN_INSTANCES:-0}"
MAX_INSTANCES="${MAX_INSTANCES:-3}"
CONCURRENCY="${CONCURRENCY:-80}"

IMAGE_REPO="${REGION}-docker.pkg.dev/${PROJECT_ID}/${AR_REPO}/${IMAGE}"
TAG="${TAG:-$(git -C "${REPO_ROOT}" rev-parse --short=7 HEAD 2>/dev/null || date +%Y%m%d%H%M%S)}"
IMAGE_URI="${IMAGE_REPO}:${TAG}"

CONFIRMED=0
COMMAND="${1:-help}"
if [[ "${2:-}" == "--yes" || "${1:-}" == "--yes" ]]; then
  CONFIRMED=1
fi

log()  { printf '\n==> %s\n' "$*"; }
warn() { printf '\n!! %s\n' "$*" >&2; }
die()  { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

require_gcloud() {
  command -v gcloud >/dev/null 2>&1 || die "gcloud CLI not found. Install it: https://cloud.google.com/sdk/docs/install"
  [[ -n "${PROJECT_ID}" ]] || die "PROJECT_ID is not set. Copy deploy/gcp/.env.example to deploy/gcp/.env."
}

require_confirmation() {
  local action="$1"
  log "Plan: ${action}"
  printf '  project      : %s\n' "${PROJECT_ID}"
  printf '  region       : %s\n' "${REGION}"
  printf '  environment  : %s\n' "${ENV}"
  printf '  service      : %s (Cloud Run)\n' "${SERVICE}"
  printf '  migration job: %s (Cloud Run job)\n' "${JOB}"
  printf '  database     : %s/%s (Cloud SQL PostgreSQL 16, tier %s)\n' "${DB_INSTANCE}" "${DB_NAME}" "${DB_TIER}"
  printf '  image repo   : %s\n' "${IMAGE_REPO}"
  if [[ "${CONFIRMED}" != "1" ]]; then
    warn "This creates or changes billable Google Cloud resources. Re-run with --yes to proceed."
    exit 1
  fi
}

gcloud_run_service_url() {
  gcloud run services describe "${SERVICE}" --region "${REGION}" --project "${PROJECT_ID}" \
    --format='value(status.url)'
}

cloudsql_connection_name() {
  printf '%s:%s:%s\n' "${PROJECT_ID}" "${REGION}" "${DB_INSTANCE}"
}

# ------------------------------------------------------------------ commands

cmd_apis() {
  log "Enabling required Google Cloud APIs"
  gcloud services enable \
    run.googleapis.com \
    sqladmin.googleapis.com \
    artifactregistry.googleapis.com \
    cloudbuild.googleapis.com \
    secretmanager.googleapis.com \
    logging.googleapis.com \
    monitoring.googleapis.com \
    iam.googleapis.com \
    --project "${PROJECT_ID}"
}

cmd_artifacts() {
  log "Ensuring Artifact Registry repository ${AR_REPO} in ${REGION}"
  if gcloud artifacts repositories describe "${AR_REPO}" --location "${REGION}" \
      --project "${PROJECT_ID}" >/dev/null 2>&1; then
    printf '  repository already exists\n'
  else
    gcloud artifacts repositories create "${AR_REPO}" \
      --repository-format=docker \
      --location="${REGION}" \
      --description="Operonix container images" \
      --project "${PROJECT_ID}"
  fi
  printf '  docker: %s\n' "${IMAGE_REPO}"
}

cmd_sql() {
  log "Ensuring Cloud SQL PostgreSQL 16 instance ${DB_INSTANCE}"
  if gcloud sql instances describe "${DB_INSTANCE}" --project "${PROJECT_ID}" >/dev/null 2>&1; then
    printf '  instance already exists\n'
  else
    # No authorized networks are configured: Cloud Run reaches the instance through its built-in
    # connector over the /cloudsql socket, authenticated by the runtime service account's IAM role.
    # A private-IP-only instance is also supported but then requires Direct VPC egress — see
    # docs/GOOGLE_CLOUD_DEPLOYMENT.md ("Networking options").
    gcloud sql instances create "${DB_INSTANCE}" \
      --project "${PROJECT_ID}" \
      --database-version=POSTGRES_16 \
      --region="${REGION}" \
      --tier="${DB_TIER}" \
      --storage-type=SSD --storage-size=10GB --storage-auto-increase \
      --backup --backup-start-time=02:00 \
      --maintenance-window-day=SUN --maintenance-window-hour=3 \
      --quiet
  fi

  log "Ensuring database ${DB_NAME} and user ${DB_USER}"
  if ! gcloud sql databases describe "${DB_NAME}" --instance "${DB_INSTANCE}" \
      --project "${PROJECT_ID}" >/dev/null 2>&1; then
    gcloud sql databases create "${DB_NAME}" --instance "${DB_INSTANCE}" --project "${PROJECT_ID}"
  else
    printf '  database already exists\n'
  fi
  if ! gcloud sql users list --instance "${DB_INSTANCE}" --project "${PROJECT_ID}" \
      --format='value(name)' | grep -qx "${DB_USER}"; then
    # The password is generated locally and written straight into Secret Manager; it is never
    # printed to the terminal or stored in this repository. gcloud has no file-based password
    # option, so the value is briefly present in the local process list; rotate it through Secret
    # Manager if that exposure matters in your environment.
    local password
    password="$(openssl rand -base64 32)"
    gcloud sql users create "${DB_USER}" --instance "${DB_INSTANCE}" \
      --password="${password}" --project "${PROJECT_ID}"
    printf '%s' "${password}" | gcloud secrets versions add "${DB_PASSWORD_SECRET}" \
      --data-file=- --project "${PROJECT_ID}" 2>/dev/null || {
        unset password
        die "user created but the password could not be stored; run './deploy/gcp/deploy.sh secrets --yes'"
      }
    unset password
  else
    printf '  user already exists\n'
  fi
}

cmd_secrets() {
  log "Ensuring Secret Manager secrets (values are never printed)"
  for secret in "${DB_PASSWORD_SECRET}" "${JWT_SECRET_NAME}"; do
    if gcloud secrets describe "${secret}" --project "${PROJECT_ID}" >/dev/null 2>&1; then
      printf '  %s exists\n' "${secret}"
    else
      gcloud secrets create "${secret}" --replication-policy=automatic --project "${PROJECT_ID}"
      printf '  %s created\n' "${secret}"
    fi
  done

  if [[ "$(gcloud secrets versions list "${JWT_SECRET_NAME}" --project "${PROJECT_ID}" \
        --format='value(state)' | grep -c ENABLED || true)" == "0" ]]; then
    log "Generating a new JWT signing key (48 random bytes)"
    openssl rand -base64 48 | gcloud secrets versions add "${JWT_SECRET_NAME}" \
      --data-file=- --project "${PROJECT_ID}"
  else
    printf '  %s already has an enabled version\n' "${JWT_SECRET_NAME}"
  fi

  if [[ "$(gcloud secrets versions list "${DB_PASSWORD_SECRET}" --project "${PROJECT_ID}" \
        --format='value(state)' | grep -c ENABLED || true)" == "0" ]]; then
    warn "Secret ${DB_PASSWORD_SECRET} has no enabled version."
    printf 'Paste the Cloud SQL password for user %s (input hidden): ' "${DB_USER}"
    local password
    read -r -s password
    printf '\n'
    printf '%s' "${password}" | gcloud secrets versions add "${DB_PASSWORD_SECRET}" \
      --data-file=- --project "${PROJECT_ID}"
    unset password
  else
    printf '  %s already has an enabled version\n' "${DB_PASSWORD_SECRET}"
  fi
}

cmd_service_account() {
  log "Ensuring least-privilege runtime service account ${RUN_SA}"
  if ! gcloud iam service-accounts describe "${RUN_SA}" --project "${PROJECT_ID}" >/dev/null 2>&1; then
    gcloud iam service-accounts create "${RUN_SA%%@*}" \
      --display-name="Operonix Cloud Run runtime (${ENV})" \
      --project "${PROJECT_ID}"
  else
    printf '  service account already exists\n'
  fi
  for role in roles/cloudsql.client roles/secretmanager.secretAccessor \
              roles/logging.logWriter roles/monitoring.metricWriter; do
    gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
      --member="serviceAccount:${RUN_SA}" --role="${role}" --condition=None --quiet >/dev/null
    printf '  granted %s\n' "${role}"
  done
}

cmd_build() {
  log "Building and pushing ${IMAGE_URI} with Cloud Build"
  gcloud builds submit "${REPO_ROOT}" \
    --config "${REPO_ROOT}/cloudbuild.yaml" \
    --substitutions="_REGION=${REGION},_AR_REPO=${AR_REPO},_IMAGE=${IMAGE},_ENV=${ENV},_DEPLOY=false" \
    --project "${PROJECT_ID}"
  # Cloud Build tags the short SHA from the submitted sources; re-tag locally for deploys from
  # an uncommitted tree so the deployed reference always matches the pushed digest.
  printf '\nImage pushed. Deploy with TAG=<sha> %s deploy\n' "$0"
}

cmd_job() {
  log "Ensuring Cloud Run migration job ${JOB}"
  local connection
  connection="$(cloudsql_connection_name)"
  local common_args=(
    --image="${IMAGE_URI}"
    --region="${REGION}"
    --project="${PROJECT_ID}"
    --service-account="${RUN_SA}"
    --add-cloudsql-instances="${connection}"
    --set-env-vars="SPRING_PROFILES_ACTIVE=prod,OPERONIX_DB_INSTANCE=${connection},OPERONIX_DB_NAME=${DB_NAME},OPERONIX_DB_USER=${DB_USER}"
    --set-secrets="OPERONIX_DB_PASSWORD=${DB_PASSWORD_SECRET}:latest,OPERONIX_JWT_SECRET=${JWT_SECRET_NAME}:latest"
    --max-retries=1
    --task-timeout=900s
    --args="--spring.main.web-application-type=none,--operonix.migrate-only=true"
    --quiet
  )
  if gcloud run jobs describe "${JOB}" --region "${REGION}" --project "${PROJECT_ID}" >/dev/null 2>&1; then
    gcloud run jobs update "${JOB}" "${common_args[@]}"
  else
    gcloud run jobs create "${JOB}" "${common_args[@]}"
  fi
}

cmd_migrate() {
  log "Running Flyway migrations in job ${JOB}"
  gcloud run jobs execute "${JOB}" --region "${REGION}" --project "${PROJECT_ID}" --wait --quiet
  printf '  migrations complete\n'
}

cmd_deploy() {
  log "Deploying Cloud Run service ${SERVICE} (${IMAGE_URI})"
  local connection
  connection="$(cloudsql_connection_name)"
  local revision_args=()
  if [[ -n "${TAG}" ]]; then
    # A deterministic revision name makes rollback and traffic pinning unambiguous.
    revision_args=(--revision-suffix="${TAG//[^a-zA-Z0-9-]/-}")
  fi
  gcloud run deploy "${SERVICE}" \
    --image="${IMAGE_URI}" \
    --region="${REGION}" \
    --project="${PROJECT_ID}" \
    --platform=managed \
    --allow-unauthenticated \
    --port=8080 \
    --cpu="${CPU}" \
    --memory="${MEMORY}" \
    --min-instances="${MIN_INSTANCES}" \
    --max-instances="${MAX_INSTANCES}" \
    --concurrency="${CONCURRENCY}" \
    --timeout=60 \
    --service-account="${RUN_SA}" \
    --add-cloudsql-instances="${connection}" \
    --set-env-vars="SPRING_PROFILES_ACTIVE=prod,OPERONIX_DB_INSTANCE=${connection},OPERONIX_DB_NAME=${DB_NAME},OPERONIX_DB_USER=${DB_USER},OPERONIX_DB_POOL=${DB_POOL},OPERONIX_API_DOCS=false" \
    --set-secrets="OPERONIX_DB_PASSWORD=${DB_PASSWORD_SECRET}:latest,OPERONIX_JWT_SECRET=${JWT_SECRET_NAME}:latest" \
    "${revision_args[@]}" \
    --quiet
  printf '\nService URL: %s\n' "$(gcloud_run_service_url)"
}

cmd_smoke() {
  log "Post-deployment smoke test"
  local url
  url="${OPERONIX_URL:-$(gcloud_run_service_url)}"
  [[ -n "${url}" ]] || die "Could not determine the service URL; set OPERONIX_URL."
  printf '  target: %s\n' "${url}"

  local failures=0
  check() {
    local name="$1" expected="$2" actual="$3"
    if [[ "${actual}" == "${expected}" ]]; then
      printf '  ok   %s\n' "${name}"
    else
      printf '  FAIL %s (expected %s, got %s)\n' "${name}" "${expected}" "${actual}"
      failures=$((failures + 1))
    fi
  }

  check "readiness probe" "UP" \
    "$(curl -fsS "${url}/actuator/health/readiness" | sed -n 's/.*"status":"\([A-Z]*\)".*/\1/p')"
  check "SPA shell at /" "200" \
    "$(curl -s -o /dev/null -w '%{http_code}' "${url}/")"
  check "SPA deep link" "200" \
    "$(curl -s -o /dev/null -w '%{http_code}' "${url}/quotes/deep-link-smoke")"
  check "unauthenticated API is 401" "401" \
    "$(curl -s -o /dev/null -w '%{http_code}' "${url}/api/v1/customers")"
  # An unknown API path must be answered by the API (401 without a token), never by the SPA shell.
  check "unknown API path is not the SPA shell" "401" \
    "$(curl -s -o /dev/null -w '%{http_code}' "${url}/api/v1/does-not-exist")"
  check "secrets are not served" "404" \
    "$(curl -s -o /dev/null -w '%{http_code}' "${url}/application-prod.yml")"

  if [[ "${failures}" -gt 0 ]]; then
    die "${failures} smoke check(s) failed"
  fi
  printf '\nAll smoke checks passed.\n'
}

cmd_plan() {
  require_confirmation "bootstrap (no changes made)"
  printf '\nDeployment workflow:\n'
  printf '  1. %s bootstrap --yes   # APIs, Artifact Registry, Cloud SQL, secrets, service account, job\n' "$0"
  printf '  2. %s build             # Cloud Build -> Artifact Registry\n' "$0"
  printf '  3. %s migrate           # Flyway in the migrate-only job\n' "$0"
  printf '  4. %s deploy            # Cloud Run revision + traffic\n' "$0"
  printf '  5. %s smoke             # live checks\n' "$0"
}

cmd_bootstrap() {
  require_confirmation "bootstrap"
  cmd_apis
  cmd_artifacts
  cmd_sql
  cmd_secrets
  cmd_service_account
  # The job needs a first image; build one before it can be created.
  if ! gcloud run jobs describe "${JOB}" --region "${REGION}" --project "${PROJECT_ID}" \
      >/dev/null 2>&1; then
    warn "Migration job is created after the first image is pushed."
    printf 'Run: %s build && %s job\n' "$0" "$0"
  fi
  printf '\nBootstrap complete. Next: %s build\n' "$0"
}

main() {
  require_gcloud
  case "${COMMAND}" in
    plan)             cmd_plan ;;
    apis)             cmd_apis ;;
    artifacts)        cmd_artifacts ;;
    sql)              require_confirmation "Cloud SQL instance ${DB_INSTANCE}"; cmd_sql ;;
    secrets)          require_confirmation "Secret Manager secrets"; cmd_secrets ;;
    service-account)  cmd_service_account ;;
    build)            cmd_build ;;
    job)              require_confirmation "Cloud Run job ${JOB}"; cmd_job ;;
    migrate)          cmd_migrate ;;
    deploy)           require_confirmation "Cloud Run service ${SERVICE}"; cmd_deploy ;;
    smoke)            cmd_smoke ;;
    bootstrap)        cmd_bootstrap ;;
    all)
      require_confirmation "full deployment (build, migrate, deploy)"
      cmd_build
      cmd_job
      cmd_migrate
      cmd_deploy
      cmd_smoke
      ;;
    *)
      cat <<USAGE
Operonix GCP deployment helper

Usage: $0 <command> [--yes]

  plan              show what bootstrap would create (no changes)
  apis              enable the required Google Cloud APIs
  artifacts         create/verify the Artifact Registry repository
  sql --yes         create/verify the Cloud SQL PostgreSQL 16 instance and database
  secrets --yes     create/verify Secret Manager secrets (JWT key generated locally)
  service-account   create the least-privilege Cloud Run runtime service account
  build             build and push the image with Cloud Build
  job --yes         create/update the migrate-only Cloud Run job
  migrate           run Flyway migrations in the job
  deploy --yes      deploy the Cloud Run service
  smoke             run post-deployment checks against the live URL
  bootstrap --yes   apis + artifacts + sql + secrets + service account
  all --yes         build + job + migrate + deploy + smoke

Configuration: deploy/gcp/.env (copy .env.example) or environment variables.
See deploy/gcp/README.md for the full walkthrough.
USAGE
      ;;
  esac
}

main "$@"
