# Third-Party Licenses

The app ships the official Android Gradle, Kotlin, Compose, and AndroidX toolchain through Gradle, plus three third-party runtimes and the optional tunnel engine. Their license terms apply to those dependencies.

| Component | Version | License | Where it comes from |
| --- | --- | --- | --- |
| llama.cpp | pinned commit `6b790a9c291b5d7af3312bbf9f0c558aa023b13e` (reports itself as 0.5.0-dev, ggml 0.25.1) | MIT | sources vendored under `app/src/main/cpp/third_party/llama.cpp`, see `PINNED_COMMIT.md` there |
| ONNX Runtime for Android | 1.23.2 | MIT | Gradle dependency `com.microsoft.onnxruntime:onnxruntime-android` |
| Cloudflare cloudflared | 2026.10.6 | Apache-2.0 | pinned source tarball, compiled at build time for Android and packaged as `libcloudflared.so`; not stored in the repository |

llama.cpp is compiled from source into `libmossdial_ai.so`; only the runtime library and the ggml CPU backend are vendored, upstream examples, tests, tools, docs and the other ggml backends are pruned.

`cloudflared` is a Go executable built for Android with cgo, used only when the user enables the
optional tunnel. Cgo is what lets its DNS lookups use Android's resolver; a pure-Go build would look
for `/etc/resolv.conf`, which Android does not provide. The source checksum is pinned in
`app/build.gradle.kts`; a substituted download fails the build. It is packaged as a JNI library
because Android only reliably executes native code from the app's extracted native-library directory.
The binary uses 16KB ELF segment alignment so it loads on devices with 16KB pages. The app also
allows a user-supplied executable as an advanced alternative.

Neither AI runtime ships consumer shrinker rules in its published artifact, so `app/proguard-rules.pro` keeps `ai.onnxruntime.**` and the JNI object `com.mossdial.ai.LlamaBridge` explicitly. Both reach native code by name, so R8 stripping or renaming either one would produce a release-only failure that no build step reports.

Dependency checksums are pinned in `gradle/verification-metadata.xml`, generated with `--write-verification-metadata sha256`, and Gradle refuses to resolve anything whose checksum is not in that file.

Nothing else is bundled or linked: no CivetWeb, mbedTLS, Rust, ZXing, kotlinx.serialization, or any
other third-party runtime. The app does not use kotlinx.coroutines either — every background path in
it runs on `java.util.concurrent` executors and Compose snapshot state — and the HTTP server, the
HTTPS certificate, the WordPiece tokenizer, the JSON reader and writer, and the tunnel process
management are all written in this project rather than pulled in.

Two limits are worth stating plainly. `kotlinx-coroutines-android` appears in the dependency graph
only because AndroidX pulls it in transitively (Compose and lifecycle use it internally); the app
never depends on it, and no coroutine is used in its own code. And `androidx.compose.material:material-icons-extended`
is an AndroidX artifact, not a third-party runtime, but it does add weight that this app only needs
for a handful of icons.

The Gradle plugin, the Kotlin plugin and the AndroidX artifacts come from Google, JetBrains and
Maven Central under their own licences, as recorded in `gradle/verification-metadata.xml`.
