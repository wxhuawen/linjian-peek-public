# Self-host the Python server on a VPS

This deployment runs only the Python server. Android keeps using its runtime
server setting, and the existing MCP deployment remains in place. Keep the
current Render server unchanged until the full migration has passed acceptance.

## Architecture

```text
Android / existing MCP
          |
          | HTTPS (80/443)
          v
  Existing host Caddy
          |
          | loopback only (127.0.0.1:8513)
          v
    linjian_server.py
          |
          v
   Docker volume (/data)
```

Port 8513 is published only on the host loopback interface; it is not reachable
from the public network. The existing host Caddy obtains and renews the HTTPS
certificate automatically. No request body limit is added, so screenshot
uploads are not constrained by this config.

## Prerequisites

- A Linux VPS with Docker Engine and Docker Compose v2
- An existing host Caddy service managing ports 80 and 443
- A DNS record for the selected subdomain pointing to the VPS public IP
- Inbound TCP ports 80 and 443 open (and UDP 443 if HTTP/3 is desired)
- An existing SSH administration path

Do not remove, stop, or reconfigure the current Render server during this
procedure.

## 1. Copy the deployment to the VPS

Clone the personal fork and check out the deployment branch:

```bash
git clone https://github.com/wxhuawen/linjian-peek-public.git
cd linjian-peek-public
git switch codex/selfhost-deploy
```

If the repository already exists on the VPS, fetch `personal` and check out the
same branch instead.

## 2. Create the private environment file

```bash
cd deploy/selfhost
cp .env.example .env
chmod 600 .env
```

Edit `.env` on the VPS and set:

- `LINJIAN_DOMAIN` to the final hostname, without `https://` or a path
- `LINJIAN_TOKEN` to the current token

Never paste the token into Git, shell history, deployment logs, or chat. Do not
change the token as part of this migration.

## 3. Validate and start the server

Run these commands from `deploy/selfhost`:

```bash
docker compose config --quiet
docker compose build server
docker compose up -d server
docker compose ps
```

`docker compose config --quiet` validates the configuration without printing
the interpolated environment. Avoid running plain `docker compose config`,
because it can display the token.

For status checks, prefer:

```bash
docker compose logs --tail=100 server
```

Review logs before sharing them and redact credentials, URLs, device data, and
phone content.

Verify the loopback-only health endpoint before changing Caddy:

```bash
curl --fail --silent --show-error http://127.0.0.1:8513/health
```

## 4. Add the site to the existing Caddy service

The tracked `Caddyfile` is a template. Replace `{$LINJIAN_DOMAIN}` with the
hostname from `.env` only in the VPS Caddy configuration; do not commit the
rendered hostname.

Before editing, back up the existing host configuration. Add the rendered site
block without changing existing sites, then validate the complete configuration
before reloading Caddy:

```bash
cp -a /etc/caddy/Caddyfile /etc/caddy/Caddyfile.pre-linjian
caddy validate --config /etc/caddy/Caddyfile
systemctl reload caddy
systemctl is-active caddy
```

If validation fails, do not reload. Restore the backup and investigate the
reported line. A reload keeps existing connections and sites in place.

## 5. Verify the new server

First verify the public health endpoint:

```bash
curl --fail --silent --show-error "https://YOUR_DOMAIN/health"
```

Then verify one authenticated backend endpoint using a command that does not
echo the token. Do not use verbose HTTP output or include the token directly in
the command line. Confirm only the HTTP result and expected response shape; do
not print phone content or event details.

## 6. Cut over in this order

1. Change only the existing Render MCP service's `LINJIAN_URL` to the new HTTPS
   origin. Keep `LINJIAN_TOKEN` unchanged and do not deploy a new public MCP.
2. Verify the existing MCP can reach the new server.
3. On the phone, change only the server address in the app settings. Keep the
   token and device ID unchanged.
4. Start the phone service and verify with the official `get_phone_state` tool.
5. Report only whether the connection works, permissions are normal, state is
   fresh, and `calendar_state.events` still exists. Do not disclose screen
   content, calendar titles, tokens, or MCP URLs.

## Rollback

1. Change the phone's server address back to the previous Render address.
2. Change the existing Render MCP service's `LINJIAN_URL` back to its previous
   value.
3. Leave all existing Render services and data intact.

Keep the Render server available until every acceptance check above succeeds.

## Operations

Run Compose commands from `deploy/selfhost` so the private `.env` is loaded:

```bash
docker compose ps
docker compose restart server
docker compose up -d --build server
```

The named volume `selfhost_linjian_data` stores screenshots,
`companion_state.json`, and `activity_events.json`. Do not run
`docker compose down -v`; the `-v` flag deletes persistent volumes.
