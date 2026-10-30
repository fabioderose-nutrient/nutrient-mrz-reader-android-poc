package io.nutrient.poc.mrz.batch

import android.net.Uri
import io.nutrient.poc.mrz.mrz.MrzData
import io.nutrient.poc.mrz.pipeline.ReadStatus

data class BatchRow(
    val uri: Uri,
    val fileName: String,
    val status: ReadStatus,
    val mrz: MrzData?,
    val totalMs: Long,
    val ocrMs: Long,
    val attempts: Int,
    val error: String? = null,
)

data class BatchStats(
    val count: Int,
    val valid: Int,
    val mrzFound: Int,
    val avgMs: Long,
    val medianMs: Long,
    val p95Ms: Long,
    val minMs: Long,
    val maxMs: Long,
) {
    val validRate: Double get() = if (count == 0) 0.0 else valid * 100.0 / count
    val foundRate: Double get() = if (count == 0) 0.0 else mrzFound * 100.0 / count

    companion object {
        fun of(rows: List<BatchRow>): BatchStats {
            val times = rows.filter { it.error == null }.map { it.totalMs }.sorted()
            fun pct(p: Double) = if (times.isEmpty()) 0L else times[((times.size - 1) * p).toInt()]
            return BatchStats(
                count = rows.size,
                valid = rows.count { it.status == ReadStatus.VALID },
                mrzFound = rows.count { it.mrz != null },
                avgMs = if (times.isEmpty()) 0 else times.average().toLong(),
                medianMs = pct(0.5),
                p95Ms = pct(0.95),
                minMs = times.firstOrNull() ?: 0,
                maxMs = times.lastOrNull() ?: 0,
            )
        }
    }
}
