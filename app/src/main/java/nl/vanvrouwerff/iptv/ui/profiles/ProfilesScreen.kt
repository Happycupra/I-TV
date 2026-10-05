@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package nl.vanvrouwerff.iptv.ui.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import nl.vanvrouwerff.iptv.ui.common.isCompactTouchLayout
import nl.vanvrouwerff.iptv.ui.common.isTelevision
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import nl.vanvrouwerff.iptv.ui.common.TouchButton as Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import nl.vanvrouwerff.iptv.ui.common.TouchSurface as Surface
import androidx.tv.material3.Text
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette
import nl.vanvrouwerff.iptv.ui.common.tvKeyboardOnOk

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProfilesScreen(
    onBack: () -> Unit,
    onPicked: () -> Unit,
    vm: ProfilesViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val compactScreen = isCompactTouchLayout()
    var pendingDelete by remember { mutableStateOf<ProfileRow?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(IptvPalette.BackgroundDeep)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(
                        horizontal = if (compactScreen) 16.dp else 48.dp,
                        vertical = if (compactScreen) 16.dp else 32.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // A phone keyboard can leave only a small landscape viewport. Let the
                // editor use that space instead of reserving a fixed page header above it.
                if (!compactScreen || state.editing == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.profiles_title),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            color = IptvPalette.TextPrimary,
                            fontWeight = FontWeight.Black,
                        ),
                    )
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onBack) {
                        Text(
                            text = stringResource(R.string.detail_back),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.profiles_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = IptvPalette.TextSecondary,
                )

                }

                val defaultNameFormat = stringResource(R.string.profiles_default_new_name)
                val editing = state.editing
                if (editing != null) {
                    // Edit mode takes over the body: hiding the LazyColumn avoids the
                    // focus tug-of-war where the lazy column kept D-pad focus trapped on
                    // the "Add profile" / "Rename" button that opened the panel, so the
                    // user could never reach Save.
                    BackHandler(enabled = true, onBack = vm::cancelEditing)
                    EditingPanel(
                        editing = editing,
                        onName = vm::updateDraftName,
                        onColor = vm::updateDraftColor,
                        onEmoji = vm::updateDraftEmoji,
                        onKids = vm::updateDraftKids,
                        onCancel = vm::cancelEditing,
                        onSave = vm::saveEditing,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else if (pendingDelete != null) {
                    val target = pendingDelete!!
                    BackHandler(enabled = true) { pendingDelete = null }
                    DeleteConfirmPanel(
                        name = target.name,
                        onCancel = { pendingDelete = null },
                        onConfirm = {
                            vm.delete(target.id)
                            pendingDelete = null
                        },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.profiles, key = { it.id }) { row ->
                            ProfileRowCard(
                                row = row,
                                onSelect = {
                                    vm.select(row.id)
                                    onPicked()
                                },
                                onEdit = { vm.startEditing(row.id) },
                                onDelete = if (row.isDefault) null else ({ pendingDelete = row }),
                            )
                        }
                        item(key = "__new__") {
                            AddProfileButton(onClick = { vm.startCreating(defaultNameFormat) })
                        }
                    }
                }
            }
        }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProfileRowCard(
    row: ProfileRow,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    if (isCompactTouchLayout()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileSelection(row, onSelect, Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileActions(onEdit, onDelete)
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileSelection(row, onSelect, Modifier.weight(1f))
            ProfileActions(onEdit, onDelete)
        }
    }
}

@Composable
private fun ProfileSelection(row: ProfileRow, onSelect: () -> Unit, modifier: Modifier) {
    Surface(
            onClick = onSelect,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (row.isActive) IptvPalette.SurfaceLift else IptvPalette.SurfaceElevated,
                contentColor = IptvPalette.TextPrimary,
                focusedContainerColor = IptvPalette.SurfaceLift,
                focusedContentColor = IptvPalette.TextPrimary,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = modifier
                .then(
                    if (row.isActive)
                        Modifier.border(2.dp, IptvPalette.Accent, RoundedCornerShape(14.dp))
                    else Modifier,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(row.colorArgb)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = row.avatarEmoji ?: row.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                        ),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.isActive) {
                        Text(
                            text = stringResource(R.string.profiles_active),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = IptvPalette.Accent,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                }
            }
        }
}

@Composable
private fun ProfileActions(onEdit: () -> Unit, onDelete: (() -> Unit)?) {
        Button(onClick = onEdit) {
            Text(
                stringResource(R.string.profiles_rename),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (onDelete != null) {
            Button(onClick = onDelete) {
                Text(
                    stringResource(R.string.profiles_delete),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddProfileButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = IptvPalette.TextSecondary,
            focusedContainerColor = IptvPalette.SurfaceElevated,
            focusedContentColor = IptvPalette.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, IptvPalette.SurfaceLift, RoundedCornerShape(14.dp)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(IptvPalette.SurfaceLift),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = IptvPalette.TextSecondary,
                        fontWeight = FontWeight.Black,
                    ),
                )
            }
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.profiles_add),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EditingPanel(
    editing: EditingState,
    onName: (String) -> Unit,
    onColor: (Int) -> Unit,
    onEmoji: (String?) -> Unit,
    onKids: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Focus Save first (not the text field) for two reasons:
    // 1. The name is already pre-filled with a sensible default, so Save works
    //    immediately — one OK press and the profile is created / renamed.
    // 2. Focusing an OutlinedTextField on Android TV pops the IME over the panel,
    //    which both hides the Save button and confuses users who didn't intend
    //    to type anything.
    // Users who want to rename can D-pad up to reach the text field.
    val saveFocus = remember { FocusRequester() }
    LaunchedEffect(editing.id ?: "__new__") {
        runCatching { saveFocus.requestFocus() }
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(IptvPalette.SurfaceElevated)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(
                if (editing.isNew) R.string.profiles_add else R.string.profiles_rename,
            ),
            style = MaterialTheme.typography.titleMedium.copy(
                color = IptvPalette.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        OutlinedTextField(
            value = editing.name,
            onValueChange = onName,
            singleLine = true,
            label = { androidx.compose.material3.Text(stringResource(R.string.profiles_name_label)) },
            // Done on the on-screen keyboard commits the rename directly. Without this
            // the user has to dismiss the IME (which hides Save), navigate D-pad to the
            // Save button, and click it — an easy flow to lose your edit in.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, showKeyboardOnFocus = !LocalContext.current.isTelevision()),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
            modifier = Modifier.fillMaxWidth().tvKeyboardOnOk(),
        )
        Text(
            text = stringResource(R.string.profiles_color_label),
            style = MaterialTheme.typography.labelMedium,
            color = IptvPalette.TextSecondary,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(ProfileColorChoices, key = { it }) { argb ->
                ColorSwatch(
                    argb = argb,
                    selected = argb == editing.colorArgb,
                    onClick = { onColor(argb) },
                )
            }
        }
        Text(
            text = stringResource(R.string.profiles_emoji_label),
            style = MaterialTheme.typography.labelMedium,
            color = IptvPalette.TextSecondary,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(ProfileEmojiChoices, key = { it ?: "__none__" }) { emoji ->
                EmojiChip(
                    emoji = emoji,
                    selected = emoji == editing.avatarEmoji,
                    onClick = { onEmoji(emoji) },
                )
            }
        }
        nl.vanvrouwerff.iptv.ui.settings.SwitchRow(
            title = stringResource(R.string.profiles_kids_label),
            body = stringResource(R.string.profiles_kids_body),
            checked = editing.isKids,
            onToggle = { onKids(!editing.isKids) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onSave,
                modifier = Modifier.focusRequester(saveFocus),
            ) {
                Text(
                    stringResource(R.string.settings_save),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
            Button(onClick = onCancel) {
                Text(
                    stringResource(R.string.favorites_done),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ColorSwatch(argb: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(argb),
            contentColor = Color.White,
            focusedContainerColor = Color(argb),
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        modifier = Modifier
            .size(40.dp)
            .then(
                if (selected)
                    Modifier.border(3.dp, IptvPalette.TextPrimary, RoundedCornerShape(999.dp))
                else Modifier,
            ),
    ) { Box(Modifier.fillMaxSize()) }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EmojiChip(emoji: String?, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = IptvPalette.SurfaceLift,
            contentColor = IptvPalette.TextPrimary,
            focusedContainerColor = IptvPalette.Accent,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        modifier = Modifier
            .height(40.dp)
            .then(
                if (selected)
                    Modifier.border(3.dp, IptvPalette.TextPrimary, RoundedCornerShape(999.dp))
                else Modifier,
            ),
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(
                text = emoji ?: stringResource(R.string.profiles_emoji_none),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DeleteConfirmPanel(
    name: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.profiles_delete_title, name),
            style = MaterialTheme.typography.headlineSmall.copy(
                color = IptvPalette.TextPrimary,
                fontWeight = FontWeight.Bold,
            ),
        )
        Text(
            text = stringResource(R.string.profiles_delete_body),
            style = MaterialTheme.typography.bodyMedium,
            color = IptvPalette.TextSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus)) {
                Text(
                    stringResource(R.string.profiles_delete_cancel),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            Button(onClick = onConfirm) {
                Text(
                    stringResource(R.string.profiles_delete),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}
