using System.IdentityModel.Tokens.Jwt;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Claims;
using System.Text;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.IdentityModel.Tokens;
using MuniPulse.Api.Data;
using MuniPulse.Api.Models;

namespace MuniPulse.Api.Tests;

public class ImpactWriteTests : IClassFixture<ImpactApiFactory>
{
    private readonly HttpClient _client;

    public ImpactWriteTests(ImpactApiFactory factory)
    {
        _client = factory.CreateClient();
    }

    [Fact]
    public async Task CitizenTokenCannotWriteABadge()
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, "/api/v1/me/badges")
        {
            Content = JsonContent.Create(new { badge = ImpactRules.VerifiedReporter }),
        };
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", CitizenToken.Create());

        var response = await _client.SendAsync(request);
        var body = await response.Content.ReadAsStringAsync();

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        Assert.Contains("BADGE_FORBIDDEN", body);
        Assert.DoesNotContain(CitizenToken.SigningKey, body);
    }

    [Fact]
    public async Task CitizenTokenCannotSendAnImpactScore()
    {
        using var request = new HttpRequestMessage(HttpMethod.Patch, "/api/v1/me")
        {
            Content = JsonContent.Create(new { impactScore = 99, badges = new[] { ImpactRules.VerifiedReporter } }),
        };
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", CitizenToken.Create());

        var response = await _client.SendAsync(request);
        var body = await response.Content.ReadAsStringAsync();

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        Assert.Contains("BADGE_FORBIDDEN", body);
    }
}

file static class CitizenToken
{
    public const string SigningKey = "test-signing-key-at-least-32-characters";

    public static string Create()
    {
        var claims = new[]
        {
            new Claim(JwtRegisteredClaimNames.Sub, "650000000000000000000001"),
            new Claim(JwtRegisteredClaimNames.Email, "citizen@munipulse.demo"),
            new Claim("role", UserRoles.Citizen),
        };
        var key = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(SigningKey));
        var token = new JwtSecurityToken(
            issuer: "MuniPulse",
            audience: "MuniPulse.Android",
            claims: claims,
            notBefore: DateTime.UtcNow.AddMinutes(-1),
            expires: DateTime.UtcNow.AddHours(1),
            signingCredentials: new SigningCredentials(key, SecurityAlgorithms.HmacSha256));
        return new JwtSecurityTokenHandler().WriteToken(token);
    }
}

public class ImpactApiFactory : WebApplicationFactory<Program>
{
    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseEnvironment("Testing");
        builder.ConfigureAppConfiguration((_, config) =>
        {
            config.AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["MongoDb:ConnectionString"] = "",
                ["Jwt:SigningKey"] = CitizenToken.SigningKey,
                ["Jwt:Issuer"] = "MuniPulse",
                ["Jwt:Audience"] = "MuniPulse.Android",
                ["FieldWorker:DemoApiKey"] = "",
                ["Auth:AllowDevBypass"] = "false",
            });
        });
        builder.ConfigureTestServices(services =>
        {
            services.PostConfigure<JwtBearerOptions>(JwtBearerDefaults.AuthenticationScheme, options =>
            {
                var key = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(CitizenToken.SigningKey));
                options.MapInboundClaims = false;
                options.TokenValidationParameters.ValidateIssuerSigningKey = true;
                options.TokenValidationParameters.IssuerSigningKey = key;
                options.TokenValidationParameters.ValidIssuer = "MuniPulse";
                options.TokenValidationParameters.ValidAudience = "MuniPulse.Android";
                options.TokenValidationParameters.RoleClaimType = "role";
                options.TokenValidationParameters.NameClaimType = JwtRegisteredClaimNames.Sub;
            });
        });
    }
}
