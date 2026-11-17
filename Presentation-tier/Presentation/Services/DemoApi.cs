using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Presentation.Services;

public sealed class DemoApi(HttpClient http)
{
    public async Task<JsonNode?> RunAsync(string operation, object? data = null, string? token = null)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, $"api/demo/{operation}");
        request.Content = JsonContent.Create(data ?? new { });
        if (!string.IsNullOrWhiteSpace(token))
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);

        try
        {
            using var response = await http.SendAsync(request);
            var body = await response.Content.ReadAsStringAsync();
            JsonNode? json = null;
            if (!string.IsNullOrWhiteSpace(body))
                try { json = JsonNode.Parse(body); } catch (JsonException) { /* Report a friendly error below. */ }
            if (!response.IsSuccessStatusCode)
            {
                var message = (json as JsonObject)?["error"]?.ToString();
                throw new DemoApiException(message ?? "UniHome could not complete the request. Please try again.", response.StatusCode);
            }
            return json;
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        {
            throw new DemoApiException("Could not reach the UniHome service. Please check that the example stack is running.", HttpStatusCode.ServiceUnavailable);
        }
    }
}

public sealed class DemoApiException(string message, HttpStatusCode status) : Exception(message)
{
    public HttpStatusCode Status { get; } = status;
}

public sealed class DemoSession
{
    public string Token { get; private set; } = "";
    public string Id { get; private set; } = "";
    public string Name { get; private set; } = "";
    public string Email { get; private set; } = "";
    public string Role { get; private set; } = "";
    public bool IsTenant => SignedIn && Role == "TENANT";
    public bool IsLandlord => SignedIn && Role == "LANDLORD";
    public bool SignedIn => Token.Length != 0;

    public void Set(JsonNode? session)
    {
        Token = session?["token"]?.ToString() ?? "";
        Id = session?["id"]?.ToString() ?? "";
        Name = session?["name"]?.ToString() ?? "";
        Email = session?["email"]?.ToString() ?? "";
        Role = session?["role"]?.ToString() ?? "";
    }

    public void Clear() => Set(null);
}

