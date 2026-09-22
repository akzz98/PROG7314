using MongoDB.Bson;
using MongoDB.Driver;
using MuniPulse.Api.Errors;
using MuniPulse.Api.Models;

namespace MuniPulse.Api.Data;

public sealed class IncidentStore
{
    private readonly IMongoCollection<UserProfile>? _users;
    private readonly IMongoCollection<IncidentReport>? _incidents;
    private readonly IMongoCollection<WardDocument>? _wards;
    private readonly ILogger<IncidentStore> _logger;

    public IncidentStore(IConfiguration configuration, ILogger<IncidentStore> logger)
    {
        _logger = logger;
        var connectionString = configuration["MongoDb:ConnectionString"];
        var databaseName = configuration["MongoDb:DatabaseName"];
        if (string.IsNullOrWhiteSpace(databaseName))
        {
            databaseName = "munipulse";
        }

        if (string.IsNullOrWhiteSpace(connectionString))
        {
            _logger.LogWarning("MongoDb:ConnectionString is empty. Incident routes cannot persist yet.");
            return;
        }

        try
        {
            var settings = MongoClientSettings.FromConnectionString(connectionString);
            settings.ServerSelectionTimeout = TimeSpan.FromSeconds(3);
            var database = new MongoClient(settings).GetDatabase(databaseName);
            _users = database.GetCollection<UserProfile>("users");
            _incidents = database.GetCollection<IncidentReport>("incidents");
            _wards = database.GetCollection<WardDocument>("wards");
        }
        catch (Exception ex) when (ex is FormatException or ArgumentException)
        {
            _logger.LogError("MongoDb:ConnectionString could not be parsed ({ExceptionType}).", ex.GetType().Name);
        }
    }

    public bool IsConfigured => _incidents is not null;

    public async Task SeedAsync(CancellationToken cancellationToken)
    {
        if (_users is null || _incidents is null || _wards is null)
        {
            return;
        }

        await _users.Indexes.CreateOneAsync(
            new CreateIndexModel<UserProfile>(
                Builders<UserProfile>.IndexKeys.Ascending(user => user.FirebaseUid),
                new CreateIndexOptions { Unique = true, Name = "ux_firebase_uid" }),
            cancellationToken: cancellationToken);

        await _incidents.Indexes.CreateOneAsync(
            new CreateIndexModel<IncidentReport>(
                Builders<IncidentReport>.IndexKeys
                    .Ascending(incident => incident.ReporterId)
                    .Ascending(incident => incident.ClientMutationId),
                new CreateIndexOptions<IncidentReport>
                {
                    Unique = true,
                    Name = "ux_reporter_mutation",
                    PartialFilterExpression = Builders<IncidentReport>.Filter.Type(
                        incident => incident.ClientMutationId,
                        BsonType.String),
                }),
            cancellationToken: cancellationToken);

        foreach (var ward in WardCatalog.All)
        {
            await _wards.ReplaceOneAsync(
                document => document.Code == ward.Code,
                new WardDocument
                {
                    Code = ward.Code,
                    Municipality = ward.Municipality,
                    Name = ward.Name,
                },
                new ReplaceOptions { IsUpsert = true },
                cancellationToken);
        }

        var fieldWorker = await _users
            .Find(user => user.FirebaseUid == WardCatalog.DemoFieldWorkerUid)
            .FirstOrDefaultAsync(cancellationToken);
        if (fieldWorker is null)
        {
            await _users.InsertOneAsync(new UserProfile
            {
                FirebaseUid = WardCatalog.DemoFieldWorkerUid,
                Email = "field.worker@munipulse.demo",
                DisplayName = "Demo Field Worker",
                DefaultWardCode = "JHB-23",
                PreferredLanguage = "en",
                Roles = [UserRoles.FieldWorker],
                CreatedAt = DateTime.UtcNow,
            }, cancellationToken: cancellationToken);
        }

        _logger.LogInformation(
            "MongoDB seed complete. Demo wards: {WardCodes}",
            string.Join(", ", WardCatalog.All.Select(ward => ward.Code)));
    }

    public async Task<UserProfile?> FindUserByIdAsync(string id, CancellationToken cancellationToken)
    {
        if (!ObjectId.TryParse(id, out _))
        {
            return null;
        }

        try
        {
            return await Users().Find(user => user.Id == id).FirstOrDefaultAsync(cancellationToken);
        }
        catch (MongoException ex)
        {
            throw DatabaseFailure(ex);
        }
    }

    public async Task<UserProfile?> FindUserByFirebaseUidAsync(string firebaseUid, CancellationToken cancellationToken)
    {
        try
        {
            return await Users().Find(user => user.FirebaseUid == firebaseUid).FirstOrDefaultAsync(cancellationToken);
        }
        catch (MongoException ex)
        {
            throw DatabaseFailure(ex);
        }
    }

    public async Task InsertUserAsync(UserProfile user, CancellationToken cancellationToken)
    {
        try
        {
            await Users().InsertOneAsync(user, cancellationToken: cancellationToken);
        }
        catch (MongoException ex)
        {
            throw DatabaseFailure(ex);
        }
    }

    public async Task ReplaceUserAsync(UserProfile user, CancellationToken cancellationToken)
    {
        try
        {
            await Users().ReplaceOneAsync(existing => existing.Id == user.Id, user, cancellationToken: cancellationToken);
        }
        catch (MongoException ex)
        {
            throw DatabaseFailure(ex);
        }
    }

    public async Task<IncidentReport?> FindByMutationAsync(
        string reporterId,
        string clientMutationId,
        CancellationToken cancellationToken)
    {
        try
        {
            return await Incidents()
                .Find(incident => incident.ReporterId == reporterId && incident.ClientMutationId == clientMutationId)
                .FirstOrDefaultAsync(cancellationToken);
        }
        catch (MongoException ex)
        {
            throw DatabaseFailure(ex);
        }
    }

    public async Task InsertIncidentAsync(IncidentReport incident, CancellationToken cancellationToken)
    {
        try
        {
            await Incidents().InsertOneAsync(incident, cancellationToken: cancellationToken);
        }
        catch (MongoWriteException ex) when (ex.WriteError?.Category == ServerErrorCategory.DuplicateKey)
        {
            throw new ApiException(
                StatusCodes.Status409Conflict,
                "DUPLICATE_MUTATION",
                "This report was already submitted.");
        }
        catch (MongoException ex)
        {
            throw DatabaseFailure(ex);
        }
    }

    public async Task<IncidentReport?> FindIncidentAsync(string id, CancellationToken cancellationToken)
    {
        if (!ObjectId.TryParse(id, out _))
        {
            return null;
        }

        try
        {
            return await Incidents().Find(incident => incident.Id == id).FirstOrDefaultAsync(cancellationToken);
        }
        catch (MongoException ex)
        {
            throw DatabaseFailure(ex);
        }
    }

    public async Task<IncidentPage> ListAsync(
        string? reporterId,
        string? wardCode,
        string? status,
        string? cursor,
        int limit,
        CancellationToken cancellationToken)
    {
        var filters = new List<FilterDefinition<IncidentReport>>();
        if (reporterId is not null)
        {
            filters.Add(Builders<IncidentReport>.Filter.Eq(incident => incident.ReporterId, reporterId));
        }

        if (wardCode is not null)
        {
            filters.Add(Builders<IncidentReport>.Filter.Eq(incident => incident.WardCode, wardCode));
        }

        if (status is not null)
        {
            filters.Add(Builders<IncidentReport>.Filter.Eq(incident => incident.Status, status));
        }

        if (cursor is not null)
        {
            filters.Add(Builders<IncidentReport>.Filter.Lt(incident => incident.Id, cursor));
        }

        var filter = filters.Count == 0
            ? Builders<IncidentReport>.Filter.Empty
            : Builders<IncidentReport>.Filter.And(filters);

        try
        {
            var items = await Incidents()
                .Find(filter)
                .SortByDescending(incident => incident.Id)
                .Limit(limit + 1)
                .ToListAsync(cancellationToken);

            string? nextCursor = null;
            if (items.Count > limit)
            {
                items.RemoveAt(items.Count - 1);
                nextCursor = items[^1].Id;
            }

            return new IncidentPage(items, nextCursor);
        }
        catch (MongoException ex)
        {
            throw DatabaseFailure(ex);
        }
    }

    public async Task<IncidentReport?> FindNearestOpenDuplicateAsync(
        string wardCode,
        string category,
        double latitude,
        double longitude,
        DateTime createdSince,
        CancellationToken cancellationToken)
    {
        var filter = Builders<IncidentReport>.Filter.And(
            Builders<IncidentReport>.Filter.Eq(incident => incident.WardCode, wardCode),
            Builders<IncidentReport>.Filter.Eq(incident => incident.Category, category),
            Builders<IncidentReport>.Filter.In(
                incident => incident.Status,
                new[] { IncidentStatuses.Submitted, IncidentStatuses.InProgress }),
            Builders<IncidentReport>.Filter.Gte(incident => incident.CreatedAt, createdSince));

        try
        {
            var candidates = await Incidents()
                .Find(filter)
                .Limit(200)
                .ToListAsync(cancellationToken);
            IncidentReport? nearest = null;
            var nearestMeters = double.MaxValue;
            foreach (var candidate in candidates)
            {
                var meters = DuplicateWindow.MetersBetween(latitude, longitude, candidate.Latitude, candidate.Longitude);
                if (meters <= DuplicateWindow.RadiusMeters && meters < nearestMeters)
                {
                    nearest = candidate;
                    nearestMeters = meters;
                }
            }

            return nearest;
        }
        catch (MongoException ex)
        {
            throw DatabaseFailure(ex);
        }
    }

    public async Task SetAggregateIdAsync(string incidentId, string aggregateId, CancellationToken cancellationToken)
    {
        try
        {
            await Incidents().UpdateOneAsync(
                incident => incident.Id == incidentId,
                Builders<IncidentReport>.Update
                    .Set(incident => incident.AggregateId, aggregateId)
                    .Set(incident => incident.UpdatedAt, DateTime.UtcNow),
                cancellationToken: cancellationToken);
        }
        catch (MongoException ex)
        {
            throw DatabaseFailure(ex);
        }
    }

    public async Task<IReadOnlyList<WardAggregate>> ListOpenAggregatesAsync(
        string wardCode,
        CancellationToken cancellationToken)
    {
        var filter = Builders<IncidentReport>.Filter.And(
            Builders<IncidentReport>.Filter.Eq(incident => incident.WardCode, wardCode),
            Builders<IncidentReport>.Filter.In(
                incident => incident.Status,
                new[] { IncidentStatuses.Submitted, IncidentStatuses.InProgress }));

        try
        {
            var items = await Incidents().Find(filter).Limit(500).ToListAsync(cancellationToken);
            return items
                .GroupBy(incident => string.IsNullOrWhiteSpace(incident.AggregateId) ? incident.Id : incident.AggregateId)
                .Select(group =>
                {
                    var lead = group
                        .OrderByDescending(incident => incident.UpvoteCount)
                        .ThenBy(incident => incident.Id, StringComparer.Ordinal)
                        .First();
                    return new WardAggregate(
                        group.Key!,
                        lead.Category,
                        wardCode,
                        group.Count(),
                        group.Sum(incident => incident.UpvoteCount),
                        lead.Id);
                })
                .OrderByDescending(aggregate => aggregate.UpvoteCount)
                .ThenBy(aggregate => aggregate.Category, StringComparer.Ordinal)
                .ToArray();
        }
        catch (MongoException ex)
        {
            throw DatabaseFailure(ex);
        }
    }

    public async Task<UpvoteResult> UpvoteAsync(string incidentId, string userId, CancellationToken cancellationToken)
    {
        if (!ObjectId.TryParse(incidentId, out _))
        {
            return UpvoteResult.Missing();
        }

        var filter = Builders<IncidentReport>.Filter.And(
            Builders<IncidentReport>.Filter.Eq(incident => incident.Id, incidentId),
            Builders<IncidentReport>.Filter.Not(
                Builders<IncidentReport>.Filter.AnyEq(incident => incident.UpvotedBy, userId)));
        var update = Builders<IncidentReport>.Update
            .Inc(incident => incident.UpvoteCount, 1)
            .AddToSet(incident => incident.UpvotedBy, userId)
            .Set(incident => incident.UpdatedAt, DateTime.UtcNow);

        try
        {
            var updated = await Incidents().FindOneAndUpdateAsync(
                filter,
                update,
                new FindOneAndUpdateOptions<IncidentReport> { ReturnDocument = ReturnDocument.After },
                cancellationToken);

            if (updated is not null)
            {
                return UpvoteResult.Updated(updated);
            }

            var existing = await FindIncidentAsync(incidentId, cancellationToken);
            return existing is null ? UpvoteResult.Missing() : UpvoteResult.Already(existing);
        }
        catch (MongoException ex)
        {
            throw DatabaseFailure(ex);
        }
    }

    public async Task<TimelineEvent?> AddMilestoneAsync(
        string incidentId,
        TimelineEvent timelineEvent,
        string status,
        CancellationToken cancellationToken)
    {
        if (!ObjectId.TryParse(incidentId, out _))
        {
            return null;
        }

        var update = Builders<IncidentReport>.Update
            .Push(incident => incident.Timeline, timelineEvent)
            .Set(incident => incident.Status, status)
            .Set(incident => incident.UpdatedAt, timelineEvent.At);

        try
        {
            var result = await Incidents().UpdateOneAsync(
                incident => incident.Id == incidentId,
                update,
                cancellationToken: cancellationToken);
            return result.MatchedCount == 0 ? null : timelineEvent;
        }
        catch (MongoException ex)
        {
            throw DatabaseFailure(ex);
        }
    }

    private IMongoCollection<UserProfile> Users() =>
        _users ?? throw NotConfigured();

    private IMongoCollection<IncidentReport> Incidents() =>
        _incidents ?? throw NotConfigured();

    private static ApiException NotConfigured() =>
        new(
            StatusCodes.Status503ServiceUnavailable,
            "DATABASE_UNAVAILABLE",
            "The database is not configured. Set MongoDb:ConnectionString in user-secrets.");

    private ApiException DatabaseFailure(MongoException exception)
    {
        _logger.LogError(
            "MongoDB operation failed ({ExceptionType}). Correlation is on the HTTP response.",
            exception.GetType().Name);
        return new ApiException(
            StatusCodes.Status503ServiceUnavailable,
            "DATABASE_UNAVAILABLE",
            "MuniPulse could not reach the database.");
    }
}

public sealed record IncidentPage(IReadOnlyList<IncidentReport> Items, string? NextCursor);

public sealed record WardAggregate(
    string AggregateId,
    string Category,
    string WardCode,
    int ReportCount,
    int UpvoteCount,
    string IncidentId);

public sealed record UpvoteResult(IncidentReport? Incident, bool MissingIncident, bool AlreadyUpvoted)
{
    public static UpvoteResult Updated(IncidentReport incident) => new(incident, false, false);

    public static UpvoteResult Missing() => new(null, true, false);

    public static UpvoteResult Already(IncidentReport incident) => new(incident, false, true);
}
