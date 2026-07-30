package com.fitlake.daily.infrastructure.persistence.repository

import com.fitlake.daily.application.port.AiInterpretationLogRepository
import com.fitlake.daily.application.port.DailyInboxEventRepository
import com.fitlake.daily.application.ai.DailyAiConcurrentRequestException
import com.fitlake.daily.domain.ai.AiInterpretationLog
import com.fitlake.daily.domain.inbox.DailyInboxChannel
import com.fitlake.daily.domain.inbox.DailyInboxEvent
import com.fitlake.daily.domain.inbox.DailyInboxEventId
import com.fitlake.daily.infrastructure.persistence.mapper.DailyAiPersistenceMapper
import com.fitlake.user.domain.UserId
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository

@Repository
class JpaDailyInboxEventRepository(
	private val repository: SpringDataDailyInboxEventRepository,
	private val mapper: DailyAiPersistenceMapper,
) : DailyInboxEventRepository {
	override fun findById(inboxEventId: DailyInboxEventId): DailyInboxEvent? =
		repository.findById(inboxEventId.value).orElse(null)?.let(mapper::toDomain)

	override fun findByIdForUpdate(inboxEventId: DailyInboxEventId): DailyInboxEvent? =
		repository.findByInboxEventIdForUpdate(inboxEventId.value)?.let(mapper::toDomain)

	override fun findByUserIdAndChannelAndSourceMessageId(
		userId: UserId,
		channel: DailyInboxChannel,
		sourceMessageId: String,
	): DailyInboxEvent? = repository.findByUserIdAndChannelAndSourceMessageId(
		userId.value,
		channel,
		sourceMessageId,
	)?.let(mapper::toDomain)

	override fun save(event: DailyInboxEvent): DailyInboxEvent = try {
		mapper.toDomain(repository.saveAndFlush(mapper.toEntity(event)))
	} catch (exception: DataIntegrityViolationException) {
		if (exception.hasIdempotencyConstraint()) {
			throw DailyAiConcurrentRequestException(exception)
		}
		throw exception
	}
}

@Repository
class JpaAiInterpretationLogRepository(
	private val repository: SpringDataAiInterpretationLogRepository,
	private val mapper: DailyAiPersistenceMapper,
) : AiInterpretationLogRepository {
	override fun findByInboxEventId(inboxEventId: DailyInboxEventId): AiInterpretationLog? =
		repository.findByInboxEventId(inboxEventId.value)?.let(mapper::toDomain)

	override fun save(log: AiInterpretationLog): AiInterpretationLog =
		mapper.toDomain(repository.saveAndFlush(mapper.toEntity(log)))
}

private fun Throwable.hasIdempotencyConstraint(): Boolean = generateSequence(this as Throwable?) { it.cause }
	.filterNotNull()
	.any { cause ->
		cause.message?.contains(IDEMPOTENCY_CONSTRAINT, ignoreCase = true) == true
	}

private const val IDEMPOTENCY_CONSTRAINT = "uq_daily_inbox_event_user_channel_source_message"
