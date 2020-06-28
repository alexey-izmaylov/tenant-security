package org.izmaylovalexey.services.istio

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.fabric8.kubernetes.api.model.ObjectMeta
import me.snowdrop.istio.api.rbac.v1alpha1.ServiceRole
import me.snowdrop.istio.api.rbac.v1alpha1.ServiceRoleBinding
import me.snowdrop.istio.client.IstioClient
import mu.KLogging
import org.izmaylovalexey.entities.Tenant
import org.izmaylovalexey.services.RoleService
import org.izmaylovalexey.services.RoleTemplate
import org.springframework.stereotype.Service

@Service
internal class IstioRole(val istioClient: IstioClient) : RoleService, RoleTemplate {

    private val mapper = YAMLMapper().registerKotlinModule()

    override suspend fun apply(tenant: Tenant, role: String) {
        val name = "${tenant.name}.$role"
        val serviceRole = when {
            role.isNotEmpty() -> istioClient.serviceRole().withName(role).get()
            else -> mapper.readValue<ServiceRole>(javaClass.classLoader.getResource("ServiceRole.yaml")!!)
        }.apply {
            metadata = ObjectMeta()
            metadata.name = name
            spec.rules.onEach {
                it.paths = it.paths.map { path -> path.replace("{tenant}", tenant.name) }
            }
        }
        logger.info { "will create $serviceRole" }

        val serviceRoleBinding = mapper.readValue<ServiceRoleBinding>(
            javaClass.classLoader.getResource("ServiceRoleBinding.yaml")!!
        ).apply {
            metadata.name = name
            spec.roleRef.name = name
            spec.subjects.onEach { it.properties.replace("request.auth.claims[roles]", tenant.name) }
        }
        logger.info { "will create $serviceRoleBinding" }

        istioClient.serviceRole().createOrReplace(serviceRole)
        istioClient.serviceRoleBinding().createOrReplace(serviceRoleBinding)
    }

    override suspend fun delete(tenant: String, role: String) {
        val name = "$tenant.$role"
        logger.info { "will delete $name ServiceRole" }
        istioClient.serviceRole().withName(name).delete()

        logger.info { "will delete $name ServiceRoleBinding" }
        istioClient.serviceRoleBinding().withName(name).delete()
    }

    override fun all(): Set<String> = istioClient
        .serviceRole()
        .withLabel("type", "tenant-template")
        .list()
        .items
        .map { it.metadata.name }
        .toSet()
        .ifEmpty { setOf("") }

    companion object : KLogging()
}