# FitLake API

FitLake is a Spring Boot/Kotlin modular monolith for personal daily tracking. The current implementation contains Firebase Authentication, the authenticated Daily REST slice, standalone natural-language insertion through a pure Spring AI structured interpreter, and a private user-managed food catalog. Telegram is not implemented yet.

## Requirements

- JDK 25
- PostgreSQL 16 or compatible
- A Firebase project with Firebase Authentication enabled
- An OpenAI-compatible chat model that can reliably return JSON for AI insertion; native JSON Schema response-format support is optional
- Docker only if you want to run PostgreSQL locally or execute the Testcontainers integration tests

The application does not start Docker or Docker Compose automatically.

## Architecture of authentication

Firebase proves the external identity. FitLake creates and owns a separate internal UUID:

```text
Firebase ID token
→ Firebase Admin SDK verification
→ user_auth_identity lookup by (issuer, subject)
→ first-login provisioning when needed
→ internal user_account.user_id
→ CurrentUserProvider
→ application use case
```

Domain ownership and foreign keys must always use the internal `UserId`. Email is profile data, is not unique, and is never used to merge or resolve authenticated users.

The API is stateless:

- `GET /actuator/health` is public.
- Swagger UI and the OpenAPI document are public.
- `/api/**` requires a valid Firebase ID token.
- Form login, HTTP Basic, and server sessions are disabled.
- Authentication failures return a small JSON response instead of an HTML login page.

## PostgreSQL

If PostgreSQL already runs in Docker with port `5432` published to the host, an application running directly on Windows can use `localhost`:

```text
DATABASE_URL=jdbc:postgresql://localhost:5432/fitlake
```

To create a disposable local PostgreSQL container:

```powershell
docker run --name fitlake-postgres `
  -e POSTGRES_DB=fitlake `
  -e POSTGRES_USER=postgres `
  -e POSTGRES_PASSWORD=change-me `
  -p 5432:5432 `
  -d postgres:16-alpine
```

Flyway runs automatically at application startup. `V1__initialize_fitlake_daily_schema.sql` creates the seven original Daily tables, `V2__add_firebase_auth_identity.sql` adds `user_auth_identity`, `V3__add_daily_ai_message_audit.sql` adds the AI reprocess link, processing lease, and database idempotency constraints, `V4__add_private_user_food_catalog.sql` adds `user_food`, `user_food_alias`, and their search indexes, `V5__add_daily_capture_content_audit.sql` adds owner-safe capture audit rows and changes finalized calories to `NUMERIC(18,6)`, `V6__expand_daily_capture_lifecycle_audit.sql` updates AI terminal statuses and records capture creation, state transitions, deletion, and reprocess replacement, and `V7__enforce_daily_tenant_references.sql` adds composite owner foreign keys across days, inbox events, captures, AI logs, and metrics. Hibernate only validates the migrated schema (`ddl-auto=validate`).

V4 enables PostgreSQL's `pg_trgm` extension with `CREATE EXTENSION IF NOT EXISTS pg_trgm`. The PostgreSQL installation must provide that extension and the migration role must be allowed to enable it. The official PostgreSQL Docker images used by local development and Testcontainers include it.

## Firebase setup

1. Create or select a project in the [Firebase console](https://console.firebase.google.com/).
2. Enable the desired sign-in provider under Authentication.
3. In project settings, generate a service-account key for local backend development, or configure another [Application Default Credentials](https://firebase.google.com/docs/admin/setup#initialize_the_sdk_in_non-google_environments) source.
4. Store the JSON outside this repository.
5. Set `FIREBASE_PROJECT_ID` and point `GOOGLE_APPLICATION_CREDENTIALS` to the absolute JSON path.

The service-account JSON, Firebase ID tokens, refresh tokens, and API keys must never be committed or logged. Credential-shaped JSON filenames are ignored by Git as an additional safeguard.

The backend expects a Firebase **ID token issued to the signed-in client**, not a Firebase web API key and not the service-account JSON. After a client signs in, obtain it from the authenticated Firebase user with the client SDK's `getIdToken(false)` equivalent, then send the returned token as the bearer value. See Firebase's [session-management documentation](https://firebase.google.com/docs/auth/admin/manage-sessions#retrieve_id_tokens_on_clients) for platform-specific examples.

## Environment variables

`.env.example` documents the available variables, but Spring Boot does not load `.env` files automatically. For a PowerShell session:

```powershell
$env:SPRING_PROFILES_ACTIVE='dev'

$env:DATABASE_URL='jdbc:postgresql://localhost:5432/fitlake'
$env:DATABASE_USERNAME='postgres'
$env:DATABASE_PASSWORD='change-me'

$env:FIREBASE_PROJECT_ID='your-firebase-project-id'
$env:GOOGLE_APPLICATION_CREDENTIALS='C:\secure\fitlake-firebase-service-account.json'
$env:FITLAKE_DEFAULT_TIMEZONE='Europe/Rome'

$env:OPENAI_API_KEY='your-provider-key'
$env:SPRING_AI_MODEL_CHAT='openai'
$env:SPRING_AI_OPENAI_BASE_URL='https://openrouter.ai/api/v1'
$env:SPRING_AI_OPENAI_CHAT_MODEL='your-openrouter-model'
$env:SPRING_AI_OPENAI_CHAT_MAX_TOKENS='4096'
$env:FITLAKE_DAILY_AI_MAX_TEXT_LENGTH='4000'
$env:FITLAKE_DAILY_AI_MAX_STRUCTURED_OUTPUT_RETRIES='1'
$env:FITLAKE_DAILY_AI_NATIVE_STRUCTURED_OUTPUT_ENABLED='false'
$env:FITLAKE_DAILY_AI_FOOD_MATCH_MINIMUM_SCORE='0.78'
$env:FITLAKE_DAILY_AI_FOOD_MATCH_MINIMUM_MARGIN='0.12'
```

`OPENAI_API_KEY` is the Spring AI OpenAI-compatible provider credential. It may be an OpenRouter key when the base URL points to OpenRouter; it does not have to be issued by OpenAI. Tool calling is not used. `SPRING_AI_OPENAI_CHAT_MAX_TOKENS` caps each response at `4096` by default. Invalid structured output is retried once by default; the configured retry count must be between `0` and `3`.

The v3 response schema is always included in the system prompt and every response is parsed and validated by the backend. `FITLAKE_DAILY_AI_NATIVE_STRUCTURED_OUTPUT_ENABLED=true` additionally sends the provider-native `response_format=json_schema`; leave it `false` for the currently used OpenRouter model, which does not support that option, and enable it only when the selected provider/model explicitly advertises support. The food-match score and margin settings govern conservative automatic prefix/fuzzy matches. AI is disabled by default (`SPRING_AI_MODEL_CHAT=none`), so the rest of the API can start without an AI key; AI endpoints then return a safe `503`.

## Run

```powershell
$env:JAVA_HOME='C:\path\to\jdk-25'
.\gradlew.bat bootRun
```

On the first request carrying a new valid `(issuer, subject)`, FitLake atomically creates one `user_account` and one `user_auth_identity`. Repeated requests reuse that internal user and update `last_login_at`.

Check the current profile:

```powershell
curl.exe -H "Authorization: Bearer <FIREBASE_ID_TOKEN>" http://localhost:8080/api/me
```

Example response:

```json
{
  "userId": "4ff558f0-bc8d-49fe-a54e-57270f7c7617",
  "email": "user@example.com",
  "displayName": "Andrea",
  "timezone": "Europe/Rome"
}
```

Health check without authentication:

```powershell
curl.exe http://localhost:8080/actuator/health
```

## Swagger / OpenAPI

With the application running, open:

```text
http://localhost:8080/swagger-ui.html
```

The raw OpenAPI document is available at:

```text
http://localhost:8080/v3/api-docs
```

To call protected endpoints from Swagger UI, click **Authorize** and paste the Firebase ID token. Swagger adds the `Bearer` prefix automatically, so paste only the token itself.

## Private food catalog

The food catalog stores reusable nutrition definitions created manually by the authenticated user. It is private and tenant-scoped: the backend obtains the owner from `CurrentUserProvider`, never from request data, and foreign or deleted foods behave as not found. Catalog operations do not call AI, do not create inbox/audit records, and do not create or modify Daily captures.

| Method | Route | Purpose |
| --- | --- | --- |
| `POST` | `/api/me/foods` | Create a private food definition |
| `GET` | `/api/me/foods?page=0&size=20&sort=NAME_ASC` | List active foods with pagination |
| `GET` | `/api/me/foods/search?query=my%20yogurt&limit=10` | Search active foods with deterministic ranking |
| `GET` | `/api/me/foods/{foodId}` | Read one owned active food |
| `PATCH` | `/api/me/foods/{foodId}` | Replace the complete editable definition |
| `DELETE` | `/api/me/foods/{foodId}` | Soft-delete a food and its active aliases from normal access |

List size is limited to `1..100`; available sorts are `NAME_ASC`, `CREATED_AT_DESC`, and `UPDATED_AT_DESC`. Search accepts `2..200` searchable characters and a result limit of `1..50`.

Supported units are `GRAM`, `KILOGRAM`, `MILLILITER`, `LITER`, `PIECE`, and `SERVING`. Nutrition is always expressed for an explicit `nutritionBasis`. An optional `defaultServing` is separate from that basis. Cross-category conversions are allowed only when the matching positive conversion is explicitly supplied through `gramsPerPiece`, `millilitersPerPiece`, `gramsPerServing`, or `millilitersPerServing`; no arbitrary conversion is inferred.

Nutrients use decimal values and may include calories, protein, carbohydrates, fat, fiber, sugars, saturated fat, sodium, and salt. A `null` nutrient means **unknown**, never zero. Catalog source types are `USER_ENTERED`, `PRODUCT_LABEL`, `EXTERNAL_DATABASE`, `AI_ESTIMATE`, and `IMPORTED`; these values are metadata only and do not trigger an external provider or AI call. A catalog definition whose provenance is `AI_ESTIMATE` is distinct from a Daily capture item whose runtime `sourceType` is `AI_ESTIMATE`.

Create a product-label food expressed per 100 grams with a 170-gram default serving:

```powershell
curl.exe -X POST "http://localhost:8080/api/me/foods" `
  -H "Authorization: Bearer <FIREBASE_ID_TOKEN>" `
  -H "Content-Type: application/json" `
  -d '{
    "name":"My usual Greek yogurt",
    "brand":"Example Brand",
    "barcode":"1234567890123",
    "description":"Copied from the breakfast yogurt label",
    "aliases":["my yogurt","usual yogurt"],
    "nutritionBasis":{"amount":100,"unit":"GRAM"},
    "nutrients":{
      "caloriesKcal":62,
      "proteinGrams":9.5,
      "carbohydratesGrams":4.1,
      "fatGrams":0.2,
      "fiberGrams":null,
      "sugarsGrams":4.1,
      "saturatedFatGrams":0.1,
      "sodiumMilligrams":40,
      "saltGrams":null
    },
    "defaultServing":{"amount":170,"unit":"GRAM"},
    "conversions":{},
    "source":{"type":"PRODUCT_LABEL","notes":"Copied manually from the package label"}
  }'
```

Nutrition per piece is represented directly, for example:

```json
{
  "name": "Homemade biscuit",
  "aliases": ["my biscuit"],
  "nutritionBasis": {"amount": 1, "unit": "PIECE"},
  "nutrients": {
    "caloriesKcal": 42,
    "proteinGrams": 1.1,
    "carbohydratesGrams": 6.8,
    "fatGrams": 1.4
  },
  "defaultServing": {"amount": 2, "unit": "PIECE"},
  "source": {"type": "USER_ENTERED"}
}
```

`PATCH` intentionally uses full replacement semantics: send the same complete editable shape accepted by `POST`. Omitted optional fields become `null` or their documented empty default, and the supplied `aliases` array replaces every active alias. IDs, owner, normalized fields, timestamps, deletion state, and version are backend-owned. For example, replacing the aliases requires resending the nutrition definition:

```http
PATCH /api/me/foods/2db702d6-aeeb-46be-b863-72d552de63ab
Authorization: Bearer <FIREBASE_ID_TOKEN>
Content-Type: application/json
```

```json
{
  "name": "My usual Greek yogurt",
  "aliases": ["my yogurt", "breakfast yogurt"],
  "nutritionBasis": {"amount": 100, "unit": "GRAM"},
  "nutrients": {"caloriesKcal": 62, "proteinGrams": 9.5, "carbohydratesGrams": 4.1, "fatGrams": 0.2},
  "defaultServing": {"amount": 170, "unit": "GRAM"},
  "source": {"type": "PRODUCT_LABEL"}
}
```

Search normalizes case, whitespace, punctuation, Unicode, and accents in the backend. Results are deduplicated per food and ordered by this fixed priority:

```text
EXACT_BARCODE
> EXACT_ALIAS
> EXACT_NAME
> PREFIX_ALIAS
> PREFIX_NAME
> FUZZY_ALIAS
> FUZZY_NAME
```

Prefix matching uses normalized indexed fields. Typo matching uses `pg_trgm` with a `0.30` similarity threshold and starts for normalized queries of at least three characters. Equal candidates are ordered deterministically by score, normalized name, and food ID. The response exposes `matchedBy`, `matchedText`, and `score`; this is conventional PostgreSQL search, not RAG, embeddings, semantic search, or an LLM call.

```powershell
curl.exe "http://localhost:8080/api/me/foods/search?query=my%20yogurth&limit=10" `
  -H "Authorization: Bearer <FIREBASE_ID_TOKEN>"

curl.exe -X DELETE "http://localhost:8080/api/me/foods/<FOOD_ID>" `
  -H "Authorization: Bearer <FIREBASE_ID_TOKEN>"
```

Deletion sets `deleted_at` and returns `204`; the food and its aliases then disappear from direct reads, normal lists, and search. There is no restore endpoint in this MVP. Active barcodes and normalized aliases are unique per user, while the same values remain valid for different users and can be reused after soft deletion.

Invalid definitions and query parameters return `400`, inaccessible foods return `404`, and active barcode or alias conflicts return `409`, using the API's standard safe error body.

The catalog participates in two read-only Daily paths. Manual typed input uses the exact, ownership-scoped `userFoodId` selected by the frontend; it never matches by name. Natural-language AI insertion submits the extracted `searchText` to the same user-scoped ranked catalog search used by the UI, then applies a stricter policy: one exact match wins; otherwise a prefix/fuzzy candidate is accepted only when its score meets the configured minimum and, when another candidate exists, its lead does too. Approximate matching is also rejected when the bounded 50-candidate window may be truncated, because the backend cannot prove the required margin. Weak, tied, truncated, foreign, or deleted candidates are never accepted. Neither path creates or updates catalog definitions.

Each matched consumed item stores its `userFoodId` and an immutable snapshot of the food definition used for calculation. Later edits or soft deletion of the reusable catalog definition never rewrite or break an existing capture.

## Daily REST API

All Daily routes require the same Firebase bearer token used by `/api/me`.

| Method | Route | Purpose |
| --- | --- | --- |
| `POST` | `/api/daily/days/{date}/captures` | Create a manual `OPEN` capture and create the day if needed |
| `POST` | `/api/daily/days/{date}/messages` | Interpret one complete standalone message through AI |
| `GET` | `/api/daily/days/{date}` | Read the day, all captures, and finalized metrics when present |
| `GET` | `/api/daily/days/{date}/captures` | List all captures for one owned day |
| `GET` | `/api/daily/captures/{captureId}` | Read one owned capture |
| `POST` | `/api/daily/captures/{captureId}/accept` | Accept an open capture |
| `POST` | `/api/daily/captures/{captureId}/reject` | Reject an open capture |
| `PUT` | `/api/daily/captures/{captureId}` | Atomically replace the complete v2 capture using optimistic locking |
| `DELETE` | `/api/daily/captures/{captureId}` | Soft delete a capture without removing its database row |
| `POST` | `/api/daily/captures/{captureId}/reprocess` | Reinterpret complete replacement text for an `OPEN` proposal |
| `POST` | `/api/daily/days/{date}/finalize` | Build metrics from accepted captures and confirm the day |
| `POST` | `/api/daily/days/{date}/reopen` | Reopen a confirmed day so it can be changed and recalculated |
| `GET` | `/api/daily/days/{date}/metrics` | Read the finalized metrics snapshot |

Dates use ISO format: `YYYY-MM-DD`.

### Manual Daily captures linked to personal foods

The frontend workflow is deterministic and does not invoke AI:

```text
GET /api/me/foods/search?query=...
→ user selects one exact userFoodId
→ POST or PUT the complete v2 Daily capture
→ backend loads that active food for the authenticated internal UserId
→ backend converts units, calculates nutrients, and stores a snapshot
```

One manual submission creates one capture. A `FOOD` entry can contain multiple selected foods, and the same capture can also contain scalar entries such as `WEIGHT`, `HYDRATION`, or `SLEEP`. Food-only content is classified as `FOOD`, scalar-only content as `DAILY_FIELDS`, food plus scalar fields as `MIXED`, and note-only content as `NOTE`.

Create one mixed capture using an exact personal-food ID and a saved default serving:

```powershell
curl.exe -X POST "http://localhost:8080/api/daily/days/2026-07-31/captures" `
  -H "Authorization: Bearer <FIREBASE_ID_TOKEN>" `
  -H "Content-Type: application/json" `
  -d '{
    "entries":[
      {
        "type":"FOOD",
        "mealType":"DINNER",
        "items":[{
          "sourceType":"USER_FOOD",
          "userFoodId":"2db702d6-aeeb-46be-b863-72d552de63ab",
          "quantity":{"amount":1,"unit":"DEFAULT_SERVING"}
        }]
      },
      {"type":"WEIGHT","value":78,"unit":"KILOGRAM"},
      {"type":"HYDRATION","value":750,"unit":"MILLILITER"}
    ]
  }'
```

Food quantities support `GRAM`, `KILOGRAM`, `MILLILITER`, `LITER`, `PIECE`, `SERVING`, and `DEFAULT_SERVING`. Kilograms/grams and liters/milliliters convert directly. Piece or serving conversion is allowed only through the selected food's explicit `gramsPerPiece`, `millilitersPerPiece`, `gramsPerServing`, or `millilitersPerServing`. `DEFAULT_SERVING` requires a saved default serving and accepts a positive multiplier. Mass is never guessed from volume, and missing conversion metadata returns `400`.

Calculations use `BigDecimal`, internal `DECIMAL128` precision, and persist item results at scale 6 with `HALF_UP`. A missing nutrient remains `null`; it is not converted to zero. Responses expose the entered quantity, canonical resolved quantity, basis, all nutrients per basis, conversion/default-serving metadata, nutrition source, catalog version/update time, calculated item values, and entry totals.

All manual and AI capture payloads use `schemaVersion: 2` and authoritative typed `entries`. Payloads without `schemaVersion`, version 1, and unknown future versions are rejected. FitLake is still in development, so there is no v1 compatibility layer or destructive data migration: an existing development database containing v1 `daily_capture.payload` or v1 AI audit snapshots must be recreated before using this version.

Full replacement requires the currently returned capture version:

```powershell
curl.exe -X PUT "http://localhost:8080/api/daily/captures/<CAPTURE_ID>" `
  -H "Authorization: Bearer <FIREBASE_ID_TOKEN>" `
  -H "X-Request-ID: mobile-edit-0194" `
  -H "Content-Type: application/json" `
  -d '{
    "version":0,
    "entries":[{
      "entryId":"<EXISTING_ENTRY_UUID>",
      "type":"FOOD",
      "mealType":"DINNER",
      "items":[{
        "itemId":"<EXISTING_ITEM_UUID>",
        "sourceType":"USER_FOOD",
        "userFoodId":"2db702d6-aeeb-46be-b863-72d552de63ab",
        "quantity":{"amount":200,"unit":"GRAM"}
      }]
    }]
  }'
```

The `entries` array is the entire new editable content: omitted entries and items are removed. Existing entry/item UUIDs must already belong to this target capture; omitted UUIDs are generated by the backend. An unchanged linked item preserves its original snapshot. A quantity-only edit recalculates from that same snapshot, even if the catalog food has since changed or been deleted. A new item or changed `userFoodId` loads the current active owned definition and creates a new snapshot; a deleted/foreign food is ownership-safely `404`.

An existing `AI_ESTIMATE` item has stricter replacement rules. The full `PUT` may preserve it unchanged by resending its existing item ID, `AI_ESTIMATE` source, and original quantity; remove it by omission; or replace it with an exact active `USER_FOOD` reference while preserving the logical item ID. That last operation loads the currently owned catalog definition, calculates authoritative nutrition, and creates a new snapshot. The client cannot create a new `AI_ESTIMATE` or directly change its estimated quantity or nutrition. Complete-text AI reprocess remains available when the natural-language interpretation itself must be regenerated.

`PUT /api/daily/captures/{captureId}` is the only capture-update endpoint. It uses one transaction for validation, scoped food resolution, calculation, payload persistence, version increment, and a `UI_EDIT` audit row containing old/new payloads and versions. A stale version returns `409` and changes nothing. `OPEN` remains `OPEN`; `ACCEPTED` remains `ACCEPTED`. Rejected, soft-deleted, expired, and captures on a `CONFIRMED` day cannot be edited. After a day is `REOPENED`, its accepted captures are editable and the next finalization recalculates the metrics snapshot.

Example manual daily-fields capture:

```http
POST /api/daily/days/2026-07-28/captures
Authorization: Bearer <FIREBASE_ID_TOKEN>
Content-Type: application/json
```

```json
{
  "entries": [
    {"type":"WEIGHT","value":78.4,"unit":"KILOGRAM"},
    {"type":"SLEEP","value":7.5,"unit":"HOUR"},
    {"type":"STEPS","value":8500,"unit":"COUNT"},
    {"type":"HYDRATION","value":2.2,"unit":"LITER"},
    {"type":"MOOD","value":8,"unit":"LEVEL"}
  ]
}
```

Entry and item UUIDs are generated by the backend when omitted. Existing IDs can only be reused when they already belong to the target capture.

Every manual capture starts as `OPEN`. It must be accepted or rejected before finalization. An `OPEN` capture causes finalization to return `409 Conflict`. Only `ACCEPTED` captures contribute to metrics; rejected, expired, and soft-deleted captures remain stored but are excluded.

Finalization reads every accepted capture. It concatenates food logs; sums calories, protein, carbohydrates, and fat with `BigDecimal`; and resolves body weight, sleep, steps, hydration, caffeine, mood, focus, stress, and daily notes using the last non-null value in deterministic capture creation order. If any food has an unknown value for a nutrient, that nutrient's daily total remains `null` rather than treating the unknown value as zero. Calling finalization again while the day is already `CONFIRMED` returns the existing snapshot without duplicating it.

`POST /api/daily/days/{date}/reopen` changes `CONFIRMED` to `REOPENED` and marks the existing metrics snapshot `REOPENED`, making its stale state explicit. Capture creation, confirmation, full replacement, and soft deletion are then available again. A new finalization still refuses unresolved `OPEN` captures; otherwise it rebuilds metrics from the current accepted captures, updates the same `daily_metrics` row, preserves its original `createdAt`, sets `recalculatedAt`, and returns both day and metrics to `CONFIRMED`.

## Daily AI insertion

The AI endpoint receives `{ "text": "..." }`, not manual-capture JSON. `CaptureInterpreterPort` is a pure outbound application port: it sends the standalone message to the configured model and returns a `DailyMessageInterpretation`. It has no repositories, catalog access, terminal service, persistence callback, or model-callable tools. The backend alone supplies the authenticated user, date, day, IDs, ownership, `OPEN` status, and timestamps. The active prompt is [`daily-capture-v3.txt`](src/main/resources/prompts/daily-capture-v3.txt).

The model returns exactly one structured interpretation with one semantic outcome:

- `COMPLETE`: at least one Daily fact was structured and no source fragment remains unresolved.
- `PARTIAL`: structured facts plus one or more unresolved fragments copied verbatim from the input. Each unresolved fragment becomes a `NOTE` entry in the same proposal.
- `UNRESOLVED`: the text is relevant but no fact can be safely structured. The backend preserves the complete original text as one `NOTE` entry.
- `NO_RELEVANT_DATA`: the text contains no Daily fact; the endpoint returns `200` without creating a capture.

There is no clarification outcome and no no-op tool. `COMPLETE`, `PARTIAL`, and `UNRESOLVED` all create an ordinary `OPEN` proposal that still requires user confirmation. Food and field `originalFragment` values, unresolved fragments, and Daily-note text must occur verbatim in the submitted text; unknown properties, model-generated backend fields, inconsistent outcomes, invalid ranges, and paraphrased fragments are rejected.

For every extracted food, the model must always return:

- `searchText` for backend catalog lookup;
- optional `statedQuantity` only when the user supplied it;
- mandatory `estimatedQuantity` for the complete consumed amount;
- mandatory `nutritionEstimate.basis` and non-negative calories, protein, carbohydrates, and fat for that explicit basis, plus any optional nutrients it can estimate.

The estimate is required even when a catalog match looks likely. `estimatedQuantity` must be directly scalable from the estimate basis; the backend performs the final decimal-safe scaling. Missing model nutrition is invalid output, not a reason to omit the food or return `UNRESOLVED`.

The backend then resolves each food independently:

1. Search only active foods owned by the authenticated user. One exact barcode/name/alias match has priority; multiple exact matches are ambiguous.
2. Without an exact match, accept only the strongest prefix/fuzzy result when its score is at least `FITLAKE_DAILY_AI_FOOD_MATCH_MINIMUM_SCORE` (default `0.78`) and, when a runner-up exists, its lead is at least `FITLAKE_DAILY_AI_FOOD_MATCH_MINIMUM_MARGIN` (default `0.12`).
3. Use an accepted catalog food only when all four core nutrients exist and the selected quantity converts deterministically through its immutable snapshot. Quantity priority is explicit user quantity, then the catalog's default serving, then the AI-estimated quantity.
4. If no candidate is safely accepted, or the accepted definition is nutritionally incomplete or unconvertible, use the AI estimate as `AI_ESTIMATE`. Prefer the explicit quantity when it scales from the AI basis; otherwise use `estimatedQuantity`.

A usable catalog definition becomes `USER_FOOD` and its complete calculated nutrition replaces the model estimate. The backend never mixes individual catalog and AI nutrients, never treats a missing catalog nutrient as zero, and never writes an estimate into the private catalog. An `AI_ESTIMATE` has no `userFoodId` or catalog snapshot and remains confirmable Daily state.

The schema is always supplied in the prompt and parsed strictly. When `FITLAKE_DAILY_AI_NATIVE_STRUCTURED_OUTPUT_ENABLED=true`, the adapter also requests the provider-native OpenAI-compatible JSON Schema response format. It defaults to `false`; keep it disabled for the current OpenRouter model because that model rejects native JSON Schema. Schema or semantic failures receive at most `FITLAKE_DAILY_AI_MAX_STRUCTURED_OUTPUT_RETRIES` corrective calls (default `1`, maximum `3`) before the API returns `502`.

Every request requires a client-generated `Idempotency-Key` header, unique for that complete operation. Retrying the same key with the same normalized text reloads the established capture in its current lifecycle state, or replays `NO_RELEVANT_DATA` or a sanitized failure, without another model call or duplicate capture. Reusing the key for different text returns `409`. An interrupted `PROCESSING` event can be recovered after its five-minute processing lease expires using the original raw input stored in the inbox event; a per-attempt fencing token prevents the expired worker from committing afterward.

Create an AI proposal:

```powershell
curl.exe -X POST "http://localhost:8080/api/daily/days/2026-07-30/messages" `
  -H "Authorization: Bearer <FIREBASE_ID_TOKEN>" `
  -H "Idempotency-Key: 0194-message-breakfast-1" `
  -H "Content-Type: application/json" `
  -d '{"text":"A colazione ho mangiato uno yogurt, una banana e 40 grammi di cereali"}'
```

A `COMPLETE`, `PARTIAL`, or `UNRESOLVED` interpretation returns `201` and an `OPEN` capture. Abbreviated response:

```json
{
  "outcome": "CAPTURE_CREATED",
  "replacedCaptureId": null,
  "interpretationOutcome": "COMPLETE",
  "capture": {
    "captureId": "72e31175-a346-45a1-b016-366861cfcb4d",
    "dayId": "9183188b-8496-4a26-b79f-55f242640cca",
    "date": "2026-07-30",
    "type": "FOOD",
    "status": "OPEN",
    "createdBy": "AI",
    "payload": {
      "schemaVersion": 2,
      "entries": [
        {
          "entryId": "3a3644fc-2c37-4578-bad0-c93722f47462",
          "type": "FOOD",
          "mealLabel": "colazione",
          "items": [
            {
              "itemId": "e78868dc-6cd0-4bc4-9e60-794889fc4227",
              "sourceType": "AI_ESTIMATE",
              "userFoodId": null,
              "displayName": "banana",
              "enteredQuantity": {"amount": 1, "unit": "PIECE"},
              "resolvedQuantity": {"amount": 1, "unit": "PIECE"},
              "calculatedNutrition": {
                "caloriesKcal": 105,
                "proteinGrams": 1.3,
                "carbohydratesGrams": 27,
                "fatGrams": 0.4
              }
            }
          ]
        }
      ]
    }
  }
}
```

Only `NO_RELEVANT_DATA` returns `200` without a capture. The adapter rejects tool calls, prose surrounding the JSON object, malformed JSON, unknown fields, invalid values, and semantic/schema violations.

To change the original text, the client sends the complete corrected sentence rather than a relative command:

```powershell
curl.exe -X POST "http://localhost:8080/api/daily/captures/<OPEN_CAPTURE_ID>/reprocess" `
  -H "Authorization: Bearer <FIREBASE_ID_TOKEN>" `
  -H "Idempotency-Key: 0194-reprocess-breakfast-1" `
  -H "Content-Type: application/json" `
  -d '{"text":"A colazione ho mangiato yogurt, cereali e tre biscotti"}'
```

On a `COMPLETE`, `PARTIAL`, or `UNRESOLVED` result, reprocess atomically creates a new `OPEN` capture and marks the old one `REJECTED` by `SYSTEM`; the inbox event records `replaces_capture_id`, and the lifecycle audit links the replacement capture. If AI, validation, or persistence fails—or the result is `NO_RELEVANT_DATA`—the old capture remains `OPEN` and unchanged. Only owned, non-deleted `OPEN` captures on editable days can be reprocessed.

There is no conversational memory: each new message is autonomous, and each reprocess interprets only the full text in its latest request. `daily_inbox_event` retains the original input. `ai_interpretation_log` stores only sanitized terminal metadata and projections such as semantic outcome, retry/token counts, capture summary, match type/score/reason, quantity source, and fallback counts; provider raw responses, prompts, API keys, Firebase tokens, credentials, and chain of thought are not stored. `daily_capture_audit` separately records AI creation and system replacement lifecycle events.

## Logging and operations

FitLake writes technical logs to stdout through SLF4J and Spring Boot's default Logback backend. Application events use stable `snake_case` names and structured key-value fields, so production collectors can query fields such as `event`, `outcome`, `requestId`, resource IDs, status, counts, and `durationMs` without parsing user content.

The available profiles are:

| Profile | Console format | Effective application level | Intended use |
| --- | --- | --- | --- |
| no explicit profile | Human-readable | `INFO` | Safe common fallback |
| `dev` | Human-readable | `DEBUG` for `com.fitlake` | Local diagnosis |
| `test` | Human-readable, reduced noise | `INFO` for `com.fitlake`, root `WARN` | Automated tests |
| `prod` | ECS structured JSON | `INFO` | Deployed runtime |

Gradle test tasks activate `test` automatically; an explicit `-Dspring.profiles.active=...` remains available for a deliberate override.

Select a profile before starting the process:

```powershell
$env:SPRING_PROFILES_ACTIVE='dev' # use 'prod' for ECS JSON
.\gradlew.bat bootRun
```

The pinned Spring Boot 4.1 version supports native structured logging, so `application-prod.properties` uses `logging.structured.format.console=ecs`; no extra JSON encoder or custom Logback file is required. Production logs remain on stdout:

```text
FitLake -> ECS JSON stdout -> container/platform collector -> centralized logging
```

Configure the deployment runtime to collect stdout/stderr and apply access controls, retention, search, and export there. FitLake does not write rolling application log files and does not connect business code directly to Elasticsearch, Loki, Datadog, or another log store. If the Spring Boot baseline is changed, verify native structured-logging compatibility before changing the encoder strategy.

### Request IDs

Clients may send `X-Request-Id` using 1 to 100 letters, digits, dots, underscores, or hyphens. A missing or invalid value is replaced with a generated UUID. The resolved value is:

- returned in every response as `X-Request-Id`;
- available to synchronous request processing through the SLF4J MDC key `requestId`;
- included in the safe `http_request_completed` event;
- cleared even if request processing throws, preventing leakage when servlet threads are reused.

HTTP completion logging records the method, resolved route template, status, outcome, and monotonic duration. It never logs raw URLs with identifiers, query strings, authorization data, request bodies, or response bodies. Health probes are kept below normal `INFO` volume.

FitLake currently has no application-owned asynchronous request handoff. The request-ID guarantee therefore covers the synchronous servlet chain. A future `@Async`, coroutine, scheduler, custom-executor, or reactive flow must add and test Spring-supported MDC/observation context propagation explicitly.

### Temporary DEBUG logging

Runtime logger mutation is deliberately unavailable: management exposure contains only `health`, `/actuator/health` is public, and the Actuator `loggers` endpoint is neither exposed nor authorized. Do not expose it, or `management.endpoints.web.exposure.include=*`, without operator authentication and a private or dedicated management boundary.

To diagnose one package, set a Spring Boot logging-level environment override and restart the application:

```powershell
$env:LOGGING_LEVEL_COM_FITLAKE_DAILY='DEBUG'
.\gradlew.bat bootRun
```

Restore the configured level after diagnosis and restart again:

```powershell
Remove-Item Env:LOGGING_LEVEL_COM_FITLAKE_DAILY
```

The same pattern works for another narrow package, such as `LOGGING_LEVEL_COM_FITLAKE_AUTH`. Avoid enabling root DEBUG or SQL bind-parameter logging in production.

### Privacy rules

Technical logs contain operational metadata only. At every level, including DEBUG and TRACE, do not log:

- Firebase tokens, authorization headers, cookies, passwords, API keys, credentials, complete JWT claims, email addresses, Firebase UIDs, or other direct identifiers;
- food names or consumed foods, nutrition values, weight, sleep, pain, mood, stress, focus, notes, free-form activity, Daily payloads, or full catalog definitions;
- user messages, prompts, completions, raw/structured provider output, hidden reasoning, or chain of thought;
- raw query strings, sensitive parameters, multipart data, or request/response bodies.

The common profile disables Spring AI's own log namespace and Hibernate JDBC bind/error/warning loggers because those libraries can serialize prompts, provider bodies, SQL values, or database details before FitLake can sanitize them. Spring MVC's raw unmatched-route logger is disabled, while Tomcat's HTTP parser and invalid-cookie loggers are kept at `WARN` to suppress request-target, header, and cookie dumps emitted at `INFO`. FitLake replaces these channels with stable application error codes, bounded metadata, sanitized stack traces, and the `http_request_completed` event; this trade-off is intentional and covered by configuration tests.

Keep `OPENAI_LOG=off`. The OpenAI Java SDK writes HTTP diagnostics directly to stderr outside SLF4J when this environment switch is enabled, including bodies at verbose levels. FitLake validates the setting at startup and fails fast for any non-`off` value.

Expected validation, missing-resource, state, idempotency, and version conflicts do not produce unexpected `ERROR` stack traces. An unexpected exception is logged once at the outer response boundary and the client receives only a safe error body. Technical logs do not replace durable `daily_capture_audit` history or sanitized `ai_interpretation_log` records; metrics and traces remain separate observability signals.

### Firebase authentication diagnostics

At startup the application emits sanitized configuration metadata indicating whether Firebase credentials and required configuration were resolved. Authentication failures use stable error codes and never include the provider exception message, token contents, claims, email, Firebase UID, project identifier, or service-account data.

For successful-verification diagnostics, enable debug logging in the run configuration:

```text
LOGGING_LEVEL_COM_FITLAKE_AUTH=DEBUG
```

Useful failure codes include `EXPIRED_ID_TOKEN`, `INVALID_ID_TOKEN`, `CERTIFICATE_FETCH_FAILED`, and `USER_DISABLED`.

## Tests

Run everything with:

```powershell
$env:JAVA_HOME='C:\path\to\jdk-25'
.\gradlew.bat test
```

Run the complete verification lifecycle with:

```powershell
.\gradlew.bat check
```

The suite includes request-correlation/MDC cleanup, structured event and exception-policy checks, Daily domain/use-case tests, offline Spring AI structured-output, correction-retry, verbatim-fragment, and nutrition-fallback tests, conservative exact/strong-fuzzy user-food matching tests, authenticated REST tests, private-food domain/CRUD/isolation/search tests, filter and MVC security tests with a fake token verifier, and PostgreSQL/Flyway/JSONB/`pg_trgm` persistence, tenant-FK rejection, rollback, and concurrency tests through Testcontainers. No real Firebase or AI credentials, provider network calls, or external logging platform are used. PostgreSQL integration tests are skipped when Docker is unavailable.

## Current deliberate limits

- Token revocation is not checked against Firebase on every request; normal Firebase Admin ID-token verification is performed.
- One Firebase issuer may map to only one identity per internal user.
- A Firebase email claim is stored even when unverified and is synchronized when it changes on later logins. A missing claim does not erase the stored value, and email is never used for account resolution or merging.
- Display name seeds the profile on first login but later token changes do not overwrite user-managed profile data.
- Personal foods remain manually managed and private; automatic creation, barcode lookup, and external nutrition lookup are not implemented. Manual Daily linking uses an exact `userFoodId`. Natural-language insertion may use one exact match or one sufficiently strong and clearly separated prefix/fuzzy match among the authenticated user's active foods; it never creates or updates catalog data.
- Soft-deleted personal foods cannot currently be restored through the API.
- Account linking, account deletion, authorization roles, Telegram, conversational memory, relative AI edits, and AI-driven finalization remain future work.
