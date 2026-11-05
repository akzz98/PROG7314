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
        Assert.Equal("UNKNOWN_MUTATION", ApiEndpoints.PushConflictCode(Valid(mutationType: "renameIncident")));
        Assert.Equal("VALIDATION_ERROR", ApiEndpoints.PushConflictCode(Valid(clientMutationId: "")));
    }

    [Fact]
    public void KeepsAResolvedReportUnchanged()
    {
        var edit = new SyncOperationRequest
        {
            ClientMutationId = "device-uuid-1",
            MutationType = "editIncident",
            IncidentId = "6500inc001",
            Description = "A deep hole in the road",
        };

        Assert.Null(ApiEndpoints.PushConflictCode(edit));
        Assert.Equal("RESOLVED", ApiEndpoints.ResolvedEditCode("Resolved"));
        Assert.Null(ApiEndpoints.ResolvedEditCode("InProgress"));
        Assert.Null(ApiEndpoints.ResolvedEditCode("Submitted"));
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
