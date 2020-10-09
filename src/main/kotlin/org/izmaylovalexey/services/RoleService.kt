package org.izmaylovalexey.services

import org.izmaylovalexey.entities.Tenant

interface RoleService {
    suspend fun apply(tenant: Tenant, role: String): Result<Unit>
    suspend fun delete(tenant: String, role: String): Result<Unit>
}
