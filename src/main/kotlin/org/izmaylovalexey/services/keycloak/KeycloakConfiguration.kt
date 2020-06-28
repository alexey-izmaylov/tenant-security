package org.izmaylovalexey.services.keycloak

import io.fabric8.kubernetes.client.KubernetesClient
import mu.KLogging
import org.keycloak.admin.client.Keycloak
import org.keycloak.representations.idm.ClientRepresentation
import org.keycloak.representations.idm.RealmRepresentation
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.Base64

@Configuration
class KeycloakConfiguration {

    @Bean
    fun keycloak(keycloakProperties: KeycloakProperties, kubernetesClient: KubernetesClient): Keycloak {
        logger.info {
            """
            Keycloak properties:
            uri=${keycloakProperties.uri}
            realm=${keycloakProperties.realm}
            client=${keycloakProperties.client}
            secret=${keycloakProperties.secret}
            secretKey=${keycloakProperties.secretKey}
            """.trimIndent()
        }
        val password = if (keycloakProperties.password.isBlank()) {
            val base64 = kubernetesClient.secrets()
                .withName(keycloakProperties.secret)
                .get()
                .data[keycloakProperties.secretKey]
            String(Base64.getDecoder().decode(base64))
        } else keycloakProperties.password
        val keycloak = Keycloak.getInstance(
            keycloakProperties.uri,
            "master",
            "keycloak",
            password,
            "admin-cli"
        )
        createRealm(keycloak, keycloakProperties.realm)
        createClient(keycloak, keycloakProperties.realm, keycloakProperties.client)
        return keycloak
    }

    private fun createRealm(keycloak: Keycloak, realm: String) {
        val realms = keycloak.realms().findAll().map { it.id }
        logger.info { "realms: " + realms.joinToString() }
        if (realm !in realms) {
            logger.info { "will create realm: $realm" }
            val realmRepresentation = RealmRepresentation()
            realmRepresentation.id = realm
            realmRepresentation.realm = realm
            realmRepresentation.isEnabled = true
            keycloak.realms().create(realmRepresentation)
        }

        val clientScope = keycloak.realm(realm)
            .clientScopes()
            .findAll()
            .first { it.name == "roles" }
        val protocolMapper = clientScope.protocolMappers
            .filter { it.name == "realm roles" }
            .onEach { it.config["claim.name"] = "roles" }
            .first()
        logger.info { "update protocolMapper: ${protocolMapper.id} of clientScope: ${clientScope.id}" }
        keycloak.realm(realm)
            .clientScopes()
            .get(clientScope.id)
            .protocolMappers
            .update(protocolMapper.id, protocolMapper)
    }

    private fun createClient(keycloak: Keycloak, realm: String, clientId: String) {
        val clientRepresentation = ClientRepresentation()
        clientRepresentation.clientId = clientId
        clientRepresentation.isEnabled = true
        clientRepresentation.clientAuthenticatorType = "client-secret"
        clientRepresentation.protocol = "openid-connect"
        clientRepresentation.isPublicClient = true
        clientRepresentation.isStandardFlowEnabled = true
        clientRepresentation.isDirectAccessGrantsEnabled = true
        logger.info { "will create client: $clientId" }
        keycloak.realm(realm).clients().create(clientRepresentation)
    }

    companion object : KLogging()
}

@ConfigurationProperties("keycloak")
@ConstructorBinding
class KeycloakProperties(
    val uri: String = "",
    val realm: String = "",
    val client: String = "",
    val secret: String = "",
    val secretKey: String = "",
    val password: String = ""
)

@ConfigurationProperties("initial.user")
@ConstructorBinding
class InitialUserProperties(
    val email: String = "",
    val password: String = "",
    val role: String = ""
)