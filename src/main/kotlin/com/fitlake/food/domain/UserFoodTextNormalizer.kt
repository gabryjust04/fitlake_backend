package com.fitlake.food.domain

import java.text.Normalizer
import java.util.Locale

object UserFoodTextNormalizer {
	private val whitespace = Regex("\\s+")

	fun displayValue(value: String): String = value.trim().replace(whitespace, " ")

	fun normalize(value: String): String {
		val decomposed = Normalizer.normalize(displayValue(value).lowercase(Locale.ROOT), Normalizer.Form.NFKD)
		val normalized = buildString(decomposed.length) {
			decomposed.codePoints().forEach { codePoint ->
				when {
					Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt() -> Unit
					Character.isLetterOrDigit(codePoint) -> appendCodePoint(codePoint)
					else -> append(' ')
				}
			}
		}
		return normalized.trim().replace(whitespace, " ")
	}
}
