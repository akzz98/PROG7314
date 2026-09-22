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
android/          Jetpack Compose app (za.co.munipulse)
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
| POST | `/api/v1/auth/session` | Firebase ID token in the body. Google certificate checks arrive in M2. |
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

The SDK used for the scaffold compile is `%LOCALAPPDATA%\Android\Sdk` (platform 37). `android/local.properties` is generated locally and gitignored. `ANDROID_HOME` does not need to be set when that file contains `sdk.dir`.

## Still to come

Screenshots and the unlisted demo video link are added in milestone M9.

**Demo video: TBD**
