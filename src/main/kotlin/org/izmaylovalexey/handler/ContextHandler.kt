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
import org.izmaylovalexey.entities.Tenant
import org.izmaylovalexey.services.Failure
import org.izmaylovalexey.services.Success
import org.izmaylovalexey.services.TenantService
import org.izmaylovalexey.services.UserService
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyToMono
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait

internal class ContextHandler(
    private val userService: UserService,
    private val tenantService: TenantService,
    private val tenantSecurityConfig: TenantSecurityConfig
) {

    suspend fun getContext(request: ServerRequest): ServerResponse {
        val auth = request.authHeader()
        return when {
            auth.isEmpty() ->
                ServerResponse.status(HttpStatus.BAD_REQUEST)
                    .bodyValueAndAwait("Authorization header is missing")
            else -> {
                val token = auth.first().substringAfter(" ")
                val userId = JWT.decode(token).subject

                val eitherUser = userService.get(userId)
                val eitherAssignments = userService.getAssignments(userId)
                if (eitherUser is Success && eitherAssignments is Success) {
                    return ServerResponse.status(HttpStatus.OK).bodyValueAndAwait(
                        SecurityContext(
                            eitherUser.value,
                            eitherAssignments.value
                                .map { tenantService.get(it.tenant) }
                                .onEach { if (it is Failure) it.log(logger, "Failed to load tenant") }
                                .filterIsInstance<Success<Tenant>>()
                                .map { it.value }
                                .toSet()
                        )
                    )
                }
                if (eitherUser is Failure) eitherUser.log(logger, "Failed to get user.")
                if (eitherAssignments is Failure) eitherAssignments.log(logger, "Failed to get assignments.")
                return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).buildAndAwait()
            }
        }
    }

    suspend fun createAndAssign(request: ServerRequest): ServerResponse {
        val auth = request.authHeader()
        return when {
            auth.isEmpty() ->
                ServerResponse.status(HttpStatus.BAD_REQUEST)
                    .bodyValueAndAwait("Authorization header is missing")
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
