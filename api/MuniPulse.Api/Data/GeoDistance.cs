namespace MuniPulse.Api.Data;

// Near-duplicate window for UD-2. Same ward and category are checked by the query.
public static class DuplicateWindow
{
    public const double RadiusMeters = 250;

    public const int MaxAgeDays = 14;

    public static double MetersBetween(double latitudeA, double longitudeA, double latitudeB, double longitudeB)
    {
        const double earthRadius = 6_371_000;
        var latDelta = ToRadians(latitudeB - latitudeA);
        var lngDelta = ToRadians(longitudeB - longitudeA);
        var latA = ToRadians(latitudeA);
        var latB = ToRadians(latitudeB);
        var haversine = Math.Sin(latDelta / 2) * Math.Sin(latDelta / 2)
            + Math.Cos(latA) * Math.Cos(latB) * Math.Sin(lngDelta / 2) * Math.Sin(lngDelta / 2);
        return earthRadius * 2 * Math.Atan2(Math.Sqrt(haversine), Math.Sqrt(1 - haversine));
    }

    private static double ToRadians(double degrees) => degrees * Math.PI / 180;
}
