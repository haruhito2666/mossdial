# Mossdial

Mossdial is a local-first Android web host. It serves a site you control from the phone, over
loopback by default, through an HTTP server written from scratch in Kotlin on platform sockets. It
can add HTTPS with a certificate the app keeps, publish through a Cloudflare Tunnel, host a model on
the device, and expose that model through a token-protected local API.

There is no account, no server operated by this project, and no analytics. Everything it serves and
everything it runs stays on the device unless you turn on a tunnel yourself.

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
  shows the state and starts or stops the server. The service returns `START_STICKY`, so a process
  crash brings the server back without the app being opened; a start that fails is retried with a
  bounded backoff, and a watchdog reopens the listener if its accept loop dies. An app update
  restores a server that was running. The Settings tab can ask Android to stop battery-optimising
  the app, which is what lets it answer while the phone is idle; that dialog is opt-in and never
  raised on its own.
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
  Cloudflare configuration rather than in the app. Map only the site's port there; the app cannot
  enforce that for you, and the API port is the one worth keeping to yourself. The token field accepts
  either the bare token or the full `cloudflared tunnel run --token ...` command Cloudflare shows.
- A second **Quick test URL** mode starts a temporary `trycloudflare.com` tunnel with no token or
  domain. The app captures the generated URL from `cloudflared`, shows it, and offers a Copy button;
  the URL stops working when the tunnel stops. It publishes only the site port, not the AI API.
- The tunnel token is held in a from-scratch Android Keystore AES-GCM store rather than
  `EncryptedSharedPreferences`, in its own slot under its own key alias, so it cannot be lifted out
  with the AI API token's key or vice versa.
- If `cloudflared` exits without a Stop, the app retries it twice, three seconds apart, and then
  reports the last exit. Stop cancels a retry that is waiting.

### On-device models

- **GGUF chat** through llama.cpp, compiled from pinned upstream sources vendored in
  `app/src/main/cpp/third_party/llama.cpp`. Load a model, set context size, max tokens and
  temperature, stream a reply, stop it, unload it.
- **ONNX text embeddings** through the official `com.microsoft.onnxruntime:onnxruntime-android` AAR,
  with a WordPiece tokenizer written from scratch. The encoder is detected from the graph itself,
  so a model that is not a text encoder is refused with the reason rather than fed guessed tensors;
  the result is mean pooled over the attention mask and L2 normalised.
- A `vocab.txt` sidecar is required for embeddings, because a WordPiece vocabulary cannot be derived
  from the graph. It is imported next to the model (`<model>.txt`, or one shared `vocab.txt`),
  checked for the three tokens a WordPiece pass needs, and bounded at 4 MiB.
- Models are imported through `ContentResolver` or downloaded over HTTPS with `HttpURLConnection`,
  and live in `noBackupFilesDir/models`, outside the served web root. Downloads are bounded, stream
  to a temporary file, and are renamed into place only after the last byte; GGUF files have their
  magic header checked.

### The local AI API

A second listener on its own port, 8081 by default, on its own socket and its own thread so a slow
inference can never hold up the site.

| Route | Purpose |
| --- | --- |
| `GET /health` | Runtime state, the loaded chat model, the selected encoder, uptime |
| `GET /v1/models` | Models the API can name, with the loaded one marked |
| `POST /v1/chat/completions` | One reply from the loaded GGUF model |
| `POST /v1/embeddings` | One sentence vector per input, from the selected ONNX encoder |

- Every route, including the health check, needs a bearer token: 256 bits of `SecureRandom` output
  in unpadded base64url, stored in the same Keystore-backed store as the tunnel token but under a
  different key alias, compared with the platform's constant-time comparison, and rotatable from the
  AI screen without touching the model.
- Bounded like the file server: request line, header count and bytes, body, read and write timeouts,
  per-client rate limit, fixed pool with a bounded queue, and no keep-alive, so the state a client
  can pin here is as small as it can be.
- JSON is read and written by hand, with a bounded reader (depth, value count, string length) and one
  escaping writer. Model output is escaped rather than trusted, and a token count is left out
  instead of invented.
- The API answers with the models the AI tab has loaded, through one shared controller, so there is
  never a second copy of the weights in memory and the model on screen is the model answering.
- Optional HTTPS, using the same persisted self-signed identity as the site. It is off by default
  because a client has to trust that certificate either way; with LAN access on and HTTPS off, the
  screen says that the bearer token crosses the network in clear text.

### Everything else

- Onboarding on first run, then a bottom navigation bar with five destinations: Server, Files, Tunnel,
  AI, Settings.
- Traffic counters, and an optional in-memory request log that is **off by default**. With it off, no
  request path is recorded anywhere, logcat included. The counters record no path and are always
  kept.
- `allowBackup="false"` plus explicit data-extraction rules: nothing is backed up or transferred, and
  the TLS identity could not be restored usefully anyway, since its key stays on the device that made
  it.

## Status

Everything above is implemented and wired. What that means today, precisely:

| Checked | Result |
| --- | --- |
| JVM unit tests (release variant) | 423 passing |
| `assembleRelease` | pass |
| `lintRelease`, `lintVitalRelease` | no errors |
| Dependency verification | enforced; a tampered checksum fails the build |
| Published file set | 522 files: sources, the pruned licence-complete llama.cpp tree, and docs |

**Not yet checked, because no device or emulator was available:** every one of these features has run
only in JVM tests. The HTTPS listener, the foreground service, the notification, the boot receiver,
the widget, the file import picker, a real `cloudflared` process, a real GGUF model through
llama.cpp, and a real ONNX encoder with its vocabulary. The release build has also never been
installed, which matters because neither third-party runtime ships shrinker rules and the app's own
`app/proguard-rules.pro` is what keeps the JNI entry points alive. See [RELEASING.md](../RELEASING.md)
for the device checklist.

## Build

Requirements: JDK 17, Android SDK 35, NDK 27 (`27.0.12077973`), CMake 3.22.1 and Go 1.27 or newer.
The NDK and CMake are needed because the llama.cpp runtime is compiled from the vendored sources
rather than shipped as a binary, and the NDK also supplies the Android clang that builds the tunnel
engine. Go compiles that engine from the pinned Cloudflare source; the app targets `arm64-v8a` and
`x86_64`.

```bash
./gradlew :app:assembleRelease      # release APK
./gradlew :app:testReleaseUnitTest  # JVM tests
./gradlew :app:lintRelease          # lint
```

The full check used before a release:

```bash
./gradlew :app:testReleaseUnitTest :app:assembleRelease \
  :app:lintRelease :app:lintVitalRelease
```

Release signing is configured but no key material is in the repository: set the four
`MOSSDIAL_KEYSTORE_*` environment variables described in [RELEASING.md](../RELEASING.md), or drop a
keystore at `mossdial/keystore/release.keystore`, which is git-ignored. Without either, a release
build still assembles, unsigned.

## Dependencies

The official Android Gradle, Kotlin, Compose and AndroidX toolchain, plus llama.cpp, compiled from
the pinned vendored sources, the `com.microsoft.onnxruntime:onnxruntime-android` AAR, and the pinned
Cloudflare `cloudflared` source compiled at build time and packaged as `libcloudflared.so` for the
optional tunnel.
CivetWeb, mbedTLS, Rust, ZXing, kotlinx serialization and kotlinx.coroutines are all absent; the AI
work runs on `java.util.concurrent` executors and Compose snapshot state. See
[LICENSES/THIRD_PARTY.md](LICENSES/THIRD_PARTY.md).

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
| `app/src/main/java/com/mossdial/MainActivity.kt` | Navigation shell, onboarding gate, notification permission |
| `app/src/main/java/com/mossdial/server/` | HTTP server, path policy, ranges, caching, gzip, rate limiting, statistics, DER encoder, certificate, TLS identity |
| `app/src/main/java/com/mossdial/aiapi/` | The second listener: routes, bearer token, JSON, bounds, health |
| `app/src/main/java/com/mossdial/service/` | Foreground service, notifications, boot receiver, start/stop policy, shared state |
| `app/src/main/java/com/mossdial/tunnel/` | The user-configured `cloudflared` process, argv, bounded status |
| `app/src/main/java/com/mossdial/data/` | Web root and file store, settings, Keystore secret store, model store, import, download, AI API settings |
| `app/src/main/java/com/mossdial/ai/` | llama.cpp JNI wrapper, ONNX Runtime wrapper, WordPiece tokenizer, encoder detection, shared AI controller |
| `app/src/main/java/com/mossdial/ui/` | Server, Files, Tunnel, AI, Settings and onboarding screens |
| `app/src/main/java/com/mossdial/ui/components/` | The shared pieces every screen is built from: section card, heading, label-over-value, status pill, switch row, notices, empty state |
| `app/src/main/java/com/mossdial/ui/theme/` | Colour palette, type scale, corner radii, the spacing scale, and the status colours Material has no slot for |
| `app/src/main/java/com/mossdial/widget/` | Home-screen widget and its start/stop actions |
| `app/src/main/cpp/` | The JNI bridge for GGUF loading and generation |
| `app/src/main/cpp/third_party/llama.cpp/` | Pinned upstream runtime sources, pruned, with licences |
| `app/src/main/res/` | Adaptive launcher icon, widget layout, strings, data-extraction rules |
| `app/proguard-rules.pro` | The keeps both third-party runtimes need, since neither ships consumer rules |
| `app/src/test/` | JVM tests for protocol, security, tokenizer, encoder, model store, tunnel, service and API behaviour |
| `app/src/androidTest/` | Device smoke tests: the keystore TLS identity and its certificate across restarts, the real TLS handshake, the server over the platform socket stack, and the foreground service |

## Security defaults

- The server binds `127.0.0.1`. It rejects request bodies and unsupported methods, never lists a
  directory, and never resolves a file outside the configured web root. Do not enable LAN mode on a
  network you do not trust.
- HTTPS uses a self-signed certificate from the app's own keystore entry. It is not trusted by
  anything, so a browser will warn; that is the trade for a certificate that survives restarts.
- Request logging is off. With it off no path reaches memory, disk or logcat. Turning it on keeps the
  last *N* requests in memory only, and nothing is ever written to storage.
- Model weights and vocabularies are never served: they live in `noBackupFilesDir/models`, outside
  the web root, and the model store refuses symlinked directories, traversal, and anything that is
  not a plain `.gguf`, `.onnx` or vocabulary `.txt` file.
- The AI API binds `127.0.0.1:8081` unless LAN access is turned on, and every route needs the bearer
  token. HTTPS is available as an option and uses the same self-signed identity as the site; without
  it, a caller with the token can read that token from the network and make the device generate and
  embed text, so LAN access without HTTPS warns on the AI screen. A Cloudflare tunnel is configured
  on Cloudflare's side, so excluding the API port from it is the user's job, and the app says so
  rather than implying it did it.
- An encoder is only given text it can hold: 64 KiB of input, sequences truncated to the graph's own
  limit, and a word that no WordPiece piece matches becomes one `[UNK]` rather than being dropped or
  exploded into characters. An embedding request and a chat turn share one slot, so they can never
  be inside llama.cpp and ONNX Runtime at the same time.
- An encoder with a vocabulary that does not match its graph produces wrong vectors, not an error.
  The vocabulary is a sidecar the user supplies, and it is the one part of this that cannot be
  verified from the file alone.

## Open source

Licensed under Apache License 2.0. See [LICENSE](../LICENSE), [NOTICE](../NOTICE),
[CONTRIBUTING.md](../CONTRIBUTING.md), [SECURITY.md](../SECURITY.md) and
[RELEASING.md](../RELEASING.md). The privacy policy is [PRIVACY_POLICY.md](PRIVACY_POLICY.md).
