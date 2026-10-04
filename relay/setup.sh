#!/usr/bin/env bash
# Sets up or updates the Perspective camera relay on Ryan's Cloud.
# The deploy workflow unpacks the code into /opt/perspective-relay/app and
# runs this. Safe to re-run: every step checks before it changes anything.
set -euo pipefail

SITE=/opt/perspective-relay
CADDYFILE=/opt/mhfa/Caddyfile
HOST=camrelay.pixelhub.org.uk

echo "== Shared network with mhfa-caddy"
NET=$(docker inspect mhfa-caddy -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' | awk '{print $1}')
if [ -z "$NET" ]; then echo "Could not find mhfa-caddy's network"; exit 1; fi
echo "CADDY_NETWORK=$NET" > "$SITE/.env"
cp "$SITE/app/docker-compose.yml" "$SITE/docker-compose.yml"
echo "   $NET"

echo "== Build and start"
cd "$SITE"
docker compose build perspective-relay
docker compose up -d perspective-relay
sleep 3
docker exec perspective-relay wget -qO- http://127.0.0.1:8080/health >/dev/null && echo "   relay healthy"

echo "== Caddy"
if grep -q "^$HOST" "$CADDYFILE"; then
  echo "   $HOST already configured; Caddy left running"
else
  cp "$CADDYFILE" "$CADDYFILE.bak-$(date +%Y%m%d%H%M%S)"
  printf '\n' >> "$CADDYFILE"
  cat "$SITE/app/Caddyfile.block" >> "$CADDYFILE"
  echo "   added $HOST (backup saved next to the Caddyfile)"
  # A new hostname needs a full restart to provision its tls-internal cert.
  docker restart mhfa-caddy
  sleep 5
fi
docker exec mhfa-caddy wget -qO- http://perspective-relay:8080/health >/dev/null && echo "   Caddy reaches the relay"
docker compose ps
