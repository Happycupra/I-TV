package nl.vanvrouwerff.iptv.ui.common

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** True for Android TV / Google TV devices. Phones and tablets remain rotation-aware. */
fun Context.isTelevision(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION

/** A landscape phone still needs a touch layout, even when its width exceeds 600 dp. */
@Composable
fun isCompactTouchLayout(): Boolean {
    val configuration = LocalConfiguration.current
    val television = (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    return !television &&
        (configuration.screenWidthDp < 600 || configuration.screenHeightDp < 480)
}
