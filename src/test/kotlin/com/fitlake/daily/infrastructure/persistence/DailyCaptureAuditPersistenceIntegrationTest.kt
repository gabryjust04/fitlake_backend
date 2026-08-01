package com.fitlake.daily.infrastructure.persistence

import com.fitlake.daily.application.port.DailyCaptureAuditRepository
import com.fitlake.daily.application.port.DailyCaptureRepository
import com.fitlake.daily.application.port.DailyDayRepository
import com.fitlake.daily.domain.audit.DailyCaptureAudit
import com.fitlake.daily.domain.audit.DailyCaptureAuditAction
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureEntry
import com.fitlake.daily.domain.capture.DailyCaptureEntryType
import com.fitlake.daily.domain.capture.DailyCapturePayload
import com.fitlake.daily.domain.capture.DailyEnteredQuantity
import com.fitlake.daily.domain.capture.DailyFoodBasisSnapshot
import com.fitlake.daily.domain.capture.DailyFoodCaptureItem
import com.fitlake.daily.domain.capture.DailyFoodConversionSnapshot
import com.fitlake.daily.domain.capture.DailyFoodItemSourceType
import com.fitlake.daily.domain.capture.DailyFoodQuantityUnit
import com.fitlake.daily.domain.capture.DailyFoodSnapshotUnit
import com.fitlake.daily.domain.capture.DailyNutritionSourceSnapshot
import com.fitlake.daily.domain.capture.DailyNutritionValues
import com.fitlake.daily.domain.capture.DailyResolvedFoodUnit
import com.fitlake.daily.domain.capture.DailyResolvedQuantity
import com.fitlake.daily.domain.capture.DailyScalarUnit
import com.fitlake.daily.domain.capture.DailyUserFoodSnapshot
import com.fitlake.daily.domain.common.DailyDay
import com.fitlake.daily.infrastructure.persistence.mapper.DailyCaptureAuditPersistenceMapper
import com.fitlake.daily.infrastructure.persistence.mapper.DailyPersistenceMapper
import com.fitlake.daily.infrastructure.persistence.repository.JpaDailyCaptureAuditRepository
import com.fitlake.daily.infrastructure.persistence.repository.JpaDailyCaptureRepository
import com.fitlake.daily.infrastructure.persistence.repository.JpaDailyDayRepository
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.user.domain.UserAccount
import com.fitlake.user.domain.UserId
import com.fitlake.user.infrastructure.SpringTransactionExecutor
import com.fitlake.user.infrastructure.persistence.mapper.UserPersistenceMapper
import com.fitlake.user.infrastructure.persistence.repository.JpaUserAccountRepositoryAdapter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@DataJpaTest(
	properties = [
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true",
	],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
	JacksonAutoConfiguration::class,
	DailyPersistenceMapper::class,
	DailyCaptureAuditPersistenceMapper::class,
	JpaDailyDayRepository::class,
	JpaDailyCaptureRepository::class,
	JpaDailyCaptureAuditRepository::class,
	UserPersistenceMapper::class,
	JpaUserAccountRepositoryAdapter::class,
)
class DailyCaptureAuditPersistenceIntegrationTest @Autowired constructor(
	private val days: DailyDayRepository,
	private val captures: DailyCaptureRepository,
	private val audits: DailyCaptureAuditRepository,
	private val users: JpaUserAccountRepositoryAdapter,
	private val jdbcTemplate: JdbcTemplate,
	transactionManager: PlatformTransactionManager,
) {
	private val transactions: TransactionExecutor = SpringTransactionExecutor(transactionManager)
	private val now = Instant.parse("2026-07-31T10:00:00Z")
	private val date = LocalDate.parse("2026-07-31")
	private var userId: UserId = UserId(UUID.randomUUID())

	@BeforeEach
	fun createUser() {
		userId = createUser("capture-audit")
	}

	@Test
	fun `UI edit audit round trips typed payload and actual optimistic versions`() {
		val oldPayload = weightPayload("78.4")
		val newPayload = weightPayload("77.8", oldPayload.entries.single().entryId)
		val created = createCapture(oldPayload)

		val result = transactions.required {
			val current = requireNotNull(captures.findByIdAndUserIdForUpdate(created.captureId, userId))
			val updated = captures.save(current.replacePayload(newPayload, now.plusSeconds(1)))
			val audit = audits.save(
				DailyCaptureAudit.uiEdit(
					captureId = current.captureId,
					userId = current.userId,
					oldPayload = current.payload,
					newPayload = updated.payload,
					oldVersion = current.version,
					newVersion = updated.version,
					requestId = "request-123",
					at = now.plusSeconds(1),
				),
			)
			updated to audit
		}

		val (updated, savedAudit) = result
		val loaded = audits.findAllByCaptureIdAndUserId(created.captureId, userId).single()

		assertEquals(DailyCaptureAuditAction.UI_EDIT, loaded.action)
		assertEquals(oldPayload, loaded.oldPayload)
		assertEquals(newPayload, loaded.newPayload)
		assertEquals(created.version, loaded.oldVersion)
		assertEquals(updated.version, loaded.newVersion)
		assertEquals(savedAudit, loaded)
		assertNotEquals(created.version, updated.version)
		assertEquals(
			2,
			jdbcTemplate.queryForObject(
				"SELECT (new_payload ->> 'schemaVersion')::integer FROM daily_capture_audit WHERE audit_id = ?",
				Int::class.java,
				loaded.auditId.value,
			),
		)
	}

	@Test
	fun `invalid audit rolls back the capture update atomically`() {
		val payload = weightPayload("78")
		val capture = createCapture(payload)
		val otherUserId = createUser("capture-audit-foreign")
		val replacement = weightPayload("79", payload.entries.single().entryId)
		assertTrue(
			transactions.required {
				captures.findByIdAndUserIdForUpdate(capture.captureId, otherUserId) == null
			},
		)

		assertFailsWith<DataIntegrityViolationException> {
			transactions.required {
				val current = requireNotNull(captures.findByIdAndUserIdForUpdate(capture.captureId, userId))
				val updated = captures.save(current.replacePayload(replacement, now.plusSeconds(1)))
				audits.save(
					DailyCaptureAudit.uiEdit(
						captureId = current.captureId,
						userId = otherUserId,
						oldPayload = current.payload,
						newPayload = updated.payload,
						oldVersion = current.version,
						newVersion = updated.version,
						requestId = null,
						at = now.plusSeconds(1),
					),
				)
			}
		}

		val reloaded = requireNotNull(captures.findById(capture.captureId))
		assertEquals(capture.version, reloaded.version)
		assertEquals(payload, reloaded.payload)
		assertTrue(audits.findAllByCaptureIdAndUserId(capture.captureId, userId).isEmpty())
	}

	@Test
	fun `Flyway exposes decimal daily calories without a stale column`() {
		val column = jdbcTemplate.queryForMap(
			"""
			SELECT data_type, numeric_precision, numeric_scale
			FROM information_schema.columns
			WHERE table_schema = 'public'
			  AND table_name = 'daily_metrics'
			  AND column_name = 'total_calories'
			""".trimIndent(),
		)
		assertEquals("numeric", column["data_type"])
		assertEquals(18, (column["numeric_precision"] as Number).toInt())
		assertEquals(6, (column["numeric_scale"] as Number).toInt())
		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"""
				SELECT count(*)
				FROM information_schema.columns
				WHERE table_schema = 'public'
				  AND table_name = 'daily_metrics'
				  AND column_name = 'metrics_stale'
				""".trimIndent(),
				Int::class.java,
			),
		)
	}

	@Test
	fun `PostgreSQL JSONB preserves six decimal precision for large snapshot nutrients`() {
		val precise = BigDecimal("999999999999.123456")
		val nutrition = DailyNutritionValues(sodiumMilligrams = precise)
		val snapshot = DailyUserFoodSnapshot(
			nutritionBasis = DailyFoodBasisSnapshot(BigDecimal.ONE, DailyFoodSnapshotUnit.GRAM),
			nutrientsPerBasis = nutrition,
			defaultServing = null,
			conversions = DailyFoodConversionSnapshot(),
			nutritionSource = DailyNutritionSourceSnapshot(
				type = DailyFoodItemSourceType.USER_FOOD,
				originalSourceType = "PRODUCT_LABEL",
				estimated = false,
			),
			userFoodVersion = 1,
			userFoodUpdatedAt = now,
		)
		val item = DailyFoodCaptureItem(
			itemId = UUID.randomUUID(),
			sourceType = DailyFoodItemSourceType.USER_FOOD,
			userFoodId = UUID.randomUUID(),
			displayName = "Precision food",
			brand = null,
			enteredQuantity = DailyEnteredQuantity(BigDecimal.ONE, DailyFoodQuantityUnit.GRAM),
			resolvedQuantity = DailyResolvedQuantity(BigDecimal.ONE, DailyResolvedFoodUnit.GRAM),
			userFoodSnapshot = snapshot,
			calculatedNutrition = nutrition,
		)
		val payload = DailyCapturePayload.fromEntries(
			listOf(
				DailyCaptureEntry(
					entryId = UUID.randomUUID(),
					type = DailyCaptureEntryType.FOOD,
					items = listOf(item),
					nutritionTotal = nutrition,
				),
			),
		)

		val created = createCapture(payload)
		val loaded = requireNotNull(captures.findById(created.captureId))
		val loadedItem = loaded.payload.entries.single().items.single()

		assertEquals(precise, loadedItem.userFoodSnapshot?.nutrientsPerBasis?.sodiumMilligrams)
		assertEquals(precise, loadedItem.calculatedNutrition.sodiumMilligrams)
		assertEquals(precise, loaded.payload.entries.single().nutritionTotal?.sodiumMilligrams)
	}

	private fun createCapture(payload: DailyCapturePayload): DailyCapture = transactions.required {
		val day = days.save(DailyDay.open(userId, date, now))
		captures.save(DailyCapture.openFromUser(userId, day.dayId, payload, now))
	}

	private fun createUser(prefix: String): UserId {
		val id = UserId(UUID.randomUUID())
		users.save(
			UserAccount(
				userId = id,
				email = "$prefix-${id.value}@example.com",
				displayName = "Capture audit test",
				timezone = ZoneId.of("Europe/Rome"),
				createdAt = now,
				updatedAt = now,
			),
		)
		return id
	}

	private fun weightPayload(value: String, entryId: UUID = UUID.randomUUID()): DailyCapturePayload =
		DailyCapturePayload.fromEntries(
			listOf(
				DailyCaptureEntry(
					entryId = entryId,
					type = DailyCaptureEntryType.WEIGHT,
					value = BigDecimal(value),
					unit = DailyScalarUnit.KILOGRAM,
				),
			),
		)

	companion object {
		@Container
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:16-alpine")

		@DynamicPropertySource
		@JvmStatic
		fun postgresProperties(registry: DynamicPropertyRegistry) {
			registry.add("spring.datasource.url", postgres::getJdbcUrl)
			registry.add("spring.datasource.username", postgres::getUsername)
			registry.add("spring.datasource.password", postgres::getPassword)
		}
	}
}
