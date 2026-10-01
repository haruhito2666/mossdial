# Privacy Policy

Mossdial is a local-first Android app. It does not require an account, it has no analytics, and it
does not send application data to any server operated by this project. Nothing leaves the device
unless you start a Cloudflare Tunnel yourself.

## Data stored on the device

All of it is app-private. The app sets `allowBackup="false"` and declares explicit data-extraction
rules, so none of it is included in a cloud backup or a device-to-device transfer.

- **Server settings** in app-private preferences: port, LAN access, HTTPS, start-after-reboot, the
  request-log preference and its size, and the AI API port and LAN access.
- **Hosted website files** in the app-private web root. This is the one part you create yourself, by
  importing files or writing them in the app.
- **Request logs, only if you turn them on.** They are held in memory, are never written to disk, and
  hold the method, path, status and size of the most recent requests up to the limit you chose. With
  the setting off — the default — no request path is recorded anywhere, logcat included. The
  aggregate counters shown as Traffic (request count, bytes sent, status totals) record no path and
  are always kept.
- **Model weights and vocabularies** in the app-private `noBackupFilesDir/models` directory. See the
  AI section below.
- **Credentials, encrypted.** The Cloudflare tunnel token and the local AI API bearer token are sealed
  with AES-GCM using keys held in the Android keystore, in separate slots under separate key aliases.
- **The HTTPS identity.** A private key created inside the platform keystore, which the app cannot
  read out of, and the public certificate signed by it, stored app-private so the same certificate is
  presented on every run.
- **Onboarding state**, so the first-run walkthrough is not shown again.

## Network behavior

The server binds to the device loopback interface by default. LAN access is disabled by default and
must be explicitly enabled by the user, in the same way for the local AI API on its own port.

Cloudflare Tunnel is an optional remote-tunnel integration and is not required for local hosting. It
runs only when you supply a `cloudflared` executable by absolute path and a tunnel token. The token is
stored encrypted on the device and is never written to a log.

A tunnel here is a token-based named tunnel: the app passes the token to `cloudflared tunnel run` and
nothing else, so the public hostname and the local ports it reaches are configured in your own
Cloudflare dashboard, not in the app. The app cannot enforce which of this device's ports that
configuration exposes. Map only the web server's port in the tunnel's ingress configuration: if the
AI API's port is mapped there too, the bearer token is the only thing protecting an endpoint that can
make this device generate text and embed text.

Model downloads happen only when you ask for one, only over `https://`, only from the URL you supply,
and only into the app-private model directory. No model catalog, telemetry endpoint, or analytics
service is contacted at any point, including on first run.

## AI models

Model files, and the `vocab.txt` a text encoder needs, are imported from storage you choose or
downloaded from a URL you supply, and are stored in the app-private `noBackupFilesDir/models`
directory. That directory is a sibling of the web root, never inside it, so no model file can be
served by the web host even if the web root is published.

Inference runs entirely on the device. Prompts, generated text, embeddings, and request logs never
leave the device: the model is loaded into this process and there is no remote inference path in the
app. With the request log off, nothing about which paths were requested is retained at all.

The local AI API is reachable from the network only when LAN access is explicitly enabled, and every
route then requires the bearer token generated and stored on the device. It answers with the models
the AI tab has loaded: a GGUF model for chat, the selected ONNX encoder for embeddings, and nothing
else. It proxies nothing and calls out to nothing.

## Children

The app collects nothing, so it is not directed at children and does not knowingly gather personal
information from anyone.

## Changes

Any change to what is stored or transmitted will be reflected in this file in the same change as the
behaviour.

## Contact

Open an issue in the public repository for privacy questions.
