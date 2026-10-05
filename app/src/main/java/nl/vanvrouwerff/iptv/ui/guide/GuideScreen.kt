@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package nl.vanvrouwerff.iptv.ui.guide

import nl.vanvrouwerff.iptv.data.DisplayNames
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import nl.vanvrouwerff.iptv.ui.common.isCompactTouchLayout
import nl.vanvrouwerff.iptv.ui.common.isTelevision
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import nl.vanvrouwerff.iptv.ui.common.TouchButton
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import nl.vanvrouwerff.iptv.ui.common.TouchSurface as Surface
import androidx.tv.material3.Text
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.catchup.Catchup
import nl.vanvrouwerff.iptv.data.db.ProgrammeEntity
import nl.vanvrouwerff.iptv.ui.channels.CategoryItem
import nl.vanvrouwerff.iptv.ui.theme.FocusStyle
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette
import nl.vanvrouwerff.iptv.ui.theme.tvFocus
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.delay

/**
 * Programme guide: live channels down, 3.5 hours across. Opens on the favourites; the chip
 * row at the top switches to a category. OK on a programme that is on now zaps to it.
 */
@Composable
fun GuideScreen(
    onBack: () -> Unit,
    onPlay: (Channel, List<Channel>) -> Unit,
    onPlayItem: (Channel) -> Unit,
    vm: GuideViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(Unit) { vm.playRequests.collect { onPlayItem(it) } }
    val state by vm.state.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    val compactScreen = isCompactTouchLayout()
    val touchDevice = !LocalContext.current.isTelevision()
    val locale = LocalConfiguration.current.locales[0]
    BackHandler(enabled = true, onBack = onBack)

    Box(modifier = Modifier.fillMaxSize().background(IptvPalette.BackgroundDeep)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = if (compactScreen) 12.dp else 48.dp,
                    vertical = if (compactScreen) 12.dp else 20.dp,
                ),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.guide_title),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = IptvPalette.TextPrimary,
                )
                if (!state.loading && state.fromMs > 0L) {
                    Text(dayLabel(state.fromMs), style = MaterialTheme.typography.titleMedium, color = IptvPalette.TextSecondary)
                    TimeButton(stringResource(R.string.guide_earlier)) { vm.shiftWindow(-GuideViewModel.STEP_MS) }
                    TimeButton(stringResource(R.string.guide_now)) { vm.goToNow() }
                    TimeButton(stringResource(R.string.guide_later)) { vm.shiftWindow(GuideViewModel.STEP_MS) }
                }
                TouchButton(onClick = vm::syncEpg, enabled = !state.epgRefreshing) {
                    Text(stringResource(if (state.epgRefreshing) R.string.guide_epg_syncing else R.string.guide_epg_sync))
                }
            }
            Text(
                text = state.epgError ?: if (state.lastEpgRefreshAt > 0L) {
                    stringResource(R.string.guide_epg_updated, SimpleDateFormat("dd.MM. HH:mm", locale).format(Date(state.lastEpgRefreshAt)))
                } else stringResource(R.string.guide_epg_never_synced),
                color = if (state.epgError != null) IptvPalette.Accent else IptvPalette.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(10.dp))
            if (state.loading) {
                Text(stringResource(R.string.detail_loading), color = IptvPalette.TextSecondary)
                return@Column
            }
            if (state.groups.isEmpty()) {
                Text(stringResource(R.string.channels_empty_type_tv), color = IptvPalette.TextSecondary)
                return@Column
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(state.groups, key = { _, g -> g.title }) { i, g ->
                    Box(Modifier.width(if (compactScreen) 140.dp else 200.dp)) {
                        CategoryItem(
                            label = DisplayNames.clean(g.title),
                            selected = i == state.groupIndex,
                            onClick = { vm.selectGroup(i) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val group = state.group ?: return@Column
            if (touchDevice) {
                MobileGuideList(
                    channels = group.channels,
                    programmesByKey = state.programmesByKey,
                    numberById = state.numberById,
                    fromMs = state.fromMs,
                    toMs = state.toMs,
                    now = now,
                    reminderKeys = state.reminderKeys,
                    onPlay = { onPlay(it, group.channels) },
                    onPast = vm::openPast,
                    onFuture = vm::toggleReminder,
                )
            } else BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val timelineWidth = maxWidth - CHANNEL_CELL_WIDTH
                val window = (state.toMs - state.fromMs).coerceAtLeast(1L)
                fun x(ms: Long): Dp = timelineWidth * ((ms - state.fromMs).toFloat() / window)
                Column {
                    TimeRuler(fromMs = state.fromMs, toMs = state.toMs, x = ::x)
                    Spacer(Modifier.height(6.dp))
                    val firstFocus = remember(state.groupIndex) { FocusRequester() }
                    LaunchedEffect(state.groupIndex, state.programmesByKey.isNotEmpty()) {
                        androidx.compose.runtime.withFrameNanos { }
                        runCatching { firstFocus.requestFocus() }
                    }
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(bottom = 32.dp),
                    ) {
                        itemsIndexed(group.channels, key = { _, ch -> ch.id }) { rowIndex, ch ->
                            val programmes = ch.epgChannelId?.let { state.programmesByKey[it] }.orEmpty()
                            GuideRow(
                                channel = ch,
                                number = state.numberById[ch.id],
                                programmes = programmes,
                                now = now,
                                fromMs = state.fromMs,
                                toMs = state.toMs,
                                x = ::x,
                                timelineWidth = timelineWidth,
                                firstFocus = if (rowIndex == 0) firstFocus else null,
                                onPlay = { onPlay(ch, group.channels) },
                                onPast = { p -> vm.openPast(ch, p) },
                                onFuture = { p -> vm.toggleReminder(ch, p) },
                                reminderKeys = state.reminderKeys,
                            )
                        }
                    }
                }
                // "Now" marker across the grid.
                val nowX = x(now)
                if (nowX > 0.dp && nowX < timelineWidth) {
                    Box(
                        modifier = Modifier
                            .offset(x = CHANNEL_CELL_WIDTH + nowX)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(IptvPalette.Accent.copy(alpha = 0.8f)),
                    )
                }
            }
        }
        state.message?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                color = IptvPalette.TextPrimary,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .background(IptvPalette.SurfaceElevated, RoundedCornerShape(999.dp))
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun dayLabel(ms: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = java.time.LocalDate.now(zone)
    val time = SimpleDateFormat("HH:mm", locale).format(Date(ms))
    val name = when (day) {
        today -> stringResource(R.string.guide_today)
        today.minusDays(1) -> stringResource(R.string.guide_yesterday)
        today.plusDays(1) -> stringResource(R.string.guide_tomorrow)
        else -> SimpleDateFormat("EEEE d MMMM", locale).format(Date(ms))
    }
    return "$name · $time"
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TimeButton(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(999.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = IptvPalette.SurfaceElevated,
            contentColor = IptvPalette.TextSecondary,
            focusedContainerColor = FocusStyle.Fill,
            focusedContentColor = IptvPalette.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .tvFocus(focused, shape, FocusStyle.ChipScale),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun TimeRuler(fromMs: Long, toMs: Long, x: (Long) -> Dp) {
    val locale = LocalConfiguration.current.locales[0]
    val fmt = remember(locale) { SimpleDateFormat("HH:mm", locale) }
    Box(modifier = Modifier.fillMaxWidth().height(20.dp)) {
        var t = fromMs
        while (t < toMs) {
            Text(
                text = fmt.format(Date(t)),
                style = MaterialTheme.typography.labelSmall,
                color = IptvPalette.TextTertiary,
                modifier = Modifier.offset(x = CHANNEL_CELL_WIDTH + x(t)),
            )
            t += GuideViewModel.HALF_HOUR_MS
        }
    }
}

@Composable
private fun GuideRow(
    channel: Channel,
    number: Int?,
    programmes: List<ProgrammeEntity>,
    now: Long,
    fromMs: Long,
    toMs: Long,
    x: (Long) -> Dp,
    timelineWidth: Dp,
    firstFocus: FocusRequester?,
    onPlay: () -> Unit,
    onPast: (ProgrammeEntity) -> Unit,
    onFuture: (ProgrammeEntity) -> Unit,
    reminderKeys: Set<String>,
) {
    Row(modifier = Modifier.height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.width(CHANNEL_CELL_WIDTH).padding(end = 10.dp)) {
            Text(
                text = listOfNotNull(number?.let { "%03d".format(it) }, channel.name).joinToString("  "),
                style = MaterialTheme.typography.labelLarge,
                color = IptvPalette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(modifier = Modifier.width(timelineWidth).fillMaxHeight()) {
            val visible = programmes.filter { it.stopMs > fromMs && it.startMs < toMs }
            if (visible.isEmpty()) {
                GuideCell(
                    title = stringResource(R.string.guide_no_data),
                    live = true,
                    modifier = Modifier
                        .width(timelineWidth)
                        .then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier),
                    onClick = onPlay,
                )
            } else {
                val liveIndex = visible.indexOfFirst { it.startMs <= now && it.stopMs > now }
                visible.forEachIndexed { i, p ->
                    val start = maxOf(p.startMs, fromMs)
                    val end = minOf(p.stopMs, toMs)
                    val live = p.startMs <= now && p.stopMs > now
                    val past = p.stopMs <= now
                    val replayable = past && Catchup.isAvailable(channel, p.startMs, p.stopMs, now)
                    val takesFirstFocus = firstFocus != null && i == (if (liveIndex >= 0) liveIndex else 0)
                    val reminded = GuideViewModel.reminderKey(channel.id, p.startMs) in reminderKeys
                    GuideCell(
                        title = when {
                            replayable -> "↺ ${p.title}"
                            reminded -> "🔔 ${p.title}"
                            else -> p.title
                        },
                        live = live,
                        dimmed = past && !replayable,
                        modifier = Modifier
                            .offset(x = x(start))
                            .width((x(end) - x(start) - 2.dp).coerceAtLeast(8.dp))
                            .then(if (takesFirstFocus) Modifier.focusRequester(firstFocus!!) else Modifier),
                        onClick = {
                            val clickTime = System.currentTimeMillis()
                            when {
                                p.startMs <= clickTime && p.stopMs > clickTime -> onPlay()
                                p.stopMs <= clickTime -> onPast(p)
                                else -> onFuture(p)
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GuideCell(
    title: String,
    live: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    dimmed: Boolean = false,
    maxLines: Int = 2,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (live) IptvPalette.SurfaceElevated else IptvPalette.SurfaceLift,
            contentColor = when {
                live -> IptvPalette.TextPrimary
                dimmed -> IptvPalette.TextTertiary
                else -> IptvPalette.TextSecondary
            },
            focusedContainerColor = FocusStyle.Fill,
            focusedContentColor = IptvPalette.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
            .fillMaxHeight()
            .onFocusChanged { focused = it.isFocused }
            .tvFocus(focused, shape, focusedScale = 1f),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

/** Phone guide: channel names remain visible and programmes have readable, scrollable cards. */
@Composable
private fun MobileGuideList(
    channels: List<Channel>,
    programmesByKey: Map<String, List<ProgrammeEntity>>,
    numberById: Map<String, Int>,
    fromMs: Long,
    toMs: Long,
    now: Long,
    reminderKeys: Set<String>,
    onPlay: (Channel) -> Unit,
    onPast: (Channel, ProgrammeEntity) -> Unit,
    onFuture: (Channel, ProgrammeEntity) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val time = remember(locale) { SimpleDateFormat("HH:mm", locale) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        items(channels, key = { it.id }) { channel ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TouchButton(onClick = { onPlay(channel) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        listOfNotNull(numberById[channel.id]?.let { "%03d".format(it) }, DisplayNames.clean(channel.name)).joinToString("  "),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                val visible = remember(programmesByKey, channel.epgChannelId, fromMs, toMs) {
                    channel.epgChannelId?.let { programmesByKey[it] }.orEmpty().filter { it.stopMs > fromMs && it.startMs < toMs }
                }
                if (visible.isEmpty()) {
                    GuideCell(stringResource(R.string.guide_no_data), false, Modifier.fillMaxWidth().height(64.dp), { onPlay(channel) })
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(visible, key = { it.startMs }) { programme ->
                            val live = programme.startMs <= now && programme.stopMs > now
                            val past = programme.stopMs <= now
                            val replayable = past && Catchup.isAvailable(channel, programme.startMs, programme.stopMs, now)
                            val reminded = GuideViewModel.reminderKey(channel.id, programme.startMs) in reminderKeys
                            val prefix = when { replayable -> "↺ "; reminded -> "🔔 "; else -> "" }
                            GuideCell(
                                title = "${time.format(Date(programme.startMs))} – ${time.format(Date(programme.stopMs))}\n$prefix${programme.title}",
                                live = live,
                                dimmed = past && !replayable,
                                modifier = Modifier.width(240.dp).height(88.dp),
                                maxLines = 3,
                                onClick = {
                                    val clickTime = System.currentTimeMillis()
                                    when {
                                        programme.startMs <= clickTime && programme.stopMs > clickTime -> onPlay(channel)
                                        programme.stopMs <= clickTime -> onPast(channel, programme)
                                        else -> onFuture(channel, programme)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private val CHANNEL_CELL_WIDTH = 190.dp
private val ROW_HEIGHT = 52.dp
