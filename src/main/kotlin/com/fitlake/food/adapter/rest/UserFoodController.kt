package com.fitlake.food.adapter.rest

import com.fitlake.auth.application.CurrentUserProvider
import com.fitlake.food.application.SearchUserFoodsUseCase
import com.fitlake.food.application.UserFoodCandidate
import com.fitlake.food.application.UserFoodPageQuery
import com.fitlake.food.application.UserFoodService
import com.fitlake.food.application.UserFoodSort
import com.fitlake.food.domain.UserFoodId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Validated
@RestController
@RequestMapping("/api/me/foods")
@Tag(name = "My foods", description = "Private manually managed nutrition catalog; independent from Daily captures and AI")
class UserFoodController(
	private val currentUserProvider: CurrentUserProvider,
	private val userFoodService: UserFoodService,
	private val searchUserFoods: SearchUserFoodsUseCase,
) {
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(
		summary = "Create a private user food",
		description = "Creates only a reusable catalog definition. It never creates a Daily capture.",
		requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(
			content = [Content(
				examples = [
					ExampleObject(
						name = "Product label per 100 grams with default serving",
						value = PRODUCT_LABEL_EXAMPLE,
					),
					ExampleObject(name = "Nutrition per piece", value = PER_PIECE_EXAMPLE),
				],
			)],
		),
	)
	fun create(@Valid @RequestBody request: UserFoodDefinitionRequest): UserFoodResponse =
		userFoodService.create(currentUserId(), request.toInput()).toResponse()

	@GetMapping
	@Operation(summary = "List active private foods with stable pagination")
	fun list(
		@RequestParam(defaultValue = "0") @Min(0) page: Int,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
		@RequestParam(defaultValue = "NAME_ASC") sort: UserFoodSort,
	): UserFoodPageResponse = userFoodService
		.list(currentUserId(), UserFoodPageQuery(page, size, sort))
		.toResponse()

	@GetMapping("/search")
	@Operation(
		summary = "Search active private foods",
		description = "Ranked exact barcode, exact alias/name, prefix and pg_trgm typo search. " +
			"Examples: exact alias `my yogurt`; typo `my yogurth`.",
	)
	fun search(
		@Parameter(example = "my yogurth") @RequestParam query: String,
		@RequestParam(defaultValue = "10") @Min(1) @Max(50) limit: Int,
	): UserFoodSearchResponse = UserFoodSearchResponse(
		query = query,
		results = searchUserFoods.search(currentUserId(), query, limit).map(UserFoodCandidate::toResponse),
	)

	@GetMapping("/{foodId}")
	@Operation(summary = "Read one owned active food")
	fun get(@PathVariable foodId: UUID): UserFoodResponse =
		userFoodService.get(currentUserId(), UserFoodId(foodId)).toResponse()

	@PatchMapping("/{foodId}")
	@Operation(
		summary = "Replace the editable definition of an owned food",
		description = "PATCH uses full replacement semantics for editable fields. The aliases array replaces all active aliases.",
		requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(
			content = [Content(examples = [ExampleObject(name = "Replace aliases", value = UPDATE_ALIASES_EXAMPLE)])],
		),
	)
	fun replace(
		@PathVariable foodId: UUID,
		@Valid @RequestBody request: UserFoodDefinitionRequest,
	): UserFoodResponse = userFoodService.replace(currentUserId(), UserFoodId(foodId), request.toInput()).toResponse()

	@DeleteMapping("/{foodId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Soft-delete an owned food", description = "The food immediately disappears from reads, lists and search.")
	fun delete(@PathVariable foodId: UUID) {
		userFoodService.softDelete(currentUserId(), UserFoodId(foodId))
	}

	private fun currentUserId() = currentUserProvider.requireCurrentUser().userId

	companion object {
		private const val PRODUCT_LABEL_EXAMPLE = """
{
  "name": "My usual Greek yogurt",
  "brand": "Example Brand",
  "barcode": "1234567890123",
  "description": "Copied from the breakfast yogurt label",
  "aliases": ["my yogurt", "usual yogurt"],
  "nutritionBasis": {"amount": 100, "unit": "GRAM"},
  "nutrients": {"caloriesKcal": 62, "proteinGrams": 9.5, "carbohydratesGrams": 4.1, "fatGrams": 0.2},
  "defaultServing": {"amount": 170, "unit": "GRAM"},
  "source": {"type": "PRODUCT_LABEL", "notes": "Copied manually from the package label"}
}
"""
		private const val PER_PIECE_EXAMPLE = """
{
  "name": "Homemade biscuit",
  "aliases": ["my biscuit"],
  "nutritionBasis": {"amount": 1, "unit": "PIECE"},
  "nutrients": {"caloriesKcal": 42, "proteinGrams": 1.1, "carbohydratesGrams": 6.8, "fatGrams": 1.4},
  "defaultServing": {"amount": 2, "unit": "PIECE"},
  "source": {"type": "USER_ENTERED"}
}
"""
		private const val UPDATE_ALIASES_EXAMPLE = """
{
  "name": "My usual Greek yogurt",
  "aliases": ["my yogurt", "breakfast yogurt"],
  "nutritionBasis": {"amount": 100, "unit": "GRAM"},
  "nutrients": {"caloriesKcal": 62, "proteinGrams": 9.5, "carbohydratesGrams": 4.1, "fatGrams": 0.2},
  "defaultServing": {"amount": 170, "unit": "GRAM"},
  "source": {"type": "PRODUCT_LABEL"}
}
"""
	}
}
