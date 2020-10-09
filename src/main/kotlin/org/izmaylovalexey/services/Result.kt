package org.izmaylovalexey.services

import org.slf4j.Logger

sealed class Result<out T>

internal class Success<T>(val value: T) : Result<T>()

internal class Failure(val error: Error) : Result<Nothing>() {

    fun log(logger: Logger, message: String) {
        when (error) {
            is Error.Message -> logger.error("{} {}", message, error.message)
            is Error.Exception -> logger.error(message, error.exception)
            else -> logger.error("{} {}", message, error::class.simpleName)
        }
    }
}

internal sealed class Error {
    object NotFound : Error()
    object EmailCollision : Error()
    class Exception(val exception: Throwable) : Error()
    class Message(val message: String) : Error()
}

internal fun Throwable.toFailure() = Failure(Error.Exception(this))
