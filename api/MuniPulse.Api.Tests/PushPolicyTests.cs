using MuniPulse.Api.Push;

namespace MuniPulse.Api.Tests;

public class PushPolicyTests
{
    [Fact]
    public void SendsATicketPushOnlyToSomeoneElseWithThePreferenceOn()
    {
        Assert.True(PushPolicy.ShouldSendTicketPush("citizen-1", "worker-1", ticketStatusEnabled: true));
        Assert.False(PushPolicy.ShouldSendTicketPush("citizen-1", "citizen-1", ticketStatusEnabled: true));
        Assert.False(PushPolicy.ShouldSendTicketPush("citizen-1", "worker-1", ticketStatusEnabled: false));
    }

    [Fact]
    public void SendsAnAreaPushOnlyWhenAreaEmergenciesAreOn()
    {
        Assert.True(PushPolicy.ShouldSendAreaPush(areaEmergenciesEnabled: true));
        Assert.False(PushPolicy.ShouldSendAreaPush(areaEmergenciesEnabled: false));
        Assert.True(PushPolicy.IsMarketingPush("marketing"));
        Assert.False(PushPolicy.IsMarketingPush("area"));
    }
}
