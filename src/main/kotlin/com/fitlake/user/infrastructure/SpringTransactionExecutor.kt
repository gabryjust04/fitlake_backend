package com.fitlake.user.infrastructure

import com.fitlake.shared.application.TransactionExecutor
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Component
class SpringTransactionExecutor(
	transactionManager: PlatformTransactionManager,
) : TransactionExecutor {
	private val transactionTemplate = TransactionTemplate(transactionManager)

	override fun <T : Any> required(action: () -> T): T =
		requireNotNull(transactionTemplate.execute { action() })
}
