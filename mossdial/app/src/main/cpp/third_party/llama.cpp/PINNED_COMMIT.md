# Vendored llama.cpp runtime

Upstream project: `ggml-org/llama.cpp`
Pinned commit: `6b790a9c291b5d7af3312bbf9f0c558aa023b13e`
Upstream license: MIT (see `LICENSE` in this directory)

Only the parts required to build the inference library on Android are vendored.
No upstream file was modified; whole directories were either copied or omitted.

## Copied

| Path            | Purpose                                             |
| --------------- | --------------------------------------------------- |
| `CMakeLists.txt`| upstream build entry point (added as a subdirectory) |
| `include/`      | public `llama.h`, `llama-cpp.h`                     |
| `src/`          | llama core, arch loaders (`src/models/*.cpp`)        |
| `cmake/`        | upstream CMake modules used by the build             |
| `ggml/`         | ggml core + the CPU backend                          |
| `vendor/`       | upstream vendored headers needed by the library      |
| `licenses/`     | license text for vendored third-party headers        |
| `LICENSE`, `AUTHORS` | upstream license and attribution                 |

## Pruned

Upstream trees that are not part of the Android inference library:

- `examples/`, `tools/`, `tests/`, `pocs/`, `benches/`, `app/`
- `common/`, `conversion/`, `gguf-py/`, `grammars/`, `models/`, `media/`
- `docs/`, `ci/`, `scripts/`, `requirements/`, `skills/`, `.devops/`, `.github/`
- `ggml/src/ggml-*` accelerator backends other than `ggml-cpu`
- `vendor/cpp-httplib` (only referenced when the `llama-common` library is built)

## Build options

`app/src/main/cpp/CMakeLists.txt` adds this directory with
`LLAMA_BUILD_COMMON`, `LLAMA_BUILD_TESTS`, `LLAMA_BUILD_TOOLS`,
`LLAMA_BUILD_EXAMPLES`, `LLAMA_BUILD_SERVER` and `LLAMA_BUILD_APP` all off, and
`GGML_OPENMP`, `GGML_LLAMAFILE`, `GGML_LTO` and `GGML_NATIVE` off. Those options
must stay off: the pruned directories are only reachable when the matching option
is enabled.

## Updating

Replace this directory with a fresh export of the pinned commit, then re-apply
the pruning list above. Record the new commit here.
