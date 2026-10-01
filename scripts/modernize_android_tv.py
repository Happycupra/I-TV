#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def replace_file(path: Path, replacements: list[tuple[str, str]]) -> None:
    text = path.read_text(encoding="utf-8")
    original = text
    for old, new in replacements:
        text = text.replace(old, new)
    if text != original:
        path.write_text(text, encoding="utf-8")
        print(f"updated {path.relative_to(ROOT)}")


def replace_once(path: Path, old: str, new: str, marker: str) -> None:
    text = path.read_text(encoding="utf-8")
    if marker in text:
        return
    if old not in text:
        raise RuntimeError(f"Could not find migration anchor in {path.relative_to(ROOT)}: {old[:80]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"updated {path.relative_to(ROOT)}")


def migrate_kotlin_file(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    original = text

    replacements = {
        "androidx.tv.foundation.lazy.list.TvLazyColumn": "androidx.compose.foundation.lazy.LazyColumn",
        "androidx.tv.foundation.lazy.list.TvLazyRow": "androidx.compose.foundation.lazy.LazyRow",
        "androidx.tv.foundation.lazy.list.TvLazyListState": "androidx.compose.foundation.lazy.LazyListState",
        "androidx.tv.foundation.lazy.list.TvLazyListScope": "androidx.compose.foundation.lazy.LazyListScope",
        "androidx.tv.foundation.lazy.list.rememberTvLazyListState": "androidx.compose.foundation.lazy.rememberLazyListState",
        "androidx.tv.foundation.lazy.list.itemsIndexed": "androidx.compose.foundation.lazy.itemsIndexed",
        "androidx.tv.foundation.lazy.list.items": "androidx.compose.foundation.lazy.items",
        "androidx.tv.foundation.lazy.grid.TvGridCells": "androidx.compose.foundation.lazy.grid.GridCells",
        "androidx.tv.foundation.lazy.grid.TvLazyHorizontalGrid": "androidx.compose.foundation.lazy.grid.LazyHorizontalGrid",
        "androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid": "androidx.compose.foundation.lazy.grid.LazyVerticalGrid",
        "androidx.tv.foundation.lazy.grid.TvLazyGridState": "androidx.compose.foundation.lazy.grid.LazyGridState",
        "androidx.tv.foundation.lazy.grid.rememberTvLazyGridState": "androidx.compose.foundation.lazy.grid.rememberLazyGridState",
        "androidx.tv.foundation.lazy.grid.itemsIndexed": "androidx.compose.foundation.lazy.grid.itemsIndexed",
        "androidx.tv.foundation.lazy.grid.items": "androidx.compose.foundation.lazy.grid.items",
        "TvLazyColumn": "LazyColumn",
        "TvLazyRow": "LazyRow",
        "TvLazyListState": "LazyListState",
        "TvLazyListScope": "LazyListScope",
        "rememberTvLazyListState": "rememberLazyListState",
        "TvGridCells": "GridCells",
        "TvLazyHorizontalGrid": "LazyHorizontalGrid",
        "TvLazyVerticalGrid": "LazyVerticalGrid",
        "TvLazyGridState": "LazyGridState",
        "rememberTvLazyGridState": "rememberLazyGridState",
        "Locale(\"nl\", \"NL\")": "Locale.forLanguageTag(\"de-CH\")",
    }
    for old, new in replacements.items():
        text = text.replace(old, new)

    # The alpha10 focus-search crash workaround is no longer needed after migrating away
    # from the removed TV-specific lazy layouts. Remove the broad IllegalStateException
    # swallow so real focus bugs are visible instead of being hidden.
    if path.name == "MainActivity.kt":
        marker = "    /**\n     * Workaround for androidx.tv.foundation:1.0.0-alpha10 focus-search crash."
        next_marker = "    private fun openPlayer("
        start = text.find(marker)
        end = text.find(next_marker, start) if start >= 0 else -1
        if start >= 0 and end > start:
            text = text[:start] + text[end:]
        text = text.replace("import android.util.Log\n", "")
        text = text.replace("import android.view.KeyEvent\n", "")
        text = text.replace(
            "\n    private companion object {\n        const val TAG = \"MainActivity\"\n    }\n",
            "\n",
        )

    if text != original:
        path.write_text(text, encoding="utf-8")
        print(f"migrated {path.relative_to(ROOT)}")


# Modern toolchain. Compose is deliberately pinned to the final 1.11.x line because
# Compose 1.12+ requires compileSdk 37 and AGP 9.1, while AGP 8.13.2 supports compileSdk 36.
versions = ROOT / "gradle/libs.versions.toml"
text = versions.read_text(encoding="utf-8")
text = re.sub(r'^agp = ".*"$', 'agp = "8.13.2"', text, flags=re.MULTILINE)
text = re.sub(r'^kotlin = ".*"$', 'kotlin = "2.3.21"', text, flags=re.MULTILINE)
text = re.sub(r'^ksp = ".*"$', 'ksp = "2.3.12"', text, flags=re.MULTILINE)
text = re.sub(r'^compose-bom = ".*"$', 'compose-bom = "2026.06.01"', text, flags=re.MULTILINE)
text = re.sub(r'^compose-compiler = ".*"\n', '', text, flags=re.MULTILINE)
text = re.sub(r'^activity-compose = ".*"$', 'activity-compose = "1.13.0"', text, flags=re.MULTILINE)
text = re.sub(r'^lifecycle = ".*"$', 'lifecycle = "2.10.0"', text, flags=re.MULTILINE)
text = re.sub(r'^tv-foundation = ".*"$', 'tv-foundation = "1.0.0"', text, flags=re.MULTILINE)
text = re.sub(r'^tv-material = ".*"$', 'tv-material = "1.1.0"', text, flags=re.MULTILINE)
text = re.sub(r'^media3 = ".*"$', 'media3 = "1.11.1"', text, flags=re.MULTILINE)
text = re.sub(r'^room = ".*"$', 'room = "2.8.5"', text, flags=re.MULTILINE)
if 'compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose"' not in text:
    text = text.replace(
        'kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }\n',
        'kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }\n'
        'compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }\n',
    )
versions.write_text(text, encoding="utf-8")
print("updated gradle/libs.versions.toml")

# Root plugin declaration for the Kotlin 2.x Compose compiler plugin.
root_build = ROOT / "build.gradle.kts"
text = root_build.read_text(encoding="utf-8")
if "alias(libs.plugins.compose.compiler) apply false" not in text:
    text = text.replace(
        "    alias(libs.plugins.kotlin.serialization) apply false\n",
        "    alias(libs.plugins.kotlin.serialization) apply false\n"
        "    alias(libs.plugins.compose.compiler) apply false\n",
    )
root_build.write_text(text, encoding="utf-8")
print("updated build.gradle.kts")

# App module: compile against Android 16 while intentionally keeping targetSdk 34.
# Kotlin 2.x uses the Compose plugin, so legacy composeOptions/kotlinOptions must go.
app_build = ROOT / "app/build.gradle.kts"
text = app_build.read_text(encoding="utf-8")
if "alias(libs.plugins.compose.compiler)" not in text:
    text = text.replace(
        "    alias(libs.plugins.kotlin.serialization)\n",
        "    alias(libs.plugins.kotlin.serialization)\n"
        "    alias(libs.plugins.compose.compiler)\n",
    )
text = text.replace("    compileSdk = 34", "    compileSdk = 36")
text = re.sub(
    r"\n    composeOptions \{\n        kotlinCompilerExtensionVersion = libs\.versions\.compose\.compiler\.get\(\)\n    \}\n",
    "\n",
    text,
)
# Kotlin 2.3 treats the old kotlinOptions DSL as an error. Remove the module's legacy
# block and configure compilerOptions via the Kotlin Gradle plugin instead.
text = re.sub(
    r"\n    kotlinOptions \{\n        jvmTarget = \"17\"\n        freeCompilerArgs \+= listOf\(\n            \"-opt-in=androidx\.tv\.material3\.ExperimentalTvMaterial3Api\",\n            \"-opt-in=androidx\.tv\.foundation\.ExperimentalTvFoundationApi\",\n        \)\n    \}\n",
    "\n",
    text,
)
if "kotlin {\n    compilerOptions {" not in text:
    text += """

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api")
        freeCompilerArgs.add("-opt-in=androidx.tv.foundation.ExperimentalTvFoundationApi")
    }
}
"""
app_build.write_text(text, encoding="utf-8")
print("updated app/build.gradle.kts")

# Gradle required by AGP 8.13.x.
wrapper = ROOT / "gradle/wrapper/gradle-wrapper.properties"
replace_file(
    wrapper,
    [("gradle-8.7-bin.zip", "gradle-8.13-bin.zip")],
)

# Migrate every Kotlin source away from removed androidx.tv.foundation lazy layouts.
# Also remove hard-coded Dutch locale formatting in favour of German (Switzerland).
for source_root in (ROOT / "app/src/main/java", ROOT / "app/src/test/java"):
    if source_root.exists():
        for kt in source_root.rglob("*.kt"):
            migrate_kotlin_file(kt)

# ---------------------------------------------------------------------------
# I-TV usability / maintenance additions.
# These patches are idempotent so the migration can keep running in CI.
# ---------------------------------------------------------------------------

http_client = ROOT / "app/src/main/java/nl/vanvrouwerff/iptv/data/remote/HttpClient.kt"
replace_once(
    http_client,
    "    fun retrofitFor(baseUrl: String): Retrofit {",
    """    /** Clears cached API metadata. Video segments are never stored in this cache. */
    fun clearCache() {
        runCatching { _okHttp?.cache?.evictAll() }
            .onFailure { Log.w("HttpClient", "Failed to clear HTTP cache", it) }
    }

    fun retrofitFor(baseUrl: String): Retrofit {""",
    "fun clearCache()",
)

iptv_app = ROOT / "app/src/main/java/nl/vanvrouwerff/iptv/IptvApp.kt"
replace_once(
    iptv_app,
    "import coil.ImageLoader\n",
    "import coil.Coil\nimport coil.ImageLoader\n",
    "import coil.Coil",
)
replace_once(
    iptv_app,
    "    companion object {\n",
    """    /**
     * Clears disposable network/image caches without touching the playlist database,
     * favourites, profiles, watch progress, PIN or source credentials.
     */
    fun clearTransientCaches() {
        HttpClient.clearCache()
        runCatching {
            val loader = Coil.imageLoader(this)
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        }
    }

    companion object {
""",
    "fun clearTransientCaches()",
)

settings_vm = ROOT / "app/src/main/java/nl/vanvrouwerff/iptv/ui/settings/SettingsViewModel.kt"
replace_once(
    settings_vm,
    "    val refreshError: String? = null,\n",
    "    val refreshError: String? = null,\n    val maintenanceRunning: Boolean = false,\n    val maintenanceMessage: String? = null,\n",
    "maintenanceRunning: Boolean",
)
replace_once(
    settings_vm,
    "    private companion object {\n",
    """    /** Clear bounded HTTP/image caches without changing user data or the catalogue. */
    fun clearCache() {
        if (_state.value.maintenanceRunning || _state.value.refreshing) return
        _state.update { it.copy(maintenanceRunning = true, maintenanceMessage = null) }
        app.appScope.launch {
            val result = runCatching { app.clearTransientCaches() }
            _state.update {
                it.copy(
                    maintenanceRunning = false,
                    maintenanceMessage = app.getString(
                        if (result.isSuccess) R.string.settings_cache_cleared
                        else R.string.settings_maintenance_failed,
                    ),
                )
            }
        }
    }

    /**
     * Safe recovery path for stale/laggy catalogues: clear disposable caches and force a
     * source re-download. Existing DB rows stay visible until the fresh import succeeds.
     */
    fun softReset() {
        if (_state.value.maintenanceRunning || _state.value.refreshing) return
        _state.update { it.copy(maintenanceRunning = true, maintenanceMessage = null) }
        app.appScope.launch {
            val result = runCatching {
                app.clearTransientCaches()
                app.refreshUseCase(force = true).getOrThrow()
            }
            _state.update {
                it.copy(
                    maintenanceRunning = false,
                    maintenanceMessage = app.getString(
                        if (result.isSuccess) R.string.settings_soft_reset_done
                        else R.string.settings_maintenance_failed,
                    ),
                )
            }
        }
    }

    private companion object {
""",
    "fun softReset()",
)

settings_screen = ROOT / "app/src/main/java/nl/vanvrouwerff/iptv/ui/settings/SettingsScreen.kt"
replace_once(
    settings_screen,
    """                        Button(onClick = { vm.bumpAutoRefreshHour(1) }) {
                            Text(stringResource(R.string.settings_auto_refresh_hour_next))
                        }
                    }
                }
            }
""",
    """                        Button(onClick = { vm.bumpAutoRefreshHour(1) }) {
                            Text(stringResource(R.string.settings_auto_refresh_hour_next))
                        }
                    }
                    RefreshCountdown(hour = state.autoRefreshHour)
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_maintenance_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = IptvPalette.TextSecondary,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = vm::clearCache,
                        enabled = !state.maintenanceRunning && !state.refreshing,
                    ) {
                        Text(stringResource(R.string.settings_clear_cache))
                    }
                    Button(
                        onClick = vm::softReset,
                        enabled = !state.maintenanceRunning && !state.refreshing,
                    ) {
                        Text(
                            if (state.maintenanceRunning) stringResource(R.string.status_refreshing)
                            else stringResource(R.string.settings_soft_reset),
                        )
                    }
                }
                state.maintenanceMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = IptvPalette.AccentSoft,
                    )
                }
            }
""",
    "RefreshCountdown(hour = state.autoRefreshHour)",
)

player_screen = ROOT / "app/src/main/java/nl/vanvrouwerff/iptv/player/PlayerScreen.kt"
replace_once(
    player_screen,
    "    onControlsInteraction: () -> Unit = {},\n",
    "    onControlsInteraction: () -> Unit = {},\n    onSurfaceTap: () -> Unit = {},\n",
    "onSurfaceTap: () -> Unit",
)
replace_once(
    player_screen,
    """                view.isFocusable = !panelOpen
                view.isFocusableInTouchMode = !panelOpen
                view.descendantFocusability = if (panelOpen) {
""",
    """                view.isFocusable = !panelOpen
                view.isFocusableInTouchMode = !panelOpen
                view.isClickable = !panelOpen
                view.setOnClickListener { if (!panelOpen) onSurfaceTap() }
                view.descendantFocusability = if (panelOpen) {
""",
    "view.isClickable = !panelOpen",
)

player_activity = ROOT / "app/src/main/java/nl/vanvrouwerff/iptv/player/PlayerActivity.kt"
replace_once(
    player_activity,
    "                        onControlsInteraction = ::bumpControlsTimer,\n",
    """                        onControlsInteraction = ::bumpControlsTimer,
                        onSurfaceTap = {
                            if (controlsVisible) hideControls() else showControls()
                        },
""",
    "onSurfaceTap = {",
)

# Small standalone composable so the SettingsScreen stays readable.
countdown_file = ROOT / "app/src/main/java/nl/vanvrouwerff/iptv/ui/settings/RefreshCountdown.kt"
if not countdown_file.exists():
    countdown_file.write_text(
        """package nl.vanvrouwerff.iptv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.util.Calendar
import java.util.Locale
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
    val value = String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
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
""",
        encoding="utf-8",
    )
    print(f"created {countdown_file.relative_to(ROOT)}")

maintenance_strings = ROOT / "app/src/main/res/values/strings_maintenance.xml"
if not maintenance_strings.exists():
    maintenance_strings.write_text(
        """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="settings_auto_refresh_countdown">Nächste Aktualisierung in %1$s</string>
    <string name="settings_maintenance_body">Bei Störungen kannst du temporäre Netzwerk-/Bilddaten leeren oder I-TV mit einer erzwungenen Playlist-Neuladung sanft zurücksetzen. Favoriten, Profile, Verlauf und Zugangsdaten bleiben erhalten.</string>
    <string name="settings_clear_cache">Cache leeren</string>
    <string name="settings_cache_cleared">Cache wurde geleert.</string>
    <string name="settings_soft_reset">Soft Reset</string>
    <string name="settings_soft_reset_done">Soft Reset abgeschlossen. Die Playlist wurde neu geladen.</string>
    <string name="settings_maintenance_failed">Aktion fehlgeschlagen. Bitte Netzwerk/Quelle prüfen und erneut versuchen.</string>
</resources>
""",
        encoding="utf-8",
    )
    print(f"created {maintenance_strings.relative_to(ROOT)}")

print("Android TV modernization migration completed.")
