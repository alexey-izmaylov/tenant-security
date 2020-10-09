package org.izmaylovalexey.services.keycloak

import com.mongodb.reactivestreams.client.MongoClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.withContext
import mu.KLogging
import org.izmaylovalexey.entities.Tenant
import org.izmaylovalexey.services.Error
import org.izmaylovalexey.services.Failure
import org.izmaylovalexey.services.Result
import org.izmaylovalexey.services.RoleService
import org.izmaylovalexey.services.RoleTemplate
import org.izmaylovalexey.services.Success
import org.izmaylovalexey.services.TenantService
import org.izmaylovalexey.services.toFailure
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

    override suspend fun list() =
        keycloak.realm(realm)
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

    override suspend fun create(tenant: Tenant) = runCatching {
        val response = keycloak.realm(realm)
            .groups()
            .add(adapt(tenant))
        if (response.status !in 200..299) {
            return@runCatching Failure(Error.Message("Keycloak responded with ${response.status} status."))
        }
        val id = response.location.path.substringAfterLast("/")
        val actualTenant = Tenant(
            name = id,
            displayedName = tenant.displayedName,
            description = tenant.description
        )
        roleTemplate.all()
            .flatMapMerge { role ->
                coroutineScope {
                    roleServices
                        .map {
                            async(Dispatchers.Default) {
                                it.apply(actualTenant, role)
                            }
                        }
                        .awaitAll()
                        .asFlow()
                }
            }
            .filterIsInstance<Failure>()
            .onEach { it.log(logger, "Exception occurred during tenant creation.") }
            .flowOn(Dispatchers.Default)
            .onEmpty<Result<Tenant>> {
                emit(Success(actualTenant))
            }
            .first()
    }.getOrElse { it.toFailure() }

    override suspend fun get(name: String) = runCatching<Result<Tenant>> {
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
        withContext(Dispatchers.Default) {
            val groupRoutine = async {
                runCatching<Result<Unit>> {
                    keycloak.realm(realm)
                        .groups()
                        .group(name)
                        .remove()
                    logger.trace { "Keycloak group is deleted: $name" }
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
            roleTemplate.all()
                .map { role ->
                    roleServices
                        .map {
                            async {
                                it.delete(name, role)
                            }
                        }
                        .awaitAll()
                        .asFlow()
                }
                .flattenMerge()
                .filterIsInstance<Failure>()
                .onEach { it.log(logger, "Exception occurred during tenant deletion.") }
                .onEmpty<Result<Unit>> {
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
