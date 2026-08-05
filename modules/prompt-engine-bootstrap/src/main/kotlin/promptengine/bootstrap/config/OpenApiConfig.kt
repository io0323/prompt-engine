package promptengine.bootstrap.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** springdoc-openapiの設定（設計書§13、P9c）。認可（CIAP発行Bearerトークン）をSecuritySchemeとして宣言する。 */
@Configuration
class OpenApiConfig {
    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .info(Info().title("Prompt Engine API").version("v1").description("設計書§13 API設計（REST）"))
            .components(
                Components()
                    .addSecuritySchemes(
                        BEARER_SCHEME,
                        SecurityScheme()
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT"),
                    ),
            )
            .addSecurityItem(SecurityRequirement().addList(BEARER_SCHEME))

    private companion object {
        const val BEARER_SCHEME = "ciap-bearer"
    }
}
