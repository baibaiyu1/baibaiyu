#!/usr/bin/env bash
set -euo pipefail
umask 077
bundle_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd -- "$bundle_dir"
chmod 700 "$bundle_dir"
chmod 600 compose.json settings.env
action="${1:-deploy}"
export BUNDLE_HOST_IP="${BUNDLE_HOST_IP:-$(hostname -I 2>/dev/null | awk '{print $1}')}"
export BUNDLE_HOST_NAME="${BUNDLE_HOST_NAME:-$(hostname)}"
compose=(docker compose --project-directory "$bundle_dir" --env-file "$bundle_dir/settings.env" -f "$bundle_dir/compose.json")

fail() { echo "ERROR: $*" >&2; exit 1; }
help() {
  cat <<'USAGE'
Usage: BUNDLE_HOST_IP=<server-ip> bash deploy.sh [command]
  deploy   Verify, load offline images, restore into empty data/ and start all services
  restore  Restore files and databases only; leave middleware running
  resume   Start applications after a successful restore (never re-import databases)
  check    Verify the bundle, Docker images, NVIDIA device and Compose configuration
  doctor   Check running service state, DNS/TCP, Nginx routes and RAG/MinerU APIs
  status   Show container state
  logs     Show the last 100 log lines (optional service names)
  stop     Stop this deployment, preserving all data
The destination must be Linux x86_64 with Docker, Compose >=2.24.4 and NVIDIA Toolkit.
Edit settings.env before first deployment to select the project, ports and GPU.
Changing project/ports after restoring is blocked; use the saved settings.
A failed database/file import is preserved for diagnosis. Extract into a NEW directory
and choose an unused COMPOSE_PROJECT_NAME to retry; existing data is never deleted.
USAGE
}
case "$action" in help|-h|--help) help; exit 0 ;; esac
command -v docker >/dev/null || fail 'Install Docker Engine and Docker Compose first.'
case "$action" in
  status) "${compose[@]}" ps; exit ;;
  logs) shift; "${compose[@]}" logs --tail 100 "$@"; exit ;;
  stop) "${compose[@]}" stop; exit ;;
  deploy|restore|resume|check|doctor) ;;
  *) help; exit 2 ;;
esac
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || fail 'Linux x86_64 is required.'
[[ "$(docker info --format '{{.OSType}}')" == linux ]] || fail 'A Linux Docker Engine is required.'
[[ -n "$BUNDLE_HOST_IP" ]] || fail 'Set BUNDLE_HOST_IP to the server address.'
command -v flock >/dev/null || fail 'Install util-linux (flock) first.'
exec 9> .deployment.lock
flock -n 9 || fail 'Another deployment command is running in this directory.'
if [[ "$action" != doctor ]]; then
  echo 'Verifying every packaged payload before restoring...'
  sha256sum --check --quiet SHA256SUMS || fail 'Bundle checksum verification failed.'
else
  # Doctor only needs the small deployment controls; do not hash 10 GiB again.
  while IFS= read -r checksum_line; do
    case "$checksum_line" in
      *'  deploy.sh'|*'  compose.json'|*'  helper.image'|*'  network.py'|*'  network-check.sh')
        printf '%s\n' "$checksum_line" | sha256sum --check --quiet || fail 'Deployment control checksum failed.' ;;
    esac
  done < SHA256SUMS
fi
IFS= read -r helper_image < helper.image
helper_image="${helper_image%$'\r'}"
[[ "$helper_image" =~ ^gmd-bundle/lightrag:[a-zA-Z0-9._-]+$ ]] || fail 'Invalid helper image reference.'
if [[ ! -f .images-loaded ]] || ! docker image inspect "$helper_image" >/dev/null 2>&1; then
  [[ "$action" != doctor ]] || fail 'Deployment images are missing; deploy the package first.'
  echo 'Loading offline images...'
  docker image load -i images.tar
  touch .images-loaded
fi
helper() {
  docker run --rm --network none --user 0 --entrypoint python \
    --mount "type=bind,source=$bundle_dir,target=/bundle" "$helper_image" /bundle/files.py "$@" /bundle
}
helper images > .expected-images.tsv
while IFS=$'\t' read -r tag expected; do
  actual="$(docker image inspect "$tag" --format '{{.Id}}' 2>/dev/null)" || fail "Missing image: $tag"
  [[ "$actual" == "$expected" ]] || fail "Image identity mismatch: $tag"
done < .expected-images.tsv
"${compose[@]}" config --format json > .effective-compose.json
source "$bundle_dir/network-check.sh"
if [[ -f .restored-settings.sha256 ]]; then
  sha256sum --check --quiet .restored-settings.sha256 || fail 'settings.env changed after restore; restore its original values.'
fi
if [[ "$action" == doctor ]]; then
  network_check
  exit $?
fi
if [[ "$action" != restore ]]; then
  command -v nvidia-smi >/dev/null || fail 'Install the NVIDIA host driver first.'
  nvidia-smi --query-gpu=index,name,driver_version --format=csv
  mineru_image="$(helper mineru-image)"
  gpu_id="$(helper gpu)"
  docker run --rm --network none --gpus "device=$gpu_id" --entrypoint python "$mineru_image" \
    -c 'import torch; assert torch.cuda.is_available(), "CUDA unavailable: check NVIDIA Container Toolkit"; print("PASS: MinerU CUDA device:", torch.cuda.get_device_name(0))'
fi
if [[ "$action" == check ]]; then
  echo 'PASS: payload checksums, image identities, Compose and GPU preflight.'
  exit 0
fi
trap 'echo "Deployment stopped. Data and phase markers are preserved. Inspect: bash deploy.sh logs" >&2' ERR
if [[ ! -f .databases-restored ]]; then
  [[ "$action" != resume ]] || fail 'No completed restore is available to resume.'
  [[ ! -f .restore-started ]] || fail 'An earlier import is incomplete; use a NEW directory and an unused project name to retry.'
  [[ -z "$("${compose[@]}" ps -aq)" ]] || fail 'This Compose project already contains containers; select an unused project name.'
  # Check the ports before allocating/importing databases; a host-network gateway uses these too.
  helper ports > .published-ports
  while IFS= read -r port; do
    if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
      fail "Port $port is in use; select unused ports or stop its current service before restoring."
    fi
  done < .published-ports
  # Helper refuses every nonempty destination; no rm/down -v is used during restore.
  touch .restore-started
  echo 'Restoring platform workspace, LightRAG active storage and MinerU models...'
  helper restore
  sha256sum settings.env > .restored-settings.sha256
  echo 'Starting middleware and importing databases...'
  "${compose[@]}" up -d --no-build --pull never --wait --wait-timeout 240 \
    gmd-faq-mysql gmd-faq-pgvector gmd-faq-redis
  "${compose[@]}" exec -T gmd-faq-mysql sh -c \
    'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --default-character-set=utf8mb4 "$MYSQL_DATABASE"' < backup/mysql.sql
  "${compose[@]}" exec -T gmd-faq-pgvector sh -c \
    'exec pg_restore --exit-on-error --no-owner --no-privileges -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < backup/pgvector.dump
  "${compose[@]}" exec -T gmd-faq-mysql sh -c \
    'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --default-character-set=utf8mb4 "$MYSQL_DATABASE"' < backup/relocate.sql
  "${compose[@]}" exec -T gmd-faq-mysql sh -c \
    'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --default-character-set=utf8mb4 -N -B --raw "$MYSQL_DATABASE"' \
    < backup/verify-mysql.sql > .mysql-counts.jsonl
  helper counts
  touch .databases-restored
  echo 'PASS: files, MySQL, pgvector and Redis restored; application startup can be resumed.'
fi
if [[ "$action" == restore ]]; then
  "${compose[@]}" ps
  exit 0
fi
echo 'Starting Console first and waiting for Flyway migrations...'
"${compose[@]}" up -d --no-build --pull never --wait --wait-timeout 360 gmd-faq-console
echo 'Starting GPU parser, LightRAG and application services...'
"${compose[@]}" up -d --no-build --pull never --wait --wait-timeout 420
# Refresh Nginx after backend container replacement.
"${compose[@]}" restart gmd-faq-frontend
network_check || fail 'Services started but connection checks failed. Run bash deploy.sh doctor after fixing the FAIL items.'
touch .applications-started
"${compose[@]}" ps
frontend_port="$(network_helper frontend-port)"
echo "Deployment ready. Open http://$BUNDLE_HOST_IP:$frontend_port/web/"
echo 'Validate an existing-document search and a new PDF import in the platform.'
