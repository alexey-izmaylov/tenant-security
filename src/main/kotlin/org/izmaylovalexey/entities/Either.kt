package org.izmaylovalexey.entities

import org.slf4j.Logger

sealed class Error {
    object NotFound : Error()
    object EmailCollision : Error()
    class Exception(val exception: Throwable) : Error()
    class Message(val message: String) : Error()
}

sealed class Either<out T>

data class Success<T>(val value: T) : Either<T>()

data class Failure(val error: Error) : Either<Nothing>() {
    fun log(logger: Logger, message: String) {
        when (error) {
            is Error.Message -> logger.error("{} {}", message, error.message)
            is Error.Exception -> logger.error(message, error.exception)
            else -> logger.error("{} {}", message, error::class.simpleName)
        }
    }
}

fun Throwable.toFailure() = Failure(Error.Exception(this))
