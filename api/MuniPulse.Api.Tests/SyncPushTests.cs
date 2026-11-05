using MuniPulse.Api.Endpoints;

namespace MuniPulse.Api.Tests;

public class SyncPushTests
{
    [Fact]
    public void AcceptsABatchOfOneToTwenty()
    {
        Assert.Null(ApiEndpoints.PushEnvelopeError(1));
        Assert.Null(ApiEndpoints.PushEnvelopeError(20));
    }

    [Fact]
    public void RejectsAnEmptyOrOversizedBatch()
    {
        Assert.NotNull(ApiEndpoints.PushEnvelopeError(null));
        Assert.NotNull(ApiEndpoints.PushEnvelopeError(0));
        Assert.NotNull(ApiEndpoints.PushEnvelopeError(21));
    }

    [Fact]
    public void AcceptsACreateAndRejectsAnythingElse()
    {
        Assert.Null(ApiEndpoints.PushConflictCode(Valid()));
        Assert.Equal("UNKNOWN_MUTATION", ApiEndpoints.PushConflictCode(Valid(mutationType: "editIncident")));
        Assert.Equal("VALIDATION_ERROR", ApiEndpoints.PushConflictCode(Valid(clientMutationId: "")));
    }

    private static SyncOperationRequest Valid(string mutationType = "createIncident", string clientMutationId = "device-uuid-1") =>
        new()
        {
            ClientMutationId = clientMutationId,
            MutationType = mutationType,
            Category = "Pothole",
            Description = "A deep hole in the road",
            Latitude = -26.2,
            Longitude = 28.04,
            AccuracyMeters = 8,
            WardCode = "JHB-23",
            PhotoIds = [],
        };
}
