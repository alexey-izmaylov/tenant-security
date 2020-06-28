package org.izmaylovalexey.entities

data class Assignment(
    val tenant: String,
    val user: User,
    val roles: Set<String>
)