package kz.autopersoncrop.core

data class FramingStyle(
    val leftSubjectWidths: Double,
    val topSubjectHeights: Double,
    val rightSubjectWidths: Double,
    val bottomSubjectHeights: Double,
    val landscapeShare: Double,
    val sampleCount: Int,
)

data class SceneCropProfile(
    val single: FramingStyle?,
    val pair: FramingStyle?,
    val fallback: FramingStyle?,
    val sampleCount: Int,
) {
    fun styleFor(subjectCount: Int): FramingStyle? = when {
        subjectCount >= 2 -> pair ?: fallback ?: single
        else -> single ?: fallback ?: pair
    }
}
