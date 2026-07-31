# FitLake API

FitLake is a Spring Boot/Kotlin modular monolith for personal daily tracking. The current implementation contains Firebase Authentication, the authenticated Daily REST slice, standalone natural-language insertion through Spring AI tool calling, and a private user-managed food catalog. Telegram is not implemented yet.

## Requirements

- JDK 25
- PostgreSQL 16 or compatible
- A Firebase project with Firebase Authentication enabled
- An OpenAI-compatible chat model with tool/function-calling support for AI insertion
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

Flyway runs automatically at application startup. `V1__initialize_fitlake_daily_schema.sql` creates the seven original Daily tables, `V2__add_firebase_auth_identity.sql` adds `user_auth_identity`, `V3__add_daily_ai_message_audit.sql` adds the AI reprocess link, processing lease, and database idempotency constraints, and `V4__add_private_user_food_catalog.sql` adds `user_food`, `user_food_alias`, and their search indexes. Hibernate only validates the migrated schema (`ddl-auto=validate`).

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
```

`OPENAI_API_KEY` is the Spring AI OpenAI-compatible provider credential. It may be an OpenRouter key when the base URL points to OpenRouter; it does not have to be issued by OpenAI. The selected model must support tool calling. `SPRING_AI_OPENAI_CHAT_MAX_TOKENS` caps each structured response and defaults to `4096`, preventing providers from reserving the model's much larger maximum output context. AI is disabled by default (`SPRING_AI_MODEL_CHAT=none`), so the rest of the API can start without an AI key; AI endpoints then return a safe `503`.

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

Nutrients use decimal values and may include calories, protein, carbohydrates, fat, fiber, sugars, saturated fat, sodium, and salt. A `null` nutrient means **unknown**, never zero. Source types are `USER_ENTERED`, `PRODUCT_LABEL`, `EXTERNAL_DATABASE`, `AI_ESTIMATE`, and `IMPORTED`; these values are metadata only and do not trigger an external provider or AI call.

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

The catalog is deliberately not connected to Daily yet. When that integration is added, each consumed item must store both a `userFoodId` reference and an immutable snapshot of the food name, consumed amount/unit, nutrition values, and source used at that time. Later edits or deletion of the catalog definition must never rewrite historical Daily captures.

## Daily REST API

All Daily routes require the same Firebase bearer token used by `/api/me`.

| Method | Route | Purpose |
| --- | --- | --- |
| `POST` | `/api/daily/days/{date}/captures` | Create a manual `OPEN` capture and create the day if needed |
| `POST` | `/api/daily/days/{date}/messages` | Interpret one complete standalone message through AI |
| `GET` | `/api/daily/days/{date}` | Read the day, all captures, and finalized metrics when present |
| `POST` | `/api/daily/captures/{captureId}/accept` | Accept an open capture |
| `POST` | `/api/daily/captures/{captureId}/reject` | Reject an open capture |
| `PUT` | `/api/daily/captures/{captureId}` | Replace an open or accepted capture payload |
| `PATCH` | `/api/daily/captures/{captureId}/food-items/{itemTempId}` | Change a food item's quantity and unit |
| `DELETE` | `/api/daily/captures/{captureId}` | Soft delete a capture without removing its database row |
| `POST` | `/api/daily/captures/{captureId}/reprocess` | Reinterpret complete replacement text for an `OPEN` proposal |
| `POST` | `/api/daily/days/{date}/finalize` | Build metrics from accepted captures and confirm the day |
| `GET` | `/api/daily/days/{date}/metrics` | Read the finalized metrics snapshot |

Dates use ISO format: `YYYY-MM-DD`.

Example manual daily-fields capture:

```http
POST /api/daily/days/2026-07-28/captures
Authorization: Bearer <FIREBASE_ID_TOKEN>
Content-Type: application/json
```

```json
{
  "type": "DAILY_FIELDS",
  "fields": {
    "bodyWeightKg": 78.4,
    "sleepHours": 7.5,
    "stepsCount": 8500,
    "hydrationLiters": 2.2,
    "moodLevel": 8
  }
}
```

Example food capture:

```json
{
  "type": "FOOD",
  "meals": [
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
        }
      ]
    }
  ]
}
```

`mealTempId` and `itemTempId` may be supplied by the client for deterministic UI references; when omitted, the backend generates them. Supported units are `g`, `kg`, `ml`, `l`, `unit`, and `portion`, with common Italian aliases normalized automatically.

Every manual capture starts as `OPEN`. It must be accepted or rejected before finalization. An `OPEN` capture causes finalization to return `409 Conflict`. Only `ACCEPTED` captures contribute to metrics; rejected, expired, and soft-deleted captures remain stored but are excluded.

For repeated scalar fields such as body weight or sleep, the last non-null value in deterministic capture creation order wins. Food meals are concatenated, and provided calories/macros are summed. Calling finalization more than once returns the existing snapshot without duplicating it.

## Daily AI insertion

The AI endpoint receives text, not the structured manual-capture JSON. The model must choose exactly one terminal tool:

```text
createCapture
askClarification
noOp
```

Only `createCapture` reaches the normal application capture use case. The authenticated user, date, day, IDs, ownership, `OPEN` status, and timestamps always come from the backend. The model cannot call repositories or write to PostgreSQL. A versioned prompt lives at `src/main/resources/prompts/daily-capture-v1.txt`.

Every request requires a client-generated `Idempotency-Key` header, unique for that complete operation. Retrying the same key with the same normalized text replays the original terminal result without another model call or duplicate capture. Reusing the key for different text returns `409`. An interrupted `PROCESSING` event can be recovered after its five-minute processing lease expires; a per-attempt fencing token prevents the expired worker from committing afterward.

Create an AI proposal:

```powershell
curl.exe -X POST "http://localhost:8080/api/daily/days/2026-07-30/messages" `
  -H "Authorization: Bearer <FIREBASE_ID_TOKEN>" `
  -H "Idempotency-Key: 0194-message-breakfast-1" `
  -H "Content-Type: application/json" `
  -d '{"text":"A colazione ho mangiato uno yogurt, una banana e 40 grammi di cereali"}'
```

A successful `createCapture` returns `201` and an `OPEN` capture. Abbreviated response:

```json
{
  "outcome": "CAPTURE_CREATED",
  "replacedCaptureId": null,
  "capture": {
    "captureId": "72e31175-a346-45a1-b016-366861cfcb4d",
    "dayId": "9183188b-8496-4a26-b79f-55f242640cca",
    "date": "2026-07-30",
    "type": "FOOD",
    "status": "OPEN",
    "createdBy": "AI",
    "payload": {
      "type": "FOOD",
      "meals": [],
      "fields": {},
      "note": null
    }
  }
}
```

`askClarification` and `noOp` return `200` and create no capture. Provider output with zero tools, multiple tools, free text beside a tool, invalid arguments, or an unsupported payload is rejected defensively.

To change the original text, the client sends the complete corrected sentence rather than a relative command:

```powershell
curl.exe -X POST "http://localhost:8080/api/daily/captures/<OPEN_CAPTURE_ID>/reprocess" `
  -H "Authorization: Bearer <FIREBASE_ID_TOKEN>" `
  -H "Idempotency-Key: 0194-reprocess-breakfast-1" `
  -H "Content-Type: application/json" `
  -d '{"text":"A colazione ho mangiato yogurt, cereali e tre biscotti"}'
```

On success, reprocess atomically creates a new `OPEN` capture and marks the old one `REJECTED` by `SYSTEM`; the inbox event records `replaces_capture_id`. If AI, validation, or persistence fails—or the outcome is clarification/no-op—the old capture remains `OPEN` and unchanged. Only owned, non-deleted `OPEN` captures on editable days can be reprocessed.

There is no conversational memory: each new message is autonomous, and each reprocess interprets only the full text in its latest request. The original text and a sanitized terminal audit are stored, but provider raw responses, prompts, API keys, Firebase tokens, and credentials are not.

### Firebase authentication diagnostics

At startup the application logs whether Application Default Credentials were loaded and which Firebase project ID was configured. Authentication failures log the Firebase error code and a sanitized reason, while token contents, emails, and service-account data remain excluded.

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

The suite includes Daily domain/use-case tests, offline Spring AI tool-calling tests, authenticated REST tests, private-food domain/CRUD/isolation/search tests, filter and MVC security tests with a fake token verifier, and PostgreSQL/Flyway/JSONB/`pg_trgm` persistence, rollback, and concurrency tests through Testcontainers. No real Firebase or AI credentials and no provider network calls are used. PostgreSQL integration tests are skipped when Docker is unavailable.

## Current deliberate limits

- Token revocation is not checked against Firebase on every request; normal Firebase Admin ID-token verification is performed.
- One Firebase issuer may map to only one identity per internal user.
- A Firebase email claim is stored even when unverified and is synchronized when it changes on later logins. A missing claim does not erase the stored value, and email is never used for account resolution or merging.
- Display name seeds the profile on first login but later token changes do not overwrite user-managed profile data.
- Personal foods are manually managed and private; automatic creation, barcode lookup, external nutrition lookup, and integration with AI or Daily captures are not implemented.
- Soft-deleted personal foods cannot currently be restored through the API.
- Account linking, account deletion, authorization roles, Telegram, conversational memory, relative AI edits, and AI-driven finalization remain future work.
