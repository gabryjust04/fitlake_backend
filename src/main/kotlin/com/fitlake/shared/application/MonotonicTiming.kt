package com.fitlake.shared.application

internal fun elapsedMilliseconds(startedAtNanos: Long): Long =
	(System.nanoTime() - startedAtNanos) / 1_000_000
