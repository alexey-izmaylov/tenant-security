package org.izmaylovalexey.services

import org.izmaylovalexey.entities.Tenant

interface RoleService {
    suspend fun apply(tenant: Tenant, role: String)
    suspend fun delete(tenant: String, role: String)
}
