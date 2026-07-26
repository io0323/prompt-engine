package promptengine.domain.prompt

/**
 * 永続化層からの復元専用API（[Prompt.restore]）にのみ付与するマーカー（ADR-0006）。
 *
 * Kotlinの `internal` はGradleモジュール単位のため、`domain` の `internal` は
 * `infrastructure` にも `application` にも等しく届かない。`@RequiresOptIn` は
 * モジュール境界と無関係にコンパイル時チェックされるため、
 * 「`infrastructure.persistence` にだけ復元を許可する」を実現できる唯一の手段として使う。
 * `promptengine.infrastructure.persistence..` 以外からの利用は
 * `prompt-engine-bootstrap` の `ArchitectureTest` でも検証する。
 */
@RequiresOptIn(
    message = "Prompt.restoreは永続化層からの復元専用。通常の新規作成は Prompt.create / newVersion を使うこと。",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
annotation class PersistenceApi
