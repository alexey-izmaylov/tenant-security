package org.izmaylovalexey.services

import kotlinx.coroutines.flow.Flow
import org.izmaylovalexey.entities.Assignment
import org.izmaylovalexey.entities.User
import java.util.Optional

interface UserService {
    suspend fun list(tenant: String): Flow<Assignment>
    suspend fun create(user: User): Optional<User>
    suspend fun get(id: String): User
    suspend fun delete(id: String): Flow<Result<Int>>
    suspend fun assign(user: String, tenant: String, role: String)
    suspend fun evict(user: String, tenant: String, role: String)
    suspend fun search(searchingParam: String): Flow<User>
    suspend fun search(userName: String, firstName: String, lastName: String, email: String): Flow<User>
    suspend fun getAssignments(id: String): Flow<Assignment>
}