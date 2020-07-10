package org.izmaylovalexey.services

import kotlinx.coroutines.flow.Flow
import org.izmaylovalexey.entities.Either
import org.izmaylovalexey.entities.Tenant

interface TenantService {
    suspend fun list(): Flow<Tenant>
    suspend fun create(tenant: Tenant): Either<Tenant>
    suspend fun get(name: String): Either<Tenant>
    suspend fun save(tenant: Tenant): Either<Tenant>
    suspend fun delete(name: String): Either<Unit>
}
