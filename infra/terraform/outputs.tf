output "service_url" {
  description = "Public Cloud Run URL of the Operonix service."
  value       = google_cloud_run_v2_service.api.uri
}

output "service_name" {
  description = "Cloud Run service name (used by rollback and traffic commands)."
  value       = google_cloud_run_v2_service.api.name
}

output "migration_job_name" {
  description = "Cloud Run job that applies Flyway migrations (migrate-only mode)."
  value       = google_cloud_run_v2_job.migrate.name
}

output "artifact_registry_repository" {
  description = "Docker repository URL prefix for pushing images."
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.images.repository_id}"
}

output "cloudsql_connection_name" {
  description = "Cloud SQL connection name used by --add-cloudsql-instances and OPERONIX_DB_INSTANCE."
  value       = local.sql_connection_name
}

output "database_private_ip" {
  description = "Status of the Cloud SQL instance (IP ranges are intentionally not exposed)."
  value       = google_sql_database_instance.postgres.database_version
}

output "runtime_service_account" {
  description = "Least-privilege identity used by Cloud Run."
  value       = google_service_account.run.email
}

output "db_password_secret_id" {
  description = "Secret Manager secret holding the database password (value is never output)."
  value       = google_secret_manager_secret.db_password.secret_id
}

output "jwt_secret_id" {
  description = "Secret Manager secret holding the JWT signing key (value is never output)."
  value       = google_secret_manager_secret.jwt_secret.secret_id
}

output "attachments_bucket" {
  description = "Cloud Storage bucket reserved for attachments, when created."
  value       = var.create_attachments_bucket ? google_storage_bucket.attachments[0].name : null
}

output "github_workload_identity_provider" {
  description = "Set as the GCP_WORKLOAD_IDENTITY_PROVIDER secret in GitHub Actions, when enabled."
  value       = var.enable_github_oidc ? google_iam_workload_identity_pool_provider.github[0].name : null
}

output "github_deployer_service_account" {
  description = "Set as the GCP_DEPLOY_SERVICE_ACCOUNT secret in GitHub Actions, when enabled."
  value       = var.enable_github_oidc ? google_service_account.deployer[0].email : null
}
