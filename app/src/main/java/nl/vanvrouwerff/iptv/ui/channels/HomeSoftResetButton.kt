package nl.vanvrouwerff.iptv.ui.channels

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.vanvrouwerff.iptv.IptvApp
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette

/**
 * Compact recovery action on the catalogue home screen. It only clears disposable
 * HTTP/image caches and forces a fresh catalogue download; user data stays untouched.
 */
@Composable
fun BoxScope.HomeSoftResetButton() {
    val app = IptvApp.get()
    val refreshing by app.refreshUseCase.refreshing.collectAsState()
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var messageRes by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 28.dp, bottom = 58.dp),
        horizontalAlignment = Alignment.End,
    ) {
        messageRes?.let { res ->
            Text(
                text = stringResource(res),
                style = MaterialTheme.typography.labelSmall,
                color = IptvPalette.TextSecondary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Button(
            enabled = !refreshing && !running,
            onClick = {
                if (refreshing || running) return@Button
                running = true
                messageRes = null
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) { app.clearTransientCaches() }
                        app.refreshUseCase(force = true).getOrThrow()
                    }
                    messageRes = if (result.isSuccess) {
                        R.string.settings_soft_reset_done
                    } else {
                        R.string.settings_maintenance_failed
                    }
                    running = false
                    delay(3_500L)
                    messageRes = null
                }
            },
        ) {
            Text(
                text = if (running || refreshing) {
                    stringResource(R.string.status_refreshing)
                } else {
                    stringResource(R.string.settings_soft_reset)
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}
