using System.Text.Json.Serialization;

namespace MuniPulse.Api.Errors;

public sealed class ApiException : Exception
{
    public ApiException(int statusCode, string code, string message, Dictionary<string, string>? fields = null)
        : base(message)
    {
        StatusCode = statusCode;
        Code = code;
        Fields = fields;
    }

    public int StatusCode { get; }

    public string Code { get; }

    public Dictionary<string, string>? Fields { get; }
}

public static class ApiErrors
{
    public static IResult Result(
        HttpContext http,
        int statusCode,
        string code,
        string message,
        Dictionary<string, string>? fields = null)
    {
        return Results.Json(Body(http, code, message, fields), statusCode: statusCode);
    }

    public static Task WriteAsync(
        HttpContext http,
        int statusCode,
        string code,
        string message,
        Dictionary<string, string>? fields = null)
    {
        http.Response.StatusCode = statusCode;
        http.Response.ContentType = "application/json";
        return http.Response.WriteAsJsonAsync(Body(http, code, message, fields));
    }

    private static ErrorEnvelope Body(
        HttpContext http,
        string code,
        string message,
        Dictionary<string, string>? fields) =>
        new(new ErrorDetail(code, message, fields, http.TraceIdentifier));
}

// Planning section F error envelope.
public sealed record ErrorEnvelope(ErrorDetail Error);

public sealed record ErrorDetail(
    string Code,
    string Message,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    Dictionary<string, string>? Fields,
    string CorrelationId);
