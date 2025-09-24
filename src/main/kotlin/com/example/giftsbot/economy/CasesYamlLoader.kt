package com.example.giftsbot.economy

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import java.util.Locale

object CasesYamlLoader {
    private const val DEFAULT_PATH = "config/cases.yaml"
    private const val MAX_PPM = 1_000_000
    private const val PPM_DENOMINATOR = 1_000_000.0
    private const val JACKPOT_ALPHA_MIN = 0.0
    private const val JACKPOT_ALPHA_MAX = 0.2

    @OptIn(ExperimentalSerializationApi::class)
    fun loadFromResources(path: String = DEFAULT_PATH): CasesRoot {
        val loader = Thread.currentThread().contextClassLoader ?: CasesYamlLoader::class.java.classLoader
        val stream = loader?.getResourceAsStream(path) ?: error("Cases config resource '$path' is not available")
        return stream.use { input ->
            val content = input.bufferedReader().use { reader -> reader.readText() }
            Yaml.default.decodeFromString(CasesRoot.serializer(), content)
        }
    }

    fun computePreview(case: CaseConfig): CasePreview {
        val sumPpm = case.items.sumOf { it.probabilityPpm }
        val evExt = case.items.sumOf { it.externalEv() }
        val price = case.priceStars.toDouble()
        val rtpExt = if (price == 0.0) 0.0 else evExt / price
        return CasePreview(case.id, case.priceStars, evExt, rtpExt, sumPpm, case.jackpotAlpha)
    }

    fun validate(case: CaseConfig): CaseValidationReport {
        val preview = computePreview(case)
        val collector = ValidationCollector()
        collector.checkSumPpm(preview.sumPpm)
        collector.checkRtp(case, preview.rtpExt)
        collector.checkJackpotAlpha(case.jackpotAlpha)
        case.items.forEach { collector.checkItem(it) }
        return CaseValidationReport(case.id, collector.isOk(), collector.messages(), preview)
    }

    fun toPublic(case: CaseConfig): PublicCaseDto =
        PublicCaseDto(
            id = case.id,
            title = case.title,
            priceStars = case.priceStars,
        )

    private fun PrizeItemConfig.externalEv(): Double =
        starCost?.let { cost -> cost.toDouble() * (probabilityPpm.toDouble() / PPM_DENOMINATOR) } ?: 0.0

    private fun ValidationCollector.checkSumPpm(sumPpm: Int) {
        when {
            sumPpm > MAX_PPM -> error("sumPpm=$sumPpm > 1_000_000")
            sumPpm < MAX_PPM -> warning("sumPpm=$sumPpm < 1_000_000 — остаток уйдёт в INTERNAL")
        }
    }

    private fun ValidationCollector.checkRtp(
        case: CaseConfig,
        rtpExt: Double,
    ) {
        if (rtpExt < case.rtpExtMin || rtpExt > case.rtpExtMax) {
            val rtp = rtpExt.format()
            val min = case.rtpExtMin.format()
            val max = case.rtpExtMax.format()
            error("rtpExt=$rtp вне коридора [$min, $max]")
        }
    }

    private fun ValidationCollector.checkJackpotAlpha(alpha: Double) {
        if (alpha < JACKPOT_ALPHA_MIN || alpha > JACKPOT_ALPHA_MAX) {
            error("jackpotAlpha=${alpha.format()} вне диапазона [$JACKPOT_ALPHA_MIN, $JACKPOT_ALPHA_MAX]")
        }
    }

    private fun ValidationCollector.checkItem(item: PrizeItemConfig) {
        val probability = item.probabilityPpm
        if (probability < 0 || probability > MAX_PPM) {
            error("probabilityPpm=$probability вне [0, 1_000_000] у приза '${item.id}'")
        }
        val cost = item.starCost
        if (cost != null && cost < 0) {
            error("starCost=$cost < 0 у внешнего приза '${item.id}'")
        }
    }

    private fun Double.format(): String = String.format(Locale.US, "%.6f", this)

    private class ValidationCollector {
        private val problems = mutableListOf<String>()
        private var hasErrors = false

        fun error(message: String) {
            hasErrors = true
            problems += message
        }

        fun warning(message: String) {
            problems += message
        }

        fun isOk(): Boolean = !hasErrors

        fun messages(): List<String> = problems.toList()
    }
}
