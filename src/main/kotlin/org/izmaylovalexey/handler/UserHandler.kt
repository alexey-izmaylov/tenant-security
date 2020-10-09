package org.izmaylovalexey.handler

import kotlinx.coroutines.reactive.awaitFirstOrDefault
import mu.KLogging
import org.izmaylovalexey.entities.User
import org.izmaylovalexey.services.Error
import org.izmaylovalexey.services.Failure
import org.izmaylovalexey.services.Result
import org.izmaylovalexey.services.Success
import org.izmaylovalexey.services.UserService
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyAndAwait
import org.springframework.web.reactive.function.server.bodyToMono
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import reactor.core.publisher.Mono

internal class UserHandler(private val userService: UserService) {

    suspend fun get(request: ServerRequest): ServerResponse {
        val id = request.pathVariable("id")
        return when (val result = userService.get(id)) {
            is Success -> ServerResponse.status(HttpStatus.OK).bodyValueAndAwait(result.value)
            is Failure -> when (result.error) {
                is Error.NotFound -> ServerResponse.status(HttpStatus.NOT_FOUND).buildAndAwait()
                else -> {
                    result.log(logger, "Failed to get $id user.")
                    ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).buildAndAwait()
                }
            }
        }
    }

    suspend fun getByTenant(request: ServerRequest): ServerResponse {
        val tenant = request.queryParam("tenant")
        return when {
            tenant.isPresent -> ServerResponse.status(HttpStatus.OK).bodyAndAwait(userService.list(tenant.get()))
            else -> ServerResponse.status(HttpStatus.BAD_REQUEST).bodyValueAndAwait("Missing tenant parameter.")
        }
    }

    suspend fun post(request: ServerRequest): ServerResponse {
        val input = request.bodyToMono<User>()
            .map<Result<User>> { Success(it) }
            .onErrorResume { Mono.just(Failure(Error.Exception(it))) }
            .awaitFirstOrDefault(Failure(Error.NotFound))
        return when (input) {
            is Success -> when (val result = userService.create(input.value)) {
                is Success -> ServerResponse.status(HttpStatus.OK).bodyValueAndAwait(result.value)
                is Failure -> when (result.error) {
                    is Error.EmailCollision ->
                        ServerResponse.status(HttpStatus.BAD_REQUEST)
                            .bodyValueAndAwait("This email is used.")
                    else -> {
                        result.log(logger, "Failed to create user: ${input.value}.")
                        ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).buildAndAwait()
                    }
                }
            }
            is Failure -> {
                input.log(logger, "Failed to post user.")
                ServerResponse.status(HttpStatus.BAD_REQUEST).buildAndAwait()
            }
        }
    }

    suspend fun assign(request: ServerRequest): ServerResponse {
        val id = request.pathVariable("id")
        val tenantName = request.pathVariable("tenant-name")
        val role = request.pathVariable("role")
        return when (val result = userService.assign(id, tenantName, role)) {
            is Success -> ServerResponse.status(HttpStatus.OK).buildAndAwait()
            is Failure -> when (result.error) {
                is Error.NotFound -> ServerResponse.status(HttpStatus.NOT_FOUND).buildAndAwait()
                else -> {
                    result.log(logger, "Failed to assign.")
                    ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).buildAndAwait()
                }
            }
        }
    }

    suspend fun evict(request: ServerRequest): ServerResponse {
        val id = request.pathVariable("id")
        val tenantName = request.pathVariable("tenant-name")
        val role = request.pathVariable("role")
        return when (val result = userService.evict(id, tenantName, role)) {
            is Success -> ServerResponse.status(HttpStatus.OK).buildAndAwait()
            is Failure -> when (result.error) {
                is Error.NotFound -> ServerResponse.status(HttpStatus.NOT_FOUND).buildAndAwait()
                else -> {
                    result.log(logger, "Failed to evict.")
                    ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).buildAndAwait()
                }
            }
        }
    }

    suspend fun delete(request: ServerRequest): ServerResponse {
        val id = request.pathVariable("id")
        return when (val result = userService.delete(id)) {
            is Success -> ServerResponse.status(HttpStatus.NO_CONTENT).buildAndAwait()
            is Failure -> {
                result.log(logger, "Failed to delete $id user.")
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
        return when (val result = userService.getAssignments(id)) {
            is Success -> ServerResponse.status(HttpStatus.OK).bodyAndAwait(result.value)
            is Failure -> when (result.error) {
                is Error.NotFound -> ServerResponse.status(HttpStatus.NOT_FOUND).buildAndAwait()
                else -> {
                    result.log(logger, "Failed to get $id user assignments.")
                    ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).buildAndAwait()
                }
            }
        }
    }

    private companion object : KLogging()
}
