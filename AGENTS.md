# AGENTS.md

## Project Overview

FitLake is a personal daily tracking system.

The primary product scope is the **Daily module**. The backend also contains a supporting private food-catalog module whose only current responsibility is manually managed reusable nutrition definitions. It is not yet connected to Daily captures or AI interpretation.

The system tracks daily personal data such as:

* food
* calories and macros
* sleep
* body weight
* caffeine
* hydration
* steps
* mood
* focus
* stress
* daily notes
* unstructured physical activity

Structured workout tracking with exercises, sets, weights, and reps is **out of scope** for now.

The product must support both conversational input and classic UI interactions.

Main channels:

* Telegram bot
* Mobile app
* Future web app

Telegram is only one channel. The mobile app and Telegram must use the same backend services.

---

## Core Product Principle

The user should not feel forced to fill a form.

The user should be able to write natural messages such as:

```text
Colazione con 40 g avena, 150 ml latte e 15 g whey.
```

or:

```text
Ho dormito 6 ore e peso 78.4.
```

The system must interpret this input, create a structured pending capture, show it to the user, and ask for confirmation.

The user can confirm or reject the capture.

Only confirmed captures are used to build the final daily metrics.

---

## Core Architecture Rule

The system must follow this flow:

```text
Natural input or UI action
→ backend use case
→ backend validation
→ daily capture
→ user confirmation / rejection
→ accepted captures
→ daily finalization
→ daily metrics
```

AI must never directly write to the database or own the application state.

AI may only help interpret natural language input and produce structured proposals.

The backend owns:

* IDs
* validation
* authorization
* state transitions
* persistence
* final metrics
* business rules
* day finalization

---

## Architecture Style

Use a **modular monolith** with lightweight Domain-Driven Design.

Do not create microservices for the MVP.

Use clear module boundaries:

```text
daily/
├── adapter/
│   ├── telegram/
│   └── mobile/
├── application/
│   ├── capture/
│   ├── finalization/
│   └── ai/
├── domain/
│   ├── capture/
│   ├── metrics/
│   └── common/
└── infrastructure/
    ├── persistence/
    ├── ai/
    └── telegram/
```

Controllers must be thin.

Application services coordinate use cases.

Domain classes represent business concepts.

Infrastructure implements external details such as JPA, PostgreSQL, Telegram, AI clients, and transcription providers.

The food catalog is a sibling bounded module:

```text
food/
├── adapter/rest/
├── application/
│   └── port/
├── domain/
└── infrastructure/persistence/
```

Its domain, application ports, REST DTOs, and persistence entities must remain separate from the Daily capture model.

---

## Current MVP Flow

For the MVP, AI is used only for **insertion**.

AI is not used for precise UI edits.

Typical Telegram insertion flow:

```text
User sends text
→ save raw input in daily_inbox_event
→ AI interprets the text
→ backend validates AI output
→ backend creates daily_capture with status OPEN
→ bot shows the capture summary
→ user clicks "È corretto" or "Non è corretto"
→ capture becomes ACCEPTED or REJECTED
```

Typical mobile UI edit flow:

```text
User edits an item from the mobile app
→ target capture/item is already known by ID
→ backend validates ownership and payload
→ backend updates the accepted capture directly
```

Typical day finalization flow:

```text
User clicks "Chiudi giornata" or says "ok giornata finita"
→ backend checks open captures
→ backend aggregates accepted captures
→ backend creates or updates daily_metrics
→ daily_day becomes CONFIRMED
```

---

## Current REST Daily Slice

The current implemented Daily channel is authenticated REST. Precise manual REST actions do not create AI, inbox-event, or AI-log records. Natural-language REST actions use the dedicated audited AI flow below.

REST routes:

```text
POST   /api/daily/days/{date}/captures
POST   /api/daily/days/{date}/messages
GET    /api/daily/days/{date}
POST   /api/daily/captures/{captureId}/accept
POST   /api/daily/captures/{captureId}/reject
POST   /api/daily/captures/{captureId}/reprocess
PUT    /api/daily/captures/{captureId}
PATCH  /api/daily/captures/{captureId}/food-items/{itemTempId}
DELETE /api/daily/captures/{captureId}
POST   /api/daily/days/{date}/finalize
GET    /api/daily/days/{date}/metrics
```

Important REST rules:

* REST controllers obtain only the internal `UserId` from `CurrentUserProvider`.
* A manual REST insertion creates a `daily_capture` with `created_by = USER_UI`, `status = OPEN`, and `source_event_id = null`.
* The backend generates payload meal/item references when the client omits them.
* Clients may supply stable meal/item references for precise subsequent UI edits.
* Capture and day ownership must be checked on every command and query.
* Returning `404` for a capture owned by another user is preferred to leaking its existence.
* Confirmed days are immutable in the current slice; reopening is not implemented yet.
* Finalization returns `409` while any open capture exists.
* Only accepted captures contribute to the metrics snapshot.
* Repeated scalar fields use the last non-null value in deterministic capture creation order.
* Food logs concatenate meals; provided calories and macros are summed.
* Finalization is idempotent and returns the existing snapshot for an already confirmed day.
* REST validation errors return `400`, missing resources return `404`, and invalid state transitions return `409`.
* `POST /days/{date}/messages` and `POST /captures/{captureId}/reprocess` require an `Idempotency-Key` header.
* One idempotency key represents one normalized complete text and terminal result. Reuse with different text is a conflict.
* The AI endpoint accepts only `{ "text": "..." }`; structured capture JSON belongs to the manual endpoint.
* A new AI message creates at most one ordinary `OPEN` capture with `created_by = AI` and a backend-owned source event.
* Reprocess accepts only complete replacement text for an owned `OPEN` capture on an editable day.
* Successful reprocess creates a distinct `OPEN` capture and atomically rejects the old proposal as `SYSTEM`.
* Failed, invalid, clarification, or no-op reprocess leaves the old capture `OPEN` and unchanged.
* There is no conversation memory or relative AI edit flow.

---

## Current Private Food Catalog Slice

A user food is a reusable personal nutrition definition. It is not something consumed on a particular date.

```text
user food = reusable catalog definition
daily capture = occurrence consumed on a date
```

The catalog is authenticated, private, manually managed, and independent from Daily and AI. Creating, reading, updating, deleting, listing, or searching a user food must not create or modify a `daily_day`, `daily_capture`, `daily_inbox_event`, or `ai_interpretation_log`, and must not call Spring AI or an external nutrition provider.

REST routes:

```text
POST   /api/me/foods
GET    /api/me/foods
GET    /api/me/foods/search?query={query}
GET    /api/me/foods/{foodId}
PATCH  /api/me/foods/{foodId}
DELETE /api/me/foods/{foodId}
```

Important catalog rules:

* Controllers obtain the internal `UserId` only from `CurrentUserProvider`; no catalog request accepts a user ID.
* Every repository read and write is scoped by both food ID and authenticated user ID where applicable.
* A foreign-owned, deleted, or nonexistent food returns `404`, avoiding resource-existence disclosure.
* Normal lists, direct reads, and search include active foods only.
* `DELETE` is a soft delete. It sets a backend timestamp and removes the food and aliases from active lookup; it never touches Daily data.
* There is no restore endpoint in this MVP. Repeated deletion therefore deterministically behaves as not found.
* `PATCH` replaces the full editable definition accepted by `POST`; it is not a partial merge. The aliases array replaces all active aliases.
* Food ID, owner, normalized text, creation timestamp, deletion timestamp, and persistence version are backend-owned.
* List pagination defaults to page `0`, size `20`, and `NAME_ASC`; size is limited to `1..100`. Supported sorts are `NAME_ASC`, `CREATED_AT_DESC`, and `UPDATED_AT_DESC`.
* Search limits are `2..200` normalized searchable characters and `1..50` results. Fuzzy matching begins at three normalized characters.
* Invalid definitions or query parameters return the shared `400` error shape; duplicate active aliases/barcodes return `409`; persistence details and foreign ownership are never exposed.
* Catalog logs may include event, internal user/food ID, result count, and duration, but not authorization data, full definitions, descriptions, source notes, or search text.

The editable definition contains:

```text
name, optional brand/barcode/description, aliases
nutrition basis amount + unit
nullable nutrient values
optional default serving amount + unit
explicit piece/serving conversion metadata
nutrition source metadata
```

Supported `FoodUnit` values:

```text
GRAM
KILOGRAM
MILLILITER
LITER
PIECE
SERVING
```

Nutrition basis and default-serving amounts must be positive. Nutrients and conversions use `BigDecimal`, have defensive upper bounds, and cannot be negative. A missing nutrient is **unknown**, not zero. Never silently coerce a missing nutrient to zero.

The default serving is independent from the nutrition basis. Mass-to-mass and volume-to-volume scaling is deterministic. Crossing between mass or volume and `PIECE`/`SERVING` requires the corresponding explicit conversion metadata:

```text
gramsPerPiece
millilitersPerPiece
gramsPerServing
millilitersPerServing
```

A piece or serving cannot simultaneously define both a mass and volume conversion. Do not perform arbitrary conversions or infer them from names.

Supported nutrition-source types are:

```text
USER_ENTERED
PRODUCT_LABEL
EXTERNAL_DATABASE
AI_ESTIMATE
IMPORTED
```

They are provenance metadata only. `EXTERNAL_DATABASE` requires provider and external ID metadata, but the catalog does not contact that provider. `AI_ESTIMATE` marks existing entered values as estimated; it does not authorize an AI call.

Example product-label definition:

```json
{
  "name": "My usual Greek yogurt",
  "brand": "Example Brand",
  "barcode": "1234567890123",
  "aliases": ["my yogurt", "usual yogurt"],
  "nutritionBasis": {"amount": 100, "unit": "GRAM"},
  "nutrients": {
    "caloriesKcal": 62,
    "proteinGrams": 9.5,
    "carbohydratesGrams": 4.1,
    "fatGrams": 0.2,
    "fiberGrams": null
  },
  "defaultServing": {"amount": 170, "unit": "GRAM"},
  "conversions": {},
  "source": {"type": "PRODUCT_LABEL", "notes": "Copied manually from the label"}
}
```

Aliases and names are normalized centrally using Unicode decomposition, locale-safe lowercasing, accent removal, punctuation-to-space conversion, trimming, and whitespace collapse. Blank aliases, aliases duplicated after normalization within one definition, or an active normalized alias already owned by another active food of the same user are conflicts or validation errors. The same alias may belong to a different user. Active barcodes are likewise unique per user and reusable after deletion.

Search is conventional PostgreSQL search, not RAG or semantic search. Each branch is scoped by authenticated `user_id` and `deleted_at IS NULL`, and candidates are deduplicated per food. Ranking priority is fixed:

```text
1. EXACT_BARCODE
2. EXACT_ALIAS
3. EXACT_NAME
4. PREFIX_ALIAS
5. PREFIX_NAME
6. FUZZY_ALIAS
7. FUZZY_NAME
```

Prefix matching uses normalized B-tree pattern indexes. Fuzzy name and alias matching use `pg_trgm` GIN indexes with a transaction-local similarity threshold of `0.30`. Within one rank, order by score descending and then stable normalized name/ID tie-breakers. Search responses may expose `matchedBy`, `matchedText`, and score, but controllers must not implement normalization or ranking.

Future Daily integration must go through the application-level search use case, not the PostgreSQL adapter directly. For every consumed personal food, a future capture must preserve both:

```text
reference to userFoodId
+ immutable snapshot of name, consumed amount/unit, nutrition values, and nutrition source used at capture time
```

Updating or deleting a catalog definition must never rewrite historical capture nutrition. Do not implement this integration unless explicitly requested.

---

## AI Usage Rules

AI is used only to interpret natural language.

AI may:

* extract food items
* extract quantities and units
* infer meal names when reasonable
* extract daily fields such as sleep or weight
* produce a structured capture proposal
* ask for clarification
* return `NO_OP`

AI must not:

* write SQL
* call repositories
* generate canonical database IDs
* persist data
* modify accepted captures directly
* create daily metrics directly
* bypass backend validation
* decide final state alone
* put business logic into prompts

AI output must be structured and validated before being saved.

Allowed AI operations for MVP:

```text
CREATE_CAPTURE
ASK_CLARIFICATION
NO_OP
```

The only supported AI correction is complete-text reprocess of an `OPEN` proposal. Do not add relative, conversational, accepted-capture, or agentic AI modification flows unless explicitly requested.

---

## DDD Rules

Use DDD pragmatically.

Do not over-engineer the MVP.

Prefer clear use cases and stable boundaries over excessive patterns.

Domain concepts currently include:

* `DailyDay`
* `DailyCapture`
* `DailyCaptureStatus`
* `DailyCaptureType`
* `DailyCapturePayload`
* `MealDraft`
* `MealItemDraft`
* `DailyMetrics`
* `DailyState`
* `UserFood`
* `UserFoodId`
* `FoodAliasValue`
* `NutritionBasis`
* `NutrientValues`
* `DefaultServing`
* `UnitConversions`
* `NutritionSource`

Application services should include:

* `DailyCaptureOrchestrator`
* `InboxEventService`
* `AiCaptureInterpreterService`
* `DailyCaptureService`
* `DailyCaptureEditService`
* `CaptureConfirmationService`
* `DailyFinalizationService`
* `DailyMetricsProjectionService`
* `UserFoodService`
* `UserFoodSearchService`

`SearchUserFoodsUseCase` is the future-facing application boundary for personal-food lookup. Daily and AI code must not depend on JPA repositories, JDBC queries, or `pg_trgm` details.

Do not put business logic in controllers.

Do not let DTOs become domain objects.

Do not let JPA entities leak into the domain unless intentionally chosen for a specific MVP shortcut.

---

## Authentication and Internal User Identity

Firebase Authentication is the external identity provider for mobile and web clients.

The backend must remain stateless and must validate Firebase ID tokens from:

```text
Authorization: Bearer <firebase-id-token>
```

Authentication flow:

```text
Firebase ID token
→ verify signature and standard token claims with Firebase Admin SDK
→ resolve (issuer, subject) in user_auth_identity
→ provision user_account and user_auth_identity on first valid login
→ expose the internal UserId through CurrentUserProvider
→ application use case
```

Important rules:

* Firebase authenticates external identities; it does not own the FitLake domain user.
* `user_account.user_id` is the canonical application identity used by all domain data and ownership checks.
* Firebase UID must never be used as a domain ID or foreign key.
* The future `daily` module receives only the internal `UserId` and must not depend on provider subjects or tokens.
* Resolve Firebase users only by `(issuer, external_subject)`, never by email.
* Email is mutable profile data and may be duplicated across internal users.
* A Firebase email claim may seed `user_account.email` even when it is not verified. A later non-null email claim for the same `(issuer, external_subject)` synchronizes the stored email, regardless of verification status.
* A missing or blank Firebase email claim must not erase an existing application email.
* Email synchronization must never be used to resolve, merge, or reassign accounts.
* Token verification must happen before opening the provisioning database transaction.
* Provisioning must be idempotent and recover safely from concurrent first-login unique-constraint races.
* Controllers and application services must use `CurrentUserProvider`; they must not parse bearer tokens or depend on Firebase SDK classes.
* Domain objects and repositories must not access Spring Security or `CurrentUserProvider`.
* Firebase SDK classes must remain inside `auth.infrastructure.firebase`.
* Do not store passwords, Firebase ID tokens, refresh tokens, API keys, or service-account credentials in the database or logs.
* `/actuator/health`, `/v3/api-docs/**`, and Swagger UI are public; `/api/**` requires authentication; other routes are denied unless explicitly designed and documented.
* Do not enable form login, HTTP Basic, or server-side sessions for the API.
* Tests must use fake token verifiers and synthetic claims. Real Firebase credentials are not required for automated tests.
* Service-account JSON files must remain outside the repository and be supplied through Application Default Credentials.
* Secrets must never be committed.

`user_auth_identity` is distinct from `user_channel_identity`:

* `user_auth_identity` proves who may authenticate as an internal user.
* `user_channel_identity` maps a Telegram or future messaging identity to an internal user for channel delivery and ingestion.

---

## MVP Database

Use PostgreSQL.

Use `UUID` primary keys.

Use `JSONB` for flexible capture payloads and final food logs.

Use optimistic locking where useful, especially on user-editable captures and days.

The MVP database contains these tables:

```text
1. user_account
2. user_auth_identity
3. user_channel_identity
4. daily_day
5. daily_inbox_event
6. daily_capture
7. ai_interpretation_log
8. daily_metrics
9. user_food
10. user_food_alias
```

`user_food` and `user_food_alias` were explicitly introduced for the private manual catalog by Flyway V4. Do not introduce further database tables unless a task explicitly requires it.

V4 also enables `pg_trgm`. Local and production PostgreSQL installations must provide this extension, and the Flyway role must be allowed to execute `CREATE EXTENSION IF NOT EXISTS pg_trgm`.

For the MVP, do not create a separate `meal` or `meal_item` table.

For the MVP, do not create a `daily_fact_event` table unless explicitly requested. The current MVP source of truth before finalization is `daily_capture`.

---

## Table: user_account

Stores internal application users.

Expected columns:

```text
user_id UUID PK
email VARCHAR nullable
display_name VARCHAR nullable
timezone VARCHAR not null default Europe/Rome
created_at TIMESTAMPTZ
updated_at TIMESTAMPTZ
```

Purpose:

* identify the owner of daily data
* store default timezone
* connect app users to Telegram/mobile identities

Important rule:

The user timezone is required for interpreting natural dates such as today, yesterday, this morning, and tonight.

Email is profile data, not an authentication identifier. Do not restore a unique constraint on `user_account.email` and do not merge users by email.

---

## Table: user_food

Stores one reusable private nutrition definition owned by an internal user.

Core columns:

```text
user_food_id UUID PK
user_id UUID FK user_account
name VARCHAR
normalized_name VARCHAR
brand VARCHAR nullable
barcode VARCHAR nullable
description VARCHAR nullable
basis_amount NUMERIC
basis_unit VARCHAR
calories_kcal NUMERIC nullable
protein_grams NUMERIC nullable
carbohydrates_grams NUMERIC nullable
fat_grams NUMERIC nullable
fiber_grams NUMERIC nullable
sugars_grams NUMERIC nullable
saturated_fat_grams NUMERIC nullable
sodium_milligrams NUMERIC nullable
salt_grams NUMERIC nullable
default_serving_amount NUMERIC nullable
default_serving_unit VARCHAR nullable
grams_per_piece NUMERIC nullable
milliliters_per_piece NUMERIC nullable
grams_per_serving NUMERIC nullable
milliliters_per_serving NUMERIC nullable
source_type VARCHAR
source_provider VARCHAR nullable
source_external_id VARCHAR nullable
source_notes VARCHAR nullable
source_copied_at DATE nullable
created_at TIMESTAMPTZ
updated_at TIMESTAMPTZ
deleted_at TIMESTAMPTZ nullable
version BIGINT
```

Important database rules:

* Numeric and unit invariants are protected by PostgreSQL check constraints as a second line of defense after domain validation.
* The pair `(user_food_id, user_id)` is unique so aliases can use a composite owner-preserving foreign key.
* Active non-null barcode is unique by `(user_id, barcode)`, not globally.
* Partial B-tree indexes support active user-scoped list sorts and prefix lookup.
* A partial GIN `gin_trgm_ops` index supports active normalized-name fuzzy lookup.
* Soft-deleted rows remain available for future historical references but are absent from current APIs.

---

## Table: user_food_alias

Stores searchable aliases belonging to one private food and repeats `user_id` to make tenant ownership enforceable in the foreign key and indexes.

Expected columns:

```text
alias_id UUID PK
user_food_id UUID
user_id UUID
alias VARCHAR
normalized_alias VARCHAR
created_at TIMESTAMPTZ
deleted_at TIMESTAMPTZ nullable
```

Important database rules:

* `(user_food_id, user_id)` references the same pair on `user_food`.
* Active normalized alias is unique by `(user_id, normalized_alias)` across the user's active foods.
* Partial prefix and `gin_trgm_ops` indexes support active alias search.
* Replacing aliases soft-deletes removed alias rows; deleting a food removes all of its aliases from active lookup.

---

## Table: user_auth_identity

Maps a verified external authentication identity to one internal user.

Expected columns:

```text
auth_identity_id UUID PK
user_id UUID FK user_account
provider VARCHAR
issuer VARCHAR
external_subject VARCHAR
email_at_link_time VARCHAR nullable
created_at TIMESTAMPTZ
last_login_at TIMESTAMPTZ
```

Expected constraints:

```text
provider IN (FIREBASE)
UNIQUE(issuer, external_subject)
UNIQUE(user_id, issuer)
```

Purpose:

* resolve a Firebase token to a stable internal `UserId`
* provision an internal user on first valid login
* support future authentication providers without leaking provider IDs into domain tables
* retain the email observed at link time for audit without treating it as identity

Important rules:

* `external_subject` is the Firebase token subject/UID and is meaningful only together with `issuer`.
* Repeated login updates `last_login_at` without creating a second user.
* The database unique constraints are the final concurrency guard for first-login provisioning.
* Do not use this table for Telegram chat identity; use `user_channel_identity` for channels.

---

## Table: user_channel_identity

Maps external channel identities to internal users.

Expected columns:

```text
channel_identity_id UUID PK
user_id UUID FK user_account
channel VARCHAR
external_user_id VARCHAR
external_chat_id VARCHAR nullable
created_at TIMESTAMPTZ
```

Expected unique constraint:

```text
UNIQUE(channel, external_user_id)
```

Purpose:

* connect Telegram users to internal `user_account`
* support future channels without changing the domain
* keep Telegram identity outside the core daily domain

Example:

```text
channel = TELEGRAM
external_user_id = Telegram user id
external_chat_id = Telegram chat id
```

---

## Table: daily_day

Represents one user day.

Expected columns:

```text
day_id UUID PK
user_id UUID FK user_account
day_date DATE
status VARCHAR
opened_at TIMESTAMPTZ
confirmed_at TIMESTAMPTZ nullable
reopened_at TIMESTAMPTZ nullable
created_at TIMESTAMPTZ
updated_at TIMESTAMPTZ
version BIGINT
```

Expected unique constraint:

```text
UNIQUE(user_id, day_date)
```

Allowed statuses:

```text
OPEN
CONFIRMED
REOPENED
```

Purpose:

* track whether a day is still open or already finalized
* provide a parent row for captures, inbox events, and metrics
* simplify day-level operations such as finalization and reopening

Important rules:

* A day starts as `OPEN`.
* When the user closes the day, status becomes `CONFIRMED`.
* If future edits are allowed after confirmation, the day may become `REOPENED` or metrics may be recalculated.

---

## Table: daily_inbox_event

Stores raw incoming input.

Expected columns:

```text
inbox_event_id UUID PK
user_id UUID FK user_account
day_id UUID FK daily_day nullable
channel VARCHAR
source_type VARCHAR
source_message_id VARCHAR nullable
source_callback_id VARCHAR nullable
raw_text TEXT nullable
transcript_text TEXT nullable
normalized_text TEXT nullable
raw_payload JSONB nullable
processing_status VARCHAR
processing_started_at TIMESTAMPTZ
processing_attempt_id UUID
error_code VARCHAR nullable
error_message TEXT nullable
received_at TIMESTAMPTZ
processed_at TIMESTAMPTZ nullable
created_at TIMESTAMPTZ
replaces_capture_id UUID FK daily_capture nullable
```

Recommended indexes:

```text
(user_id, received_at DESC)
UNIQUE(user_id, channel, source_message_id) WHERE source_message_id IS NOT NULL
```

Allowed source types:

```text
TEXT_MESSAGE
VOICE_MESSAGE
CALLBACK
MOBILE_AI_INPUT
MOBILE_UI_ACTION
```

Current REST AI channels:

```text
REST_AI_MESSAGE
REST_AI_REPROCESS
```

Allowed processing statuses:

```text
RECEIVED
PROCESSING
PROCESSED
FAILED
IGNORED
```

Purpose:

* audit raw user input
* debug AI interpretation
* retry failed processing
* prevent duplicate Telegram webhook processing
* keep original text/audio transcription separate from structured capture

Important rules:

* Save the inbox event before calling AI.
* Do not lose the raw input.
* Use `processing_started_at` as a renewable processing lease so a crashed request does not reserve an idempotency key forever.
* Use `processing_attempt_id` as the fencing token. A worker may commit or record failure only while its attempt still owns the current lease.
* A source event may create at most one capture; enforce this in PostgreSQL as well as application code.
* `replaces_capture_id` is the single audit link from a reprocess event to the old proposal.
* Do not put business state in this table.
* This is an operational/audit table, not the final daily state.

---

## Table: daily_capture

Stores confirmable pieces of daily data.

This is the core MVP table.

Expected columns:

```text
capture_id UUID PK
user_id UUID FK user_account
day_id UUID FK daily_day
source_event_id UUID FK daily_inbox_event nullable
capture_type VARCHAR
status VARCHAR
payload JSONB
confidence NUMERIC nullable
created_by VARCHAR
updated_by VARCHAR nullable
accepted_at TIMESTAMPTZ nullable
rejected_at TIMESTAMPTZ nullable
deleted_at TIMESTAMPTZ nullable
expired_at TIMESTAMPTZ nullable
created_at TIMESTAMPTZ
updated_at TIMESTAMPTZ
version BIGINT
```

Recommended indexes:

```text
(day_id, status)
(user_id, day_id)
(created_at DESC)
GIN(payload)
```

Allowed statuses:

```text
OPEN
ACCEPTED
REJECTED
SOFT_DELETED
EXPIRED
```

Allowed capture types:

```text
FOOD
DAILY_FIELDS
MIXED
NOTE
```

Allowed created_by values:

```text
AI
USER_UI
SYSTEM
```

Allowed updated_by values:

```text
USER_UI
SYSTEM
AI
```

Purpose:

* represent one proposed daily insertion
* allow the user to confirm or reject it
* store multiple foods in one capture
* support direct mobile edits after confirmation
* act as source data for final daily metrics

Important rules:

* AI creates `OPEN` captures only through backend validation.
* User confirmation changes `OPEN` to `ACCEPTED`.
* User rejection changes `OPEN` to `REJECTED`.
* Mobile UI edits can modify accepted capture payloads directly for the MVP.
* Soft delete should set status to `SOFT_DELETED` or `deleted_at`, not physically delete the row.
* Only `ACCEPTED` captures are used for finalization.
* `REJECTED`, `EXPIRED`, and `SOFT_DELETED` captures must not contribute to `daily_metrics`.

---

## daily_capture Payload: FOOD

Example user input:

```text
Colazione con 40 g avena, 150 ml latte e 15 g whey.
```

Expected payload:

```json
{
  "type": "FOOD",
  "meals": [
    {
      "mealTempId": "meal_tmp_001",
      "mealName": "colazione",
      "items": [
        {
          "itemTempId": "item_tmp_001",
          "foodName": "avena",
          "quantity": 40,
          "unit": "g"
        },
        {
          "itemTempId": "item_tmp_002",
          "foodName": "latte",
          "quantity": 150,
          "unit": "ml"
        },
        {
          "itemTempId": "item_tmp_003",
          "foodName": "whey",
          "quantity": 15,
          "unit": "g"
        }
      ]
    }
  ]
}
```

Rules:

* Multiple foods in the same user message should usually become one capture with multiple items.
* `mealName` may be inferred when reasonable.
* If meal name is missing, use `null` or a backend-approved fallback such as `non_assegnato`.
* Quantities may be null only if acceptable for the specific food/context.
* Units must be normalized where possible, for example `grammi` → `g`.

---

## daily_capture Payload: DAILY_FIELDS

Example user input:

```text
Ho dormito 6 ore e peso 78.4.
```

Expected payload:

```json
{
  "type": "DAILY_FIELDS",
  "fields": {
    "sleepHours": 6,
    "bodyWeightKg": 78.4
  }
}
```

Allowed MVP fields:

```text
bodyWeightKg
sleepHours
stepsCount
hydrationLiters
caffeineMg
moodLevel
focusLevel
stressLevel
dailyNotes
```

Rules:

* Validate numeric ranges in the backend.
* Do not trust AI output blindly.
* Field names should use backend canonical names.

---

## daily_capture Payload: MIXED

Example user input:

```text
Stamattina peso 78.4, ho dormito 5 ore e mezza, colazione con 40 g avena e 150 ml latte.
```

Expected payload:

```json
{
  "type": "MIXED",
  "fields": {
    "bodyWeightKg": 78.4,
    "sleepHours": 5.5
  },
  "meals": [
    {
      "mealTempId": "meal_tmp_001",
      "mealName": "colazione",
      "items": [
        {
          "itemTempId": "item_tmp_001",
          "foodName": "avena",
          "quantity": 40,
          "unit": "g"
        },
        {
          "itemTempId": "item_tmp_002",
          "foodName": "latte",
          "quantity": 150,
          "unit": "ml"
        }
      ]
    }
  ]
}
```

Rules:

* A single capture may contain both fields and meals.
* The backend must validate both parts independently.
* Finalization must aggregate all accepted captures consistently.

---

## Table: ai_interpretation_log

Stores AI input/output for debugging and evaluation.

Expected columns:

```text
ai_log_id UUID PK
user_id UUID FK user_account
inbox_event_id UUID FK daily_inbox_event nullable
capture_id UUID FK daily_capture nullable
provider VARCHAR
model VARCHAR
prompt_version VARCHAR
input_text TEXT
context_snapshot JSONB nullable
raw_response JSONB nullable
parsed_output JSONB nullable
status VARCHAR
confidence NUMERIC nullable
error_code VARCHAR nullable
error_message TEXT nullable
latency_ms INTEGER nullable
created_at TIMESTAMPTZ
```

Recommended indexes:

```text
(inbox_event_id)
(user_id, created_at DESC)
```

Allowed statuses:

```text
SUCCESS
FAILED
INVALID_OUTPUT
NEEDS_CLARIFICATION
NO_OP
```

Purpose:

* debug wrong AI interpretations
* compare prompt versions
* inspect model outputs
* understand why a capture was created
* measure latency and reliability

Important rules:

* Do not store secrets in this table.
* Store the immutable terminal result needed for idempotent replay; do not replay a later mutable state of the capture.
* Do not store provider raw responses, chain of thought, Firebase tokens, API keys, prompts containing secrets, or credentials.
* Do not rely on this table for business state.
* Business state belongs to `daily_capture` and `daily_metrics`.

---

## Table: daily_metrics

Stores the finalized daily snapshot.

Expected columns:

```text
day_id UUID PK FK daily_day
user_id UUID FK user_account
day_date DATE
status VARCHAR
body_weight_kg NUMERIC nullable
sleep_hours NUMERIC nullable
steps_count INTEGER nullable
hydration_liters NUMERIC nullable
caffeine_mg INTEGER nullable
mood_level SMALLINT nullable
focus_level SMALLINT nullable
stress_level SMALLINT nullable
total_calories INTEGER nullable
protein_g NUMERIC nullable
carbs_g NUMERIC nullable
fat_g NUMERIC nullable
food_log JSONB
daily_notes TEXT nullable
experimental_data JSONB
generated_from_capture_ids JSONB
confirmed_at TIMESTAMPTZ nullable
recalculated_at TIMESTAMPTZ nullable
created_at TIMESTAMPTZ
updated_at TIMESTAMPTZ
```

Expected unique constraint:

```text
UNIQUE(user_id, day_date)
```

Recommended indexes:

```text
(user_id, day_date DESC)
GIN(food_log)
```

Purpose:

* store the final queryable snapshot of the day
* support history views
* support analytics
* avoid recalculating the whole day every time

Important rules:

* `daily_metrics` is created or updated during day finalization.
* It must be generated from `ACCEPTED` captures only.
* It must not include rejected, expired, or soft-deleted captures.
* `generated_from_capture_ids` should list the accepted captures used for generation.
* If accepted captures are edited after finalization, metrics must be recalculated or the day must be reopened.

Example `food_log`:

```json
[
  {
    "mealName": "colazione",
    "items": [
      {
        "foodName": "avena",
        "quantity": 40,
        "unit": "g",
        "calories": 150,
        "proteinG": 5,
        "carbsG": 27,
        "fatG": 3
      },
      {
        "foodName": "latte",
        "quantity": 150,
        "unit": "ml",
        "calories": 75,
        "proteinG": 5,
        "carbsG": 7,
        "fatG": 3
      }
    ]
  }
]
```

---

## Main Business Flows

### Flow: Telegram Text Insertion

```text
TelegramWebhookController
→ TelegramMessageAdapter
→ InboxEventService.save()
→ DailyCaptureOrchestrator.handleText()
→ AiCaptureInterpreterService.interpret()
→ AiCaptureDecisionValidator.validate()
→ DailyCaptureService.createOpenCapture()
→ TelegramResponsePresenter.renderCaptureConfirmation()
```

Expected result:

* one `daily_inbox_event`
* one `ai_interpretation_log`
* one `daily_capture` with status `OPEN`
* Telegram message with summary and buttons

Buttons:

```text
È corretto
Non è corretto
```

---

### Flow: Capture Confirmation

```text
TelegramCallbackController
→ TelegramCallbackAdapter
→ CaptureConfirmationService.acceptCapture()
→ DailyCaptureService.markAccepted()
→ TelegramResponsePresenter.renderAccepted()
```

Expected DB change:

```text
daily_capture.status = ACCEPTED
daily_capture.accepted_at = now()
```

Do not create `daily_metrics` during individual capture confirmation.

---

### Flow: Capture Rejection

```text
TelegramCallbackController
→ TelegramCallbackAdapter
→ CaptureConfirmationService.rejectCapture()
→ DailyCaptureService.markRejected()
→ TelegramResponsePresenter.renderRejected()
```

Expected DB change:

```text
daily_capture.status = REJECTED
daily_capture.rejected_at = now()
```

Rejected captures must remain in the database for audit/debug.

Do not physically delete rejected captures.

---

### Flow: Mobile UI Edit

The mobile app can edit accepted captures directly.

Example:

```text
User changes avena from 40 g to 50 g.
```

Flow:

```text
DailyCaptureEditController
→ DailyCaptureEditService.updateFoodItemQuantity()
→ validate user ownership
→ validate capture status
→ validate quantity and unit
→ update daily_capture.payload
→ increment version
```

Rules:

* No AI.
* No proposal.
* No Telegram-specific logic.
* The target item is already known by ID or by deterministic payload reference.
* All edit logic must live in `DailyCaptureEditService`, not the controller.

For MVP, direct JSONB update is allowed through a service.

Future versions may replace this internally with mutation/event records, but external API should not depend on that implementation detail.

---

### Flow: Soft Delete From UI

Flow:

```text
DailyCaptureEditController
→ DailyCaptureEditService.softDeleteCapture()
→ validate user ownership
→ set status = SOFT_DELETED
→ set deleted_at = now()
```

Rules:

* Do not physically delete user data in the MVP.
* Soft-deleted captures must not be used for finalization.
* Soft delete must be reversible in the future if needed.

---

### Flow: Day Finalization

The user may close the day by button or natural language.

Examples:

```text
ok giornata finita
```

```text
chiudi giornata
```

Flow:

```text
DailyFinalizationController or TelegramCallbackController
→ DailyFinalizationService.finalizeDay()
→ check daily_day status
→ check OPEN captures
→ aggregate ACCEPTED captures
→ calculate metrics
→ upsert daily_metrics
→ mark daily_day as CONFIRMED
```

Rules:

* If `OPEN` captures exist, do not silently ignore them.
* Ask the user to review, confirm, or reject open captures before finalization.
* Only `ACCEPTED` captures contribute to final metrics.
* Finalization must be deterministic.
* Running finalization twice should not duplicate metrics.

---

## Validation Rules

Backend validation is mandatory.

Validate AI output before creating a capture.

Validate UI edits before updating payloads.

Minimum validations:

* user owns the day
* user owns the capture
* day exists or can be created
* capture status allows the requested transition
* quantity is numeric if present
* quantity is not negative
* unit is allowed or normalized
* food name is not blank
* field name is supported
* field value type is correct
* day is not finalized unless the use case allows reopening/recalculation

For the private food catalog also validate:

* authenticated user owns every accessed food
* name and aliases satisfy length and nonblank limits
* aliases are unique after centralized normalization
* barcode contains 8 to 14 digits when present
* nutrition basis and default-serving amounts are positive
* nutrients are nullable or nonnegative decimal values within the defensive maximum
* conversion values are positive and do not define both mass and volume for one piece/serving
* the nutrition-basis/default-serving unit pair is deterministic or has explicit conversion metadata
* nutrition-source provider/external-ID consistency
* list pagination, sort allowlist, search length, and search limit

Do not trust client-side validation.

Do not trust AI validation.

---

## Transaction Rules

Do not keep database transactions open while calling AI.

Recommended text insertion flow:

```text
Transaction 1:
- save daily_inbox_event

Outside transaction:
- call AI
- parse AI output

Transaction 2:
- save ai_interpretation_log
- create daily_capture
- mark inbox event as PROCESSED
```

For reprocess, the short terminal transaction must lock in the order `inbox event → day → previous capture`, create the new capture, reject the old proposal, write the terminal audit, and complete the event atomically. Never reject the old capture before the new one is valid and persisted.

The network/model call always occurs outside a database transaction. A stale `PROCESSING` event may renew its lease and retry; database uniqueness on `source_event_id` remains the final duplicate-capture guard.

Recommended finalization flow:

```text
Single transaction:
- lock daily_day
- load accepted captures
- aggregate
- upsert daily_metrics
- mark daily_day CONFIRMED
```

Use optimistic locking on:

* `daily_day`
* `daily_capture`
* `user_food`

Catalog CRUD and search must use the shared transaction abstraction. PostgreSQL's transaction-local `pg_trgm` threshold must be set and consumed inside the same search transaction.

---

## API/Controller Rules

Controllers must be thin.

Controllers may:

* receive requests
* authenticate user
* parse request DTOs
* call application services
* return response DTOs

Controllers must not:

* call repositories directly
* contain AI logic
* contain aggregation logic
* edit JSON payloads directly
* decide business transitions
* normalize food names or aliases
* implement personal-food ranking or query persistence adapters directly

---

## Telegram Rules

Telegram must stay in the adapter layer.

Telegram may:

* receive webhook updates
* extract message text
* extract callback data
* map external input to internal commands
* render response text
* render inline buttons

Telegram must not:

* validate business rules
* decide capture transitions
* call repositories directly
* aggregate daily metrics
* know how food logs are stored internally

Telegram buttons for capture confirmation:

```text
È corretto
Non è corretto
```

Suggested callback format:

```text
daily_capture:accept:<captureId>
daily_capture:reject:<captureId>
daily_day:finalize:<dayId>
```

Do not expose sensitive information in callback data.

Always verify ownership server-side.

---

## Mobile Rules

The mobile app uses the same backend services as Telegram.

Mobile can:

* list daily captures
* list current daily state
* edit accepted captures directly
* soft delete captures
* close the day
* show finalized metrics
* optionally submit text to the same AI insertion flow

Mobile must not:

* bypass backend validation
* write directly to database
* duplicate business rules that belong to backend
* rely on Telegram-specific behavior

Important distinction:

```text
Natural language input → AI insertion flow
Precise UI action → direct application service
```

---

## Naming Rules

Use clear names.

Preferred names:

```text
DailyCapture
DailyCaptureService
DailyCaptureEditService
CaptureConfirmationService
DailyFinalizationService
AiCaptureInterpreterService
DailyMetricsProjectionService
UserFood
UserFoodService
UserFoodSearchService
SearchUserFoodsUseCase
```

Avoid misleading names such as:

```text
AiAddMealService
TelegramDailyService
DatabaseWriterAgent
MagicParser
```

AI-related classes should make it clear that AI proposes or interprets; it does not execute business mutations directly.

---

## What Not To Do

Never let AI write to the database.

Never let AI call repositories.

Never put business logic in controllers.

Never make Telegram the center of the architecture.

Never make mobile DTOs the domain model.

Never create microservices for this MVP.

Never create meal/item relational tables unless explicitly requested.

Never physically delete captures for normal user rejection.

Never create daily metrics from rejected or open captures.

Never skip backend validation because the AI output seems correct.

Never introduce correction/modification AI flows unless explicitly requested.

Never create a Daily capture as a side effect of personal-food CRUD or search.

Never call AI, an external nutrition API, embeddings, a vector store, or RAG from personal-food CRUD or search.

Never accept a catalog owner ID, normalized name/alias, deletion timestamp, or persistence version from a client.

Never treat a missing catalog nutrient as zero.

Never convert between mass/volume and piece/serving without explicit conversion metadata.

Never update historical Daily data when a catalog definition changes or is soft-deleted.

---

## Testing Expectations

Add or update tests for non-trivial changes.

Prioritize tests for:

* AI decision validation
* capture creation
* capture confirmation
* capture rejection
* direct mobile edit
* soft delete
* day finalization
* aggregation of multiple food items
* rejected captures excluded from metrics
* open captures blocking finalization
* invalid units
* invalid quantities
* user ownership checks
* first-login user provisioning
* repeated-login provisioning idempotency
* same email with different authentication subjects
* invalid and expired Firebase tokens
* public health and protected API endpoint policy
* authentication identity uniqueness and persistence mapping
* one terminal AI tool and defensive rejection of zero/multiple/unknown tools or free text
* AI message idempotency, immutable replay, and stale-processing recovery
* complete-text reprocess success and failure invariants
* atomic replacement rollback and concurrent reprocess locking on PostgreSQL
* private-food creation, backend-generated identity, retrieval, full-definition replacement, and soft deletion
* private-food domain validation for basis, nutrients, aliases, barcode, serving, conversions, and source metadata
* authenticated user isolation for private-food read, update, delete, list, uniqueness, and search
* alias replacement and normalized-name recomputation
* pagination boundaries and stable list ordering
* exact barcode, exact alias/name, alias/name prefix, and alias/name typo ranking order
* deterministic search result limiting, deduplication, deletion filtering, and user scoping
* `pg_trgm` migration, indexes, decimal round-trip, alias persistence, and database constraints on PostgreSQL
* no real AI provider, Firebase provider, network, or credentials in automated tests

Recommended test style:

* unit tests for domain and validators
* service tests for application use cases
* integration tests for persistence and finalization
* controller tests only for request/response behavior

---

## Definition of Done

A task is done only when:

* the code compiles
* relevant tests pass
* controllers remain thin
* application services own the use case
* domain rules are not duplicated in adapters
* AI cannot persist or mutate state directly
* database changes are represented by migrations
* new tables or columns are documented
* authenticated use cases depend on the internal `UserId`, not Firebase SDK types
* authentication tests do not require real provider credentials
* personal-food endpoints remain manual, private, and independent from Daily and AI
* personal-food nutrients preserve unknown values as null and use decimal-safe types
* personal-food search remains user-scoped, deterministic, and backed by the V4 PostgreSQL indexes
* future Daily integration preserves an immutable nutrition snapshot rather than reading mutable catalog values as history
* Telegram and mobile flows still use shared backend services
* daily finalization remains deterministic
