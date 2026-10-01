package nl.vanvrouwerff.iptv.ui.common

import android.content.Context
import android.content.res.Configuration

/** True for Android TV / Google TV devices. Phones and tablets remain rotation-aware. */
fun Context.isTelevision(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
