package nl.vanvrouwerff.iptv.ui.channels

import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.vanvrouwerff.iptv.IptvApp
import nl.vanvrouwerff.iptv.R

/**
 * Compact recovery action placed directly in the catalogue top bar beside the I-TV name.
 * It clears only disposable caches and forces a fresh catalogue download.
 */
@Composable
fun HomeSoftResetButton() {
    val app = IptvApp.get()
    val context = LocalContext.current
    val refreshing by app.refreshUseCase.refreshing.collectAsState()
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }

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
                    context.getString(
                        if (result.isSuccess) R.string.settings_soft_reset_done
                        else R.string.settings_maintenance_failed,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
                running = false
            }
        },
    ) {
        Text(
            text = if (running || refreshing) {
                stringResource(R.string.status_refreshing)
            } else {
                stringResource(R.string.settings_soft_reset)
            },
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}
