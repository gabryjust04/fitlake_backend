package com.fitlake.daily.application.port

import com.fitlake.daily.domain.ai.AiInterpretationLog
import com.fitlake.daily.domain.inbox.DailyInboxChannel
import com.fitlake.daily.domain.inbox.DailyInboxEvent
import com.fitlake.daily.domain.inbox.DailyInboxEventId
import com.fitlake.user.domain.UserId

interface DailyInboxEventRepository {
	fun findById(inboxEventId: DailyInboxEventId): DailyInboxEvent?
	fun findByIdForUpdate(inboxEventId: DailyInboxEventId): DailyInboxEvent?
	fun findByUserIdAndChannelAndSourceMessageId(
		userId: UserId,
		channel: DailyInboxChannel,
		sourceMessageId: String,
	): DailyInboxEvent?
	fun save(event: DailyInboxEvent): DailyInboxEvent
}

interface AiInterpretationLogRepository {
	fun findByInboxEventId(inboxEventId: DailyInboxEventId): AiInterpretationLog?
	fun save(log: AiInterpretationLog): AiInterpretationLog
}
