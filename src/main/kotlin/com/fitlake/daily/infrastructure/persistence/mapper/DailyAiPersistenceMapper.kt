package com.fitlake.daily.infrastructure.persistence.mapper

import com.fitlake.daily.domain.ai.AiInterpretationLog
import com.fitlake.daily.domain.ai.AiInterpretationLogId
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.common.DailyDayId
import com.fitlake.daily.domain.inbox.DailyInboxEvent
import com.fitlake.daily.domain.inbox.DailyInboxEventId
import com.fitlake.daily.infrastructure.persistence.entity.AiInterpretationLogEntity
import com.fitlake.daily.infrastructure.persistence.entity.DailyInboxEventEntity
import com.fitlake.user.domain.UserId
import org.springframework.stereotype.Component

@Component
class DailyAiPersistenceMapper {
	fun toDomain(entity: DailyInboxEventEntity): DailyInboxEvent = DailyInboxEvent(
		inboxEventId = DailyInboxEventId(entity.inboxEventId),
		userId = UserId(entity.userId),
		dayId = DailyDayId(requireNotNull(entity.dayId) { "REST AI inbox event has no day" }),
		channel = entity.channel,
		sourceType = entity.sourceType,
		sourceMessageId = requireNotNull(entity.sourceMessageId) { "REST AI inbox event has no idempotency key" },
		rawText = requireNotNull(entity.rawText) { "REST AI inbox event has no text" },
		normalizedText = requireNotNull(entity.normalizedText) { "REST AI inbox event has no normalized text" },
		processingStatus = entity.processingStatus,
		errorCode = entity.errorCode,
		errorMessage = entity.errorMessage,
		receivedAt = entity.receivedAt,
		processingStartedAt = entity.processingStartedAt,
		processingAttemptId = entity.processingAttemptId,
		processedAt = entity.processedAt,
		createdAt = entity.createdAt,
		replacesCaptureId = entity.replacesCaptureId?.let(::DailyCaptureId),
	)

	fun toEntity(domain: DailyInboxEvent): DailyInboxEventEntity = DailyInboxEventEntity(
		inboxEventId = domain.inboxEventId.value,
		userId = domain.userId.value,
		dayId = domain.dayId.value,
		channel = domain.channel,
		sourceType = domain.sourceType,
		sourceMessageId = domain.sourceMessageId,
		rawText = domain.rawText,
		normalizedText = domain.normalizedText,
		processingStatus = domain.processingStatus,
		errorCode = domain.errorCode,
		errorMessage = domain.errorMessage,
		receivedAt = domain.receivedAt,
		processingStartedAt = domain.processingStartedAt,
		processingAttemptId = domain.processingAttemptId,
		processedAt = domain.processedAt,
		createdAt = domain.createdAt,
		replacesCaptureId = domain.replacesCaptureId?.value,
	)

	fun toDomain(entity: AiInterpretationLogEntity): AiInterpretationLog = AiInterpretationLog(
		aiLogId = AiInterpretationLogId(entity.aiLogId),
		userId = UserId(entity.userId),
		inboxEventId = DailyInboxEventId(requireNotNull(entity.inboxEventId) { "AI audit has no inbox event" }),
		captureId = entity.captureId?.let(::DailyCaptureId),
		provider = entity.provider,
		model = entity.model,
		promptVersion = entity.promptVersion,
		inputText = entity.inputText,
		contextSnapshot = entity.contextSnapshot ?: emptyMap(),
		parsedOutput = entity.parsedOutput ?: emptyMap(),
		status = entity.status,
		confidence = entity.confidence,
		errorCode = entity.errorCode,
		errorMessage = entity.errorMessage,
		latencyMs = entity.latencyMs,
		createdAt = entity.createdAt,
	)

	fun toEntity(domain: AiInterpretationLog): AiInterpretationLogEntity = AiInterpretationLogEntity(
		aiLogId = domain.aiLogId.value,
		userId = domain.userId.value,
		inboxEventId = domain.inboxEventId.value,
		captureId = domain.captureId?.value,
		provider = domain.provider,
		model = domain.model,
		promptVersion = domain.promptVersion,
		inputText = domain.inputText,
		contextSnapshot = domain.contextSnapshot,
		parsedOutput = domain.parsedOutput,
		status = domain.status,
		confidence = domain.confidence,
		errorCode = domain.errorCode,
		errorMessage = domain.errorMessage,
		latencyMs = domain.latencyMs,
		createdAt = domain.createdAt,
	)
}
