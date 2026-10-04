package nl.vanvrouwerff.iptv.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import nl.vanvrouwerff.iptv.ui.theme.IptvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TouchControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `touch button taps trigger exactly one action`() {
        var clicks = 0
        compose.setContent {
            IptvTheme {
                TouchButton(onClick = { clicks++ }, modifier = Modifier.testTag("button")) { Text("Play") }
            }
        }
        compose.onNodeWithTag("button").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun `long pressing a card opens its menu without playing`() {
        var clicks = 0
        var longClicks = 0
        compose.setContent {
            IptvTheme {
                TouchSurface(
                    onClick = { clicks++ }, onLongClick = { longClicks++ },
                    modifier = Modifier.size(100.dp).testTag("card"),
                ) { Text("Channel") }
            }
        }
        compose.onNodeWithTag("card").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(0, clicks); assertEquals(1, longClicks) }
    }

    @Test fun `disabled controls ignore touches`() {
        var clicks = 0
        compose.setContent {
            IptvTheme {
                Column {
                    TouchButton(onClick = { clicks++ }, enabled = false, modifier = Modifier.testTag("button")) { Text("Disabled") }
                    TouchSurface(onClick = { clicks++ }, enabled = false, modifier = Modifier.size(100.dp).testTag("card")) { Text("Preview") }
                }
            }
        }
        compose.onNodeWithTag("button").performTouchInput { click() }
        compose.onNodeWithTag("card").performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, clicks) }
    }

    @Test fun `scrolling over a channel card does not select it`() {
        var clicks = 0
        compose.setContent {
            IptvTheme {
                Column(Modifier.height(200.dp).verticalScroll(rememberScrollState())) {
                    repeat(8) { index ->
                        TouchSurface(onClick = { clicks++ }, modifier = Modifier.size(150.dp).testTag("card-$index")) { Text("Channel $index") }
                    }
                }
            }
        }
        compose.onNodeWithTag("card-0").performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(0, clicks) }
    }

    @Test fun `recomposed callbacks are used by the next tap`() {
        val selected = mutableStateOf(1)
        var value = 0
        compose.setContent {
            val current = selected.value
            IptvTheme {
                TouchButton(onClick = { value = current }, modifier = Modifier.testTag("button")) { Text("Select") }
            }
        }
        compose.runOnIdle { selected.value = 2 }
        compose.onNodeWithTag("button").performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, value) }
    }

    @Test fun `remote enter still triggers exactly one action`() {
        var clicks = 0
        val focus = androidx.compose.ui.focus.FocusRequester()
        compose.setContent {
            IptvTheme {
                TouchButton(
                    onClick = { clicks++ },
                    modifier = Modifier.testTag("button").focusRequester(focus),
                ) { Text("Play") }
            }
        }
        compose.runOnIdle { focus.requestFocus() }
        compose.onNodeWithTag("button").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun `small phone controls have a visible tappable 56 dp target`() {
        var clicks = 0
        compose.setContent {
            IptvTheme {
                Row {
                    TouchButton(onClick = { clicks++ }, modifier = Modifier.size(32.dp).testTag("button")) { Text("+") }
                    TouchSurface(onClick = { clicks++ }, modifier = Modifier.size(40.dp).testTag("surface")) { Text("1") }
                }
            }
        }
        listOf("button", "surface").forEach { tag ->
            compose.onNodeWithTag(tag)
                .assertWidthIsAtLeast(56.dp)
                .assertHeightIsAtLeast(56.dp)
                .performTouchInput { click(bottomRight - Offset(1f, 1f)) }
        }
        compose.runOnIdle { assertEquals(2, clicks) }
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `landscape phones keep the compact touch layout`() {
        var compact = false
        compose.setContent {
            compact = isCompactTouchLayout()
            IptvTheme {
                TouchSurface(onClick = {}, modifier = Modifier.size(40.dp).testTag("surface")) { Text("1") }
            }
        }
        compose.runOnIdle { assertEquals(true, compact) }
        compose.onNodeWithTag("surface").assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(56.dp)
    }

    @Test
    @Config(qualifiers = "television")
    fun `TV controls preserve their requested size and layout`() {
        var compact = true
        compose.setContent {
            compact = isCompactTouchLayout()
            IptvTheme {
                TouchSurface(onClick = {}, modifier = Modifier.size(40.dp).testTag("surface")) { Text("1") }
            }
        }
        compose.runOnIdle { assertEquals(false, compact) }
        compose.onNodeWithTag("surface").assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(40.dp)
    }

    @Test fun `PIN entry remains reachable in a short landscape viewport`() {
        var entered = ""
        compose.setContent {
            IptvTheme {
                Box(Modifier.size(width = 320.dp, height = 280.dp)) {
                    nl.vanvrouwerff.iptv.ui.parental.PinPad(
                        title = "PIN", error = null,
                        onComplete = { entered = it }, onCancel = {},
                    )
                }
            }
        }
        listOf("1", "2", "3", "0").forEach { digit ->
            compose.onNodeWithText(digit).performScrollTo().performTouchInput { click() }
        }
        compose.runOnIdle { assertEquals("1230", entered) }
    }
}
