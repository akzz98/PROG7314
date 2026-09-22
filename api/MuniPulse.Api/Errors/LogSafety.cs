using Microsoft.IdentityModel.Logging;

namespace MuniPulse.Api.Errors;

// NFR-02 and NFR-04: the JWT library must not print tokens or other security artifacts.
public static class LogSafety
{
    public static void DisableTokenLogging()
    {
        IdentityModelEventSource.ShowPII = false;
        IdentityModelEventSource.LogCompleteSecurityArtifact = false;
    }
}
