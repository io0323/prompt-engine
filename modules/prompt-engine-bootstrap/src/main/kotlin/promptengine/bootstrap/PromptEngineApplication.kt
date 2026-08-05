package promptengine.bootstrap

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * `promptengine.interfaces`（Controller/`@RestControllerAdvice`/Servlet Filter）は
 * `promptengine.bootstrap`の兄弟パッケージであり、`@SpringBootApplication`の既定スキャン範囲
 * （自パッケージ配下のみ）には含まれない。`scanBasePackages`で明示しないと、P9cで追加した
 * Controller等が一切Beanとして登録されず、全エンドポイントが404になる（P9cで判明・対応）。
 */
@SpringBootApplication(scanBasePackages = ["promptengine.bootstrap", "promptengine.interfaces"])
class PromptEngineApplication

fun main(args: Array<String>) {
    runApplication<PromptEngineApplication>(*args)
}
