using System.IdentityModel.Tokens.Jwt;
using System.Security.Cryptography.X509Certificates;
using Microsoft.IdentityModel.Tokens;
using MuniPulse.Api.Models;

namespace MuniPulse.Api.Auth;

// Verifies a Firebase ID token with Google's published certificates.
// https://firebase.google.com/docs/auth/admin/verify-id-tokens
public sealed class FirebaseIdTokenVerifier
{
    public const string HttpClientName = "firebase-certs";
    public const string CertificateUrl =
        "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com";

    private readonly IHttpClientFactory _httpClientFactory;
    private readonly ILogger<FirebaseIdTokenVerifier> _logger;
    private readonly string? _projectId;
    private readonly object _cacheGate = new();
    private Dictionary<string, SecurityKey>? _keys;
    private DateTimeOffset _keysExpiry = DateTimeOffset.MinValue;

    public FirebaseIdTokenVerifier(
        IHttpClientFactory httpClientFactory,
        IConfiguration configuration,
        ILogger<FirebaseIdTokenVerifier> logger)
    {
        _httpClientFactory = httpClientFactory;
        _logger = logger;
        _projectId = configuration["Firebase:ProjectId"];
    }

    public bool IsConfigured => !string.IsNullOrWhiteSpace(_projectId);

    public async Task<VerifiedIdentity?> VerifyAsync(string firebaseIdToken, CancellationToken cancellationToken)
    {
        if (!IsConfigured || _projectId is null)
        {
            _logger.LogWarning("Firebase:ProjectId is empty, so Google ID tokens cannot be verified.");
            return null;
        }

        try
        {
            var handler = new JwtSecurityTokenHandler();
            if (!handler.CanReadToken(firebaseIdToken))
            {
                return null;
            }

            var keys = await GetKeysAsync(cancellationToken);
            var parameters = new TokenValidationParameters
            {
                ValidateIssuer = true,
                ValidIssuer = $"https://securetoken.google.com/{_projectId}",
                ValidateAudience = true,
                ValidAudience = _projectId,
                ValidateLifetime = true,
                ValidateIssuerSigningKey = true,
                IssuerSigningKeys = keys.Values,
                ClockSkew = TimeSpan.FromMinutes(1),
                NameClaimType = "sub",
            };

            var principal = handler.ValidateToken(firebaseIdToken, parameters, out _);
            var uid = principal.FindFirst("user_id")?.Value ?? principal.FindFirst("sub")?.Value;
            var email = principal.FindFirst("email")?.Value;
            if (string.IsNullOrWhiteSpace(uid) || string.IsNullOrWhiteSpace(email))
            {
                return null;
            }

            var name = principal.FindFirst("name")?.Value;
            if (string.IsNullOrWhiteSpace(name))
            {
                name = email;
            }

            return new VerifiedIdentity(uid, email, name, UserRoles.Citizen);
        }
        catch (Exception ex) when (ex is SecurityTokenException or HttpRequestException or ArgumentException)
        {
            _logger.LogWarning("Firebase ID token rejected: {Reason}", ex.GetType().Name);
            return null;
        }
    }

    private async Task<Dictionary<string, SecurityKey>> GetKeysAsync(CancellationToken cancellationToken)
    {
        lock (_cacheGate)
        {
            if (_keys is not null && DateTimeOffset.UtcNow < _keysExpiry)
            {
                return _keys;
            }
        }

        var client = _httpClientFactory.CreateClient(HttpClientName);
        using var response = await client.GetAsync(CertificateUrl, cancellationToken);
        response.EnsureSuccessStatusCode();
        var certificates = await response.Content.ReadFromJsonAsync<Dictionary<string, string>>(cancellationToken)
            ?? [];
        var keys = new Dictionary<string, SecurityKey>();
        foreach (var (keyId, pem) in certificates)
        {
            var certificate = X509Certificate2.CreateFromPem(pem);
            keys[keyId] = new X509SecurityKey(certificate) { KeyId = keyId };
        }

        var lifetime = TimeSpan.FromHours(1);
        if (response.Headers.CacheControl?.MaxAge is TimeSpan maxAge && maxAge > TimeSpan.Zero)
        {
            lifetime = maxAge;
        }

        lock (_cacheGate)
        {
            _keys = keys;
            _keysExpiry = DateTimeOffset.UtcNow.Add(lifetime);
            return _keys;
        }
    }
}

public sealed class SessionIdTokenVerifier : IIdTokenVerifier
{
    private readonly DevBypassIdTokenVerifier _devBypass;
    private readonly FirebaseIdTokenVerifier _firebase;

    public SessionIdTokenVerifier(DevBypassIdTokenVerifier devBypass, FirebaseIdTokenVerifier firebase)
    {
        _devBypass = devBypass;
        _firebase = firebase;
    }

    public async Task<VerifiedIdentity?> VerifyAsync(string firebaseIdToken, CancellationToken cancellationToken)
    {
        var developmentIdentity = _devBypass.Verify(firebaseIdToken);
        if (developmentIdentity is not null)
        {
            return developmentIdentity;
        }

        return await _firebase.VerifyAsync(firebaseIdToken, cancellationToken);
    }
}
