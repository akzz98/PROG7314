using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using MuniPulse.Api.Auth;
using MuniPulse.Api.Data;
using MuniPulse.Api.Errors;
using MuniPulse.Api.Models;
using MuniPulse.Api.Push;

namespace MuniPulse.Api.Endpoints;

public static class ApiEndpoints
{
    public static void MapMuniPulseApi(this WebApplication app)
    {
        var api = app.MapGroup("/api/v1");
        api.MapPost("/auth/session", CreateSession);
        api.MapGet("/me", GetMe).RequireAuthorization();
        api.MapGet("/me/impact", GetImpact).RequireAuthorization();
        api.MapPost("/me/badges", RejectBadgeWrite).RequireAuthorization();
        api.MapPatch("/me", PatchMe).RequireAuthorization();
        api.MapPost("/incidents", CreateIncident).RequireAuthorization().RequireRateLimiting("writes");
        api.MapPost("/media/sas", IssueMediaSas).RequireAuthorization().RequireRateLimiting("writes");
        api.MapPost("/sync/push", PushSync).RequireAuthorization().RequireRateLimiting("writes");
        api.MapPost("/devices/fcm-token", RegisterFcmToken).RequireAuthorization().RequireRateLimiting("writes");
        api.MapDelete("/devices/fcm-token", ClearFcmToken).RequireAuthorization().RequireRateLimiting("writes");
        api.MapGet("/emergency/points", ListEmergencyPoints).RequireAuthorization();
        api.MapGet("/incidents/aggregates", ListAggregates).RequireAuthorization();
        api.MapGet("/incidents/nearby", ListNearby).RequireAuthorization();
        api.MapGet("/incidents/{id}", GetIncident).RequireAuthorization();
        api.MapGet("/incidents", ListIncidents).RequireAuthorization();
        api.MapPost("/incidents/{id}/upvotes", Upvote).RequireAuthorization().RequireRateLimiting("writes");
        api.MapPost("/incidents/{id}/milestones", AddMilestone).RequireRateLimiting("writes");
        api.MapPost("/alerts/area", SendAreaAlert).RequireRateLimiting("writes");
    }

    private static async Task<IResult> CreateSession(
        SessionRequest? request,
        IIdTokenVerifier verifier,
        ApiJwtIssuer issuer,
        IncidentStore store,
        HttpContext http,
        ILogger<ApiJwtIssuer> logger,
        CancellationToken cancellationToken)
    {
        if (request is null || string.IsNullOrWhiteSpace(request.FirebaseIdToken))
        {
            return ApiErrors.Result(http, StatusCodes.Status400BadRequest, "MISSING_TOKEN", "Firebase ID token is required.");
        }

        var identity = await verifier.VerifyAsync(request.FirebaseIdToken, cancellationToken);
        if (identity is null)
        {
            return ApiErrors.Result(
                http,
                StatusCodes.Status401Unauthorized,
                "INVALID_TOKEN",
                "The Firebase ID token was rejected.");
        }

        var user = await store.FindUserByFirebaseUidAsync(identity.FirebaseUid, cancellationToken);
        if (user is null)
        {
            user = new UserProfile
            {
                FirebaseUid = identity.FirebaseUid,
                Email = identity.Email,
                DisplayName = identity.DisplayName,
                DefaultWardCode = "JHB-23",
                PreferredLanguage = "en",
                Roles = [identity.Role],
                CreatedAt = DateTime.UtcNow,
            };
            await store.InsertUserAsync(user, cancellationToken);
        }

        var token = issuer.CreateToken(user);
        logger.LogInformation("API session issued for user {UserId}", user.Id);
        return Results.Ok(new SessionResponse(token, JwtSettings.TokenLifetimeSeconds, ToUser(user)));
    }

    private static async Task<IResult> GetMe(
        HttpContext http,
        IncidentStore store,
        CancellationToken cancellationToken)
    {
        var user = await RequireUserAsync(http, store, cancellationToken);
        return user is null
            ? ApiErrors.Result(http, StatusCodes.Status401Unauthorized, "UNAUTHENTICATED", "Sign in is required.")
            : Results.Ok(ToUser(user));
    }

    private static async Task<IResult> PatchMe(
        PatchMeRequest? request,
        HttpContext http,
        IncidentStore store,
        ILogger<IncidentStore> logger,
        CancellationToken cancellationToken)
    {
        if (request is not null && ImpactRules.IsClientScoreOrBadge(request.Extra?.Keys))
        {
            return BadgeForbidden(http);
        }

        var user = await RequireUserAsync(http, store, cancellationToken);
        if (user is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status401Unauthorized, "UNAUTHENTICATED", "Sign in is required.");
        }

        if (request is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status400BadRequest, "VALIDATION_ERROR", "A JSON body is required.");
        }

        var fields = new Dictionary<string, string>();
        if (request.DefaultWardCode is not null && !WardCatalog.IsKnown(request.DefaultWardCode))
        {
            fields["defaultWardCode"] = "Choose a demo ward such as JHB-23.";
        }

        if (request.PreferredLanguage is not null && !Languages.IsKnown(request.PreferredLanguage))
        {
            fields["preferredLanguage"] = "Use en or zu.";
        }

        if (fields.Count > 0)
        {
            return ApiErrors.Result(
                http,
                StatusCodes.Status400BadRequest,
                "VALIDATION_ERROR",
                "One or more fields are invalid.",
                fields);
        }

        if (request.DefaultWardCode is not null)
        {
            user.DefaultWardCode = request.DefaultWardCode;
        }

        if (request.PreferredLanguage is not null)
        {
            user.PreferredLanguage = request.PreferredLanguage;
        }

        if (request.Notifications?.TicketStatus is bool ticketStatus)
        {
            user.Notifications.TicketStatus = ticketStatus;
        }

        if (request.Notifications?.AreaEmergencies is bool areaEmergencies)
        {
            user.Notifications.AreaEmergencies = areaEmergencies;
        }

        await store.ReplaceUserAsync(user, cancellationToken);
        logger.LogInformation("Profile updated for user {UserId}", user.Id);
        return Results.Ok(ToUser(user));
    }

    private static async Task<IResult> GetImpact(
        HttpContext http,
        IncidentStore store,
        ILogger<IncidentStore> logger,
        CancellationToken cancellationToken)
    {
        var user = await RequireUserAsync(http, store, cancellationToken);
        if (user is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status401Unauthorized, "UNAUTHENTICATED", "Sign in is required.");
        }

        var summary = await store.SummarizeReporterAsync(user.Id, cancellationToken);
        var impact = ImpactRules.Calculate(summary.ReportsCount, summary.UpvoteCount, user.Roles);
        user.ImpactScore = impact.Score;
        user.Badges = impact.Badges.ToList();
        await store.ReplaceUserAsync(user, cancellationToken);
        logger.LogInformation(
            "Impact calculated for user {UserId}. Reports {ReportsCount}",
            user.Id,
            impact.ReportsCount);
        return Results.Ok(new ImpactResponse(impact.Score, impact.Badges.ToArray(), impact.ReportsCount, impact.UpvoteCount));
    }

    private static IResult RejectBadgeWrite(HttpContext http) => BadgeForbidden(http);

    private static IResult BadgeForbidden(HttpContext http) =>
        ApiErrors.Result(
            http,
            StatusCodes.Status403Forbidden,
            "BADGE_FORBIDDEN",
            "Badges and the impact score are assigned by the server. A citizen cannot award VerifiedReporter.");

    private static async Task<IResult> CreateIncident(
        CreateIncidentRequest? request,
        HttpContext http,
        IncidentStore store,
        BlobPhotos photos,
        ILogger<IncidentStore> logger,
        CancellationToken cancellationToken)
    {
        var user = await RequireUserAsync(http, store, cancellationToken);
        if (user is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status401Unauthorized, "UNAUTHENTICATED", "Sign in is required.");
        }

        if (request is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status400BadRequest, "VALIDATION_ERROR", "A JSON body is required.");
        }

        var fields = ValidateCreate(request);
        if (fields.Count > 0)
        {
            return ApiErrors.Result(
                http,
                StatusCodes.Status400BadRequest,
                "VALIDATION_ERROR",
                "One or more fields are invalid.",
                fields);
        }

        var mutationId = string.IsNullOrWhiteSpace(request.ClientMutationId)
            ? null
            : request.ClientMutationId.Trim();
        if (mutationId is not null)
        {
            var existing = await store.FindByMutationAsync(user.Id, mutationId, cancellationToken);
            if (existing is not null)
            {
                return ApiErrors.Result(
                    http,
                    StatusCodes.Status409Conflict,
                    "DUPLICATE_MUTATION",
                    "This report was already submitted.");
            }
        }

        var photoProblem = await RequireUploadedPhotos(request, user, photos, store, http, cancellationToken);
        if (photoProblem is not null)
        {
            return photoProblem;
        }

        var incident = await SaveNewIncident(request, user, store, logger, mutationId, cancellationToken);

        return Results.Created($"/api/v1/incidents/{incident.Id}", new CreateIncidentResponse(
            incident.Id,
            incident.Status,
            incident.UpvoteCount,
            incident.AggregateId,
            incident.CreatedAt));
    }

    private static async Task<IncidentReport> SaveNewIncident(
        CreateIncidentRequest request,
        UserProfile user,
        IncidentStore store,
        ILogger logger,
        string? mutationId,
        CancellationToken cancellationToken)
    {
        var now = DateTime.UtcNow;
        var incident = new IncidentReport
        {
            ReporterId = user.Id,
            Category = request.Category!.Trim(),
            Description = request.Description!.Trim(),
            Status = IncidentStatuses.Submitted,
            Latitude = request.Latitude!.Value,
            Longitude = request.Longitude!.Value,
            AccuracyMeters = request.AccuracyMeters,
            WardCode = request.WardCode!.Trim(),
            PhotoIds = (request.PhotoIds ?? []).Select(photo => photo.Trim()).ToList(),
            UpvoteCount = 1,
            ClientMutationId = mutationId,
            UpvotedBy = [user.Id],
            CreatedAt = now,
            UpdatedAt = now,
            Timeline =
            [
                new TimelineEvent
                {
                    At = now,
                    Type = TimelineEventTypes.Submitted,
                    Note = "Logged by citizen",
                    ActorId = user.Id,
                    ActorRole = UserRoles.Citizen,
                },
            ],
        };

        var nearest = await store.FindNearestOpenDuplicateAsync(
            incident.WardCode,
            incident.Category,
            incident.Latitude,
            incident.Longitude,
            now.AddDays(-DuplicateWindow.MaxAgeDays),
            cancellationToken);
        if (nearest is not null)
        {
            var aggregateId = string.IsNullOrWhiteSpace(nearest.AggregateId) ? nearest.Id : nearest.AggregateId;
            incident.AggregateId = aggregateId;
            if (!string.Equals(nearest.AggregateId, aggregateId, StringComparison.Ordinal))
            {
                await store.SetAggregateIdAsync(nearest.Id, aggregateId, cancellationToken);
            }
        }

        await store.InsertIncidentAsync(incident, cancellationToken);
        if (incident.AggregateId is null)
        {
            logger.LogInformation(
                "Incident {IncidentId} created in ward {WardCode} category {Category}",
                incident.Id,
                incident.WardCode,
                incident.Category);
        }
        else
        {
            logger.LogInformation(
                "Incident {IncidentId} merged into aggregate {AggregateId} in ward {WardCode} category {Category}",
                incident.Id,
                incident.AggregateId,
                incident.WardCode,
                incident.Category);
        }

        return incident;
    }

    private static async Task<IResult> PushSync(
        SyncPushRequest? request,
        HttpContext http,
        IncidentStore store,
        BlobPhotos photos,
        ILogger<IncidentStore> logger,
        CancellationToken cancellationToken)
    {
        var user = await RequireUserAsync(http, store, cancellationToken);
        if (user is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status401Unauthorized, "UNAUTHENTICATED", "Sign in is required.");
        }

        if (PushEnvelopeError(request?.Operations?.Count) is not null)
        {
            return ApiErrors.Result(http, StatusCodes.Status400BadRequest, "VALIDATION_ERROR", "One or more fields are invalid.");
        }

        var operations = request!.Operations!;
        var results = new SyncPushItemResult[operations.Count];
        var ready = new List<int>();
        for (var index = 0; index < operations.Count; index++)
        {
            var operation = operations[index];
            var conflict = PushConflictCode(operation);
            if (conflict is not null)
            {
                results[index] = new SyncPushItemResult(operation.ClientMutationId?.Trim(), SyncResults.Conflict, null, conflict);
                continue;
            }

            if (IsQueuedEdit(operation))
            {
                results[index] = await EditQueuedIncident(operation, user, store, logger, cancellationToken);
                continue;
            }

            var create = ToCreateRequest(operation);
            var gate = await GatePhotos(create, user, photos, store, cancellationToken);
            if (gate == PhotoGate.Unavailable)
            {
                return ApiErrors.Result(
                    http,
                    StatusCodes.Status503ServiceUnavailable,
                    "STORAGE_UNAVAILABLE",
                    "Photo storage could not be reached.");
            }

            if (gate == PhotoGate.Invalid)
            {
                results[index] = new SyncPushItemResult(operation.ClientMutationId?.Trim(), SyncResults.Conflict, null, "VALIDATION_ERROR");
                continue;
            }

            var mutationId = operation.ClientMutationId!.Trim();
            var existing = await store.FindByMutationAsync(user.Id, mutationId, cancellationToken);
            if (existing is not null)
            {
                results[index] = new SyncPushItemResult(mutationId, SyncResults.Ack, existing.Id, "DUPLICATE_MUTATION");
                logger.LogInformation(
                    "Sync item already stored for mutation {MutationId}. Incident {IncidentId}",
                    mutationId,
                    existing.Id);
                continue;
            }

            ready.Add(index);
        }

        foreach (var index in ready)
        {
            var operation = operations[index];
            var mutationId = operation.ClientMutationId!.Trim();
            try
            {
                var incident = await SaveNewIncident(ToCreateRequest(operation), user, store, logger, mutationId, cancellationToken);
                results[index] = new SyncPushItemResult(mutationId, SyncResults.Ack, incident.Id, null);
            }
            catch (ApiException ex) when (ex.Code == "DUPLICATE_MUTATION")
            {
                var existing = await store.FindByMutationAsync(user.Id, mutationId, cancellationToken);
                if (existing is null)
                {
                    throw;
                }

                results[index] = new SyncPushItemResult(mutationId, SyncResults.Ack, existing.Id, "DUPLICATE_MUTATION");
                logger.LogInformation(
                    "Sync item already stored for mutation {MutationId}. Incident {IncidentId}",
                    mutationId,
                    existing.Id);
            }
        }

        var ackCount = results.Count(item => item.Result == SyncResults.Ack);
        var conflictCount = results.Count(item => item.Result == SyncResults.Conflict);
        logger.LogInformation("Sync push finished. Ack {AckCount}. Conflict {ConflictCount}", ackCount, conflictCount);
        return Results.Ok(new SyncPushResponse(results));
    }

    public static string? PushEnvelopeError(int? operationCount)
    {
        if (operationCount is null or < 1 or > 20)
        {
            return "Send 1 to 20 operations.";
        }

        return null;
    }

    public static string? PushConflictCode(SyncOperationRequest? operation)
    {
        if (operation is null)
        {
            return "UNKNOWN_MUTATION";
        }

        var mutationId = operation.ClientMutationId?.Trim() ?? "";
        if (mutationId.Length is < 1 or > 80)
        {
            return "VALIDATION_ERROR";
        }

        var type = operation.MutationType?.Trim();
        if (string.Equals(type, "createIncident", StringComparison.Ordinal))
        {
            return ValidateCreate(ToCreateRequest(operation)).Count > 0 ? "VALIDATION_ERROR" : null;
        }

        if (IsQueuedEdit(operation))
        {
            var incidentId = operation.IncidentId?.Trim() ?? "";
            var description = operation.Description?.Trim() ?? "";
            if (incidentId.Length is < 1 or > 80 || description.Length is < 10 or > 1000)
            {
                return "VALIDATION_ERROR";
            }

            return null;
        }

        return "UNKNOWN_MUTATION";
    }

    public static string? ResolvedEditCode(string? serverStatus) =>
        serverStatus == IncidentStatuses.Resolved ? "RESOLVED" : null;

    private static bool IsQueuedEdit(SyncOperationRequest? operation) =>
        string.Equals(operation?.MutationType?.Trim(), "editIncident", StringComparison.Ordinal);

    private static async Task<SyncPushItemResult> EditQueuedIncident(
        SyncOperationRequest operation,
        UserProfile user,
        IncidentStore store,
        ILogger logger,
        CancellationToken cancellationToken)
    {
        var mutationId = operation.ClientMutationId!.Trim();
        var incident = await store.FindIncidentAsync(operation.IncidentId!.Trim(), cancellationToken);
        if (incident is null || !string.Equals(incident.ReporterId, user.Id, StringComparison.Ordinal))
        {
            return new SyncPushItemResult(mutationId, SyncResults.Conflict, null, "NOT_FOUND");
        }

        if (ResolvedEditCode(incident.Status) is not null)
        {
            logger.LogInformation(
                "Sync edit kept status {Status} for incident {IncidentId}",
                incident.Status,
                incident.Id);
            return new SyncPushItemResult(mutationId, SyncResults.Conflict, incident.Id, "RESOLVED");
        }

        var description = operation.Description!.Trim();
        var wrote = await store.UpdateDescriptionAsync(incident.Id, description, cancellationToken);
        if (!wrote)
        {
            var again = await store.FindIncidentAsync(incident.Id, cancellationToken);
            if (again is not null && ResolvedEditCode(again.Status) is not null)
            {
                logger.LogInformation(
                    "Sync edit kept status {Status} for incident {IncidentId}",
                    again.Status,
                    again.Id);
                return new SyncPushItemResult(mutationId, SyncResults.Conflict, again.Id, "RESOLVED");
            }

            return new SyncPushItemResult(mutationId, SyncResults.Conflict, incident.Id, "NOT_FOUND");
        }

        logger.LogInformation("Sync edit stored for incident {IncidentId}", incident.Id);
        return new SyncPushItemResult(mutationId, SyncResults.Ack, incident.Id, null);
    }

    private static CreateIncidentRequest ToCreateRequest(SyncOperationRequest operation) =>
        new()
        {
            Category = operation.Category,
            Description = operation.Description,
            Latitude = operation.Latitude,
            Longitude = operation.Longitude,
            AccuracyMeters = operation.AccuracyMeters,
            WardCode = operation.WardCode,
            PhotoIds = operation.PhotoIds,
            ClientMutationId = operation.ClientMutationId,
        };

    private static async Task<IResult> RegisterFcmToken(
        FcmTokenRequest? request,
        HttpContext http,
        IncidentStore store,
        ILogger<IncidentStore> logger,
        CancellationToken cancellationToken)
    {
        var user = await RequireUserAsync(http, store, cancellationToken);
        if (user is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status401Unauthorized, "UNAUTHENTICATED", "Sign in is required.");
        }

        if (request is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status400BadRequest, "VALIDATION_ERROR", "A JSON body is required.");
        }

        var fields = ValidateFcmToken(request);
        if (fields.Count > 0)
        {
            return ApiErrors.Result(
                http,
                StatusCodes.Status400BadRequest,
                "VALIDATION_ERROR",
                "One or more fields are invalid.",
                fields);
        }

        await store.UpsertDeviceTokenAsync(
            new DevicePushToken
            {
                UserId = user.Id,
                Token = request.Token!.Trim(),
                Platform = request.Platform!.Trim(),
                UpdatedAt = DateTime.UtcNow,
            },
            cancellationToken);
        logger.LogInformation("FCM token stored for platform {Platform}", request.Platform.Trim());
        return Results.NoContent();
    }

    private static async Task<IResult> ClearFcmToken(
        HttpContext http,
        IncidentStore store,
        ILogger<IncidentStore> logger,
        CancellationToken cancellationToken)
    {
        var user = await RequireUserAsync(http, store, cancellationToken);
        if (user is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status401Unauthorized, "UNAUTHENTICATED", "Sign in is required.");
        }

        await store.DeleteDeviceTokensAsync(user.Id, cancellationToken);
        logger.LogInformation("FCM tokens cleared for the signed-in user");
        return Results.NoContent();
    }

    public static Dictionary<string, string> ValidateFcmToken(FcmTokenRequest request)
    {
        var fields = new Dictionary<string, string>();
        var token = request.Token?.Trim() ?? "";
        if (token.Length is < 1 or > 4096)
        {
            fields["token"] = "Send a device push token.";
        }

        var platform = request.Platform?.Trim() ?? "";
        if (!string.Equals(platform, "android", StringComparison.Ordinal))
        {
            fields["platform"] = "Use android.";
        }

        return fields;
    }

    private static async Task<IResult> IssueMediaSas(
        MediaSasRequest? request,
        HttpContext http,
        BlobPhotos photos,
        IncidentStore store,
        ILogger<IncidentStore> logger,
        CancellationToken cancellationToken)
    {
        var user = await RequireUserAsync(http, store, cancellationToken);
        if (user is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status401Unauthorized, "UNAUTHENTICATED", "Sign in is required.");
        }

        if (request is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status400BadRequest, "VALIDATION_ERROR", "A JSON body is required.");
        }

        var fields = BlobPhotos.ValidateSas(request.ContentType, request.ByteSize);
        if (fields.Count > 0)
        {
            var failure = BlobPhotos.SasError(fields);
            return ApiErrors.Result(http, failure.Status, failure.Code, failure.Message, fields);
        }

        if (!photos.IsConfigured)
        {
            return ApiErrors.Result(
                http,
                StatusCodes.Status503ServiceUnavailable,
                "STORAGE_UNAVAILABLE",
                "Photo storage is not configured. Set AzureBlob:ConnectionString in user-secrets.");
        }

        var photoId = Guid.NewGuid().ToString("N");
        var lease = await photos.IssueUploadAsync(photoId, cancellationToken);
        if (lease is null)
        {
            return ApiErrors.Result(
                http,
                StatusCodes.Status503ServiceUnavailable,
                "STORAGE_UNAVAILABLE",
                "Photo storage could not issue an upload link.");
        }

        await store.InsertMediaAsync(
            new MediaAsset
            {
                Id = photoId,
                BlobContainer = photos.ContainerName,
                BlobName = BlobPhotos.BlobNameFor(photoId),
                ContentType = BlobPhotos.JpegContentType,
                ByteSize = request.ByteSize!.Value,
                CreatedBy = user.Id,
                CreatedAt = DateTime.UtcNow,
            },
            cancellationToken);
        logger.LogInformation("Media record stored for photo {PhotoId}.", photoId);
        return Results.Ok(new MediaSasResponse(photoId, lease.UploadUrl, lease.ExpiresAt));
    }

    private static async Task<IResult?> RequireUploadedPhotos(
        CreateIncidentRequest request,
        UserProfile user,
        BlobPhotos photos,
        IncidentStore store,
        HttpContext http,
        CancellationToken cancellationToken)
    {
        var gate = await GatePhotos(request, user, photos, store, cancellationToken);
        if (gate == PhotoGate.Ok)
        {
            return null;
        }

        if (gate == PhotoGate.Unavailable)
        {
            var message = photos.IsConfigured
                ? "Photo storage could not be reached."
                : "Photo storage is not configured. Set AzureBlob:ConnectionString in user-secrets.";
            return ApiErrors.Result(
                http,
                StatusCodes.Status503ServiceUnavailable,
                "STORAGE_UNAVAILABLE",
                message);
        }

        return ApiErrors.Result(
            http,
            StatusCodes.Status400BadRequest,
            "VALIDATION_ERROR",
            "One or more fields are invalid.",
            new Dictionary<string, string> { ["photoIds"] = "Upload each photo before submitting." });
    }

    private static async Task<PhotoGate> GatePhotos(
        CreateIncidentRequest request,
        UserProfile user,
        BlobPhotos photos,
        IncidentStore store,
        CancellationToken cancellationToken)
    {
        var ids = (request.PhotoIds ?? [])
            .Select(photo => photo.Trim())
            .Where(photo => photo.Length > 0)
            .ToList();
        if (ids.Count == 0)
        {
            return PhotoGate.Ok;
        }

        if (!photos.IsConfigured)
        {
            return PhotoGate.Unavailable;
        }

        foreach (var id in ids)
        {
            var media = BlobPhotos.IsPhotoId(id) ? await store.FindMediaAsync(id, cancellationToken) : null;
            if (media is null || !string.Equals(media.CreatedBy, user.Id, StringComparison.Ordinal))
            {
                return PhotoGate.Invalid;
            }

            var check = await photos.ExistsAsync(media.BlobName, cancellationToken);
            if (check == BlobCheck.Unavailable)
            {
                return PhotoGate.Unavailable;
            }

            if (check == BlobCheck.Missing)
            {
                return PhotoGate.Invalid;
            }
        }

        return PhotoGate.Ok;
    }

    private enum PhotoGate
    {
        Ok,
        Invalid,
        Unavailable,
    }

    private static async Task<IResult> ListAggregates(
        string? wardCode,
        HttpContext http,
        IncidentStore store,
        ILogger<IncidentStore> logger,
        CancellationToken cancellationToken)
    {
        var user = await RequireUserAsync(http, store, cancellationToken);
        if (user is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status401Unauthorized, "UNAUTHENTICATED", "Sign in is required.");
        }

        var resolvedWard = string.IsNullOrWhiteSpace(wardCode) ? user.DefaultWardCode : wardCode.Trim();
        if (string.IsNullOrWhiteSpace(resolvedWard) || !WardCatalog.IsKnown(resolvedWard))
        {
            return ApiErrors.Result(
                http,
                StatusCodes.Status400BadRequest,
                "VALIDATION_ERROR",
                "One or more fields are invalid.",
                new Dictionary<string, string> { ["wardCode"] = "A hotspot list needs a demo ward such as JHB-23." });
        }

        var groups = await store.ListOpenAggregatesAsync(resolvedWard, cancellationToken);
        logger.LogInformation("Ward aggregates loaded for {WardCode}. Groups {GroupCount}", resolvedWard, groups.Count);
        return Results.Ok(new AggregateListResponse(groups.Select(ToAggregate).ToArray()));
    }

    private static async Task<IResult> ListNearby(
        string? category,
        double? latitude,
        double? longitude,
        string? wardCode,
        HttpContext http,
        IncidentStore store,
        ILogger<IncidentStore> logger,
        CancellationToken cancellationToken)
    {
        var user = await RequireUserAsync(http, store, cancellationToken);
        if (user is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status401Unauthorized, "UNAUTHENTICATED", "Sign in is required.");
        }

        var fields = new Dictionary<string, string>();
        if (!IncidentCategories.IsKnown(category))
        {
            fields["category"] = "Choose a category such as Pothole.";
        }

        if (latitude is null or < -90 or > 90)
        {
            fields["latitude"] = "Latitude must be between -90 and 90.";
        }

        if (longitude is null or < -180 or > 180)
        {
            fields["longitude"] = "Longitude must be between -180 and 180.";
        }

        var resolvedWard = string.IsNullOrWhiteSpace(wardCode) ? user.DefaultWardCode : wardCode.Trim();
        if (string.IsNullOrWhiteSpace(resolvedWard) || !WardCatalog.IsKnown(resolvedWard))
        {
            fields["wardCode"] = "A nearby search needs a demo ward such as JHB-23.";
        }

        if (fields.Count > 0)
        {
            return ApiErrors.Result(
                http,
                StatusCodes.Status400BadRequest,
                "VALIDATION_ERROR",
                "One or more fields are invalid.",
                fields);
        }

        var matches = await store.ListNearbyDuplicatesAsync(
            resolvedWard!,
            category!.Trim(),
            latitude!.Value,
            longitude!.Value,
            DateTime.UtcNow.AddDays(-DuplicateWindow.MaxAgeDays),
            cancellationToken);
        logger.LogInformation("Nearby duplicates loaded for ward {WardCode}. Count {MatchCount}", resolvedWard, matches.Count);
        return Results.Ok(new NearbyListResponse(matches.Select(match => new NearbyDuplicateResponse(
            match.Id,
            match.Category,
            match.Place,
            match.UpvoteCount,
            match.DistanceMeters)).ToArray()));
    }

    private static AggregateSummary ToAggregate(WardAggregate group) =>
        new(group.AggregateId, group.Category, group.WardCode, group.ReportCount, group.UpvoteCount, group.IncidentId);

    private static async Task<IResult> ListEmergencyPoints(
        string? wardCode,
        HttpContext http,
        IncidentStore store,
        ILogger<IncidentStore> logger,
        CancellationToken cancellationToken)
    {
        var user = await RequireUserAsync(http, store, cancellationToken);
        if (user is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status401Unauthorized, "UNAUTHENTICATED", "Sign in is required.");
        }

        var code = wardCode?.Trim() ?? "";
        if (!WardCatalog.IsKnown(code))
        {
            return ApiErrors.Result(
                http,
                StatusCodes.Status400BadRequest,
                "VALIDATION_ERROR",
                "One or more fields are invalid.",
                new Dictionary<string, string> { ["wardCode"] = "Choose a demo ward such as JHB-23." });
        }

        var points = await store.ListEmergencyPointsAsync(code, cancellationToken);
        logger.LogInformation("Emergency points loaded for ward {WardCode}. Count {PointCount}", code, points.Count);
        return Results.Ok(new EmergencyPointsResponse(points.Select(point => new EmergencyPointResponse(
            point.Id,
            point.WardCode,
            point.Kind,
            point.Name,
            point.Latitude,
            point.Longitude)).ToArray()));
    }

    private static async Task<IResult> GetIncident(
        string id,
        HttpContext http,
        IncidentStore store,
        BlobPhotos photos,
        CancellationToken cancellationToken)
    {
        var user = await RequireUserAsync(http, store, cancellationToken);
        if (user is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status401Unauthorized, "UNAUTHENTICATED", "Sign in is required.");
        }

        var incident = await store.FindIncidentAsync(id, cancellationToken);
        if (incident is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status404NotFound, "NOT_FOUND", "That incident does not exist.");
        }

        var links = new List<PhotoLink>(incident.PhotoIds.Count);
        foreach (var photoId in incident.PhotoIds)
        {
            var media = await store.FindMediaAsync(photoId, cancellationToken);
            links.Add(new PhotoLink(photoId, media is null ? null : photos.ReadUrl(media.BlobName)));
        }

        return Results.Ok(ToDetail(incident, user.Id, links));
    }

    private static async Task<IResult> ListIncidents(
        string? scope,
        string? wardCode,
        string? status,
        string? cursor,
        int? limit,
        HttpContext http,
        IncidentStore store,
        CancellationToken cancellationToken)
    {
        var user = await RequireUserAsync(http, store, cancellationToken);
        if (user is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status401Unauthorized, "UNAUTHENTICATED", "Sign in is required.");
        }

        var fields = new Dictionary<string, string>();
        if (scope is not "mine" and not "ward")
        {
            fields["scope"] = "Use mine or ward.";
        }

        if (status is not null && !IncidentStatuses.IsKnown(status))
        {
            fields["status"] = "Use Submitted, InProgress, or Resolved.";
        }

        if (wardCode is not null && !WardCatalog.IsKnown(wardCode))
        {
            fields["wardCode"] = "Choose a demo ward such as JHB-23.";
        }

        if (cursor is not null && !MongoDB.Bson.ObjectId.TryParse(cursor, out _))
        {
            fields["cursor"] = "The page cursor is not valid.";
        }

        var pageSize = limit ?? 20;
        if (limit is < 1 or > 50)
        {
            fields["limit"] = "Use a limit from 1 to 50.";
        }

        string? resolvedWard = null;
        if (scope == "ward")
        {
            resolvedWard = wardCode ?? user.DefaultWardCode;
            if (string.IsNullOrWhiteSpace(resolvedWard) || !WardCatalog.IsKnown(resolvedWard))
            {
                fields["wardCode"] = "A ward feed needs a demo ward such as JHB-23.";
            }
        }

        if (fields.Count > 0)
        {
            return ApiErrors.Result(
                http,
                StatusCodes.Status400BadRequest,
                "VALIDATION_ERROR",
                "One or more fields are invalid.",
                fields);
        }

        var page = await store.ListAsync(
            scope == "mine" ? user.Id : null,
            scope == "ward" ? resolvedWard : null,
            status,
            cursor,
            pageSize,
            cancellationToken);

        return Results.Ok(new IncidentListResponse(
            page.Items.Select(incident => ToSummary(incident, user.Id)).ToArray(),
            page.NextCursor));
    }

    private static async Task<IResult> Upvote(
        string id,
        HttpContext http,
        IncidentStore store,
        ILogger<IncidentStore> logger,
        CancellationToken cancellationToken)
    {
        var user = await RequireUserAsync(http, store, cancellationToken);
        if (user is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status401Unauthorized, "UNAUTHENTICATED", "Sign in is required.");
        }

        var result = await store.UpvoteAsync(id, user.Id, cancellationToken);
        if (result.MissingIncident || result.Incident is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status404NotFound, "NOT_FOUND", "That incident does not exist.");
        }

        if (result.AlreadyUpvoted)
        {
            return ApiErrors.Result(
                http,
                StatusCodes.Status409Conflict,
                "ALREADY_UPVOTED",
                "You have already upvoted this incident.");
        }

        logger.LogInformation(
            "Upvote on incident {IncidentId} count {UpvoteCount}",
            result.Incident.Id,
            result.Incident.UpvoteCount);

        return Results.Ok(new UpvoteResponse(result.Incident.UpvoteCount, result.Incident.AggregateId));
    }

    private static async Task<IResult> AddMilestone(
        string id,
        MilestoneRequest? request,
        HttpContext http,
        IncidentStore store,
        PushDispatch pushes,
        IConfiguration configuration,
        ILogger<IncidentStore> logger,
        CancellationToken cancellationToken)
    {
        var actor = await ResolveFieldWorkerAsync(http, store, configuration, logger, cancellationToken);
        if (actor.Error is not null)
        {
            return actor.Error;
        }

        if (request is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status400BadRequest, "VALIDATION_ERROR", "A JSON body is required.");
        }

        var fields = new Dictionary<string, string>();
        if (!TimelineEventTypes.IsStaffType(request.Type))
        {
            fields["type"] = "Use Assigned, OnSite, Resolved, or Note.";
        }

        var note = request.Note?.Trim() ?? "";
        if (note.Length is < 1 or > 500)
        {
            fields["note"] = "Enter a note between 1 and 500 characters.";
        }

        if (fields.Count > 0)
        {
            return ApiErrors.Result(
                http,
                StatusCodes.Status400BadRequest,
                "VALIDATION_ERROR",
                "One or more fields are invalid.",
                fields);
        }

        var incident = await store.FindIncidentAsync(id, cancellationToken);
        if (incident is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status404NotFound, "NOT_FOUND", "That incident does not exist.");
        }

        if (incident.Status == IncidentStatuses.Resolved && request.Type != TimelineEventTypes.Note)
        {
            return ApiErrors.Result(
                http,
                StatusCodes.Status409Conflict,
                "CONFLICT",
                "This incident is already resolved.");
        }

        var status = request.Type switch
        {
            TimelineEventTypes.Resolved => IncidentStatuses.Resolved,
            TimelineEventTypes.Note => incident.Status,
            _ => incident.Status == IncidentStatuses.Resolved
                ? incident.Status
                : IncidentStatuses.InProgress,
        };

        var timelineEvent = new TimelineEvent
        {
            At = DateTime.UtcNow,
            Type = request.Type!,
            Note = note,
            ActorId = actor.User!.Id,
            ActorRole = UserRoles.FieldWorker,
        };

        var saved = await store.AddMilestoneAsync(id, timelineEvent, status, cancellationToken);
        if (saved is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status404NotFound, "NOT_FOUND", "That incident does not exist.");
        }

        var updated = await store.FindIncidentAsync(id, cancellationToken);
        var ordered = (updated?.Timeline ?? [saved])
            .OrderBy(entry => entry.At)
            .ThenBy(entry => entry.Type, StringComparer.Ordinal)
            .Select(ToTimeline)
            .ToArray();

        logger.LogInformation(
            "Milestone {MilestoneType} appended to incident {IncidentId}. Timeline {TimelineCount}",
            saved.Type,
            id,
            ordered.Length);

        if (updated is not null)
        {
            try
            {
                await pushes.NotifyTicketAsync(updated, actor.User!.Id, saved.Type, cancellationToken);
            }
            catch (Exception ex)
            {
                logger.LogError("Ticket push failed after milestone ({ExceptionType}).", ex.GetType().Name);
            }
        }

        return Results.Created(
            $"/api/v1/incidents/{id}",
            new MilestoneCreatedResponse(updated?.Status ?? status, ordered));
    }

    private static async Task<IResult> SendAreaAlert(
        AreaAlertRequest? request,
        HttpContext http,
        IncidentStore store,
        PushDispatch pushes,
        IConfiguration configuration,
        ILogger<IncidentStore> logger,
        CancellationToken cancellationToken)
    {
        var actor = await ResolveFieldWorkerAsync(http, store, configuration, logger, cancellationToken);
        if (actor.Error is not null)
        {
            return actor.Error;
        }

        if (request is null)
        {
            return ApiErrors.Result(http, StatusCodes.Status400BadRequest, "VALIDATION_ERROR", "A JSON body is required.");
        }

        var wardCode = request.WardCode?.Trim() ?? "";
        var note = request.Note?.Trim() ?? "";
        var fields = new Dictionary<string, string>();
        if (!WardCatalog.IsKnown(wardCode))
        {
            fields["wardCode"] = "Choose a demo ward such as JHB-23.";
        }

        if (note.Length > 500)
        {
            fields["note"] = "Use at most 500 characters.";
        }

        if (fields.Count > 0)
        {
            return ApiErrors.Result(
                http,
                StatusCodes.Status400BadRequest,
                "VALIDATION_ERROR",
                "One or more fields are invalid.",
                fields);
        }

        try
        {
            await pushes.NotifyAreaAsync(wardCode, note, cancellationToken);
        }
        catch (Exception ex)
        {
            logger.LogError("Area push failed ({ExceptionType}).", ex.GetType().Name);
        }

        logger.LogInformation("Area alert accepted for ward {WardCode}", wardCode);
        return Results.Accepted();
    }

    private static async Task<UserProfile?> RequireUserAsync(
        HttpContext http,
        IncidentStore store,
        CancellationToken cancellationToken)
    {
        var id = http.User.FindFirstValue(JwtRegisteredClaimNames.Sub);
        if (string.IsNullOrEmpty(id))
        {
            return null;
        }

        return await store.FindUserByIdAsync(id, cancellationToken);
    }

    private static async Task<(UserProfile? User, IResult? Error)> ResolveFieldWorkerAsync(
        HttpContext http,
        IncidentStore store,
        IConfiguration configuration,
        ILogger logger,
        CancellationToken cancellationToken)
    {
        if (FieldWorkerKey.Matches(http, configuration))
        {
            var seeded = await store.FindUserByFirebaseUidAsync(WardCatalog.DemoFieldWorkerUid, cancellationToken);
            if (seeded is null)
            {
                return (null, ApiErrors.Result(
                    http,
                    StatusCodes.Status503ServiceUnavailable,
                    "DATABASE_UNAVAILABLE",
                    "The demo field worker is not available. Check the database connection."));
            }

            return (seeded, null);
        }

        if (FieldWorkerKey.WasProvided(http))
        {
            logger.LogInformation("Milestone rejected. Demo key did not match");
            var configured = !string.IsNullOrWhiteSpace(configuration["FieldWorker:DemoApiKey"]);
            return (null, ApiErrors.Result(
                http,
                StatusCodes.Status403Forbidden,
                "FORBIDDEN",
                configured
                    ? "The field worker key was rejected."
                    : "The field worker demo key is not configured."));
        }

        if (HasBearerToken(http))
        {
            var auth = await http.AuthenticateAsync(JwtBearerDefaults.AuthenticationScheme);
            if (!auth.Succeeded || auth.Principal is null)
            {
                return (null, ApiErrors.Result(
                    http,
                    StatusCodes.Status401Unauthorized,
                    "INVALID_TOKEN",
                    "The access token is invalid or expired."));
            }

            http.User = auth.Principal;
        }

        var user = await RequireUserAsync(http, store, cancellationToken);
        if (user is null)
        {
            return (null, ApiErrors.Result(http, StatusCodes.Status401Unauthorized, "UNAUTHENTICATED", "Sign in is required."));
        }

        if (!user.Roles.Contains(UserRoles.FieldWorker))
        {
            logger.LogInformation("Milestone forbidden for user {UserId}", user.Id);
            return (null, ApiErrors.Result(
                http,
                StatusCodes.Status403Forbidden,
                "FORBIDDEN",
                "Only a field worker can publish milestones."));
        }

        return (user, null);
    }

    private static bool HasBearerToken(HttpContext http)
    {
        if (!http.Request.Headers.TryGetValue("Authorization", out var values))
        {
            return false;
        }

        var header = values.ToString();
        return header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase)
            && header.Length > "Bearer ".Length;
    }

    internal static Dictionary<string, string> ValidateCreate(CreateIncidentRequest request)
    {
        var fields = new Dictionary<string, string>();
        if (!IncidentCategories.IsKnown(request.Category))
        {
            fields["category"] = "Choose a known category such as Pothole or WaterLeak.";
        }

        var description = request.Description?.Trim() ?? "";
        if (description.Length is < 10 or > 1000)
        {
            fields["description"] = "Use between 10 and 1000 characters.";
        }

        if (request.Latitude is null || request.Latitude is < -90 or > 90)
        {
            fields["latitude"] = "Latitude must be between -90 and 90.";
        }

        if (request.Longitude is null || request.Longitude is < -180 or > 180)
        {
            fields["longitude"] = "Longitude must be between -180 and 180.";
        }

        if (request.AccuracyMeters is < 0 or > 10000)
        {
            fields["accuracyMeters"] = "Accuracy must be zero or a positive distance in metres.";
        }

        if (!WardCatalog.IsKnown(request.WardCode))
        {
            fields["wardCode"] = "Choose a demo ward such as JHB-23.";
        }

        var photos = request.PhotoIds ?? [];
        if (photos.Count > 3)
        {
            fields["photoIds"] = "Attach at most 3 photos.";
        }
        else if (photos.Any(photo => string.IsNullOrWhiteSpace(photo) || photo.Trim().Length > 80))
        {
            fields["photoIds"] = "Each photo id must be 1 to 80 characters.";
        }

        if (request.ClientMutationId is not null &&
            (request.ClientMutationId.Trim().Length is < 1 or > 80))
        {
            fields["clientMutationId"] = "Use a client id between 1 and 80 characters.";
        }

        return fields;
    }

    private static UserResponse ToUser(UserProfile user) =>
        new(
            user.Id,
            user.DisplayName,
            user.Email,
            user.DefaultWardCode,
            user.PreferredLanguage,
            user.Roles.ToArray(),
            new NotificationResponse(user.Notifications.TicketStatus, user.Notifications.AreaEmergencies));

    private static IncidentSummary ToSummary(IncidentReport incident, string viewerId) =>
        new(
            incident.Id,
            incident.Category,
            incident.Description,
            incident.Status,
            incident.WardCode,
            incident.Latitude,
            incident.Longitude,
            incident.UpvoteCount,
            incident.AggregateId,
            incident.CreatedAt,
            incident.UpvotedBy.Contains(viewerId));

    private static IncidentDetail ToDetail(IncidentReport incident, string viewerId, IReadOnlyList<PhotoLink> photos) =>
        new(
            incident.Id,
            incident.Category,
            incident.Description,
            incident.Status,
            incident.Latitude,
            incident.Longitude,
            incident.AccuracyMeters,
            incident.WardCode,
            incident.UpvoteCount,
            incident.AggregateId,
            incident.UpvotedBy.Contains(viewerId),
            photos.ToArray(),
            incident.Timeline
                .OrderBy(entry => entry.At)
                .Select(ToTimeline)
                .ToArray(),
            incident.CreatedAt);

    private static TimelineEventResponse ToTimeline(TimelineEvent entry) =>
        new(entry.At, entry.Type, entry.Note, entry.ActorRole);
}

public sealed class SessionRequest
{
    public string? FirebaseIdToken { get; set; }
}

public sealed class PatchMeRequest
{
    public string? DefaultWardCode { get; set; }

    public string? PreferredLanguage { get; set; }

    public NotificationPatch? Notifications { get; set; }

    [JsonExtensionData]
    public Dictionary<string, JsonElement>? Extra { get; set; }
}

public sealed record ImpactResponse(int Score, string[] Badges, int ReportsCount, int UpvoteCount);

public sealed class NotificationPatch
{
    public bool? TicketStatus { get; set; }

    public bool? AreaEmergencies { get; set; }
}

public static class SyncResults
{
    public const string Ack = "ack";

    public const string Conflict = "conflict";
}

public sealed class FcmTokenRequest
{
    public string? Token { get; set; }

    public string? Platform { get; set; }
}

public sealed class SyncPushRequest
{
    public List<SyncOperationRequest>? Operations { get; set; }
}

public sealed class SyncOperationRequest
{
    public string? ClientMutationId { get; set; }

    public string? MutationType { get; set; }

    public string? IncidentId { get; set; }

    public string? Category { get; set; }

    public string? Description { get; set; }

    public double? Latitude { get; set; }

    public double? Longitude { get; set; }

    public double? AccuracyMeters { get; set; }

    public string? WardCode { get; set; }

    public List<string>? PhotoIds { get; set; }
}

public sealed record SyncPushResponse(SyncPushItemResult[] Results);

public sealed record SyncPushItemResult(string? ClientMutationId, string Result, string? IncidentId, string? Code);

public sealed class CreateIncidentRequest
{
    public string? Category { get; set; }

    public string? Description { get; set; }

    public double? Latitude { get; set; }

    public double? Longitude { get; set; }

    public double? AccuracyMeters { get; set; }

    public string? WardCode { get; set; }

    public List<string>? PhotoIds { get; set; }

    public string? ClientMutationId { get; set; }
}

public sealed class MilestoneRequest
{
    public string? Type { get; set; }

    public string? Note { get; set; }
}

public sealed class AreaAlertRequest
{
    public string? WardCode { get; set; }

    public string? Note { get; set; }
}

public sealed record SessionResponse(string AccessToken, int ExpiresIn, UserResponse User);

public sealed record UserResponse(
    string Id,
    string? DisplayName,
    string Email,
    string? DefaultWardCode,
    string PreferredLanguage,
    string[] Roles,
    NotificationResponse Notifications);

public sealed record NotificationResponse(bool TicketStatus, bool AreaEmergencies);

public sealed class MediaSasRequest
{
    public string? ContentType { get; set; }

    public long? ByteSize { get; set; }
}

public sealed record MediaSasResponse(string PhotoId, string UploadUrl, DateTimeOffset ExpiresAt);

public sealed record CreateIncidentResponse(
    string Id,
    string Status,
    int UpvoteCount,
    string? AggregateId,
    DateTime CreatedAt);

public sealed record UpvoteResponse(int UpvoteCount, string? AggregateId);

public sealed record EmergencyPointsResponse(EmergencyPointResponse[] Items);

public sealed record EmergencyPointResponse(
    string Id,
    string WardCode,
    string Kind,
    string Name,
    double Latitude,
    double Longitude);

public sealed record NearbyListResponse(NearbyDuplicateResponse[] Items);

public sealed record NearbyDuplicateResponse(
    string Id,
    string Category,
    string Place,
    int UpvoteCount,
    int DistanceMeters);

public sealed record AggregateListResponse(AggregateSummary[] Items);

public sealed record AggregateSummary(
    string AggregateId,
    string Category,
    string WardCode,
    int ReportCount,
    int UpvoteCount,
    string IncidentId);

public sealed record IncidentListResponse(IncidentSummary[] Items, string? NextCursor);

public sealed record IncidentSummary(
    string Id,
    string Category,
    string Description,
    string Status,
    string WardCode,
    double Latitude,
    double Longitude,
    int UpvoteCount,
    string? AggregateId,
    DateTime CreatedAt,
    bool ViewerHasUpvoted);

public sealed record IncidentDetail(
    string Id,
    string Category,
    string Description,
    string Status,
    double Latitude,
    double Longitude,
    double? AccuracyMeters,
    string WardCode,
    int UpvoteCount,
    string? AggregateId,
    bool ViewerHasUpvoted,
    PhotoLink[] Photos,
    TimelineEventResponse[] Timeline,
    DateTime CreatedAt);

public sealed record PhotoLink(string Id, string? Url);

public sealed record MilestoneCreatedResponse(string Status, TimelineEventResponse[] Timeline);

public sealed record TimelineEventResponse(DateTime At, string Type, string Note, string ActorRole);
