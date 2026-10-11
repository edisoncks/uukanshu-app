package cc.uukanshu.ui.reader

/**
 * Pure reader typography math: one formula for content + preview + tests.
 *
 * Base matches the old hardcoded ReaderContent (17sp / 28sp / 12dp bottom).
 * Scales multiply orthogonally so font size and looseness stay independent.
 */
object ReaderTypography {
    const val BASE_FONT_SP = 17f
    const val BASE_LINE_SP = 28f
    const val BASE_PARA_DP = 12f

    fun fontSizeSp(fontScale: Float): Float = BASE_FONT_SP * fontScale

    fun lineHeightSp(fontScale: Float, lineScale: Float): Float =
        BASE_LINE_SP * fontScale * lineScale

    fun paragraphSpacingDp(paraScale: Float): Float = BASE_PARA_DP * paraScale
}
