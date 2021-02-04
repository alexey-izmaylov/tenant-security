package org.izmaylovalexey

import com.epages.restdocs.apispec.WebTestClientRestDocumentationWrapper.document
import me.snowdrop.istio.client.IstioClient
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.not
import org.izmaylovalexey.entities.Tenant
import org.izmaylovalexey.handler.TenantHandler
import org.izmaylovalexey.services.TenantService
import org.izmaylovalexey.services.keycloak.KeycloakProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.keycloak.admin.client.Keycloak
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.restdocs.RestDocumentationContextProvider
import org.springframework.restdocs.operation.preprocess.Preprocessors
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.restdocs.webtestclient.WebTestClientRestDocumentation.documentationConfiguration
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestConstructor
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.body
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.returnResult
import reactor.core.publisher.Hooks
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

@AutoConfigureRestDocs
@ContextConfiguration(initializers = [ApplicationTest.PropertyOverrideContextInitializer::class])
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantTest(
    tenantService: TenantService,
    restDocumentation: RestDocumentationContextProvider,
    private val keycloak: Keycloak,
    private val keycloakProperties: KeycloakProperties,
    private val istio: IstioClient
) {

    init {
        Hooks.onOperatorDebug()
    }

    private val client = WebTestClient
        .bindToRouterFunction(tenantRoute(TenantHandler(tenantService)))
        .configureClient()
        .filter(
            documentationConfiguration(restDocumentation)
                .operationPreprocessors()
                .withResponseDefaults(Preprocessors.prettyPrint())
                .withRequestDefaults(Preprocessors.prettyPrint())
        )
        .build()

    @Test
    fun `post-tenant`() {
        val tenant = Tenant(
            name = "tenant-name",
            displayedName = "My Project",
            description = "top secret"
        )
        client
            .post()
            .uri("/tenant")
            .body(Mono.just(tenant))
            .exchange()
            .expectStatus().isOk
            .expectBody<Tenant>()
            .consumeWith {
                document<Tenant>(
                    "tenants/{methodName}",
                    snippets = arrayOf(
                        requestFields(
                            fieldWithPath("name").description("Name of tenant").optional(),
                            fieldWithPath("displayedName").description("Displayed name of tenant").optional(),
                            fieldWithPath("description").description("Description").optional()
                        ),
                        responseFields(
                            fieldWithPath("name").description("Name of tenant"),
                            fieldWithPath("displayedName").description("Displayed name of tenant"),
                            fieldWithPath("description").description("Description")
                        )
                    )
                ).accept(it)
            }
    }

    @Test
    fun `get-all-tenants`() {
        val expectedTenants = ArrayList<Tenant>()
        for (i in 1..3) expectedTenants.add(post())

        val actualTenants = client
            .get()
            .uri("/tenant")
            .exchange()
            .expectStatus().isOk
            .returnResult<Tenant>()
            .responseBody
            .switchIfEmpty { fail("Empty stream") }
            .collectList()
            .blockOptional(Duration.ofMinutes(1))
            .orElseGet { emptyList() }
        assertThat(actualTenants, hasItems(*expectedTenants.toTypedArray()))

        client
            .get()
            .uri("/tenant")
            .exchange()
            .expectStatus().isOk
            .expectBody<List<Tenant>>()
            .consumeWith {
                document<List<Tenant>>(
                    "tenants/{methodName}",
                    snippets = arrayOf(
                        responseFields(
                            fieldWithPath("[]").description("Tenant list"),
                            fieldWithPath("[].name").description("Name of tenant"),
                            fieldWithPath("[].displayedName").description("Displayed name of tenant"),
                            fieldWithPath("[].description").description("Description")
                        )
                    )
                ).accept(it)
            }
    }

    @Test
    fun `get-tenant`() {
        val tenant = post()
        client
            .get()
            .uri("/tenant/{name}", tenant.name)
            .exchange()
            .expectStatus().isOk
            .expectBody<Tenant>().isEqualTo(tenant)
            .consumeWith {
                document<Tenant>(
                    "tenants/{methodName}",
                    snippets = arrayOf(
                        pathParameters(
                            parameterWithName("name").description("Name of the tenant to be read")
                        ),
                        responseFields(
                            fieldWithPath("name").description("Name of tenant"),
                            fieldWithPath("displayedName").description("Displayed name of tenant"),
                            fieldWithPath("description").description("Description")
                        )
                    )
                ).accept(it)
            }
    }

    @Test
    fun `tenant not found`() {
        client
            .get()
            .uri("/tenant/${UUID.randomUUID()}")
            .exchange()
            .expectStatus().isNotFound
            .expectBody().isEmpty
    }

    @Test
    fun `delete-tenant`() {
        client
            .delete()
            .uri("/tenant/{name}", UUID.randomUUID().toString())
            .exchange()
            .expectStatus().isNoContent
            .expectBody()
            .consumeWith {
                document<ByteArray>(
                    "tenants/{methodName}",
                    snippets = arrayOf(
                        pathParameters(
                            parameterWithName("name").description("Name of the tenant to be deleted")
                        )
                    )
                ).accept(it)
            }
            .isEmpty
    }

    @Test
    fun `group and role consistency`() {
        val tenant = post()
        assertEquals(
            3,
            istio.serviceRole()
                .list()
                .items
                .map { it.metadata.name.substringBefore(".") }
                .filter { it == tenant.name }
                .count()
        )
        assertEquals(
            3,
            istio.serviceRoleBinding()
                .list()
                .items
                .map { it.metadata.name.substringBefore(".") }
                .filter { it == tenant.name }
                .count()
        )
        client
            .get()
            .uri("/tenant/${tenant.name}")
            .exchange()
            .expectStatus().isOk
            .expectBody<Tenant>().isEqualTo(tenant)
        assertThat(keycloak.realm(keycloakProperties.realm).groups().groups().map { it.id }, hasItem(tenant.name))
        assertThat(
            keycloak.realm(keycloakProperties.realm).roles().list().map { it.name.substringBefore(".") },
            hasItem(tenant.name)
        )

        delete(tenant)
        client
            .get()
            .uri("/tenant/${tenant.name}")
            .exchange()
            .expectStatus().isNotFound
        assertThat(keycloak.realm(keycloakProperties.realm).groups().groups().map { it.id }, not(hasItem(tenant.name)))
        assertThat(
            keycloak.realm(keycloakProperties.realm).roles().list().map { it.name.substringBefore(".") },
            not(hasItem(tenant.name))
        )
        assertEquals(
            0,
            istio.serviceRole()
                .list()
                .items
                .map { it.metadata.name.substringBefore(".") }
                .filter { it == tenant.name }
                .count()
        )
        assertEquals(
            0,
            istio.serviceRoleBinding()
                .list()
                .items
                .map { it.metadata.name.substringBefore(".") }
                .filter { it == tenant.name }
                .count()
        )
    }

    @Test
    fun `update-tenant`() {
        val tenant = post()
        val updatedTenant = Tenant(
            name = tenant.name,
            displayedName = "Updated Tenant",
            description = tenant.description
        )
        val expectedTenant = Tenant(tenant.name, updatedTenant.displayedName, updatedTenant.description)
        client
            .patch()
            .uri("/tenant/{name}", tenant.name)
            .body(Mono.just(updatedTenant))
            .exchange()
            .expectStatus().isOk
            .expectBody<Tenant>().isEqualTo(expectedTenant)
            .consumeWith {
                document<Tenant>(
                    "tenants/{methodName}",
                    snippets = arrayOf(
                        pathParameters(
                            parameterWithName("name").description("Name of the tenant to be updated")
                        ),
                        requestFields(
                            fieldWithPath("name").description("Name of tenant (is ignored)").optional(),
                            fieldWithPath("displayedName").description("Displayed name of tenant").optional(),
                            fieldWithPath("description").description("Description").optional()
                        ),
                        responseFields(
                            fieldWithPath("name").description("Name of tenant"),
                            fieldWithPath("displayedName").description("Displayed name of tenant"),
                            fieldWithPath("description").description("Description")
                        )
                    )
                ).accept(it)
            }
        client
            .get()
            .uri("/tenant/{name}", expectedTenant.name)
            .exchange()
            .expectStatus().isOk
            .expectBody<Tenant>().isEqualTo(expectedTenant)
    }

    @Test
    fun badPost() {
        client
            .post()
            .uri("/tenant")
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(Mono.just(" "))
            .exchange()
            .expectStatus().isBadRequest
            .expectBody().isEmpty
    }

    @Test
    fun emptyPost() {
        client
            .post()
            .uri("/tenant")
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .exchange()
            .expectStatus().isBadRequest
            .expectBody().isEmpty
    }

    @Test
    fun badPatch() {
        client
            .patch()
            .uri("/tenant/1")
            .body(Mono.just(""))
            .exchange()
            .expectStatus().isBadRequest
            .expectBody().isEmpty
    }

    @Test
    fun emptyPatch() {
        client
            .patch()
            .uri("/tenant/1")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody().isEmpty
    }

    private fun post(): Tenant {
        val tenant = Tenant(
            name = UUID.randomUUID().toString(),
            displayedName = "My Project",
            description = "top secret"
        )
        val actual = client
            .post()
            .uri("/tenant")
            .body(Mono.just(tenant))
            .exchange()
            .expectStatus().isOk
            .expectBody<Tenant>()
            .returnResult().responseBody
        assertNotNull(actual)
        val expected = Tenant(
            name = actual.name,
            displayedName = tenant.displayedName,
            description = tenant.description
        )
        assertEquals(expected, actual)
        return actual
    }

    private fun delete(tenant: Tenant) {
        client
            .delete()
            .uri("/tenant/${tenant.name}")
            .exchange()
            .expectStatus().isNoContent
            .expectBody().isEmpty
    }
}
