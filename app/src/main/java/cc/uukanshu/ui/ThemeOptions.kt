package cc.uukanshu.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
 * What Material3 reserves beside a segmented button's label: the check icon's
 * box plus its spacing. `SegmentedButtonContentMeasurePolicy` adds
 * `maxOf(IconSize, iconWidth) + IconSpacing` to the label's width whatever the
 * icon slot draws, so an empty `icon` only shifts the label — it reclaims
 * nothing. The 12dp content padding per side is not a parameter in material3
 * 1.3.0 either, so the label slot has to pay for both.
 */
private val IconReserve = 18.dp + 8.dp

/**
 * The appearance card: one segmented row over [THEME_OPTIONS], rendered by the
 * app settings screen and the reader settings sheet alike. The card, the row
 * and the padding live here together so the two screens share the whole
 * control and not just its labels.
 *
 * Width, measured rather than hoped for: the label slot is capped at
 * `segment - 24dp content padding - [IconReserve]`, so however narrow the
 * device is the label ellipsizes inside its segment instead of overflowing it.
 * That cap is the fix for a real defect: `SegmentedButton` measures the label
 * with the full inner width and *then* adds the icon box and spacing, so an
 * unconstrained `Text` reports its full intrinsic width (`自動` at default
 * scale ≈ 28dp), the row lays out wider than the segment, and `Surface` clips
 * the glyph edges — the ellipsis never fires because the label was never
 * measured against the width it actually had.
 *
 * What is left for text on a 360dp phone: 360 − 32 screen padding − 32 card
 * padding = 296dp over four segments = 74dp each, minus 24dp padding and 26dp
 * icon reserve = 24dp, which is short of a two-glyph 14sp label (≈28dp). So a
 * 360dp device ellipsizes one glyph at default font scale, 393dp and up show
 * both. `ThemeOptionsTest.labelsStayTwoGlyphs` keeps labels short enough that
 * this is the worst case; it cannot see layout, and a device check is owed.
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
                        BoxWithConstraints {
                            Text(
                                display(name),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(
                                    max = (maxWidth - IconReserve).coerceAtLeast(1.dp),
                                ),
                            )
                        }
                    },
                )
            }
        }
    }
}
