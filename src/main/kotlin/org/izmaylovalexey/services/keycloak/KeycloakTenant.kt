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
import org.izmaylovalexey.entities.Either
import org.izmaylovalexey.entities.Error
import org.izmaylovalexey.entities.Failure
import org.izmaylovalexey.entities.Success
import org.izmaylovalexey.entities.Tenant
import org.izmaylovalexey.entities.toFailure
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
                // attributes missed in group list
                keycloak.realm(realm)
                    .groups()
                    .group(it.id)
                    .toRepresentation()
            }
            .map { adapt(it) }
    }

    override suspend fun create(tenant: Tenant) = runCatching {
        coroutineScope {
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
                            service.apply(actualTenant, it)
                        }
                }
                .flowOn(Dispatchers.Default)
                .collect()
            Success(actualTenant)
        }
    }.getOrElse { it.toFailure() }

    override suspend fun get(name: String) = runCatching<Either<Tenant>> {
        Success(
            adapt(
                keycloak.realm(realm)
                    .groups()
                    .group(name)
                    .toRepresentation()
            )
        )
    }.getOrElse {
        when (it) {
            is javax.ws.rs.NotFoundException -> Failure(Error.NotFound)
            else -> it.toFailure()
        }
    }

    override suspend fun save(tenant: Tenant) = runCatching {
        keycloak.realm(realm)
            .groups()
            .group(tenant.name)
            .update(adapt(tenant))
        Success(tenant)
    }.getOrElse { it.toFailure() }

    override suspend fun delete(name: String) = runCatching {
        coroutineScope {
            val groupRoutine = async {
                runCatching<Either<Unit>> {
                    keycloak.realm(realm)
                        .groups()
                        .group(name)
                        .remove()
                    Success(Unit)
                }.getOrElse {
                    when (it) {
                        is javax.ws.rs.NotFoundException -> Success(Unit)
                        else -> Failure(Error.Exception(it))
                    }
                }
            }
            launch {
                logger.info { "drop mongo database $name" }
                // TODO use DBaaS
                mongoClient.getDatabase(name).drop().asFlow().collect()
            }
            roleServices
                .asFlow()
                .map { service ->
                    roleTemplate
                        .all()
                        .asFlow()
                        .map {
                            service.delete(name, it)
                        }
                }
                .flattenMerge()
                .flowOn(Dispatchers.Default)
                .collect()
            groupRoutine.await()
        }
    }.getOrElse { it.toFailure() }

    private companion object : KLogging()
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
