using MuniPulse.Api.Push;

namespace MuniPulse.Api.Tests;

public class PushCopyTests
{
    [Fact]
    public void BuildsEnglishAndIsiZuluTicketCopy()
    {
        var english = PushCopy.Ticket("en", "Resolved");
        var zulu = PushCopy.Ticket("zu", "Resolved");

        Assert.Equal("Ticket update", english.Title);
        Assert.Contains("Resolved", english.Body);
        Assert.Equal("Isimo setikithi", zulu.Title);
        Assert.Contains("Uxazululiwe", zulu.Body);
    }

    [Fact]
    public void BuildsEnglishAndIsiZuluAreaCopy()
    {
        var english = PushCopy.Area("en", "JHB-23", "Burst main");
        var zulu = PushCopy.Area("zu", "JHB-23", "Burst main");

        Assert.Equal("Area alert", english.Title);
        Assert.Contains("JHB-23", english.Body);
        Assert.Equal("Isixwayiso sendawo", zulu.Title);
        Assert.Contains("JHB-23", zulu.Body);
    }
}
