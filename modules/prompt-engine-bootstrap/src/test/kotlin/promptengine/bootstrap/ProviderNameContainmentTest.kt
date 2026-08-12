package promptengine.bootstrap

import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 特定プロバイダ名の直接記述禁止（CLAUDE.md「特定のAIプロバイダ名・モデル名をコードに直接書かない」）を
 * `plugins/execution-openai`（M2-1a、ADR-0029）について機械的に検証する。
 *
 * `promptengine.plugin.execution.openai`パッケージ（ADR-0003命名規則）はプロバイダ固有の知識
 * （HTTP形状・認証ヘッダ・JSONスキーマ）を封じ込める境界そのものであり、実APAP接続（#31）が
 * 実現した際に`git rm -r plugins/execution-openai`一発で削除できることが暫定実装としての価値
 * （[OpenAiExecutionAdapter][promptengine.plugin.execution.openai.OpenAiExecutionAdapter]のKDoc、
 * ADR-0029決定1参照）。この境界がコピペ等で漏れていないことを、リポジトリ全体の`.kt`ソースを
 * 走査して確認する。
 *
 * 本テスト自身は判定対象の文字列を保持する必要があるため、自身のソースファイルを走査対象から除外する。
 */
class ProviderNameContainmentTest {
    @Test
    fun `プロバイダ名の文字列は execution-openai の外の kt ソースに現れない`() {
        val repoRoot = findRepoRoot()
        val needle = PROVIDER_NAME.lowercase()

        val offenders =
            repoRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { isExcluded(it, repoRoot) }
                .filter { it.readText().lowercase().contains(needle) }
                .map { it.relativeTo(repoRoot).invariantSeparatorsPath }
                .toList()

        offenders.shouldBeEmpty()
    }

    private fun isExcluded(
        file: File,
        repoRoot: File,
    ): Boolean {
        val relative = file.relativeTo(repoRoot).invariantSeparatorsPath
        return relative.startsWith(ALLOWED_PACKAGE_DIR) ||
            relative == SELF_RELATIVE_PATH ||
            relative.contains("/build/") ||
            relative.contains("/bin/")
    }

    /**
     * `settings.gradle.kts`を上方探索してリポジトリルートを特定する
     * （`ArchitectureTest.findPluginSubprojectDirs`と同じ手法）。
     */
    private fun findRepoRoot(): File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").exists() }
            ?: error("settings.gradle.kts が見つからずリポジトリルートを特定できません")

    companion object {
        // このテスト自身の判定対象文字列。分割せず保持する（分割すると走査時に検出できなくなり
        // ガードが無意味になるため。自身は SELF_RELATIVE_PATH による経路除外で回避する）。
        private const val PROVIDER_NAME = "OpenAI"
        private const val ALLOWED_PACKAGE_DIR = "plugins/execution-openai/"
        private const val SELF_RELATIVE_PATH =
            "modules/prompt-engine-bootstrap/src/test/kotlin/promptengine/bootstrap/ProviderNameContainmentTest.kt"
    }
}
