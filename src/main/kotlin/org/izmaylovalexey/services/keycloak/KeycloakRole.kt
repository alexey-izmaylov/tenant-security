package org.izmaylovalexey.services.keycloak

import mu.KLogging
import org.izmaylovalexey.entities.Tenant
import org.izmaylovalexey.services.RoleService
import org.keycloak.admin.client.Keycloak
import org.keycloak.representations.idm.RoleRepresentation
import org.springframework.stereotype.Service

@Service
internal class KeycloakRole(val keycloak: Keycloak, keycloakProperties: KeycloakProperties) : RoleService {

    private val realm = keycloakProperties.realm

    override suspend fun apply(tenant: Tenant, role: String) {
        val name = "${tenant.name}.${role}"
        logger.info { "will create $name Keycloak Role" }
        keycloak.realm(realm)
            .roles()
            .create(RoleRepresentation(name, tenant.displayedName, false))
    }

    override suspend fun delete(tenant: String, role: String) {
        runCatching {
            val name = "$tenant.$role"
            logger.info { "will delete $name Keycloak Role" }
            keycloak.realm(realm)
                .roles()
                .deleteRole(name)
        }.getOrElse {
            if (it !is javax.ws.rs.NotFoundException) throw it
        }
    }

    companion object : KLogging()
}