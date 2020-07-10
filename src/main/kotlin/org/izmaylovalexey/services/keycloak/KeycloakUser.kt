package org.izmaylovalexey.services.keycloak

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import mu.KLogging
import org.izmaylovalexey.entities.Assignment
import org.izmaylovalexey.entities.User
import org.izmaylovalexey.services.RoleTemplate
import org.izmaylovalexey.services.UserService
import org.keycloak.admin.client.Keycloak
import org.keycloak.representations.idm.CredentialRepresentation
import org.keycloak.representations.idm.UserRepresentation
import org.springframework.stereotype.Service
import java.util.Optional

@Service
internal class KeycloakUser(
    private val keycloak: Keycloak,
    keycloakProperties: KeycloakProperties,
    private val roleTemplate: RoleTemplate
) : UserService {

    private val realm = keycloakProperties.realm

    override suspend fun list(tenant: String): Flow<Assignment> = coroutineScope {
        roleTemplate.all()
            .flatMap { role ->
                keycloak.realm(realm)
                    .roles()
                    .get("$tenant.$role")
                    .roleUserMembers
                    .map { Pair(it.adapt(), role) }
            }
            .groupBy(
                { it.first },
                { it.second }
            )
            .map {
                Assignment(
                    tenant = tenant,
                    user = it.key,
                    roles = it.value.toSet()
                )
            }
            .asFlow()
    }

    override suspend fun create(user: User): Optional<User> {
        val sameEmailUsers = keycloak.realm(realm)
            .users()
            .search(user.email)
            .asFlow()
            .map { it.email }
            .filter { it == user.email }
            .count()
        if (sameEmailUsers > 0) {
            return Optional.empty()
        }
        keycloak.realm(realm)
            .users()
            .create(adapt(user))
        return keycloak.realm(realm)
            .users()
            .search(user.email)
            .stream()
            .filter { it.email == user.email }
            .limit(1)
            .peek {
                keycloak.realm(realm)
                    .users()
                    .get(it.id)
                    .resetPassword(
                        CredentialRepresentation().apply {
                            value = user.credential
                            type = "password"
                            isTemporary = false
                        }
                    )
            }
            .map { it.adapt() }
            .findFirst()
    }

    override suspend fun get(id: String): User =
        keycloak.realm(realm)
            .users()
            .get(id)
            .toRepresentation()
            .adapt()

    override suspend fun delete(id: String): Flow<Result<Int>> = flowOf(
        runCatching {
            logger.trace { "will delete user $id" }
            keycloak.realm(realm).users().delete(id).status
        }
    )

    override suspend fun assign(user: String, tenant: String, role: String) {
        keycloak.realm(realm)
            .users()
            .get(user)
            .roles()
            .realmLevel()
            .add(
                listOf(
                    keycloak.realm(realm)
                        .roles()
                        .get("$tenant.$role")
                        .toRepresentation()
                )
            )
    }

    override suspend fun evict(user: String, tenant: String, role: String) {
        keycloak.realm(realm)
            .users()
            .get(user)
            .roles()
            .realmLevel()
            .remove(
                listOf(
                    keycloak.realm(realm)
                        .roles()
                        .get("$tenant.$role")
                        .toRepresentation()
                )
            )
    }

    override suspend fun search(searchingParam: String): Flow<User> {
        return keycloak
            .realm(realm)
            .users()
            .search(searchingParam, 0, 100, true)
            .asFlow()
            .map { it.adapt() }
    }

    override suspend fun search(userName: String, firstName: String, lastName: String, email: String): Flow<User> {
        return keycloak.realm(realm)
            .users()
            .search(userName, firstName, lastName, email, 0, 100, true)
            .asFlow()
            .map { it.adapt() }
    }

    override suspend fun getAssignments(id: String): Flow<Assignment> {
        val user = keycloak.realm(realm)
            .users()
            .get(id)
            .toRepresentation()
            .adapt()
        return keycloak
            .realm(realm)
            .users()
            .get(id)
            .roles()
            .realmLevel()
            .listEffective()
            .map { it.name }
            .filter { it.contains(".") }
            .groupBy(
                { it.substringBefore(".") },
                { it.substringAfter(".") }
            )
            .map {
                Assignment(
                    tenant = it.key,
                    user = user,
                    roles = it.value.toSet()
                )
            }
            .asFlow()
    }

    private companion object : KLogging()
}

private fun UserRepresentation.adapt() = User(
    id = this.id,
    email = this.email,
    firstName = this.firstName.orEmpty(),
    lastName = this.lastName.orEmpty(),
    credential = "*****"
)

internal fun adapt(user: User) = UserRepresentation().apply {
    username = user.email
    email = user.email
    firstName = user.firstName
    lastName = user.lastName
    isEnabled = true
    isEmailVerified = true
}
