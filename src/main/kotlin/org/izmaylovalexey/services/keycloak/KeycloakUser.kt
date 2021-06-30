package org.izmaylovalexey.services.keycloak

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toSet
import mu.KLogging
import org.izmaylovalexey.entities.Assignment
import org.izmaylovalexey.entities.User
import org.izmaylovalexey.services.Error
import org.izmaylovalexey.services.Failure
import org.izmaylovalexey.services.Result
import org.izmaylovalexey.services.RoleTemplateService
import org.izmaylovalexey.services.Success
import org.izmaylovalexey.services.UserService
import org.izmaylovalexey.services.toFailure
import org.keycloak.admin.client.Keycloak
import org.keycloak.representations.idm.CredentialRepresentation
import org.keycloak.representations.idm.UserRepresentation
import javax.ws.rs.NotFoundException

internal class KeycloakUser(
    private val keycloak: Keycloak,
    keycloakProperties: KeycloakProperties,
    private val roleTemplateService: RoleTemplateService
) : UserService {

    private val realm = keycloakProperties.realm

    override suspend fun list(tenant: String) =
        roleTemplateService.all()
            .flatMapConcat { role ->
                keycloak.realm(realm)
                    .roles()
                    .get("$tenant.$role")
                    .roleUserMembers
                    .asFlow()
                    .map { Pair(it.adapt(), role) }
            }
            .catch { logger.error(it) { "Exception occurred during user list loading." } }
            .toSet()
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

    override suspend fun create(user: User) = runCatching<Result<User>> {
        val sameEmailUsers = keycloak.realm(realm)
            .users()
            .search(user.email)
            .asFlow()
            .map { it.email }
            .filter { it == user.email }
            .count()
        if (sameEmailUsers > 0) {
            return Failure(Error.EmailCollision)
        }
        keycloak.realm(realm)
            .users()
            .create(adapt(user))
        return keycloak.realm(realm)
            .users()
            .search(user.email)
            .asFlow()
            .filter { it.email == user.email }
            .take(1)
            .onEach {
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
            .map { Success(it) }
            .first()
    }.getOrElse { it.toFailure() }

    override suspend fun get(id: String) = runCatching<Result<User>> {
        Success(
            keycloak.realm(realm)
                .users()
                .get(id)
                .toRepresentation()
                .adapt()
        )
    }.getOrElse {
        when (it) {
            is NotFoundException -> Failure(Error.NotFound)
            else -> it.toFailure()
        }
    }

    override suspend fun delete(id: String) = runCatching {
        logger.info { "will delete user $id" }
        keycloak.realm(realm).users().delete(id)
        Success(Unit)
    }.getOrElse { it.toFailure() }

    override suspend fun assign(user: String, tenant: String, role: String) = runCatching<Result<Unit>> {
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
        Success(Unit)
    }.getOrElse {
        when (it) {
            is NotFoundException -> Failure(Error.NotFound)
            else -> it.toFailure()
        }
    }

    override suspend fun evict(user: String, tenant: String, role: String) = runCatching {
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
        Success(Unit)
    }.getOrElse { it.toFailure() }

    override suspend fun search(searchingParam: String): Flow<User> {
        return keycloak
            .realm(realm)
            .users()
            .search(searchingParam, 0, 100, true)
            .asFlow()
            .map { it.adapt() }
            .catch { logger.error(it) { "Exception occurred during user search." } }
    }

    override suspend fun search(userName: String, firstName: String, lastName: String, email: String): Flow<User> {
        return keycloak.realm(realm)
            .users()
            .search(userName, firstName, lastName, email, 0, 100, true)
            .asFlow()
            .map { it.adapt() }
            .catch { logger.error(it) { "Exception occurred during user search." } }
    }

    override suspend fun getAssignments(id: String) = runCatching<Result<Flow<Assignment>>> {
        val user = keycloak.realm(realm)
            .users()
            .get(id)
            .toRepresentation()
            .adapt()
        val flow = keycloak
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
        return Success(flow)
    }.getOrElse {
        when (it) {
            is NotFoundException -> Failure(Error.NotFound)
            else -> it.toFailure()
        }
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
