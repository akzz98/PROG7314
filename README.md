# MuniPulse SA

Citizen Service Delivery and Community Incident Tracker for South African wards.

Residents report municipal faults with a photo and a GPS fix, upvote duplicates into a ward signal, and follow a resolution timeline published by field workers. This repository is the Part 2 prototype for PROG7314 / OPSC7312, student Alton Muganda (St10263456), solo.

## Stack

| Layer | Choice |
| --- | --- |
| Android | Kotlin, MVVM, Jetpack Compose, Coroutines, Retrofit 2 (from M4) |
| Identity | Firebase Authentication, Google SSO (from M2) |
| API | ASP.NET Core Web API (`net10.0`) |
| Database | MongoDB Atlas (from M1) |
| Photos in Part 2 | Multipart upload stored by the API (Azure Blob is Final POE) |

## Part 2 and Final POE

Part 2 includes Google SSO, Settings, the custom API and database, and custom features UD-1 (photo + GPS), UD-2 (upvotes and ward aggregation), and UD-3 (resolution timeline).

Final POE is not in this build: biometric unlock, Room offline sync, FCM, full English and isiZulu localisation, Azure Blob, the emergency map (UD-4), and impact scores (UD-5).

## Layout

```
android/          Jetpack Compose app (application id com.munipulse)
api/              MuniPulse.slnx and MuniPulse.Api
```

## Run the API

Requires the .NET 10 SDK.

```powershell
dotnet run --project api/MuniPulse.Api --launch-profile http
```

Health check: [http://localhost:5285/health](http://localhost:5285/health)

```json
{ "status": "ok", "app": "MuniPulse SA" }
```

MongoDB, the JWT signing key, and the field-worker demo key stay out of git. Put them in [user-secrets](https://learn.microsoft.com/aspnet/core/security/app-secrets) or a gitignored `appsettings.Development.json` (start from `appsettings.Development.json.example`). The signing key must be at least 32 characters.

```powershell
dotnet user-secrets set "MongoDb:ConnectionString" "<atlas-connection-string>" --project api/MuniPulse.Api
dotnet user-secrets set "Jwt:SigningKey" "<at-least-32-random-characters>" --project api/MuniPulse.Api
dotnet user-secrets set "FieldWorker:DemoApiKey" "<long-random-demo-key>" --project api/MuniPulse.Api
```

Copy `api/MuniPulse.Api/appsettings.Development.json.example` to `appsettings.Development.json` if you want the local session aliases below. That file is gitignored. `Auth:AllowDevBypass` only works when the host environment is Development.

### Domain API

Routes live under `/api/v1`. Failures use the planning error envelope (`error.code`, `message`, `fields`, `correlationId`). `/health` does not need the database. Data routes return `503 DATABASE_UNAVAILABLE` until `MongoDb:ConnectionString` is set.

| Method | Path | Auth |
| --- | --- | --- |
| POST | `/api/v1/auth/session` | Firebase ID token in the body. The API checks it against Google's securetoken certificates, then returns an API JWT. |
| GET, PATCH | `/api/v1/me` | Bearer API JWT |
| POST, GET | `/api/v1/incidents` and `/api/v1/incidents/{id}` | Bearer API JWT |
| POST | `/api/v1/incidents/{id}/upvotes` | Bearer API JWT. One vote per user. |
| POST | `/api/v1/incidents/{id}/milestones` | FieldWorker role, or header `X-Demo-Api-Key` |

Demo wards seeded into MongoDB: `JHB-23`, `JHB-24`, `CPT-11`, `DBN-07`, `TSH-04`. Categories: `Pothole`, `WaterLeak`, `IllegalDumping`, `Streetlight`, `Sewage`, `Other`.

Until M2, a Development host with `Auth:AllowDevBypass` set to true accepts `firebaseIdToken` values `dev-citizen` and `dev-field-worker` and returns an API JWT. Those aliases are refused in any other environment.

Milestone calls from a marker can send `X-Demo-Api-Key` with the value stored in user-secrets. A citizen JWT receives `403`. Near-duplicate ward aggregation is M6, so `aggregateId` stays null for now. Photo `url` stays null until multipart upload in M4.

Sample calls are in `api/MuniPulse.Api/MuniPulse.Api.http`.

## Run the Android app

Open `android/` in Android Studio (AGP 9.4, Gradle 9.6, compile SDK 37). Studio writes `sdk.dir` into `android/local.properties`. Copy `android/local.properties.example` if you need to set `API_BASE_URL` yourself.

The default API address `http://10.0.2.2:5285/` is the emulator route to the API on your computer. A physical phone needs your PC's LAN address or an HTTPS tunnel.

### Google sign-in

The login screen (S03) uses Firebase Authentication. The Google services plugin is `com.google.gms.google-services` 4.5.0. It is declared in `android/build.gradle.kts` and applied in `android/app/build.gradle.kts`. The Firebase BoM is 34.19.0.

`android/app/google-services.json` is gitignored (`**/google-services.json` in the root `.gitignore`). Commit `android/app/google-services.json.example` only. That example uses package `com.munipulse` and placeholder values, with an empty `oauth_client`.

1. Copy `android/app/google-services.json.example` to `android/app/google-services.json` if the real file is not there yet. Do not commit the copy.
2. The Firebase Android app package is `com.munipulse`. That is the application id in `android/app/build.gradle.kts`.
3. From `android/`, run `.\gradlew.bat :app:signingReport` and register the debug SHA-1 on that Firebase app.
4. Enable the Google sign-in provider. That creates a Web client ID.
5. Download `google-services.json` again into `android/app/`, replacing the local file. Do not commit it. The file is only complete after the Web client appears under `oauth_client`.
6. Rebuild and run on a device or emulator with Google Play. Tap **Continue with Google**.

After Google sign-in, the app sends the Firebase ID token to `POST /api/v1/auth/session`. The API JWT is stored in encrypted preferences and removed on sign-out. A later launch reuses it until it expires. Logs record the Firebase uid, the API user id, the HTTP method, path, status, and correlation id. They record an exception type when something fails. They do not record the Firebase ID token, the API JWT, the Authorization header, or a MongoDB connection string.

Cold start stays on the splash while that session is checked. A valid session opens Home. With no session, first launch shows three onboarding pages (photo and GPS, crowd-rank, milestones), then the Google sign-in screen. Sign-out returns to sign-in and does not show those pages again.

Home opens Settings. That screen shows the display name, a masked email, and the default ward (JHB-23, JHB-24, CPT-11, DBN-07, TSH-04). Sign out is on Settings.

When a session exists, the app calls `GET /api/v1/me`. Changing the ward or ticket-status and area-emergency switches calls `PATCH /api/v1/me`. Marketing and news stay on the device and are not sent. If the API is unreachable, the change stays local and Settings says so. The next successful sync sends that change. Preferred language is read and written with the profile (`en` or `zu`) and is not shown as its own screen yet.

Settings opens notification preferences: ticket status and area emergencies start on, and marketing and news start off. Those three choices stay on the device. Push delivery is Final POE and is not implemented.

Language, biometric unlock, and the sync centre are visible on Settings and marked **Coming in Final POE**. They do not open a language picker, a fingerprint prompt, or an offline queue.

Settings includes a POPIA-style privacy note: name, email, ward, location, and photos are kept only for this demo, and location and photos are used to report a fault.

Settings opens Profile. That screen shows the name, masked email, default ward, language, and role. Impact score and badges are marked **Coming in Final POE** and are not shown.

Home opens **New incident**. The form has a category (pothole, water leak, illegal dumping, streetlight, sewage, or other), a description, up to three photo previews from the camera or gallery, and a GPS line with **Refresh GPS**. Denying camera, gallery, or location stays on the screen and does not close the app. **Submit report** does not send the incident yet. Logcat records permission results and the accuracy in metres. It does not record the coordinates, the description, or a token.

Protected API routes require that bearer token. A missing token returns `401 UNAUTHENTICATED`. An invalid or expired token returns `401 INVALID_TOKEN`.

`Firebase:ProjectId` in `appsettings.json` must match the Firebase project (`munipulse-987ce`). The API still needs `Jwt:SigningKey` in user-secrets before it can issue a session.

The SDK used for the scaffold compile is `%LOCALAPPDATA%\Android\Sdk` (platform 37). `android/local.properties` is generated locally and gitignored. `ANDROID_HOME` does not need to be set when that file contains `sdk.dir`.

## Still to come

Screenshots and the unlisted demo video link are added in milestone M9.

**Demo video: TBD**
