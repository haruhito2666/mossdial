# Mossdial

Mossdial is a local-first Android web host. It serves a site you control from the phone over
loopback by default, through an HTTP server written from scratch in Kotlin on platform sockets. It
can add HTTPS with a certificate the app keeps, publish through a Cloudflare Tunnel, host a model on
the device (GGUF chat through llama.cpp, ONNX text embeddings through the official ONNX Runtime), and
expose that model through a token-protected local API that speaks the OpenAI chat and embeddings
shapes.

There is no account, no server operated by this project, and no analytics.

The Android application and build system are in [`mossdial/`](./mossdial/). Its
[README](./mossdial/README.md) is the real document: features, architecture, security defaults,
project layout, and exactly what has and has not been verified.

## Requirements

JDK 17, Android SDK 35, NDK 27 and CMake 3.22.1. The NDK and CMake are needed because the llama.cpp
runtime is compiled from vendored sources rather than shipped as a binary.

## Build

```bash
cd mossdial
./gradlew :app:assembleDebug
```

The full check used before a release:

```bash
cd mossdial
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease \
  :app:assembleAndroidTest :app:lintDebug :app:lintVitalRelease
```

As of now this passes: 348 JVM tests, all three assembles, and lint clean on both variants. No feature
has yet been exercised on a device or emulator — [RELEASING.md](./RELEASING.md) has the checklist
that closes that gap.

## Release signing

No signing material is in this repository. Set the `MOSSDIAL_KEYSTORE_*` environment variables
described in [RELEASING.md](./RELEASING.md), or place a keystore at the git-ignored path
`mossdial/keystore/release.keystore`. Without either, a release build still assembles, unsigned.

## Contributing

Read [CONTRIBUTING.md](./CONTRIBUTING.md) first. Report suspected vulnerabilities according to
[SECURITY.md](./SECURITY.md), and see [RELEASING.md](./RELEASING.md) for release steps.

## License

Apache License 2.0. See [LICENSE](./LICENSE) and [NOTICE](./NOTICE). Third-party licence details are
tracked in [`mossdial/LICENSES/THIRD_PARTY.md`](./mossdial/LICENSES/THIRD_PARTY.md), and dependency
checksums are pinned in
[`mossdial/gradle/verification-metadata.xml`](./mossdial/gradle/verification-metadata.xml).
