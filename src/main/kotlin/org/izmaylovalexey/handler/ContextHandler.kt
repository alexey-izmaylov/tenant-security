package org.izmaylovalexey.handler

import com.auth0.jwt.JWT
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toSet
import kotlinx.coroutines.reactive.asFlow
import mu.KLogging
import org.izmaylovalexey.TenantSecurityConfig
import org.izmaylovalexey.entities.SecurityContext
import org.izmaylovalexey.entities.Success
import org.izmaylovalexey.entities.Tenant
import org.izmaylovalexey.services.TenantService
import org.izmaylovalexey.services.UserService
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyToMono
import org.springframework.web.reactive.function.server.bodyValueAndAwait

internal class ContextHandler(
    private val userService: UserService,
    private val tenantService: TenantService,
    private val tenantSecurityConfig: TenantSecurityConfig
) {

    suspend fun getContext(request: ServerRequest): ServerResponse {
        val auth = request.authHeader()
        return when {
            auth.isEmpty() -> ServerResponse.status(HttpStatus.BAD_REQUEST).bodyValueAndAwait("Authorization header is missing")
            else -> {
                val token = auth.first().substringAfter(" ")
                val userId = JWT.decode(token).subject
                ServerResponse.status(HttpStatus.OK).bodyValueAndAwait(
                    SecurityContext(
                        userService.get(userId),
                        userService.getAssignments(userId)
                            .map { tenantService.get(it.tenant) }
                            .filterIsInstance<Success<Tenant>>()
                            .map { it.value }
                            .toSet()
                    )
                )
            }
        }
    }

    suspend fun createAndAssign(request: ServerRequest): ServerResponse {
        val auth = request.authHeader()
        return when {
            auth.isEmpty() -> ServerResponse.status(HttpStatus.BAD_REQUEST).bodyValueAndAwait("Authorization header is missing")
            else -> {
                val token = auth.first().substringAfter(" ")
                val userId = JWT.decode(token).subject
                ServerResponse.status(HttpStatus.OK).bodyValueAndAwait(
                    request.bodyToMono<Tenant>().asFlow()
                        .map(tenantService::create)
                        .filterIsInstance<Success<Tenant>>()
                        .map { it.value }
                        .onEach { userService.assign(userId, it.name, tenantSecurityConfig.defaultRole) }
                        .first()
                )
            }
        }
    }

    private companion object : KLogging()
}

fun ServerRequest.authHeader(): List<String> = headers().header("Authorization")
