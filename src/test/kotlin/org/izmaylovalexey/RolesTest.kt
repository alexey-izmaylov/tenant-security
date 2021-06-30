package org.izmaylovalexey

import com.epages.restdocs.apispec.WebTestClientRestDocumentationWrapper.document
import org.izmaylovalexey.entities.RoleTemplate
import org.izmaylovalexey.handler.RoleTemplateHandler
import org.izmaylovalexey.services.RoleTemplateService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.restdocs.RestDocumentationContextProvider
import org.springframework.restdocs.operation.preprocess.Preprocessors
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.webtestclient.WebTestClientRestDocumentation
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestConstructor
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBodyList

@AutoConfigureRestDocs
@ContextConfiguration(initializers = [Integration.SpringInitializer::class])
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RolesTest(
    roleTemplateService: RoleTemplateService,
    restDocumentation: RestDocumentationContextProvider
) {

    private val client = WebTestClient
        .bindToRouterFunction(roleTemplateRoute(RoleTemplateHandler(roleTemplateService)))
        .configureClient()
        .filter(
            WebTestClientRestDocumentation.documentationConfiguration(restDocumentation)
                .operationPreprocessors()
                .withResponseDefaults(Preprocessors.prettyPrint())
                .withRequestDefaults(Preprocessors.prettyPrint())
        )
        .build()

    @Test
    fun listRoleTemplates() {
        client
            .get()
            .uri("/role")
            .exchange()
            .expectBodyList<RoleTemplate>()
            .contains(RoleTemplate("maintainer"), RoleTemplate("developer"), RoleTemplate("owner"))
            .hasSize(3)
            .consumeWith<WebTestClient.ListBodySpec<RoleTemplate>>(
                document<List<RoleTemplate>>(
                    "roles/list",
                    snippets = arrayOf(
                        responseFields(
                            fieldWithPath("[]").description("Role Template List"),
                            fieldWithPath("[].name").description("Name"),
                        )
                    )
                )
            )
    }
}
