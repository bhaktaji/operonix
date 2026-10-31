<#
.SYNOPSIS
  Operonix — Google Cloud deployment helper for Windows PowerShell.

.DESCRIPTION
  Mirrors deploy/gcp/deploy.sh. Run from the repository root with an authenticated gcloud CLI.

  .\deploy\gcp\deploy.ps1 plan              # show what a bootstrap would create (no changes)
  .\deploy\gcp\deploy.ps1 bootstrap -Yes    # APIs, Artifact Registry, Cloud SQL, secrets, service account
  .\deploy\gcp\deploy.ps1 build             # build + push the image through Cloud Build
  .\deploy\gcp\deploy.ps1 job -Yes          # create/update the migrate-only Cloud Run job
  .\deploy\gcp\deploy.ps1 migrate           # run Flyway migrations in the job
  .\deploy\gcp\deploy.ps1 deploy -Yes       # roll out the Cloud Run revision
  .\deploy\gcp\deploy.ps1 smoke             # post-deployment checks against the live URL
  .\deploy\gcp\deploy.ps1 all -Yes          # build + job + migrate + deploy + smoke

  Configuration comes from deploy\gcp\.env (git-ignored; copy .env.example) or the environment.
  Commands that create billable resources require -Yes and print their plan first.
#>
[CmdletBinding()]
param(
  [Parameter(Position = 0)]
  [string]$Command = "help",

  [Parameter(Position = 1)]
  [switch]$Yes,

  # Optional: deploy this tag instead of the current commit short SHA.
  [string]$Tag
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = (Resolve-Path (Join-Path $ScriptDir "..\..")).Path
$EnvFile = Join-Path $ScriptDir ".env"

# ------------------------------------------------------------------ configuration

if (Test-Path $EnvFile) {
  foreach ($line in (Get-Content $EnvFile)) {
    $trimmed = $line.Trim()
    if ($trimmed -eq "" -or $trimmed.StartsWith("#")) { continue }
    $index = $trimmed.IndexOf("=")
    if ($index -lt 1) { continue }
    $key = $trimmed.Substring(0, $index).Trim()
    $value = $trimmed.Substring($index + 1).Trim()
    if (-not (Test-Path "Env:$key")) { Set-Item -Path "Env:$key" -Value $value }
  }
}

function Get-Setting([string]$Name, [string]$Default) {
  $value = [Environment]::GetEnvironmentVariable($Name)
  if ([string]::IsNullOrWhiteSpace($value)) { return $Default }
  return $value
}

$ProjectId = Get-Setting "PROJECT_ID" ""
if ([string]::IsNullOrWhiteSpace($ProjectId)) {
  $ProjectId = (& gcloud config get-value project 2>$null | Select-Object -First 1)
}
# asia-south1 is only the default; override REGION in .env or the environment.
$Region = Get-Setting "REGION" "asia-south1"
$Env = Get-Setting "ENV" "staging"
$ArRepo = Get-Setting "AR_REPO" "operonix"
$Image = Get-Setting "IMAGE" "operonix"
$Service = "$(Get-Setting 'SERVICE' 'operonix-api')-$Env"
$Job = "$(Get-Setting 'JOB' 'operonix-migrate')-$Env"
$DbInstance = Get-Setting "DB_INSTANCE" "operonix-postgres"
$DbName = Get-Setting "DB_NAME" "operonix"
$DbUser = Get-Setting "DB_USER" "operonix"
$DbTier = Get-Setting "DB_TIER" "db-g1-small"
$DbPool = Get-Setting "DB_POOL" "5"
$DbPasswordSecret = Get-Setting "DB_PASSWORD_SECRET" "operonix-db-password"
$JwtSecretName = Get-Setting "JWT_SECRET_NAME" "operonix-jwt-secret"
$Cpu = Get-Setting "CPU" "1"
$Memory = Get-Setting "MEMORY" "1Gi"
$MinInstances = Get-Setting "MIN_INSTANCES" "0"
$MaxInstances = Get-Setting "MAX_INSTANCES" "3"
$Concurrency = Get-Setting "CONCURRENCY" "80"
$RunSa = Get-Setting "RUN_SA" "operonix-run-$Env@$ProjectId.iam.gserviceaccount.com"

$ImageRepo = "$Region-docker.pkg.dev/$ProjectId/$ArRepo/$Image"
if (-not $Tag) {
  $Tag = (& git -C $RepoRoot rev-parse --short=7 HEAD 2>$null | Select-Object -First 1)
  if ([string]::IsNullOrWhiteSpace($Tag)) {
    $Tag = (Get-Date -Format "yyyyMMddHHmmss")
  }
}
$ImageUri = "${ImageRepo}:$Tag"
$ConnectionName = "$ProjectId`:$Region`:$DbInstance"

function Write-Log([string]$Message) { Write-Host "`n==> $Message" }
function Write-Warn([string]$Message) { Write-Host "`n!! $Message" -ForegroundColor Yellow }
function Write-Fail([string]$Message) { Write-Host "`nERROR: $Message" -ForegroundColor Red; exit 1 }

function Invoke-Gcloud {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
  & gcloud @Arguments
  if ($LASTEXITCODE -ne 0) { Write-Fail "gcloud $($Arguments -join ' ') failed with exit code $LASTEXITCODE" }
}

function Test-Confirmation([string]$Action) {
  Write-Log "Plan: $Action"
  Write-Host "  project      : $ProjectId"
  Write-Host "  region       : $Region"
  Write-Host "  environment  : $Env"
  Write-Host "  service      : $Service (Cloud Run)"
  Write-Host "  migration job: $Job (Cloud Run job)"
  Write-Host "  database     : $DbInstance/$DbName (Cloud SQL PostgreSQL 16, tier $DbTier)"
  Write-Host "  image repo   : $ImageRepo"
  Write-Host "  image tag    : $Tag"
  if (-not $Yes) {
    Write-Warn "This creates or changes billable Google Cloud resources. Re-run with -Yes to proceed."
    exit 1
  }
}

# ------------------------------------------------------------------ commands

function Invoke-Apis {
  Write-Log "Enabling required Google Cloud APIs"
  Invoke-Gcloud services enable `
    run.googleapis.com `
    sqladmin.googleapis.com `
    artifactregistry.googleapis.com `
    cloudbuild.googleapis.com `
    secretmanager.googleapis.com `
    logging.googleapis.com `
    monitoring.googleapis.com `
    iam.googleapis.com `
    --project $ProjectId
}

function Invoke-Artifacts {
  Write-Log "Ensuring Artifact Registry repository $ArRepo in $Region"
  & gcloud artifacts repositories describe $ArRepo --location $Region --project $ProjectId 2>$null | Out-Null
  if ($LASTEXITCODE -ne 0) {
    Invoke-Gcloud artifacts repositories create $ArRepo `
      --repository-format=docker `
      --location=$Region `
      --description="Operonix container images" `
      --project=$ProjectId
  }
  else { Write-Host "  repository already exists" }
}

function Invoke-Sql {
  Write-Log "Ensuring Cloud SQL PostgreSQL 16 instance $DbInstance"
  & gcloud sql instances describe $DbInstance --project $ProjectId 2>$null | Out-Null
  if ($LASTEXITCODE -ne 0) {
    # No authorized networks are configured: Cloud Run reaches the instance through its built-in
    # connector over the /cloudsql socket, authenticated by the runtime service account's IAM role.
    # A private-IP-only instance is also supported but then requires Direct VPC egress — see
    # docs/GOOGLE_CLOUD_DEPLOYMENT.md ("Networking options").
    Invoke-Gcloud sql instances create $DbInstance `
      --project=$ProjectId `
      --database-version=POSTGRES_16 `
      --region=$Region `
      --tier=$DbTier `
      --storage-type=SSD --storage-size=10GB --storage-auto-increase `
      --backup --backup-start-time=02:00 `
      --maintenance-window-day=SUN --maintenance-window-hour=3 `
      --quiet
  }
  else { Write-Host "  instance already exists" }

  Write-Log "Ensuring database $DbName"
  & gcloud sql databases describe $DbName --instance $DbInstance --project $ProjectId 2>$null | Out-Null
  if ($LASTEXITCODE -ne 0) {
    Invoke-Gcloud sql databases create $DbName --instance $DbInstance --project $ProjectId
  }
  else { Write-Host "  database already exists" }

  Write-Log "Ensuring database user $DbUser"
  $existingUsers = & gcloud sql users list --instance $DbInstance --project $ProjectId --format="value(name)"
  if ($existingUsers -notcontains $DbUser) {
    # The password is generated locally and written straight into Secret Manager through a
    # temporary file that is deleted immediately; it is never printed or stored in the repository.
    # gcloud has no file-based password option for sql users create, so the value is briefly
    # present in the local process list; rotate it through Secret Manager if that matters.
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $password = [Convert]::ToBase64String($bytes)
    try {
      Invoke-Gcloud sql users create $DbUser --instance $DbInstance --password=$password --project $ProjectId
      $temp = New-TemporaryFile
      Set-Content -Path $temp -Value $password -NoNewline -Encoding utf8
      Invoke-Gcloud secrets versions add $DbPasswordSecret --data-file=$temp --project $ProjectId
      Remove-Item $temp -Force
    }
    finally { $password = $null }
  }
  else { Write-Host "  user already exists" }
}

function Invoke-Secrets {
  Write-Log "Ensuring Secret Manager secrets (values are never printed)"
  foreach ($secret in @($DbPasswordSecret, $JwtSecretName)) {
    & gcloud secrets describe $secret --project $ProjectId 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
      Invoke-Gcloud secrets create $secret --replication-policy=automatic --project $ProjectId
    }
    else { Write-Host "  $secret exists" }
  }

  $jwtVersions = & gcloud secrets versions list $JwtSecretName --project $ProjectId --format="value(state)"
  if ($jwtVersions -notcontains "ENABLED") {
    Write-Log "Generating a new JWT signing key (48 random bytes)"
    $bytes = New-Object byte[] 48
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $temp = New-TemporaryFile
    try {
      Set-Content -Path $temp -Value ([Convert]::ToBase64String($bytes)) -NoNewline -Encoding utf8
      Invoke-Gcloud secrets versions add $JwtSecretName --data-file=$temp --project $ProjectId
    }
    finally { Remove-Item $temp -Force }
  }
  else { Write-Host "  $JwtSecretName already has an enabled version" }

  $dbVersions = & gcloud secrets versions list $DbPasswordSecret --project $ProjectId --format="value(state)"
  if ($dbVersions -notcontains "ENABLED") {
    Write-Warn "Secret $DbPasswordSecret has no enabled version."
    $secure = Read-Host -Prompt "Paste the Cloud SQL password for $DbUser" -AsSecureString
    $temp = New-TemporaryFile
    try {
      $plain = [System.Net.NetworkCredential]::new("", $secure).Password
      Set-Content -Path $temp -Value $plain -NoNewline -Encoding utf8
      Invoke-Gcloud secrets versions add $DbPasswordSecret --data-file=$temp --project $ProjectId
      $plain = $null
    }
    finally { Remove-Item $temp -Force }
  }
  else { Write-Host "  $DbPasswordSecret already has an enabled version" }
}

function Invoke-ServiceAccount {
  Write-Log "Ensuring least-privilege runtime service account $RunSa"
  & gcloud iam service-accounts describe $RunSa --project $ProjectId 2>$null | Out-Null
  if ($LASTEXITCODE -ne 0) {
    $accountId = $RunSa.Split("@")[0]
    Invoke-Gcloud iam service-accounts create $accountId `
      --display-name="Operonix Cloud Run runtime ($Env)" --project=$ProjectId
  }
  else { Write-Host "  service account already exists" }

  foreach ($role in @("roles/cloudsql.client", "roles/secretmanager.secretAccessor",
                      "roles/logging.logWriter", "roles/monitoring.metricWriter")) {
    Invoke-Gcloud projects add-iam-policy-binding $ProjectId `
      --member="serviceAccount:$RunSa" --role=$role --condition=None --quiet | Out-Null
    Write-Host "  granted $role"
  }
}

function Invoke-Build {
  Write-Log "Building and pushing $ImageUri with Cloud Build"
  Invoke-Gcloud builds submit $RepoRoot `
    --config="$RepoRoot\cloudbuild.yaml" `
    --substitutions="_REGION=$Region,_AR_REPO=$ArRepo,_IMAGE=$Image,_ENV=$Env,_DEPLOY=false" `
    --project=$ProjectId
  Write-Host "`nImage pushed. Next: .\deploy\gcp\deploy.ps1 job -Yes"
}

function Invoke-Job {
  Write-Log "Ensuring Cloud Run migration job $Job"
  $common = @(
    "--image=$ImageUri",
    "--region=$Region",
    "--project=$ProjectId",
    "--service-account=$RunSa",
    "--add-cloudsql-instances=$ConnectionName",
    "--set-env-vars=SPRING_PROFILES_ACTIVE=prod,OPERONIX_DB_INSTANCE=$ConnectionName,OPERONIX_DB_NAME=$DbName,OPERONIX_DB_USER=$DbUser",
    "--set-secrets=OPERONIX_DB_PASSWORD=${DbPasswordSecret}:latest,OPERONIX_JWT_SECRET=${JwtSecretName}:latest",
    "--max-retries=1",
    "--task-timeout=900s",
    "--args=--spring.main.web-application-type=none,--operonix.migrate-only=true",
    "--quiet"
  )
  & gcloud run jobs describe $Job --region $Region --project $ProjectId 2>$null | Out-Null
  if ($LASTEXITCODE -eq 0) { Invoke-Gcloud run jobs update $Job @common }
  else { Invoke-Gcloud run jobs create $Job @common }
}

function Invoke-Migrate {
  Write-Log "Running Flyway migrations in job $Job"
  Invoke-Gcloud run jobs execute $Job --region $Region --project $ProjectId --wait --quiet
  Write-Host "  migrations complete"
}

function Invoke-Deploy {
  Write-Log "Deploying Cloud Run service $Service ($ImageUri)"
  $revisionSuffix = ($Tag -replace "[^a-zA-Z0-9-]", "-")
  Invoke-Gcloud run deploy $Service `
    --image=$ImageUri `
    --region=$Region `
    --project=$ProjectId `
    --platform=managed `
    --allow-unauthenticated `
    --port=8080 `
    --cpu=$Cpu `
    --memory=$Memory `
    --min-instances=$MinInstances `
    --max-instances=$MaxInstances `
    --concurrency=$Concurrency `
    --timeout=60 `
    --service-account=$RunSa `
    --add-cloudsql-instances=$ConnectionName `
    --set-env-vars="SPRING_PROFILES_ACTIVE=prod,OPERONIX_DB_INSTANCE=$ConnectionName,OPERONIX_DB_NAME=$DbName,OPERONIX_DB_USER=$DbUser,OPERONIX_DB_POOL=$DbPool,OPERONIX_API_DOCS=false" `
    --set-secrets="OPERONIX_DB_PASSWORD=${DbPasswordSecret}:latest,OPERONIX_JWT_SECRET=${JwtSecretName}:latest" `
    --revision-suffix=$revisionSuffix `
    --quiet
  Write-Host "`nService URL: $(Get-ServiceUrl)"
}

function Get-ServiceUrl {
  return (& gcloud run services describe $Service --region $Region --project $ProjectId --format="value(status.url)")
}

function Invoke-Smoke {
  Write-Log "Post-deployment smoke test"
  $url = Get-Setting "OPERONIX_URL" ""
  if ([string]::IsNullOrWhiteSpace($url)) { $url = Get-ServiceUrl }
  if ([string]::IsNullOrWhiteSpace($url)) { Write-Fail "Could not determine the service URL; set OPERONIX_URL." }
  Write-Host "  target: $url"

  $script:failures = 0
  function Test-Check([string]$Name, [string]$Expected, [string]$Actual) {
    if ($Actual -eq $Expected) { Write-Host "  ok   $Name" }
    else {
      Write-Host "  FAIL $Name (expected $Expected, got $Actual)" -ForegroundColor Red
      $script:failures = $script:failures + 1
    }
  }

  function Get-Status([string]$Path) {
    try {
      return [string](Invoke-WebRequest -Uri "$url$Path" -UseBasicParsing -TimeoutSec 30).StatusCode
    }
    catch {
      $response = $_.Exception.Response
      if ($null -ne $response) { return [string][int]$response.StatusCode }
      throw
    }
  }

  $readiness = (Invoke-WebRequest -Uri "$url/actuator/health/readiness" -UseBasicParsing -TimeoutSec 30 |
    Select-Object -ExpandProperty Content | ConvertFrom-Json).status
  Test-Check "readiness probe" "UP" $readiness
  Test-Check "SPA shell at /" "200" (Get-Status "/")
  Test-Check "SPA deep link" "200" (Get-Status "/quotes/deep-link-smoke")
  Test-Check "unauthenticated API is 401" "401" (Get-Status "/api/v1/customers")
  # An unknown API path must be answered by the API (401 without a token), never by the SPA shell.
  Test-Check "unknown API path is not the SPA shell" "401" (Get-Status "/api/v1/does-not-exist")
  Test-Check "secrets are not served" "404" (Get-Status "/application-prod.yml")

  if ($script:failures -gt 0) { Write-Fail "$script:failures smoke check(s) failed" }
  Write-Host "`nAll smoke checks passed."
}

function Invoke-Plan {
  Test-Confirmation "bootstrap (no changes made)"
  Write-Host "`nDeployment workflow:"
  Write-Host "  1. .\deploy\gcp\deploy.ps1 bootstrap -Yes   # APIs, Artifact Registry, Cloud SQL, secrets, service account"
  Write-Host "  2. .\deploy\gcp\deploy.ps1 build             # Cloud Build -> Artifact Registry"
  Write-Host "  3. .\deploy\gcp\deploy.ps1 job -Yes          # migrate-only Cloud Run job"
  Write-Host "  4. .\deploy\gcp\deploy.ps1 migrate           # Flyway migrations"
  Write-Host "  5. .\deploy\gcp\deploy.ps1 deploy -Yes       # Cloud Run revision"
  Write-Host "  6. .\deploy\gcp\deploy.ps1 smoke             # live checks"
}

function Invoke-Bootstrap {
  Test-Confirmation "bootstrap"
  Invoke-Apis
  Invoke-Artifacts
  Invoke-Sql
  Invoke-Secrets
  Invoke-ServiceAccount
  Write-Host "`nBootstrap complete. Next: .\deploy\gcp\deploy.ps1 build"
}

function Show-Usage {
  Write-Host "Operonix GCP deployment helper (PowerShell)"
  Write-Host ""
  Write-Host "Usage: .\deploy\gcp\deploy.ps1 <command> [-Yes] [-Tag <tag>]"
  Write-Host ""
  Write-Host "  plan              show what bootstrap would create (no changes)"
  Write-Host "  apis              enable the required Google Cloud APIs"
  Write-Host "  artifacts         create/verify the Artifact Registry repository"
  Write-Host "  sql -Yes          create/verify the Cloud SQL PostgreSQL 16 instance and database"
  Write-Host "  secrets -Yes      create/verify Secret Manager secrets (JWT key generated locally)"
  Write-Host "  service-account   create the least-privilege Cloud Run runtime service account"
  Write-Host "  build             build and push the image with Cloud Build"
  Write-Host "  job -Yes          create/update the migrate-only Cloud Run job"
  Write-Host "  migrate           run Flyway migrations in the job"
  Write-Host "  deploy -Yes       deploy the Cloud Run service"
  Write-Host "  smoke             run post-deployment checks against the live URL"
  Write-Host "  bootstrap -Yes    apis + artifacts + sql + secrets + service account"
  Write-Host "  all -Yes          build + job + migrate + deploy + smoke"
  Write-Host ""
  Write-Host "Configuration: deploy\gcp\.env (copy .env.example) or environment variables."
}

if ([string]::IsNullOrWhiteSpace($ProjectId)) {
  Write-Fail "PROJECT_ID is not set. Copy deploy\gcp\.env.example to deploy\gcp\.env."
}

switch ($Command.ToLower()) {
  "plan"            { Invoke-Plan }
  "apis"            { Invoke-Apis }
  "artifacts"       { Invoke-Artifacts }
  "sql"             { Test-Confirmation "Cloud SQL instance $DbInstance"; Invoke-Sql }
  "secrets"         { Test-Confirmation "Secret Manager secrets"; Invoke-Secrets }
  "service-account" { Invoke-ServiceAccount }
  "build"           { Invoke-Build }
  "job"             { Test-Confirmation "Cloud Run job $Job"; Invoke-Job }
  "migrate"         { Invoke-Migrate }
  "deploy"          { Test-Confirmation "Cloud Run service $Service"; Invoke-Deploy }
  "smoke"           { Invoke-Smoke }
  "bootstrap"       { Invoke-Bootstrap }
  "all" {
    Test-Confirmation "full deployment (build, migrate, deploy)"
    Invoke-Build
    Invoke-Job
    Invoke-Migrate
    Invoke-Deploy
    Invoke-Smoke
  }
  default { Show-Usage }
}
