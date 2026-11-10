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

Mossdial hosts a local web server and an optional tunnel integration. The Rentals tab is a UI-only
preview and does not provide customer isolation, attestation, accounts, leases or payments. In scope:

- Unintended network exposure and access outside the configured web root.
- Disclosure of the TLS identity or tunnel token.
- Path traversal, symlink escapes and requests that exceed configured bounds.
- Tunnel process handling, credential storage and dependency supply-chain issues.

Named tunnel ingress is configured in Cloudflare, so review its hostname and origin settings. For
current local hosting, issues requiring a rooted device or modified system image are outside the
current scope. The proposed private-rental threat model separately considers a device owner with
physical possession and potential control of Android; it remains unverified and cannot be inferred
from the preview. See `mossdial/PLAN-PHONE-RENTAL-CLOUD.md`.

## Safe disclosure

Please do not access data belonging to other users, deploy public test infrastructure, or publish a
proof of concept before a fix is available. Give maintainers a reasonable opportunity to release a
fix before disclosure.
