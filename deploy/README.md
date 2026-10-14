# deploy

Deployment of the backend to the Hetzner VM: Docker Compose (Caddy for TLS, the Ktor server, PostgreSQL), plus the hardening checklist and deploy scripts.

Empty for now. Filled by #6 (VM baseline) and #7 (deploy workflow). Never commit secrets, hostnames of private infrastructure or `.env` files here; they come from GitHub secrets or the VM.
