package org.izmaylovalexey.handler

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asFlow
import mu.KLogging
import org.izmaylovalexey.entities.User
import org.izmaylovalexey.services.UserService
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyAndAwait
import org.springframework.web.reactive.function.server.bodyToMono
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait

internal class UserHandler(private val userService: UserService) {

    suspend fun get(request: ServerRequest): ServerResponse {
        val result = runCatching {
            userService.get(request.pathVariable("id"))
        }
        return when {
            result.isSuccess -> ServerResponse.status(HttpStatus.OK).bodyValueAndAwait(result.getOrThrow())
            result.exceptionOrNull() is javax.ws.rs.NotFoundException -> ServerResponse.status(HttpStatus.NOT_FOUND).buildAndAwait()
            else -> ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).buildAndAwait()
        }
    }

    suspend fun getByTenant(request: ServerRequest): ServerResponse {
        val tenant = request.queryParam("tenant")
        return when {
            tenant.isPresent -> ServerResponse.status(HttpStatus.OK).bodyAndAwait(userService.list(tenant.get()))
            else -> ServerResponse.status(HttpStatus.BAD_REQUEST).bodyValueAndAwait("Missing tenant parameter")
        }
    }

    suspend fun post(request: ServerRequest): ServerResponse {
        val result = request
            .bodyToMono<User>()
            .asFlow()
            .map(userService::create)
            .first()
        return when {
            result.isPresent -> ServerResponse.status(HttpStatus.OK).bodyValueAndAwait(result.get())
            else -> ServerResponse.status(HttpStatus.BAD_REQUEST).bodyValueAndAwait("This email is used")
        }
    }

    suspend fun assign(request: ServerRequest): ServerResponse {
        val id = request.pathVariable("id")
        val tenantName = request.pathVariable("tenant-name")
        val role = request.pathVariable("role")
        val result = runCatching { userService.assign(id, tenantName, role) }
        return when {
            result.isSuccess -> ServerResponse.status(HttpStatus.OK).buildAndAwait()
            result.exceptionOrNull() is javax.ws.rs.NotFoundException -> ServerResponse.status(HttpStatus.NOT_FOUND).buildAndAwait()
            else -> ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).buildAndAwait()
        }
    }

    suspend fun evict(request: ServerRequest): ServerResponse {
        val id = request.pathVariable("id")
        val tenantName = request.pathVariable("tenant-name")
        val role = request.pathVariable("role")
        userService.evict(id, tenantName, role)
        return ServerResponse.status(HttpStatus.OK).buildAndAwait()
    }

    suspend fun delete(request: ServerRequest): ServerResponse {
        val id = request.pathVariable("id")
        val result = userService.delete(id).first()
        return when {
            result.isSuccess -> {
                logger.trace { "user has been deleted, status: ${result.getOrThrow()}" }
                ServerResponse.status(HttpStatus.NO_CONTENT).buildAndAwait()
            }
            else -> {
                logger.error(result.exceptionOrNull()) { "Failed to delete $id user" }
                ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).buildAndAwait()
            }
        }
    }

    suspend fun search(request: ServerRequest): ServerResponse {
        val searchingString = request.queryParam("searchingString")
        val userName = request.queryParam("userName").orElse("")
        val firstName = request.queryParam("firstName").orElse("")
        val lastName = request.queryParam("lastName").orElse("")
        val email = request.queryParam("email").orElse("")
        if (searchingString.isPresent) {
            return when {
                userName == "" && firstName == "" && lastName == "" && email == "" ->
                    ServerResponse.status(HttpStatus.OK).bodyAndAwait(userService.search(searchingString.get()))
                else ->
                    ServerResponse.status(HttpStatus.BAD_REQUEST)
                        .bodyValueAndAwait("Wrong params. Use only searchingString or special parameters for different strings")
            }
        }
        return ServerResponse.status(HttpStatus.OK)
            .bodyAndAwait(userService.search(userName, firstName, lastName, email))
    }

    suspend fun getAssignments(request: ServerRequest): ServerResponse {
        val id = request.pathVariable("id")
        val result = runCatching {
            userService.getAssignments(id)
        }
        return when {
            result.isSuccess -> ServerResponse.status(HttpStatus.OK).bodyAndAwait(result.getOrThrow())
            result.exceptionOrNull() is javax.ws.rs.NotFoundException -> ServerResponse.status(HttpStatus.NOT_FOUND).buildAndAwait()
            else -> ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).buildAndAwait()
        }
    }

    private companion object : KLogging()
}
