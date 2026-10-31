# Releasing Mossdial

## Before a release

1. Work from a clean checkout with no local modifications. Dependency verification is enforced by
   `mossdial/gradle/verification-metadata.xml`; after changing a dependency, regenerate it with
   `./gradlew --write-verification-metadata sha256` and commit the result in the same change.
2. Confirm the build machine has JDK 17, Android SDK 35, NDK 27 (`27.0.12077973`) and CMake 3.22.1.
   The NDK and CMake are required because the llama.cpp runtime is compiled from the vendored
   sources.
3. Run the full check in `mossdial/`:

   ```bash
   ./gradlew :app:testReleaseUnitTest :app:assembleRelease \
     :app:lintRelease :app:lintVitalRelease
   ```

4. Check the file set that would be published. `MOSSDIAL_PLAN.md` and `MOSSDIAL_ROADMAP.md` are
   internal and ignored; confirm no model, vocabulary, keystore, `local.properties`, build output, log
   or captured traffic is in the way:

   ```bash
   git add -An --dry-run .
   ```

   Expect source, the pruned licence-complete llama.cpp tree, resources, and documentation. Anything
   under `app/build/`, any `.gguf`, `.onnx`, or `vocab.txt`, and any `keystore/` is a mistake.
5. Test on a real device, not an emulator only:
   - HTTP over loopback, and LAN mode with a second device.
   - HTTPS, then **restart the app and connect again**: the certificate must be the same one. That is
     the only way to know the keystore identity is reused rather than regenerated.
   - Static hosting, path traversal attempts, a file import, create, rename and delete.
   - The notification, the widget's start and stop, and start-after-reboot.
   - A real GGUF model through the AI tab and through `POST /v1/chat/completions`.
   - A real ONNX encoder with its `vocab.txt`, through the AI tab and through `POST /v1/embeddings`.
   - A trusted `cloudflared` binary and token, if the tunnel is advertised in the listing.
   - Low-memory behaviour with a large model loaded.
6. Install the **minified release** build and repeat the model paths on it. Neither third-party runtime
   ships shrinker rules in its published artifact, so `app/proguard-rules.pro` is what keeps the JNI
   entry points alive; a stripped class fails at runtime and nowhere else.
7. Bump `versionCode` and `versionName` in `mossdial/app/build.gradle.kts`, and update the status
   table in `mossdial/README.md` so it describes the release rather than the development state.

## Tunnel test

The app supports both a token-based named Cloudflare Tunnel and a tokenless quick
`trycloudflare.com` tunnel. The pinned `cloudflared` engine is compiled from source and packaged as
`libcloudflared.so`, so there is no binary to push or path to type for the normal test.

1. Build the APK. `buildCloudflared` downloads the pinned Cloudflare source, verifies its SHA-256,
   compiles it for both Android ABIs with Go and the NDK (cgo is required for Android DNS), and
   packages it; the manifest must have `extractNativeLibs="true"`.
2. In Cloudflare Zero Trust, create a named tunnel and copy its connector token. Add a public
   hostname whose service is `http://127.0.0.1:8080` (the site port), not the AI API port.
3. Start the Mossdial server and confirm `http://127.0.0.1:8080` answers locally.
4. In the Tunnel tab, leave **Use built-in cloudflared** on, choose `http2`, paste either the token
   or the full command Cloudflare shows, enable the tunnel, and press Start. Also test **Quick test
   URL** mode with no token; the generated `https://...trycloudflare.com` URL must appear in Status
   and its Copy button must work.
5. From cellular data with Wi-Fi off, open `https://<public-hostname>`. Confirm the page and its
   assets load, then open a deep link.
6. Stop the tunnel and confirm the public URL becomes unreachable (Cloudflare 1033/502 depending on
   edge state). Start it again and confirm the page returns.
7. If it fails: check the Tunnel tab's detail and the **Copy diagnostic** output, the Cloudflare
   connector status, the origin service scheme/port, and `adb logcat -s MossdialTunnel`. The token
   must never appear in any of them.

For the advanced custom-binary path, turn off the built-in switch and use an absolute path the app
can execute. Do not use `/sdcard/...`; it is mounted noexec on most devices. A connected tunnel with
a 502 means the connector is up but cannot reach the local server. If the app's HTTPS listener is
enabled, the Cloudflare origin must use `https://` and its no-TLS-verify option, or the origin should
stay on HTTP for this test.

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

If none of the four are set, and no keystore exists at `mossdial/keystore/release.keystore`, the
release build still assembles but produces an unsigned artifact. That is deliberate: a release must
be signable in CI without a key ever existing in the repository.

Never commit the keystore, passwords, generated AAB, or generated APK. Keep signing keys in a
password manager or a CI secret store, back them up securely, and record where the backup is.

## Store listing

- Title, short description, full description, and the feature graphic.
- Screenshots for a phone and, if a tablet layout ships, for a tablet.
- The launcher icon at 512×512 PNG, exported from the adaptive icon in `app/src/main/res/`.
- Content rating questionnaire, and the Data Safety form: no data collected, no data shared, nothing
  leaving the device unless the user starts a tunnel.
- `mossdial/PRIVACY_POLICY.md` published at a public URL, because the listing links to it.
- The foreground service declaration. The app uses
  `FOREGROUND_SERVICE_SPECIAL_USE` with the subtype `local_web_hosting`, and the Play Console asks for
  a justification of that subtype. This is the item most likely to need a rewrite of the service type
  rather than a better explanation, so decide it early.

## Publication

Tag the release with a semantic version, publish the GitHub release notes, and attach the signed
bundle produced by CI. Verify the published repository contains the licence, the notice, the
third-party licence inventory, the privacy policy, the dependency verification metadata, and the CI
workflow.
