package nl.vanvrouwerff.iptv.ui.profilepicker

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import nl.vanvrouwerff.iptv.ui.common.isCompactTouchLayout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.data.db.ProfileEntity
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette

@Composable
fun ProfilePickerScreen(
    onPicked: () -> Unit,
    onManageProfiles: () -> Unit = {},
    vm: ProfilePickerViewModel = viewModel(),
) {
    val profiles by vm.profiles.collectAsState()
    val compactScreen = isCompactTouchLayout()
    val app = nl.vanvrouwerff.iptv.IptvApp.get()
    val pin by app.settings.parentalPin.collectAsState(initial = "")
    val activeId by app.activeProfileId.collectAsState()
    val kids by app.kidsMode.collectAsState()
    var pendingProfile by remember { mutableStateOf<ProfileEntity?>(null) }
    var pinError by remember { mutableStateOf<String?>(null) }
    val wrongPin = stringResource(R.string.pin_wrong)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IptvPalette.BackgroundDeep),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = if (compactScreen) 16.dp else 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.profile_picker_title),
                modifier = Modifier.padding(horizontal = 16.dp),
                fontSize = if (compactScreen) 28.sp else 44.sp,
                fontWeight = FontWeight.ExtraBold,
                color = IptvPalette.TextPrimary,
            )
            Spacer(Modifier.height(if (compactScreen) 20.dp else 48.dp))
            LazyVerticalGrid(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                columns = if (compactScreen) GridCells.Adaptive(120.dp) else GridCells.Fixed(3),
                contentPadding = PaddingValues(horizontal = if (compactScreen) 12.dp else 48.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compactScreen) 12.dp else 48.dp),
                verticalArrangement = Arrangement.spacedBy(if (compactScreen) 18.dp else 36.dp),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileTile(
                        profile = profile,
                        onClick = {
                            if (kids && pin.isNotEmpty() && profile.id != activeId) {
                                pinError = null
                                pendingProfile = profile
                            } else {
                                vm.onProfileSelected(profile, onPicked)
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(36.dp))
            nl.vanvrouwerff.iptv.ui.common.TouchButton(onClick = onManageProfiles) {
                androidx.tv.material3.Text(
                    text = stringResource(R.string.profiles_manage),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        pendingProfile?.let { target ->
            nl.vanvrouwerff.iptv.ui.parental.PinPad(
                title = stringResource(R.string.pin_enter),
                error = pinError,
                onComplete = { entered ->
                    if (entered == pin) {
                        pendingProfile = null
                        vm.onProfileSelected(target, onPicked)
                    } else {
                        pinError = wrongPin
                    }
                },
                onCancel = { pendingProfile = null },
            )
        }
    }
}

@Composable
private fun ProfileTile(profile: ProfileEntity, onClick: () -> Unit) {
    val compactScreen = isCompactTouchLayout()
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.10f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "profile-tile-scale",
    )
    val focusRequester = remember { FocusRequester() }
    // First profile auto-focuses so the D-pad has a clear landing spot.
    LaunchedEffect(profile.id) {
        if (profile.sortIndex == 0) runCatching { focusRequester.requestFocus() }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .scale(scale)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(if (compactScreen) 96.dp else 160.dp)
                .clip(CircleShape)
                .background(Color(profile.colorArgb)),
            contentAlignment = Alignment.Center,
        ) {
            if (profile.avatarEmoji != null) {
                Text(profile.avatarEmoji, fontSize = if (compactScreen) 46.sp else 72.sp)
            } else {
                Text(
                    text = profile.name.take(1).uppercase(),
                    fontSize = if (compactScreen) 40.sp else 64.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White.copy(alpha = 0.92f),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            profile.name,
            maxLines = 2,
            fontSize = if (compactScreen) 15.sp else 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (focused) IptvPalette.TextPrimary else IptvPalette.TextSecondary,
        )
    }
}
