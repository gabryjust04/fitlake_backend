# FitLake API

FitLake is a Spring Boot/Kotlin modular monolith for personal daily tracking. The current implementation contains Firebase Authentication, the authenticated Daily REST slice, and standalone natural-language insertion through Spring AI tool calling. Telegram is not implemented yet.

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

Flyway runs automatically at application startup. `V1__initialize_fitlake_daily_schema.sql` creates the seven original Daily tables, `V2__add_firebase_auth_identity.sql` adds `user_auth_identity`, and `V3__add_daily_ai_message_audit.sql` adds the AI reprocess link, processing lease, and database idempotency constraints. Hibernate only validates the migrated schema (`ddl-auto=validate`).

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

The suite includes Daily domain/use-case tests, offline Spring AI tool-calling tests, authenticated REST tests, filter and MVC security tests with a fake token verifier, and PostgreSQL/Flyway/JSONB persistence, rollback, and concurrency tests through Testcontainers. No real Firebase or AI credentials and no provider network calls are used. PostgreSQL integration tests are skipped when Docker is unavailable.

## Current deliberate limits

- Token revocation is not checked against Firebase on every request; normal Firebase Admin ID-token verification is performed.
- One Firebase issuer may map to only one identity per internal user.
- A Firebase email claim is stored even when unverified and is synchronized when it changes on later logins. A missing claim does not erase the stored value, and email is never used for account resolution or merging.
- Display name seeds the profile on first login but later token changes do not overwrite user-managed profile data.
- Account linking, account deletion, authorization roles, Telegram, conversational memory, relative AI edits, and AI-driven finalization remain future work.
