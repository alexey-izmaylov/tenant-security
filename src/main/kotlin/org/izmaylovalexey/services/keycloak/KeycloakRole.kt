package org.izmaylovalexey.services.keycloak

import mu.KLogging
import org.izmaylovalexey.entities.Either
import org.izmaylovalexey.entities.Error
import org.izmaylovalexey.entities.Failure
import org.izmaylovalexey.entities.Success
import org.izmaylovalexey.entities.Tenant
import org.izmaylovalexey.entities.toFailure
import org.izmaylovalexey.services.RoleService
import org.keycloak.admin.client.Keycloak
import org.keycloak.representations.idm.RoleRepresentation
import org.springframework.stereotype.Service

@Service
internal class KeycloakRole(val keycloak: Keycloak, keycloakProperties: KeycloakProperties) : RoleService {

    private val realm = keycloakProperties.realm

    override suspend fun apply(tenant: Tenant, role: String) = runCatching {
        val name = "${tenant.name}.$role"
        logger.info { "will create Keycloak Role $name" }
        keycloak.realm(realm)
            .roles()
            .create(RoleRepresentation(name, tenant.displayedName, false))
        Success(Unit)
    }.getOrElse { it.toFailure() }

    override suspend fun delete(tenant: String, role: String) = runCatching<Either<Unit>> {
        val name = "$tenant.$role"
        logger.info { "will delete Keycloak Role $name" }
        keycloak.realm(realm)
            .roles()
            .deleteRole(name)
        Success(Unit)
    }.getOrElse {
        when (it) {
            is javax.ws.rs.NotFoundException -> Success(Unit)
            else -> Failure(Error.Exception(it))
        }
    }

    private companion object : KLogging()
}
