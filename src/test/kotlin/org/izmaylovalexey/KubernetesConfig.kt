package org.izmaylovalexey

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import me.snowdrop.istio.api.rbac.v1alpha1.ServiceRole
import me.snowdrop.istio.client.DefaultIstioClient
import me.snowdrop.istio.client.IstioClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class KubernetesConfig {

    @Bean
    fun kubernetesServer(): KubernetesServer {
        val kubernetes = KubernetesServer(true, true)
        kubernetes.before()
        return kubernetes
    }

    @Bean
    fun istioClient(kubernetes: KubernetesServer): IstioClient {
        val istioClient = DefaultIstioClient(kubernetes.client.configuration)
        val mapper = YAMLMapper().registerKotlinModule()
        setOf("developer-role.yaml", "maintainer-role.yaml", "owner-role.yaml").forEach {
            istioClient.serviceRole().createOrReplace(
                mapper.readValue<ServiceRole>(javaClass.classLoader.getResource(it)!!)
            )
        }
        return istioClient
    }
}
