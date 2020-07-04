package org.izmaylovalexey.entities

import java.util.UUID

data class Tenant(
    val name: String = UUID.randomUUID().toString(),
    val displayedName: String = name,
    val description: String = ""
)
