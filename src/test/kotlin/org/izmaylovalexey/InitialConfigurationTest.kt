package org.izmaylovalexey

import mu.KLogging
import org.awaitility.Awaitility
import org.awaitility.core.ConditionTimeoutException
import org.izmaylovalexey.services.keycloak.InitialUserProperties
import org.izmaylovalexey.services.keycloak.KeycloakProperties
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.keycloak.admin.client.Keycloak
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestConstructor
import org.testcontainers.junit.jupiter.Testcontainers
import reactor.core.publisher.Hooks
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@AutoConfigureRestDocs
@Testcontainers
@ContextConfiguration(initializers = [ApplicationTest.PropertyOverrideContextInitializer::class])
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InitialConfigurationTest(
    private val keycloak: Keycloak,
    private val keycloakProperties: KeycloakProperties,
    private val initialUserProperties: InitialUserProperties
) {

    init {
        Hooks.onOperatorDebug()
    }

    companion object : KLogging()

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
                    keycloak.realm(keycloakProperties.realm)
                        .roles()
                        .get(initialUserProperties.role)
                        .getRoleUserMembers(0, 1)
                        .size >= 1
                }
        } catch (e: ConditionTimeoutException) {
            logger.warn { "Waiting time was finished" }
        }
    }

    @Test
    fun `check initial user`() {
        val initialUser = keycloak.realm(keycloakProperties.realm)
            .users()
            .search(initialUserProperties.email).first { it.email == initialUserProperties.email }
        assertNotNull(initialUser)
        assertEquals(initialUser.email, initialUserProperties.email)
        val roles = keycloak.realm(keycloakProperties.realm)
            .users()
            .get(initialUser.id)
            .roles()
            .realmLevel()
            .listAll()
            .filter { it.name == initialUserProperties.role }
        assertEquals(1, roles.size)
        val role = roles.first()
        assertNotNull(role)
    }
}
