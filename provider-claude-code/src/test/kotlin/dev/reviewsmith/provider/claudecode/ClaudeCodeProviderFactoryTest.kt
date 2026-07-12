package dev.reviewsmith.provider.claudecode

import dev.reviewsmith.spi.AgentProviderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.ServiceLoader

class ClaudeCodeProviderFactoryTest {

    @Test
    fun `ServiceLoader discovers ClaudeCodeProviderFactory`() {
        val factories = ServiceLoader.load(AgentProviderFactory::class.java).toList()
        assertNotNull(
            factories.firstOrNull { it.id == "claude-code" },
            "ClaudeCodeProviderFactory must be discovered via ServiceLoader; found: $factories",
        )
    }

    @Test
    fun `create produces a provider with the requested effectiveModel`() {
        val factory = ClaudeCodeProviderFactory()
        val provider = factory.create(model = "some-model", hermetic = true)
        assertEquals("some-model", provider.effectiveModel)
    }

    @Test
    fun `create with null model yields null effectiveModel`() {
        val factory = ClaudeCodeProviderFactory()
        val provider = factory.create(model = null, hermetic = false)
        assertEquals(null, provider.effectiveModel)
    }

    @Test
    fun `factory id is claude-code`() {
        assertEquals("claude-code", ClaudeCodeProviderFactory().id)
    }
}
