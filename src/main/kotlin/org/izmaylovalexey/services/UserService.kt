package org.izmaylovalexey.services

import kotlinx.coroutines.flow.Flow
import org.izmaylovalexey.entities.Assignment
import org.izmaylovalexey.entities.Either
import org.izmaylovalexey.entities.User

interface UserService {
    suspend fun list(tenant: String): Flow<Assignment>
    suspend fun create(user: User): Either<User>
    suspend fun get(id: String): Either<User>
    suspend fun delete(id: String): Either<Unit>
    suspend fun assign(user: String, tenant: String, role: String): Either<Unit>
    suspend fun evict(user: String, tenant: String, role: String): Either<Unit>
    suspend fun search(searchingParam: String): Flow<User>
    suspend fun search(userName: String, firstName: String, lastName: String, email: String): Flow<User>
    suspend fun getAssignments(id: String): Either<Flow<Assignment>>
}
