using MuniPulse.Api.Data;

namespace MuniPulse.Api.Tests;

public class DuplicateWindowTests
{
    [Fact]
    public void SamePointIsInsideTheWindow()
    {
        var meters = DuplicateWindow.MetersBetween(-26.2041, 28.0473, -26.2041, 28.0473);

        Assert.True(meters < 1);
        Assert.True(meters <= DuplicateWindow.RadiusMeters);
    }

    [Fact]
    public void TwoHundredMetresIsInsideAndFourHundredIsOutside()
    {
        const double originLat = -26.2041;
        const double originLng = 28.0473;
        var nearby = originLat + (200d / 111_320d);
        var far = originLat + (400d / 111_320d);

        Assert.True(DuplicateWindow.MetersBetween(originLat, originLng, nearby, originLng) <= DuplicateWindow.RadiusMeters);
        Assert.True(DuplicateWindow.MetersBetween(originLat, originLng, far, originLng) > DuplicateWindow.RadiusMeters);
    }
}
