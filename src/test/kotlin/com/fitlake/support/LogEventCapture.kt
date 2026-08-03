package com.fitlake.support

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

class LogEventCapture(
	loggerType: Class<*>,
	level: Level? = null,
) : AutoCloseable {
	private val logger = LoggerFactory.getLogger(loggerType) as Logger
	private val previousLevel = logger.level
	private val appender = ListAppender<ILoggingEvent>().also {
		it.context = logger.loggerContext
		it.start()
		logger.addAppender(it)
	}

	init {
		if (level != null) logger.level = level
	}

	val events: List<ILoggingEvent>
		get() = appender.list.toList()

	override fun close() {
		logger.detachAppender(appender)
		logger.level = previousLevel
		appender.stop()
	}
}

fun ILoggingEvent.structuredFields(): Map<String, Any?> =
	keyValuePairs.orEmpty().associate { pair -> pair.key to pair.value }

/**
 * Test-only flattened representation used exclusively for privacy assertions.
 * Production logging remains structured and does not depend on this helper.
 */
fun Iterable<ILoggingEvent>.renderedLogContent(): String = joinToString("\n") { event ->
	buildList {
		add(event.formattedMessage)
		event.structuredFields().forEach { (key, value) -> add("$key=$value") }
		event.throwableProxy?.let { throwable -> addAll(throwable.renderedCauseChain()) }
	}.joinToString(" ")
}

private fun IThrowableProxy.renderedCauseChain(): List<String> = buildList {
	add(className)
	message?.let(::add)
	cause?.let { addAll(it.renderedCauseChain()) }
	suppressed.orEmpty().forEach { addAll(it.renderedCauseChain()) }
}
