# Mossdial

Mossdial is a clean-room local web host for Android. The current milestone is a from-scratch Kotlin HTTP server with a small Compose control surface.

## Current milestone

- HTTP server implemented in Kotlin using platform sockets.
- Loopback-only binding by default; LAN binding is explicit.
- GET/HEAD-only static file serving with path traversal protection, no directory listing, bounded headers, security headers, and a fixed worker pool.
- Foreground service lifecycle and basic start/stop UI.
- Optional HTTPS with a certificate that is generated on the device and kept: the private key is
  created inside the platform keystore and never leaves it, and the public certificate is stored
  app-private so the same one is presented on every start. A generated key per start would make a
  trusted certificate untrusted again on the next launch.
- Traffic counters, and an optional in-memory request log that is off by default and never written
  to disk or logcat. The logcat request line is behind the same setting, so turning logging off
  really does stop paths from being recorded.
- Optional Cloudflare tunnel support: the app launches a cloudflared executable the user already
  supplies, with an explicit argument vector and no shell. No tunnel binary is bundled.
- Tunnel tokens are stored with a from-scratch Android Keystore AES-GCM secret store, not
  `EncryptedSharedPreferences`.
- On-device AI tab: GGUF loading and chat through llama.cpp, and ONNX text embeddings through
  the official ONNX Runtime Android AAR, with a from-scratch WordPiece tokenizer. These are the
  only two third-party runtimes the project ships.
- Model weights are imported through `ContentResolver` or downloaded with
  `HttpURLConnection`, and live in `noBackupFilesDir/models`, outside the served web root.
- ONNX text encoders, the shape every `sentence-transformers` text model exports to: integer
  `input_ids`/`attention_mask`, an optional `token_type_ids`, and a float output. The encoder
  is detected from the graph itself, so a model that does not fit is refused with a reason
  instead of being fed guessed tensors. Text is encoded by a from-scratch WordPiece
  tokenizer, the result is mean pooled over the attention mask and L2 normalised, and the
  vector is what the app keeps.
- A `vocab.txt` sidecar is required for embeddings, because a WordPiece vocabulary cannot be
  derived from the graph. It is imported next to the model (`<model>.txt`, or one shared
  `vocab.txt`), checked for the three tokens a WordPiece pass needs, and bounded at 4 MiB.
- A local AI API on its own port, 8081 by default: `GET /health`, `GET /v1/models`,
  `POST /v1/chat/completions` and `POST /v1/embeddings`, written with `ServerSocket` and a
  hand-rolled bounded JSON reader and writer. It answers with the models the AI tab has loaded,
  through one shared controller rather than a second copy of the weights, and it is started and
  stopped with the web server.

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

Dependency checksums are pinned in `gradle/verification-metadata.xml`; Gradle refuses to resolve an
artifact whose checksum is not listed there.

The project uses the official Android Gradle, Kotlin, Compose, and AndroidX toolchain, plus two
third-party runtimes: llama.cpp, compiled from the pinned sources in
`app/src/main/cpp/third_party/llama.cpp`, and the `com.microsoft.onnxruntime:onnxruntime-android`
AAR. See [`LICENSES/THIRD_PARTY.md`](LICENSES/THIRD_PARTY.md). CivetWeb, mbedTLS, Rust, ZXing and
serialization libraries are not bundled, and the AI feature uses no kotlinx libraries.

## Project layout

| Path | Role |
|------|------|
| `app/src/main/java/com/mossdial/server/` | From-scratch HTTP server, path policy, certificate, TLS identity |
| `app/src/main/java/com/mossdial/aiapi/` | Second listener: the local OpenAI-shaped AI API, its token, JSON and bounds |
| `app/src/main/java/com/mossdial/service/` | Foreground server lifecycle |
| `app/src/main/java/com/mossdial/tunnel/` | User-configured cloudflared process and bounded status |
| `app/src/main/java/com/mossdial/data/` | Web root, server settings, Keystore-backed secret store, model store/import/download |
| `app/src/main/java/com/mossdial/ai/` | llama.cpp JNI wrapper, ONNX Runtime wrapper, WordPiece tokenizer, encoder detection, AI tab state |
| `app/src/main/cpp/` | JNI bridge for GGUF loading and generation |
| `app/src/main/cpp/third_party/llama.cpp/` | Pinned upstream llama.cpp runtime sources, pruned |
| `app/src/main/java/com/mossdial/ui/theme/` | Compose theme |
| `app/src/test/` | JVM tests for security and protocol logic |

## Security defaults

The server binds to `127.0.0.1`, rejects request bodies and unsupported methods, does not list directories, and never resolves files outside the configured web root. Do not enable LAN mode on an untrusted network.

Model weights are never served: they live in `noBackupFilesDir/models`, which is outside the web root, and the model store refuses symlinked directories, traversal, and anything that is not a plain `.gguf` or `.onnx` file. Downloads are HTTPS only, bounded at 8 GiB, and stream to a temporary file that is renamed into place only after the last byte. GGUF imports and downloads are checked for the `GGUF` magic header.

An encoder is only given text it can hold: input is capped at 64 KiB, sequences are truncated to
the graph's own limit, 512 tokens by default, and a word that no WordPiece piece matches becomes one
`[UNK]` rather than being dropped. The embedding path takes the same single generation slot as chat,
so an embedding request and a chat turn can never be inside the runtimes at the same time.

The local AI API binds `127.0.0.1:8081` unless LAN access is turned on, and every route including
the health check needs the bearer token. That token is 256 bits of `SecureRandom` output stored in
the same Keystore-backed AES-GCM store as the tunnel token, but in its own slot and under its own
key alias, so neither credential can be recovered with the other's key. Requests are bounded: a
capped request line, header count and header bytes, a capped body, a read and a write timeout, a
per-client rate limit, and a fixed worker pool with a bounded queue whose overflow connections are
closed rather than queued. The token is compared with the platform's constant-time comparison.
The API runs the model the AI tab has loaded and holds the same single generation slot the tab
does, so a request cannot be answered from a second copy of the weights or from two generations at
once. Do not enable LAN mode on an untrusted network: the port is plain HTTP, and a caller with
the token can make the device generate text.

## Open source

This project is licensed under Apache License 2.0. See [`LICENSE`](../LICENSE), [`NOTICE`](../NOTICE), [`CONTRIBUTING.md`](../CONTRIBUTING.md), and [`SECURITY.md`](../SECURITY.md).
