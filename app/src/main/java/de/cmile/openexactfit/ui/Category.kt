package de.cmile.openexactfit.ui

import androidx.compose.ui.graphics.Color
import de.cmile.openexactfit.R
import de.cmile.openexactfit.ui.theme.CategoryCrisis
import de.cmile.openexactfit.ui.theme.CategoryElevated
import de.cmile.openexactfit.ui.theme.CategoryHigh
import de.cmile.openexactfit.ui.theme.CategoryNormal

/**
 * the colour bands of the vendor app's "blutdruck-farbgrafik", read off its screen on
 * 2026-11-06: normal below 135/85, elevated 135-159/85-99, high 160-179/100-109,
 * crisis from 180/110. the chart only says "and"; when systolic and diastolic fall into
 * different bands the higher one counts here, as the who/esh tables do. the values
 * 180 and 110 themselves, which the chart leaves out, count as crisis.
 */
enum class Category(val label: Int, val color: Color) {
    NORMAL(R.string.category_normal, CategoryNormal),
    ELEVATED(R.string.category_elevated, CategoryElevated),
    HIGH(R.string.category_high, CategoryHigh),
    CRISIS(R.string.category_crisis, CategoryCrisis);

    companion object {
        fun of(systolic: Int, diastolic: Int): Category = when {
            systolic >= 180 || diastolic >= 110 -> CRISIS
            systolic >= 160 || diastolic >= 100 -> HIGH
            systolic >= 135 || diastolic >= 85 -> ELEVATED
            else -> NORMAL
        }
    }
}
