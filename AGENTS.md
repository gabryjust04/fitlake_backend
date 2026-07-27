# AGENTS.md

## Project Overview

FitLake is a personal daily tracking system.

The current scope is the **Daily module only**.

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

Do not add AI correction/modification flows unless explicitly requested.

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

Application services should include:

* `DailyCaptureOrchestrator`
* `InboxEventService`
* `AiCaptureInterpreterService`
* `DailyCaptureService`
* `DailyCaptureEditService`
* `CaptureConfirmationService`
* `DailyFinalizationService`
* `DailyMetricsProjectionService`

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
```

Do not introduce new database tables unless the task explicitly requires it.

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
error_code VARCHAR nullable
error_message TEXT nullable
received_at TIMESTAMPTZ
processed_at TIMESTAMPTZ nullable
created_at TIMESTAMPTZ
```

Recommended indexes:

```text
(user_id, received_at DESC)
UNIQUE(channel, source_message_id) WHERE source_message_id IS NOT NULL
```

Allowed source types:

```text
TEXT_MESSAGE
VOICE_MESSAGE
CALLBACK
MOBILE_AI_INPUT
MOBILE_UI_ACTION
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
```

Purpose:

* debug wrong AI interpretations
* compare prompt versions
* inspect model outputs
* understand why a capture was created
* measure latency and reliability

Important rules:

* Do not store secrets in this table.
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
* Telegram and mobile flows still use shared backend services
* daily finalization remains deterministic
