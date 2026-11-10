# Mossdial

<p align="center">
  <img src="branding/mossdial-mark-512.png" width="112" alt="Mossdial logo">
</p>

Mossdial is a local-first Android web host for publishing a website from your phone. It serves a
site you control over loopback by default, through an HTTP server written from scratch in Kotlin on
platform sockets, and can publish it through a Cloudflare Tunnel. It also supports local HTTPS.

There is no account, no server operated by this project, and no built-in site analytics. Hosting can
send content to clients you allow, and optional imports and tunnels contact the services
you choose. See [PRIVACY_POLICY.md](PRIVACY_POLICY.md) for details.

## Direction

The primary experience is publishing a useful website from a phone. A proposed marketplace would
let owners rent individual phones exclusively to customers for supported website and API workloads.
The app includes an interactive UI preview, but the marketplace is not implemented. A private
rental requires device-specific isolation and attestation to be proven first; see the
[phone-rental plan](PLAN-PHONE-RENTAL-CLOUD.md). Existing local hosting remains usable without a
marketplace account.

## What it does

### Hosting a site

- HTTP server written in Kotlin on `java.net`, with no embedded server library: `GET`/`HEAD` static
  serving, byte ranges, `ETag` and conditional requests, gzip when the client asks, per-client rate
  limiting, security headers, and request statistics.
- Bounded on every axis that a network can push on: request line, header count, header bytes, body,
  request timeout, connection count, worker pool, and a queue whose overflow is refused rather than
  grown.
- Path traversal and symlink protection on every file, and no directory listing.
- Loopback-only (`127.0.0.1`) by default. LAN binding is an explicit setting, never a default.
- Foreground service with a notification, optional start after reboot, and a home-screen widget that
  shows the state and starts or stops the server. The service requests `START_STICKY` so Android can
  restart it after process death; a start that fails is retried with a bounded backoff, and a
  watchdog attempts to reopen the listener if its accept loop dies. The app is designed to restore
  a running server after an update. The Settings tab can request an exemption from battery
  optimisation; that dialog is opt-in. Android and device-specific restrictions can still affect
  availability, and these recovery paths need device testing.
- A file manager for the served directory: import from device storage, browse, create a file or
  folder, rename, delete, with the same path checks the server uses. Any folder inside the web root
  can be chosen as the one the server hosts, so several sites can live side by side without moving
  files; the choice is validated again at server start.
- Static web apps are first-class: clean URLs (`/about` -> `about.html` or `about/index.html`, with
  `index.htm` accepted too), a single-page-app fallback to the entry page for extensionless routes,
  and the MIME types modern builds need (`.mjs`, `.cjs`, `.wasm`, `.woff2`, `.webmanifest`, `.avif`).
  The hosted folder carries a site type — custom static, SPA, Next.js export or another static export
  — so React, Vue, Svelte, TanStack, static Next.js/Astro exports and WebAssembly are served with the
  routing they expect. A build-time base path such as `/repo/` is detected and stripped, so a GitHub
  Pages build works when hosted at `/`. The Files tab checks the entry page and its referenced local
  assets before the browser is opened. A server-rendered framework needs a server process, which this
  app deliberately does not run.
- Hosted pages are served with no injected Content-Security-Policy. A general static host cannot know
  which origins a site loads, and the former `default-src 'self'` policy blocked inline bootstrap
  scripts, external fonts and images, API calls, `<base>`, and WebAssembly compilation. The server
  still applies `X-Content-Type-Options: nosniff`, path containment, and its own bounded protocol
  handling; a site that wants a CSP can send its own in the HTML or through a later per-site option.
- A backend runtime can be front-ended: server settings can proxy the public port to a loopback port
  where the user's own runtime listens — Next.js dev/server output, Node, Bun, Deno, Python, PHP or
  anything else that speaks HTTP. The proxy is transport-only: it adds `X-Forwarded-*`, relays the
  response, bounds request bodies and does not start, monitor or manage the process.
- A GitHub site import: paste `owner/repo` or a `github.com` URL, and the app fetches the archive
  from `codeload.github.com`, picks the folder that holds the site when it can (`out`, `docs`,
  `public`, `dist`, `build`, `site`, `_site`), copies it into the web root with the same path checks
  as a local import, and immediately makes that folder the hosted site. The detected base path and
  site type are applied too. Public repositories only; the archive is size-capped and a failed import
  leaves nothing behind.
- Local site zips: the Files tab's Import button accepts a `.zip` of a built site, extracts it
  through the same path-checked store, detects its base path and site type, and hosts it. A
  repository or zip that contains framework source (`src/main.tsx`, `.vue`, `.svelte`, SCSS, etc.)
  instead of built output is refused with an actionable message rather than served as a blank page.
- Optional HTTPS. The key is created once inside the platform keystore and cannot be read out of the
  app; the signed certificate is stored app-private so the same one is presented on every start. A
  certificate regenerated per launch would make a browser exception or a pinned fingerprint useless
  the next morning, so it is deliberately kept.

### Publishing it

- Optional Cloudflare Tunnel. The app ships a pinned `cloudflared` engine as `libcloudflared.so`
  inside the APK; Android extracts it to the executable native-library directory, so the normal flow
  is just "paste the token and press Start" — no ADB, `chmod`, or binary path. The engine is launched
  through an explicit argument vector with no shell, and its output is read with a bound.
- Advanced users can turn the packaged engine off and point at their own `cloudflared` executable by
  absolute path. The app works fully without a tunnel either way.
- It is a token-based named tunnel, so the public hostname and the ports it reaches live in your own
  Cloudflare configuration rather than in the app. Point the hostname to the site's address and port;
  the app cannot enforce your Cloudflare ingress settings. The token field accepts
  either the bare token or the full `cloudflared tunnel run --token ...` command Cloudflare shows.
- A second **Quick test URL** mode starts a temporary `trycloudflare.com` tunnel with no token or
  domain. The app captures the generated URL from `cloudflared`, shows it, and offers a Copy button;
  the URL stops working when the tunnel stops. It publishes the site port.
- The tunnel token is held in an Android Keystore AES-GCM store rather than
  `EncryptedSharedPreferences`.
- If `cloudflared` exits without a Stop, the app retries it twice, three seconds apart, and then
  reports the last exit. Stop cancels a retry that is waiting.

### Phone-rental preview

Open **Rentals** and choose **Rent a phone** or **Offer a phone**. The renter can inspect an example
listing and rental workspace. The owner can try example CPU, memory, storage and price fields, plus
charging and Wi-Fi switches. These inputs are temporary UI state: they do not configure the phone or
create an offer. Android may retain them to restore the screen after a configuration change.

The listing is illustrative, with no actual phone, price, verified resources or security profile.
Renting, listing, deployment and rental-management buttons are disabled. There is no marketplace
backend, account, lease, payment or payout system. Private rentals require the security and device
checks in the [plan](PLAN-PHONE-RENTAL-CLOUD.md) before launch.

### Everything else

- Onboarding on first run, then a bottom navigation bar with five destinations: Server, Files, Tunnel,
  Rentals and Settings.
- Traffic counters, and an optional in-memory request log that is **off by default**. With it off, no
  request path is recorded anywhere, logcat included. The counters record no path and are kept in
  memory for the current run.
- `allowBackup="false"` plus explicit data-extraction rules: nothing is backed up or transferred, and
  the TLS identity could not be restored usefully anyway, since its key stays on the device that made
  it.

## Status

The hosting features above are implemented; the Rentals tab is a UI-only preview. The current checks are:

| Check | Result |
| --- | --- |
| Release JVM tests | 186 passed |
| Release assembly, release lint and vital lint | passed |
| Android emulator, debug app | five-tab navigation, both rental journeys, preview inputs and switches, disabled rent/list actions, large text, server start/stop and local HTTP passed |
| Android emulator instrumentation, previous debug build | 31 passed, 2 failed, 2 skipped out of 35 tests |

The two failed tests on the previous debug build attempted an HTTPS handshake with the emulator's
Android Keystore identity; logs reported an incompatible key padding operation. The two skipped
tests require real hardware. These instrumentation tests were not rerun for the rental UI change.
**HTTPS and the rest of the release checklist still require real-device verification**; the emulator
result cannot establish whether hardware devices are affected. See [RELEASING.md](../RELEASING.md).

## Build

Requirements: JDK 17, Android SDK 35, NDK 27 (`27.0.12077973`) and Go 1.27 or newer.
The NDK supplies Android clang for the tunnel engine. Go compiles the pinned Cloudflare source;
the app targets `arm64-v8a` and `x86_64`.

```bash
./gradlew :app:assembleRelease      # release APK
./gradlew :app:testReleaseUnitTest  # JVM tests
./gradlew :app:lintRelease          # lint
```

The full check used before a release:

```bash
./gradlew :app:testReleaseUnitTest :app:assembleRelease \
  :app:lintRelease :app:lintVitalRelease
# If Gradle skips vital lint in the combined invocation:
./gradlew :app:lintVitalRelease
```

Release signing is configured but no key material is in the repository: set the four
`MOSSDIAL_KEYSTORE_*` environment variables described in [RELEASING.md](../RELEASING.md), or drop a
keystore at `mossdial/keystore/release.keystore`, which is git-ignored. Without either, a release
build still assembles, unsigned.

## Dependencies

The official Android Gradle, Kotlin, Compose and AndroidX toolchain and the pinned Cloudflare
`cloudflared` source compiled at build time and packaged as `libcloudflared.so` for the optional
tunnel. See [LICENSES/THIRD_PARTY.md](LICENSES/THIRD_PARTY.md).

The first build downloads the pinned `cloudflared` source tarball, verifies its SHA-256, and compiles
it for both Android ABIs with the NDK's clang and cgo. Cgo matters: a pure-Go build looks for DNS in
`/etc/resolv.conf`, which Android does not provide, so Cloudflare's API is unreachable; cgo routes
lookups through Android's own resolver instead. The generated `libcloudflared.so` files live under
`app/build` and are not stored in the repository. Dependency checksums are pinned in
`gradle/verification-metadata.xml`; Gradle refuses to resolve an artifact whose checksum is not in
that file, so a substituted jar fails the build rather than the device.

## Project layout

| Path | Role |
|------|------|
| `branding/` | Primary logo, transparent circular mark, 512px icon and the adaptive-icon derivation notes |
| `app/src/main/java/com/mossdial/MainActivity.kt` | Navigation shell, onboarding gate, notification permission |
| `app/src/main/java/com/mossdial/server/` | HTTP server, path policy, ranges, caching, gzip, rate limiting, statistics, DER encoder, certificate, TLS identity |
| `app/src/main/java/com/mossdial/service/` | Foreground service, notifications, boot receiver, start/stop policy, shared state |
| `app/src/main/java/com/mossdial/tunnel/` | The user-configured `cloudflared` process, argv, bounded status |
| `app/src/main/java/com/mossdial/data/` | Web root and file store, settings, imports and Keystore secret store |
| `app/src/main/java/com/mossdial/ui/` | Server, Files, Tunnel, Rentals preview, Settings, notifications and onboarding screens |
| `app/src/main/java/com/mossdial/ui/components/` | The shared pieces every screen is built from: section card, heading, label-over-value, status pill, switch row, notices, empty state |
| `app/src/main/java/com/mossdial/ui/theme/` | Colour palette, type scale, corner radii, the spacing scale, and the status colours Material has no slot for |
| `app/src/main/java/com/mossdial/widget/` | Home-screen widget and its start/stop actions |
| `app/src/main/res/` | Adaptive launcher icon, widget layout, strings, data-extraction rules |
| `app/proguard-rules.pro` | Release shrinker rules for platform components |
| `app/src/test/` | JVM tests for web protocol, security, storage, tunnel and service behaviour |
| `app/src/androidTest/` | Device smoke tests: the keystore TLS identity and its certificate across restarts, the real TLS handshake, the server over the platform socket stack, and the foreground service |

## Security defaults

- The server binds `127.0.0.1`. Static serving rejects request bodies and unsupported methods,
  never lists a directory, and never resolves a file outside the configured web root. The optional
  backend proxy has its own bounded request-body settings. Do not enable LAN mode on a network you
  do not trust.
- HTTPS uses a self-signed certificate from the app's own keystore entry. It is not trusted by
  anything, so a browser will warn; that is the trade for a certificate that survives restarts.
- Request logging is off. With it off no path reaches memory, disk or logcat. Turning it on keeps the
  last *N* requests in memory only, and nothing is ever written to storage.

## Open source

Licensed under Apache License 2.0. See [LICENSE](../LICENSE), [NOTICE](../NOTICE),
[CONTRIBUTING.md](../CONTRIBUTING.md), [SECURITY.md](../SECURITY.md) and
[RELEASING.md](../RELEASING.md). The privacy policy is [PRIVACY_POLICY.md](PRIVACY_POLICY.md).
