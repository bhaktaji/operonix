# Platform resources: image registry, database, secrets and identities.
# Application deployment (Cloud Run revisions) is handled by the deploy scripts or CI, keeping
# "provision infrastructure" separate from "ship a build".

locals {
  name_suffix = var.environment
  sql_instance = "operonix-postgres"
  sql_connection_name = "${var.project_id}:${var.region}:${local.sql_instance}"
  jwt_secret_id  = "operonix-jwt-secret"
  db_secret_id   = "operonix-db-password"
  run_sa_account = "operonix-run-${local.name_suffix}"
}

resource "google_artifact_registry_repository" "images" {
  project       = var.project_id
  location      = var.region
  repository_id = "operonix"
  description   = "Operonix container images"
  format        = "DOCKER"

  # Keep the last 10 tagged builds; older images are pruned to control storage cost.
  cleanup_policy_dry_run = false
  cleanup_policies {
    id     = "keep-recent"
    action = "KEEP"
    most_recent_versions {
      keep_count = 10
    }
  }
}

# Point-in-time recovery, automated daily backups and a Sunday maintenance window. The instance
# deliberately has no authorized networks: Cloud Run connects through its built-in connector over
# the /cloudsql socket, authenticated by the runtime service account's IAM role.
resource "google_sql_database_instance" "postgres" {
  project             = var.project_id
  name                = local.sql_instance
  region              = var.region
  database_version    = "POSTGRES_16"
  deletion_protection = true

  settings {
    tier                        = var.db_tier
    edition                     = "ENTERPRISE"
    availability_type           = var.environment == "production" ? "REGIONAL" : "ZONAL"
    deletion_protection_enabled = true

    backup_configuration {
      enabled                        = true
      start_time                     = "02:00"
      point_in_time_recovery_enabled = true
      backup_retention_settings {
        retained_backups = 14
      }
    }

    ip_configuration {
      ipv4_enabled    = true
      ssl_mode        = "ENCRYPTED_ONLY"
      # No authorized networks by design: access is IAM-authenticated through the connector.
      authorized_networks = []
    }

    maintenance_window {
      day  = 7 # Sunday
      hour = 3
    }

    insights_config {
      query_insights_enabled  = true
      record_application_tags = false
      record_client_address   = false
    }
  }
}

resource "google_sql_database" "app" {
  project  = var.project_id
  name     = var.db_name
  instance = google_sql_database_instance.postgres.name
}

# Generated here and stored only in Secret Manager; the value never enters Terraform state in
# plaintext output (the provider marks the attribute sensitive).
resource "random_password" "db_password" {
  length  = 32
  special = false
}

resource "random_password" "jwt_secret" {
  length  = 48
  special = false
}

resource "google_sql_user" "app" {
  project  = var.project_id
  name     = var.db_user
  instance = google_sql_database_instance.postgres.name
  password = random_password.db_password.result
}

resource "google_secret_manager_secret" "db_password" {
  project   = var.project_id
  secret_id = local.db_secret_id
  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "db_password" {
  secret      = google_secret_manager_secret.db_password.id
  secret_data = random_password.db_password.result
}

resource "google_secret_manager_secret" "jwt_secret" {
  project   = var.project_id
  secret_id = local.jwt_secret_id
  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "jwt_secret" {
  secret      = google_secret_manager_secret.jwt_secret.id
  secret_data = random_password.jwt_secret.result
}

# Least privilege: the runtime can reach the database, read its two secrets and write telemetry.
# It cannot create resources, read other secrets or modify IAM.
resource "google_service_account" "run" {
  project      = var.project_id
  account_id   = local.run_sa_account
  display_name = "Operonix Cloud Run runtime (${local.name_suffix})"
}

locals {
  runtime_roles = [
    "roles/cloudsql.client",
    "roles/secretmanager.secretAccessor",
    "roles/logging.logWriter",
    "roles/monitoring.metricWriter",
  ]
}

resource "google_project_iam_member" "runtime" {
  for_each = toset(local.runtime_roles)

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.run.email}"
}

# Optional: bucket reserved for future document/attachment storage (not used by the MVP).
resource "google_storage_bucket" "attachments" {
  count = var.create_attachments_bucket ? 1 : 0

  project                     = var.project_id
  name                        = "${var.project_id}-operonix-attachments-${local.name_suffix}"
  location                    = var.region
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  force_destroy               = false

  lifecycle_rule {
    condition {
      age = 365
    }
    action {
      type = "Delete"
    }
  }

  versioning {
    enabled = true
  }
}

# Optional: Workload Identity Federation for GitHub Actions, so CI deploys without a service-account
# key file. Create the deployer account with only the roles a deployment needs.
resource "google_iam_workload_identity_pool" "github" {
  count = var.enable_github_oidc ? 1 : 0

  project                   = var.project_id
  workload_identity_pool_id = "github"
  display_name              = "GitHub Actions"
}

resource "google_iam_workload_identity_pool_provider" "github" {
  count = var.enable_github_oidc ? 1 : 0

  project                            = var.project_id
  workload_identity_pool_id          = google_iam_workload_identity_pool.github[0].workload_identity_pool_id
  workload_identity_pool_provider_id = "github"
  display_name                       = "GitHub Actions OIDC"

  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.repository" = "assertion.repository"
    "attribute.ref"        = "assertion.ref"
  }

  # Only tokens minted for this repository can federate, and only from the default branch or tags
  # in the workflow's environment. Anything else is rejected before IAM is consulted.
  attribute_condition = "assertion.repository == '${var.github_repository}'"

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

resource "google_service_account" "deployer" {
  count = var.enable_github_oidc ? 1 : 0

  project      = var.project_id
  account_id   = "operonix-deployer"
  display_name = "Operonix CI deployer (GitHub Actions)"
}

locals {
  deployer_roles = [
    "roles/artifactregistry.writer",
    "roles/run.developer",
    "roles/cloudbuild.builds.editor",
    "roles/iam.serviceAccountUser",
    "roles/logging.viewer",
  ]
}

resource "google_project_iam_member" "deployer" {
  for_each = var.enable_github_oidc ? toset(local.deployer_roles) : toset([])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.deployer[0].email}"
}

resource "google_service_account_iam_member" "deployer_federation" {
  count = var.enable_github_oidc ? 1 : 0

  service_account_id = google_service_account.deployer[0].name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github[0].name}/attribute.repository/${var.github_repository}"
}
