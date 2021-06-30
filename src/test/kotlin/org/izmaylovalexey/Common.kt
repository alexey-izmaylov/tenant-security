package org.izmaylovalexey

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.fabric8.kubernetes.client.NamespacedKubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import me.snowdrop.istio.api.rbac.v1alpha1.ServiceRole
import me.snowdrop.istio.client.DefaultIstioClient
import me.snowdrop.istio.client.IstioClient
import mu.KLogging
import okhttp3.mockwebserver.MockWebServer
import org.izmaylovalexey.services.Error
import org.izmaylovalexey.services.Failure
import org.izmaylovalexey.services.Result
import org.izmaylovalexey.services.Success
import org.junit.jupiter.api.fail
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.support.GenericApplicationContext
import org.springframework.test.context.support.TestPropertySourceUtils
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy
import org.testcontainers.lifecycle.Startables
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Stream

internal object Integration : KLogging() {

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
        Startables.deepStart(Stream.of(mongo, keycloak)).join()
    }

    class SpringInitializer : ApplicationContextInitializer<GenericApplicationContext> {

        override fun initialize(applicationContext: GenericApplicationContext) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                applicationContext,
                "spring.data.mongodb.uri=mongodb://${mongo.containerIpAddress}:${mongo.firstMappedPort}/tenant-security",
                "keycloak.uri=http://${keycloak.containerIpAddress}:${keycloak.firstMappedPort}/auth",
                "keycloak.password=keycloak"
            )
            beans().apply {
                initialize(applicationContext)
                bean(::kubernetesClient)
                bean(::istioClient)
            }
        }
    }

    fun kubernetesClient(): NamespacedKubernetesClient {
        val server = KubernetesMockServer(
            Context(),
            MockWebServer(),
            ConcurrentHashMap(),
            object : KubernetesCrudDispatcher() {
                init {
                    map = ConcurrentHashMap()
                }
            },
            true
        )
        server.init()
        return server.createClient()
    }

    fun istioClient(kubernetesClient: NamespacedKubernetesClient): IstioClient {
        val istioClient = DefaultIstioClient(kubernetesClient.configuration)
        val mapper = YAMLMapper().registerKotlinModule()
        setOf("developer-role.yaml", "maintainer-role.yaml", "owner-role.yaml").forEach {
            istioClient.serviceRole().createOrReplace(
                mapper.readValue<ServiceRole>(javaClass.classLoader.getResource(it)!!)
            )
        }
        return istioClient
    }
}

fun <T> Result<T>.unwrap(): T = when (this) {
    is Success -> value
    is Failure -> when (error) {
        is Error.Exception -> fail(error.exception)
        is Error.Message -> fail(error.message)
        else -> fail(error::class.simpleName)
    }
}
