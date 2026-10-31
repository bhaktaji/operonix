variable "project_id" {
  description = "Google Cloud project that owns every resource in this module."
  type        = string
}

variable "region" {
  description = "Region for Cloud Run, Cloud SQL, Artifact Registry and Secret Manager. asia-south1 (Mumbai) is the initial default, not a hardcoded assumption."
  type        = string
  default     = "asia-south1"
}

variable "environment" {
  description = "Environment name used as a resource-name suffix: staging or production."
  type        = string
  default     = "staging"

  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "environment must be \"staging\" or \"production\"."
  }
}

# ---------------------------------------------------------------- application

variable "image" {
  description = "Container image to deploy, including tag. Pushed by Cloud Build or the deploy scripts."
  type        = string
  default     = ""
}

variable "container_port" {
  description = "Port the container listens on (Cloud Run injects PORT; the app defaults to 8080)."
  type        = number
  default     = 8080
}

variable "db_name" {
  description = "Database name inside the Cloud SQL instance."
  type        = string
  default     = "operonix"
}

variable "db_user" {
  description = "Application database user."
  type        = string
  default     = "operonix"
}

variable "db_tier" {
  description = "Cloud SQL machine tier. Shared-core for staging, db-custom-* for production."
  type        = string
  default     = "db-g1-small"
}

variable "db_pool_size" {
  description = "Hikari connection pool size per Cloud Run instance. Keep (pool x max_instances) + headroom under the instance connection limit."
  type        = number
  default     = 5
}

# ---------------------------------------------------------------- Cloud Run

variable "min_instances" {
  description = "Minimum Cloud Run instances. 0 scales to zero (cheapest); 1 removes cold starts."
  type        = number
  default     = 0
}

variable "max_instances" {
  description = "Maximum Cloud Run instances. Combined with db_pool_size this bounds database connections."
  type        = number
  default     = 3
}

variable "cpu" {
  description = "vCPU limit per instance."
  type        = string
  default     = "1"
}

variable "memory" {
  description = "Memory limit per instance. The JVM is configured for 75% of this value."
  type        = string
  default     = "1Gi"
}

variable "concurrency" {
  description = "Maximum concurrent requests per instance."
  type        = number
  default     = 80
}

variable "allow_unauthenticated" {
  description = "Serve the public URL. The application enforces its own authentication; set false to additionally require IAM tokens."
  type        = bool
  default     = true
}

variable "api_docs_enabled" {
  description = "Expose Swagger/OpenAPI in the deployed environment. Keep false in production."
  type        = bool
  default     = false
}

# ---------------------------------------------------------------- optional resources

variable "create_attachments_bucket" {
  description = "Create the Cloud Storage bucket reserved for future document and attachment storage. Off by default so a plan creates no unnecessary billable resource."
  type        = bool
  default     = false
}

variable "enable_github_oidc" {
  description = "Create the Workload Identity Federation pool, provider and deployer service account used by .github/workflows/deploy.yml (no service-account keys)."
  type        = bool
  default     = false
}

variable "github_repository" {
  description = "owner/repository allowed to federate, used only when enable_github_oidc is true."
  type        = string
  default     = ""
}
