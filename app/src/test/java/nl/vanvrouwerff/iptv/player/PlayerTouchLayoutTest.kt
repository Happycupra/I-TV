package nl.vanvrouwerff.iptv.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.ui.theme.IptvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlayerTouchLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun errorExitRemainsReachableOnASmallPhone() {
        var exits = 0
        val label = RuntimeEnvironment.getApplication().getString(R.string.player_error_exit)
        compose.setContent {
            IptvTheme {
                Box(Modifier.size(width = 320.dp, height = 240.dp)) {
                    ErrorOverlay(
                        state = ErrorState("Channel", "Connection unavailable. ".repeat(12), canSkip = true),
                        onRetry = {}, onSkip = {}, onExit = { exits++ },
                    )
                }
            }
        }
        compose.onNodeWithText(label).performScrollTo().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, exits) }
    }
}
