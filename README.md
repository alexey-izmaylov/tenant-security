# Tenant Security
Microservice handles role-based access control (RBAC) policies for multi-tenant environment.

## Architecture
Tenant reflects a resource group.
Several roles can be related to one resource group.
For HTTP, gRPC services it means different paths (`/tenant/{name}/*`, `*/tenant/{name}/sample/path`), hosts, methods, etc.
Request with token is filtered by authentication and authorization policies when user is trying to access tenant resource.

Tenant-security allows to:
1. create tenant with set of roles
2. create user
3. assign user to tenant with specific role.

This microservice builds all necessary entities under the hood.
Tenant is represented as:
- Istio Role (many)
- Keycloak Role (many)
- Keycloak Group (one)

Each tenant has the same set of granular roles (at least one), that should be defined as templates by the application operator.

### Application start
At the start application
1. loads available Istio roles having `type=tenant-template` label or default [ServiceRole.yaml](src/main/resources/ServiceRole.yaml)
2. creates realm, client in Keycloak
3. configures Keycloak to include user roles into token that is validated by Istio sidecars. 
4. optionally creates initial user and role in Keycloak

### Design
Future improvements can bring more flexible solutions:
- Istio and Keycloak (present)
- Istio and other identity providers
- Gateway with authentication/authorization and identity provider
- Test mocks

Every mode can be defined by Spring profile.
It consists of necessary service beans.
Each service operation leads to stream processing of corresponding beans.
Spring finds interface implementations for selected profile and injects them into collections.
Services can be separate modules with Spring Boot auto-configuration.

For example, in the istio-keycloak profile you have IstioRole, KeycloakRole beans as Role parts, and Role creation means that all parts should be created in any order. 

## API
### REST
See [Specification](docs/index.html).

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
| INIT_USER_EMAIL      | -                                         | Email of initial user |
| INIT_USER_PASSWORD   | -                                         | Password of initial user |
| INIT_USER_ROLE       | -                                         | Role of initial user |
| DEFAULT_ROLE         | owner                                     | In the 'user context' API new tenant is created and assigned with this role |

## Build
Required: JDK 11, Docker, Maven.

### Compile, execute tests, generate documentation and package jar
```shell script
mvn clean install
```
Application will be assembled as JAR in `target/` directory, e.g. `target/tenant-security-1.0.0-SNAPSHOT.jar`.

Actual docs are generated:
- AsciiDoc `target/generated-snippets/`
- HTML `target/generated-docs/index.html`
- OpenAPI `target/restdocs-spec/openapi-3.0.yml`.

### Build docker image
```shell script
docker build .
```