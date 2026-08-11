{{- define "prompt-engine.fullname" -}}
{{ .Release.Name }}-prompt-engine
{{- end -}}

{{- define "prompt-engine.labels" -}}
app.kubernetes.io/name: prompt-engine
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
api/worker/adminで共通のcontainer定義。roleごとに変わるのはPE_SCHEDULER_ENABLEDのみ
（deploy/docker/DockerfileでビルドしたイメージはPromptEngineApplication一つで共通、
CLAUDE.md「モジュール構成」・Chart.yaml参照）。
*/}}
{{- define "prompt-engine.container" -}}
- name: prompt-engine
  image: "{{ .root.Values.image.repository }}:{{ .root.Values.image.tag }}"
  imagePullPolicy: {{ .root.Values.image.pullPolicy }}
  ports:
    - name: http
      containerPort: {{ .root.Values.probes.port }}
  env:
    - name: PE_DATASOURCE_URL
      valueFrom:
        configMapKeyRef:
          name: {{ include "prompt-engine.fullname" .root }}-config
          key: datasource-url
    - name: PE_DATASOURCE_USERNAME
      valueFrom:
        secretKeyRef:
          name: {{ .root.Values.secret.name }}
          key: datasource-username
    - name: PE_DATASOURCE_PASSWORD
      valueFrom:
        secretKeyRef:
          name: {{ .root.Values.secret.name }}
          key: datasource-password
    - name: PE_CIAP_JWKS_URI
      valueFrom:
        configMapKeyRef:
          name: {{ include "prompt-engine.fullname" .root }}-config
          key: ciap-jwks-uri
    - name: PE_OTEL_EXPORTER_ENDPOINT
      valueFrom:
        configMapKeyRef:
          name: {{ include "prompt-engine.fullname" .root }}-config
          key: otel-exporter-endpoint
    - name: PE_EVENTBUS_KAFKA_BOOTSTRAP_SERVERS
      valueFrom:
        configMapKeyRef:
          name: {{ include "prompt-engine.fullname" .root }}-config
          key: eventbus-kafka-bootstrap-servers
    - name: SPRING_PROFILES_ACTIVE
      value: "production"
    - name: PE_SCHEDULER_ENABLED
      value: {{ .schedulerEnabled | quote }}
  resources:
    {{- toYaml .root.Values.resources | nindent 4 }}
  livenessProbe:
    httpGet:
      path: {{ .root.Values.probes.livenessPath }}
      port: {{ .root.Values.probes.port }}
    initialDelaySeconds: 20
    periodSeconds: 10
  readinessProbe:
    httpGet:
      path: {{ .root.Values.probes.readinessPath }}
      port: {{ .root.Values.probes.port }}
    initialDelaySeconds: 10
    periodSeconds: 5
  securityContext:
    runAsNonRoot: true
    allowPrivilegeEscalation: false
    readOnlyRootFilesystem: true
    capabilities:
      drop: ["ALL"]
  volumeMounts:
    - name: tmp
      mountPath: /tmp
{{- end -}}

{{/* readOnlyRootFilesystemのため、JVM/Tomcatが書き込む一時領域をemptyDirで確保する。 */}}
{{- define "prompt-engine.volumes" -}}
volumes:
  - name: tmp
    emptyDir: {}
{{- end -}}
