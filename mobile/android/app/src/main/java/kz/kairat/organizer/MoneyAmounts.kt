package kz.kairat.organizer

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/** Shared by editing and display: never convert a stored fractional budget to Long. */
internal object MoneyAmounts {
    fun edit(value: Double): String = if(value.isFinite()) formatInput(BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()) else ""
    fun formatInput(input: String): String {
        val raw=input.filterNot { it==' ' || it=='\u00a0' || it=='\u202f' }.replace('.', ',')
        // Keep malformed input visible so validation can reject it, rather than changing its meaning.
        if(!raw.matches(Regex("[0-9]*(,[0-9]*)?"))) return input
        val parts=raw.split(',',limit=2)
        val integer=parts[0].trimStart('0').ifEmpty { if(parts[0].isNotEmpty()) "0" else "" }
        val grouped=integer.reversed().chunked(3).joinToString(" ").reversed()
        return grouped + if(parts.size==2) ","+parts[1] else ""
    }
    fun parse(input: String): Double? {
        val raw=input.filterNot { it==' ' || it=='\u00a0' || it=='\u202f' }.replace(',', '.')
        if(!raw.matches(Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"))) return null
        return raw.toDoubleOrNull()?.takeIf { it.isFinite() && it>=0.0 }
    }
    fun display(value: Double): String = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        minimumFractionDigits=2;maximumFractionDigits=2
    }.format(value)+" ₸"
}
