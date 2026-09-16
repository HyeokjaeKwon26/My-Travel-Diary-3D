package com.traveler.feature.video

import org.junit.Assert.*
import org.junit.Test

class ExportTitlePrivacyTest {
    @Test fun sequenceNumbersAndYearsAreNotStreetAddresses() {
        for(title in listOf("Newport 2","Summer 2026","제주 3박 4일")) assertEquals(title,ExportPrivacy.title(title))
        for(title in listOf("Home", "123 Main Street", "서울 강남대로 123")) assertEquals("My Travel Story",ExportPrivacy.title(title))
        assertEquals("Private place",ExportPrivacy.label("123 Main Street"))
    }
}
