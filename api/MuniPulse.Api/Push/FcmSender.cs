using FirebaseAdmin;
using FirebaseAdmin.Messaging;
using Google.Apis.Auth.OAuth2;

namespace MuniPulse.Api.Push;

// Sends through FCM HTTP v1 via the Admin SDK. Empty credentials skip the send.
public sealed class FcmSender
{
    private readonly ILogger<FcmSender> _logger;
    private readonly bool _configured;

    public FcmSender(IConfiguration configuration, ILogger<FcmSender> logger)
    {
        _logger = logger;
        var json = configuration["Firebase:ServiceAccountJson"];
        if (string.IsNullOrWhiteSpace(json))
        {
            _logger.LogWarning("Firebase:ServiceAccountJson is empty. Push sends are skipped.");
            return;
        }

        try
        {
            if (FirebaseApp.DefaultInstance is null)
            {
                FirebaseApp.Create(new AppOptions
                {
                    Credential = GoogleCredential.FromJson(json),
                });
            }

            _configured = true;
            _logger.LogInformation("FCM sender is configured.");
        }
        catch (Exception ex)
        {
            _logger.LogError("FCM sender could not start ({ExceptionType}).", ex.GetType().Name);
        }
    }

    public bool IsConfigured => _configured;

    public async Task SendAsync(
        string deviceToken,
        string title,
        string body,
        IReadOnlyDictionary<string, string> data,
        CancellationToken cancellationToken)
    {
        if (!_configured)
        {
            _logger.LogInformation("FCM send skipped. Service account is not configured.");
            return;
        }

        if (string.IsNullOrWhiteSpace(deviceToken))
        {
            _logger.LogInformation("FCM send skipped. Device token was empty.");
            return;
        }

        try
        {
            var payload = data.ToDictionary(pair => pair.Key, pair => pair.Value);
            payload["title"] = title;
            payload["body"] = body;
            var message = new Message
            {
                Token = deviceToken,
                Data = payload,
                Android = new AndroidConfig
                {
                    Priority = Priority.High,
                },
            };
            await FirebaseMessaging.DefaultInstance.SendAsync(message, cancellationToken);
            _logger.LogInformation("FCM message accepted for delivery.");
        }
        catch (Exception ex)
        {
            _logger.LogError("FCM send failed ({ExceptionType}).", ex.GetType().Name);
        }
    }
}
