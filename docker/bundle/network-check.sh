#!/usr/bin/env bash
# Sourced by deploy.sh after Compose config has been rendered.
network_helper() {
  docker run --rm --network none --user 0 --entrypoint python \
    --mount "type=bind,source=$bundle_dir,target=/bundle,readonly" \
    "$helper_image" /bundle/network.py "$@" /bundle
}

network_check() {
  local result=0 service kind cid state probe_code frontend_port
  network_helper services > .network-services
  echo 'Checking container state and configured health checks...'
  while IFS= read -r service; do
    cid="$("${compose[@]}" ps -aq "$service")"
    if [[ -z "$cid" ]]; then
      echo "FAIL: $service container is missing"
      result=1
      continue
    fi
    state="$(docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "$cid")"
    case "$state" in
      'running healthy'|'running no-healthcheck') echo "PASS: $service ($state)" ;;
      *) echo "FAIL: $service ($state)"; result=1 ;;
    esac
  done < .network-services
  network_helper plan > .network-plan
  probe_code="$(<"$bundle_dir/network.py")"
  echo 'Checking DNS, TCP and HTTP from the actual service containers...'
  while IFS=$'\t' read -r service kind; do
    if [[ "$kind" == python ]]; then
      if ! "${compose[@]}" exec -T "$service" python -c "$probe_code" python "$service" < .effective-compose.json; then
        result=1
      fi
    else
      network_helper bash "$service" > .network-probe.sh
      if ! "${compose[@]}" exec -T "$service" bash -s < .network-probe.sh; then
        result=1
      fi
    fi
  done < .network-plan
  if ! "${compose[@]}" exec -T gmd-faq-frontend nginx -t; then
    result=1
  fi
  frontend_port="$(network_helper frontend-port)"
  if ! timeout 5 bash -c 'exec 3<>"/dev/tcp/127.0.0.1/$1"' bash "$frontend_port" 2>/dev/null; then
    echo "FAIL: host -> frontend port $frontend_port"
    result=1
  else
    echo "PASS: host -> frontend port $frontend_port"
  fi
  if (( result )); then
    echo 'Network checks failed. Inspect the FAIL lines and bash deploy.sh logs <service>.'
  else
    echo 'PASS: internal network checks. Test the browser URL from your own computer next.'
    echo 'These probes do not verify cloud firewall rules, model-provider credentials or a complete chat/PDF import.'
  fi
  return "$result"
}
