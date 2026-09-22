using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.IdentityModel.Tokens;
using MuniPulse.Api.Errors;
using MuniPulse.Api.Models;

namespace MuniPulse.Api.Auth;

public sealed class JwtSettings
{
    public const int TokenLifetimeSeconds = 3600;
    public const int MinimumKeyLength = 32;

    public JwtSettings(IConfiguration configuration)
    {
        Issuer = configuration["Jwt:Issuer"] ?? "MuniPulse";
        Audience = configuration["Jwt:Audience"] ?? "MuniPulse.Android";
        SigningKey = configuration["Jwt:SigningKey"];
    }

    public string Issuer { get; }

    public string Audience { get; }

    public string? SigningKey { get; }

    public bool IsConfigured =>
        !string.IsNullOrWhiteSpace(SigningKey) && SigningKey.Length >= MinimumKeyLength;
}

public sealed record VerifiedIdentity(string FirebaseUid, string Email, string DisplayName, string Role);

public interface IIdTokenVerifier
{
    Task<VerifiedIdentity?> VerifyAsync(string firebaseIdToken, CancellationToken cancellationToken);
}

// Development-only aliases. They are not secrets, and they do nothing unless
// Auth:AllowDevBypass is true and the host environment is Development.
public sealed class DevBypassIdTokenVerifier
{
    private readonly IHostEnvironment _environment;
    private readonly bool _allowDevBypass;

    public DevBypassIdTokenVerifier(IHostEnvironment environment, IConfiguration configuration)
    {
        _environment = environment;
        _allowDevBypass = configuration.GetValue("Auth:AllowDevBypass", false);
    }

    public bool IsActive => _environment.IsDevelopment() && _allowDevBypass;

    public VerifiedIdentity? Verify(string firebaseIdToken)
    {
        if (!IsActive)
        {
            return null;
        }

        return firebaseIdToken switch
        {
            "dev-citizen" => new VerifiedIdentity(
                "dev-citizen",
                "citizen@munipulse.demo",
                "Demo Citizen",
                UserRoles.Citizen),
            "dev-field-worker" => new VerifiedIdentity(
                WardCatalog.DemoFieldWorkerUid,
                "field.worker@munipulse.demo",
                "Demo Field Worker",
                UserRoles.FieldWorker),
            _ => null,
        };
    }
}

public sealed class ApiJwtIssuer
{
    private readonly JwtSettings _settings;

    public ApiJwtIssuer(JwtSettings settings)
    {
        _settings = settings;
    }

    public string CreateToken(UserProfile user)
    {
        if (!_settings.IsConfigured || _settings.SigningKey is null)
        {
            throw new ApiException(
                StatusCodes.Status500InternalServerError,
                "CONFIGURATION_ERROR",
                "The API signing key is not configured.");
        }

        var claims = new List<Claim>
        {
            new(JwtRegisteredClaimNames.Sub, user.Id),
            new(JwtRegisteredClaimNames.Email, user.Email),
        };
        claims.AddRange(user.Roles.Select(role => new Claim("role", role)));

        var key = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(_settings.SigningKey));
        var token = new JwtSecurityToken(
            issuer: _settings.Issuer,
            audience: _settings.Audience,
            claims: claims,
            notBefore: DateTime.UtcNow,
            expires: DateTime.UtcNow.AddSeconds(JwtSettings.TokenLifetimeSeconds),
            signingCredentials: new SigningCredentials(key, SecurityAlgorithms.HmacSha256));

        return new JwtSecurityTokenHandler().WriteToken(token);
    }
}

public static class FieldWorkerKey
{
    public const string HeaderName = "X-Demo-Api-Key";

    public static bool Matches(HttpContext http, IConfiguration configuration)
    {
        var expected = configuration["FieldWorker:DemoApiKey"];
        if (string.IsNullOrWhiteSpace(expected))
        {
            return false;
        }

        if (!http.Request.Headers.TryGetValue(HeaderName, out var providedValues))
        {
            return false;
        }

        var provided = providedValues.ToString();
        if (string.IsNullOrEmpty(provided))
        {
            return false;
        }

        var providedHash = SHA256.HashData(Encoding.UTF8.GetBytes(provided));
        var expectedHash = SHA256.HashData(Encoding.UTF8.GetBytes(expected));
        return CryptographicOperations.FixedTimeEquals(providedHash, expectedHash);
    }

    public static bool WasProvided(HttpContext http) =>
        http.Request.Headers.ContainsKey(HeaderName);
}

public static class AuthRegistration
{
    public static void AddApiAuth(this WebApplicationBuilder builder)
    {
        // Keep "sub" and "role" as issued. The default inbound map renames them.
        JwtSecurityTokenHandler.DefaultInboundClaimTypeMap.Clear();

        var settings = new JwtSettings(builder.Configuration);
        builder.Services.AddSingleton(settings);
        builder.Services.AddSingleton<ApiJwtIssuer>();
        builder.Services.AddHttpClient(FirebaseIdTokenVerifier.HttpClientName);
        builder.Services.AddSingleton<DevBypassIdTokenVerifier>();
        builder.Services.AddSingleton<FirebaseIdTokenVerifier>();
        builder.Services.AddSingleton<IIdTokenVerifier, SessionIdTokenVerifier>();

        // When the real key is missing, tokens are checked against a random key so
        // nothing issued outside this process can authenticate. Issuance stays disabled.
        var keyText = settings.IsConfigured
            ? settings.SigningKey!
            : Convert.ToHexString(RandomNumberGenerator.GetBytes(32));

        builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
            .AddJwtBearer(options =>
            {
                options.TokenValidationParameters = new TokenValidationParameters
                {
                    ValidateIssuer = true,
                    ValidIssuer = settings.Issuer,
                    ValidateAudience = true,
                    ValidAudience = settings.Audience,
                    ValidateIssuerSigningKey = true,
                    IssuerSigningKey = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(keyText)),
                    ValidateLifetime = true,
                    ClockSkew = TimeSpan.FromMinutes(1),
                    NameClaimType = JwtRegisteredClaimNames.Sub,
                    RoleClaimType = "role",
                };
                options.Events = new JwtBearerEvents
                {
                    OnChallenge = async context =>
                    {
                        context.HandleResponse();
                        if (context.Response.HasStarted)
                        {
                            return;
                        }

                        var invalid = context.AuthenticateFailure is not null;
                        await ApiErrors.WriteAsync(
                            context.HttpContext,
                            StatusCodes.Status401Unauthorized,
                            invalid ? "INVALID_TOKEN" : "UNAUTHENTICATED",
                            invalid
                                ? "The access token is invalid or expired."
                                : "Sign in is required.");
                    },
                };
            });

        builder.Services.AddAuthorization();
    }
}
