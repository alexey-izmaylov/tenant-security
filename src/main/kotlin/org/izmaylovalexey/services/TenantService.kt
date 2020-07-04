package org.izmaylovalexey.services

import kotlinx.coroutines.flow.Flow
import org.izmaylovalexey.entities.Tenant

interface TenantService {
    suspend fun list(): Flow<Tenant>
    suspend fun create(tenant: Tenant): Tenant
    suspend fun get(name: String): Tenant
    suspend fun save(tenant: Tenant): Tenant
    suspend fun delete(name: String)
}
