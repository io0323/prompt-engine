package promptengine.bootstrap.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 実行時に使用する[promptengine.domain.execution.ExecutionAdapter]実装の選択
 * （`promptengine.execution.*`、M2-1c、ADR-0030決定4）。
 *
 * APAP不在の間の暫定措置（ADR-0029・ADR-0030）。[provider]の値でFake/実プロバイダを
 * 切り替える。既定は`fake`——設定を変えない限りM1からの挙動を変えない
 * （`FakeExecutionAdapter`自身が持つ`production`プロファイルガードは本設定の値に関わらず
 * 常に有効なままであり、`provider=fake`のまま本番へデプロイすれば従来通り起動が失敗する）。
 *
 * `provider`が既知の値かどうか・未知の値をどう扱うかの判断、実際のアダプタ実装への
 * ディスパッチは`ExecutionConfig`（Composition Root）の責務とする。本クラスは
 * 具体的なプロバイダ名を知らない、純粋な設定値の保持・基本検証のみを行う
 * （ArchUnit/`ProviderNameContainmentTest`が守る境界の外に出ないため）。
 */
@ConfigurationProperties(prefix = "promptengine.execution")
data class ExecutionProviderProperties(
    val provider: String = DEFAULT_PROVIDER,
    /** `provider`が実プロバイダの場合にアダプタへ渡すモデル名。 */
    val modelName: String = DEFAULT_MODEL_NAME,
) {
    init {
        require(provider.isNotBlank()) { "promptengine.execution.provider must not be blank" }
        require(modelName.isNotBlank()) { "promptengine.execution.model-name must not be blank" }
    }

    companion object {
        private const val DEFAULT_PROVIDER = "fake"
        private const val DEFAULT_MODEL_NAME = "gpt-4o-mini"
    }
}
