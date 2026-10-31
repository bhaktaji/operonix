terraform {
  required_version = ">= 1.7"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Remote state is strongly recommended for shared environments. Create the bucket once
  # (`gcloud storage buckets create gs://<project>-operonix-tfstate --location <region>`) and
  # uncomment. Local state works for a single operator but is easy to lose.
  #
  # backend "gcs" {
  #   bucket = "your-project-operonix-tfstate"
  #   prefix = "operonix/staging"
  # }
}

provider "google" {
  project = var.project_id
  region  = var.region
}
