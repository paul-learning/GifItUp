package app.gifitup.shared.presentation.chat

import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.media.GifDelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatViewsTest {
    @Test
    fun aChatWithOneOtherIsNotAGroupAndANamedOrLargerOneIs() {
        assertFalse(ConversationTitle.Members(listOf("bob")).isGroup)
        assertFalse(ConversationTitle.Members(emptyList()).isGroup)
        assertTrue(ConversationTitle.Members(listOf("bob", "cy")).isGroup)
        assertTrue(ConversationTitle.Named("Trip").isGroup)
    }

    @Test
    fun theListKeyIsTheIdentityInHexWithLeadingZeros() {
        assertEquals("000aff10", KeyBytes(byteArrayOf(0, 10, -1, 16)).listKey())
    }

    @Test
    fun aGifDelayOfTenMillisecondsOrLessIsShownAsAHundred() {
        assertEquals(100, GifDelay.millis(0))
        assertEquals(100, GifDelay.millis(10))
        assertEquals(11, GifDelay.millis(11))
        assertEquals(2_500, GifDelay.millis(2_500))
    }
}
