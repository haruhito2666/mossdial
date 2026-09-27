# Contributing to Mossdial

Thank you for helping improve Mossdial.

## Development

1. Install JDK 17 and Android SDK 35 with the official Android build tools.
2. Run `./gradlew :app:assembleDebug` and `./gradlew :app:testDebugUnitTest` from `mossdial/`.
3. Run `./gradlew :app:lintDebug` before opening a pull request.

## Pull requests

- Keep changes focused and include tests for behavioral changes.
- Do not commit models, APKs, keystores, generated build output, or local SDK configuration.
- Preserve third-party license notices when adding or updating vendored code.
- Describe security-sensitive changes clearly; do not include exploit details in public issue reports.
- Sign release artifacts only through the documented environment variables; never commit signing material.

## Runtime dependencies

The project uses the official Android/Kotlin/Compose toolchain plus two third-party runtimes: llama.cpp, vendored from a pinned commit, and the `com.microsoft.onnxruntime:onnxruntime-android` AAR. Their versions, licenses, and provenance are tracked in `mossdial/LICENSES/THIRD_PARTY.md`. When either is updated, update that file, the pinned commit note, and the license notices in the same change, and apply upstream security fixes before release. No other third-party runtime may be added.

## License

Contributions are licensed under Apache License 2.0 unless a file states otherwise.
