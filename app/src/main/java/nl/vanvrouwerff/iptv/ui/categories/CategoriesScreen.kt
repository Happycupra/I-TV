package nl.vanvrouwerff.iptv.ui.categories

import nl.vanvrouwerff.iptv.data.DisplayNames
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import nl.vanvrouwerff.iptv.ui.common.isCompactTouchLayout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.ContentType
import nl.vanvrouwerff.iptv.ui.channels.CategoryItem
import nl.vanvrouwerff.iptv.ui.channels.LogoCard
import nl.vanvrouwerff.iptv.ui.channels.PosterCard
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette

/**
 * Browse every category of one content type: category list on the left, all of its items
 * in a lazy grid on the right. Unlike the home rails this has no per-rail or rail-count cap,
 * so nothing the provider offers is only reachable through search.
 */
@Composable
fun CategoriesScreen(
    type: ContentType,
    initialCategory: String?,
    onBack: () -> Unit,
    onOpen: (Channel, List<Channel>) -> Unit,
    vm: CategoriesViewModel = viewModel(key = "categories-${type.name}"),
) {
    LaunchedEffect(type, initialCategory) { vm.load(type, initialCategory) }
    val state by vm.state.collectAsState()
    val compactScreen = isCompactTouchLayout()
    BackHandler(enabled = true, onBack = onBack)

    val selectedFocus = remember { FocusRequester() }
    var focusedOnce = remember { booleanArrayOf(false) }
    LaunchedEffect(state.selected, state.categories.isNotEmpty()) {
        if (!focusedOnce[0] && state.selected != null) {
            androidx.compose.runtime.withFrameNanos { }
            if (runCatching { selectedFocus.requestFocus() }.isSuccess) focusedOnce[0] = true
        }
    }

    val titleRes = when (type) {
        ContentType.TV -> R.string.tab_tv
        ContentType.MOVIE -> R.string.tab_movies
        ContentType.SERIES -> R.string.tab_series
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IptvPalette.BackgroundDeep)
            .padding(
                horizontal = if (compactScreen) 12.dp else 48.dp,
                vertical = if (compactScreen) 12.dp else 24.dp,
            ),
    ) {
        Text(
            text = stringResource(R.string.categories_title, stringResource(titleRes)),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                color = IptvPalette.TextPrimary,
            ),
        )
        Spacer(Modifier.height(16.dp))
        if (compactScreen) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(state.categories, key = { _, category -> category }) { _, category ->
                    Box(Modifier.width(200.dp)) {
                        CategoryItem(DisplayNames.clean(category), category == state.selected, { vm.select(category) })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            CategoryContent(state, type, onOpen, Modifier.fillMaxSize())
        } else Row(modifier = Modifier.fillMaxSize()) {
            val selectedIndex = state.categories.indexOf(state.selected).coerceAtLeast(0)
            val listState = remember(state.categories.isNotEmpty()) {
                androidx.compose.foundation.lazy.LazyListState(selectedIndex, 0)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .width(if (compactScreen) 116.dp else 300.dp)
                    .fillMaxHeight()
                    .background(IptvPalette.SurfaceElevated, RoundedCornerShape(14.dp))
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(state.categories, key = { _, c -> c }) { _, cat ->
                    val isSelected = cat == state.selected
                    Box(
                        modifier = if (isSelected) Modifier.focusRequester(selectedFocus) else Modifier,
                    ) {
                        CategoryItem(
                            label = DisplayNames.clean(cat),
                            selected = isSelected,
                            onClick = { vm.select(cat) },
                        )
                    }
                }
            }
            Spacer(Modifier.width(if (compactScreen) 8.dp else 24.dp))
            CategoryContent(state, type, onOpen, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun CategoryContent(
    state: CategoryBrowseState,
    type: ContentType,
    onOpen: (Channel, List<Channel>) -> Unit,
    modifier: Modifier,
) {
            val playable = remember(state.items) { state.items.filter { it.streamUrl != null } }
            if (!state.loading && state.items.isEmpty()) {
                Box(modifier, contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.categories_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = IptvPalette.TextSecondary,
                    )
                }
            } else androidx.compose.runtime.key(state.selected) {
                LazyVerticalGrid(
                    columns = when {
                        isCompactTouchLayout() && type == ContentType.TV -> GridCells.Adaptive(150.dp)
                        isCompactTouchLayout() -> GridCells.Adaptive(132.dp)
                        type == ContentType.TV -> GridCells.Adaptive(220.dp)
                        else -> GridCells.Adaptive(168.dp)
                    },
                    modifier = modifier,
                    contentPadding = PaddingValues(top = 12.dp, bottom = 48.dp, start = 12.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(state.items, key = { it.id }) { channel ->
                        when (type) {
                            ContentType.TV -> LogoCard(
                                channel = channel,
                                progressFraction = null,
                                onClick = { if (channel.streamUrl != null) onOpen(channel, playable) },
                            )
                            ContentType.MOVIE, ContentType.SERIES -> PosterCard(
                                channel = channel,
                                progressFraction = null,
                                onClick = { onOpen(channel, playable) },
                            )
                        }
                    }
                }
            }
}
