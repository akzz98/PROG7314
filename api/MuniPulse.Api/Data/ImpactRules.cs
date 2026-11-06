using MuniPulse.Api.Models;

namespace MuniPulse.Api.Data;

public sealed record ImpactSnapshot(int Score, IReadOnlyList<string> Badges, int ReportsCount, int UpvoteCount);

// Score and badges are derived here. Callers cannot pass a score or a badge to award.
public static class ImpactRules
{
    public const string FirstReport = "FirstReport";
    public const string CommunityVoice = "CommunityVoice";
    public const string VerifiedReporter = "VerifiedReporter";

    public const int PointsPerReport = 10;
    public const int PointsPerUpvote = 2;
    public const int CommunityVoiceUpvotes = 5;

    public static ImpactSnapshot Calculate(int reportsCount, int upvoteCount, IEnumerable<string>? roles)
    {
        var reports = Math.Max(0, reportsCount);
        var upvotes = Math.Max(0, upvoteCount);
        var score = checked(reports * PointsPerReport + upvotes * PointsPerUpvote);
        var badges = new List<string>();
        if (reports >= 1)
        {
            badges.Add(FirstReport);
        }

        if (upvotes >= CommunityVoiceUpvotes)
        {
            badges.Add(CommunityVoice);
        }

        if (reports >= 1 && HasStaffRole(roles))
        {
            badges.Add(VerifiedReporter);
        }

        return new ImpactSnapshot(score, badges, reports, upvotes);
    }

    // Badge writes are refused for every role. VerifiedReporter is only added by Calculate for staff.
    public static bool CitizenCanWriteBadge(IEnumerable<string>? roles, string? badge)
    {
        _ = roles;
        _ = badge;
        return false;
    }

    public static bool IsClientScoreOrBadge(IEnumerable<string>? propertyNames)
    {
        if (propertyNames is null)
        {
            return false;
        }

        foreach (var name in propertyNames)
        {
            if (name.Equals("badges", StringComparison.OrdinalIgnoreCase) ||
                name.Equals("badge", StringComparison.OrdinalIgnoreCase) ||
                name.Equals("impactScore", StringComparison.OrdinalIgnoreCase) ||
                name.Equals("score", StringComparison.OrdinalIgnoreCase))
            {
                return true;
            }
        }

        return false;
    }

    private static bool HasStaffRole(IEnumerable<string>? roles) =>
        roles?.Any(role =>
            string.Equals(role, UserRoles.FieldWorker, StringComparison.Ordinal) ||
            string.Equals(role, UserRoles.WardLead, StringComparison.Ordinal)) == true;
}
