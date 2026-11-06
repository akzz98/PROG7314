using MuniPulse.Api.Data;
using MuniPulse.Api.Models;

namespace MuniPulse.Api.Push;

public sealed class PushDispatch
{
    private readonly IncidentStore _store;
    private readonly FcmSender _sender;
    private readonly ILogger<PushDispatch> _logger;

    public PushDispatch(IncidentStore store, FcmSender sender, ILogger<PushDispatch> logger)
    {
        _store = store;
        _sender = sender;
        _logger = logger;
    }

    public async Task NotifyTicketAsync(
        IncidentReport incident,
        string actorId,
        string milestoneType,
        CancellationToken cancellationToken)
    {
        var reporter = await _store.FindUserByIdAsync(incident.ReporterId, cancellationToken);
        if (reporter is null)
        {
            _logger.LogInformation("Ticket push skipped. Reporter was not found.");
            return;
        }

        if (!PushPolicy.ShouldSendTicketPush(reporter.Id, actorId, reporter.Notifications.TicketStatus))
        {
            _logger.LogInformation("Ticket push skipped. Preference or actor blocked it.");
            return;
        }

        var copy = PushCopy.Ticket(reporter.PreferredLanguage, milestoneType);
        var data = new Dictionary<string, string>
        {
            ["type"] = "ticket",
            ["incidentId"] = incident.Id,
        };
        await SendToUserAsync(reporter.Id, copy.Title, copy.Body, data, cancellationToken);
    }

    public async Task NotifyAreaAsync(
        string wardCode,
        string note,
        CancellationToken cancellationToken)
    {
        var users = await _store.ListUsersByDefaultWardAsync(wardCode, cancellationToken);
        var sent = 0;
        foreach (var user in users)
        {
            if (!PushPolicy.ShouldSendAreaPush(user.Notifications.AreaEmergencies))
            {
                continue;
            }

            var copy = PushCopy.Area(user.PreferredLanguage, wardCode, note);
            var data = new Dictionary<string, string>
            {
                ["type"] = "area",
                ["wardCode"] = wardCode,
            };
            await SendToUserAsync(user.Id, copy.Title, copy.Body, data, cancellationToken);
            sent += 1;
        }

        _logger.LogInformation("Area push finished for ward {WardCode}. Recipients {RecipientCount}", wardCode, sent);
    }

    private async Task SendToUserAsync(
        string userId,
        string title,
        string body,
        IReadOnlyDictionary<string, string> data,
        CancellationToken cancellationToken)
    {
        var tokens = await _store.ListDeviceTokensForUserAsync(userId, cancellationToken);
        if (tokens.Count == 0)
        {
            _logger.LogInformation("Push skipped. No device token for the user.");
            return;
        }

        foreach (var device in tokens)
        {
            await _sender.SendAsync(device.Token, title, body, data, cancellationToken);
        }
    }
}
