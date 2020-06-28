package org.izmaylovalexey.services.keycloak

import com.mongodb.reactivestreams.client.MongoClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import mu.KLogging
import org.izmaylovalexey.entities.Tenant
import org.izmaylovalexey.services.RoleService
import org.izmaylovalexey.services.RoleTemplate
import org.izmaylovalexey.services.TenantService
import org.keycloak.admin.client.Keycloak
import org.keycloak.representations.idm.GroupRepresentation
import org.springframework.stereotype.Service

@Service
internal class KeycloakTenant(
    private val keycloak: Keycloak,
    keycloakProperties: KeycloakProperties,
    private val roleServices: Collection<RoleService>,
    private val roleTemplate: RoleTemplate,
    private val mongoClient: MongoClient
) : TenantService {

    private val realm = keycloakProperties.realm

    override suspend fun list(): Flow<Tenant> {
        return keycloak.realm(realm)
            .groups()
            .groups()
            .asFlow()
            .map {
                //attributes missed in group list
                keycloak.realm(realm)
                    .groups()
                    .group(it.id)
                    .toRepresentation()
            }
            .map { adapt(it) }
    }

    override suspend fun create(tenant: Tenant): Tenant = coroutineScope {
        val response = keycloak.realm(realm)
            .groups()
            .add(adapt(tenant))
        logger.trace { "create group response: ${response.status}" }
        val id = response.location.path.substringAfterLast("/")
        val actualTenant = Tenant(
            name = id,
            displayedName = tenant.displayedName,
            description = tenant.description
        )
        roleServices.asFlow()
            .flatMapMerge { service ->
                roleTemplate.all().asFlow()
                    .map {
                        async {
                            service.apply(actualTenant, it)
                        }
                    }
            }
            .flowOn(Dispatchers.Default)
            .collect()
        actualTenant
    }

    override suspend fun get(name: String): Tenant = adapt(
        keycloak.realm(realm)
            .groups()
            .group(name)
            .toRepresentation()
    )

    override suspend fun save(tenant: Tenant): Tenant {
        keycloak.realm(realm)
            .groups()
            .group(tenant.name)
            .update(adapt(tenant))
        return tenant
    }

    override suspend fun delete(name: String) = coroutineScope {
        launch {
            runCatching {
                keycloak.realm(realm)
                    .groups()
                    .group(name)
                    .remove()
            }.getOrElse {
                if (it !is javax.ws.rs.NotFoundException) throw it
            }
        }
        launch {
            logger.info { "drop mongo database $name" }
            //TODO use DBaaS
            mongoClient.getDatabase(name).drop().asFlow().collect()
        }
        roleServices
            .asFlow()
            .map { service ->
                roleTemplate
                    .all()
                    .asFlow()
                    .map {
                        async {
                            service.delete(name, it)
                        }
                    }
            }
            .flattenMerge()
            .flowOn(Dispatchers.Default)
            .collect()
    }

    companion object : KLogging()
}

internal fun adapt(group: GroupRepresentation) = Tenant(
    name = group.id,
    displayedName = group.attributes?.get("displayedName").orEmpty().getOrElse(0) { "" },
    description = group.attributes?.get("description").orEmpty().getOrElse(0) { "" }
)

internal fun adapt(tenant: Tenant) = GroupRepresentation().apply {
    name = tenant.name
    path = "/${tenant.name}"
    attributes = mutableMapOf()
    singleAttribute("displayedName", tenant.displayedName)
    singleAttribute("description", tenant.description)
}