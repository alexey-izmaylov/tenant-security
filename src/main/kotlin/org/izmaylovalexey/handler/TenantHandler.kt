package org.izmaylovalexey.handler

import kotlinx.coroutines.reactive.awaitFirstOrDefault
import mu.KLogging
import org.izmaylovalexey.entities.Either
import org.izmaylovalexey.entities.Error
import org.izmaylovalexey.entities.Failure
import org.izmaylovalexey.entities.Success
import org.izmaylovalexey.entities.Tenant
import org.izmaylovalexey.services.TenantService
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyAndAwait
import org.springframework.web.reactive.function.server.bodyToMono
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import reactor.core.publisher.Mono

internal class TenantHandler(private val tenantService: TenantService) {

    suspend fun get(request: ServerRequest): ServerResponse {
        val name = request.pathVariable("name")
        return when (val either = tenantService.get(name)) {
            is Success -> ServerResponse.status(HttpStatus.OK).bodyValueAndAwait(either.value)
            is Failure -> when (either.error) {
                is Error.NotFound -> ServerResponse.status(HttpStatus.NOT_FOUND).buildAndAwait()
                else -> {
                    either.log(logger, "Failed to get $name tenant.")
                    ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).buildAndAwait()
                }
            }
        }
    }

    suspend fun getAll(request: ServerRequest) = ServerResponse
        .status(HttpStatus.OK)
        .bodyAndAwait(tenantService.list())

    suspend fun post(request: ServerRequest): ServerResponse {
        val input = request.bodyToMono<Tenant>()
            .map<Either<Tenant>> { Success(it) }
            .onErrorResume { Mono.just(Failure(Error.Exception(it))) }
            .awaitFirstOrDefault(Failure(Error.NotFound))
        return when (input) {
            is Success -> when (val result = tenantService.create(input.value)) {
                is Success -> ServerResponse.status(HttpStatus.OK).bodyValueAndAwait(result.value)
                is Failure -> {
                    result.log(logger, "Failed to create tenant: ${input.value}.")
                    ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).buildAndAwait()
                }
            }
            is Failure -> {
                input.log(logger, "Failed to post tenant.")
                ServerResponse.status(HttpStatus.BAD_REQUEST).buildAndAwait()
            }
        }
    }

    suspend fun delete(request: ServerRequest): ServerResponse {
        val name = request.pathVariable("name")
        return when (val result = tenantService.delete(name)) {
            is Success -> ServerResponse.status(HttpStatus.NO_CONTENT).buildAndAwait()
            is Failure -> {
                result.log(logger, "Failed to delete $name tenant")
                ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).buildAndAwait()
            }
        }
    }

    suspend fun patch(request: ServerRequest): ServerResponse {
        val name = request.pathVariable("name")
        val input = request.bodyToMono<Tenant>()
            .map {
                Tenant(
                    name = name,
                    displayedName = it.displayedName,
                    description = it.description
                )
            }
            .map<Either<Tenant>> { Success(it) }
            .onErrorResume { Mono.just(Failure(Error.Exception(it))) }
            .awaitFirstOrDefault(Failure(Error.NotFound))
        return when (input) {
            is Success -> when (val result = tenantService.save(input.value)) {
                is Success -> ServerResponse.status(HttpStatus.OK).bodyValueAndAwait(result.value)
                is Failure -> {
                    result.log(logger, "Failed to save tenant: ${input.value}.")
                    ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).buildAndAwait()
                }
            }
            is Failure -> {
                input.log(logger, "Failed to patch tenant.")
                ServerResponse.status(HttpStatus.BAD_REQUEST).buildAndAwait()
            }
        }
    }

    private companion object : KLogging()
}
