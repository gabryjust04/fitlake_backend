package com.fitlake.food.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "user_food_alias")
class UserFoodAliasEntity(
	@Id
	@Column(name = "alias_id", nullable = false, updatable = false)
	var aliasId: UUID,

	@Column(name = "user_food_id", nullable = false, updatable = false)
	var userFoodId: UUID,

	@Column(name = "user_id", nullable = false, updatable = false)
	var userId: UUID,

	@Column(name = "alias", nullable = false, length = 120)
	var alias: String,

	@Column(name = "normalized_alias", nullable = false, length = 120)
	var normalizedAlias: String,

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant,

	@Column(name = "deleted_at")
	var deletedAt: Instant?,
)
