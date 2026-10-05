package nl.vanvrouwerff.iptv.data.repo

import kotlinx.coroutines.CancellationException

/** Cancellation stops the operation instead of becoming a provider failure or fallback. */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
