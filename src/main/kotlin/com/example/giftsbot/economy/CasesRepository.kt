package com.example.giftsbot.economy

import com.example.app.observability.Metrics
import com.example.app.observability.MetricsTags
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

class CasesRepository(
    private val meterRegistry: MeterRegistry,
    private val resourcePath: String = DEFAULT_RESOURCE_PATH,
    private val loader: (String) -> CasesRoot = CasesYamlLoader::loadFromResources,
) {
    private val reloadLock = Any()
    private val rootRef = AtomicReference(EMPTY_ROOT)

    private val reloadOkCounter =
        Metrics.counter(meterRegistry, RELOAD_METRIC, MetricsTags.RESULT to RESULT_OK)
    private val reloadFailCounter =
        Metrics.counter(meterRegistry, RELOAD_METRIC, MetricsTags.RESULT to RESULT_FAIL)
    private val validateOkCounter =
        Metrics.counter(meterRegistry, VALIDATE_METRIC, MetricsTags.RESULT to RESULT_OK)
    private val validateFailCounter =
        Metrics.counter(meterRegistry, VALIDATE_METRIC, MetricsTags.RESULT to RESULT_FAIL)

    fun reload(): CasesValidationSummary =
        synchronized(reloadLock) {
            runCatching {
                val loaded = loader(resourcePath)
                val summary = validateInternal(loaded)
                rootRef.set(loaded)
                summary
            }.onSuccess { summary ->
                reloadOkCounter.increment()
                logSummary("Reloaded cases", summary)
            }.onFailure { error ->
                reloadFailCounter.increment()
                logger.error("Failed to reload cases configuration from '{}'", resourcePath, error)
            }.getOrThrow()
        }

    fun listPublic(): List<PublicCaseDto> = rootRef.get().cases.map(CasesYamlLoader::toPublic)

    fun getPreview(caseId: String): CasePreview? {
        val current = rootRef.get()
        val case = current.cases.firstOrNull { it.id == caseId } ?: return null
        return CasesYamlLoader.computePreview(case)
    }

    fun get(caseId: String): CaseConfig? = rootRef.get().cases.firstOrNull { it.id == caseId }

    fun validateAll(): CasesValidationSummary {
        val summary = validateInternal(rootRef.get())
        logSummary("Validated cases", summary)
        return summary
    }

    private fun validateInternal(root: CasesRoot): CasesValidationSummary {
        val reports =
            root.cases.map { case ->
                val report = CasesYamlLoader.validate(case)
                if (report.isOk) {
                    validateOkCounter.increment()
                } else {
                    validateFailCounter.increment()
                    logger.warn(
                        "Case '{}' failed validation: {}",
                        report.caseId,
                        report.problems.joinToString(separator = "; "),
                    )
                }
                report
            }
        val okCount = reports.count { it.isOk }
        val failCount = reports.size - okCount
        return CasesValidationSummary(
            total = reports.size,
            ok = okCount,
            failed = failCount,
            reports = reports,
        )
    }

    private fun logSummary(
        action: String,
        summary: CasesValidationSummary,
    ) {
        if (summary.failed == 0) {
            logger.info("{}: {} total, all passed", action, summary.total)
        } else {
            logger.warn(
                "{}: {} total, {} passed, {} failed",
                action,
                summary.total,
                summary.ok,
                summary.failed,
            )
        }
    }

    companion object {
        private const val DEFAULT_RESOURCE_PATH = "config/cases.yaml"
        private const val RELOAD_METRIC = "economy_reload_total"
        private const val VALIDATE_METRIC = "economy_validate_total"
        private const val RESULT_OK = "ok"
        private const val RESULT_FAIL = "fail"

        private val EMPTY_ROOT = CasesRoot(emptyList())
        private val logger = LoggerFactory.getLogger(CasesRepository::class.java)
    }
}

@Serializable
data class CasesValidationSummary(
    val total: Int,
    val ok: Int,
    val failed: Int,
    val reports: List<CaseValidationReport>,
)
