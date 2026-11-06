package io.embodit.yarddrive.ui.theme

import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.fonts.Font as PlatformFont
import android.graphics.fonts.FontFamily as PlatformFontFamily
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Typography
import io.embodit.yarddrive.R

/**
 * One type family for the whole app: Noto Sans for Latin and digits, Noto Sans SC for Chinese, both
 * bundled (SIL OFL 1.1, see third_party/). The system font only covers what both lack.
 *
 * Compose resolves a FontFamily to a single font per weight, so the per-glyph order
 * "Noto Sans -> Noto Sans SC -> system" is built as one platform Typeface with a custom fallback.
 * The Chinese font is a variable-font subset (tools/subset_cjk_font.py); one instance per weight.
 */
private val Weights = intArrayOf(400, 500, 600, 700)

private fun buildFontFamily(resources: Resources): FontFamily =
    try {
        fun latin(id: Int, weight: Int): PlatformFont = PlatformFont.Builder(resources, id).setWeight(weight).build()
        fun chinese(weight: Int): PlatformFont =
            PlatformFont.Builder(resources, R.font.noto_sans_sc_subset)
                .setFontVariationSettings("'wght' $weight")
                .setWeight(weight)
                .build()
        val latinFamily =
            PlatformFontFamily.Builder(latin(R.font.noto_sans_regular, 400))
                .addFont(latin(R.font.noto_sans_medium, 500))
                .addFont(latin(R.font.noto_sans_semibold, 600))
                .addFont(latin(R.font.noto_sans_bold, 700))
                .build()
        val chineseBuilder = PlatformFontFamily.Builder(chinese(Weights[0]))
        for (i in 1 until Weights.size) chineseBuilder.addFont(chinese(Weights[i]))
        val typeface =
            Typeface.CustomFallbackBuilder(latinFamily)
                .addCustomFallback(chineseBuilder.build())
                .setSystemFallback("sans-serif")
                .build()
        YdWeights.base = typeface
        FontFamily(typeface)
    } catch (t: Throwable) {
        FontFamily(
            Font(R.font.noto_sans_regular, FontWeight.Normal),
            Font(R.font.noto_sans_medium, FontWeight.Medium),
            Font(R.font.noto_sans_semibold, FontWeight.SemiBold),
            Font(R.font.noto_sans_bold, FontWeight.Bold),
        )
    }

@Composable
internal fun rememberYdFontFamily(): FontFamily {
    val resources = LocalContext.current.resources
    return remember(resources) { buildFontFamily(resources) }
}

/** Keeps the PicoTheme type scale and swaps the family of every role. */
internal fun Typography.withFamily(family: FontFamily): Typography {
    fun TextStyle.inFamily(): TextStyle = copy(fontFamily = family)
    return copy(
        bodyLarge = bodyLarge.inFamily(),
        bodyLargeMultiline = bodyLargeMultiline.inFamily(),
        bodyMedium = bodyMedium.inFamily(),
        bodyMediumMultiline = bodyMediumMultiline.inFamily(),
        bodySmall = bodySmall.inFamily(),
        bodyTiny = bodyTiny.inFamily(),
        displayLarge = displayLarge.inFamily(),
        displayMedium = displayMedium.inFamily(),
        displaySmall = displaySmall.inFamily(),
        headlineLarge = headlineLarge.inFamily(),
        headlineMedium = headlineMedium.inFamily(),
        headlineSmall = headlineSmall.inFamily(),
        labelLarge = labelLarge.inFamily(),
        labelMedium = labelMedium.inFamily(),
        labelSmall = labelSmall.inFamily(),
        titleLarge = titleLarge.inFamily(),
        titleMedium = titleMedium.inFamily(),
        titleSmall = titleSmall.inFamily(),
    )
}


/**
 * A FontFamily made from one Typeface ignores TextStyle.fontWeight (every role rendered at 400, q1-7: thin strokes blur
 * below 4.5:1 on the emulator), so each weight gets its own family cut from the same fallback typeface.
 */
internal object YdWeights {
    var base: Typeface? = null
    private val cache = HashMap<Int, FontFamily>()

    fun of(style: TextStyle, w: FontWeight): TextStyle {
        val b = base ?: return style.copy(fontWeight = w)
        val family = cache.getOrPut(w.weight) { FontFamily(Typeface.create(b, w.weight, false)) }
        return style.copy(fontFamily = family, fontWeight = w)
    }
}

/** Type roles of the design (typography): P2 at 1.10 m and scale 0.8, so the small line is 18 sp, not labelLarge 14. */
object YdType {
    val headline: TextStyle
        @Composable get() = YdWeights.of(PicoTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp), FontWeight.SemiBold)

    /** The charged stop on its disc (k2-3: thin small digits blur to about 4.7:1 on the emulator). */
    val ring: TextStyle
        @Composable get() = YdWeights.of(PicoTheme.typography.titleMedium.copy(fontSize = 24.sp, lineHeight = 28.sp), FontWeight.Bold)

    /** Danger confirm question: bold on the danger fill (q1-7). */
    val titleBold: TextStyle
        @Composable get() = YdWeights.of(PicoTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp), FontWeight.Bold)

    val title: TextStyle
        @Composable get() = YdWeights.of(PicoTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp), FontWeight.SemiBold)

    val body: TextStyle
        @Composable get() = YdWeights.of(PicoTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 25.sp), FontWeight.Medium)

    val label: TextStyle
        @Composable get() = YdWeights.of(PicoTheme.typography.labelLarge.copy(fontSize = 16.sp, lineHeight = 22.sp), FontWeight.SemiBold)

    val small: TextStyle
        @Composable get() = YdWeights.of(PicoTheme.typography.labelLarge.copy(fontSize = 15.sp, lineHeight = 21.sp), FontWeight.SemiBold)

    /** P2 gear letter (headlineMedium 56, tabular). */
    val gear: TextStyle
        @Composable get() = YdWeights.of(PicoTheme.typography.headlineMedium.copy(fontSize = 56.sp, lineHeight = 60.sp), FontWeight.Bold)

    /** P2 coach cue (headlineSmall 32). */
    val cue: TextStyle
        @Composable get() = YdWeights.of(PicoTheme.typography.headlineSmall.copy(fontSize = 32.sp, lineHeight = 38.sp), FontWeight.Bold)

    /** Result score (displaySmall 40, tabular). */
    val score: TextStyle
        @Composable get() = YdWeights.of(PicoTheme.typography.displaySmall.copy(fontSize = 40.sp, lineHeight = 46.sp), FontWeight.Bold)

    /** HUB turns (titleLarge 26). */
    val hub: TextStyle
        @Composable get() = YdWeights.of(PicoTheme.typography.titleLarge.copy(fontSize = 26.sp, lineHeight = 30.sp), FontWeight.Bold)

    /** MRD-R reading and label (titleLarge 22, 1.3 m from the eye: design r4, QA #3 k3-2). A line takes 30 dp (the font's own height, a shorter lineHeight does not help): two fit in 64 dp. */
    val mrdR: TextStyle
        @Composable get() = YdWeights.of(PicoTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 30.sp), FontWeight.Bold)

    /** MRD reading (titleMedium 20). */
    val mrd: TextStyle
        @Composable get() = YdWeights.of(PicoTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 24.sp), FontWeight.Bold)
}
