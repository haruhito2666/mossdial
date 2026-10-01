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
  shows the state and starts or stops the server.
- A file manager for the served directory: import from device storage, browse, create a file or
  folder, rename, delete, with the same path checks the server uses.
- Optional HTTPS. The key is created once inside the platform keystore and cannot be read out of the
  app; the signed certificate is stored app-private so the same one is presented on every start. A
  certificate regenerated per launch would make a browser exception or a pinned fingerprint useless
  the next morning, so it is deliberately kept.

### Publishing it

- Optional Cloudflare Tunnel. The app runs a `cloudflared` executable **you** supply, by absolute
  path, through an explicit argument vector with no shell, and reads its output with a bound. No
  tunnel binary and no Cloudflare SDK is bundled, and the app works fully without one.
- It is a token-based named tunnel, so the public hostname and the ports it reaches live in your own
  Cloudflare configuration rather than in the app. Map only the site's port there; the app cannot
  enforce that for you, and the API port is the one worth keeping to yourself.
- The tunnel token is held in a from-scratch Android Keystore AES-GCM store rather than
  `EncryptedSharedPreferences`, in its own slot under its own key alias, so it cannot be lifted out
  with the AI API token's key or vice versa.

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

### Everything else

- Onboarding on first run, then five tabs: Server, Files, Tunnel, AI, Settings.
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
| JVM unit tests | 348 passing across 33 classes |
| `assembleDebug`, `assembleRelease`, `assembleAndroidTest` | pass |
| `lintDebug`, `lintVitalRelease` | no errors |
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

Requirements: JDK 17, Android SDK 35, NDK 27 (`27.0.12077973`) and CMake 3.22.1. The NDK and CMake
are needed because the llama.cpp runtime is compiled from the vendored sources rather than shipped
as a binary; the app targets `arm64-v8a` and `x86_64`.

```bash
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:testDebugUnitTest    # JVM tests
./gradlew :app:lintDebug            # lint
```

The full check used before a release:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease \
  :app:assembleAndroidTest :app:lintDebug :app:lintVitalRelease
```

Release signing is configured but no key material is in the repository: set the four
`MOSSDIAL_KEYSTORE_*` environment variables described in [RELEASING.md](../RELEASING.md), or drop a
keystore at `mossdial/keystore/release.keystore`, which is git-ignored. Without either, a release
build still assembles, unsigned.

## Dependencies

The official Android Gradle, Kotlin, Compose and AndroidX toolchain, plus exactly two third-party
runtimes: llama.cpp, compiled from the pinned vendored sources, and the
`com.microsoft.onnxruntime:onnxruntime-android` AAR. CivetWeb, mbedTLS, Rust, ZXing, kotlinx
serialization and kotlinx.coroutines are all absent; the AI work runs on `java.util.concurrent`
executors and Compose snapshot state. See [LICENSES/THIRD_PARTY.md](LICENSES/THIRD_PARTY.md).

Dependency checksums are pinned in `gradle/verification-metadata.xml`; Gradle refuses to resolve an
artifact whose checksum is not in that file, so a substituted jar fails the build rather than the
device.

## Project layout

| Path | Role |
|------|------|
| `app/src/main/java/com/mossdial/MainActivity.kt` | Tabs, notification permission, the Server screen and its traffic card |
| `app/src/main/java/com/mossdial/server/` | HTTP server, path policy, ranges, caching, gzip, rate limiting, statistics, DER encoder, certificate, TLS identity |
| `app/src/main/java/com/mossdial/aiapi/` | The second listener: routes, bearer token, JSON, bounds, health |
| `app/src/main/java/com/mossdial/service/` | Foreground service, notifications, boot receiver, start/stop policy, shared state |
| `app/src/main/java/com/mossdial/tunnel/` | The user-configured `cloudflared` process, argv, bounded status |
| `app/src/main/java/com/mossdial/data/` | Web root and file store, settings, Keystore secret store, model store, import, download, AI API settings |
| `app/src/main/java/com/mossdial/ai/` | llama.cpp JNI wrapper, ONNX Runtime wrapper, WordPiece tokenizer, encoder detection, shared AI controller |
| `app/src/main/java/com/mossdial/ui/` | Files, Tunnel, AI, Settings and onboarding screens, theme |
| `app/src/main/java/com/mossdial/widget/` | Home-screen widget and its start/stop actions |
| `app/src/main/cpp/` | The JNI bridge for GGUF loading and generation |
| `app/src/main/cpp/third_party/llama.cpp/` | Pinned upstream runtime sources, pruned, with licences |
| `app/src/main/res/` | Adaptive launcher icon, widget layout, strings, data-extraction rules |
| `app/proguard-rules.pro` | The keeps both third-party runtimes need, since neither ships consumer rules |
| `app/src/test/` | JVM tests for protocol, security, tokenizer, encoder, model store, tunnel, service and API behaviour |

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
  token. The port is plain HTTP, so a caller with the token can make the device generate text and
  embed text; do not enable LAN mode for it on an untrusted network. A Cloudflare tunnel is configured
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
