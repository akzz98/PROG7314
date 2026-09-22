using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using MuniPulse.Api.Auth;
using MuniPulse.Api.Data;
using MuniPulse.Api.Errors;
using MuniPulse.Api.Models;

namespace MuniPulse.Api.Endpoints;

public static class ApiEndpoints
{
    public static void MapMuniPulseApi(this WebApplication app)
    {
        var api = app.MapGroup("/api/v1");
        api.MapPost("/auth/session", CreateSession);
        api.MapGet("/me", GetMe).RequireAuthorization();
        api.MapPatch("/me", PatchMe).RequireAuthorization();
        api.MapPost("/incidents", CreateIncident).RequireAuthorization().RequireRateLimiting("writes");
        api.MapGet("/incidents/{id}", GetIncident).RequireAuthorization();
        api.MapGet("/incidents", ListIncidents).RequireAuthorization();
        api.MapPost("/incidents/{id}/upvotes", Upvote).RequireAuthorization().RequireRateLimiting("writes");
        api.MapPost("/incidents/{id}/milestones", AddMilestone).RequireRateLimiting("writes");
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

    private static async Task<IResult> CreateIncident(
        CreateIncidentRequest? request,
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

        await store.InsertIncidentAsync(incident, cancellationToken);
        logger.LogInformation(
            "Incident {IncidentId} created in ward {WardCode} category {Category}",
            incident.Id,
            incident.WardCode,
            incident.Category);

        return Results.Created($"/api/v1/incidents/{incident.Id}", new CreateIncidentResponse(
            incident.Id,
            incident.Status,
            incident.UpvoteCount,
            incident.AggregateId,
            incident.CreatedAt));
    }

    private static async Task<IResult> GetIncident(
        string id,
        HttpContext http,
        IncidentStore store,
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

        return Results.Ok(ToDetail(incident, user.Id));
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
        IConfiguration configuration,
        ILogger<IncidentStore> logger,
        CancellationToken cancellationToken)
    {
        var actor = await ResolveFieldWorkerAsync(http, store, configuration, cancellationToken);
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

        logger.LogInformation(
            "Milestone {MilestoneType} appended to incident {IncidentId}",
            saved.Type,
            id);

        return Results.Created($"/api/v1/incidents/{id}", ToTimeline(saved));
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
            var configured = !string.IsNullOrWhiteSpace(configuration["FieldWorker:DemoApiKey"]);
            return (null, ApiErrors.Result(
                http,
                StatusCodes.Status403Forbidden,
                "FORBIDDEN",
                configured
                    ? "The field worker key was rejected."
                    : "The field worker demo key is not configured."));
        }

        var user = await RequireUserAsync(http, store, cancellationToken);
        if (user is null)
        {
            return (null, ApiErrors.Result(http, StatusCodes.Status401Unauthorized, "UNAUTHENTICATED", "Sign in is required."));
        }

        if (!user.Roles.Contains(UserRoles.FieldWorker))
        {
            return (null, ApiErrors.Result(
                http,
                StatusCodes.Status403Forbidden,
                "FORBIDDEN",
                "Only a field worker can publish milestones."));
        }

        return (user, null);
    }

    private static Dictionary<string, string> ValidateCreate(CreateIncidentRequest request)
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

    private static IncidentDetail ToDetail(IncidentReport incident, string viewerId) =>
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
            incident.PhotoIds.Select(photo => new PhotoLink(photo, null)).ToArray(),
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
}

public sealed class NotificationPatch
{
    public bool? TicketStatus { get; set; }

    public bool? AreaEmergencies { get; set; }
}

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

public sealed record CreateIncidentResponse(
    string Id,
    string Status,
    int UpvoteCount,
    string? AggregateId,
    DateTime CreatedAt);

public sealed record UpvoteResponse(int UpvoteCount, string? AggregateId);

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

public sealed record TimelineEventResponse(DateTime At, string Type, string Note, string ActorRole);
