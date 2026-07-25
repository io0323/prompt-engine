package promptengine.bootstrap

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class PromptEngineApplication

fun main(args: Array<String>) {
    runApplication<PromptEngineApplication>(*args)
}
