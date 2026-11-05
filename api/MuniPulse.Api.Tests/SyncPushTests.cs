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
        Assert.Equal("VALIDATION_ERROR", ApiEndpoints.PushConflictCode(Valid(category: "Hole")));
    }

    [Fact]
    public void AcceptsAnEditWithAnIncidentAndDescription()
    {
        Assert.Null(ApiEndpoints.PushConflictCode(Edit()));
        Assert.Equal("VALIDATION_ERROR", ApiEndpoints.PushConflictCode(Edit(incidentId: "")));
        Assert.Equal("VALIDATION_ERROR", ApiEndpoints.PushConflictCode(Edit(description: "too short")));
        Assert.Equal("VALIDATION_ERROR", ApiEndpoints.PushConflictCode(Edit(clientMutationId: "")));
    }

    [Fact]
    public void KeepsAResolvedReportUnchanged()
    {
        Assert.Equal("RESOLVED", ApiEndpoints.ResolvedEditCode("Resolved"));
        Assert.Null(ApiEndpoints.ResolvedEditCode("InProgress"));
        Assert.Null(ApiEndpoints.ResolvedEditCode("Submitted"));
        Assert.Null(ApiEndpoints.ResolvedEditCode(null));
    }

    [Fact]
    public void NamesAckAndConflictResults()
    {
        Assert.Equal("ack", SyncResults.Ack);
        Assert.Equal("conflict", SyncResults.Conflict);
    }

    private static SyncOperationRequest Valid(
        string mutationType = "createIncident",
        string clientMutationId = "device-uuid-1",
        string category = "Pothole") =>
        new()
        {
            ClientMutationId = clientMutationId,
            MutationType = mutationType,
            Category = category,
            Description = "A deep hole in the road",
            Latitude = -26.2,
            Longitude = 28.04,
            AccuracyMeters = 8,
            WardCode = "JHB-23",
            PhotoIds = [],
        };

    private static SyncOperationRequest Edit(
        string clientMutationId = "device-uuid-1",
        string incidentId = "6500inc001",
        string description = "A deep hole in the road") =>
        new()
        {
            ClientMutationId = clientMutationId,
            MutationType = "editIncident",
            IncidentId = incidentId,
            Description = description,
        };
}
