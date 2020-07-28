package org.izmaylovalexey.services

import kotlinx.coroutines.flow.Flow

interface RoleTemplate {
    suspend fun all(): Flow<String>
}
