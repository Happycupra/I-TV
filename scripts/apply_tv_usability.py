#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write_if_changed(path: Path, original: str, text: str) -> None:
    if text != original:
        path.write_text(text, encoding="utf-8")
        print(f"updated {path.relative_to(ROOT)}")


def add_import(text: str, anchor: str, new_import: str) -> str:
    if new_import in text:
        return text
    if anchor not in text:
        raise RuntimeError(f"Import anchor not found: {anchor}")
    return text.replace(anchor, anchor + new_import, 1)


# ---------------------------------------------------------------------------
# Search: keep the field focused, but do not pop the Android TV IME until OK.
# ---------------------------------------------------------------------------
channels = ROOT / "app/src/main/java/nl/vanvrouwerff/iptv/ui/channels/ChannelsScreen.kt"
original = text = read(channels)
text = add_import(
    text,
    "import nl.vanvrouwerff.iptv.ui.theme.tvFocus\n",
    "import nl.vanvrouwerff.iptv.ui.common.tvKeyboardOnOk\n",
)
search_anchor = """        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
"""
if "value = query" in text and "showKeyboardOnFocus = false" not in text[text.find("value = query") : text.find("value = query") + 500]:
    if search_anchor not in text:
        raise RuntimeError("Search field anchor not found")
    text = text.replace(
        search_anchor,
        search_anchor + "            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(showKeyboardOnFocus = false),\n",
        1,
    )
search_modifier = """            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
"""
if ".focusRequester(focusRequester).tvKeyboardOnOk()" not in text:
    if search_modifier not in text:
        raise RuntimeError("Search modifier anchor not found")
    text = text.replace(
        search_modifier,
        """            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .tvKeyboardOnOk(),
""",
        1,
    )
write_if_changed(channels, original, text)


# ---------------------------------------------------------------------------
# Profiles: moving focus to the name field should not open the IME by itself.
# ---------------------------------------------------------------------------
profiles = ROOT / "app/src/main/java/nl/vanvrouwerff/iptv/ui/profiles/ProfilesScreen.kt"
original = text = read(profiles)
text = add_import(
    text,
    "import nl.vanvrouwerff.iptv.ui.theme.IptvPalette\n",
    "import nl.vanvrouwerff.iptv.ui.common.tvKeyboardOnOk\n",
)
text = text.replace(
    "keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),",
    "keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, showKeyboardOnFocus = false),",
)
profile_modifier = """            keyboardActions = KeyboardActions(onDone = { onSave() }),
            modifier = Modifier.fillMaxWidth(),
"""
if ".fillMaxWidth().tvKeyboardOnOk()" not in text:
    if profile_modifier not in text:
        raise RuntimeError("Profile name modifier anchor not found")
    text = text.replace(
        profile_modifier,
        """            keyboardActions = KeyboardActions(onDone = { onSave() }),
            modifier = Modifier.fillMaxWidth().tvKeyboardOnOk(),
""",
        1,
    )
write_if_changed(profiles, original, text)


# ---------------------------------------------------------------------------
# Settings source fields: D-pad focus alone must not cover the TV with the IME.
# ---------------------------------------------------------------------------
settings = ROOT / "app/src/main/java/nl/vanvrouwerff/iptv/ui/settings/SettingsScreen.kt"
original = text = read(settings)
text = add_import(
    text,
    "import nl.vanvrouwerff.iptv.ui.theme.tvFocus\n",
    "import nl.vanvrouwerff.iptv.ui.common.tvKeyboardOnOk\n",
)
# Plain M3U/host/username/category fields. Only source text fields use this exact
# singleLine -> 720dp modifier sequence.
plain_pattern = re.compile(
    r"singleLine = true,\n(?P<indent>\s+)modifier = Modifier\.width\(720\.dp\),"
)
text = plain_pattern.sub(
    lambda m: (
        "singleLine = true,\n"
        f"{m.group('indent')}keyboardOptions = KeyboardOptions(showKeyboardOnFocus = false),\n"
        f"{m.group('indent')}modifier = Modifier.width(720.dp).tvKeyboardOnOk(),"
    ),
    text,
)
text = text.replace(
    "keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),",
    "keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, showKeyboardOnFocus = false),",
)
password_modifier = """                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, showKeyboardOnFocus = false),
                modifier = Modifier.width(720.dp),
"""
if password_modifier in text:
    text = text.replace(
        password_modifier,
        """                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, showKeyboardOnFocus = false),
                modifier = Modifier.width(720.dp).tvKeyboardOnOk(),
""",
        1,
    )
write_if_changed(settings, original, text)


# ---------------------------------------------------------------------------
# Home: expose the safe Soft Reset directly over the catalogue start screen.
# ---------------------------------------------------------------------------
main = ROOT / "app/src/main/java/nl/vanvrouwerff/iptv/MainActivity.kt"
original = text = read(main)
text = add_import(
    text,
    "import nl.vanvrouwerff.iptv.ui.channels.ChannelsScreen\n",
    "import nl.vanvrouwerff.iptv.ui.channels.HomeSoftResetButton\n",
)
if "HomeSoftResetButton()" not in text:
    pattern = re.compile(
        r"            Route\.Channels -> (ChannelsScreen\([\s\S]*?^            \))\n(?=            is Route\.MovieDetail ->)",
        re.MULTILINE,
    )
    match = pattern.search(text)
    if not match:
        raise RuntimeError("Route.Channels block not found")
    block = match.group(1)
    indented = "                " + block.replace("\n", "\n                ")
    replacement = (
        "            Route.Channels -> Box(modifier = Modifier.fillMaxSize()) {\n"
        + indented
        + "\n                HomeSoftResetButton()\n"
        + "            }\n"
    )
    text = text[: match.start()] + replacement + text[match.end() :]
write_if_changed(main, original, text)

print("TV usability patches completed.")
