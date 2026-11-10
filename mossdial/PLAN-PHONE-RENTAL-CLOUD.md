# Plan: Mossdial Private Phone Server Marketplace

Status: proposed, 2026-11-10. This document specifies work to investigate and
implement; the marketplace and private sandbox do not yet exist. The app includes a UI-only rental
preview with an illustrative listing and owner controls; it does not create rentals or offers. This
plan is based on a targeted review of the working tree, not a security audit.

## Current progress — 2026-11-10

**Implemented:** local website hosting and a UI-only Rentals preview. The app has five navigation
destinations; saved selections from the earlier navigation layouts are migrated to a stable
destination name. The preview is implemented in `app/src/main/java/com/mossdial/ui/RentalsScreen.kt`.

- The renter can explore an explicitly illustrative phone listing and rental workspace. The sample
  has no real device, price, verified resources or security profile.
- The owner can try CPU, memory, storage and price fields, and charging/Wi-Fi switches. These are
  temporary screen state; they do not configure the device, create a listing or submit an offer.
- Rent, list, deploy and manage actions are disabled. There is no rental backend, device enrollment,
  attestation, protected workload environment, listing inventory, account, lease, payment, payout,
  customer connection or real rental data.
- The latest release build, release lint and vital lint passed with 186 JVM tests. Debug emulator
  smoke checks covered the five tabs, renter/owner previews, inputs and switches, disabled rent/list
  actions, large text, and web server start/stop and local HTTP. A previous debug instrumentation
  run had 31 passes, two HTTPS-handshake failures and two hardware-only skips out of 35 tests; it was
  not rerun for the UI change. See [README status](README.md#status) for the limitation.
- Real-device hosting, HTTPS, privacy and attestation verification remain outstanding. The preview
  is not evidence of isolation, availability, a payment flow or a functioning rental.

**Next planned work:** refine the product contract and threat model, establish a real-device
baseline, then investigate the protected-execution and attestation gate in M0–M1. Implement backend
and operational functions incrementally only as their dependencies and acceptance criteria are
satisfied. Do not enable private rental or accept real customer secrets before the gate passes.

## 1. Product goal

Let a person offer one Android phone as a rented cloud server and get paid
when a customer rents and uses it. The customer chooses an eligible phone,
rents that individual device, connects securely and deploys supported
applications inside an isolated server environment. Mossdial charges the
customer, pays the phone owner, and retains a disclosed fee.

The initial marketplace focuses on supported websites and APIs. Define each
supported application type and its actual limitations.

**One rental is one phone.** Phones are not connected into a cluster, and
their CPUs, RAM and storage are not combined. A customer may rent multiple
independent phones. As an initial product rule, lease each phone exclusively
to one customer at a time, even if the customer does not use all offered
resources. Each lease has its own isolation, keys, storage, status and billing.

The requested privacy goal is that the phone owner cannot access customer
application code, secrets, private files or data. Protect the phone owner from
the customer's workload as well. **Do not sell or label a private server
unless the required isolation and verification are demonstrated on the exact
supported device and software configuration.** No security design can
honestly promise that a breach is impossible.

This is primarily a rented server, similar in concept to an individual cloud
instance. Per-request serverless execution could be a later feature on an
individual rented phone; it is not required for the first server product and
must not introduce phone-to-phone pooling.

## 2. Baseline and constraints

- The [Mossdial README](README.md) describes a local-first Android website
  host, an optional tunnel and a foreground service. Real-device verification
  remains outstanding. Re-check the current working tree and release artifact
  before making claims.
- Current hosting and the existing proxy do not establish a multi-party
  marketplace, a protected customer execution environment, reliable lease
  enforcement, customer-to-phone attestation, billing or payouts. A proxy is
  transport-only; the README says it does not manage the backend process.
- Existing self-hosting must remain useful without a marketplace account.
  Phone-rental participation, any account, remote control, data collection,
  and public exposure are separate, explicit opt-ins.
- Customer workloads execute on individual Android phones. A coordination
  service, discovery service or encrypted relay may require separate
  infrastructure; it handles control and transport, not pooled customer
  execution. Document where that infrastructure runs.
- Verify Android API availability, app distribution rules, foreground
  service restrictions, OEM behavior, security patch status, licenses and
  hardware support on real devices. Do not assume a particular Android
  virtualization feature is available to a normal third-party application.

## 3. Roles and trust boundaries

| Role | What the role can do | What it must not gain by default |
|---|---|---|
| Phone owner / provider | Enroll a phone, choose available resources and pricing, view non-sensitive health and earnings, pause or stop participation. | Read customer code, files, secrets, private traffic or private application logs. |
| Customer / renter | Select a listing, accept its terms, deploy and manage their own workload, retrieve their own data and logs. | Access the owner's personal files, apps, sensors, network credentials or another customer's environment. |
| Mossdial marketplace | Publish suitable listings, issue scoped lease authorization, coordinate connectivity, maintain billing and safety operations. | Receive customer workload plaintext or act as a silent man-in-the-middle in the private management channel. |
| Relay / network operator | Forward encrypted bytes where direct connectivity is unavailable. | Terminate or inspect the private customer-to-protected-environment session. |
| Visitors to a customer service | Use only the customer-published service. | Gain management access to the phone, other services or the private sandbox. |

Threats include a malicious customer, malicious or curious phone owner, rooted
or outdated device, compromised app or update, compromised coordination
service, network attacker, incorrect billing and accidental data exposure.
The owner can always interrupt power or connectivity: isolation does not
guarantee availability. Track physical attacks, firmware, side channels,
denial of service and supply-chain compromise explicitly in the threat model;
state which risks are mitigated and which remain.

## 4. Privacy architecture and hard feasibility gate

### 4.1 What encryption does and does not do

Use end-to-end authentication and encryption from the customer's trusted
client to the verified protected execution environment. A relay may forward
ciphertext. Encryption in transit does not protect plaintext from an owner
who controls the endpoint or from a compromised environment. Android Keystore
key storage, a foreground service, TLS, JavaScript isolation or an ordinary
app sandbox alone does not prove the owner cannot inspect a running workload.

The customer connects only after verifying the protected environment and its
public session key. The marketplace must not be able to substitute a different
key undetected. Define how the client authenticates marketplace identity,
verifier trust roots, signed release measurements and binding to the specific
lease; no trust-on-first-use or silent fallback is sufficient for private
rental.

### 4.2 Feasibility before product implementation

- [ ] Define the exact confidentiality and integrity promise, including a
      phone owner with physical possession and potential control of Android.
      Define supported threats and realistic limits before selecting technology.
- [ ] Survey protected execution mechanisms on real candidate Android models:
      platform/OEM support, availability to the actual app distribution,
      guest runtime requirements, memory protection, remote attestation,
      device lifecycle, cost and licensing. An Android protected VM or other
      hardware-backed mechanism is a candidate, not an assumed solution.
- [ ] Build a small real proof on an eligible physical phone that runs an
      independently identified guest, produces a verifiable fresh attestation,
      binds the guest session key to its measured identity and lease, and
      accepts encrypted customer data only after verification. Verify whether
      the actual app, OS version and hardware expose every required API.
- [ ] Have the customer-side verifier validate trusted roots, freshness,
      device/guest identity, approved software measurements, revocation and
      lease binding before establishing the session or releasing any secret.
      A device-key attestation alone is not proof that arbitrary app memory is
      confidential. Reject stale, malformed, replayed, wrong-device,
      unapproved, revoked, unavailable or unverifiable evidence.
- [ ] Establish an update, key rotation and revocation process, including
      compromised verifier roots, vulnerable guest images and lost devices.
      Specify how long offline leases remain authorized and how expiry is
      enforced without relying solely on an owner-controlled clock.
- [ ] Test the isolation against the documented owner-controlled threat model
      on actual devices. An implementation unable to substantiate the promised
      protection is **blocked** for private rentals, regardless of whether it
      can serve a website or API.

**Decision gate:** if no practical, supportable technology meets this privacy
requirement for the selected devices, stop private-marketplace launch and
report the blocker. Do not weaken the promise, send real customer secrets,
pretend ordinary app isolation is equivalent, or silently fall back to an
unverified device. Controlled experiments with non-sensitive test data may
continue on devices you own or have explicit permission to use; they are not
private-marketplace evidence.

## 5. Architecture to build

### 5.1 Device, control plane and independent server

1. The owner opts in within the Android app. Device admission checks identity,
   integrity and supported security configuration; health checks determine
   whether the phone can currently accept a rental.
2. The marketplace keeps listing and lease state, account and payment metadata,
   minimal device health, compatibility and an authorized connectivity route.
   It does not receive plaintext customer files, application input or secrets.
3. The customer chooses a specific phone and accepts exact price, duration,
   offered resource limits, availability policy and security profile.
4. An atomic lease operation prevents two customers receiving the same phone.
   The phone accepts a short-lived, scoped lease authorization only after it
   and the control plane verify the relevant identities and state.
5. The customer independently authenticates the selected protected environment
   and establishes an encrypted management session, direct when possible or
   through a relay that sees ciphertext and limited connection metadata.
6. The customer deploys into that phone's isolated environment. The phone's
   other app state and personal data are outside the sandbox; the phone owner
   cannot read the protected customer environment under the validated model.
7. The customer can explicitly publish a supported service to visitors. Its
   public ingress, hostnames, TLS termination and permissions are separate
   from private management access and are disabled until enabled.

The control plane may coordinate many phones, but there is no device-to-device
workload routing, memory aggregation, shared customer filesystem or cluster.
Do not implement automatic migration to another owner's phone. If a customer
later chooses a different phone, restoration is an explicit new rental from a
customer-controlled backup.

### 5.2 Resource offer and enforcement

The owner chooses the resources offered, subject to verified device capacity
and safe operational limits. The customer chooses among actual offers. Before
sale, clearly distinguish a measured or enforceable limit from a best-effort
estimate:

| Resource | Required design and evidence |
|---|---|
| CPU | Define whether the offer means a CPU time quota, actual core affinity or a maximum concurrency. Advertise exact enforceable behavior and architecture; do not equate mobile cores with cloud-server cores. |
| RAM | Specify an enforceable ceiling and whether any memory is genuinely reserved. Include the sandbox, runtime and OS overhead; handle pressure or allocation failure without claiming a reservation that does not exist. |
| Storage / ROM | Offer usable disk storage, not physical ROM access. Define capacity, quota enforcement, encryption, durability, retention, full-disk failure and cleanup. Owner personal storage stays inaccessible. |
| Network | Define private management, public ingress, allowed egress, rate and data limits, NAT/relay behavior and the effect of connection loss. Public IP or incoming ports are not assumed. |
| Power / heat | Require explicit owner consent and configurable safe operating conditions. Pause on supported measured limits; explain unknown readings and unsafe battery conditions. |

Reject or clearly omit any resource guarantee that the device or execution
environment cannot enforce. Benchmark real devices before displaying
performance comparisons. Do not describe one phone as a whole cloud platform.

### 5.3 Sandbox and workloads

- [ ] Choose an isolation mechanism only after section 4's feasibility gate.
      Define the trusted computing base and isolate customer processes, files,
      credentials and management endpoint from the owner and Android host to
      the extent required by the approved threat model.
- [ ] Provide a bounded, versioned workload interface and allowlisted
      runtimes. Begin with a small real web service or API; do not imply that
      arbitrary Linux distributions, Docker images, root access, GPUs,
      operating systems or binary architectures already work on Android.
- [ ] Apply least privilege: no access to owner's files, camera, microphone,
      SMS, sensors or credentials; no network or host capabilities except
      those explicitly enabled by the customer and permitted by the listing
      and policy. Protect public ingress from becoming a control endpoint.
- [ ] Enforce supported CPU, memory, storage, process, output, connection and
      time limits outside untrusted workload code. Define behavior on limit,
      out-of-memory, crash, disk full, interruption and cleanup.
- [ ] Arbitrary programs and broader runtime support require their own proven
      isolation, platform-policy, licensing and abuse controls. Add them
      incrementally rather than silently exposing unrestricted host execution.

## 6. Networking, availability and recovery

- [ ] Design authenticated device discovery and a direct P2P connection with
      NAT traversal where supported. Offer an encrypted relay when direct
      connectivity fails; identify and meter relay traffic. If neither path is
      available, show the phone as unreachable. Do not silently terminate
      private encryption at the relay.
- [ ] Authenticate both customer and protected environment; bind session keys
      to the selected phone, active lease and verified guest measurement.
      Expire sessions, prevent replay, rotate/revoke keys and limit connections.
- [ ] Separate customer management traffic, control-plane traffic and public
      application traffic. Plan DNS, certificate issuance, TLS termination,
      routing, abuse/rate limits and user-facing disclosure for public services.
      A tunnel's existence is not proof of public reachability or end-to-end
      encryption all the way to a protected guest.
- [ ] Track actual availability with independent observations when possible;
      distinguish local service state, phone heartbeat, customer connectivity
      and external public endpoint health. Report unknown intervals as unknown.
- [ ] Define lease behavior for sleep, process death, force-stop, Android
      update, power loss, thermal pause, owner stop and network changes.
      Reconnect with fresh authorization and attestation as needed. Do not
      claim an uninterrupted server when state was lost or is unknown.
- [ ] Use operation IDs and idempotent control requests. A lost response after
      an effect produces an unknown outcome until reconciled; never silently
      replay a potentially side-effecting operation.
- [ ] Offer customer-controlled encrypted backup/restore and a recovery window
      as a separate feature. Clearly state backup location, costs, retention and
      whether the customer has verified a restore. A restore to another phone
      starts a separate lease; it is not pooled or automatic failover.

## 7. Rental lifecycle, payment and owner experience

### 7.1 Lease state machine

Design durable, auditable transitions with idempotent request IDs:

    listed → reserved → verifying → active → suspended → active
                                      ↓          ↓
                                  ending → cleanup → closed

Any state may reach a documented failed, expired or disputed outcome. Only a
verified eligible device can become active; one device has at most one active
or reserved lease. Define reservation timeout, payment authorization, guest
startup, user cancellation, owner withdrawal, reconnect grace, lease expiry,
cleanup failure and recovery. A crash or duplicate request must not create two
leases, duplicate payouts, or silently claim an operation completed.

### 7.2 Pricing and accounting

- [ ] Choose and document whether the first offer is hourly, daily or another
      unit; disclose minimum rental, fees, taxes, network/relay charges and
      payment schedule before purchase. Do not invent earnings forecasts.
- [ ] Define exactly when billing starts/stops, who pays during disconnects,
      grace periods, canceled reservations, owner interruption, failures and
      customer abuse. Link each ledger line to the lease and a verifiable
      event without recording private workload contents.
- [ ] Reconcile independent control-plane and device evidence; do not assume
      owner-reported time or usage is tamper-proof. Use signed/attested
      evidence only where the underlying measurements and clock are actually
      trustworthy. Define disputes and uncertainty instead of fabricating
      exact usage. Do not offer precise usage-based billing before proving it.
- [ ] Implement an append-only auditable ledger, idempotent settlement,
      reconciliation, refunds/credits, payout holds, reversals, failed
      transfers and fraud review. Use a suitable payment provider and obtain
      advice on applicable identity, tax, consumer, payout and regional rules.
      Do not promise escrow or hold funds without verifying legal requirements.
- [ ] Treat early price and earning assumptions as hypotheses. Measure phone
      availability, actual utilization, operating costs and willingness to pay
      before scaling or promising income.

### 7.3 Owner and customer controls

The owner must explicitly enroll, view the offer and payout terms, configure
supported CPU/RAM/storage limits, set charging/Wi-Fi/data/operating-hour
preferences and pause or stop participation. Show actual status and earnings,
not customer content. Make clear how stopping affects an active lease and
avoid unsafe use of damaged or overheating batteries.

The customer must see the chosen phone, security profile, enforceable limits,
actual availability, price and likely failure modes before paying. Provide
secure deployment, status, customer-only logs, backup/export, renewal, end of
lease and support.

Account enrollment, information collection, user consent, support and abuse
handling are new to the current local-first product. Update privacy policies,
terms and in-app explanations to match the real implementation. Preserve the
existing offline/local hosting experience without forcing marketplace opt-in.

## 8. Data lifecycle and incident response

- [ ] Classify customer code, configuration, input, output, files, logs, keys,
      public service content, account/payment data and connection metadata.
      Define which party may access each category and for how long.
- [ ] Generate or derive customer-controlled keys with a reviewed design.
      Encrypt customer data at rest as appropriate; no marketplace or owner
      plaintext key access is implied. The precise storage/key design must be
      validated against the selected trusted execution mechanism.
- [ ] Define retention during an outage or expired lease, encrypted backup
      handling, key revocation and cleanup. Crypto-erasure is only claimable
      when all relevant keys and replicas are controlled; physical flash
      erasure cannot be promised without specific proof.
- [ ] Define minimal encrypted audit records, security event monitoring,
      privacy-preserving abuse reporting, incident containment, owner/customer
      notification and a way to disable vulnerable devices and guest versions.
      Logs must not collect customer payloads, secrets or traffic plaintext.
- [ ] Document tradeoffs: end-to-end privacy limits inspection and moderation;
      the owner can deny service; compromised software, hardware and endpoints
      remain risks. No system should be marketed as impossible to breach.

## 9. Marketplace and phone-server contract

Mossdial owns the marketplace, lease and protected phone environment. The
following are design targets for a versioned customer-to-server contract,
not currently implemented API names:

| Contract object / operation | Required semantics |
|---|---|
| Capability and listing | Protocol version, listing/device identity, platform/ABI, supported runtimes, verified security profile, enforceable resource ceilings, availability and itemized price. Unknown values remain unknown. |
| Lease | Unique lease ID, one device, one customer, authorized operations, expiry, terms/version and state. Idempotent reservation; no double-rental. |
| Verification | Fresh guest evidence, approved measurement/version, lease and peer-key binding, revocation state and a customer-verifiable result. A failure blocks confidential deployment. |
| Connection | Authenticated encrypted direct session or opaque encrypted relay; private management and optional public service ingress remain separate. |
| Deployment | Versioned artifact format, content integrity, resource requirements, explicit permissions, size/time limits, cancellation and an operation ID. No execution before policy and identity checks. |
| Status and result | Actual acknowledged state, timestamps with provenance, known errors and unknown-completion state. Never fabricate success, uptime, privacy or a workload result. |
| Logs and storage | Customer-authorized encrypted access, bounded streams and explicit durability/retention. No owner or control-plane plaintext access under the private profile. |
| Termination | Scoped revoke, guest shutdown, cleanup status and customer-visible outcome; billing reconciled separately and idempotently. |

Choose encodings, versions and stable error codes before coding. Define compatibility,
version negotiation, signed artifact/update provenance, credential rotation and recovery.

## 10. Implementation phases and acceptance

Phases are ordered by risk, not calendar promises. The maintainer owns
implementation and review; do not create external contribution or review
automation as a prerequisite.

### M0 — Baseline and product contract

- [ ] Verify the current app on a real Android device using the existing
      release checklist. Record OS/device/build, service/tunnel behavior and
      known gaps; do not treat a build or emulator smoke check as device proof.
- [ ] Specify threat model, one-phone lease semantics, privacy promise,
      provider/customer journeys, supported use cases, and independent-phone
      architecture. Specify the contract in section 9.
- [ ] Inventory candidate physical devices and the protected execution,
      attestation, resource-control, background-service and distribution APIs
      available to this app. Record unknowns and blockers.

**Exit:** documented requirements and physical-device baseline; no marketplace
or privacy claims.

### M1 — Prove or block protected customer execution

- [ ] Complete section 4's real-device feasibility and customer-side
      verification, including owner-control and replay/identity tests.
- [ ] Produce a reviewed design showing the protected guest, trusted computing
      base, supported OS/device combinations, residual risks and update process.
- [ ] Demonstrate a real bounded non-sensitive workload on a phone under the
      tested execution mechanism. Prove the permitted privacy claims separately.

**Exit:** an evidence-backed go/no-go for a specific device profile. If
blocked, do not proceed to a private customer pilot or collect customer data.

### M2 — One private phone server, no marketplace

- [ ] On an approved test device and with explicit owner/customer consent,
      implement authenticated connection, sandbox launch, one real web/API
      workload, resource ceilings, encrypted storage and customer-only logs.
- [ ] Test direct connectivity and, if required, an actual opaque relay. Test
      startup, stopping, disconnect/reconnect, denial, exhaustion and teardown.
- [ ] Integrate one supported runtime; keep Mossdial usable directly. Match
      client/guest versions and maintain the validated isolation boundary.

**Exit:** one customer can safely operate one independent phone server on the
documented profile, with real-device evidence and no implied marketplace.

### M3 — Listings, leases and billing preparation

- [ ] Implement opt-in owner listing, device checks, customer selection, atomic
      lease state and scoped authorization. Keep one renter per phone.
- [ ] Implement the durable ledger and reproducible reconciliation; choose a
      payment provider and finalize fees, payout, outage and dispute rules.
- [ ] Validate the legal, security, store-policy and operational obligations.
      Test actual local ledger/accounting and approved integration environments;
      do not fabricate external payment success.

**Exit:** leases and ledger survive retries, crashes and conflicts; payment
and legal requirements are resolved before involving real customers' money.

### M4 — Limited end-to-end pilot

- [ ] Invite a small number of explicitly consenting owners and customers.
      Enroll only approved phone/software profiles. Enable real payments only
      after provider setup, legal requirements and controls are complete.
- [ ] Pilot supported web/API workloads, measure actual availability, resource
      limits, network behavior, owner earnings, customer cost and support load.
      Collect no customer payloads in platform diagnostics.
- [ ] Publish accurate incident, outage, refund, backup and privacy terms.
      Suspend affected profiles and notify participants on verified incidents.

**Exit:** a completed, reconciled rental from an independent customer to one
phone, supported by privacy, operational and financial evidence; unresolved
security defects block expansion.

### M5 — Expand supported devices and workloads

- [ ] Qualify new device/OS profiles one by one, with actual protected
      execution and attestation evidence. Add only runtime and resource features
      that are enforceable on those profiles.
- [ ] Improve capacity discovery, monitoring, support and incident response
      using real pilot data. Keep phone servers independent.
- [ ] Consider optional per-request serverless features only as a separate,
      scoped design on an individual rented phone.

## 11. Verification matrix

| Area | Real verification and acceptance |
|---|---|
| Device admission | Supported device/OS/guest is identified and verified; rooted, revoked, unpatched, unsupported or unverified cases are refused according to the defined threat model. |
| Owner versus customer | On real candidate devices, test the documented owner-control attacks against guest memory, storage, logs, session identity and keys. Record residual risks; a failed privacy requirement blocks private rental. |
| Customer versus owner | Workload cannot access the owner's app data, device capabilities or files; violations are denied and logged without private payloads. |
| Encryption / identity | Fresh attestation and peer-key binding verified by the customer; wrong phone, replay, stale or unapproved guest, tampered keys and unauthorized lease fail before data release. |
| Direct and relay | Real direct session when possible, real relay when needed, ciphertext through relay, explicit failure when both unavailable. Public website ingress is tested separately. |
| Resource limits | Real stress of each advertised limit, boundary, allocation failure, thermal response and disk-full behavior; distinguish ceiling from reserved resource. |
| Lease and billing | Concurrent reservations, duplicate messages, disconnect, expiry, cancellation, refund and payout reconciliation do not double-rent or double-charge. Use actual authorized integrations when testing payments. |
| Reliability | Real screen lock, app/process stop, reboot, network change, power interruption and recovery on the supported device/OS; report missing hardware or permissions. |
| Data lifecycle | Customer-only access to files/logs, encrypted backup/restore, expiry and cleanup, key revocation and accurately reported deletion limitations. |
| Isolation between rentals | New customer cannot read prior customer's data or credentials; independent leases never share workload state. |
| User experience | Owner opt-in, stop and payout visibility; customer can assess price, profile, limitations, availability and outage outcome without invented claims. |
| Integration | A real customer client and an approved Mossdial server agree on contract/version, credentials, denial, cancellation and error handling. |

Record source revisions and dirty state, artifact hashes, device model, OS and
patch level, security profile, test consent, actual commands, non-sensitive
inputs, observed results and limitations. Never publish customer payloads,
credentials or private keys in evidence. If a required real device or service
is unavailable, record the coverage as blocked.

## 12. Definition of done

- [ ] One eligible Android phone can be rented exclusively as one independent
      sandbox server, and local web hosting remains usable without the marketplace.
- [ ] The customer's required privacy from the owner is supported by an
      independently verified, device-specific isolation design; no unverifiable
      device is marketed or rented under that private-server promise.
- [ ] Customer and owner access boundaries, encrypted sessions, resource
      limits, lifecycle, recovery, data handling and public ingress are tested
      on real hardware and described with their actual limitations.
- [ ] Real lease, payment, payout, refund, incident and support processes work
      for the supported pilot region and are reconciled without false success.
- [ ] The customer client and protected server conform to the versioned contract;
      local hosting works independently of the marketplace.
- [ ] Public claims, privacy policy, store declarations and release checklist
      match the tested artifact. Unsupported devices and missing evidence are
      visible, and no absolute breach-free or uptime guarantee is made.
