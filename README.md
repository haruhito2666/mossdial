# Mossdial

<p align="center">
  <img src="mossdial/branding/mossdial-mark-512.png" width="112" alt="Mossdial logo">
</p>

Mossdial is a local-first Android web host for publishing a website from your phone. It serves a
site you control over loopback by default, through an HTTP server written from scratch in Kotlin on
platform sockets, and can publish it through a Cloudflare Tunnel. It also supports local HTTPS.

There is no account, no server operated by this project, and no built-in site analytics or project
telemetry.

The Android application and build system are in [`mossdial/`](./mossdial/). Its
[README](./mossdial/README.md) is the real document: features, architecture, security defaults,
project layout, and exactly what has and has not been verified.

## Direction

Mossdial currently hosts websites locally and through an optional tunnel. The new **Rentals** tab
previews both sides of a proposed marketplace: renting an individual phone and offering a phone.
Listings, rentals, deployment and payments are not implemented. Private rentals require
security isolation and attestation to be proven on supported devices first. See the
[phone-rental plan](./mossdial/PLAN-PHONE-RENTAL-CLOUD.md).

## Requirements

JDK 17, Android SDK 35, NDK 27 and Go 1.27 or newer. Go and the NDK build the bundled
Cloudflare tunnel engine.

## Build

```bash
cd mossdial
./gradlew :app:assembleRelease
```

The full check used before a release:

```bash
cd mossdial
./gradlew :app:testReleaseUnitTest :app:assembleRelease \
  :app:lintRelease :app:lintVitalRelease
# If Gradle skips vital lint in the combined invocation:
./gradlew :app:lintVitalRelease
```

The latest release build and lint passed with 186 JVM tests. Emulator checks covered the rental
preview and local HTTP hosting; two HTTPS instrumentation tests failed on an earlier debug build.
Real-device testing remains outstanding. See the [app status](./mossdial/README.md#status) and
[release checklist](./RELEASING.md) for details.

## Release signing

No signing material is in this repository. Set the `MOSSDIAL_KEYSTORE_*` environment variables
described in [RELEASING.md](./RELEASING.md), or place a keystore at the git-ignored path
`mossdial/keystore/release.keystore`. Without either, a release build still assembles, unsigned.

## Contributing

Mossdial is open source under Apache 2.0, but it does not accept external pull requests. Only the
maintainer commits and pushes to `master`. The code is public for use, review and forking under the
license; forks are independent and are not merged upstream.

Read [CONTRIBUTING.md](./CONTRIBUTING.md) for the build and repository rules. Report suspected
vulnerabilities according to [SECURITY.md](./SECURITY.md), and see [RELEASING.md](./RELEASING.md) for
release steps.

## Design credit

The interface was polished using [Unslop](https://github.com/heydamir/unslop) by
[Damir Sokolovsky (@heydamir)](https://x.com/heydamir), adapted for Android and Jetpack Compose.
Unslop is licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

## License

Apache License 2.0. See [LICENSE](./LICENSE) and [NOTICE](./NOTICE). Third-party licence details are
tracked in [`mossdial/LICENSES/THIRD_PARTY.md`](./mossdial/LICENSES/THIRD_PARTY.md), and dependency
checksums are pinned in
[`mossdial/gradle/verification-metadata.xml`](./mossdial/gradle/verification-metadata.xml).
