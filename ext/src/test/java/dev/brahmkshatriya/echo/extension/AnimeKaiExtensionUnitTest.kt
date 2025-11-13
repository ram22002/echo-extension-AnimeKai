package dev.brahmkshatriya.echo.extension

import kotlinx.coroutines.runBlocking
import org.junit.Test

class AnimeKaiExtensionUnitTest {

    @Test
    fun testInitialize() = runBlocking {
        val ext = AnimeKaiExtension()
        // Should not throw
        ext.setSettings(MockedSettings())
        ext.onInitialize()
    }
}
