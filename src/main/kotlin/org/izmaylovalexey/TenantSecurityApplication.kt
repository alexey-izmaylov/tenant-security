package org.izmaylovalexey

import org.izmaylovalexey.handler.ContextHandler
import org.izmaylovalexey.handler.TenantHandler
import org.izmaylovalexey.handler.UserHandler
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.boot.runApplication
import org.springframework.context.support.beans
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories
import org.springframework.http.HttpMethod.DELETE
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.PATCH
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter

@ConfigurationPropertiesScan
@EnableReactiveMongoRepositories
@SpringBootApplication
class TenantSecurityApplication

fun main(args: Array<String>) {
    runApplication<TenantSecurityApplication>(*args) {
        addInitializers(
            beans {
                bean<TenantHandler>()
                bean(::tenantRoute)
                bean<UserHandler>()
                bean(::userRoute)
                bean<ContextHandler>()
                bean(::contextRoute)
                bean { healthRoute() }
            }
        )
    }
}

internal fun healthRoute() = coRouter {
    method(GET).nest { (path("/health") or path("/")).invoke { ok().bodyValueAndAwait("tenant-security") } }
}

internal fun tenantRoute(handler: TenantHandler) = coRouter {
    "/tenant".nest {
        "/{name}".nest {
            method(GET, handler::get)
            method(PATCH, handler::patch)
            method(DELETE, handler::delete)
        }
        method(GET, handler::getAll)
        (method(POST) and contentType(MediaType.APPLICATION_JSON)).invoke(handler::post)
    }
}

internal fun userRoute(handler: UserHandler) = coRouter {
    "/user".nest {
        GET("/search", handler::search)
        "/{id}".nest {
            "/tenant/{tenant-name}/{role}".nest {
                method(PUT, handler::assign)
                method(DELETE, handler::evict)
            }
            GET("/tenant", handler::getAssignments)
            method(GET, handler::get)
            method(DELETE, handler::delete)
        }
        method(GET, handler::getByTenant)
        (method(POST) and contentType(MediaType.APPLICATION_JSON)).invoke(handler::post)
    }
}

internal fun contextRoute(handler: ContextHandler) = coRouter {
    GET("/context", handler::getContext)
    (POST("/context/tenant") and contentType(MediaType.APPLICATION_JSON)).invoke(handler::createAndAssign)
}

@ConfigurationProperties("tenant")
@ConstructorBinding
class TenantSecurityConfig(
    val defaultRole: String
)
