package org.izmaylovalexey.services

import kotlinx.coroutines.flow.Flow

interface RoleTemplateService {
    suspend fun all(): Flow<String>
}
