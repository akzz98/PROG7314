namespace MuniPulse.Api.Data;

// Part 2 photo bytes live on the API machine. Azure Blob is Final POE.
public sealed class PhotoStore
{
    public const int MaxBytes = 5_000_000;

    private readonly string _directory;
    private readonly ILogger<PhotoStore> _logger;

    public PhotoStore(IWebHostEnvironment environment, ILogger<PhotoStore> logger)
    {
        _directory = Path.Combine(environment.ContentRootPath, "App_Data", "incident-photos");
        Directory.CreateDirectory(_directory);
        _logger = logger;
    }

    public async Task<IReadOnlyList<string>> SaveJpegsAsync(
        IReadOnlyList<byte[]> files,
        CancellationToken cancellationToken)
    {
        var ids = new List<string>(files.Count);
        foreach (var bytes in files)
        {
            var id = Guid.NewGuid().ToString("N");
            var path = Path.Combine(_directory, id + ".jpg");
            await File.WriteAllBytesAsync(path, bytes, cancellationToken);
            ids.Add(id);
            _logger.LogInformation("Stored incident photo {PhotoId}", id);
        }

        return ids;
    }

    public string? FileFor(string? id)
    {
        if (!IsPhotoId(id))
        {
            return null;
        }

        var path = Path.GetFullPath(Path.Combine(_directory, id + ".jpg"));
        var root = Path.GetFullPath(_directory + Path.DirectorySeparatorChar);
        if (!path.StartsWith(root, StringComparison.OrdinalIgnoreCase) || !File.Exists(path))
        {
            return null;
        }

        return path;
    }

    public static bool IsJpeg(ReadOnlySpan<byte> bytes) =>
        bytes.Length is > 2 and <= MaxBytes &&
        bytes[0] == 0xFF &&
        bytes[1] == 0xD8 &&
        bytes[2] == 0xFF;

    public static bool IsPhotoId(string? id) =>
        id is { Length: 32 } && id.All(Uri.IsHexDigit);
}
