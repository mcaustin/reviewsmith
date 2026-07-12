package dev.reviewsmith.spi

import java.util.ServiceLoader

interface AgentProviderFactory {
    val id: String
    fun create(model: String?, hermetic: Boolean): AgentProvider

    companion object {
        fun list(
            factories: Iterable<AgentProviderFactory> = ServiceLoader.load(AgentProviderFactory::class.java),
        ): List<AgentProviderFactory> = factories.toList()

        fun resolve(
            preferredId: String? = null,
            factories: Iterable<AgentProviderFactory> = ServiceLoader.load(AgentProviderFactory::class.java),
        ): AgentProviderFactory? {
            val all = factories.toList()
            if (all.isEmpty()) return null
            if (preferredId != null) {
                val match = all.firstOrNull { it.id == preferredId }
                if (match != null) return match
            }
            return all.first()
        }
    }
}
