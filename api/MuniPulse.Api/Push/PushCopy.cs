namespace MuniPulse.Api.Push;

public static class PushCopy
{
    public static (string Title, string Body) Ticket(string language, string milestoneType) =>
        IsZulu(language)
            ? ("Isimo setikithi", $"Umbiko wakho manje uthi: {MilestoneZu(milestoneType)}.")
            : ("Ticket update", $"Your report is now: {MilestoneEn(milestoneType)}.");

    public static (string Title, string Body) Area(string language, string wardCode, string note)
    {
        var place = string.IsNullOrWhiteSpace(wardCode) ? "your ward" : wardCode.Trim();
        var detail = string.IsNullOrWhiteSpace(note) ? null : note.Trim();
        if (IsZulu(language))
        {
            var body = detail is null
                ? $"Isixwayiso sendawo e-{place}."
                : $"Isixwayiso sendawo e-{place}: {detail}";
            return ("Isixwayiso sendawo", body);
        }

        var english = detail is null
            ? $"Area alert for {place}."
            : $"Area alert for {place}: {detail}";
        return ("Area alert", english);
    }

    private static bool IsZulu(string? language) =>
        string.Equals(language?.Trim(), "zu", StringComparison.OrdinalIgnoreCase);

    private static string MilestoneEn(string type) => type switch
    {
        "Assigned" => "Assigned",
        "OnSite" => "On site",
        "Resolved" => "Resolved",
        "Note" => "Updated",
        _ => type,
    };

    private static string MilestoneZu(string type) => type switch
    {
        "Assigned" => "Wabiwe",
        "OnSite" => "Esizeni",
        "Resolved" => "Uxazululiwe",
        "Note" => "Ubuyekeziwe",
        _ => type,
    };
}
