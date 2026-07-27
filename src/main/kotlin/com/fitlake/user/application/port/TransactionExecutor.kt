package com.fitlake.user.application.port

interface TransactionExecutor {
	fun <T : Any> required(action: () -> T): T
}
