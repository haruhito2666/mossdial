# Contributing to Mossdial

Thank you for helping improve Mossdial.

## Development

1. Install JDK 17, Android SDK 35, NDK 27 (`27.0.12077973`) and CMake 3.22.1. The NDK and CMake are
   not optional: the llama.cpp runtime is compiled from the vendored sources, and the AI tab does not
   work without it. Targets are `arm64-v8a` and `x86_64`.
2. Run `./gradlew :app:assembleDebug` and `./gradlew :app:testDebugUnitTest` from `mossdial/`.
3. Before opening a pull request, run the full check:

   ```bash
   ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease \
     :app:assembleAndroidTest :app:lintDebug :app:lintVitalRelease
   ```

   `:app:assembleRelease` is not optional here: the release build minifies, and the two third-party
   runtimes are kept alive by the app's own `app/proguard-rules.pro` rather than by rules in their
   published artifacts. A stripped class only fails on a device, not in CI.

There is no instrumentation test source set yet. A JVM test that can run without a device is always
preferred; add to `app/src/test/java/com/mossdial/` and name the class after what it covers.

## Pull requests

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
commit the result in the same change:

```bash
cd mossdial
./gradlew --write-verification-metadata sha256 :app:assembleDebug :app:assembleRelease \
  :app:assembleAndroidTest :app:testDebugUnitTest :app:lintDebug
```

Never "fix" a verification failure by deleting the entry. A mismatch is either a corrupted download
or a substituted artifact, and both are worth stopping for.

## Runtime dependencies

The project uses the official Android/Kotlin/Compose toolchain plus two third-party runtimes:
llama.cpp, vendored from a pinned commit, and the `com.microsoft.onnxruntime:onnxruntime-android`
AAR. Their versions, licences and provenance are tracked in `mossdial/LICENSES/THIRD_PARTY.md`. When
either is updated, update that file, the pinned commit note, the licence notices and the shrinker
rules in the same change, and apply upstream security fixes before release. No other third-party
runtime may be added: this is a deliberate limit, not an oversight.

## Internal documents

`MOSSDIAL_PLAN.md` and `MOSSDIAL_ROADMAP.md` are internal working notes and are git-ignored. Do not
rely on them for anything a contributor needs; the README is the document of record.

## License

Contributions are licensed under Apache License 2.0 unless a file states otherwise.
