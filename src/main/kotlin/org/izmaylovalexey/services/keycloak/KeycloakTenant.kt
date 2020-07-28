package org.izmaylovalexey.services.keycloak

import com.mongodb.reactivestreams.client.MongoClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onEmpty
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
import javax.ws.rs.NotFoundException

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
            .catch { logger.error(it) { "Exception occurred during tenant list loading." } }
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
            roleServices
                .asFlow()
                .map { service ->
                    roleTemplate
                        .all()
                        .map {
                            service.apply(actualTenant, it)
                        }
                }
                .flattenMerge()
                .flowOn(Dispatchers.Default)
                .filterIsInstance<Failure>()
                .onEach { it.log(logger, "Exception occurred during tenant creation.") }
                .onEmpty<Either<Tenant>> {
                    emit(Success(actualTenant))
                }
                .first()
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
            is NotFoundException -> Failure(Error.NotFound)
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
                        is NotFoundException -> Success(Unit)
                        else -> Failure(Error.Exception(it))
                    }
                }
            }
            launch {
                logger.info { "drop mongo database $name" }
                // TODO use DBaaS
                mongoClient.getDatabase(name).drop().asFlow().collect()
            }
            roleServices.asFlow()
                .flatMapMerge { service ->
                    roleTemplate.all()
                        .map {
                            service.delete(name, it)
                        }
                }
                .filterIsInstance<Failure>()
                .onEach { it.log(logger, "Exception occurred during tenant deletion.") }
                .flowOn(Dispatchers.Default)
                .onEmpty<Either<Unit>> {
                    emit(groupRoutine.await())
                }
                .first()
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
