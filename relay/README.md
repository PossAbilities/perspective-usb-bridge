# Perspective camera relay

Lets the tablet's camera and microphone reach a Mac or PC on a **different
network** (tablet on mobile data or another Wi-Fi, Mac at home). Neither end
can accept an incoming connection there, so both dial out to this relay on
Ryan's Cloud and are paired by the code shown on the tablet.

- Host: `camrelay.pixelhub.org.uk` → `mhfa-caddy` → container `perspective-relay:8080`
- Stateless: no database, no volumes, nothing to back up.
- The relay forwards bytes without reading them. Traffic is TLS between each
  device and Cloudflare/Caddy; anyone without the tablet's pairing code
  (10 characters, ~50 bits) cannot join its stream.

## First deploy (once)

1. **Cloudflare:** A record `camrelay` → `178.104.251.61`, **Proxied**. Zone SSL
   stays **Full**.
2. **GitHub repo secrets** (Settings → Secrets and variables → Actions), the same
   three as the other Ryan's Cloud repos: `VPS_HOST` = `178.104.251.61`,
   `VPS_USER` = `root`, `VPS_SSH_KEY` = the deploy key, base64-encoded.
3. **Merge to `main`.** The *Deploy camera relay to VPS* workflow ships
   `relay/` to `/opt/perspective-relay/app` and runs `setup.sh`, which builds
   the container, joins it to `mhfa-caddy`'s network, adds the Caddy block
   (backing up the Caddyfile first) and restarts Caddy that one time.
4. **Check:** `https://camrelay.pixelhub.org.uk/health` says `ok`.
5. Add the site to `RYAN-CLOUD-SITES.md`.

After that, any push to `main` that touches `relay/` redeploys it.

## Protocol

```
wss://camrelay.pixelhub.org.uk/relay?role=tablet&code=<CODE>
wss://camrelay.pixelhub.org.uk/relay?role=viewer&code=<CODE>
```

Binary messages are piped unchanged between the tablet and the viewer of the
same code. Close codes: `4000` bad role/code, `4004` tablet not connected,
`4009` replaced by a newer connection, `4010` the other side left (the tablet
then reconnects for the next viewer).

## Develop

```
cd relay && npm install && npm test
```
