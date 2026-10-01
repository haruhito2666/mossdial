# Security Policy

## Reporting a vulnerability

Please report suspected vulnerabilities privately to the maintainer through the repository's private
security advisory channel. Do not open a public issue containing exploit details, credentials, private
keys, or personal data.

Include the affected version, device and OS details, reproduction steps, impact, and any suggested
mitigation. You should receive an acknowledgement and a remediation status update as soon as practical.

## Supported versions

| Version | Supported |
| --- | --- |
| 0.1.x | yes |
| anything older | no, it was never released |

Security fixes land on the current line and are published in the next release. There is no
long-term-support branch.

## Scope

Mossdial hosts a local web server, a local AI API, and an optional tunnel integration. In scope:

- Unauthenticated LAN exposure, or anything reachable from the network that the user did not intend
  to be reachable.
- Certificate or key disclosure, including the app's own TLS identity, the tunnel token, and the AI
  API bearer token.
- Path traversal, symlink escape, or any file reachable outside the intended roots from either the
  web server or the model directory.
- Anything that lets a request escape its bounds: unbounded headers, bodies, queues, model
  downloads, tokenizer input, or vocabulary files.
- Credential handling in the local AI API, including the constant-time comparison, the request
  bounds, and the separation between the API token and the tunnel token.
- The tunnel is token-based, so the ports a tunnel exposes are configured in Cloudflare rather than in
  this app. A report that the app should prevent a user from publishing the AI API port through their
  own tunnel configuration is not a vulnerability, but a way of surfacing that guidance more loudly is
  welcome.
- Supply-chain issues in the two third-party runtimes, or in the vendored llama.cpp sources.
- Anything that reaches the native code through JNI in a release build, since the shrinker keeps for
  both runtimes live in this repository.

Out of scope: vulnerabilities in llama.cpp or ONNX Runtime that require the user to supply a
maliciously crafted model and that do not cross a Mossdial boundary, and issues that need a
rooted device or a modified system image.

## Safe disclosure

Please do not access data belonging to other users, deploy public test infrastructure, or publish a
proof of concept before a fix is available. Give maintainers a reasonable opportunity to release a
fix before disclosure.
