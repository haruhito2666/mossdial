# Privacy Policy

Mossdial is a local-first Android app. It does not require an account, has no built-in site analytics
or project telemetry, and does not send application data to a server operated by this project.
Hosting can send content to clients you allow; optional GitHub imports and Cloudflare tunnels contact
the services you choose, as described below.

## Data stored on the device

Persisted app data is app-private. The app sets `allowBackup="false"` and declares explicit
data-extraction rules, so app data is not included in a cloud backup or device-to-device transfer.
Android may also retain temporary screen state as described below.

- **Server settings** in app-private preferences: port, LAN access, HTTPS, start-after-reboot, the
  request-log preference and its size.
- **Hosted website files** in the app-private web root. This is the one part you create yourself, by
  importing files or writing them in the app.
- **Request logs, only if you turn them on.** They are held in memory, are never written to disk, and
  hold the method, path, status and size of the most recent requests up to the limit you chose. With
  the setting off — the default — no request path is recorded anywhere, logcat included. The
  aggregate counters shown as Traffic (request count, bytes sent, status totals) record no path and
  are kept in memory for the current run.
- **Credentials, encrypted.** The Cloudflare tunnel token is sealed with AES-GCM using a key held
  in the Android keystore.
- **The HTTPS identity.** A private key created inside the platform keystore, which the app cannot
  read out of, and the public certificate signed by it, stored app-private so the same certificate is
  presented on every run.
- **Onboarding state**, so the first-run walkthrough is not shown again.
- **Rental preview inputs** include example CPU, memory, storage and price values and charging/Wi-Fi
  switches. They are temporary interface state; Android may retain them for screen-state restoration.
  They do not change device settings, are not submitted or stored as a listing, and the preview makes
  no marketplace network request.

## Network behavior

The server binds to the device loopback interface by default. LAN access is disabled by default and
must be explicitly enabled by the user. When you enable LAN hosting or a tunnel, served website
files are sent to clients that request them.

Cloudflare Tunnel is an optional remote-tunnel integration and is not required for local hosting. It
runs only when you enable it. A named tunnel needs a tunnel token; a Quick test URL mode uses a
temporary `trycloudflare.com` link with no token. The app includes a pinned `cloudflared` engine
inside the APK; advanced users may instead point it at their own executable by absolute path. The
token is stored encrypted on the device and is never written to a log.

A named tunnel is token-based: the app passes the token to `cloudflared tunnel run` and nothing else,
so the public hostname and the local ports it reaches are configured in your own Cloudflare dashboard,
not in the app. The app cannot enforce which of this device's ports that configuration exposes. Map
only the web server's port in the tunnel's ingress configuration. A Quick test URL publishes the
site port and stops working when the tunnel stops.

When you import a public GitHub repository, the app requests its archive from
`codeload.github.com` over HTTPS. That service receives the network request needed to provide the
files. No project telemetry endpoint or analytics service is contacted by the app.

## Proposed phone rentals

The Rentals tab is a preview. It does not create an account, list or rent a phone, process payments,
connect to a customer or deploy a workload. The proposed marketplace would introduce new data and
services; its privacy implications will be documented if and when they are implemented.

## Data retained from earlier versions

Updating does not delete model files or vocabularies from earlier versions. They may remain in the
app-private `noBackupFilesDir/models` directory, along with the old API settings and encrypted token
and its Keystore key. The current version does not use them. Clearing app data or uninstalling removes
app-private data, including hosted websites and settings; save anything you need first.

## Children

The app is not directed at children. The project does not knowingly gather personal information on
its own servers; the local data and optional network features are described above.

## Changes

Any change to what is stored or transmitted will be reflected in this file in the same change as the
behaviour.

## Contact

Open an issue in the public repository for privacy questions.
