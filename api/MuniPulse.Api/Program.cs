using System.Threading.RateLimiting;
using MuniPulse.Api.Auth;
using MuniPulse.Api.Data;
using MuniPulse.Api.Endpoints;
using MuniPulse.Api.Errors;
using Microsoft.AspNetCore.Http.Json;

LogSafety.DisableTokenLogging();

var builder = WebApplication.CreateBuilder(args);

builder.Services.AddOpenApi();
builder.Services.Configure<JsonOptions>(options =>
{
    options.SerializerOptions.PropertyNameCaseInsensitive = true;
});
builder.Services.AddSingleton<IncidentStore>();
builder.Services.AddSingleton<PhotoStore>();
builder.Services.Configure<Microsoft.AspNetCore.Http.Features.FormOptions>(options =>
{
    options.MultipartBodyLengthLimit = PhotoStore.MaxBytes * 3L;
});
builder.AddApiAuth();
builder.Services.AddRateLimiter(options =>
{
    options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
    options.OnRejected = async (context, _) =>
    {
        if (!context.HttpContext.Response.HasStarted)
        {
            await ApiErrors.WriteAsync(
                context.HttpContext,
                StatusCodes.Status429TooManyRequests,
                "RATE_LIMITED",
                "Too many requests. Wait a moment and try again.");
        }
    };
    options.AddPolicy("writes", httpContext =>
        RateLimitPartition.GetFixedWindowLimiter(
            httpContext.User.FindFirst("sub")?.Value
                ?? httpContext.Connection.RemoteIpAddress?.ToString()
                ?? "anonymous",
            _ => new FixedWindowRateLimiterOptions
            {
                PermitLimit = 30,
                Window = TimeSpan.FromMinutes(1),
                QueueLimit = 0,
            }));
});

var app = builder.Build();

app.Use(async (context, next) =>
{
    context.Response.Headers["X-Correlation-Id"] = context.TraceIdentifier;
    var logger = context.RequestServices.GetRequiredService<ILoggerFactory>().CreateLogger("MuniPulse.Http");
    try
    {
        await next();
    }
    catch (ApiException ex)
    {
        logger.LogWarning(
            "API error {ErrorCode} on {Method} {Path}. Correlation {CorrelationId}",
            ex.Code,
            context.Request.Method,
            context.Request.Path.Value,
            context.TraceIdentifier);
        if (!context.Response.HasStarted)
        {
            await ApiErrors.WriteAsync(context, ex.StatusCode, ex.Code, ex.Message, ex.Fields);
        }
    }
    catch (BadHttpRequestException)
    {
        if (!context.Response.HasStarted)
        {
            await ApiErrors.WriteAsync(
                context,
                StatusCodes.Status400BadRequest,
                "VALIDATION_ERROR",
                "The request body is not valid JSON.");
        }
    }
    catch (Exception ex)
    {
        logger.LogError(
            "Unhandled API error {ExceptionType}. Correlation {CorrelationId}",
            ex.GetType().Name,
            context.TraceIdentifier);
        if (!context.Response.HasStarted)
        {
            await ApiErrors.WriteAsync(
                context,
                StatusCodes.Status500InternalServerError,
                "UNEXPECTED",
                "Something went wrong. Try again.");
        }
    }
    finally
    {
        if (!context.Request.Path.StartsWithSegments("/health"))
        {
            logger.LogInformation(
                "HTTP {Method} {Path} responded {StatusCode}. Correlation {CorrelationId}",
                context.Request.Method,
                context.Request.Path.Value,
                context.Response.StatusCode,
                context.TraceIdentifier);
        }
    }
});

if (app.Environment.IsDevelopment())
{
    app.MapOpenApi();
}

app.UseHttpsRedirection();
app.UseAuthentication();
app.UseAuthorization();
app.UseRateLimiter();

app.MapGet("/health", () => Results.Ok(new HealthResponse("ok", "MuniPulse SA")))
    .WithName("GetHealth");

app.MapMuniPulseApi();

var store = app.Services.GetRequiredService<IncidentStore>();
var bypass = app.Services.GetRequiredService<DevBypassIdTokenVerifier>();
var jwt = app.Services.GetRequiredService<JwtSettings>();
app.Logger.LogInformation(
    "MuniPulse SA API starting. Mongo configured: {MongoConfigured}. Dev session bypass: {DevBypass}. JWT configured: {JwtConfigured}. Field worker key configured: {FieldWorkerKeyConfigured}",
    store.IsConfigured,
    bypass?.IsActive == true,
    jwt.IsConfigured,
    !string.IsNullOrWhiteSpace(app.Configuration["FieldWorker:DemoApiKey"]));

try
{
    await store.SeedAsync(CancellationToken.None);
}
catch (Exception ex)
{
    app.Logger.LogError(
        "MongoDB seed failed ({ExceptionType}). Data routes will report a database error until the cluster is reachable.",
        ex.GetType().Name);
}

app.Run();

internal sealed record HealthResponse(string Status, string App);

public partial class Program
{
}
