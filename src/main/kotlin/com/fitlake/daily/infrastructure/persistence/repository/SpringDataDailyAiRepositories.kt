package com.fitlake.daily.infrastructure.persistence.repository

import com.fitlake.daily.domain.inbox.DailyInboxChannel
import com.fitlake.daily.infrastructure.persistence.entity.AiInterpretationLogEntity
import com.fitlake.daily.infrastructure.persistence.entity.DailyInboxEventEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface SpringDataDailyInboxEventRepository : JpaRepository<DailyInboxEventEntity, UUID> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select event from DailyInboxEventEntity event where event.inboxEventId = :inboxEventId")
	fun findByInboxEventIdForUpdate(
		@Param("inboxEventId") inboxEventId: UUID,
	): DailyInboxEventEntity?

	fun findByUserIdAndChannelAndSourceMessageId(
		userId: UUID,
		channel: DailyInboxChannel,
		sourceMessageId: String,
	): DailyInboxEventEntity?
}

interface SpringDataAiInterpretationLogRepository : JpaRepository<AiInterpretationLogEntity, UUID> {
	fun findByInboxEventId(inboxEventId: UUID): AiInterpretationLogEntity?
}
