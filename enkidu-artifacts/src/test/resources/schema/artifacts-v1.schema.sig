# sha256: 3b1299869c31101454bed8f88bb426ddf6598c44606bfaebc66d3f9291eff4a9
data class ClasspathIdentity(val label: String, val fingerprintSha256: String, val entryCount: Int,)
data class CompareReport(val tool: ToolMetadata, val compared: ComparedClasspaths, val summary: CompareSummary, val regressions: List<LinkageFailure>, val fixed: List<LinkageFailure>, val winnerChanges: List<WinnerChange>,)
data class CompareSummary(val totalFailuresA: Int, val totalFailuresB: Int, val regressions: Int, val fixed: Int, val winnerChanges: Int,)
data class ComparedClasspaths(val targets: List<String>, val classpathA: ClasspathIdentity, val classpathB: ClasspathIdentity,)
data class DuplicateAbiDifference(val entry: String, val kind: DuplicateAbiDiffKind, val member: String? = null, val detail: String? = null,)
data class DuplicateEvidence(val className: String, val identicalBytecode: Boolean, val riskScore: Int, val riskLevel: DuplicateRiskLevel, val hashes: List<JarHash> = emptyList(), val abiDifferences: List<DuplicateAbiDifference> = emptyList(),)
data class Evidence(val winnerJar: String? = null, val shadowedJars: List<String> = emptyList(), val missingJarHint: String? = null, val targetModule: String? = null, val callerModule: String? = null, val packageName: String? = null, val exported: Boolean? = null, val spi: SpiEvidence? = null, val duplicate: DuplicateEvidence? = null,)
data class ExecutionContext(val runtimeJavaFeature: Int, val continueOnError: Boolean,)
data class Fingerprint(val algorithm: String, val value: String,)
data class Fingerprints(val classpath: Fingerprint, val targets: Fingerprint, val report: Fingerprint? = null,)
data class FixPlanItem(val kind: FixKind, val value: String, val confidence: Double? = null,)
data class JarHash(val entry: String, val sha256: String,)
data class LinkageFailure(val type: FailureType, val severity: Severity, val message: String, val symbol: SymbolId? = null, val referenceSite: ReferenceSite, val evidence: Evidence? = null, val fixPlan: List<FixPlanItem> = emptyList(),)
data class LinkageReport(val tool: ToolMetadata, val fingerprints: Fingerprints, val summary: ReportSummary, val failures: List<LinkageFailure>, val execution: ExecutionContext? = null, val warnings: List<ScanWarning>? = null,)
data class ReferenceSite(val callerClass: String, val callerMethod: String, val callerDescriptor: String, val line: Int? = null, val bytecodeOffset: Int? = null,)
data class ReportSummary(val failureCount: Int, val failureCountByType: Map<FailureType, Int>,)
data class ScanWarning(val code: WarningCode, val message: String, val path: String? = null, val jarEntry: String? = null,)
data class SpiEvidence(val service: String, val provider: String? = null, val serviceFileEntries: List<String> = emptyList(), val providerEntry: String? = null,)
data class SymbolId(val owner: String, val kind: SymbolKind, val name: String, val descriptor: String,)
data class ToolMetadata(val name: String, val version: String, val resolverMode: String,)
data class WinnerChange(val className: String, val winnerA: String?, val winnerB: String?,)
enum class DuplicateAbiDiffKind{SUPER_CHANGED, INTERFACES_CHANGED, METHOD_ADDED, METHOD_REMOVED, FIELD_ADDED, FIELD_REMOVED}
enum class DuplicateRiskLevel{BENIGN, LOW, MEDIUM, HIGH, CRITICAL}
enum class FailureType{MISSING_CLASS, MISSING_METHOD, MISSING_FIELD, INCOMPATIBLE_CLASS_CHANGE, DESCRIPTOR_MISMATCH, ILLEGAL_ACCESS_RISK, DUPLICATE_CLASS_SHADOWING, SPI_PROVIDER_BROKEN, SPI_PROVIDER_TYPE_MISMATCH, CLASSPATH_COMPARE_REGRESSION}
enum class FixKind{ALIGN_VERSIONS, EXCLUDE_JAR, UPGRADE_DEPENDENCY, DOWNGRADE_DEPENDENCY, ADD_MISSING_DEPENDENCY, REMOVE_DUPLICATES, FIX_SHADING, MERGE_SPI}
enum class Severity{INFO, WARN, ERROR}
enum class SymbolKind{METHOD, FIELD, TYPE}
enum class WarningCode{UNREADABLE_JAR, MANIFEST_PARSE_FAILED, INVALID_BYTECODE, MODULE_INFO_PARSE_FAILED, IO_ERROR}
