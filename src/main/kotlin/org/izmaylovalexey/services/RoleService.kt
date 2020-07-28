package org.izmaylovalexey.services

import org.izmaylovalexey.entities.Either
import org.izmaylovalexey.entities.Tenant

interface RoleService {
    suspend fun apply(tenant: Tenant, role: String): Either<Unit>
    suspend fun delete(tenant: String, role: String): Either<Unit>
}
