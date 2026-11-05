using Azure;
using Azure.Storage.Blobs;
using Azure.Storage.Blobs.Models;
using Azure.Storage.Sas;

namespace MuniPulse.Api.Data;

// Photo bytes live in Azure Blob. Mongo stores only the media record.
public sealed class BlobPhotos
{
    public const int MaxBytes = 5_000_000;
    public const string JpegContentType = "image/jpeg";
    public static readonly TimeSpan SasLifetime = TimeSpan.FromMinutes(15);

    private readonly BlobContainerClient? _container;
    private readonly ILogger<BlobPhotos> _logger;
    private readonly string _containerName;

    public BlobPhotos(IConfiguration configuration, ILogger<BlobPhotos> logger)
    {
        _logger = logger;
        _containerName = configuration["AzureBlob:Container"]?.Trim() ?? "";
        if (string.IsNullOrWhiteSpace(_containerName))
        {
            _containerName = "incident-photos";
        }

        var connectionString = configuration["AzureBlob:ConnectionString"];
        if (string.IsNullOrWhiteSpace(connectionString))
        {
            _logger.LogWarning("AzureBlob:ConnectionString is empty. Photo uploads are unavailable.");
            return;
        }

        try
        {
            _container = new BlobServiceClient(connectionString).GetBlobContainerClient(_containerName);
        }
        catch (Exception ex) when (ex is FormatException or ArgumentException)
        {
            _logger.LogError("AzureBlob:ConnectionString could not be parsed ({ExceptionType}).", ex.GetType().Name);
        }
    }

    public bool IsConfigured => _container is not null;

    public string ContainerName => _containerName;

    public static Dictionary<string, string> ValidateSas(string? contentType, long? byteSize)
    {
        var fields = new Dictionary<string, string>();
        if (!string.Equals(contentType?.Trim(), JpegContentType, StringComparison.OrdinalIgnoreCase))
        {
            fields["contentType"] = "Use image/jpeg.";
        }

        if (byteSize is null or <= 0)
        {
            fields["byteSize"] = "Send the JPEG size in bytes.";
        }
        else if (byteSize > MaxBytes)
        {
            fields["byteSize"] = "Each photo must be a JPEG under 5 MB.";
        }

        return fields;
    }

    public static (int Status, string Code, string Message) SasError(IReadOnlyDictionary<string, string> fields)
    {
        if (fields.TryGetValue("byteSize", out var size) && size.Contains("5 MB", StringComparison.Ordinal))
        {
            return (StatusCodes.Status413PayloadTooLarge, "PAYLOAD_TOO_LARGE", "Each photo must be a JPEG under 5 MB.");
        }

        return (StatusCodes.Status400BadRequest, "VALIDATION_ERROR", "One or more fields are invalid.");
    }

    public static bool IsPhotoId(string? id) =>
        id is { Length: 32 } && id.All(Uri.IsHexDigit);

    public static string BlobNameFor(string photoId) => photoId + ".jpg";

    public async Task<UploadLease?> IssueUploadAsync(string photoId, CancellationToken cancellationToken)
    {
        if (_container is null || !IsPhotoId(photoId))
        {
            return null;
        }

        try
        {
            await _container.CreateIfNotExistsAsync(PublicAccessType.None, cancellationToken: cancellationToken);
            var blob = _container.GetBlobClient(BlobNameFor(photoId));
            var expires = DateTimeOffset.UtcNow.Add(SasLifetime);
            var sas = new BlobSasBuilder
            {
                BlobContainerName = _containerName,
                BlobName = blob.Name,
                Resource = "b",
                StartsOn = DateTimeOffset.UtcNow.AddMinutes(-5),
                ExpiresOn = expires,
                Protocol = SasProtocol.Https,
                ContentType = JpegContentType,
            };
            sas.SetPermissions(BlobSasPermissions.Create | BlobSasPermissions.Write);
            var lease = new UploadLease(blob.GenerateSasUri(sas).ToString(), expires);
            _logger.LogInformation("Upload lease issued for photo {PhotoId}.", photoId);
            return lease;
        }
        catch (Exception ex) when (ex is RequestFailedException or InvalidOperationException or ArgumentException)
        {
            _logger.LogError("Blob upload lease failed ({ExceptionType}) for photo {PhotoId}.", ex.GetType().Name, photoId);
            return null;
        }
    }

    public string? ReadUrl(string? blobName)
    {
        if (_container is null || string.IsNullOrWhiteSpace(blobName) || blobName.Contains('/', StringComparison.Ordinal))
        {
            return null;
        }

        try
        {
            var blob = _container.GetBlobClient(blobName);
            var sas = new BlobSasBuilder
            {
                BlobContainerName = _containerName,
                BlobName = blob.Name,
                Resource = "b",
                StartsOn = DateTimeOffset.UtcNow.AddMinutes(-5),
                ExpiresOn = DateTimeOffset.UtcNow.Add(SasLifetime),
                Protocol = SasProtocol.Https,
            };
            sas.SetPermissions(BlobSasPermissions.Read);
            return blob.GenerateSasUri(sas).ToString();
        }
        catch (Exception ex) when (ex is RequestFailedException or InvalidOperationException or ArgumentException)
        {
            _logger.LogError("Blob read link failed ({ExceptionType}).", ex.GetType().Name);
            return null;
        }
    }

    public async Task<BlobCheck> ExistsAsync(string? blobName, CancellationToken cancellationToken)
    {
        if (_container is null || string.IsNullOrWhiteSpace(blobName))
        {
            return BlobCheck.Unavailable;
        }

        try
        {
            var found = await _container.GetBlobClient(blobName).ExistsAsync(cancellationToken);
            var status = found.Value ? BlobCheck.Present : BlobCheck.Missing;
            _logger.LogInformation("Blob check for {BlobName} is {BlobStatus}.", blobName, status);
            return status;
        }
        catch (Exception ex) when (ex is RequestFailedException or InvalidOperationException or ArgumentException)
        {
            _logger.LogError("Blob check failed ({ExceptionType}).", ex.GetType().Name);
            return BlobCheck.Unavailable;
        }
    }
}

public enum BlobCheck
{
    Present,
    Missing,
    Unavailable,
}

public sealed record UploadLease(string UploadUrl, DateTimeOffset ExpiresAt);
