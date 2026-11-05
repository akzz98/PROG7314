using MuniPulse.Api.Endpoints;

namespace MuniPulse.Api.Tests;

public class FcmTokenTests
{
    [Fact]
    public void AcceptsAnAndroidToken()
    {
        Assert.Empty(ApiEndpoints.ValidateFcmToken(new FcmTokenRequest
        {
            Token = "fcm-demo-token",
            Platform = "android",
        }));
    }

    [Fact]
    public void RejectsABlankTokenOrUnknownPlatform()
    {
        Assert.Contains("token", ApiEndpoints.ValidateFcmToken(new FcmTokenRequest
        {
            Token = " ",
            Platform = "android",
        }).Keys);
        Assert.Contains("platform", ApiEndpoints.ValidateFcmToken(new FcmTokenRequest
        {
            Token = "fcm-demo-token",
            Platform = "ios",
        }).Keys);
    }
}
