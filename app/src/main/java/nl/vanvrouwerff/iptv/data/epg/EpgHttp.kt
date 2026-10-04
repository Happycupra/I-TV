package nl.vanvrouwerff.iptv.data.epg

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/** EPG transfers can be large; cancelling a refresh must cancel the HTTP request too. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun Call.awaitEpgResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { response.close() }
        }
    })
}

/** Keep cancellation attached after headers arrive, while the parser is reading the response body. */
internal suspend fun <T> Call.withEpgResponse(block: (Response) -> T): T = coroutineScope {
    val call = this@withEpgResponse
    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try { awaitEpgResponse().use(block) } finally { cancellation.cancel() }
}
