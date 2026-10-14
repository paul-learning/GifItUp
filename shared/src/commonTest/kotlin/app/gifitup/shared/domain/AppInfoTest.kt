package app.gifitup.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class AppInfoTest {
    @Test
    fun greetingUsesAppName() {
        assertEquals("Welcome to GifItUp", greeting())
    }
}
