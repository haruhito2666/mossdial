# Mossdial

Mossdial is a local-first Android web host. It serves a user-controlled website over loopback by default with a from-scratch Kotlin HTTP server, adds optional local HTTPS with a self-signed certificate, can publish through a Cloudflare Tunnel, and can host an on-device model (GGUF chat via llama.cpp, ONNX text embeddings via the official ONNX Runtime) and expose both through a token-protected local AI API.

The Android application and build system are in [`mossdial/`](./mossdial/). See its [README](./mossdial/README.md) for architecture, build instructions, features, and project layout.

## Build

```bash
cd mossdial
./gradlew :app:assembleDebug
```

Release signing is intentionally not included. See the project README for the required environment variables and unsigned release behavior.

## Contributing

Read [CONTRIBUTING.md](./CONTRIBUTING.md) before submitting changes. Report suspected vulnerabilities according to [SECURITY.md](./SECURITY.md). Release steps are documented in [RELEASING.md](./RELEASING.md).

## License

Apache License 2.0. See [LICENSE](./LICENSE) and [NOTICE](./NOTICE). Third-party license details are tracked in [`mossdial/LICENSES/THIRD_PARTY.md`](./mossdial/LICENSES/THIRD_PARTY.md).
