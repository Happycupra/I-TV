#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text()

def write(path, text):
    (ROOT / path).write_text(text)

def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"Missing patch anchor: {label}")
    return text.replace(old, new, 1)

# 1) Manifest: phone/tablet may rotate freely; TV lock is applied at runtime.
p = "app/src/main/AndroidManifest.xml"
s = read(p)
s = s.replace('            android:launchMode="singleTop"\n            android:screenOrientation="landscape">', '            android:launchMode="singleTop">')
s = s.replace('            android:launchMode="singleTask"\n            android:screenOrientation="landscape"\n            android:theme="@style/Theme.Iptv.Player" />', '            android:launchMode="singleTask"\n            android:theme="@style/Theme.Iptv.Player" />')
s = s.replace('        android:networkSecurityConfig="@xml/network_security_config"', '        android:networkSecurityConfig="@xml/network_security_config"\n        android:resizeableActivity="true"')
write(p, s)

# 2) MainActivity: preserve the TV landscape UX, let phones follow sensor/user rotation.
p = "app/src/main/java/nl/vanvrouwerff/iptv/MainActivity.kt"
s = read(p)
s = replace_once(s, 'import android.content.Intent\n', 'import android.content.Intent\nimport android.content.pm.ActivityInfo\n', 'MainActivity ActivityInfo import')
s = replace_once(s, 'import nl.vanvrouwerff.iptv.ui.categories.CategoriesScreen\n', 'import nl.vanvrouwerff.iptv.ui.categories.CategoriesScreen\nimport nl.vanvrouwerff.iptv.ui.common.isTelevision\n', 'MainActivity device import')
s = replace_once(s, '    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)\n', '    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)\n        if (isTelevision()) {\n            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE\n        }\n', 'MainActivity runtime orientation')
write(p, s)

# 3) Player: same policy. Phone playback can rotate; TV remains fixed landscape.
p = "app/src/main/java/nl/vanvrouwerff/iptv/player/PlayerActivity.kt"
s = read(p)
s = replace_once(s, 'import android.content.Intent\n', 'import android.content.Intent\nimport android.content.pm.ActivityInfo\n', 'Player ActivityInfo import')
s = replace_once(s, 'import nl.vanvrouwerff.iptv.ui.theme.IptvTheme\n', 'import nl.vanvrouwerff.iptv.ui.theme.IptvTheme\nimport nl.vanvrouwerff.iptv.ui.common.isTelevision\n', 'Player device import')
s = replace_once(s, '    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)\n', '    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)\n        if (isTelevision()) {\n            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE\n        }\n', 'Player runtime orientation')
write(p, s)

# 4) Home screen: compact side margins, swipeable top bar, hide remote-only key legend on phones.
p = "app/src/main/java/nl/vanvrouwerff/iptv/ui/channels/ChannelsScreen.kt"
s = read(p)
s = replace_once(s, 'import androidx.compose.foundation.background\n', 'import androidx.compose.foundation.background\nimport androidx.compose.foundation.horizontalScroll\nimport androidx.compose.foundation.rememberScrollState\n', 'Channels scroll imports')
s = replace_once(s, 'import androidx.compose.ui.platform.LocalContext\n', 'import androidx.compose.ui.platform.LocalConfiguration\nimport androidx.compose.ui.platform.LocalContext\n', 'Channels config import')
anchor = 'private const val HERO_SWAP_FOCUS_DELAY_MS: Long = 650L\n\n'
helper = '''private const val HERO_SWAP_FOCUS_DELAY_MS: Long = 650L\n\n@Composable\nprivate fun screenHorizontalPadding(): Dp =\n    if (LocalConfiguration.current.screenWidthDp < 600) 16.dp else 48.dp\n\n'''
s = replace_once(s, anchor, helper, 'Channels responsive padding helper')
s = s.replace('horizontal = 48.dp', 'horizontal = screenHorizontalPadding()')
s = replace_once(s, '    val initialType = remember { state.selectedType }\n', '    val compactScreen = LocalConfiguration.current.screenWidthDp < 600\n    val initialType = remember { state.selectedType }\n', 'Channels compact flag')
old_key = '''                KeyHintStrip(\n                    state = state,\n                    modifier = Modifier.align(Alignment.CenterHorizontally),\n                )'''
new_key = '''                if (!compactScreen) {\n                    KeyHintStrip(\n                        state = state,\n                        modifier = Modifier.align(Alignment.CenterHorizontally),\n                    )\n                }'''
s = replace_once(s, old_key, new_key, 'Hide TV key hints on phone')
old_top = '''    Row(\n        modifier = Modifier\n            .fillMaxWidth()\n            .padding(horizontal = screenHorizontalPadding(), vertical = 20.dp)\n            .onFocusChanged { onFocusChanged(it.hasFocus) },'''
new_top = '''    Row(\n        modifier = Modifier\n            .fillMaxWidth()\n            .horizontalScroll(rememberScrollState())\n            .padding(horizontal = screenHorizontalPadding(), vertical = 20.dp)\n            .onFocusChanged { onFocusChanged(it.hasFocus) },'''
s = replace_once(s, old_top, new_top, 'Swipeable compact top bar')
write(p, s)

# 5) Settings: fixed 720dp TV fields become responsive phone fields.
p = "app/src/main/java/nl/vanvrouwerff/iptv/ui/settings/SettingsScreen.kt"
s = read(p)
s = replace_once(s, 'import androidx.compose.foundation.layout.width\n', 'import androidx.compose.foundation.layout.width\nimport androidx.compose.foundation.layout.widthIn\n', 'Settings widthIn import')
s = replace_once(s, 'import androidx.compose.ui.res.stringResource\n', 'import androidx.compose.ui.platform.LocalConfiguration\nimport androidx.compose.ui.res.stringResource\n', 'Settings config import')
s = replace_once(s, '    val state by vm.state.collectAsState()\n    var pinDialog', '    val state by vm.state.collectAsState()\n    val compactScreen = LocalConfiguration.current.screenWidthDp < 600\n    var pinDialog', 'Settings compact flag')
s = replace_once(s, '.padding(horizontal = 64.dp, vertical = 36.dp)', '.padding(\n                    horizontal = if (compactScreen) 16.dp else 64.dp,\n                    vertical = if (compactScreen) 16.dp else 36.dp,\n                )', 'Settings adaptive padding')
s = s.replace('Modifier.width(720.dp).tvKeyboardOnOk()', 'Modifier.fillMaxWidth().widthIn(max = 720.dp).tvKeyboardOnOk()')
s = s.replace('Modifier.width(560.dp)', 'Modifier.fillMaxWidth().widthIn(max = 560.dp)')
write(p, s)

# 6) Category browser: retain split-pane on TV, shrink the category rail/grid on compact phones.
p = "app/src/main/java/nl/vanvrouwerff/iptv/ui/categories/CategoriesScreen.kt"
s = read(p)
s = replace_once(s, 'import androidx.compose.ui.focus.focusRequester\n', 'import androidx.compose.ui.focus.focusRequester\nimport androidx.compose.ui.platform.LocalConfiguration\n', 'Categories config import')
s = replace_once(s, '    val state by vm.state.collectAsState()\n    BackHandler', '    val state by vm.state.collectAsState()\n    val compactScreen = LocalConfiguration.current.screenWidthDp < 600\n    BackHandler', 'Categories compact flag')
s = replace_once(s, '.padding(horizontal = 48.dp, vertical = 24.dp)', '.padding(\n                horizontal = if (compactScreen) 12.dp else 48.dp,\n                vertical = if (compactScreen) 12.dp else 24.dp,\n            )', 'Categories adaptive padding')
s = replace_once(s, '.width(300.dp)\n                    .fillMaxHeight()', '.width(if (compactScreen) 116.dp else 300.dp)\n                    .fillMaxHeight()', 'Categories rail width')
s = replace_once(s, '            Spacer(Modifier.width(24.dp))', '            Spacer(Modifier.width(if (compactScreen) 8.dp else 24.dp))', 'Categories rail gap')
s = replace_once(s, '                    columns = if (type == ContentType.TV) GridCells.Adaptive(220.dp) else GridCells.Adaptive(168.dp),', '                    columns = when {\n                        compactScreen && type == ContentType.TV -> GridCells.Adaptive(150.dp)\n                        compactScreen -> GridCells.Adaptive(132.dp)\n                        type == ContentType.TV -> GridCells.Adaptive(220.dp)\n                        else -> GridCells.Adaptive(168.dp)\n                    },', 'Categories adaptive grid')
write(p, s)

# 7) Guide: reduce wasted edge space on phones while retaining the TV layout.
p = "app/src/main/java/nl/vanvrouwerff/iptv/ui/guide/GuideScreen.kt"
s = read(p)
s = replace_once(s, 'import androidx.compose.ui.focus.onFocusChanged\n', 'import androidx.compose.ui.focus.onFocusChanged\nimport androidx.compose.ui.platform.LocalConfiguration\n', 'Guide config import')
s = replace_once(s, '    val state by vm.state.collectAsState()\n    BackHandler', '    val state by vm.state.collectAsState()\n    val compactScreen = LocalConfiguration.current.screenWidthDp < 600\n    BackHandler', 'Guide compact flag')
s = replace_once(s, '.padding(horizontal = 48.dp, vertical = 20.dp)', '.padding(\n                    horizontal = if (compactScreen) 12.dp else 48.dp,\n                    vertical = if (compactScreen) 12.dp else 20.dp,\n                )', 'Guide adaptive padding')
s = replace_once(s, '                    Box(Modifier.width(200.dp)) {', '                    Box(Modifier.width(if (compactScreen) 140.dp else 200.dp)) {', 'Guide compact category width')
write(p, s)

print('Android phone support patch applied')
