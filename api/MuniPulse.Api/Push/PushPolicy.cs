namespace MuniPulse.Api.Push;

public static class PushPolicy
{
    // Ticket pushes go to the reporter when someone else posts a milestone.
    public static bool ShouldSendTicketPush(string reporterId, string actorId, bool ticketStatusEnabled) =>
        ticketStatusEnabled &&
        !string.IsNullOrWhiteSpace(reporterId) &&
        !string.Equals(reporterId, actorId, StringComparison.Ordinal);

    // Area pushes only when that preference is on. Marketing is never pushed.
    public static bool ShouldSendAreaPush(bool areaEmergenciesEnabled) => areaEmergenciesEnabled;

    public static bool IsMarketingPush(string? kind) =>
        string.Equals(kind?.Trim(), "marketing", StringComparison.OrdinalIgnoreCase);
}
