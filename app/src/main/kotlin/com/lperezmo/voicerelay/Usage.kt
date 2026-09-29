package com.lperezmo.voicerelay

import android.content.Context
import java.time.YearMonth
import kotlin.math.roundToLong

/**
 * OpenAI list prices used for the in-app cost estimate. Prices change, and a
 * model missing from these tables is counted but not priced, so the estimate
 * says so instead of guessing.
 */
object Pricing {
    /** USD per minute of audio. */
    private val transcriptionPerMinute = mapOf(
        "gpt-transcribe" to 0.0045,
        "gpt-live-transcribe" to 0.017,
        "gpt-4o-transcribe" to 0.006,
        "gpt-4o-mini-transcribe" to 0.003,
        "whisper-1" to 0.006,
    )

    /** USD per 1M tokens, input to output (short-context rates). */
    private val textPerMillion = mapOf(
        "gpt-6-luna" to (0.10 to 0.50),
        "gpt-5.6-luna" to (0.20 to 1.20),
        "gpt-5.6-terra" to (2.00 to 12.00),
        "gpt-5.6-sol" to (4.00 to 20.00),
        "gpt-5-mini" to (0.25 to 2.00),
        "gpt-5-nano" to (0.05 to 0.40),
        "gpt-4.1-mini" to (0.40 to 1.60),
    )

    /** Dated snapshots (gpt-4o-transcribe-2025-...) are priced like their base model. */
    private fun <T> lookup(table: Map<String, T>, model: String): T? =
        table[model] ?: table.entries.filter { model.startsWith(it.key + "-") }.maxByOrNull { it.key.length }?.value

    fun transcription(model: String, seconds: Double): Double? =
        lookup(transcriptionPerMinute, model)?.let { it * seconds / 60.0 }

    fun text(model: String, inputTokens: Int, outputTokens: Int): Double? =
        lookup(textPerMillion, model)?.let { (inRate, outRate) -> (inputTokens * inRate + outputTokens * outRate) / 1_000_000.0 }
}

/** Running totals for one period. Cost is in micro-dollars so it adds up exactly. */
data class UsageTotals(
    val dictations: Int = 0,
    val audioSeconds: Double = 0.0,
    val textCalls: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val costMicros: Long = 0,
    /** Calls to a model with no known price, left out of [costMicros]. */
    val unpricedCalls: Int = 0,
) {
    val costUsd get() = costMicros / 1_000_000.0

    fun plusTranscription(seconds: Double, cost: Double?) = copy(
        dictations = dictations + 1,
        audioSeconds = audioSeconds + seconds,
        costMicros = costMicros + micros(cost),
        unpricedCalls = unpricedCalls + if (cost == null) 1 else 0,
    )

    fun plusText(inputTokens: Int, outputTokens: Int, cost: Double?) = copy(
        textCalls = textCalls + 1,
        inputTokens = this.inputTokens + inputTokens,
        outputTokens = this.outputTokens + outputTokens,
        costMicros = costMicros + micros(cost),
        unpricedCalls = unpricedCalls + if (cost == null) 1 else 0,
    )

    /** One line for the settings screen. */
    fun summary(): String {
        if (dictations == 0 && textCalls == 0) return "Nothing yet"
        val minutes = "%.1f".format(audioSeconds / 60.0)
        val cost = if (costUsd < 0.01 && costMicros > 0) "< \$0.01" else "\$%.2f".format(costUsd)
        val unpriced = if (unpricedCalls > 0) " (${plural(unpricedCalls, "call")} to unpriced models not included)" else ""
        return "${plural(dictations, "dictation")}, $minutes min of audio, " +
            "${plural(textCalls, "cleanup or fix", "cleanups and fixes")}, about $cost$unpriced"
    }

    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

    private fun micros(cost: Double?) = cost?.let { (it * 1_000_000).roundToLong() } ?: 0L
}

/**
 * Counts what this phone sends to OpenAI: this month and since the last reset.
 * Stored locally only; it is an estimate, and the OpenAI usage dashboard is the
 * bill of record.
 */
class UsageStore(context: Context, private val month: () -> String = { YearMonth.now().toString() }) {
    private val prefs = context.getSharedPreferences("usage", Context.MODE_PRIVATE)

    fun recordTranscription(model: String, seconds: Double) =
        update { it.plusTranscription(seconds, Pricing.transcription(model, seconds)) }

    fun recordText(model: String, inputTokens: Int, outputTokens: Int) =
        update { it.plusText(inputTokens, outputTokens, Pricing.text(model, inputTokens, outputTokens)) }

    /** This calendar month; zero once the month rolls over. */
    fun thisMonth(): UsageTotals = if (prefs.getString("month", null) == month()) read("m_") else UsageTotals()

    fun allTime(): UsageTotals = read("t_")

    val since: Long get() = prefs.getLong("since", 0L)

    fun reset() {
        prefs.edit().clear().putLong("since", System.currentTimeMillis()).apply()
    }

    @Synchronized
    private fun update(change: (UsageTotals) -> UsageTotals) {
        val edit = prefs.edit()
        if (!prefs.contains("since")) edit.putLong("since", System.currentTimeMillis())
        edit.putString("month", month())
        write(edit, "m_", change(thisMonth()))
        write(edit, "t_", change(allTime()))
        edit.apply()
    }

    private fun read(p: String) = UsageTotals(
        dictations = prefs.getInt("${p}dictations", 0),
        audioSeconds = prefs.getFloat("${p}audio_seconds", 0f).toDouble(),
        textCalls = prefs.getInt("${p}text_calls", 0),
        inputTokens = prefs.getLong("${p}input_tokens", 0),
        outputTokens = prefs.getLong("${p}output_tokens", 0),
        costMicros = prefs.getLong("${p}cost_micros", 0),
        unpricedCalls = prefs.getInt("${p}unpriced", 0),
    )

    private fun write(edit: android.content.SharedPreferences.Editor, p: String, t: UsageTotals) {
        edit.putInt("${p}dictations", t.dictations)
            .putFloat("${p}audio_seconds", t.audioSeconds.toFloat())
            .putInt("${p}text_calls", t.textCalls)
            .putLong("${p}input_tokens", t.inputTokens)
            .putLong("${p}output_tokens", t.outputTokens)
            .putLong("${p}cost_micros", t.costMicros)
            .putInt("${p}unpriced", t.unpricedCalls)
    }
}
