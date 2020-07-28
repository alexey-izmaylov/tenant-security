package org.izmaylovalexey.services.istio

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.fabric8.kubernetes.api.model.ObjectMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEmpty
import me.snowdrop.istio.api.rbac.v1alpha1.ServiceRole
import me.snowdrop.istio.api.rbac.v1alpha1.ServiceRoleBinding
import me.snowdrop.istio.client.IstioClient
import mu.KLogging
import org.izmaylovalexey.entities.Success
import org.izmaylovalexey.entities.Tenant
import org.izmaylovalexey.entities.toFailure
import org.izmaylovalexey.services.RoleService
import org.izmaylovalexey.services.RoleTemplate
import org.springframework.stereotype.Service

@Service
internal class IstioRole(val istioClient: IstioClient) : RoleService, RoleTemplate {

    private val mapper = YAMLMapper().registerKotlinModule()

    override suspend fun apply(tenant: Tenant, role: String) = runCatching {
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
        logger.info { "will create ServiceRole $serviceRole" }

        val serviceRoleBinding = mapper.readValue<ServiceRoleBinding>(
            javaClass.classLoader.getResource("ServiceRoleBinding.yaml")!!
        ).apply {
            metadata.name = name
            spec.roleRef.name = name
            spec.subjects.onEach { it.properties.replace("request.auth.claims[roles]", tenant.name) }
        }
        logger.info { "will create ServiceRoleBinding $serviceRoleBinding" }

        istioClient.serviceRole().createOrReplace(serviceRole)
        istioClient.serviceRoleBinding().createOrReplace(serviceRoleBinding)
        Success(Unit)
    }.getOrElse { it.toFailure() }

    override suspend fun delete(tenant: String, role: String) = runCatching {
        val name = "$tenant.$role"
        logger.info { "will delete ServiceRole $name" }
        istioClient.serviceRole().withName(name).delete()

        logger.info { "will delete ServiceRoleBinding $name" }
        istioClient.serviceRoleBinding().withName(name).delete()
        Success(Unit)
    }.getOrElse { it.toFailure() }

    override suspend fun all(): Flow<String> = istioClient
        .serviceRole()
        .withLabel("type", "tenant-template")
        .list()
        .items
        .asFlow()
        .map { it.metadata.name }
        .onEmpty { emit("") }

    private companion object : KLogging()
}
