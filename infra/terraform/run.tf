# Cloud Run: one service serves the SPA and the API on the same origin, and one job applies
# database migrations before a revision is expected to serve traffic.

locals {
  runtime_env = {
    SPRING_PROFILES_ACTIVE = "prod"
    OPERONIX_DB_INSTANCE   = local.sql_connection_name
    OPERONIX_DB_NAME       = var.db_name
    OPERONIX_DB_USER       = var.db_user
    OPERONIX_DB_POOL       = tostring(var.db_pool_size)
    OPERONIX_API_DOCS      = tostring(var.api_docs_enabled)
  }

  runtime_secrets = {
    OPERONIX_DB_PASSWORD = "${local.db_secret_id}:latest"
    OPERONIX_JWT_SECRET  = "${local.jwt_secret_id}:latest"
  }
}

resource "google_cloud_run_v2_service" "api" {
  project  = var.project_id
  name     = "operonix-api-${local.name_suffix}"
  location = var.region

  # Keep revisions even after traffic moves, so a rollback is a traffic change, not a redeploy.
  deletion_protection = false
  ingress             = "INGRESS_TRAFFIC_ALL"
  launch_stage        = "GA"

  template {
    service_account = google_service_account.run.email

    scaling {
      min_instance_count = var.min_instances
      max_instance_count = var.max_instances
    }

    max_instance_request_concurrency = var.concurrency
    timeout                          = "60s"

    containers {
      image = var.image
      ports {
        container_port = var.container_port
      }

      resources {
        limits = {
          cpu    = var.cpu
          memory = var.memory
        }
        # CPU is only allocated while a request is in flight (cheapest). The first request after
        # scaling to zero also pays JVM startup; set startup_cpu_boost to keep that acceptable.
        cpu_idle          = true
        startup_cpu_boost = true
      }

      dynamic "env" {
        for_each = local.runtime_env
        content {
          name  = env.key
          value = env.value
        }
      }

      dynamic "env" {
        for_each = local.runtime_secrets
        content {
          name = env.key
          value_source {
            secret_key_ref {
              secret  = env.value
              version = "latest"
            }
          }
        }
      }

      # Cloud Run's built-in Cloud SQL connector exposes the instance as a Unix socket at
      # /cloudsql/<connection-name>; the datasource URL uses exactly that host.
      volume_mounts {
        name       = "cloudsql"
        mount_path = "/cloudsql"
      }

      startup_probe {
        http_get {
          path = "/actuator/health/readiness"
          port = var.container_port
        }
        initial_delay_seconds = 5
        timeout_seconds       = 3
        period_seconds        = 5
        failure_threshold     = 24 # allows up to ~2 minutes for JVM start plus migrations
      }

      liveness_probe {
        http_get {
          path = "/actuator/health/liveness"
          port = var.container_port
        }
        timeout_seconds   = 3
        period_seconds    = 30
        failure_threshold = 3
      }
    }

    volumes {
      name = "cloudsql"
      cloud_sql_instance {
        instances = [local.sql_connection_name]
      }
    }
  }

  traffic {
    type    = "TRAFFIC_TARGET_ALLOCATION_TYPE_LATEST"
    percent = 100
  }

  lifecycle {
    ignore_changes = [
      # Revisions are rolled out by Cloud Build/deploy scripts, not by Terraform.
      template[0].containers[0].image,
      client,
      client_version,
    ]
  }
}

# The application authenticates users itself; IAM-level invoker access is granted only when the
# deployment is meant to be reachable from a browser.
resource "google_cloud_run_v2_service_iam_member" "public" {
  count = var.allow_unauthenticated ? 1 : 0

  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_service.api.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}

# Migrate-only mode: the same image, no HTTP server, exits 0 once Flyway finishes. Deployments run
# this before rolling out a revision so schema changes are visible and verified first.
resource "google_cloud_run_v2_job" "migrate" {
  project  = var.project_id
  name     = "operonix-migrate-${local.name_suffix}"
  location = var.region

  template {
    task_count  = 1
    parallelism = 1

    template {
      service_account = google_service_account.run.email
      max_retries     = 1
      timeout         = "900s"

      containers {
        image   = var.image
        args    = ["--spring.main.web-application-type=none", "--operonix.migrate-only=true"]
        command = []

        dynamic "env" {
          for_each = local.runtime_env
          content {
            name  = env.key
            value = env.value
          }
        }

        dynamic "env" {
          for_each = local.runtime_secrets
          content {
            name = env.key
            value_source {
              secret_key_ref {
                secret  = env.value
                version = "latest"
              }
            }
          }
        }

        volume_mounts {
          name       = "cloudsql"
          mount_path = "/cloudsql"
        }
      }

      volumes {
        name = "cloudsql"
        cloud_sql_instance {
          instances = [local.sql_connection_name]
        }
      }
    }
  }

  lifecycle {
    ignore_changes = [
      template[0].template[0].containers[0].image,
      client,
      client_version,
    ]
  }
}
