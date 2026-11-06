using MuniPulse.Api.Data;
using MuniPulse.Api.Models;

namespace MuniPulse.Api.Tests;

public class EmergencyPointCatalogTests
{
    [Fact]
    public void EachDemoWardHasWaterAndEvacuationPoints()
    {
        foreach (var ward in WardCatalog.All)
        {
            var points = EmergencyPointCatalog.ForWard(ward.Code);
            Assert.Contains(points, point => point.Kind == EmergencyPointCatalog.Water);
            Assert.Contains(points, point => point.Kind == EmergencyPointCatalog.Evacuation);
            Assert.All(points, point =>
            {
                Assert.False(string.IsNullOrWhiteSpace(point.Id));
                Assert.False(string.IsNullOrWhiteSpace(point.Name));
                Assert.InRange(point.Latitude, -90, 90);
                Assert.InRange(point.Longitude, -180, 180);
            });
        }
    }

    [Fact]
    public void UnknownWardReturnsNoPoints()
    {
        Assert.Empty(EmergencyPointCatalog.ForWard("XXX-00"));
        Assert.Empty(EmergencyPointCatalog.ForWard(null));
        Assert.Equal(EmergencyPointCatalog.All.Count, EmergencyPointCatalog.All.Select(point => point.Id).Distinct().Count());
    }
}
