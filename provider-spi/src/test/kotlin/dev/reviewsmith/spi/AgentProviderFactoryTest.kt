package dev.reviewsmith.spi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.ServiceLoader

class AgentProviderFactoryTest {

    private val fake = FakeProviderFactory()
    private val other = object : AgentProviderFactory {
        override val id = "other"
        override fun create(model: String?, hermetic: Boolean): AgentProvider =
            object : AgentProvider {
                override val id = "other"
                override fun analyze(request: AgentRequest): AgentResult = AgentResult(findings = emptyList())
            }
    }

    @Test
    fun `resolve returns null when factory list is empty`() {
        assertNull(AgentProviderFactory.resolve(factories = emptyList()))
    }

    @Test
    fun `resolve returns first factory when no preferred id given`() {
        val result = AgentProviderFactory.resolve(factories = listOf(fake, other))
        assertEquals("fake", result?.id)
    }

    @Test
    fun `resolve returns matching factory when preferred id is known`() {
        val result = AgentProviderFactory.resolve(preferredId = "other", factories = listOf(fake, other))
        assertEquals("other", result?.id)
    }

    @Test
    fun `resolve falls back to first when preferred id is not found`() {
        val result = AgentProviderFactory.resolve(preferredId = "unknown", factories = listOf(fake, other))
        assertEquals("fake", result?.id)
    }

    @Test
    fun `list returns all factories in order`() {
        val ids = AgentProviderFactory.list(factories = listOf(fake, other)).map { it.id }
        assertEquals(listOf("fake", "other"), ids)
    }

    @Test
    fun `test-classpath ServiceLoader discovers FakeProviderFactory`() {
        val found = ServiceLoader.load(AgentProviderFactory::class.java).toList()
        assertNotNull(found.firstOrNull { it.id == "fake" }, "FakeProviderFactory must be discovered via ServiceLoader; found: $found")
    }

    @Test
    fun `real ServiceLoader default in resolve does not throw`() {
        val result = runCatching { AgentProviderFactory.resolve() }
        assert(result.isSuccess) { "resolve() must not throw even when no factory is registered: ${result.exceptionOrNull()}" }
    }
}
