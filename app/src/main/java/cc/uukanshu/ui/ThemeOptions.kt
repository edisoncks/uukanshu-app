package cc.uukanshu.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
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
 * The four modes of `Prefs.theme`, in the order the appearance card shows them
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
 * The appearance card: one segmented row over [THEME_OPTIONS], rendered by the
 * app settings screen and the reader settings sheet alike. The card, the row
 * and the padding live here together so the two screens share the whole
 * control and not just its labels.
 *
 * Constraint: the label stays a plain `Text`. Capping its width needs the
 * segment's real width, and the natural way to ask (`BoxWithConstraints`) is a
 * `SubcomposeLayout`, which throws inside `SegmentedButton`'s intrinsic
 * measurement and takes both settings surfaces down on open. A width cap has to
 * come from arithmetic or a hand-rolled row, never from a subcomposing child.
 * `ThemeOptionsCardTest` bounds-locks the labels; see docs/ARCHITECTURE.md
 * § Screens (Settings) for the measured budget and the one excluded case.
 */
@Composable
internal fun ThemeOptionsCard(
    selected: String,
    display: (String) -> String,
    onSelect: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            THEME_OPTIONS.forEachIndexed { index, (value, name) ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = THEME_OPTIONS.size),
                    label = {
                        Text(display(name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        }
    }
}
