using Presentation.Models;

namespace Presentation.Services;

// calls the application tier REST API
public class ListingService(HttpClient http)
{
    public async Task<List<Listing>> GetListingsAsync()
    {
        return await http.GetFromJsonAsync<List<Listing>>("api/listings") ?? [];
    }

    public async Task CreateListingAsync(CreateListingRequest listing)
    {
        var response = await http.PostAsJsonAsync("api/listings", listing);
        response.EnsureSuccessStatusCode();
    }
}