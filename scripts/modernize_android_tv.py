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

print("Android TV modernization migration completed.")
