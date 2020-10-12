package org.izmaylovalexey

import mu.KLogging
import org.awaitility.Awaitility
import org.izmaylovalexey.services.Error
import org.izmaylovalexey.services.Failure
import org.izmaylovalexey.services.Result
import org.izmaylovalexey.services.Success
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.fail
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.support.TestPropertySourceUtils
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy
import org.testcontainers.lifecycle.Startable
import org.testcontainers.lifecycle.Startables
import java.net.ServerSocket
import java.time.Duration
import java.util.stream.Stream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApplicationTest {

    private companion object : KLogging() {

        private val registry: String = System.getProperty("registry", "")

        val mongo = GenericContainer<Nothing>("${registry}mongo:4.2.6").apply {
            withExposedPorts(27017)
        }

        val keycloak = GenericContainer<Nothing>("${registry}jboss/keycloak:10.0.1").apply {
            withExposedPorts(8080)
            withEnv("DB_VENDOR", "h2")
            withEnv("KEYCLOAK_USER", "keycloak")
            withEnv("KEYCLOAK_PASSWORD", "keycloak")
            waitingFor(
                HttpWaitStrategy()
                    .forPath("/auth/")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(5))
            )
        }

        init {
            Startables.deepStart(Stream.of<Startable>(mongo, keycloak)).join()
        }
    }

    class PropertyOverrideContextInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {

        override fun initialize(applicationContext: ConfigurableApplicationContext) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                applicationContext,
                "spring.data.mongodb.uri=mongodb://${mongo.containerIpAddress}:${mongo.firstMappedPort}/tenant-security",
                "keycloak.uri=http://${keycloak.containerIpAddress}:${keycloak.firstMappedPort}/auth",
                "keycloak.password=keycloak"
            )
        }
    }

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
                "--server.port=$port",
                "--spring.data.mongodb.uri=mongodb://${mongo.containerIpAddress}:${mongo.firstMappedPort}/tenant-security",
                "--keycloak.uri=http://${keycloak.containerIpAddress}:${keycloak.firstMappedPort}/auth",
                "--keycloak.password=keycloak"
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
}

fun <T> Result<T>.unwrap(): T = when (this) {
    is Success -> value
    is Failure -> when (error) {
        is Error.Exception -> fail(error.exception)
        else -> fail(error::class.simpleName)
    }
}
