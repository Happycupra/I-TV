package nl.vanvrouwerff.iptv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.util.Calendar
import kotlinx.coroutines.delay
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette

@Composable
internal fun RefreshCountdown(hour: Int) {
    var remainingMs by remember(hour) { mutableLongStateOf(millisUntilNextRefresh(hour)) }
    LaunchedEffect(hour) {
        while (true) {
            remainingMs = millisUntilNextRefresh(hour)
            delay(1_000L)
        }
    }
    val seconds = (remainingMs / 1_000L).coerceAtLeast(0L)
    val h = seconds / 3_600L
    val m = (seconds % 3_600L) / 60L
    val s = seconds % 60L
    val locale = LocalConfiguration.current.locales[0]
    val value = String.format(locale, "%02d:%02d:%02d", h, m, s)
    Text(
        text = stringResource(R.string.settings_auto_refresh_countdown, value),
        style = MaterialTheme.typography.bodySmall,
        color = IptvPalette.AccentSoft,
    )
}

private fun millisUntilNextRefresh(hour: Int): Long {
    val now = Calendar.getInstance()
    val target = (now.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
    }
    return (target.timeInMillis - now.timeInMillis).coerceAtLeast(0L)
}
