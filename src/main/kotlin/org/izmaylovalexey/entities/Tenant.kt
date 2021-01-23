package org.izmaylovalexey.entities

data class Tenant(
    val name: String,
    val displayedName: String = name,
    val description: String = ""
)
