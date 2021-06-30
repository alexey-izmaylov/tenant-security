package org.izmaylovalexey.services.keycloak

import mu.KLogging
import org.izmaylovalexey.entities.Tenant
import org.izmaylovalexey.services.Error
import org.izmaylovalexey.services.Failure
import org.izmaylovalexey.services.Result
import org.izmaylovalexey.services.RoleService
import org.izmaylovalexey.services.Success
import org.izmaylovalexey.services.toFailure
import org.keycloak.admin.client.Keycloak
import org.keycloak.representations.idm.RoleRepresentation

internal class KeycloakRole(val keycloak: Keycloak, keycloakProperties: KeycloakProperties) : RoleService {

    private val realm = keycloakProperties.realm

    override suspend fun apply(tenant: Tenant, role: String) = runCatching {
        val name = "${tenant.name}.$role"
        logger.info { "will create Keycloak Role: $name" }
        keycloak.realm(realm)
            .roles()
            .create(RoleRepresentation(name, tenant.displayedName, false))
        logger.trace { "Keycloak role is created: $name" }
        Success(Unit)
    }.getOrElse { it.toFailure() }

    override suspend fun delete(tenant: String, role: String) = runCatching<Result<Unit>> {
        val name = "$tenant.$role"
        logger.info { "will delete Keycloak Role: $name" }
        keycloak.realm(realm)
            .roles()
            .deleteRole(name)
        logger.trace { "Keycloak role is deleted: $name" }
        Success(Unit)
    }.getOrElse {
        when (it) {
            is javax.ws.rs.NotFoundException -> Success(Unit)
            else -> Failure(Error.Exception(it))
        }
    }

    private companion object : KLogging()
}
