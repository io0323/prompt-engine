package promptengine.interfaces.rest

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import promptengine.application.command.SetAliasHandler
import promptengine.application.view.DomainValueFactory
import promptengine.application.view.PromptCommandFactory
import promptengine.application.view.toView
import promptengine.interfaces.dto.SetAliasRequestDto
import promptengine.interfaces.dto.SetAliasResponseDto

/** `POST /prompts/{namespace}/{name}/aliases`（設計書§13.1、エイリアス設定。[PromptController]のKDoc参照）。 */
@RestController
@RequestMapping("/api/v1/prompts/{namespace}/{name}/aliases")
class AliasController(private val setAliasHandler: SetAliasHandler) {
    @PostMapping
    @PreAuthorize("hasAuthority('prompt:publish')")
    fun setAlias(
        @PathVariable namespace: String,
        @PathVariable name: String,
        @Valid @RequestBody body: SetAliasRequestDto,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<SetAliasResponseDto> {
        val key = DomainValueFactory.promptKeyText(namespace, name)
        val command = PromptCommandFactory.setAliasCommand(key, body.alias, body.version, idempotencyKey)
        val view = setAliasHandler.handle(command).toView()
        return ResponseEntity.status(HttpStatus.CREATED).body(SetAliasResponseDto(view.key, view.alias, view.semVer))
    }
}
