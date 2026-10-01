package nl.vanvrouwerff.iptv.data.repo

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

/** Pure decision used before replacing a known-good catalogue partition. */
data class SectionDecision(val accept: Boolean, val reason: String? = null)

object PlaylistResilience {
    private const val MIN_BASELINE_FOR_RATIO_GUARD = 20
    private const val MIN_RETAINED_PERCENT = 25

    /**
     * Same source: never replace a non-empty known-good partition with zero or a sudden
     * >75% collapse. A deliberately changed source is authoritative and may be smaller.
     */
    fun assessSection(existingCount: Int, incomingCount: Int, sourceChanged: Boolean): SectionDecision {
        if (sourceChanged) return SectionDecision(true)
        if (existingCount <= 0) return SectionDecision(true)
        if (incomingCount <= 0) {
            return SectionDecision(false, "0 statt $existingCount Einträgen")
        }
        if (existingCount >= MIN_BASELINE_FOR_RATIO_GUARD &&
            incomingCount * 100 < existingCount * MIN_RETAINED_PERCENT
        ) {
            return SectionDecision(false, "$incomingCount statt $existingCount Einträgen")
        }
        return SectionDecision(true)
    }

    fun isRetryableHttp(code: Int): Boolean = code == 408 || code == 425 || code == 429 || code >= 500
}

/** Short foreground retry budget; WorkManager provides the longer retry horizon afterwards. */
internal suspend fun <T> retryProviderCall(
    label: String,
    delaysMs: LongArray = longArrayOf(1_500L, 5_000L),
    block: suspend () -> T,
): T {
    var last: Throwable? = null
    for (attempt in 0..delaysMs.size) {
        try {
            return block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            last = t
            val retryable = when (t) {
                is HttpException -> PlaylistResilience.isRetryableHttp(t.code())
                is IOException -> true
                is SerializationException -> true // truncated/half-written provider JSON
                else -> false
            }
            if (!retryable || attempt >= delaysMs.size) throw t
            val waitMs = delaysMs[attempt]
            Log.w("PlaylistRetry", "$label failed; retry ${attempt + 1}/${delaysMs.size} in ${waitMs}ms")
            delay(waitMs)
        }
    }
    throw last ?: IllegalStateException("Provider request failed")
}
