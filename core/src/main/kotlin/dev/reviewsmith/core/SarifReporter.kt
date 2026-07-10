package dev.reviewsmith.core

import dev.reviewsmith.spi.Severity

class SarifReporter : Reporter {
    override fun report(result: RunResult): String {
        val driverRules = result.findings.map { it.ruleId }.distinct().sorted()
            .map { ruleId -> SarifReportingDescriptor(id = ruleId, name = result.rulesById[ruleId]?.name) }

        val sarifResults = result.findings.map { f ->
            SarifResult(
                ruleId = f.ruleId,
                level = f.severity.toSarifLevel(),
                message = SarifMessage(f.message),
                locations = listOf(
                    SarifLocation(
                        SarifPhysicalLocation(
                            artifactLocation = SarifArtifactLocation(uri = f.file.replace('\\', '/')),
                            region = f.line?.takeIf { it >= 1 }?.let { SarifRegion(it) },
                        ),
                    ),
                ),
            )
        }

        val log = SarifLog(
            version = "2.1.0",
            schema = SCHEMA,
            runs = listOf(
                SarifRun(
                    tool = SarifTool(SarifDriver(name = "Reviewsmith", version = TOOL_VERSION, rules = driverRules)),
                    results = sarifResults,
                ),
            ),
        )
        return sarifJson.encodeToString(SarifLog.serializer(), log)
    }

    private fun Severity.toSarifLevel(): String = when (this) {
        Severity.ERROR -> "error"
        Severity.WARNING -> "warning"
        Severity.INFO -> "note"
    }

    private companion object {
        const val SCHEMA =
            "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json"
        const val TOOL_VERSION = "0.0.1"
    }
}
