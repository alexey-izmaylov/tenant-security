package org.izmaylovalexey

import com.epages.restdocs.apispec.WebTestClientRestDocumentationWrapper.document
import kotlinx.coroutines.runBlocking
import mu.KLogging
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.izmaylovalexey.entities.Assignment
import org.izmaylovalexey.entities.Tenant
import org.izmaylovalexey.entities.User
import org.izmaylovalexey.handler.UserHandler
import org.izmaylovalexey.services.TenantService
import org.izmaylovalexey.services.UserService
import org.izmaylovalexey.services.keycloak.KeycloakProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.keycloak.admin.client.Keycloak
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.restdocs.RestDocumentationContextProvider
import org.springframework.restdocs.operation.preprocess.Preprocessors
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.payload.PayloadDocumentation.subsectionWithPath
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.restdocs.request.RequestDocumentation.requestParameters
import org.springframework.restdocs.webtestclient.WebTestClientRestDocumentation.documentationConfiguration
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestConstructor
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.body
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.expectBodyList
import org.springframework.test.web.reactive.server.returnResult
import reactor.core.publisher.Hooks
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.HashSet
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@AutoConfigureRestDocs
@ContextConfiguration(initializers = [ApplicationTest.PropertyOverrideContextInitializer::class])
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserTest(
    userService: UserService,
    restDocumentation: RestDocumentationContextProvider,
    private val tenantService: TenantService,
    private val keycloak: Keycloak,
    private val keycloakProperties: KeycloakProperties
) {

    init {
        Hooks.onOperatorDebug()
    }

    private val client = WebTestClient
        .bindToRouterFunction(userRoute(UserHandler(userService)))
        .configureClient()
        .responseTimeout(Duration.ofMinutes(2))
        .filter(
            documentationConfiguration(restDocumentation)
                .operationPreprocessors()
                .withResponseDefaults(Preprocessors.prettyPrint())
                .withRequestDefaults(Preprocessors.prettyPrint())
        )
        .build()

    private companion object : KLogging()

    @Test
    fun `post-user`() {
        val user = User(
            email = "jedi@mail",
            firstName = "Obi-Wan",
            lastName = "Kenobi",
            credential = "TheForce"
        )
        client
            .post()
            .uri("/user")
            .body(Mono.just(user))
            .exchange()
            .expectStatus().isOk
            .expectBody<User>()
            .consumeWith {
                document<User>(
                    "users/{methodName}",
                    snippets = arrayOf(
                        requestFields(
                            fieldWithPath("id").description("Optional id of user").optional(),
                            fieldWithPath("email").description("Email of user"),
                            fieldWithPath("firstName").description("First name of user"),
                            fieldWithPath("lastName").description("Last name of user"),
                            fieldWithPath("credential").description("Password or key of user")
                        ),
                        responseFields(
                            fieldWithPath("id").description("Id of user"),
                            fieldWithPath("email").description("Email of user"),
                            fieldWithPath("firstName").description("First name of user"),
                            fieldWithPath("lastName").description("Last name of user"),
                            fieldWithPath("credential").description("Masked password or key of user")
                        )
                    )
                ).accept(it)
            }
    }

    @Test
    fun `post user with occupied email`() {
        val user = postUser()

        val user2 = User(
            id = user.id,
            email = user.email,
            firstName = user.firstName,
            lastName = user.lastName,
            credential = "12345"
        )

        client
            .post()
            .uri("/user")
            .body(Mono.just(user2))
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)
            .expectBody<String>().isEqualTo("This email is used.")
    }

    @Test
    fun `get-user`() {
        val user = postUser()
        assertThat(getAllUserNames(), hasItem(user.email))

        client
            .get()
            .uri("/user/{id}", user.id)
            .exchange()
            .expectStatus().isOk
            .expectBody<User>().isEqualTo(user)
            .consumeWith {
                document<User>(
                    "users/{methodName}",
                    snippets = arrayOf(
                        pathParameters(
                            parameterWithName("id").description("Id of the user to be read")
                        ),
                        responseFields(
                            fieldWithPath("id").description("Id of user"),
                            fieldWithPath("email").description("Email of user"),
                            fieldWithPath("firstName").description("First name of user"),
                            fieldWithPath("lastName").description("Last name of user"),
                            fieldWithPath("credential").description("Masked password or key of user")
                        )
                    )
                ).accept(it)
            }
    }

    @Test
    fun `delete-user`() {
        val user = postUser()
        client
            .delete()
            .uri("/user/{id}", user.id)
            .exchange()
            .expectStatus().isNoContent
            .expectBody()
            .consumeWith {
                document<ByteArray>(
                    "users/{methodName}",
                    snippets = arrayOf(
                        pathParameters(
                            parameterWithName("id").description("Id of the user to be deleted")
                        )
                    )
                ).accept(it)
            }
            .isEmpty
        assertThat(getAllUserNames(), not(hasItem(user.email)))
    }

    @Test
    fun `assign-tenant-to-user`() {
        val tenant = newTenant()
        val user = postUser()

        client
            .put()
            .uri("/user/{id}/tenant/{tenant-name}/{role}", user.id, tenant, "maintainer")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .consumeWith {
                document<ByteArray>(
                    "users/{methodName}",
                    snippets = arrayOf(
                        pathParameters(
                            parameterWithName("id").description("Id of user"),
                            parameterWithName("tenant-name").description("Name of tenant"),
                            parameterWithName("role").description("Role of tenant")
                        )
                    )
                ).accept(it)
            }
            .isEmpty

        val roles = keycloak.realm(keycloakProperties.realm)
            .users()
            .get(user.id)
            .roles()
            .realmLevel()
            .listEffective()
            .map { it.name.substringBefore(".", "") }
            .filter { it.isNotEmpty() }
        assertThat(roles, containsInAnyOrder(tenant))
    }

    @Test
    fun `assign several roles`() {
        val tenant = newTenant()
        val user = postUser()
        client
            .put()
            .uri("/user/{id}/tenant/{tenant-name}/{role}", user.id, tenant, "developer")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .isEmpty
        client
            .put()
            .uri("/user/{id}/tenant/{tenant-name}/{role}", user.id, tenant, "maintainer")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .isEmpty

        assertEquals(
            1,
            client
                .get()
                .uri("/user/{id}/tenant", user.id)
                .exchange()
                .expectStatus().isOk
                .returnResult<Assignment>()
                .responseBody
                .log()
                .count()
                .blockOptional()
                .orElse(-1)
        )

        val roles = keycloak.realm(keycloakProperties.realm)
            .users()
            .get(user.id)
            .roles()
            .realmLevel()
            .listEffective()
            .map { it.name }
            .filter { it.contains(".") }
        assertThat(roles, containsInAnyOrder("$tenant.developer", "$tenant.maintainer"))
    }

    @Test
    fun `get-tenant-assignments`() {
        val tenant = newTenant()

        val users = mutableSetOf<User>()
        for (i in 1..5) users.add(postUser())

        val expectedAssignments = users
            .take(3)
            .map { assign(it, tenant, "developer") }

        val actualAssignments = client
            .get()
            .uri("/user?tenant=$tenant")
            .exchange()
            .expectStatus().isOk
            .returnResult<Assignment>()
            .responseBody
            .timeout(Duration.ofMinutes(1))
            .log()
            .collectList()
            .blockOptional(Duration.ofMinutes(1))
            .orElse(emptyList())
        assertThat(actualAssignments, containsInAnyOrder(*expectedAssignments.toTypedArray()))

        client
            .get()
            .uri("/user?tenant=$tenant")
            .exchange()
            .expectStatus().isOk
            .expectBody<List<Assignment>>()
            .consumeWith {
                document<List<Assignment>>(
                    "users/{methodName}",
                    snippets = arrayOf(
                        requestParameters(
                            parameterWithName("tenant").description("Name of the tenant")
                        ),
                        responseFields(
                            fieldWithPath("[]").description("Tenant list"),
                            fieldWithPath("[].tenant").description("Name of tenant"),
                            subsectionWithPath("[].user").description("User object"),
                            fieldWithPath("[].roles").description("Tenant roles")
                        )
                    )
                ).accept(it)
            }
    }

    @Test
    fun `get users of several tenants`() {
        for (i in 1..20) postUser()

        val tenantA = newTenant()
        val expectedAssignmentsA = HashSet<Assignment>()
        for (i in 1..5) expectedAssignmentsA.add(assign(postUser(), tenantA, "developer"))
        assertEquals(5, expectedAssignmentsA.size)

        val actualAssignmentsA = client
            .get()
            .uri("/user?tenant=$tenantA")
            .exchange()
            .expectStatus().isOk
            .returnResult<Assignment>()
            .responseBody
            .log()
            .timeout(Duration.ofMinutes(1))
            .collectList()
            .blockOptional(Duration.ofMinutes(1))
            .orElse(emptyList())
        assertThat(actualAssignmentsA, containsInAnyOrder(*expectedAssignmentsA.toTypedArray()))

        val tenantB = newTenant()
        val expectedAssignmentsB = HashSet<Assignment>()
        for (i in 1..5) expectedAssignmentsB.add(assign(postUser(), tenantB, "developer"))
        expectedAssignmentsB.add(assign(expectedAssignmentsA.random().user, tenantB, "maintainer"))
        assertEquals(6, expectedAssignmentsB.size)

        val actualAssignmentsB = client
            .get()
            .uri("/user?tenant=$tenantB")
            .exchange()
            .expectStatus().isOk
            .returnResult<Assignment>()
            .responseBody
            .log()
            .timeout(Duration.ofMinutes(1))
            .collectList()
            .blockOptional(Duration.ofMinutes(1))
            .orElse(emptyList())
        assertThat(actualAssignmentsB, containsInAnyOrder(*expectedAssignmentsB.toTypedArray()))
    }

    @Test
    fun `evict-user-from-tenant`() {
        val tenant = newTenant()
        val user = postUser()
        assign(user, tenant, "maintainer")

        client
            .delete()
            .uri("/user/{id}/tenant/{tenant-name}/{role}", user.id, tenant, "maintainer")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .consumeWith {
                document<ByteArray>(
                    "users/{methodName}",
                    snippets = arrayOf(
                        pathParameters(
                            parameterWithName("id").description("Id of the user to be evicted"),
                            parameterWithName("tenant-name").description("Name of tenant"),
                            parameterWithName("role").description("Role of tenant")
                        )
                    )
                ).accept(it)
            }
            .isEmpty

        assertEquals(
            0,
            client
                .get()
                .uri("/user?tenant=$tenant")
                .exchange()
                .expectStatus().isOk
                .returnResult<Assignment>()
                .responseBody
                .timeout(Duration.ofMinutes(1))
                .log()
                .count()
                .blockOptional(Duration.ofMinutes(1))
                .orElse(-1)
        )
    }

    @Test
    fun `user not found`() {
        client
            .get()
            .uri("/user/${UUID.randomUUID()}")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `get users without tenant`() {
        client
            .get()
            .uri("/user")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody<String>().isEqualTo("Missing tenant parameter.")
    }

    @Test
    fun `search user by one param`() {
        val user1 = client.post()
            .uri("/user")
            .body(
                Mono.just(
                    User(
                        id = "1",
                        email = "${UUID.randomUUID()}@mail",
                        firstName = "Ner",
                        lastName = "Zhul",
                        credential = "plague"
                    )
                )
            )
            .exchange()
            .expectStatus().isOk
            .expectBody<User>()
            .returnResult()
            .responseBody
        assertNotNull(user1)
        val user2 = client.post()
            .uri("/user")
            .body(
                Mono.just(
                    User(
                        id = "2",
                        email = "${UUID.randomUUID()}@mail",
                        firstName = "Fon",
                        lastName = "Ner",
                        credential = "plague"
                    )
                )
            )
            .exchange()
            .expectStatus().isOk
            .expectBody<User>()
            .returnResult()
            .responseBody
        assertNotNull(user2)

        client.get()
            .uri("/user/search?searchingString=Ne")
            .exchange()
            .expectStatus().isOk
            .expectBodyList<User>()
            .hasSize(2)
            .contains(user1, user2)
            .consumeWith<WebTestClient.ListBodySpec<User>>(
                document<List<User>>(
                    "users/search-by-string",
                    snippets = arrayOf(
                        requestParameters(
                            parameterWithName("searchingString").description("Username of the user")
                        ),
                        responseFields(
                            fieldWithPath("[]").description("User list"),
                            fieldWithPath("[].id").description("Id of user"),
                            fieldWithPath("[].email").description("Email of user"),
                            fieldWithPath("[].firstName").description("First name of user"),
                            fieldWithPath("[].lastName").description("Last name of user"),
                            fieldWithPath("[].credential").description("Masked password or key of user")
                        )
                    )
                )
            )

        client.get()
            .uri("/user/search?searchingString=${user2.firstName}")
            .exchange()
            .expectStatus().isOk
            .expectBodyList<User>()
            .hasSize(1)
            .contains(user2)

        client.get()
            .uri("/user/search?searchingString=${user1.firstName}&email=${user1.email}")
            .exchange()
            .expectStatus().isBadRequest

        client.delete().uri("/user/${user1.id}").exchange().expectStatus().isNoContent
        client.delete().uri("/user/${user2.id}").exchange().expectStatus().isNoContent
    }

    @Test
    fun `search user by all params`() {
        val user1 = client.post()
            .uri("/user")
            .body(
                Mono.just(
                    User(
                        id = "1",
                        email = "${UUID.randomUUID()}@mail",
                        firstName = "Ner",
                        lastName = "Zhul",
                        credential = "plague"
                    )
                )
            )
            .exchange()
            .expectStatus().isOk
            .expectBody<User>()
            .returnResult()
            .responseBody
        assertNotNull(user1)
        val user2 = client.post()
            .uri("/user")
            .body(
                Mono.just(
                    User(
                        id = "2",
                        email = "${UUID.randomUUID()}@mail",
                        firstName = "Fon",
                        lastName = "Ner",
                        credential = "plague"
                    )
                )
            )
            .exchange()
            .expectStatus().isOk
            .expectBody<User>()
            .returnResult()
            .responseBody
        assertNotNull(user2)

        client.get()
            .uri("/user/search?userName=${user1.email}&firstName=Ne&lastName=${user1.lastName}&email=${user1.email}")
            .exchange()
            .expectStatus().isOk
            .expectBodyList<User>()
            .hasSize(1)
            .contains(user1)
            .consumeWith<WebTestClient.ListBodySpec<User>>(
                document<List<User>>(
                    "users/search-by-all",
                    snippets = arrayOf(
                        requestParameters(
                            parameterWithName("firstName").description("First name of the user"),
                            parameterWithName("lastName").description("Last name of the user"),
                            parameterWithName("email").description("Email of the user"),
                            parameterWithName("userName").description("Username of the user")
                        ),
                        responseFields(
                            fieldWithPath("[]").description("User list"),
                            fieldWithPath("[].id").description("Id of user"),
                            fieldWithPath("[].email").description("Email of user"),
                            fieldWithPath("[].firstName").description("First name of user"),
                            fieldWithPath("[].lastName").description("Last name of user"),
                            fieldWithPath("[].credential").description("Masked password or key of user")
                        )
                    )
                )
            )

        client.get()
            .uri("/user/search?lastName=${user2.lastName}")
            .exchange()
            .expectStatus().isOk
            .expectBodyList<User>()
            .hasSize(1)
            .contains(user2)

        client.get()
            .uri("/user/search")
            .exchange()
            .expectStatus().isOk
            .expectBodyList<User>()
            .hasSize(getAllUserNames().size)

        client.delete().uri("/user/${user1.id}").exchange().expectStatus().isNoContent
        client.delete().uri("/user/${user2.id}").exchange().expectStatus().isNoContent
    }

    @Test
    fun `get-user-assignments`() {
        val user = postUser()
        val tenant1 = newTenant()
        assign(user, tenant1, "developer")
        assign(user, tenant1, "maintainer")
        val assignment1 = Assignment(
            tenant = tenant1,
            user = user,
            roles = setOf("developer", "maintainer")
        )
        val assignment2 = assign(user, newTenant(), "developer")

        client
            .get()
            .uri("/user/{id}/tenant", user.id)
            .exchange()
            .expectStatus().isOk
            .expectBodyList<Assignment>()
            .contains(assignment1, assignment2)
            .consumeWith<WebTestClient.ListBodySpec<Assignment>>(
                document<List<Assignment>>(
                    "users/{methodName}",
                    snippets = arrayOf(
                        pathParameters(
                            parameterWithName("id").description("Id of the user")
                        ),
                        responseFields(
                            fieldWithPath("[]").description("Tenant list"),
                            fieldWithPath("[].tenant").description("Name of tenant"),
                            subsectionWithPath("[].user").description("User object"),
                            fieldWithPath("[].roles").description("Tenant roles")
                        )
                    )
                )
            )
    }

    @Test
    fun `assign absent`() {
        client
            .put()
            .uri("/user/{id}/tenant/{tenant-name}/{role}", postUser().id, newTenant(), UUID.randomUUID().toString())
            .exchange()
            .expectStatus().isNotFound
        client
            .put()
            .uri("/user/{id}/tenant/{tenant-name}/{role}", postUser().id, UUID.randomUUID().toString(), "maintainer")
            .exchange()
            .expectStatus().isNotFound
        client
            .put()
            .uri("/user/{id}/tenant/{tenant-name}/{role}", UUID.randomUUID().toString(), newTenant(), "maintainer")
            .exchange()
            .expectStatus().isNotFound
    }

    private fun postUser(): User {
        val user = User(
            id = "1",
            email = "${UUID.randomUUID()}@mail",
            firstName = randomUsername(),
            lastName = randomUsername(),
            credential = Random.nextLong().toString()
        )
        val actual = client
            .post()
            .uri("/user")
            .body(Mono.just(user))
            .exchange()
            .expectStatus().isOk
            .expectBody<User>()
            .returnResult()
            .responseBody
        assertNotNull(actual)
        val expected = User(
            id = actual.id,
            email = user.email,
            firstName = user.firstName,
            lastName = user.lastName,
            credential = "*****"
        )
        assertEquals(expected, actual)
        return actual
    }

    private fun newTenant(): String = runBlocking {
        tenantService.create(
            Tenant(
                name = UUID.randomUUID().toString(),
                displayedName = "resource-group"
            )
        ).unwrap().name
    }

    private fun assign(user: User, tenant: String, role: String): Assignment {
        client
            .put()
            .uri("/user/${user.id}/tenant/$tenant/$role")
            .exchange()
            .expectStatus().isOk
        return Assignment(tenant, user, setOf(role))
    }

    private fun getAllUserNames() = keycloak.realm(keycloakProperties.realm).users().list().map { it.username }

    private fun randomUsername() = ('A'..'Z').random() + List(9) { ('a'..'z').random() }.joinToString("")
}
