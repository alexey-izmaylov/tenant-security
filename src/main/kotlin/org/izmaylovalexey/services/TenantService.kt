package org.izmaylovalexey.services

import kotlinx.coroutines.flow.Flow
import org.izmaylovalexey.entities.Tenant

interface TenantService {
    suspend fun list(): Flow<Tenant>
    suspend fun create(tenant: Tenant): Result<Tenant>
    suspend fun get(name: String): Result<Tenant>
    suspend fun save(tenant: Tenant): Result<Tenant>
    suspend fun delete(name: String): Result<Unit>
}
