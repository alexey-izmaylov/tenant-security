package org.izmaylovalexey.handler

import kotlinx.coroutines.flow.map
import org.izmaylovalexey.entities.RoleTemplate
import org.izmaylovalexey.services.RoleTemplateService
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyAndAwait

internal class RoleTemplateHandler(private val roleTemplateService: RoleTemplateService) {

    suspend fun list() = ServerResponse.ok().bodyAndAwait(roleTemplateService.all().map { RoleTemplate(it) })
}
