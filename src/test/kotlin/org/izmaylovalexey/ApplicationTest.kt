package org.izmaylovalexey

import mu.KLogging
import org.awaitility.Awaitility
import org.izmaylovalexey.Integration.keycloak
import org.izmaylovalexey.Integration.mongo
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import java.net.ServerSocket
import java.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApplicationTest {

    private val port = 8080
    private val client = WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()

    init {
        Awaitility.await("spring-boot-port")
            .pollInSameThread()
            .pollInterval(Duration.ofMillis(100))
            .timeout(Duration.ofMinutes(1))
            .ignoreExceptions()
            .until {
                logger.info { "waiting for port $port" }
                ServerSocket(port).use { true }
            }
    }

    @BeforeAll
    fun start() {
        main(
            arrayOf(
                "--spring.profiles.active=test",
                "--server.port=$port",
                "--spring.data.mongodb.uri=mongodb://${mongo.containerIpAddress}:${mongo.firstMappedPort}/tenant-security",
                "--keycloak.uri=http://${keycloak.containerIpAddress}:${keycloak.firstMappedPort}/auth",
                "--keycloak.password=keycloak",
            )
        )
    }

    @Test
    fun name() {
        client.get().uri("/")
            .exchange()
            .expectStatus().isOk
            .expectBody<String>().isEqualTo("tenant-security")
    }

    @Test
    fun health() {
        client.get().uri("/health")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun metrics() {
        client.get().uri("/actuator/prometheus")
            .exchange()
            .expectStatus().isOk
    }

    @AfterAll
    fun shutdown() {
        client.post().uri("/actuator/shutdown")
            .exchange()
            .expectStatus().isOk
    }

    private companion object : KLogging()
}
