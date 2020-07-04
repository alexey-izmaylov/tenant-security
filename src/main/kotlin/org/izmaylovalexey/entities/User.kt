package org.izmaylovalexey.entities

import java.util.UUID

data class User(
    val id: String = UUID.randomUUID().toString(),
    val email: String,
    val firstName: String,
    val lastName: String,
    @Transient val credential: String
)
