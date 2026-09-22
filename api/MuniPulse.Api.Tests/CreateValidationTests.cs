using MuniPulse.Api.Endpoints;

namespace MuniPulse.Api.Tests;

public class CreateValidationTests
{
    [Fact]
    public void AcceptsACompleteReport()
    {
        var errors = ApiEndpoints.ValidateCreate(Valid());

        Assert.Empty(errors);
    }

    [Fact]
    public void AcceptsBoundsAndOptionalFields()
    {
        Assert.Empty(ApiEndpoints.ValidateCreate(Valid(description: new string('a', 10))));
        Assert.Empty(ApiEndpoints.ValidateCreate(Valid(description: new string('a', 1000))));
        Assert.Empty(ApiEndpoints.ValidateCreate(Valid(description: "  1234567890  ")));
        Assert.Empty(ApiEndpoints.ValidateCreate(Valid(accuracyMeters: null)));
        Assert.Empty(ApiEndpoints.ValidateCreate(Valid(accuracyMeters: 0)));
        Assert.Empty(ApiEndpoints.ValidateCreate(Valid(accuracyMeters: 10000)));
        Assert.Empty(ApiEndpoints.ValidateCreate(Valid(latitude: -90, longitude: -180)));
        Assert.Empty(ApiEndpoints.ValidateCreate(Valid(latitude: 90, longitude: 180)));
        Assert.Empty(ApiEndpoints.ValidateCreate(Valid(photoIds: [])));
        Assert.Empty(ApiEndpoints.ValidateCreate(Valid(clientMutationId: null)));
    }

    [Fact]
    public void RejectsEachInvalidField()
    {
        Assert.Contains("category", ApiEndpoints.ValidateCreate(Valid(category: "Hole")).Keys);
        Assert.Contains("description", ApiEndpoints.ValidateCreate(Valid(description: "too short")).Keys);
        Assert.Contains("description", ApiEndpoints.ValidateCreate(Valid(description: new string('a', 1001))).Keys);
        Assert.Contains("latitude", ApiEndpoints.ValidateCreate(Valid(latitude: null)).Keys);
        Assert.Contains("latitude", ApiEndpoints.ValidateCreate(Valid(latitude: 90.1)).Keys);
        Assert.Contains("longitude", ApiEndpoints.ValidateCreate(Valid(longitude: null)).Keys);
        Assert.Contains("longitude", ApiEndpoints.ValidateCreate(Valid(longitude: -180.1)).Keys);
        Assert.Contains("accuracyMeters", ApiEndpoints.ValidateCreate(Valid(accuracyMeters: -0.1)).Keys);
        Assert.Contains("accuracyMeters", ApiEndpoints.ValidateCreate(Valid(accuracyMeters: 10001)).Keys);
        Assert.Contains("wardCode", ApiEndpoints.ValidateCreate(Valid(wardCode: "JHB-99")).Keys);
        Assert.Contains("photoIds", ApiEndpoints.ValidateCreate(Valid(photoIds: ["a", "b", "c", "d"])).Keys);
        Assert.Contains("photoIds", ApiEndpoints.ValidateCreate(Valid(photoIds: [" "])).Keys);
        Assert.Contains("clientMutationId", ApiEndpoints.ValidateCreate(Valid(clientMutationId: " ")).Keys);
    }

    private static CreateIncidentRequest Valid(
        string? category = "Pothole",
        string? description = "Deep pothole outside the clinic gate",
        double? latitude = -26.2041,
        double? longitude = 28.0473,
        double? accuracyMeters = 12.5,
        string? wardCode = "JHB-23",
        List<string>? photoIds = null,
        string? clientMutationId = "device-uuid-1") =>
        new()
        {
            Category = category,
            Description = description,
            Latitude = latitude,
            Longitude = longitude,
            AccuracyMeters = accuracyMeters,
            WardCode = wardCode,
            PhotoIds = photoIds ?? ["photo-1"],
            ClientMutationId = clientMutationId,
        };
}
