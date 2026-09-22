using MongoDB.Bson;
using MongoDB.Bson.Serialization.Attributes;

namespace MuniPulse.Api.Models;

// Planning section H. Impact score and badges are stored so the document shape
// survives until Final POE UD-5. Part 2 responses do not expose them.
public sealed class UserProfile
{
    [BsonId]
    [BsonRepresentation(BsonType.ObjectId)]
    public string Id { get; set; } = ObjectId.GenerateNewId().ToString();

    public string FirebaseUid { get; set; } = "";

    public string Email { get; set; } = "";

    public string? DisplayName { get; set; }

    public string? DefaultWardCode { get; set; }

    public string PreferredLanguage { get; set; } = "en";

    public List<string> Roles { get; set; } = ["Citizen"];

    public NotificationPreferences Notifications { get; set; } = new();

    public int ImpactScore { get; set; }

    public List<string> Badges { get; set; } = [];

    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
}

public sealed class NotificationPreferences
{
    public bool TicketStatus { get; set; } = true;

    public bool AreaEmergencies { get; set; } = true;
}

public sealed class IncidentReport
{
    [BsonId]
    [BsonRepresentation(BsonType.ObjectId)]
    public string Id { get; set; } = ObjectId.GenerateNewId().ToString();

    public string ReporterId { get; set; } = "";

    public string Category { get; set; } = "";

    public string Description { get; set; } = "";

    public string Status { get; set; } = IncidentStatuses.Submitted;

    public double Latitude { get; set; }

    public double Longitude { get; set; }

    public double? AccuracyMeters { get; set; }

    public string WardCode { get; set; } = "";

    public List<string> PhotoIds { get; set; } = [];

    public int UpvoteCount { get; set; }

    // Shared by open reports in the same ward and category within 250 m and 14 days.
    public string? AggregateId { get; set; }

    public string? ClientMutationId { get; set; }

    public List<string> UpvotedBy { get; set; } = [];

    public List<TimelineEvent> Timeline { get; set; } = [];

    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;

    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;
}

// Planning section H. Embedded on the incident so a detail read returns one document.
public sealed class TimelineEvent
{
    public DateTime At { get; set; }

    public string Type { get; set; } = "";

    public string Note { get; set; } = "";

    public string ActorId { get; set; } = "";

    public string ActorRole { get; set; } = "";
}

public sealed class WardDocument
{
    [BsonId]
    public string Code { get; set; } = "";

    public string Municipality { get; set; } = "";

    public string Name { get; set; } = "";
}

public static class IncidentCategories
{
    public const string Pothole = "Pothole";
    public const string WaterLeak = "WaterLeak";
    public const string IllegalDumping = "IllegalDumping";
    public const string Streetlight = "Streetlight";
    public const string Sewage = "Sewage";
    public const string Other = "Other";

    public static readonly string[] All =
    [
        Pothole, WaterLeak, IllegalDumping, Streetlight, Sewage, Other,
    ];

    public static bool IsKnown(string? value) => value is not null && All.Contains(value);
}

public static class IncidentStatuses
{
    public const string Submitted = "Submitted";
    public const string InProgress = "InProgress";
    public const string Resolved = "Resolved";

    public static readonly string[] All = [Submitted, InProgress, Resolved];

    public static bool IsKnown(string? value) => value is not null && All.Contains(value);
}

public static class TimelineEventTypes
{
    public const string Submitted = "Submitted";
    public const string Assigned = "Assigned";
    public const string OnSite = "OnSite";
    public const string Resolved = "Resolved";
    public const string Note = "Note";

    public static readonly string[] StaffTypes = [Assigned, OnSite, Resolved, Note];

    public static bool IsStaffType(string? value) => value is not null && StaffTypes.Contains(value);
}

public static class UserRoles
{
    public const string Citizen = "Citizen";
    public const string WardLead = "WardLead";
    public const string FieldWorker = "FieldWorker";
}

public static class Languages
{
    public static readonly string[] All = ["en", "zu"];

    public static bool IsKnown(string? value) => value is not null && All.Contains(value);
}

public sealed record WardInfo(string Code, string Municipality, string Name);

// Demo municipality codes from Planning (JHB-23) plus a few sibling wards for the picker.
public static class WardCatalog
{
    public const string DemoFieldWorkerUid = "demo-field-worker";

    public static readonly IReadOnlyList<WardInfo> All =
    [
        new("JHB-23", "Johannesburg", "Ward 23"),
        new("JHB-24", "Johannesburg", "Ward 24"),
        new("CPT-11", "Cape Town", "Ward 11"),
        new("DBN-07", "eThekwini", "Ward 07"),
        new("TSH-04", "Tshwane", "Ward 04"),
    ];

    public static bool IsKnown(string? code) =>
        code is not null && All.Any(ward => ward.Code == code);
}
