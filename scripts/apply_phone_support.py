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

# 5) Settings: fixed TV fields become responsive phone fields.
p = "app/src/main/java/nl/vanvrouwerff/iptv/ui/settings/SettingsScreen.kt"
s = read(p)
s = replace_once(s, 'import androidx.compose.foundation.layout.width\n', 'import androidx.compose.foundation.layout.width\nimport androidx.compose.foundation.layout.widthIn\n', 'Settings widthIn import')
s = replace_once(s, 'import androidx.compose.ui.res.stringResource\n', 'import androidx.compose.ui.platform.LocalConfiguration\nimport androidx.compose.ui.res.stringResource\n', 'Settings config import')
s = replace_once(s, '    val state by vm.state.collectAsState()\n    var pinDialog', '    val state by vm.state.collectAsState()\n    val compactScreen = LocalConfiguration.current.screenWidthDp < 600\n    var pinDialog', 'Settings compact flag')
s = replace_once(s, '.padding(horizontal = 64.dp, vertical = 36.dp)', '.padding(\n                    horizontal = if (compactScreen) 16.dp else 64.dp,\n                    vertical = if (compactScreen) 16.dp else 36.dp,\n                )', 'Settings adaptive padding')
s = s.replace('Modifier.width(720.dp).tvKeyboardOnOk()', 'Modifier.widthIn(max = 720.dp).fillMaxWidth().tvKeyboardOnOk()')
s = s.replace('Modifier.width(560.dp)', 'Modifier.widthIn(max = 560.dp).fillMaxWidth()')
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

# 8) Welcome/setup: stack onboarding content on a phone; QR remains a TV convenience.
p = "app/src/main/java/nl/vanvrouwerff/iptv/ui/wizard/WelcomeScreen.kt"
s = read(p)
s = replace_once(s, 'import androidx.compose.foundation.layout.fillMaxSize\n', 'import androidx.compose.foundation.layout.fillMaxSize\nimport androidx.compose.foundation.layout.fillMaxWidth\n', 'Welcome fillMaxWidth import')
s = replace_once(s, 'import androidx.compose.ui.draw.clip\n', 'import androidx.compose.ui.draw.clip\nimport androidx.compose.ui.platform.LocalConfiguration\n', 'Welcome config import')
s = replace_once(s, '    val phoneSubmission by nl.vanvrouwerff.iptv.data.settings.PhoneSetupServer.submission.collectAsState()\n', '    val phoneSubmission by nl.vanvrouwerff.iptv.data.settings.PhoneSetupServer.submission.collectAsState()\n    val compactScreen = LocalConfiguration.current.screenWidthDp < 600\n', 'Welcome compact flag')
s = replace_once(s, '.padding(horizontal = 64.dp, vertical = 36.dp)', '.padding(\n                    horizontal = if (compactScreen) 20.dp else 64.dp,\n                    vertical = if (compactScreen) 20.dp else 36.dp,\n                )', 'Welcome adaptive padding')
old_steps = '''            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {\n                WizardStep(\n                    number = "1",\n                    title = stringResource(R.string.wizard_step1_title),\n                    body = stringResource(R.string.wizard_step1_body),\n                )\n                WizardStep(\n                    number = "2",\n                    title = stringResource(R.string.wizard_step2_title),\n                    body = stringResource(R.string.wizard_step2_body),\n                )\n                WizardStep(\n                    number = "3",\n                    title = stringResource(R.string.wizard_step3_title),\n                    body = stringResource(R.string.wizard_step3_body),\n                )\n            }'''
new_steps = '''            if (compactScreen) {\n                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {\n                    WizardStep("1", stringResource(R.string.wizard_step1_title), stringResource(R.string.wizard_step1_body))\n                    WizardStep("2", stringResource(R.string.wizard_step2_title), stringResource(R.string.wizard_step2_body))\n                    WizardStep("3", stringResource(R.string.wizard_step3_title), stringResource(R.string.wizard_step3_body))\n                }\n            } else {\n                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {\n                    WizardStep("1", stringResource(R.string.wizard_step1_title), stringResource(R.string.wizard_step1_body))\n                    WizardStep("2", stringResource(R.string.wizard_step2_title), stringResource(R.string.wizard_step2_body))\n                    WizardStep("3", stringResource(R.string.wizard_step3_title), stringResource(R.string.wizard_step3_body))\n                }\n            }'''
s = replace_once(s, old_steps, new_steps, 'Welcome stacked steps')
old_bottom = '''            Row(verticalAlignment = Alignment.CenterVertically) {\n                Button(\n                    onClick = onConfigure,\n                    modifier = Modifier.width(320.dp),\n                ) {\n                    Text(\n                        text = stringResource(R.string.wizard_configure),\n                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),\n                        modifier = Modifier.padding(vertical = 4.dp),\n                    )\n                }\n                Spacer(Modifier.width(40.dp))\n                nl.vanvrouwerff.iptv.ui.settings.PhoneSetupPanel(qrSize = 110.dp)\n            }'''
new_bottom = '''            if (compactScreen) {\n                Button(onClick = onConfigure, modifier = Modifier.fillMaxWidth()) {\n                    Text(\n                        text = stringResource(R.string.wizard_configure),\n                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),\n                        modifier = Modifier.padding(vertical = 4.dp),\n                    )\n                }\n            } else {\n                Row(verticalAlignment = Alignment.CenterVertically) {\n                    Button(onClick = onConfigure, modifier = Modifier.width(320.dp)) {\n                        Text(\n                            text = stringResource(R.string.wizard_configure),\n                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),\n                            modifier = Modifier.padding(vertical = 4.dp),\n                        )\n                    }\n                    Spacer(Modifier.width(40.dp))\n                    nl.vanvrouwerff.iptv.ui.settings.PhoneSetupPanel(qrSize = 110.dp)\n                }\n            }'''
s = replace_once(s, old_bottom, new_bottom, 'Welcome compact action')
s = replace_once(s, 'private fun WizardStep(number: String, title: String, body: String) {\n    Column(\n        modifier = Modifier\n            .width(220.dp)', 'private fun WizardStep(number: String, title: String, body: String) {\n    val compactScreen = LocalConfiguration.current.screenWidthDp < 600\n    Column(\n        modifier = (if (compactScreen) Modifier.fillMaxWidth() else Modifier.width(220.dp))', 'Welcome responsive step card')
write(p, s)

# 9) Profile picker: phone-friendly 2-column grid and smaller touch avatars.
p = "app/src/main/java/nl/vanvrouwerff/iptv/ui/profilepicker/ProfilePickerScreen.kt"
s = read(p)
s = replace_once(s, 'import androidx.compose.ui.graphics.Color\n', 'import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.platform.LocalConfiguration\n', 'ProfilePicker config import')
s = replace_once(s, '    val profiles by vm.profiles.collectAsState()\n', '    val profiles by vm.profiles.collectAsState()\n    val compactScreen = LocalConfiguration.current.screenWidthDp < 600\n', 'ProfilePicker compact flag')
s = replace_once(s, '                fontSize = 44.sp,', '                fontSize = if (compactScreen) 28.sp else 44.sp,', 'ProfilePicker title size')
s = replace_once(s, '            Spacer(Modifier.height(48.dp))', '            Spacer(Modifier.height(if (compactScreen) 20.dp else 48.dp))', 'ProfilePicker title gap')
s = replace_once(s, '                columns = GridCells.Fixed(3),\n                contentPadding = PaddingValues(horizontal = 48.dp),\n                horizontalArrangement = Arrangement.spacedBy(48.dp),\n                verticalArrangement = Arrangement.spacedBy(36.dp),', '                columns = if (compactScreen) GridCells.Adaptive(120.dp) else GridCells.Fixed(3),\n                contentPadding = PaddingValues(horizontal = if (compactScreen) 12.dp else 48.dp),\n                horizontalArrangement = Arrangement.spacedBy(if (compactScreen) 12.dp else 48.dp),\n                verticalArrangement = Arrangement.spacedBy(if (compactScreen) 18.dp else 36.dp),', 'ProfilePicker adaptive grid')
s = replace_once(s, 'private fun ProfileTile(profile: ProfileEntity, onClick: () -> Unit) {\n    var focused', 'private fun ProfileTile(profile: ProfileEntity, onClick: () -> Unit) {\n    val compactScreen = LocalConfiguration.current.screenWidthDp < 600\n    var focused', 'ProfileTile compact flag')
s = replace_once(s, '                .size(160.dp)', '                .size(if (compactScreen) 96.dp else 160.dp)', 'ProfileTile avatar size')
s = replace_once(s, '                Text(profile.avatarEmoji, fontSize = 72.sp)', '                Text(profile.avatarEmoji, fontSize = if (compactScreen) 46.sp else 72.sp)', 'ProfileTile emoji size')
s = replace_once(s, '                    fontSize = 64.sp,', '                    fontSize = if (compactScreen) 40.sp else 64.sp,', 'ProfileTile initial size')
s = replace_once(s, '            fontSize = 18.sp,', '            fontSize = if (compactScreen) 15.sp else 18.sp,', 'ProfileTile name size')
write(p, s)

# 10) Profile management: compact page margins on a phone.
p = "app/src/main/java/nl/vanvrouwerff/iptv/ui/profiles/ProfilesScreen.kt"
s = read(p)
s = replace_once(s, 'import androidx.compose.ui.graphics.Color\n', 'import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.platform.LocalConfiguration\n', 'Profiles config import')
s = replace_once(s, '    val state by vm.state.collectAsState()\n    var pendingDelete', '    val state by vm.state.collectAsState()\n    val compactScreen = LocalConfiguration.current.screenWidthDp < 600\n    var pendingDelete', 'Profiles compact flag')
s = replace_once(s, '.padding(horizontal = 48.dp, vertical = 32.dp)', '.padding(\n                        horizontal = if (compactScreen) 16.dp else 48.dp,\n                        vertical = if (compactScreen) 16.dp else 32.dp,\n                    )', 'Profiles adaptive padding')
write(p, s)

# 11) Movie/series detail scaffold: responsive text width, padding and swipeable action row.
p = "app/src/main/java/nl/vanvrouwerff/iptv/ui/detail/DetailScaffold.kt"
s = read(p)
s = replace_once(s, 'import androidx.compose.foundation.background\n', 'import androidx.compose.foundation.background\nimport androidx.compose.foundation.horizontalScroll\nimport androidx.compose.foundation.rememberScrollState\n', 'Detail scroll imports')
s = replace_once(s, 'import androidx.compose.ui.layout.ContentScale\n', 'import androidx.compose.ui.layout.ContentScale\nimport androidx.compose.ui.platform.LocalConfiguration\n', 'Detail config import')
s = replace_once(s, ') {\n    val kenBurns = rememberInfiniteTransition', ') {\n    val compactScreen = LocalConfiguration.current.screenWidthDp < 600\n    val kenBurns = rememberInfiniteTransition', 'Detail compact flag')
s = replace_once(s, '                Box(modifier = Modifier.fillMaxWidth().height(DETAIL_HEADER_HEIGHT)) {', '                Box(modifier = Modifier.fillMaxWidth().height(if (compactScreen) 360.dp else DETAIL_HEADER_HEIGHT)) {', 'Detail header height')
s = replace_once(s, '.padding(start = DETAIL_H_PADDING, end = 32.dp, bottom = 24.dp)', '.padding(\n                                start = if (compactScreen) 16.dp else DETAIL_H_PADDING,\n                                end = if (compactScreen) 16.dp else 32.dp,\n                                bottom = 24.dp,\n                            )', 'Detail header padding')
s = replace_once(s, '                        Column(modifier = Modifier.fillMaxWidth(0.66f)) {', '                        Column(modifier = Modifier.fillMaxWidth(if (compactScreen) 0.96f else 0.66f)) {', 'Detail text width')
s = replace_once(s, '                        Row(\n                            horizontalArrangement = Arrangement.spacedBy(12.dp),', '                        Row(\n                            modifier = Modifier.horizontalScroll(rememberScrollState()),\n                            horizontalArrangement = Arrangement.spacedBy(12.dp),', 'Detail swipeable actions')
s = replace_once(s, '    item(key = key) {\n        Column(', '    item(key = key) {\n        val compactScreen = LocalConfiguration.current.screenWidthDp < 600\n        val horizontalPadding = if (compactScreen) 16.dp else DETAIL_H_PADDING\n        Column(', 'Detail section compact padding var')
s = replace_once(s, '.padding(start = DETAIL_H_PADDING, end = DETAIL_H_PADDING, top = 20.dp, bottom = 8.dp)', '.padding(start = horizontalPadding, end = horizontalPadding, top = 20.dp, bottom = 8.dp)', 'Detail section padding')
s = replace_once(s, 'fun FocusableTextBlock(text: String, footer: List<String> = emptyList()) {\n    var focused', 'fun FocusableTextBlock(text: String, footer: List<String> = emptyList()) {\n    val compactScreen = LocalConfiguration.current.screenWidthDp < 600\n    var focused', 'Detail text block compact flag')
s = replace_once(s, '            .fillMaxWidth(0.75f)', '            .fillMaxWidth(if (compactScreen) 1f else 0.75f)', 'Detail text block width')
write(p, s)

print('Android phone support patch applied')
