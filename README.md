[![simple build status](https://github.com/alexey-izmaylov/tenant-security/workflows/build/badge.svg)](https://github.com/alexey-izmaylov/tenant-security/actions?query=workflow%3Abuild)

# Tenant Security

The microservice handles role-based access control (RBAC) policies for multi-tenant environment.

## Architecture

Tenant is a resource group.

In case of HTTP, gRPC services it signifies different hosts,
paths (`/tenant/{name}/*`,`*/tenant/{name}/sample/path`),
methods (`GET`, `PUT`, `DELETE`), etc.
It conforms to REST.

Certainly, tenant requires security.
Several roles can be related to resource group:
to create or modify one resource and to only read others.

Tenant-security allows to:

1. create the tenant having set of roles
2. create user
3. assign user to tenant with the specific role.

![service-integration.puml](http://www.plantuml.com/plantuml/proxy?cache=no&src=https://github.com/alexey-izmaylov/tenant-security/raw/master/docs/service-integration.puml)

All communications pass through Istio sidecars validating JSON Web Tokens.
JWTs are forged by Keycloak identity provider.
Request with token will be filtered by authentication and authorization policies
when user tries to access tenant resource.

This microservice builds all necessary entities under the hood.
Tenant is represented as:

- Istio ServiceRole (many)
- Istio ServiceRoleBinding (many)
- Keycloak Role (many)
- Keycloak Group (one).

Each tenant has the same set of granular roles (at least one).
To create these roles tenant-security has to use ServiceRole marked with `type=tenant-template` label as template.
Such templates should be defined by application operator.
Template can also contain `{tenant}` in path strings which will be replaced by actual tenant name.

### Application start

During the initialization tenant-security

- loads available Istio ServiceRole templates or default [ServiceRole.yaml](src/main/resources/ServiceRole.yaml)
- creates the realm, client in Keycloak
- configures Keycloak to include user roles into token
- optionally creates initial user and role in Keycloak.

### Design

Future improvements can bring more flexible solutions:

1. Istio and Keycloak (present)
2. Istio and other identity providers
3. Gateway with authentication/authorization and identity provider
4. Test mocks.

Every mode can be declared by Spring profile.
It consists of necessary service beans.
Each service operation leads to stream processing of corresponding beans.
Spring finds interface implementations for selected profile and injects them into collections.
Services can be separate modules with Spring Boot auto-configuration.

For example, in the istio-keycloak profile you have IstioRole, KeycloakRole beans as Role parts,
and Role creation means that all parts should be created in any order.

## API

### REST

See [Specification](https://alexey-izmaylov.github.io/tenant-security/).

### Metrics

Simple health check: /health or /

Spring Boot actuator: /actuator

## Properties

| Environment variable | Default                                   | Description |
| -------------------- | ----------------------------------------- |:-----------:|
| KEYCLOAK_URI         | http://keycloak-http:80/auth              | Keycloak REST API |
| KEYCLOAK_SECRET      | keycloak-http                             | Name of Kubernetes secret with Keycloak password |
| KEYCLOAK_SECRET_KEY  | password                                  | Key of Kubernetes secret with Keycloak password |
| KEYCLOAK_REALM       | istio                                     | Keycloak realm to manage security entities |
| KEYCLOAK_CLIENT      | frontend                                  | Keycloak client to manage security entities |
| MONGO_URI            | mongodb://localhost:27017/tenant-security | MongoDB connection string |
| INIT_USER_EMAIL      |                                           | Email of initial user |
| INIT_USER_PASSWORD   |                                           | Password of initial user |
| INIT_USER_ROLE       |                                           | Role of initial user |
| DEFAULT_ROLE         | owner                                     | With this role new tenant is created and assigned by the user context API |

## Build

### JVM JAR

> Required: JDK 11, Docker

```shell script
./mvnw install
```

It will:

1. format sources
2. compile sources
3. execute tests
4. generate documentations
5. package jars
6. lint sources
7. check dependencies.

The application is assembled as jar in `target` directory,
e.g. `target/tenant-security-1.0.0-SNAPSHOT.jar`.

Actual docs are generated:

- AsciiDoc `target/generated-snippets/`
- HTML `target/generated-docs/index.html`
- OpenAPI `target/restdocs-spec/openapi-3.0.yml`.

### Docker image

> Required: Docker

```shell script
docker build -t tenant-security:1.0.0 .
```
