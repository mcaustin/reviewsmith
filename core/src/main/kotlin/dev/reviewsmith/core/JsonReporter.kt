package dev.reviewsmith.core

import dev.reviewsmith.spi.Finding
import kotlinx.serialization.Serializable
import java.time.Clock

@Serializable
data class RunMeta(
    val filesReviewed: Int,
    val rulesRun: Int,
    val suppressedByBaseline: Int,
    val abandonedUnits: Int,
    val cacheHits: Int,
    val totalCostUsd: Double?,
    val modelId: String?,
)

@Serializable
data class JsonReport(
    val schemaVersion: String,
    val generatedAt: String,
    val runMeta: RunMeta,
    val config: ReviewsmithConfig,
    val findings: List<Finding>,
)

class JsonReporter(
    private val resolvedConfig: ReviewsmithConfig,
    private val clock: Clock = Clock.systemUTC(),
) : Reporter {
    override fun report(result: RunResult): String {
        val report = JsonReport(
            schemaVersion = "1",
            generatedAt = clock.instant().toString(),
            runMeta = RunMeta(
                filesReviewed = result.filesReviewed,
                rulesRun = result.rulesRun,
                suppressedByBaseline = result.suppressedByBaseline,
                abandonedUnits = result.abandonedUnits,
                cacheHits = result.cacheHits,
                totalCostUsd = result.totalCostUsd,
                modelId = result.modelId,
            ),
            config = resolvedConfig,
            findings = result.findings,
        )
        return reportJson.encodeToString(JsonReport.serializer(), report)
    }
}
