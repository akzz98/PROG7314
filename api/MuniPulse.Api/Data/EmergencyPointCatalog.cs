namespace MuniPulse.Api.Data;

public sealed record EmergencyPointSeed(
    string Id,
    string WardCode,
    string Kind,
    string Name,
    double Latitude,
    double Longitude);

// Seeded water and evacuation sites for the demo wards. No map provider key.
public static class EmergencyPointCatalog
{
    public const string Water = "Water";
    public const string Evacuation = "Evacuation";

    public static readonly IReadOnlyList<EmergencyPointSeed> All =
    [
        new("JHB-23-water", "JHB-23", Water, "Yeoville water point", -26.1890, 28.0630),
        new("JHB-23-evac", "JHB-23", Evacuation, "Hillbrow evacuation site", -26.1960, 28.0470),
        new("JHB-24-water", "JHB-24", Water, "Bellevue water point", -26.1780, 28.0820),
        new("JHB-24-evac", "JHB-24", Evacuation, "Observatory evacuation site", -26.1850, 28.0900),
        new("CPT-11-water", "CPT-11", Water, "Woodstock water point", -33.9270, 18.4450),
        new("CPT-11-evac", "CPT-11", Evacuation, "Salt River evacuation site", -33.9340, 18.4560),
        new("DBN-07-water", "DBN-07", Water, "Berea water point", -29.8520, 31.0050),
        new("DBN-07-evac", "DBN-07", Evacuation, "Musgrave evacuation site", -29.8460, 31.0000),
        new("TSH-04-water", "TSH-04", Water, "Sunnyside water point", -25.7620, 28.2040),
        new("TSH-04-evac", "TSH-04", Evacuation, "Arcadia evacuation site", -25.7460, 28.2100),
    ];

    public static IReadOnlyList<EmergencyPointSeed> ForWard(string? wardCode)
    {
        var code = wardCode?.Trim() ?? "";
        return All.Where(point => point.WardCode == code).ToArray();
    }
}
