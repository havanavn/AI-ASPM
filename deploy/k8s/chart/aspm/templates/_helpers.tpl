{{/*
Helpers. The model file is the source of truth for units; these helpers read it once.
*/}}
{{- define "aspm.model" -}}
{{- .Files.Get "files/model.yaml" | fromYaml | toJson -}}
{{- end -}}

{{- define "aspm.name" -}}
{{- .Chart.Name -}}
{{- end -}}

{{- define "aspm.fullname" -}}
{{- printf "%s" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "aspm.labels" -}}
app.kubernetes.io/name: {{ include "aspm.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{/* Labels that select ONE unit's pods. Every policy keys on these. */}}
{{- define "aspm.unitLabels" -}}
app.kubernetes.io/name: {{ include "aspm.name" .root }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .unit }}
{{- end -}}

{{/* image reference: digest wins over tag (OPS-DEP-028). */}}
{{- define "aspm.image" -}}
{{- if .digest -}}
{{ .repository }}@{{ .digest }}
{{- else -}}
{{ .repository }}:{{ .tag | default "latest" }}
{{- end -}}
{{- end -}}

{{/* The environment shared by app and worker, minus the role. Secrets arrive as FILES, never as env. */}}
{{- define "aspm.commonEnv" -}}
- name: ASPM_DB_URL
  value: {{ printf "jdbc:postgresql://%s:%d/%s" .Values.postgres.host (int .Values.postgres.port) .Values.postgres.database | quote }}
- name: ASPM_DB_USER
  value: {{ .Values.postgres.appUser | quote }}
- name: ASPM_DB_PASSWORD_REF
  value: {{ printf "file:%s" .Values.postgres.appPasswordKey | quote }}
- name: ASPM_TENANT_ID
  value: {{ .Values.tenantId | quote }}
- name: ASPM_ENVIRONMENT
  value: {{ .Values.environment | quote }}
- name: ASPM_PORT
  value: "8080"
- name: ASPM_PUBLIC_BASE_URL
  value: {{ .Values.publicBaseUrl | quote }}
- name: ASPM_OBJECTSTORE_ENDPOINT
  value: {{ .Values.objectStore.endpoint | quote }}
- name: ASPM_OBJECTSTORE_REGION
  value: {{ .Values.objectStore.region | quote }}
- name: ASPM_OBJECTSTORE_USER_REF
  value: {{ printf "file:%s" .Values.objectStore.userKey | quote }}
- name: ASPM_OBJECTSTORE_PASSWORD_REF
  value: {{ printf "file:%s" .Values.objectStore.passwordKey | quote }}
- name: ASPM_SECRETS_DIR
  value: {{ .Values.secrets.file.mountPath | quote }}
- name: ASPM_CREDENTIAL_KEY_REF
  value: "file:credential-key"
- name: ASPM_BOOTSTRAP_PASSWORD_REF
  value: {{ printf "file:%s" .Values.bootstrapPasswordKey | quote }}
{{- if eq .Values.secrets.provider "file" }}
- name: ASPM_SECRETS_PROVIDERS
  value: "file,sealed"
{{- else if eq .Values.secrets.provider "vault" }}
- name: ASPM_SECRETS_PROVIDERS
  value: "file,vault"
- name: ASPM_SECRETS_WRITER
  value: "vault"
- name: ASPM_VAULT_ADDR
  value: {{ .Values.secrets.vault.address | quote }}
- name: ASPM_VAULT_MOUNT
  value: {{ .Values.secrets.vault.mount | quote }}
{{- if .Values.secrets.vault.namespace }}
- name: ASPM_VAULT_NAMESPACE
  value: {{ .Values.secrets.vault.namespace | quote }}
{{- end }}
- name: ASPM_VAULT_K8S_ROLE
  value: {{ .Values.secrets.vault.kubernetesRole | quote }}
- name: ASPM_VAULT_K8S_AUTH_PATH
  value: {{ .Values.secrets.vault.kubernetesAuthPath | quote }}
{{- else if eq .Values.secrets.provider "azkv" }}
- name: ASPM_SECRETS_PROVIDERS
  value: "file,azkv"
- name: ASPM_SECRETS_WRITER
  value: "azkv"
- name: ASPM_AZKV_VAULT
  value: {{ .Values.secrets.azkv.vaultName | quote }}
- name: AZURE_TENANT_ID
  value: {{ .Values.secrets.azkv.tenantId | quote }}
- name: AZURE_CLIENT_ID
  value: {{ .Values.secrets.azkv.clientId | quote }}
{{- else if eq .Values.secrets.provider "awssm" }}
- name: ASPM_SECRETS_PROVIDERS
  value: "file,awssm"
- name: ASPM_SECRETS_WRITER
  value: "awssm"
- name: ASPM_AWSSM_REGION
  value: {{ .Values.secrets.awssm.region | quote }}
{{- else if eq .Values.secrets.provider "gcpsm" }}
- name: ASPM_SECRETS_PROVIDERS
  value: "file,gcpsm"
- name: ASPM_SECRETS_WRITER
  value: "gcpsm"
- name: ASPM_GCPSM_PROJECT
  value: {{ .Values.secrets.gcpsm.project | quote }}
{{- end }}
{{- if .Values.smtp.relays }}
- name: ASPM_SMTP_RELAYS
  value: {{ join "," .Values.smtp.relays | quote }}
{{- end }}
{{- if .Values.smtp.cleartextRelays }}
- name: ASPM_SMTP_CLEARTEXT_RELAYS
  value: {{ join "," .Values.smtp.cleartextRelays | quote }}
{{- end }}
{{- if .Values.connectors.enabledKinds }}
- name: ASPM_CONNECTOR_KINDS
  value: {{ join "," .Values.connectors.enabledKinds | quote }}
{{- end }}
- name: ASPM_CONNECTOR_EXPIRY_WARNING_DAYS
  value: {{ .Values.connectors.expiryWarningDays | quote }}
{{- if .Values.ai.modelEndpoints }}
- name: ASPM_MODEL_ENDPOINTS
  value: {{ join "," .Values.ai.modelEndpoints | quote }}
{{- end }}
{{- end -}}

{{/* The secrets volume: an existing Secret, or a CSI SecretProviderClass filling the same mount. */}}
{{- define "aspm.secretsVolume" -}}
- name: aspm-secrets
{{- if .Values.secrets.csi.enabled }}
  csi:
    driver: secrets-store.csi.k8s.io
    readOnly: true
    volumeAttributes:
      secretProviderClass: {{ .Values.secrets.csi.providerClassName | quote }}
{{- else }}
  secret:
    secretName: {{ .Values.secrets.file.existingSecret | quote }}
    defaultMode: 0400
{{- end }}
{{- end -}}

{{/* Mesh annotations per pod. */}}
{{- define "aspm.meshPodAnnotations" -}}
{{- if eq .Values.mesh.kind "linkerd" }}
linkerd.io/inject: {{ ternary "enabled" "disabled" .Values.mesh.linkerd.inject | quote }}
{{- else if eq .Values.mesh.kind "istio" }}
sidecar.istio.io/inject: "true"
{{- end }}
{{- end -}}
