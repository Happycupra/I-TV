#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def patch(path: Path, fn):
    original = path.read_text(encoding="utf-8")
    updated = fn(original)
    if updated == original:
        print(f"unchanged {path.relative_to(ROOT)}")
    else:
        path.write_text(updated, encoding="utf-8")
        print(f"updated {path.relative_to(ROOT)}")


# Settings state + automatic get.php -> Xtream conversion.
def patch_vm(text: str) -> str:
    if "import nl.vanvrouwerff.iptv.data.settings.XtreamUrlParser" not in text:
        text = text.replace(
            "import nl.vanvrouwerff.iptv.data.settings.SourceConfig\n",
            "import nl.vanvrouwerff.iptv.data.settings.SourceConfig\n"
            "import nl.vanvrouwerff.iptv.data.settings.XtreamUrlParser\n",
            1,
        )
    if "val quickSetupUrl:" not in text:
        text = text.replace(
            "    val m3uUrl: String = \"\",\n",
            "    val m3uUrl: String = \"\",\n"
            "    val quickSetupUrl: String = \"\",\n"
            "    val xtreamUrlDetected: Boolean = false,\n",
            1,
        )
    if "fun setQuickSetupUrl" not in text:
        anchor = "    fun setType(type: SourceType) { _state.update { it.copy(type = type, validationError = null, testResult = null) } }\n"
        if anchor not in text:
            raise RuntimeError("SettingsViewModel setType anchor not found")
        method = '''    /**
     * Paste-friendly setup: a standard Xtream get.php M3U URL is recognised locally,
     * split into host/user/password, then discarded from the visible quick-entry field.
     * Nothing is saved until the user presses Save.
     */
    fun setQuickSetupUrl(v: String) {
        val parsed = XtreamUrlParser.parse(v)
        _state.update { current ->
            if (parsed == null) {
                current.copy(
                    quickSetupUrl = v,
                    xtreamUrlDetected = false,
                    validationError = null,
                    testResult = null,
                )
            } else {
                current.copy(
                    quickSetupUrl = "",
                    xtreamUrlDetected = true,
                    type = SourceType.Xtream,
                    host = parsed.host,
                    username = parsed.username,
                    password = parsed.password,
                    validationError = null,
                    testResult = null,
                    filledFromPhone = false,
                )
            }
        }
    }

'''
        text = text.replace(anchor, method + anchor, 1)
    return text


# Dedicated paste field at the top of the source section.
def patch_settings(text: str) -> str:
    marker = "settings_quick_url_title"
    if marker in text:
        return text
    anchor = '''    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SegmentPill(
'''
    if anchor not in text:
        raise RuntimeError("SettingsScreen SourceSection anchor not found")
    block = '''    OutlinedTextField(
        value = state.quickSetupUrl,
        onValueChange = vm::setQuickSetupUrl,
        label = { androidx.compose.material3.Text(stringResource(R.string.settings_quick_url_title)) },
        supportingText = {
            androidx.compose.material3.Text(stringResource(R.string.settings_quick_url_hint))
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(showKeyboardOnFocus = false),
        modifier = Modifier.width(720.dp).tvKeyboardOnOk(),
    )
    if (state.xtreamUrlDetected) {
        Text(
            text = stringResource(R.string.settings_xtream_detected),
            style = MaterialTheme.typography.bodyMedium,
            color = IptvPalette.AccentSoft,
        )
    }

'''
    return text.replace(anchor, block + anchor, 1)


# Move Soft Reset from floating bottom-right overlay into the top bar next to the app name.
def patch_channels(text: str) -> str:
    anchor = '''        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Black,
                color = IptvPalette.Accent,
                letterSpacing = 2.sp,
            ),
        )
        Spacer(Modifier.width(24.dp))
'''
    if "HomeSoftResetButton()\n        Spacer(Modifier.width(24.dp))" in text:
        return text
    if anchor not in text:
        raise RuntimeError("ChannelsScreen app-name anchor not found")
    replacement = anchor.replace(
        "        Spacer(Modifier.width(24.dp))\n",
        "        Spacer(Modifier.width(12.dp))\n"
        "        HomeSoftResetButton()\n"
        "        Spacer(Modifier.width(24.dp))\n",
    )
    return text.replace(anchor, replacement, 1)


# The reset button is now rendered by TopBar, so remove the MainActivity overlay wrapper.
def patch_main(text: str) -> str:
    text = text.replace("import nl.vanvrouwerff.iptv.ui.channels.HomeSoftResetButton\n", "")
    if "Route.Channels -> Box(modifier = Modifier.fillMaxSize())" not in text:
        return text
    pattern = re.compile(
        r'''            Route\.Channels -> Box\(modifier = Modifier\.fillMaxSize\(\)\) \{\n\s+(ChannelsScreen\([\s\S]*?\n\s+\))\n\s+HomeSoftResetButton\(\)\n\s+\}\n(?=            is Route\.MovieDetail ->)''',
        re.MULTILINE,
    )
    match = pattern.search(text)
    if not match:
        raise RuntimeError("MainActivity home soft-reset overlay block not found")
    screen = match.group(1)
    # Normalise the indentation introduced by the earlier wrapper patch.
    lines = screen.splitlines()
    min_indent = min((len(line) - len(line.lstrip()) for line in lines if line.strip()), default=0)
    screen = "\n".join(line[min_indent:] if line.strip() else "" for line in lines)
    screen = "            " + screen.replace("\n", "\n            ")
    return text[:match.start()] + "            Route.Channels -> " + screen.lstrip() + "\n" + text[match.end():]


patch(ROOT / "app/src/main/java/nl/vanvrouwerff/iptv/ui/settings/SettingsViewModel.kt", patch_vm)
patch(ROOT / "app/src/main/java/nl/vanvrouwerff/iptv/ui/settings/SettingsScreen.kt", patch_settings)
patch(ROOT / "app/src/main/java/nl/vanvrouwerff/iptv/ui/channels/ChannelsScreen.kt", patch_channels)
patch(ROOT / "app/src/main/java/nl/vanvrouwerff/iptv/MainActivity.kt", patch_main)

print("Xtream quick import + top-bar soft reset patch completed")
