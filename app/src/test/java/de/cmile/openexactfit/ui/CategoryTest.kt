package de.cmile.openexactfit.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryTest {
    @Test fun bandsFollowTheVendorChart() {
        assertEquals(Category.NORMAL, Category.of(134, 84))
        assertEquals(Category.ELEVATED, Category.of(135, 84))
        assertEquals(Category.ELEVATED, Category.of(120, 85))
        assertEquals(Category.ELEVATED, Category.of(159, 99))
        assertEquals(Category.HIGH, Category.of(160, 70))
        assertEquals(Category.HIGH, Category.of(120, 100))
        assertEquals(Category.HIGH, Category.of(179, 109))
        assertEquals(Category.CRISIS, Category.of(180, 80))
        assertEquals(Category.CRISIS, Category.of(120, 110))
    }

    @Test fun theHigherBandWinsWhenTheTwoValuesDisagree() {
        assertEquals(Category.HIGH, Category.of(165, 80))
        assertEquals(Category.CRISIS, Category.of(130, 115))
    }
}
