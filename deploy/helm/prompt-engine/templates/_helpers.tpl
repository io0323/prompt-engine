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
    # Flyway専用の資格情報（Issue #85、ADR-0036）。datasourceの資格情報とは別ロール
    # （prompt_engine_migrator）を指す。secret.yamlのKDoc参照。
    - name: PE_FLYWAY_DATASOURCE_USERNAME
      valueFrom:
        secretKeyRef:
          name: {{ .root.Values.secret.name }}
          key: flyway-datasource-username
    - name: PE_FLYWAY_DATASOURCE_PASSWORD
      valueFrom:
        secretKeyRef:
          name: {{ .root.Values.secret.name }}
          key: flyway-datasource-password
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
    # 実行アダプタの選択・ModelProfile設定（ADR-0030決定1・4、ADR-0031）。
    - name: PE_EXECUTION_PROVIDER
      valueFrom:
        configMapKeyRef:
          name: {{ include "prompt-engine.fullname" .root }}-config
          key: execution-provider
    - name: PE_MODEL_PROFILE_MAX_CONTEXT_TOKENS
      valueFrom:
        configMapKeyRef:
          name: {{ include "prompt-engine.fullname" .root }}-config
          key: model-profile-max-context-tokens
    - name: PE_MODEL_PROFILE_TOKENIZER_ID
      valueFrom:
        configMapKeyRef:
          name: {{ include "prompt-engine.fullname" .root }}-config
          key: model-profile-tokenizer-id
    - name: PE_MODEL_PROFILE_COST_PER_TOKEN
      valueFrom:
        configMapKeyRef:
          name: {{ include "prompt-engine.fullname" .root }}-config
          key: model-profile-cost-per-token
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
