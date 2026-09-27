package com.ling20.translator

import kotlinx.coroutines.CancellationException

/**
 * Coroutine-aware replacement for Kotlin's default runCatching inside this package.
 *
 * Compose cancels LaunchedEffect whenever one of its keys changes or the effect
 * leaves composition. Cancellation is normal control flow and must never be
 * converted into an OCR/translation error banner.
 */
internal inline fun <R> runCatching(block: () -> R): Result<R> = try {
    Result.success(block())
} catch (error: Throwable) {
    if (error is CancellationException) throw error
    Result.failure(error)
}
