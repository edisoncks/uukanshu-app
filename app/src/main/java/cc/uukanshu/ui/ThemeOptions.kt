package cc.uukanshu.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.uukanshu.data.prefs.Prefs

/**
 * The four modes of `Prefs.theme`, in the order the appearance row shows them
 * (see the theme contract in docs/ARCHITECTURE.md). A mode missing here has no
 * way to be selected, so this list is the UI half of that contract.
 *
 * It lives beside `AppTheme` rather than in a screen package: two screens pick
 * a theme, and neither may depend on the other to get at the list.
 */
internal val THEME_OPTIONS: List<Pair<String, String>> = listOf(
    Prefs.SYSTEM to "自動",
    Prefs.LIGHT to "淺色",
    Prefs.PAPER to "紙色",
    Prefs.DARK to "深色",
)

/**
 * The appearance picker: one segmented row over [THEME_OPTIONS], rendered by
 * the app settings screen and the reader settings sheet alike, so a theme is
 * one tap out of four in the same order and the same shape in both places
 * instead of two pickers that can drift apart.
 *
 * Labels are one line. A segment cannot hold more than a short label: the
 * Material3 content policy reserves the check icon's box plus its spacing
 * (18dp + 8dp) whatever the icon slot draws — passing an empty `icon` moves
 * the label, it does not reclaim the width — and adds 12dp of content padding
 * per side. On a 360dp-wide phone that leaves roughly 24dp for text, so a
 * large font scale outgrows the segment. [TextOverflow.Ellipsis] is the floor,
 * not a feature: the labels are kept to two glyphs (see `ThemeOptionsTest`)
 * so this only bites at accessibility font scales. The picker this replaced
 * showed the same four labels in 2x2 tiles with no such budget, and the
 * `自動` tile also carried a `跟隨系統` subtitle that a segment has no room
 * for — the mode's meaning now lives in docs/ARCHITECTURE.md § Theme, not in
 * the picker.
 */
@Composable
internal fun ThemeOptionsRow(
    selected: String,
    display: (String) -> String,
    onSelect: (String) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        THEME_OPTIONS.forEachIndexed { index, (value, name) ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = THEME_OPTIONS.size),
                label = { Text(display(name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}
