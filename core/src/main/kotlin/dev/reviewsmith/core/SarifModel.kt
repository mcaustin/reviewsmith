package dev.reviewsmith.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SarifLog(
    val version: String,
    @SerialName("\$schema") val schema: String,
    val runs: List<SarifRun>,
)

@Serializable
data class SarifRun(val tool: SarifTool, val results: List<SarifResult>)

@Serializable
data class SarifTool(val driver: SarifDriver)

@Serializable
data class SarifDriver(
    val name: String,
    val version: String? = null,
    val rules: List<SarifReportingDescriptor> = emptyList(),
)

@Serializable
data class SarifReportingDescriptor(val id: String, val name: String? = null)

@Serializable
data class SarifResult(
    val ruleId: String,
    val level: String,
    val message: SarifMessage,
    val locations: List<SarifLocation>,
)

@Serializable
data class SarifMessage(val text: String)

@Serializable
data class SarifLocation(val physicalLocation: SarifPhysicalLocation)

@Serializable
data class SarifPhysicalLocation(
    val artifactLocation: SarifArtifactLocation,
    val region: SarifRegion? = null,
)

@Serializable
data class SarifArtifactLocation(val uri: String)

@Serializable
data class SarifRegion(val startLine: Int)
