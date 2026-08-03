# AGENTS.md

## Project Overview

FitLake is a personal daily tracking system.

The primary product scope is the **Daily module**. The backend also contains a supporting private food-catalog module for manually managed reusable nutrition definitions. Manual Daily content links definitions by an exact selected `userFoodId`; natural-language insertion may read one exact match or one sufficiently strong and clearly separated prefix/fuzzy match among active owned foods as described below. AI estimates never create or update catalog definitions.

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
→ pure CaptureInterpreterPort returns one structured semantic outcome
→ backend validates schema, verbatim fragments, quantities, and per-basis nutrition
→ backend resolves each food through the owned catalog policy or AI fallback
→ NO_RELEVANT_DATA completes without a capture
→ otherwise backend creates daily_capture with status OPEN
→ bot shows the capture summary
→ user clicks "È corretto" or "Non è corretto"
→ capture becomes ACCEPTED or REJECTED
```

Typical mobile UI edit flow:

```text
User edits one or more values from the mobile app
→ target capture is known by ID and current version
→ client submits the complete replacement entries array
→ backend validates ownership and the whole payload atomically
→ backend replaces the editable capture and writes one UI_EDIT audit
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
GET    /api/daily/days/{date}/captures
GET    /api/daily/captures/{captureId}
POST   /api/daily/captures/{captureId}/accept
POST   /api/daily/captures/{captureId}/reject
POST   /api/daily/captures/{captureId}/reprocess
PUT    /api/daily/captures/{captureId}
DELETE /api/daily/captures/{captureId}
POST   /api/daily/days/{date}/finalize
POST   /api/daily/days/{date}/reopen
GET    /api/daily/days/{date}/metrics
```

Important REST rules:

* REST controllers obtain only the internal `UserId` from `CurrentUserProvider`.
* A manual REST insertion creates a `daily_capture` with `created_by = USER_UI`, `status = OPEN`, and `source_event_id = null`.
* Manual and AI captures use schema v2 typed entries only; v1 and missing-version payloads are unsupported.
* The backend generates entry/item UUIDs when the client omits them.
* `PUT /captures/{captureId}` is the only content-update route and replaces the complete entries array.
* Capture and day ownership must be checked on every command and query.
* Returning `404` for a capture owned by another user is preferred to leaking its existence.
* Confirmed days are immutable until `POST /days/{date}/reopen` changes them to `REOPENED`.
* Reopening marks the existing metrics snapshot `REOPENED`; the next finalization recalculates and updates it.
* Finalization returns `409` while any open capture exists.
* Only accepted captures contribute to the metrics snapshot.
* Repeated scalar fields use the last non-null value in deterministic capture creation order.
* Food logs concatenate meals; calories and core macros are summed with strict unknown-value propagation.
* Finalization is idempotent and returns the existing snapshot for an already confirmed day.
* REST validation errors return `400`, missing resources return `404`, and invalid state transitions return `409`.
* `POST /days/{date}/messages` and `POST /captures/{captureId}/reprocess` require an `Idempotency-Key` header.
* One idempotency key represents one normalized complete text and terminal result. Reuse with different text is a conflict.
* Reuse with the same normalized text reloads the established capture in its current lifecycle state, or replays `NO_RELEVANT_DATA` or a sanitized recorded failure, without another model call.
* The AI endpoint accepts only `{ "text": "..." }`; structured capture JSON belongs to the manual endpoint.
* A new AI message creates at most one ordinary `OPEN` capture with `created_by = AI` and a backend-owned source event.
* Reprocess accepts only complete replacement text for an owned `OPEN` capture on an editable day.
* A `COMPLETE`, `PARTIAL`, or `UNRESOLVED` reprocess creates a distinct `OPEN` capture and atomically rejects the old proposal as `SYSTEM`.
* Failed or invalid reprocess, and a `NO_RELEVANT_DATA` result, leave the old capture `OPEN` and unchanged.
* There is no conversation memory or relative AI edit flow.

---

## Current Private Food Catalog Slice

A user food is a reusable personal nutrition definition. It is not something consumed on a particular date.

```text
user food = reusable catalog definition
daily capture = occurrence consumed on a date
```

The catalog is authenticated, private, and manually managed. Its CRUD and frontend search operations remain independent from Daily and AI: creating, reading, updating, deleting, listing, or searching a user food must not create or modify a `daily_day`, `daily_capture`, `daily_inbox_event`, or `ai_interpretation_log`, and must not call Spring AI or an external nutrition provider.

Daily has two narrow, read-only integration paths. Manual input may load one exact active owned definition after the frontend selects a `userFoodId`. Natural-language insertion resolves AI-extracted `searchText` through `DailyAiUserFoodMatchPort`: exact active owned matches have priority; otherwise one prefix/fuzzy candidate may be accepted only by the conservative score-and-margin policy. Neither path may mutate the catalog or expose foreign/deleted definitions.

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

They are catalog provenance metadata only. `EXTERNAL_DATABASE` requires provider and external ID metadata, but the catalog does not contact that provider. Catalog provenance `AI_ESTIMATE` marks existing entered values as estimated; it does not authorize an AI call and is distinct from the `AI_ESTIMATE` source of a Daily capture item.

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

Daily manual capture integration goes through `DailyUserFoodLookupPort`, which loads one exact active food by authenticated `UserId` plus `userFoodId`. It must never call search, ranking, controllers, or JPA directly. For every consumed personal food, a capture preserves both:

```text
reference to userFoodId
+ immutable snapshot of name/brand, entered and resolved quantity, basis, all nutrient values, default serving, conversions, source, and lightweight catalog version metadata used at capture time
```

Updating or deleting a catalog definition must never rewrite historical capture nutrition. A new or changed reference requires an active owned food. An existing unchanged reference may remain after deletion, and a quantity-only edit recalculates from the stored snapshot.

### Deterministic manual Daily content

Manual structured Daily input never invokes AI. One submission creates one capture. A capture may contain multiple typed entries, a `FOOD` entry may contain multiple exact personal-food items, and food plus scalar entries forms one mixed capture.

```text
frontend searches /api/me/foods
→ user selects exact userFoodId
→ Daily application validates owned active food
→ deterministic BigDecimal conversion/calculation
→ schema-v2 Daily payload with immutable snapshot
```

Supported consumption units are `GRAM`, `KILOGRAM`, `MILLILITER`, `LITER`, `PIECE`, `SERVING`, and `DEFAULT_SERVING`. Convert kg/g and l/ml directly. Cross dimension conversion is valid only with the exact saved piece/serving metadata. Never infer density, serving size, package size, or grams per piece.

Typed capture entry/item IDs are backend-generated UUIDs. On full replacement, a supplied ID is valid only when it already belongs to the target owned capture. Omitted entries/items are removed. `PUT /api/daily/captures/{captureId}` is the only content-update endpoint; it requires the current capture version and atomically validates, resolves, calculates, persists, increments the version, and writes a `UI_EDIT` audit containing old/new payloads and versions. Stale versions return `409` without mutation.

An `AI_ESTIMATE` item is created only by the natural-language insertion flow. Full replacement may preserve it only when its existing item ID, source type, and entered quantity are unchanged; remove it by omission; or replace it with an exact active owned `USER_FOOD` reference while preserving the item ID. That conversion must use the current catalog snapshot and backend calculation. A client must not create a new `AI_ESTIMATE` or directly change its estimated quantity or nutrition. Use complete-text AI reprocess when interpretation itself must be regenerated.

Payload JSONB is versioned centrally and only schema v2 is supported during development. Encoders always write version 2; readers reject missing, v1, non-integral, and unknown future versions. Existing development databases containing v1 capture or AI-audit JSONB must be recreated rather than supported by compatibility branches. AI catalog resolution must use the narrow application port. Its infrastructure adapter may reuse `SearchUserFoodsUseCase` to obtain ranked candidates, but application/domain code must not call catalog controllers, JPA repositories, or persistence queries directly.

`OPEN` captures and `ACCEPTED` captures on `OPEN` or `REOPENED` days may use full replacement; their state is preserved. `REJECTED`, `SOFT_DELETED`, `EXPIRED`, and captures on confirmed days are immutable. Finalization reads only authoritative stored v2 entries and their immutable nutrition snapshots; any derived projection is internal and never restores v1 wire compatibility.

---

## AI Usage Rules

AI is used only to interpret natural language.

AI may:

* extract food items
* extract quantities and units
* estimate a complete consumed quantity for each extracted food
* estimate calories, protein, carbohydrates, and fat for an explicit nutrition basis for every extracted food item
* infer meal names when reasonable
* extract daily fields such as sleep or weight
* produce one pure structured interpretation
* classify the message as `COMPLETE`, `PARTIAL`, `UNRESOLVED`, or `NO_RELEVANT_DATA`

AI must not:

* write SQL
* call repositories
* call application tools, functions, or persistence callbacks
* generate canonical database IDs
* persist data
* modify accepted captures directly
* create daily metrics directly
* bypass backend validation
* decide final state alone
* put business logic into prompts
* search the personal-food catalog or invent catalog IDs or matches

`CaptureInterpreterPort` is a pure outbound application port. Its request contains only the validated target date, authenticated user's timezone, and complete standalone text. Its response is a `DailyMessageInterpretation`; it has no repository, catalog, `DailyAiTerminalService`, or tool-callback dependency. Only backend application services may validate, resolve catalog candidates, allocate IDs, persist, or change state.

The active prompt contract is `daily-capture-v3`. The schema is always embedded in the system prompt through `BeanOutputConverter`, parsed with unknown-property rejection, and validated again in the backend. Provider-native OpenAI-compatible `response_format=json_schema` is optional and controlled by `fitlake.daily.ai.native-structured-output-enabled` / `FITLAKE_DAILY_AI_NATIVE_STRUCTURED_OUTPUT_ENABLED`. It defaults to `false`; keep it disabled for the current OpenRouter model because that model rejects native JSON Schema, and enable it only for a provider/model that explicitly supports it. Do not reintroduce tool calling as a fallback.

Relevant runtime knobs and defaults:

```text
SPRING_AI_OPENAI_CHAT_MAX_TOKENS=4096
FITLAKE_DAILY_AI_MAX_TEXT_LENGTH=4000
FITLAKE_DAILY_AI_MAX_STRUCTURED_OUTPUT_RETRIES=1
FITLAKE_DAILY_AI_NATIVE_STRUCTURED_OUTPUT_ENABLED=false
FITLAKE_DAILY_AI_FOOD_MATCH_MINIMUM_SCORE=0.78
FITLAKE_DAILY_AI_FOOD_MATCH_MINIMUM_MARGIN=0.12
```

Allowed semantic outcomes:

```text
COMPLETE
PARTIAL
UNRESOLVED
NO_RELEVANT_DATA
```

Outcome rules:

* `COMPLETE` contains at least one structured Daily fact and no unresolved fragments.
* `PARTIAL` contains at least one structured fact plus exact unresolved source fragments; each unresolved fragment becomes a `NOTE` entry in the same `OPEN` proposal.
* `UNRESOLVED` contains no structured facts; the backend preserves the complete original message as one `NOTE` entry in an `OPEN` proposal.
* `NO_RELEVANT_DATA` contains no structured or unresolved facts, creates no capture, and is the only successful no-capture result.
* There is no clarification outcome. Missing quantity or nutrition for a recognizable food is not a reason to omit it: the model must estimate them and record concise assumptions.

Every food and field `originalFragment`, every `PARTIAL` unresolved fragment, and every `DAILY_NOTES.textValue` must be copied verbatim from the submitted text. Paraphrased or absent fragments, unknown fields, model-controlled IDs/state, inconsistent outcomes, invalid ranges, and invalid structure are rejected. A bounded corrective call may retry invalid structured output; `fitlake.daily.ai.max-structured-output-retries` defaults to `1` and must remain in `0..3`.

Every proposed food item must contain:

* nonblank `originalFragment` and `searchText`;
* nullable `statedQuantity`, present only for a quantity explicitly stated by the user;
* mandatory positive `estimatedQuantity` describing the model's best estimate of the complete consumed amount;
* mandatory positive `nutritionEstimate.basis`;
* non-null, non-negative calories, protein, carbohydrates, and fat for that basis, plus nullable non-negative optional nutrients;
* an estimated quantity directly scalable from the nutrition basis: mass-to-mass, volume-to-volume, piece-to-piece, or serving-to-serving.

The AI estimate is mandatory even when a catalog match is likely because it is the atomic fallback. The backend scales the estimate from its basis to the selected consumed quantity. Missing or invalid core estimates make the structured output invalid.

After the model call, the backend resolves each food item independently:

* `DailyAiUserFoodMatchPort` is scoped to the authenticated internal `UserId` and returns candidates only from active owned foods.
* One exact barcode/name/alias match wins. More than one exact match is ambiguous.
* Without an exact match, a prefix/fuzzy candidate is accepted only when the best score is at least `fitlake.daily.ai.food-match.minimum-score` (default `0.78`) and, when a runner-up exists, leads it by at least `fitlake.daily.ai.food-match.minimum-margin` (default `0.12`). A first search result is never accepted blindly, and an approximate result is rejected when the bounded 50-candidate search window may be truncated.
* An accepted catalog match is usable only when calories plus all three core macronutrients are present and the selected quantity converts deterministically through the saved basis/default-serving/conversion metadata.
* Catalog quantity priority is: explicit `statedQuantity`, saved default serving, then `estimatedQuantity`.
* A usable match becomes `USER_FOOD`; backend calculation from its immutable snapshot replaces the entire AI estimate.
* An absent, ambiguous, weak, nutritionally incomplete, or unconvertible match becomes `AI_ESTIMATE`. Use `statedQuantity` only when it scales from the AI basis; otherwise use `estimatedQuantity`.
* Use the complete AI nutrient set together and never mix catalog and AI nutrients within one item.
* Never coerce missing catalog nutrients to zero.

`USER_FOOD` and `AI_ESTIMATE` items are both ordinary `OPEN` proposals and require user confirmation. An `AI_ESTIMATE` has no `userFoodId` or user-food snapshot and must never be inserted into or used to update the private catalog. Sanitized audit data retains the semantic outcome, retry/token counts, catalog decision, match type/score/reason, quantity source, and fallback outcome, but never provider raw output, prompts, secrets, tokens, credentials, or chain of thought.

The only supported user-facing AI correction is complete-text reprocess of an `OPEN` proposal. The bounded structured-output retry only repairs the same provider response contract; it is not conversation memory or a capture edit. Do not add relative, conversational, accepted-capture, or agentic AI modification flows unless explicitly requested.

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
* `DailyAiMessageService`
* `DailyAiTerminalService`
* `DailyAiCaptureProposalFactory`
* `DailyCaptureService`
* `DailyCaptureEditService`
* `DailyManualCaptureService`
* `CaptureConfirmationService`
* `DailyFinalizationService`
* `DailyMetricsProjectionService`
* `UserFoodService`
* `UserFoodSearchService`

`CaptureInterpreterPort` is the pure provider boundary and returns data only; the backend terminal service owns all effects. `SearchUserFoodsUseCase` is the ranked catalog-search boundary used by the frontend and may be reused inside the infrastructure implementation of `DailyAiUserFoodMatchPort`. Manual Daily content uses `DailyUserFoodLookupPort` for exact ownership-safe ID lookup. Natural-language resolution receives ranked active owned candidates through the narrow AI port, applies exact-first and conservative score/margin policy, and returns a Daily-owned read model. Application/domain code must not depend directly on catalog JPA repositories, JDBC queries, controllers, or `pg_trgm` details.

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

## Technical Logging Policy

Technical logs describe application and infrastructure behavior. They are not durable business history and must never replace application persistence, `daily_capture_audit`, `ai_interpretation_log`, metrics, or distributed traces. These concerns remain separate:

```text
technical logs         = operational events and failures
daily_capture_audit    = durable capture lifecycle and payload history
ai_interpretation_log  = sanitized, durable AI execution metadata
metrics                = aggregated system behavior
traces                 = request and dependency paths
```

### Stack and structure

* Application code uses SLF4J, with one local logger per class that emits meaningful events. Logback remains Spring Boot's logging backend.
* Production output is structured ECS JSON written to stdout. The deployment platform, not business code, is responsible for collecting and forwarding stdout.
* Do not call Logback APIs from production code, use `println`, write local rolling files by default, or send logs directly to a remote logging vendor.
* Do not introduce a generic `LoggingService`, annotation-based logging around every method, or an elaborate logging framework. A small duration or constants helper is acceptable when it does not hide SLF4J.
* Significant application events use SLF4J fluent key-value logging. The stable machine-queryable `event` value uses lowercase `snake_case`, normally `<domain>_<resource>_<action>`, and the human message remains concise.
* Use stable fields where applicable, such as `outcome`, `requestId`, `userRef`, resource IDs, status, old/new version, counts, `durationMs`, provider/model/prompt version, and a normalized `errorCode`. Do not use exception messages as error codes or emit null-heavy records.
* Measure elapsed time with `System.nanoTime()`, not wall-clock timestamps. Do not log every method, repository call, or normal framework operation.
* Logging stays outside pure domain objects. Meaningful business events belong in application use cases/orchestrators; HTTP completion and external-provider details belong at their infrastructure boundaries. Controllers normally rely on those layers and centralized exception handling.

### Privacy and data minimization

The restrictions below apply at every level, including `TRACE` and `DEBUG`. Technical logs must never contain:

* authorization headers, Firebase ID or refresh tokens, cookies, passwords, API keys, service-account JSON, private keys, database credentials, or complete JWT claims;
* email addresses, phone numbers, personal names or addresses, Firebase subjects/UIDs, or other external identity contents;
* actual foods or food names, nutrition values, body weight, pain, mood, stress, focus, sleep, notes, unstructured activity text, full Daily payloads, or complete personal-food definitions;
* user messages, prompts, model responses, raw provider responses, full structured AI output, chain of thought, hidden reasoning, or provider credentials;
* request or response bodies, multipart contents, raw query strings, sensitive query parameters, or command/DTO serialization.

Do not add request/response body-caching logging filters. Prefer privacy-safe metadata such as content length, entry/food counts, capture type, presence flags, result count, match type, coarse score bucket, retry count, token usage, IDs, status, and elapsed duration. An internal FitLake UUID may be logged as `userRef` only for a meaningful user-owned operation; never log email or Firebase UID, include `userRef` indiscriminately, or use user IDs as metric tags.

Exception messages may contain user or provider content. Include a throwable only at the single boundary responsible for an unexpected failure, and never copy its raw message into a structured field without proving it is sanitized.

### HTTP correlation and completion

* Every HTTP request resolves an optional `X-Request-Id`. Accept only 1 to 100 letters, digits, dots, underscores, or hyphens; otherwise generate a UUID.
* Store the resolved value as `requestId` in SLF4J MDC for the complete synchronous servlet request, return it in the `X-Request-Id` response header, and always clear it in `finally`/`MDC.putCloseable`, including exceptional paths. Reused server threads must never inherit a prior request's MDC.
* Emit one `http_request_completed` event after a normal request. Include method, resolved route template, response status, outcome, request ID, and monotonic `durationMs`. Do not log both request start and completion at `INFO` without a demonstrated need.
* Prefer a route template such as `/api/daily/captures/{captureId}` over a concrete URL. Never fall back to raw paths or query strings that may expose identifiers or content. Noisy probes such as health may log at a lower level.
* Existing trace/span identifiers supplied by a future Micrometer tracing integration may coexist with `requestId`; they do not replace its validation, response header, or cleanup behavior.

The current application has no application-owned `@Async`, coroutine-dispatcher, scheduled, custom-executor, or reactive request handoff, so the MDC guarantee is limited to the synchronous servlet chain. If asynchronous work is added, use Spring-supported context propagation and add tests for both MDC/observation propagation and cleanup; do not assume thread-local MDC crosses an executor boundary.

### Levels and exception ownership

* `TRACE` is exceptional local diagnostics and remains off in production. `DEBUG` is for sanitized internal decisions. `INFO` records meaningful successful lifecycle, authentication provisioning, catalog, Daily, AI, and HTTP completion events. `WARN` records recoverable degradation, retries, rejected authentication, stale/version conflicts, and other actionable expected conditions. `ERROR` is reserved for unexpected failures or unavailable dependencies that prevent the operation.
* Expected validation, not-found, state-transition, idempotency, and optimistic-lock/domain conflicts normally return their safe `4xx` response without an `ERROR` stack trace. Add an `INFO`/`WARN` event only when it has operational value.
* Unexpected exceptions are logged once, at the outermost boundary that converts them to the safe `500` response, with stable event/error code, exception type, request ID from MDC, and the stack trace. Never return exception messages or stack traces to the client.
* A local catch may log only when it handles/translates the failure, adds otherwise unavailable context, and prevents duplicate outer logging. Do not log a stack trace and then simply rethrow it.
* Authentication failures use normalized codes and sanitized metadata only. Successful token verification does not need an `INFO` event on every request when HTTP and application events already provide visibility.
* Full capture changes remain solely in `daily_capture_audit`. AI technical events may summarize provider, model, prompt version, outcome, retry/token/count metadata and duration, while `ai_interpretation_log` remains the sanitized operational record. Neither full audit payloads nor AI content may be copied into technical logs.

### Configuration and operations

* Common defaults are root/application `INFO`, Spring framework `WARN`, Hibernate SQL `WARN`, and JDBC bind-parameter logging `OFF`.
* The `dev` profile keeps human-readable stdout and enables `com.fitlake` `DEBUG`; it still does not enable SQL bind values.
* The `test` profile reduces noise while retaining application events useful for diagnosing failures.
* The `prod` profile uses Spring Boot's native ECS structured console format at `INFO`, on stdout, with SQL bind logging disabled. Do not add a competing JSON encoder or `logback-spring.xml` unless a requirement cannot be represented by supported Spring Boot properties.
* Only the intended health endpoint may be public. The Actuator `loggers` endpoint is not exposed by default; never expose it, or a broad endpoint wildcard, without operator authorization and a private/dedicated management boundary. Temporary package `DEBUG` currently requires a logging-level environment override and application restart.
* Production log access and retention must be restricted because library stack traces can still reveal technical internals even when application fields are sanitized.

Logging tests must inspect event names and structured key-value fields rather than complete formatted lines. Cover request-ID validation, response propagation, MDC cleanup on success and failure, no cross-request leakage, safe route templates, one unexpected-exception log, absence of stack traces for expected errors, representative application events, profile configuration, Actuator exposure, and conspicuous fake secrets/content never appearing in captured output. Tests must not require a remote logging platform or real credentials.

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
7. daily_capture_audit
8. ai_interpretation_log
9. daily_metrics
10. user_food
11. user_food_alias
```

`user_food` and `user_food_alias` were introduced by Flyway V4, `daily_capture_audit` by V5, its lifecycle coverage was expanded by V6, and V7 added composite owner foreign keys across Daily rows without adding tables. Application ownership checks remain mandatory even though the database now also rejects cross-tenant links. Do not introduce further database tables unless a task explicitly requires it.

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
* A confirmed day may be explicitly reopened; both `daily_day` and its existing metrics snapshot become `REOPENED`.
* Repeated reopen on an already reopened day is idempotent; reopening an initial `OPEN` day is a conflict.
* Finalizing a reopened day recalculates from current accepted captures, updates the same metrics row, and returns both states to `CONFIRMED`.

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
* `replaces_capture_id` links a reprocess inbox event to the old proposal; `daily_capture_audit.related_capture_id` provides the lifecycle link from that old proposal to the newly created replacement.
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
* support atomic full-capture replacement from mobile after confirmation when the day is editable
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

## Table: daily_capture_audit

Stores immutable, owner-scoped capture lifecycle and content-change records.

Expected columns:

```text
audit_id UUID PK
capture_id UUID
user_id UUID
action VARCHAR
actor VARCHAR
old_payload JSONB nullable
new_payload JSONB nullable
old_status VARCHAR nullable
new_status VARCHAR nullable
old_version BIGINT nullable
new_version BIGINT nullable
reason_code VARCHAR nullable
related_capture_id UUID nullable
request_id VARCHAR nullable
created_at TIMESTAMPTZ
```

Allowed actions:

```text
CREATE
ACCEPT
REJECT
UI_EDIT
SOFT_DELETE
REPLACED_BY_REPROCESS
```

Important rules:

* Capture creation and its `CREATE` audit row are committed in the same transaction; the actor is `AI` or `USER_UI`.
* User accept, reject, edit, and soft-delete operations record their corresponding transition in the same transaction as the capture mutation.
* A successful AI reprocess creates and audits the new `OPEN` capture, then records `REPLACED_BY_REPROCESS` on the old capture with actor `SYSTEM`, reason code `REPLACED_BY_REPROCESS`, and `related_capture_id` pointing to the new capture.
* Status-only lifecycle actions do not duplicate payload JSON. `UI_EDIT` records old/new payloads and versions; `CREATE` records only the new payload.
* Failed, invalid, or `NO_RELEVANT_DATA` reprocess must not write a replacement lifecycle audit or mutate the old capture.

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

Stores sanitized AI terminal metadata and replay projections for debugging and evaluation. The raw user input remains in `daily_inbox_event`.

Expected columns:

```text
ai_log_id UUID PK
user_id UUID FK user_account
inbox_event_id UUID FK daily_inbox_event nullable
capture_id UUID FK daily_capture nullable
provider VARCHAR
model VARCHAR
prompt_version VARCHAR
input_text TEXT nullable
context_snapshot JSONB nullable
raw_response JSONB nullable (unused; must remain null)
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
NO_RELEVANT_DATA
```

Purpose:

* debug wrong AI interpretations
* compare prompt versions
* inspect sanitized interpretation and resolution summaries
* understand why a capture was created
* measure latency and reliability

Important rules:

* Do not store secrets in this table.
* Store a sanitized terminal summary for idempotency. Successful replay resolves the established capture and returns its current lifecycle state; it must not duplicate a stale full payload inside the AI log.
* Current AI logs set `input_text = null` because the authoritative original text already belongs to `daily_inbox_event`.
* Keep `raw_response = null`. Do not store provider raw responses, chain of thought, Firebase tokens, API keys, prompts containing secrets, or credentials.
* `context_snapshot` and `parsed_output` may contain only sanitized metadata and projections needed for audit/replay: semantic outcome, retry/token counts, capture summary, and food-resolution decisions.
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
total_calories NUMERIC(18,6) nullable
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

The mobile app replaces one editable capture atomically. There is no item-level mutation endpoint.

Example:

```text
User changes avena from 40 g to 50 g.
```

Flow:

```text
PUT /api/daily/captures/{captureId}
→ DailyManualCaptureService.replace()
→ validate user ownership
→ lock and validate day/capture status and expected version
→ validate every submitted entry and food snapshot/calculation
→ replace the complete schema-v2 entries array
→ increment version and write one UI_EDIT audit row
```

Rules:

* No AI.
* No proposal.
* No Telegram-specific logic.
* The target capture and current version are known; existing entry/item IDs may be reused only within it.
* Omitted entries/items are removed, so the request represents the complete desired capture.
* All replacement logic must live in `DailyManualCaptureService` and its content factory, not the controller.

For MVP, replacing JSONB through the application service is allowed.

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
* Aggregate calories, protein, carbohydrates, and fat across every accepted food item with decimal-safe arithmetic.
* Resolve weight, sleep, steps, hydration, caffeine, mood, focus, stress, and daily notes from accepted captures in deterministic creation order; the last non-null value wins.
* Unknown nutrition remains unknown: if any contributing item lacks one nutrient, that daily nutrient total is `null`, never a partial total or implicit zero.
* On `REOPENED`, recompute from scratch and upsert the existing metrics row with preserved `created_at` and a new `recalculated_at`.

### Flow: Day Reopening

```text
POST /api/daily/days/{date}/reopen
→ lock the owned daily_day
→ require CONFIRMED (or replay REOPENED idempotently)
→ require one consistent confirmed metrics snapshot
→ mark daily_metrics as REOPENED
→ mark daily_day as REOPENED
```

Rules:

* Reopening and finalization serialize on the day lock.
* The reopened metrics row is an explicitly stale snapshot until refinalization.
* Creating, accepting/rejecting, fully replacing, and soft-deleting captures is allowed again while reopened.
* Refinalization still fails while an `OPEN` capture exists.

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
* every AI food proposal contains a positive estimated quantity, a positive nutrition basis, and non-null, non-negative calories, protein, carbohydrates, and fat for that basis within defensive bounds
* the AI estimated quantity scales deterministically from its nutrition basis
* every model source fragment and unresolved fragment is present verbatim in the original text
* every semantic outcome satisfies its structured/unresolved-content invariant
* an automatic catalog match is active, owned, accepted by exact-first plus strong score/margin policy, nutritionally complete for the four core values, and deterministically convertible
* an AI fallback scales the complete estimate from its basis without mixing catalog nutrients
* a full-content request can preserve or remove an existing `AI_ESTIMATE`, or convert that logical item to an exact active `USER_FOOD`; it cannot create or directly modify an estimate

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
- call the pure `CaptureInterpreterPort`
- parse and validate the structured outcome, strict schema, verbatim fragments, quantities, and per-basis nutrition estimate
- resolve each food through short reads on the user-scoped ranked catalog-match port and conservative exact/score/margin policy
- build and validate the schema-v2 `USER_FOOD` or `AI_ESTIMATE` payload

Transaction 2:
- lock the inbox event and day, plus the previous capture for reprocess
- create the new OPEN capture and its CREATE lifecycle audit when the outcome is not NO_RELEVANT_DATA
- for reprocess success, reject the old capture and write REPLACED_BY_REPROCESS with the new capture link
- save the sanitized ai_interpretation_log terminal record
- mark the inbox event PROCESSED, or IGNORED for NO_RELEVANT_DATA
```

For a capture-producing reprocess, the short terminal transaction must lock in the order `inbox event → day → previous capture`, create the new capture, reject the old proposal, write the terminal audit, and complete the event atomically. Never reject the old capture before the new one is valid and persisted. A `NO_RELEVANT_DATA` reprocess records its terminal result and leaves the old capture unchanged.

The network/model call always occurs outside a database transaction. A stale `PROCESSING` event may renew its lease and retry; database uniqueness on `source_event_id` remains the final duplicate-capture guard.

Recommended finalization flow:

```text
Single transaction:
- lock daily_day
- validate existing metrics status against day status
- reject unresolved OPEN captures
- load accepted captures
- aggregate all nutrition and personal-state fields from scratch
- insert or update the single daily_metrics row
- mark daily_day CONFIRMED
```

Reopening is also one transaction: lock the owned day first, mark the consistent confirmed metrics row `REOPENED`, then mark the day `REOPENED`. No capture mutation, reopen, or finalization path may bypass the day-level serialization invariant.

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
* replace the complete content of an editable accepted capture through the single full PUT
* soft delete captures
* close the day
* reopen a confirmed day before changing it
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

Never introduce conversational or relative AI capture-modification flows unless explicitly requested.

Never create a Daily capture as a side effect of personal-food CRUD or search.

Never call AI, an external nutrition API, embeddings, a vector store, or RAG from personal-food CRUD or search.

Never accept a catalog owner ID, normalized name/alias, deletion timestamp, or persistence version from a client.

Never treat a missing catalog nutrient as zero.

Never convert between mass/volume and piece/serving without explicit conversion metadata.

Never update historical Daily data when a catalog definition changes or is soft-deleted.

Never accept a prefix or fuzzy catalog candidate automatically unless it satisfies the configured minimum score and, when a runner-up exists, the configured minimum lead.

Never mix catalog and AI values within one resolved food item.

Never write an AI fallback estimate into the private food catalog.

Never let manual full-content input create or modify an `AI_ESTIMATE` item.

---

## Testing Expectations

Add or update tests for non-trivial changes.

Prioritize tests for:

* AI decision validation
* capture creation
* capture confirmation
* capture rejection
* single-endpoint atomic full-capture replacement and removal of granular/legacy routes
* soft delete
* day finalization
* aggregation of multiple food items
* rejected captures excluded from metrics
* open captures blocking finalization
* aggregation of all personal-state fields and strict unknown nutrition totals
* confirmed-to-reopened transition, stale metrics status, edit while reopened, and refinalization upsert
* repeated reopen/finalize idempotency and ownership-safe missing-day behavior
* invalid units
* invalid quantities
* user ownership checks
* first-login user provisioning
* repeated-login provisioning idempotency
* same email with different authentication subjects
* invalid and expired Firebase tokens
* public health and protected API endpoint policy
* authentication identity uniqueness and persistence mapping
* pure `CaptureInterpreterPort` with no tool callbacks, terminal-service dependency, or persistence side effect
* prompt `daily-capture-v3`, strict JSON conversion, optional native JSON Schema mode, and bounded corrective retries
* `COMPLETE`, `PARTIAL`, `UNRESOLVED`, and `NO_RELEVANT_DATA` outcome invariants
* verbatim food/field/unresolved/note fragments and defensive rejection of tool calls, prose, unknown fields, and backend-owned fields
* required positive estimated quantity and basis plus decimal calories/protein/carbohydrates/fat for every proposed food item
* rejection of missing, negative, or out-of-range AI nutrition estimates without a partial capture
* exact unique owned barcode/name/alias catalog match overriding the complete AI estimate
* strong, clearly separated prefix/fuzzy candidate acceptance at the configured score and margin boundaries
* no-match, weak/ambiguous-match, incomplete-catalog, and unconvertible-catalog fallback to the scaled AI estimate
* foreign/deleted food exclusion from automatic AI matching
* no mixing of catalog and AI nutrients, and no catalog mutation from AI estimates
* preservation/removal/conversion-to-`USER_FOOD` semantics for existing `AI_ESTIMATE` items in full-content replacement
* AI message idempotency, current-state capture replay, and stale-processing recovery on the originally persisted raw input
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
* personal-food CRUD/search endpoints remain manual, private, and side-effect independent from Daily and AI
* personal-food nutrients preserve unknown values as null and use decimal-safe types
* personal-food search remains user-scoped, deterministic, and backed by the V4 PostgreSQL indexes
* Daily linked-food captures preserve an immutable nutrition snapshot rather than reading mutable catalog values as history
* capture APIs and JSONB use schema v2 only, with one atomic full-replacement PUT and no granular or v1 compatibility routes
* new AI food captures use schema v2 and preserve either an immutable `USER_FOOD` snapshot or a complete `AI_ESTIMATE`
* AI catalog resolution remains user-scoped, exact-first, conservative for prefix/fuzzy candidates, nutritionally complete, and deterministically convertible
* AI fallback estimates remain confirmable Daily state and never mutate the personal-food catalog
* Telegram and mobile flows still use shared backend services
* daily finalization remains deterministic, aggregates every supported metric, and refinalization updates the reopened snapshot without duplication
