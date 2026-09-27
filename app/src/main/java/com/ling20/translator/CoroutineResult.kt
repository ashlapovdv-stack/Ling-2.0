package com.ling20.translator

import kotlinx.coroutines.CancellationException

/**
 * Coroutine-aware replacement for Kotlin's default runCatching inside this package.
 *
 * Compose cancels LaunchedEffect whenever one of its keys changes or the effect
 * leaves composition. Cancellation is normal control flow and must never be
 * converted into an OCR/translation error banner.
 *
 * Some Compose versions surface their internal "left the composition" cancellation
 * through an implementation-specific throwable. Normalize that message back to a
 * CancellationException so callers never treat it as a user-visible failure.
 */
internal inline fun <R> runCatching(block: () -> R): Result<R> = try {
    Result.success(block())
} catch (error: Throwable) {
    if (error is CancellationException || error.isComposeScopeCancellation()) {
        val cancellation = if (error is CancellationException) {
            error
        } else {
            CancellationException(error.message ?: "Coroutine cancelled").also {
                it.initCause(error)
            }
        }
        throw cancellation
    }
    Result.failure(error)
}

private fun Throwable.isComposeScopeCancellation(): Boolean {
    val value = message.orEmpty().lowercase()
    return value.contains("coroutine scope left the composition") ||
        value.contains("left the composition")
}
