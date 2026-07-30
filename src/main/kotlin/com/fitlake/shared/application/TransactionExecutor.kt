package com.fitlake.shared.application

interface TransactionExecutor {
	fun <T : Any> required(action: () -> T): T
}
