# ADR-0017: REST API（P9）のQuery側Read Modelポートを確定する（9a）

## ステータス

Accepted

## コンテキスト

設計書§13.1のM1対象エンドポイントのうち、`GET /prompts`（検索）・
`GET /prompts/{key}/dependencies`・`GET /audit-logs`・`GET /metrics/prompts/{key}`は、
いずれも既存のCommand側ポート（`PromptRepository`はAggregate単位の`findByKey`/`save`
のみ、`AuditRepository`はPipeline実行専用の`append`のみ）では応えられない。
設計書§2.14は「Query側: Read Model（非正規化ビュー）と Search Index」と定めており、
Command側（Aggregate）と独立した読み取り専用ポートを新設する方針が既に確立している。

`prompt-engine-interface`のController実装（9c）に先立ち、これらQuery系エンドポイントが
共通で必要とするページング型と、各エンドポイント固有のRead Modelポートをここで確定する。

## 決定

### 1. `Page<T>`（`domain.shared`、全Query系エンドポイント共通）

```kotlin
data class Page<T>(val items: List<T>, val page: Int, val size: Int, val totalElements: Long) {
    init {
        require(page >= 0)
        require(size in 1..MAX_SIZE)
        require(totalElements >= 0)
    }
    companion object { const val DEFAULT_SIZE = 20; const val MAX_SIZE = 100 }
}
```

設計書§13共通仕様のページング（既定20・上限100）をdomain型として表現する。
`size`の上限強制はこの型自身の不変条件とする（呼出側でのチェック漏れを防ぐ）。

### 2. `AuditRepository`に`record`/`search`を追加する（Issue #35を本ADRでクローズ）

既存の`append(AuditRecord)`（ADR-0015決定7、Pipeline Stage 12専用、`traceId`/
`promptKey`/`mode`/`stageDurationsMs`/`outcome`のみの狭い形）は変更しない。
CRUD/lifecycle系のCommandハンドラ（9b）が記録する監査エントリは、`audit_logs`
テーブル（設計書§12、`aggregate_type`/`aggregate_id`/`action`/`actor`/`payload`/
`trace_id`/`occurred_at`）の列にそのまま対応する一般形`AuditLogEntry`とし、
`record(entry: AuditLogEntry)`を追加する。`search(query: AuditQuery): Page<AuditLogEntry>`
は`append`由来（Pipeline実行）・`record`由来（governance操作）のいずれの行も
`AuditLogEntry`として返す（Pipeline実行の行は`aggregateType="PipelineExecution"`、
`payload`に`stageDurationsMs`/`outcome`をJSONで格納したものとして読み出す）。

```kotlin
data class AuditLogEntry(
    val auditId: UUID, val aggregateType: String, val aggregateId: String,
    val action: String, val actor: String, val payload: String,
    val traceId: String, val occurredAt: Instant,
)
data class AuditQuery(
    val aggregateId: String? = null, val actor: String? = null,
    val from: Instant? = null, val to: Instant? = null,
    val page: Int = 0, val size: Int = Page.DEFAULT_SIZE,
)
interface AuditRepository {
    fun append(record: AuditRecord)               // 既存、Pipeline Stage 12専用
    fun record(entry: AuditLogEntry)               // 新設、governance操作用
    fun search(query: AuditQuery): Page<AuditLogEntry>  // 新設
}
```

この変更はP8の`AuditRecord`/`AuditStage`のシグネチャ・挙動を一切変更しない
（インターフェースへのメソッド追加のみ）。`InMemoryAuditRepository`は
テスト・ローカル開発用に`record`/`search`も実装する。本番相当の永続化は
`JdbcAuditRepository`（9a、新設）が担う。

### 3. `DependencyRepository`（`domain.dependency`、新設）

```kotlin
enum class DependencyKind { TEMPLATE, FRAGMENT, PROMPT }
data class DependencyEdge(
    val fromKey: PromptKey, val fromVersion: SemVer,
    val toKind: DependencyKind, val toKey: String, val toVersion: String?,
)
interface DependencyRepository {
    fun findOutbound(promptKey: PromptKey): List<DependencyEdge>  // direction=out: Published版が直接参照する依存
    fun findInbound(promptKey: PromptKey): List<DependencyEdge>   // direction=in: このPromptを参照する側
}
```

`toKey`/`toVersion`は参照先がTemplate/Fragment/Promptのいずれもあり得るため、
`PromptKey`/`SemVer`のバリデーションを参照先の種別ごとに使い分けず、文字列のまま保持する。

### 4. `MetricsRepository`（`domain.metrics`、新設）

```kotlin
data class MetricsSummary(
    val promptKey: PromptKey, val from: Instant, val to: Instant,
    val executionCount: Long, val successCount: Long,
    val totalInputTokens: TokenCount, val totalOutputTokens: TokenCount,
    val totalCost: Cost, val averageLatency: LatencyMs,
) { val successRate: Double get() = if (executionCount == 0L) 0.0 else successCount.toDouble() / executionCount }
interface MetricsRepository {
    fun summarize(promptKey: PromptKey, from: Instant, to: Instant): MetricsSummary
}
```

`execution_logs`（設計書§12）の集計。対象行が無い場合も0値の`MetricsSummary`を返す
（呼出元が`null`分岐を持たずに済むようにする）。

### 5. `PromptSearchRepository`（`domain.prompt`、新設）

```kotlin
data class PromptSearchCriteria(
    val q: String? = null, val tag: String? = null, val category: String? = null,
    val status: LifecycleState? = null, val page: Int = 0, val size: Int = Page.DEFAULT_SIZE,
)
data class PromptSummary(
    val key: PromptKey, val name: String, val category: String?, val tags: List<String>,
    val status: LifecycleState, val latestVersion: String, val publishedVersion: String?,
)
interface PromptSearchRepository {
    fun search(criteria: PromptSearchCriteria): Page<PromptSummary>
}
```

`PromptRepository`（Command側、Aggregate単位）とは独立した読み取り専用ポート
（設計書§2.14 Query側=Read Model）。`name`/`category`/`tags`はAggregateの外側にある
属性のため（ADR-0020参照）、`PromptMetadataRepository`と合わせて読み合わせる実装
（9a、JDBC）を`prompt-engine-infrastructure`に置く。

## 影響範囲

- `prompt-engine-domain`: `domain.shared.Page`、`domain.audit.AuditLogEntry`/
  `AuditQuery`（`AuditRepository`へ`record`/`search`追加）、`domain.dependency`
  （新設）、`domain.metrics`（新設）、`domain.prompt.PromptSearchRepository`
  （新設、ADR-0020のPromptMetadataと合わせて使用）
- `prompt-engine-infrastructure`: 上記ポートのJDBC実装（`JdbcAuditRepository`
  ※既存`InMemoryAuditRepository`は残しrecord/search実装を追加、
  `JdbcDependencyRepository`、`JdbcMetricsRepository`、`JdbcPromptSearchRepository`）
- GitHub Issue #35（`AuditRepository`本実装への置換）を本ADRでクローズする

## 参照

- [PromptEngine_設計書.md §2.14 / §12 / §13.1](../PromptEngine_設計書.md)
- [ADR-0015: Pipeline Orchestrator（AuditRecord・InMemory実装の前例、決定7）](0015-pipeline-orchestrator.md)
- [ADR-0020: Promptメタデータ（name/category/tags）をAggregate外で扱う](0020-prompt-metadata-outside-aggregate.md)
- GitHub Issue #35（本ADRでクローズ）
