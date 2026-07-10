package dev.reviewsmith.core

import dev.reviewsmith.spi.Finding

data class BaselinePartition(
    val surfaced: List<Finding>,
    val suppressed: List<Finding>,
)

/**
 * Partitions findings into surfaced (new) and suppressed (accepted by the baseline). Each
 * `(ruleId, file)` bucket suppresses up to its baselined count; within a bucket, findings
 * are ordered by line so the suppressed count is deterministic.
 */
object BaselineFilter {
    fun partition(findings: List<Finding>, store: BaselineStore): BaselinePartition {
        val surfaced = mutableListOf<Finding>()
        val suppressed = mutableListOf<Finding>()

        findings.groupBy { Fingerprint.of(it) }.forEach { (fp, group) ->
            val budget = store.countFor(fp)
            val ordered = group.sortedBy { it.line ?: Int.MAX_VALUE }
            ordered.forEachIndexed { index, finding ->
                if (index < budget) suppressed.add(finding) else surfaced.add(finding)
            }
        }
        return BaselinePartition(surfaced, suppressed)
    }
}
