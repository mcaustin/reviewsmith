package dev.reviewsmith.provider.claudecode

import dev.reviewsmith.spi.AgentProvider
import dev.reviewsmith.spi.AgentProviderFactory

class ClaudeCodeProviderFactory : AgentProviderFactory {
    override val id: String = "claude-code"
    override fun create(model: String?, hermetic: Boolean): AgentProvider = ClaudeCodeProvider(model = model, hermetic = hermetic)
}
