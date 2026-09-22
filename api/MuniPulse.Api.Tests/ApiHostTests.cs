using System.Net;
using System.Net.Http.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Configuration;

namespace MuniPulse.Api.Tests;

public class ApiHostTests : IClassFixture<ApiFactory>
{
    private readonly HttpClient _client;

    public ApiHostTests(ApiFactory factory)
    {
        _client = factory.CreateClient();
    }

    [Fact]
    public async Task HealthDoesNotNeedSecrets()
    {
        var response = await _client.GetAsync("/health");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task SessionWithoutATokenIsRejected()
    {
        var response = await _client.PostAsJsonAsync("/api/v1/auth/session", new { });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var body = await response.Content.ReadAsStringAsync();
        Assert.Contains("MISSING_TOKEN", body);
    }

    [Fact]
    public async Task CreateAndMilestoneWithoutATokenAreUnauthorized()
    {
        var create = await _client.PostAsJsonAsync("/api/v1/incidents", new { category = "Pothole" });
        var milestone = await _client.PostAsJsonAsync(
            "/api/v1/incidents/6500inc001/milestones",
            new { type = "Note", note = "Crew assigned" });

        Assert.Equal(HttpStatusCode.Unauthorized, create.StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, milestone.StatusCode);
    }
}

public class ApiFactory : WebApplicationFactory<Program>
{
    protected override void ConfigureWebHost(Microsoft.AspNetCore.Hosting.IWebHostBuilder builder)
    {
        builder.UseEnvironment("Testing");
        builder.ConfigureAppConfiguration((_, config) =>
        {
            config.AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["MongoDb:ConnectionString"] = "",
                ["Jwt:SigningKey"] = "",
                ["FieldWorker:DemoApiKey"] = "",
                ["Auth:AllowDevBypass"] = "false",
            });
        });
    }
}
