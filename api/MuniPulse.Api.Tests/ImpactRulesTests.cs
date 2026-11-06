using MuniPulse.Api.Data;
using MuniPulse.Api.Models;

namespace MuniPulse.Api.Tests;

public class ImpactRulesTests
{
    [Fact]
    public void NoReportsScoresZeroWithoutAnError()
    {
        var impact = ImpactRules.Calculate(0, 0, [UserRoles.Citizen]);

        Assert.Equal(0, impact.Score);
        Assert.Equal(0, impact.ReportsCount);
        Assert.Equal(0, impact.UpvoteCount);
        Assert.Empty(impact.Badges);
    }

    [Fact]
    public void ScoreComesFromStoredReportsAndUpvotes()
    {
        var impact = ImpactRules.Calculate(reportsCount: 2, upvoteCount: 3, roles: [UserRoles.Citizen]);

        Assert.Equal(26, impact.Score);
        Assert.Contains(ImpactRules.FirstReport, impact.Badges);
        Assert.DoesNotContain(ImpactRules.VerifiedReporter, impact.Badges);
    }

    [Fact]
    public void CitizenCannotSelfAwardVerifiedReporter()
    {
        var earned = ImpactRules.Calculate(reportsCount: 4, upvoteCount: 5, roles: [UserRoles.Citizen]);
        var staff = ImpactRules.Calculate(reportsCount: 1, upvoteCount: 0, roles: [UserRoles.FieldWorker]);

        Assert.DoesNotContain(ImpactRules.VerifiedReporter, earned.Badges);
        Assert.Contains(ImpactRules.CommunityVoice, earned.Badges);
        Assert.Contains(ImpactRules.VerifiedReporter, staff.Badges);
        Assert.False(ImpactRules.CitizenCanWriteBadge([UserRoles.Citizen], ImpactRules.VerifiedReporter));
        Assert.True(ImpactRules.IsClientScoreOrBadge(["badges", "impactScore"]));
        Assert.False(ImpactRules.IsClientScoreOrBadge(["defaultWardCode"]));
    }
}
