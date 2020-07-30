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
import okhttp3.mockwebserver.MockWebServer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ConcurrentHashMap

@Configuration
class KubernetesConfig {

    @Bean
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

    @Bean
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
