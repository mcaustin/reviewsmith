package dev.reviewsmith.spi

class FakeProviderFactory : AgentProviderFactory {
    override val id: String = "fake"
    override fun create(model: String?, hermetic: Boolean): AgentProvider =
        object : AgentProvider {
            override val id: String = "fake"
            override val effectiveModel: String? = model
            override fun analyze(request: AgentRequest): AgentResult =
                AgentResult(findings = emptyList())
        }
}
