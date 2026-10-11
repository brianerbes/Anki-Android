// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OfflineHandsFreeReviewTest {
    @Test
    fun exactJapaneseAnswerMatches() {
        assertTrue(OfflineHandsFreeReview.matchesAnswer("行きます", listOf("行きます", "いきます")))
    }

    @Test
    fun normalizationIgnoresSpacingAndPunctuation() {
        assertTrue(OfflineHandsFreeReview.matchesAnswer(" 行きます。 ", listOf("行きます")))
        assertTrue(OfflineHandsFreeReview.matchesAnswer("ｉｋｉｍａｓｕ", listOf("ikimasu")))
    }

    @Test
    fun differentAnswerDoesNotMatch() {
        assertFalse(OfflineHandsFreeReview.matchesAnswer("行きません", listOf("行きます", "いきます")))
    }

    @Test
    fun blankTranscriptNeverMatches() {
        assertFalse(OfflineHandsFreeReview.matchesAnswer(" ", listOf("行きます")))
    }
}
