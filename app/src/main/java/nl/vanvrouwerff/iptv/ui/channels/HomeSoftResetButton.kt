package nl.vanvrouwerff.iptv.ui.channels

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.vanvrouwerff.iptv.ui.common.TouchButton as Button
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.vanvrouwerff.iptv.IptvApp
import nl.vanvrouwerff.iptv.R

/** Compact provider-health indicator + safe recovery action beside the I-TV name. */
@Composable
fun HomeSoftResetButton() {
    val app = IptvApp.get()
    val context = LocalContext.current
    val refreshing by app.refreshUseCase.refreshing.collectAsState()
    val progress by app.refreshUseCase.progress.collectAsState()
    val failureStreak by app.settings.providerFailureStreak.collectAsState(initial = 0)
    val lastError by app.settings.lastProviderError.collectAsState(initial = "")
    val liveCount by app.settings.lastLiveCount.collectAsState(initial = 0)
    val movieCount by app.settings.lastMoviesCount.collectAsState(initial = 0)
    val seriesCount by app.settings.lastSeriesCount.collectAsState(initial = 0)
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }

    val stage = progress?.let { "${it.stage}: ${it.count}" }.orEmpty()
    val noErrorMessage = stringResource(R.string.playlist_health_no_error)
    val healthDetail = stringResource(
        R.string.playlist_health_detail,
        liveCount, movieCount, seriesCount, failureStreak,
        lastError.ifBlank { noErrorMessage }, stage,
    )
    val resetDoneMessage by rememberUpdatedState(stringResource(R.string.settings_soft_reset_done))
    val recoveryPreservedMessage by rememberUpdatedState(stringResource(R.string.playlist_recovery_preserved))

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = {
            Toast.makeText(context, healthDetail, Toast.LENGTH_LONG).show()
        }) {
            Text(
                text = when {
                    refreshing || running -> stringResource(R.string.playlist_health_refreshing)
                    failureStreak > 0 && liveCount + movieCount + seriesCount > 0 -> stringResource(R.string.playlist_health_cached)
                    failureStreak > 0 -> stringResource(R.string.playlist_health_problem)
                    liveCount + movieCount + seriesCount > 0 -> stringResource(R.string.playlist_health_online)
                    else -> stringResource(R.string.playlist_health_ready)
                },
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }

        Button(
            enabled = !refreshing && !running,
            onClick = {
                if (refreshing || running) return@Button
                running = true
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) { app.clearTransientCaches() }
                        app.refreshUseCase(force = true).getOrThrow()
                    }
                    Toast.makeText(
                        context,
                        if (result.isSuccess) resetDoneMessage else recoveryPreservedMessage,
                        Toast.LENGTH_SHORT,
                    ).show()
                    running = false
                }
            },
        ) {
            Text(
                text = if (running || refreshing) stringResource(R.string.status_refreshing)
                else stringResource(R.string.settings_soft_reset),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}
