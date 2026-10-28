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
 * What the label has to work with: on a 360dp phone the card leaves 296dp for
 * four segments, so 74dp each, less 12dp of content padding per side
 * (material3 1.3.0 hardcodes it — `SegmentedButton` has no `contentPadding`
 * parameter) = 49dp of inner width. `SegmentedButtonContentMeasurePolicy`
 * measures the label against that inner width and *then* adds the check icon's
 * box and spacing (`maxOf(18dp, iconWidth) + 8dp`), so the label may render
 * past its segment by up to those 26dp even though it was never measured
 * against the width it really had; two 14sp glyphs (≈28dp) plus the 26dp is
 * 54dp against 49dp, and `Surface` clips the overrun. 393dp and up fit.
 *
 * The label is deliberately not capped here. Capping it needs the segment's
 * real width, and the obvious way to ask for it — `BoxWithConstraints` — is a
 * `SubcomposeLayout`, which throws `NoIntrinsicsMessage` because this row
 * measures intrinsics (`width(IntrinsicSize.Min)` in the row, `height(
 * IntrinsicSize.Min)` around this content). That was v1.2.26: both settings
 * screens crashed on open. A width cap has to come from arithmetic or from a
 * row of our own, never from a subcomposing child.
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
