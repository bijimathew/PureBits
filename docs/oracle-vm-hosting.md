# PureBits on an Oracle VM

Assessment: 8 November 2026. This is a deployment assessment, not a record of a live deployment. No VM was accessed or changed.

## What can run there

| Component | Oracle VM suitability | What it does |
| --- | --- | --- |
| Android mobile app (`app/`) | Keep installed on the phone | Playback, downloads, local library, source matching, addon/module execution and USB audio run on Android. An APK is not an Ubuntu server process. |
| Go backend (`backend/`) | Yes, Linux amd64 or arm64 | Listen Together rooms, queue/playback synchronization, invite pages and live presence counts. It does **not** search addon sources, relay music or store the mobile library. |
| Web player (`web/app/`) | Yes, as static files | Browser UI and browser playback. It is a separate implementation, not a hosted copy of every Android feature. |
| Browser extension (`web/extension/`) | Build/package for users to install | Supplies the browser permissions and request handling the web player needs for YouTube, sign-in and other integrations. Hosting the site does not replace installation of this extension. |
| Addon/module files | Static index/JavaScript files can be hosted | A remote addon API requires its own server implementation. None is provided by the Listen Together backend. Android's module runtime stays on the phone. |

For primarily mobile use, the smallest useful deployment is **the Go backend only**. Normal music playback already works independently of that backend. Hosting it gives you your own Listen Together service; it does not make mismatched addon results more accurate or move playback onto the VM.

The backend already includes an Ubuntu installer, Caddy configuration and systemd service under [`backend/deploy/`](../backend/deploy/). Its installer selects `linux-amd64` on `x86_64` and `linux-arm64` on `aarch64`, covering Oracle AMD and Ampere VM architectures. This is source-level compatibility evidence; a deployment has not been tested against your VM.

## Changes needed before deployment

1. Choose your own API hostname, for example `api.purebits.example.com`, with DNS pointing at the VM. Allow inbound TCP 80/443 in the relevant OCI security rules **and** the VM firewall. Leave port 8000 private: the Go server currently binds `0.0.0.0:8000`, and the supplied service trusts proxy headers. OCI network rules and host firewall rules both matter. See [Oracle security lists](https://docs.oracle.com/en-us/iaas/Content/Network/Concepts/securitylists.htm).
2. Use your fork as the source. **Change `backend/deploy/bitchord-deploy.sh` before using automated deployments:** it clones `https://github.com/kushagrasinghx/BitChord.git`, fetches `main`, and resets the checkout. Unchanged, it deploys upstream rather than PureBits. Existing `/opt/bitchord-src` checkouts must also have their remote checked and corrected.
3. Retarget `.github/workflows/deploy-backend.yml` to your own host and health URL. Its current health check points at the upstream API. Set your own `ORACLE_HOST`, `ORACLE_DEPLOY_KEY` and pinned `ORACLE_KNOWN_HOSTS` only if you choose this deployment method.
4. Replace the upstream origins in `backend/deploy/bitchord-jam.service`. For a web deployment, set `JAM_ALLOWED_ORIGINS=https://music.purebits.example.com` and `JAM_PUBLIC_ORIGIN=https://api.purebits.example.com`. Android does not require a browser Origin allowlist. Keep `JAM_TRUST_PROXY=true` only with the trusted reverse proxy and a private backend port.
5. Point the Android app's **Listen Together custom server** setting at your API origin. This requires no APK rebuild. To make it the build default, set `LISTEN_TOGETHER_SERVER=https://api.purebits.example.com` in the root `local.properties` or build environment, then rebuild. The current Gradle fallback is an upstream Render URL; the comments suggesting an empty default are outdated.
6. Monitor a real backend endpoint. The supplied Caddyfile answers `/healthz` itself with `{"ok":true}`, even if Go has stopped. Use `/api/time` for an external backend check, or change `/healthz` to proxy upstream. This also affects the bundled watchdog and CI checks.

The installer modifies `/etc/caddy/Caddyfile`, systemd, firewall rules, swap and kernel settings. On an existing VM that hosts other services, review and merge those settings before running it; replacing the Caddyfile can remove the other sites. It targets Ubuntu 22.04/24.04 with `apt`, not Oracle Linux's package manager.

The installer defaults to Go 1.27.0 and `backend/go.mod` requires Go 1.27.0. A supported newer patch can be selected with `GO_VERSION`; verify the version and architecture on the [official Go downloads page](https://go.dev/dl/) before installation. Do not lower the module's Go requirement merely to use an older distro package.

## Backend deployment outline

These commands are for a fresh Ubuntu VM, from a copy of **your fork**, after applying the configuration changes above. Substitute a domain you control. They have not been executed on a VM in this task.

```sh
cd /path/to/PureBits/backend
go vet ./...
go test ./...

# Only after reviewing deploy/setup.sh and the files it installs:
sudo DOMAIN=api.purebits.example.com bash deploy/setup.sh

sudo systemctl status bitchord-jam --no-pager
curl --fail http://127.0.0.1:8000/api/time
curl --fail https://api.purebits.example.com/api/time
sudo journalctl -u bitchord-jam -n 50 --no-pager
```

Retaining the internal `bitchord-jam` service name is harmless. Renaming it is optional, but references across the installer, deploy hook, service and watchdog must then change together.

Keep one backend instance/process. Party state is in memory, so a restart ends active rooms, and multiple independent instances do not share rooms. Ordinary playback on the phone remains independent. Creating and joining rooms trusts the profile identity supplied by the client; bearer tokens protect subsequent membership actions, but this is not a private account-verified server. Add server-side identity verification or access control if you need to limit who can create rooms.

## Optional: host the web player on the same VM

Use separate hostnames for the site and API. The site is a Vite production build; no Node server is needed after building.

Build from the repository root on a machine with the repository's Node/pnpm requirements (`web/package.json` currently specifies Node >=22 and pins pnpm):

```sh
corepack pnpm --dir web install --frozen-lockfile
BITCHORD_SITE_ORIGINS=https://music.purebits.example.com \
VITE_PARTY_SERVER_URL=https://api.purebits.example.com \
corepack pnpm --dir web build:site
```

`build:site` packages Chromium/Firefox extensions and copies the downloadable ZIPs into the site before building it. `VITE_PARTY_SERVER_URL` is mandatory for production builds. All `VITE_*` values are public build-time settings, not secret storage. If your Corepack installation is unavailable, install the exact pnpm version pinned in `web/package.json` through its supported installation method.

Copy the **contents** of `web/app/dist/` to a dedicated directory such as `/srv/purebits/web/`. Add a site block to the VM's Caddy configuration, alongside the API block:

```caddyfile
music.purebits.example.com {
    root * /srv/purebits/web
    encode zstd gzip
    try_files {path} /index.html
    file_server
}
```

The index fallback is required for React Router paths such as `/album/...` and `/invite/...`. Make sure the `caddy` service user can read the published files. Validate with `caddy validate --config /etc/caddy/Caddyfile` before reloading. This follows Caddy's [single-page app hosting pattern](https://caddyserver.com/docs/caddyfile/patterns#single-page-apps-spas).

The downloaded extension must be rebuilt with your **exact** site origin in `BITCHORD_SITE_ORIGINS`. Otherwise the default extension trusts only the upstream domains and localhost, and your hosted site will not connect. These defaults live in `web/extension/site.config.ts`; the setting also controls manifest permissions, Firefox relay matching and request rules. Store builds may need `VITE_EXTENSION_ID_CHROMIUM` set to their assigned extension ID.

The web player's YouTube API/media requests leave from the listener's browser, with extension assistance; they are not proxied through this Go backend. Hosting on Oracle therefore does not move YouTube streaming traffic to the VM or remove browser CORS/extension requirements. Hosting also does not port Android addon/module support to the web player: no equivalent source registry/resolver is wired into the current web playback flow. That would be additional development.

## Invite links and fork branding

An own-server deployment works through manual server selection and party codes. The backend's `/invite/{code}` page already generates an Android deep link containing its own server URL.

Fully branded default invite links need extra changes:

- `web/app/src/party/invite.ts` hardcodes the upstream `INVITE_ORIGIN`. `ListenTogetherPage.tsx` uses that origin when the server equals the configured default, so setting `VITE_PARTY_SERVER_URL` alone does not fix the shared link. Make the invite origin configurable or derive it from the active backend.
- `app/.../data/listentogether/JamInviteLink.kt` and the mobile manifest recognize the upstream HTTPS invite domain. Own-domain verified Android App Links need the fork domain in both places, plus a matching `/.well-known/assetlinks.json` with the actual Android application ID and signing certificate fingerprint. The existing `bitchord://party/...` custom scheme works through the backend landing page without that domain migration.
- `web/app/src/config.ts` still links to upstream Android releases by default. Set `VITE_ANDROID_APP_URL` to your fork's APK release page. Rename extension/site metadata separately if desired.

## VM size and verification limits

For a few personal Listen Together rooms plus a static site, the architecture is modest: one Go process and Caddy, with no database or server audio processing. Actual memory/CPU capacity depends on listeners, queues, other VM services and traffic; benchmark before increasing the room limits. Build the web bundle elsewhere if the VM is small.

Do not assume old Oracle Free Tier quotas or guaranteed uptime. Check your tenancy's current shape/region/limits. Oracle documents that idle Always Free compute instances may be reclaimed; see [Always Free resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm). Keep your source, deployment configuration and any future persistent data backed up.

This assessment inspected source and deployment configuration and checked the cited official documentation. Go is not installed in the local workspace environment, so backend tests/builds were not run here. Neither backend nor web production deployment has been verified on your VM.

Before a deployment can be prepared for your actual machine, the remaining facts are its OS/version, CPU architecture/shape, RAM, existing services on 80/443/8000, intended hostname(s), and whether you want just the mobile companion backend or also the browser player. No passwords or private SSH keys are needed to make those decisions.
