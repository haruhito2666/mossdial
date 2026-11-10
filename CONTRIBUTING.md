# Contributing to Mossdial

Mossdial is published as an open-source project, but it does **not** accept external pull requests.
Only the maintainer commits and pushes to `master`. This document describes how the project is built
and the rules the repository follows; it is not an invitation to send changes upstream. The Apache
2.0 license permits forking, but forks are independent and are not reviewed or merged here.

## Development

1. Install JDK 17, Android SDK 35, NDK 27 (`27.0.12077973`) and Go 1.27 or newer.
   Go and the NDK compile the bundled Cloudflare tunnel engine with cgo, which supplies Android's
   DNS resolver. Targets are `arm64-v8a` and `x86_64`.
2. Run `./gradlew :app:assembleRelease` and `./gradlew :app:testReleaseUnitTest` from `mossdial/`.
3. Before committing, run the full check:

   ```bash
   ./gradlew :app:testReleaseUnitTest :app:assembleRelease \
     :app:lintRelease :app:lintVitalRelease
   ```

   If Gradle skips vital lint in the combined invocation, run `./gradlew :app:lintVitalRelease`
   separately. The release variant is minified; a successful build does not replace testing the
   installed artifact on a device.

Device tests live under `app/src/androidTest/` but are not part of the release-only check. A JVM test
that can run without a device is always preferred; add to `app/src/test/java/com/mossdial/` and name
the class after what it covers.

## Rental preview and planned backend

`app/src/main/java/com/mossdial/ui/RentalsScreen.kt` is an interactive preview, not a marketplace
client. Example listings and workspace details are illustrative; offer fields and switches are
screen state only. Keep rent, list, deployment and rental-management actions disabled until the
corresponding systems exist and have been verified. Preserve local hosting without a marketplace
account. `mossdial/PLAN-PHONE-RENTAL-CLOUD.md` records the proposed phases and the real-device
isolation and attestation gate for private rentals.

## Repository rules

External pull requests are not accepted. Do not open one, do not ask for direct write access, and do
not push branches to this repository. The maintainer is the only person who commits and pushes to
`master`.

- Keep changes focused and include tests for behavioural changes.
- Do not commit models, vocabularies, APKs, keystores, generated build output, or local SDK
  configuration. The ignore rules already cover these; if something slipped through, that is a bug
  in `.gitignore` worth fixing in the same change.
- Preserve third-party licence notices when adding or updating vendored code, including the licence
  files inside a vendored tree.
- Update the documentation your change makes wrong. The project README, the privacy policy and the
  third-party inventory are the ones that go stale: a new stored datum belongs in the privacy policy,
  a new dependency belongs in `LICENSES/THIRD_PARTY.md`, and a new user-visible capability belongs in
  the README's feature list and status table.
- Describe security-sensitive changes clearly; do not include exploit details in public issue
  reports.
- Sign release artifacts only through the documented environment variables; never commit signing
  material.

## Dependencies

Dependency verification is on: `mossdial/gradle/verification-metadata.xml` pins a checksum for every
resolved artifact and Gradle fails on anything else. After changing a dependency, regenerate it and
include the result in the same change:

```bash
cd mossdial
./gradlew --write-verification-metadata sha256 :app:assembleRelease \
  :app:testReleaseUnitTest :app:lintRelease
```

Never "fix" a verification failure by deleting the entry. A mismatch is either a corrupted download
or a substituted artifact, and both are worth stopping for.

## Runtime dependencies

The project uses the Android/Kotlin/Compose toolchain and the pinned Cloudflare `cloudflared`
engine, compiled from source at build time. Track versions, licences and provenance in
`mossdial/LICENSES/THIRD_PARTY.md` and update them with dependency changes.

## Internal documents

`MOSSDIAL_PLAN.md`, `MOSSDIAL_ROADMAP.md` and `MOSSDIAL_FEATURES.md` are internal, git-ignored
working notes. The plan is historical; the roadmap records the current priorities, and the feature
notes explain the candidates. The phone-rental plan in `mossdial/PLAN-PHONE-RENTAL-CLOUD.md` records
the proposed marketplace work. The app README is the document of record for current behaviour.

## License

Mossdial is licensed under Apache License 2.0 unless a file states otherwise. Forking is permitted by
that license, but no external contribution is accepted upstream.
