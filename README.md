# FitLake API

FitLake is a Spring Boot/Kotlin modular monolith for personal daily tracking. The current implementation contains the database foundation and Firebase Authentication boundary; Daily, Telegram, and AI use cases are not implemented yet.

## Requirements

- JDK 25
- PostgreSQL 16 or compatible
- A Firebase project with Firebase Authentication enabled
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

Flyway runs automatically at application startup. `V1__initialize_fitlake_daily_schema.sql` creates the seven original Daily tables, and `V2__add_firebase_auth_identity.sql` adds `user_auth_identity` and makes `user_account.email` non-unique. Hibernate only validates the migrated schema (`ddl-auto=validate`).

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
$env:SPRING_AI_OPENAI_BASE_URL='https://openrouter.ai/api/v1'
$env:SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL='your-openrouter-model'
```

`OPENAI_API_KEY` is the Spring AI OpenAI-compatible provider credential. It may be an OpenRouter key when the base URL points to OpenRouter; it does not have to be issued by OpenAI. AI is not invoked by the authentication flow.

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

The suite includes domain and provisioning tests, filter and MVC security tests with a fake token verifier, and PostgreSQL/Flyway persistence tests through Testcontainers. No real Firebase credentials are used in tests. PostgreSQL integration tests are skipped when Docker is unavailable.

## Current deliberate limits

- Token revocation is not checked against Firebase on every request; normal Firebase Admin ID-token verification is performed.
- One Firebase issuer may map to only one identity per internal user.
- A Firebase email claim is stored even when unverified and is synchronized when it changes on later logins. A missing claim does not erase the stored value, and email is never used for account resolution or merging.
- Display name seeds the profile on first login but later token changes do not overwrite user-managed profile data.
- Account linking, account deletion, authorization roles, and Daily/Telegram/AI feature flows remain future work.
