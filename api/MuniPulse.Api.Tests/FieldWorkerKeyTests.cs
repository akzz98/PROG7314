using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Configuration;
using MuniPulse.Api.Auth;

namespace MuniPulse.Api.Tests;

public class FieldWorkerKeyTests
{
    [Fact]
    public void MatchingHeaderIsAccepted()
    {
        var context = new DefaultHttpContext();
        context.Request.Headers[FieldWorkerKey.HeaderName] = "marker-demo-key";

        Assert.True(FieldWorkerKey.Matches(context, Config("marker-demo-key")));
    }

    [Fact]
    public void WrongOrMissingKeyIsRejected()
    {
        var context = new DefaultHttpContext();
        var config = Config("marker-demo-key");

        Assert.False(FieldWorkerKey.Matches(context, config));
        Assert.False(FieldWorkerKey.WasProvided(context));

        context.Request.Headers[FieldWorkerKey.HeaderName] = "other-key";
        Assert.False(FieldWorkerKey.Matches(context, config));
        Assert.True(FieldWorkerKey.WasProvided(context));
        Assert.False(FieldWorkerKey.Matches(context, Config("")));
    }

    private static IConfiguration Config(string key) =>
        new ConfigurationBuilder()
            .AddInMemoryCollection(new Dictionary<string, string?> { ["FieldWorker:DemoApiKey"] = key })
            .Build();
}
