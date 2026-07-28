package promptengine.domain.shared

/**
 * [promptengine.domain.template.ExtendsRef] の直接構築専用API（ADR-0009）。
 *
 * `ExtendsRef(key, range)` はDSLソース（`content.source`）のフロントマター`extends`
 * 文字列から導出されるべき値であり、`content` とは独立に任意の値を構築できてしまうと
 * 「保存された参照 == DSLソースをパースした結果」という整合性を型で保証できなくなる
 * （domain層はパーサに依存できないため、この整合性チェック自体をdomain内に実装できない）。
 *
 * そのため`ExtendsRef`の直接構築は、DSLソースから実際に導出する2箇所
 * （`promptengine.engine.compiler.ExtendsFieldMapper` によるフロントマター解析、
 * `promptengine.infrastructure.persistence` によるDB行からの復元 ── DBの行自体が
 * 過去にMapperを経由して書き込まれた結果であることを信頼する）にのみ許可する
 * （`@PersistenceApi` と同じ、`@RequiresOptIn` によるモジュール境界非依存の強制）。
 *
 * これは「同じ`content.source`から一貫して導出されたか」までは保証しない
 * （それは値どうしの意味的な整合性であり型システムの範囲外）。保証するのは
 * 「`ExtendsRef`が常に何らかのパース結果として得られたものであり、
 * アプリケーションコードが手書きの任意値を紛れ込ませる経路が無い」ことまで。
 */
@RequiresOptIn(
    message = "ExtendsRefの直接構築はDSLソースからの導出専用（ExtendsFieldMapper／永続化層の復元）に限る。",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
annotation class ExtendsRefApi
