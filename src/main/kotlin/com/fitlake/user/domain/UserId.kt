package com.fitlake.user.domain

import java.util.UUID

@JvmInline
value class UserId(val value: UUID) {
	override fun toString(): String = value.toString()
}
