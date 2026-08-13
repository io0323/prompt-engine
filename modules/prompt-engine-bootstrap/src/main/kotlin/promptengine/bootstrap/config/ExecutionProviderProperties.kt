package promptengine.bootstrap.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 実行時に使用する[promptengine.domain.execution.ExecutionAdapter]実装の選択
 * （`promptengine.execution.provider`、ADR-0030決定4、ADR-0031）。
 *
 * APAPは独立基盤として別途構築する方針（ADR-0031）。実接続が無い間は`fake`のみ実装があり、
 * `apap`は将来の切替先として値だけ受け付ける（実装は`ExecutionConfig`が`apap`選択時に
 * fail-fastする形で示す）。既定は`fake`——設定を変えない限りM1からの挙動を変えない
 * （`FakeExecutionAdapter`自身が持つ`production`プロファイルガードは本設定の値に関わらず
 * 常に有効なままであり、`provider=fake`のまま本番へデプロイすれば従来通り起動が失敗する）。
 *
 * `provider`が既知の値かどうか・未知の値をどう扱うかの判断、実際のアダプタ実装への
 * ディスパッチは`ExecutionConfig`（Composition Root）の責務とする。本クラスは具体的な
 * プロバイダ名を知らない、純粋な設定値の保持・基本検証のみを行う。
 */
@ConfigurationProperties(prefix = "promptengine.execution")
data class ExecutionProviderProperties(
    val provider: String = DEFAULT_PROVIDER,
) {
    init {
        require(provider.isNotBlank()) { "promptengine.execution.provider must not be blank" }
    }

    companion object {
        private const val DEFAULT_PROVIDER = "fake"
    }
}
