package io.github.mathiashuber.gymsuite.ui

import io.github.mathiashuber.gymsuite.data.StrengthSet
import io.github.mathiashuber.gymsuite.data.WeightUnit
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

fun fmtNumber(v: Double): String =
    if (abs(v - v.roundToLong()) < 0.05) v.roundToLong().toString() else "%.1f".format(v)

fun fmtWeight(kg: Double, unit: WeightUnit): String = fmtNumber(kg * unit.perKg) + " " + unit.label

/** "5 × 100 kg", or "12 reps" for a bodyweight set. */
fun fmtSet(s: StrengthSet, unit: WeightUnit): String {
    val reps = s.reps ?: 0
    val w = s.weightKg ?: 0.0
    return if (w > 0) "$reps × ${fmtWeight(w, unit)}" else "$reps reps"
}
