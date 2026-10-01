package nl.vanvrouwerff.iptv.ui.common

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Android TV text fields should be focusable with the D-pad without immediately covering
 * the screen with the software keyboard. Pair this modifier with
 * KeyboardOptions(showKeyboardOnFocus = false): OK/Enter explicitly opens the IME.
 */
@Composable
fun Modifier.tvKeyboardOnOk(): Modifier {
    val keyboard = LocalSoftwareKeyboardController.current
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            -> {
                keyboard?.show()
                // Do not consume the event: the focused text field may also use OK to place
                // the cursor. We only add the explicit keyboard request.
                false
            }
            else -> false
        }
    }
}
