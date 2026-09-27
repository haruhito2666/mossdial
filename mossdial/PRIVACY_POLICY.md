# Privacy Policy

Mossdial is a local-first Android app. It does not require an account and does not send application data to a server operated by the project.

## Data stored on the device

- Server settings, including port and LAN mode, in app-private preferences.
- Hosted website files in the app-private web root.
- Request logs only when the user turns them on in settings. They are kept in memory, never written
  to disk, and hold the method, path, status and size of the most recent requests. With the setting
  off, no request path is recorded anywhere, including logcat. The aggregate counters shown as
  Traffic — request count, bytes sent, and status totals — record no path and are always kept.

## Network behavior

The server binds to the device loopback interface by default. LAN access is disabled by default and must be explicitly enabled by the user. Cloudflare Tunnel is an optional remote-tunnel integration that is not required for local hosting: it is run only when the user supplies a `cloudflared` executable and a tunnel token, and the token is stored encrypted on the device.

The HTTPS certificate is generated on the device. Its private key is created inside the platform keystore and cannot be read out of the app, and the public certificate is stored in the app-private no-backup directory so the same certificate is presented on every run. Both are excluded from cloud backup and device transfer, and neither is ever sent anywhere.

## AI models

Model files, and the `vocab.txt` a text encoder needs, are imported from user-chosen local storage or downloaded by the user from the URL they supply, and are stored in the app-private `noBackupFilesDir/models` directory, outside the served web root and excluded from backups. A download is made only over HTTPS, only when the user asks for it, and no model catalog or telemetry is contacted. Inference runs entirely on the device: prompts, generated text, and request logs never leave the device unless the user has separately configured an optional Cloudflare Tunnel, which publishes the web server and not the AI API. The local AI API is reachable from the network only when LAN access is explicitly enabled, and every route then requires the bearer token generated and stored on the device. It answers with the models the AI tab has loaded: a GGUF model for chat, the selected ONNX encoder for embeddings, and nothing else.

## Contact

Open an issue in the public repository for privacy questions.
