# Security Policy

## Reporting a vulnerability

Please report suspected vulnerabilities privately to the maintainer through the repository's private security advisory channel. Do not open a public issue containing exploit details, credentials, private keys, or personal data.

Include the affected version, device/OS details, reproduction steps, impact, and any suggested mitigation. You should receive an acknowledgement and a remediation status update as soon as practical.

## Scope

Mossdial hosts a local web server, a local AI API, and tunnel integrations. Reports about unauthenticated LAN exposure, certificate/key disclosure, path traversal, SSRF, model-file escape, tokenizer or vocabulary handling that can be driven past its bounds, or inference/API sandboxing are in scope.

## Safe disclosure

Please do not access data belonging to other users, deploy public test infrastructure, or publish a proof of concept before a fix is available. Give maintainers a reasonable opportunity to release a fix before disclosure.
