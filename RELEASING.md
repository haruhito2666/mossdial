# Releasing Mossdial

## Before a release

1. Work from a clean checkout. Dependency verification is enforced by `gradle/verification-metadata.xml`; after changing a dependency, regenerate it with `./gradlew --write-verification-metadata sha256` and commit the result in the same change.
2. Confirm the build machine has JDK 17, Android SDK 35, NDK 27, and CMake 3.22.1, because the llama.cpp runtime is compiled from the pinned vendored sources.
3. Run `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleAndroidTest :app:lintDebug :app:lintVitalRelease` in `mossdial`.
4. Test a real device with HTTP loopback, LAN mode, HTTPS with the self-signed certificate, static file hosting, path traversal protection, a GGUF model through the AI tab and the local AI API, an ONNX text encoder with its `vocab.txt` through `/v1/embeddings`, and low-memory behavior. Restart the app between two HTTPS sessions and confirm the certificate is the same one, which is the only way to know the keystore identity is being reused rather than regenerated.
5. Run the minified release build on a device, not only the debug one: neither third-party runtime
   ships shrinker rules in its published artifact, so the app's own `proguard-rules.pro` is what
   keeps the JNI entry points alive, and a stripped class only fails at runtime.
6. Confirm no model files, keystores, local properties, build outputs, logs, or captured traffic are staged.

## Signed bundle

Set these environment variables locally or in the CI secret store:

- `MOSSDIAL_KEYSTORE_FILE`
- `MOSSDIAL_KEYSTORE_PASSWORD`
- `MOSSDIAL_KEY_ALIAS`
- `MOSSDIAL_KEY_PASSWORD`

Then run:

```bash
cd mossdial
./gradlew :app:bundleRelease
```

Never commit the keystore, passwords, generated AAB, or generated APK. Keep signing keys in a password manager or CI secret store and back them up securely.

## Publication

Tag the release with a semantic version, publish the GitHub release notes, and attach the signed bundle produced by CI. Verify the public repository contains the license, notice, third-party license inventory, privacy policy, and dependency verification metadata.
