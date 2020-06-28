package org.izmaylovalexey.handler

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asFlow
import mu.KLogging
import org.izmaylovalexey.entities.Tenant
import org.izmaylovalexey.services.TenantService
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyAndAwait
import org.springframework.web.reactive.function.server.bodyToMono
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait

internal class TenantHandler(private val tenantService: TenantService) {

    suspend fun get(request: ServerRequest): ServerResponse {
        val name = request.pathVariable("name")
        val result = runCatching { tenantService.get(name) }
        return when {
            result.isSuccess -> ServerResponse.status(HttpStatus.OK).bodyValueAndAwait(result.getOrThrow())
            result.exceptionOrNull() is javax.ws.rs.NotFoundException -> ServerResponse.status(HttpStatus.NOT_FOUND).buildAndAwait()
            else -> {
                logger.error(result.exceptionOrNull()) { "Failed to get $name tenant" }
                ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).buildAndAwait()
            }
        }
    }

    suspend fun getAll(request: ServerRequest): ServerResponse {
        return ServerResponse.status(HttpStatus.OK).bodyAndAwait(
            tenantService.list()
        )
    }

    suspend fun post(request: ServerRequest): ServerResponse {
        return ServerResponse.status(HttpStatus.OK).bodyValueAndAwait(
            request.bodyToMono<Tenant>().asFlow()
                .map(tenantService::create)
                .first()
        )
    }

    suspend fun delete(request: ServerRequest): ServerResponse {
        val name = request.pathVariable("name")
        val result = runCatching { tenantService.delete(name) }
        return when {
            result.isSuccess -> ServerResponse.status(HttpStatus.NO_CONTENT).buildAndAwait()
            else -> {
                logger.error(result.exceptionOrNull()) { "Failed to delete $name tenant" }
                ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).buildAndAwait()
            }
        }
    }

    suspend fun patch(request: ServerRequest): ServerResponse {
        val name = request.pathVariable("name")
        return ServerResponse.status(HttpStatus.OK).bodyValueAndAwait(
            request.bodyToMono<Tenant>().asFlow()
                .map {
                    Tenant(
                        name = name,
                        displayedName = it.displayedName,
                        description = it.description
                    )
                }
                .map(tenantService::save)
                .first()
        )
    }

    companion object : KLogging()
}
