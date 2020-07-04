package org.izmaylovalexey.entities

data class SecurityContext(
    val user: User,
    val tenants: Set<Tenant>
)
