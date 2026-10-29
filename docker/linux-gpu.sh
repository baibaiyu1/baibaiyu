#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
env_file="$script_dir/.env.linux-gpu"
model_env_file="$script_dir/lightrag.env.linux-gpu"
compose=(docker compose --project-directory "$script_dir" --env-file "$env_file"
  -f "$script_dir/docker-compose-simple.yml" -f "$script_dir/docker-compose-linux-gpu.yml")

usage() {
  cat <<'USAGE'
Usage: bash docker/linux-gpu.sh <command> [service ...]
  init       Create separate Linux GPU configuration; preserve existing files
  check      Validate Compose, Linux Docker Engine and the host NVIDIA driver
  build      Build platform, API-only LightRAG and MinerU 3.4.5 GPU images
  up         Build, start all services and refresh the frontend upstreams
  ps         Show service state and health
  logs       Show the last 100 log lines (accepts options such as -f)
  stop       Stop services while preserving containers and data
  down       Remove this stack's containers and network; preserve data
  restart-lightrag  Restart LightRAG after a platform model synchronization
USAGE
}

init_config() {
  command -v openssl >/dev/null || { echo "Install openssl first." >&2; exit 1; }
  umask 077
  if [[ -e "$env_file" ]]; then
    echo "Preserved $env_file"
  else
    local db_password redis_password pg_password jwt_secret rag_key node_name node_ip line
    db_password="$(openssl rand -hex 24)"
    redis_password="$(openssl rand -hex 24)"
    pg_password="$(openssl rand -hex 24)"
    jwt_secret="$(openssl rand -hex 32)"
    rag_key="$(openssl rand -hex 32)"
    node_name="$(hostname)"
    node_ip="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"
    (
      set -o noclobber
      while IFS= read -r line || [[ -n "$line" ]]; do
        case "$line" in
          MYSQL_PASSWORD=*) line="MYSQL_PASSWORD=$db_password" ;;
          MYSQL_ROOT_PASSWORD=*) line="MYSQL_ROOT_PASSWORD=$db_password" ;;
          REDIS_PASSWORD=*) line="REDIS_PASSWORD=$redis_password" ;;
          PG_PASSWORD=*) line="PG_PASSWORD=$pg_password" ;;
          JWT_SECRET=*) line="JWT_SECRET=$jwt_secret" ;;
          LIGHTRAG_API_KEY=*) line="LIGHTRAG_API_KEY=$rag_key" ;;
          GMD_FAQ_HOST_NAME=*) line="GMD_FAQ_HOST_NAME=$node_name" ;;
          GMD_FAQ_HOST_IP=*) line="GMD_FAQ_HOST_IP=$node_ip" ;;
        esac
        printf '%s\n' "$line"
      done < "$script_dir/.env.linux-gpu.example" > "$env_file"
    )
    echo "Created $env_file with independent random credentials."
  fi
  if [[ -e "$model_env_file" ]]; then
    echo "Preserved $model_env_file"
  else
    (set -o noclobber; cat "$script_dir/lightrag.env.linux-gpu.example" > "$model_env_file")
    echo "Created $model_env_file"
  fi
  echo "Review model/source/data paths, GPU ID and node IP before running check/up."
}

require_config() {
  [[ -f "$env_file" && -f "$model_env_file" ]] || {
    echo "Run: bash docker/linux-gpu.sh init" >&2
    exit 1
  }
  command -v docker >/dev/null || { echo "Install Docker Engine and Compose first." >&2; exit 1; }
}

check_host() {
  [[ "$(uname -s)" == Linux ]] || { echo "Run this deployment on Linux." >&2; exit 1; }
  [[ "$(uname -m)" == x86_64 ]] || { echo "The default MinerU CUDA image requires x86_64 Linux." >&2; exit 1; }
  [[ "$(docker info --format '{{.OSType}}')" == linux ]] || {
    echo "A running Linux Docker Engine is required." >&2
    exit 1
  }
  "${compose[@]}" config --quiet
  command -v nvidia-smi >/dev/null || { echo "Install the host NVIDIA driver first." >&2; exit 1; }
  nvidia-smi --query-gpu=index,name,driver_version --format=csv
  echo "Compose and host checks passed. Container GPU/model checks run when MinerU starts."
}

action="${1:-help}"
if (( $# )); then shift; fi
case "$action" in
  init) init_config ;;
  help|-h|--help) usage ;;
  check) require_config; check_host ;;
  build) require_config; check_host; "${compose[@]}" build "$@" ;;
  up)
    require_config
    check_host
    # Apply database migrations before updating Runtime and the gateway.
    "${compose[@]}" up -d --build --wait --wait-timeout 360 gmd-faq-console
    "${compose[@]}" up -d --build "$@"
    # Nginx resolves backend addresses at startup; refresh after recreating them.
    "${compose[@]}" restart gmd-faq-frontend
    "${compose[@]}" ps
    ;;
  ps) require_config; "${compose[@]}" ps "$@" ;;
  logs) require_config; "${compose[@]}" logs --tail 100 "$@" ;;
  stop) require_config; "${compose[@]}" stop "$@" ;;
  down) require_config; "${compose[@]}" down ;;
  restart-lightrag) require_config; "${compose[@]}" restart lightrag ;;
  *) usage >&2; exit 2 ;;
esac
