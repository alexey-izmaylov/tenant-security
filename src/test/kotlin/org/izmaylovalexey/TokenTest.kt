package org.izmaylovalexey

import com.auth0.jwt.JWT
import com.epages.restdocs.apispec.WebTestClientRestDocumentationWrapper.document
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toSet
import kotlinx.coroutines.runBlocking
import mu.KLogging
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.awaitility.Awaitility
import org.awaitility.core.ConditionTimeoutException
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.not
import org.izmaylovalexey.entities.SecurityContext
import org.izmaylovalexey.entities.Tenant
import org.izmaylovalexey.entities.User
import org.izmaylovalexey.handler.ContextHandler
import org.izmaylovalexey.services.Success
import org.izmaylovalexey.services.TenantService
import org.izmaylovalexey.services.UserService
import org.izmaylovalexey.services.keycloak.InitialUserProperties
import org.izmaylovalexey.services.keycloak.KeycloakProperties
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.keycloak.admin.client.Keycloak
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.restdocs.RestDocumentationContextProvider
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders
import org.springframework.restdocs.operation.preprocess.Preprocessors
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.payload.PayloadDocumentation.subsectionWithPath
import org.springframework.restdocs.webtestclient.WebTestClientRestDocumentation.documentationConfiguration
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestConstructor
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.body
import org.springframework.test.web.reactive.server.expectBody
import reactor.core.publisher.Hooks
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@AutoConfigureRestDocs
@ContextConfiguration(initializers = [ApplicationTest.PropertyOverrideContextInitializer::class])
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TokenTest(
    keycloakProperties: KeycloakProperties,
    private val userService: UserService,
    private val tenantService: TenantService,
    private val tenantSecurityConfig: TenantSecurityConfig,
    private val keycloak: Keycloak,
    private val initialUserProperties: InitialUserProperties,
    restDocumentation: RestDocumentationContextProvider
) {

    private companion object : KLogging()

    init {
        Hooks.onOperatorDebug()
    }

    private val uri = keycloakProperties.uri
    private val realm = keycloakProperties.realm
    private val clientId = keycloakProperties.client
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(30))
        .build()
    private val mapper = ObjectMapper().registerKotlinModule()
    private val contextClient = WebTestClient
        .bindToRouterFunction(
            contextRoute(
                ContextHandler(
                    userService,
                    tenantService,
                    tenantSecurityConfig
                )
            )
        )
        .configureClient()
        .responseTimeout(Duration.ofMinutes(2))
        .filter(
            documentationConfiguration(restDocumentation)
                .operationPreprocessors()
                .withResponseDefaults(Preprocessors.prettyPrint())
                .withRequestDefaults(Preprocessors.prettyPrint())
        )
        .build()

    @BeforeAll
    fun startApp() {
        try {
            Awaitility.await()
                .pollInSameThread()
                .pollInterval(Duration.ofMillis(100))
                .timeout(Duration.ofSeconds(20))
                .ignoreExceptions()
                .until {
                    logger.info { "Wait for creating initial user" }
                    keycloak.realm(realm)
                        .roles()
                        .get(initialUserProperties.role)
                        .getRoleUserMembers(0, 1)
                        .size >= 1
                }
        } catch (e: ConditionTimeoutException) {
            logger.error { "Waiting time was finished" }
        }
    }

    @Test
    fun `get-password-token`() {
        val user = User(
            email = "${UUID.randomUUID()}@gmail.com",
            firstName = "Alexey",
            lastName = "Izmaylov",
            credential = UUID.randomUUID().toString()
        )
        runBlocking { userService.create(user) }
        getToken(user.email, user.credential)
    }

    @Test
    fun `get-password-token-for-initial-user`() {
        getToken(initialUserProperties.email, initialUserProperties.password)
    }

    @Test
    fun `roles in token`() {
        val email = "${UUID.randomUUID()}@gmail.com"
        val credential = UUID.randomUUID().toString()

        val tokenRoles = runBlocking {
            val user = userService.create(
                User(
                    email = email,
                    firstName = "Alexey",
                    lastName = "Izmaylov",
                    credential = credential
                )
            ).unwrap()
            val tenant = tenantService.create(Tenant(UUID.randomUUID().toString())).unwrap()
            listOf("developer", "maintainer")
                .onEach { userService.assign(user.id, tenant.name, it) }
                .map { "${tenant.name}.$it" }
        }
        assertThat(
            JWT.decode(getToken(email, credential)).claims["roles"]?.asList(String::class.java).orEmpty(),
            hasItems(*tokenRoles.toTypedArray())
        )
    }

    @Test
    fun `get-context-by-token`() {
        val email = "${UUID.randomUUID()}@gmail.com"
        val credential = UUID.randomUUID().toString()

        val securityContext = runBlocking {
            val user = userService.create(
                User(
                    email = email,
                    firstName = "Alexey",
                    lastName = "Izmaylov",
                    credential = credential
                )
            ).unwrap()
            val tenants = (1..3).asFlow()
                .map {
                    tenantService.create(
                        Tenant(
                            name = UUID.randomUUID().toString(),
                            displayedName = "tenant $it",
                            description = it.toString()
                        )
                    )
                }
                .filterIsInstance<Success<Tenant>>()
                .map { it.value }
                .onEach { userService.assign(user.id, it.name, "developer") }
                .toSet()
            SecurityContext(user, tenants)
        }
        assertEquals(3, securityContext.tenants.size)

        val token = getToken(email, credential)
        contextClient
            .get()
            .uri("/context")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isOk
            .expectBody<SecurityContext>().isEqualTo(securityContext)
            .consumeWith {
                document<SecurityContext>(
                    "context/{methodName}",
                    snippets = arrayOf(
                        requestHeaders(headerWithName(HttpHeaders.AUTHORIZATION).description("JSON Web Token")),
                        responseFields(
                            subsectionWithPath("user").description("User entity"),
                            subsectionWithPath("tenants").description("Available tenants")
                        )
                    )
                ).accept(it)
            }
    }

    @Test
    fun `get-context-without-header`() {
        contextClient
            .get()
            .uri("/context")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `create-tenant-and-assign-to-current-user`() {
        val email = "${UUID.randomUUID()}@gmail.com"
        val credential = UUID.randomUUID().toString()
        val user = runBlocking {
            userService.create(
                User(
                    email = email,
                    firstName = "Alexey",
                    lastName = "Izmaylov",
                    credential = credential
                )
            ).unwrap()
        }
        val token = getToken(email, credential)

        val tenant = contextClient
            .post()
            .uri("/context/tenant")
            .header("Authorization", "Bearer $token")
            .body(
                Mono.just(
                    Tenant(
                        name = UUID.randomUUID().toString(),
                        displayedName = "Galaxy",
                        description = "Resource group description"
                    )
                )
            )
            .exchange()
            .expectStatus().isOk
            .expectBody<Tenant>()
            .consumeWith {
                document<Tenant>(
                    "context/{methodName}",
                    snippets = arrayOf(
                        requestHeaders(headerWithName(HttpHeaders.AUTHORIZATION).description("JSON Web Token")),
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
            }.returnResult()
            .responseBody
        assertNotNull(tenant)
        assertThat(
            runBlocking { userService.getAssignments(user.id).unwrap().map { it.tenant }.toSet() },
            hasItem(tenant.name)
        )

        val tokenRole = "${tenant.name}.${tenantSecurityConfig.defaultRole}"
        assertThat(
            JWT.decode(token).claims["roles"]?.asList(String::class.java).orEmpty(),
            not(hasItem(tokenRole))
        )
        assertThat(
            JWT.decode(getToken(email, credential)).claims["roles"]?.asList(String::class.java).orEmpty(),
            hasItem(tokenRole)
        )
    }

    private fun getToken(username: String, password: String): String {
        val request = Request.Builder()
            .url("$uri/realms/$realm/protocol/openid-connect/token")
            .post(
                FormBody.Builder()
                    .add("grant_type", "password")
                    .add("client_id", clientId)
                    .add("username", username)
                    .add("password", password)
                    .build()
            ).build()
        httpClient.newCall(request).execute().use { response ->
            assertEquals(200, response.code())
            return mapper.readTree(response.body()?.string())
                .get("access_token")
                .textValue()
        }
    }
}
