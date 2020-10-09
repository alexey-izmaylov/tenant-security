package org.izmaylovalexey.services.keycloak

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.asFlux
import mu.KLogging
import org.izmaylovalexey.entities.User
import org.izmaylovalexey.services.Error
import org.izmaylovalexey.services.Failure
import org.izmaylovalexey.services.Success
import org.izmaylovalexey.services.UserService
import org.keycloak.admin.client.Keycloak
import org.keycloak.representations.idm.RoleRepresentation
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
@EnableAsync
internal class AsyncConfiguration(
    private val keycloak: Keycloak,
    private val userService: UserService,
    private val initialUserProperties: InitialUserProperties,
    keycloakProperties: KeycloakProperties
) {

    private companion object : KLogging()

    private val realm = keycloakProperties.realm

    @Async
    @EventListener
    fun createInitialUser(cse: ContextRefreshedEvent) {
        if (initialUserProperties.email == "\${INIT_USER_EMAIL}" ||
            initialUserProperties.password == "\${INIT_USER_PASSWORD}" ||
            initialUserProperties.role == "\${INIT_USER_ROLE}"
        ) {
            logger.info { "Some of initialUserProperties are not set." }
            return
        }

        logger.trace { "will create operator role: ${initialUserProperties.role}" }

        val resultRole = kotlin.runCatching {
            keycloak.realm(realm)
                .roles()
                .create(RoleRepresentation(initialUserProperties.role, "initial role", false))
        }
        when {
            resultRole.isSuccess -> logger.info { "Role ${initialUserProperties.role} was created" }
            else -> logger.info { "Role ${initialUserProperties.role} already exist" }
        }
        val role = keycloak.realm(realm)
            .roles()
            .get(initialUserProperties.role)
            .toRepresentation()

        logger.trace { "will create operator user: ${initialUserProperties.email}" }

        val resultUser = Mono.just(
            User(
                email = initialUserProperties.email,
                firstName = "",
                lastName = "",
                credential = initialUserProperties.password
            )
        )
            .asFlow()
            .map(userService::create)
            .asFlux()
            .blockFirst()!!

        when (resultUser) {
            is Success -> logger.info { "Initial user ${initialUserProperties.email} was created successfully." }
            is Failure -> when (resultUser.error) {
                is Error.EmailCollision -> logger.info { "Initial user ${initialUserProperties.email} already exists." }
                else -> resultUser.log(logger, "Failed to create initial user.")
            }
        }

        val user = Mono.just(initialUserProperties.email)
            .asFlow()
            .map {
                userService.search(it)
                    .filter { user -> user.email == initialUserProperties.email }
                    .first()
            }
            .asFlux()
            .blockFirst()!!

        keycloak.realm(realm).users().get(user.id).roles().realmLevel().add(listOf(role))

        logger.info { "Initial user ${initialUserProperties.email} has role ${initialUserProperties.role}." }
    }
}
