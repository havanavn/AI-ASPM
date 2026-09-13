# Kubernetes deployment — mesh-native

**Read the first section before installing.** It says what this chart stands up, what it expects to
already exist, and what it does not do yet.

---

## What this is

The same artifact the compose deployment runs (`OPS-DEP-003`), deployed as the runtime units of
DOC-15 §4 that exist today:

| Unit | Deployment | `ASPM_ROLE` | May reach (egress classes, from the model) |
|---|---|---|---|
| Application tier | `<release>-app` | `app` | `IDENTITY_PROVIDER`, `SECRETS_STORE`, `KEY_MANAGEMENT` |
| General workers | `<release>-worker` | `worker` | `CONNECTOR_ALLOWLIST`, `WEBHOOK_ALLOWLIST`, `MAIL_RELAY`, `MODEL_PROVIDER`, `SECRETS_STORE`, `KEY_MANAGEMENT` |

The table is not typed into the chart. `src/deployment` — the Java model of DOC-15 — generates
`chart/aspm/files/model.yaml` (`KubernetesManifestsTest` writes it on every build and fails on drift),
and every Deployment, NetworkPolicy, probe, resource envelope and Istio `Sidecar` is rendered from that
file. A policy that says something the model does not is therefore a diff, not a discovery.

**Mesh-native, meaning:**

- TLS terminates at the ingress (ADR-057, `OPS-DEP-017`); the ingress makes no authorization decision.
- Between units, mTLS when a mesh is present — Istio `PeerAuthentication STRICT`, or Linkerd injection.
- Egress is **deny-by-default per unit** (`OPS-DEP-014`), enforced twice: a NetworkPolicy generated from
  the model, and — under Istio — a `Sidecar` with `REGISTRY_ONLY` outbound plus one `ServiceEntry` per
  allowed external host. Internal, link-local, CGNAT and loopback ranges are excluded from every
  Internet-facing class (`OPS-DEP-015`); the application's `EgressGuard` refuses the same ranges at the
  application layer, so a misconfigured policy and a compromised worker have to agree to leak.
- Secrets are **files**, never environment variables (`OPS-DEP-019`, `OPS-DEP-020`): a Kubernetes Secret,
  a Secrets Store CSI `SecretProviderClass` (Vault/OpenBao, Azure Key Vault, AWS Secrets Manager, Google
  Secret Manager) or an External Secrets target projects them into `/var/run/secrets/aspm`, and the
  application reads `ASPM_*_REF=file:<name>` references at start, in memory.
- Migrations and the post-deploy conformance job are **Helm hooks**: `migrate` runs before the new
  version as `aspm_migrate`, `conformance` runs after as `aspm_verify`, and a failure in either fails
  the release (`OPS-DEP-031`). Both run the same `apply.sh` and `conformance.sql` the laptop runs;
  the test copies them into the chart.

## Options

Every option is a value; none is a code path (ADR-027 applied to deployment).

| Option | Values | Notes |
|---|---|---|
| `ingress.kind` | `gatewayApi` (default), `nginx`, `none` | Gateway API is mesh-neutral; both Istio and Linkerd implement it |
| `mesh.kind` | `none` (default), `istio`, `linkerd` | NetworkPolicy applies regardless |
| `secrets.provider` | `file` (default), `vault`, `azkv`, `awssm`, `gcpsm` | Selects the application's writable store for tenant-entered secrets; `file` enables the platform-provided sealed store |
| `secrets.csi.enabled` | `false`, `true` | Fill the mount from the enterprise store through the CSI driver instead of a Kubernetes Secret |
| `postgres.*` | host/port/database and the three login users | Managed PostgreSQL 18 (ADR-049: 18 is the floor) |
| `egress.destinations.<CLASS>` | hosts, CIDRs, ports | Where each destination class lives in this network; empty = unreachable (air-gapped: leave `IDENTITY_PROVIDER`, `MODEL_PROVIDER`, `INTELLIGENCE_FEED` empty) |
| `smtp.relays`, `smtp.cleartextRelays` | `host:port` | Operator-vouched relays that may be private / spoken to in the clear |
| `connectors.enabledKinds` | subset of `JIRA_CLOUD`, `JIRA_DATA_CENTER`, `GITLAB`, `SERVICENOW`, `GENERIC_WEBHOOK`; empty = all | Air-gapped: list only what is reachable; disabled kinds are shown with their consequence, not hidden (`PRD-CON-054`) |
| `connectors.expiryWarningDays` | days | How far ahead a connector's owner is told a credential expires (`PRD-CON-023`) |
| `ai.modelEndpoints` | base URLs | Self-hosted inference servers (vLLM, Ollama, a gateway) the operator vouches for, so a tenant may configure them although they are private (`PRD-AIC-025`, ADR-075); hosted providers need no entry |
| `scanner.enabled` | `false`, `true` | The Trivy re-scan ticker; needs a signed credential issued in the interface first |

## Prerequisites

1. **PostgreSQL 18 or later**, reachable at `postgres.host`, with the five group roles and three login
   roles of DOC-15 §5.1 created **once by a DBA** with `deploy/postgres/01-login-roles.sh` (it needs a
   superuser; the chart never holds one). The chart holds `aspm_app` (application), `aspm_migrate`
   (migrate hook only) and `aspm_verify` (conformance hook only) — never `offboarding_executor`.
2. **An S3-compatible object store** on its own registrable domain (`OPS-DEP-016`), and **Valkey**.
3. **The deployment secrets**, as a Secret whose keys are file names — or the CSI class the chart renders:

   ```
   db-app-password  db-migrate-password  db-verify-password
   objectstore-user  objectstore-password
   credential-key            # base64 of 32 random bytes: openssl rand -base64 32
   bootstrap-password        # first administrator's initial password (ADR-059)
   ```

   ```bash
   kubectl -n aspm create secret generic aspm-deployment-secrets \
     --from-literal=db-app-password=… --from-literal=db-migrate-password=… --from-literal=db-verify-password=… \
     --from-literal=objectstore-user=… --from-literal=objectstore-password=… \
     --from-literal=credential-key="$(openssl rand -base64 32)" --from-literal=bootstrap-password=…
   ```
4. **Images**: `deploy/app/Dockerfile` (the application, both units) and `deploy/migrate/Dockerfile`
   (the runner). Build the second after `deploy/collect-migrations.sh`. Pin by digest (`OPS-DEP-028`).
5. A Gateway (Gateway API) or an ingress controller, with the TLS certificate for `ingress.host`.

## Install

```bash
cd deploy
./collect-migrations.sh
docker build -t ghcr.io/example/aspm-migrate:1.0.0 migrate/
(cd ../src && ./gradlew :app:installDist && cp -r app/build/install/app ../deploy/app/install)
docker build -t ghcr.io/example/aspm-app:1.0.0 app/

helm upgrade --install aspm k8s/chart/aspm -n aspm --create-namespace \
  --set image.repository=ghcr.io/example/aspm-app --set image.digest=sha256:… \
  --set migrateImage.repository=ghcr.io/example/aspm-migrate --set migrateImage.digest=sha256:… \
  --set ingress.host=aspm.example.com --set publicBaseUrl=https://aspm.example.com \
  --set mesh.kind=istio --set secrets.provider=vault --set secrets.csi.enabled=true \
  --set secrets.vault.address=https://vault.example.com --set secrets.vault.kubernetesRole=aspm \
  --set postgres.host=pg.example.internal
```

Rendering without a cluster, for review:

```bash
helm template aspm k8s/chart/aspm --set mesh.kind=istio > /tmp/aspm-istio.yaml
helm template aspm k8s/chart/aspm --set mesh.kind=linkerd --set ingress.kind=nginx > /tmp/aspm-linkerd.yaml
```

## What this chart does not do

Stated rather than left to be discovered.

| Gap | Where it is |
|---|---|
| Provision the data tier | Bring managed PostgreSQL 18, Valkey and object storage; the compose file's in-cluster instances are for a laptop |
| Create database roles | `deploy/postgres/01-login-roles.sh`, once, by a DBA with a superuser |
| Deploy match, projection or scheduler units | Modelled in DOC-15 §4 and listed in `files/model.yaml` under `notShipped` with the reason each does not yet exist as a process |
| Rate limiting at the ingress (`OPS-DEP-017`) | The Gateway or ingress controller's own policy; the application declares rate classes and enforces none |
| Backups, restore rehearsal, DR (`OPS-DEP-033`–`037`) | The managed store's responsibility and the operator's runbooks; nothing in this chart claims them |
| Multi-tenant routing | The application tier serves one tenant per deployment (`tenantId`); a second tenant is a second release |
