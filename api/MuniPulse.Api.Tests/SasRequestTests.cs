using MuniPulse.Api.Data;

namespace MuniPulse.Api.Tests;

public class SasRequestTests
{
    [Fact]
    public void AcceptsAJpegUnderFiveMegabytes()
    {
        Assert.Empty(BlobPhotos.ValidateSas("image/jpeg", 1));
        Assert.Empty(BlobPhotos.ValidateSas("IMAGE/JPEG", BlobPhotos.MaxBytes));
    }

    [Fact]
    public void RejectsANonJpegContentType()
    {
        var fields = BlobPhotos.ValidateSas("image/png", 100);
        var failure = BlobPhotos.SasError(fields);

        Assert.Contains("contentType", fields.Keys);
        Assert.Equal(400, failure.Status);
        Assert.Equal("VALIDATION_ERROR", failure.Code);
    }

    [Fact]
    public void RejectsAMissingOrZeroSize()
    {
        var missing = BlobPhotos.SasError(BlobPhotos.ValidateSas("image/jpeg", null));
        var zero = BlobPhotos.SasError(BlobPhotos.ValidateSas("image/jpeg", 0));

        Assert.Equal(400, missing.Status);
        Assert.Equal(400, zero.Status);
    }

    [Fact]
    public void RejectsASizeOverFiveMegabytes()
    {
        var fields = BlobPhotos.ValidateSas("image/jpeg", BlobPhotos.MaxBytes + 1);
        var failure = BlobPhotos.SasError(fields);

        Assert.Contains("byteSize", fields.Keys);
        Assert.Equal(413, failure.Status);
        Assert.Equal("PAYLOAD_TOO_LARGE", failure.Code);
    }

    [Fact]
    public void KeepsTheLeaseAtFifteenMinutesOrLess()
    {
        Assert.True(BlobPhotos.SasLifetime.TotalMinutes <= 15);
        Assert.True(BlobPhotos.IsPhotoId("0123456789abcdef0123456789abcdef"));
        Assert.False(BlobPhotos.IsPhotoId("photo-1"));
    }
}
