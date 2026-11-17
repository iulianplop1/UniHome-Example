using System.Globalization;
using System.Net;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Components;
using Presentation.Services;

namespace Presentation.Components.Pages;

public partial class Home
{
    [Inject] private DemoApi Api { get; set; } = default!;
    [Inject] private DemoSession Session { get; set; } = default!;

    private string Screen = "browse";
    private string Error = "";
    private string Success = "";
    private bool Busy;
    private string City = "";
    private decimal? MinRent;
    private decimal? MaxRent;
    private string Date = "";
    private int? MinSize;
    private string Furnished = "";
    private string Name = "";
    private string Email = "";
    private string Password = "";
    private string Role = "TENANT";
    private string Message = "";
    private string AnnouncementTitle = "";
    private string AnnouncementText = "";
    private string EndDate = "";
    private string Today = "";
    private bool ShowFilters;
    private bool Approved;
    private bool PaymentConfirm;
    private bool IsProblem;
    private bool ShowAnnouncementForm;
    private bool ShowCancellation;
    private JsonNode? TargetInvoice;
    private JsonNode? SelectedListing;
    private JsonNode? SelectedUnit;
    private JsonNode? ChosenQuote;
    private JsonNode? SelectedRental;
    private JsonNode? SelectedFeed;
    private JsonNode? SelectedNotification;
    private JsonArray Catalog = [];
    private JsonArray MyListings = [];
    private JsonArray Rentals = [];
    private JsonArray SavedItems = [];
    private JsonArray Notifications = [];
    private JsonArray Messages = [];
    private JsonArray FeedItems = [];

    private ListingDraft Draft = new();
    private UnitDraft Unit = new();
    private PeriodDraft Period = new();
    private string EditMode = "";
    private string ConfirmOperation = "";
    private string ConfirmId = "";

    protected override async Task OnInitializedAsync() =>
        await Run(async () =>
        {
            Today = S(await Call("ping"), "today");
            await LoadBrowse();
        });

    private Task<JsonNode?> Call(string op, object? data = null) =>
        Api.RunAsync(op, data, Session.Token);

    private async Task Run(Func<Task> action, string? message = null)
    {
        if (Busy) return;
        Busy = true;
        Error = "";
        Success = "";
        try
        {
            await action();
            if (message is not null) Success = message;
        }
        catch (DemoApiException ex)
        {
            Error = ex.Message;
            if (ex.Status == HttpStatusCode.Unauthorized && Session.SignedIn)
            {
                Session.Clear();
                ClearPrivate();
                Screen = "signin";
            }
        }
        catch (Exception ex) when (ex is FormatException or OverflowException)
        {
            Error = "One of the values is outside the supported range.";
        }
        finally { Busy = false; }
    }

    private void ClearPrivate()
    {
        MyListings = [];
        Rentals = [];
        Notifications = [];
        SavedItems = [];
        SelectedRental = null;
        SelectedFeed = null;
        ChosenQuote = null;
        Messages = [];
        FeedItems = [];
        SelectedNotification = null;
        SelectedListing = null;
        SelectedUnit = null;
        TargetInvoice = null;
        Draft = new();
        Unit = new();
        Period = new();
        Message = AnnouncementTitle = AnnouncementText = EndDate = EditMode = "";
        ShowAnnouncementForm = ShowCancellation = IsProblem = Approved = PaymentConfirm = false;
        CloseConfirmation();
    }

    private async Task LoadBrowse()
    {
        var query = new Dictionary<string, object>();
        if (!string.IsNullOrWhiteSpace(City)) query["city"] = City.Trim();
        if (MinRent.HasValue) query["minRent"] = Ore(MinRent.Value);
        if (MaxRent.HasValue) query["maxRent"] = Ore(MaxRent.Value);
        if (!string.IsNullOrEmpty(Date)) query["date"] = Date;
        if (MinSize.HasValue) query["minSize"] = MinSize.Value;
        if (bool.TryParse(Furnished, out var furnished)) query["furnished"] = furnished;
        Catalog = Arr(await Call("catalog", query), "listings");
    }

    private Task Navigate(string screen) => Run(async () =>
    {
        CloseConfirmation();
        SelectedNotification = null;
        switch (screen)
        {
            case "browse": await LoadBrowse(); break;
            case "manage":
                MyListings = Arr(await Call("owned"), "listings");
                Rentals = Arr(await Call("rentals"), "rentals");
                SelectedListing = null; EditMode = ""; break;
            case "rentals":
                await LoadRentals(); SelectedRental = null; break;
            case "saved":
                SavedItems = Arr(await Call("saved"), "items"); break;
            case "notifications":
                Notifications = Arr(await Call("notifications"), "items"); break;
            case "feed":
                await LoadRentals(); SelectedFeed = null;
                if (Session.IsLandlord) MyListings = Arr(await Call("owned"), "listings");
                break;
        }
        Screen = screen;
    });

    private void AuthScreen(string screen)
    {
        Error = ""; Success = ""; Screen = screen; Password = ""; Name = "";
    }

    private Task Authenticate() => Run(async () =>
    {
        var newAccount = Screen == "register";
        var result = await Call(newAccount ? "register" : "login", new { name = Name, email = Email, password = Password, role = Role });
        Password = "";
        if (newAccount && string.IsNullOrEmpty(S(result, "token")))
        {
            Screen = "signin"; Success = "Your account is ready. You can sign in now."; return;
        }
        Session.Set(result);
        if (Session.IsLandlord)
        {
            Screen = "manage";
            MyListings = Arr(await Call("owned"), "listings");
            await LoadRentals();
        }
        else
        {
            Screen = "rentals";
            await LoadRentals();
        }
        Success = $"Welcome, {Session.Name}.";
    });

    private Task Logout() => Run(async () =>
    {
        try { await Call("logout"); }
        finally { Session.Clear(); ClearPrivate(); Password = ""; Email = ""; Screen = "browse"; }
        await LoadBrowse();
    });

    private async Task ResetFilters()
    {
        City = ""; MinRent = null; MaxRent = null; Date = ""; MinSize = null; Furnished = "";
        await Run(LoadBrowse);
    }

    private Task SelectListing(string id, string unitId = "") => Run(async () =>
    {
        if (id.Length == 0) { Error = "This listing is no longer available."; return; }
        SelectedListing = await Call("listing", new { id });
        SelectedUnit = unitId.Length == 0 ? null : Items(SelectedListing, "units").FirstOrDefault(x => S(x, "id") == unitId);
        ChosenQuote = null;
        Approved = false;
        PaymentConfirm = false;
        Screen = "details";
    });

    private Task SaveItem(string id, bool unit = false) => Run(async () =>
    {
        await Call("save", unit ? new { unitId = id } : (object)new { listingId = id });
    }, "Saved to your list.");

    private Task UnsaveItem(JsonNode item) => Run(async () =>
    {
        var unitId = S(item, "unitId");
        await Call("unsave", unitId.Length == 0 ? new { listingId = S(item, "listingId") } : (object)new { unitId });
        SavedItems = Arr(await Call("saved"), "items");
    }, "Removed from your saved list.");

    private void UseUnit(JsonNode unit)
    {
        SelectedUnit = unit;
        ChosenQuote = null;
        Approved = false;
        PaymentConfirm = false;
        Error = "";
        Success = "";
    }

    private Task RequestQuote(string id) => Run(async () =>
    {
        Approved = false; PaymentConfirm = false;
        ChosenQuote = await Call("quote", new { periodId = id });
    });

    private Task Book() => Run(async () =>
    {
        var quoteId = S(ChosenQuote, "id");
        if (!Approved || !PaymentConfirm || quoteId.Length == 0) { Error = "Accept the contract and confirm the simulated payment first."; return; }
        var result = await Call("book", new { quoteId, accepted = true, confirmed = true });
        ChosenQuote = null; Approved = false; PaymentConfirm = false;
        await LoadRentals(S(result, "id"));
        Screen = "rentals"; Success = "Rental confirmed. Your contract and simulated payment appear below.";
    });

    private async Task LoadRentals(string selection = "")
    {
        Rentals = Arr(await Call("rentals"), "rentals");
        SelectedRental = selection.Length == 0 ? null : Rentals.FirstOrDefault(n => S(n, "id") == selection);
    }

    private void ChooseRental(JsonNode rental)
    {
        SelectedRental = rental; ShowCancellation = false; TargetInvoice = null; EndDate = ""; Error = ""; Success = "";
    }

    private Task Pay() => Run(async () =>
    {
        var id = S(TargetInvoice, "id");
        if (id.Length == 0 || !PaymentConfirm) { Error = "Please confirm the simulated payment."; return; }
        var rentalId = S(SelectedRental, "id");
        await Call("pay", new { id, confirmed = true });
        TargetInvoice = null; PaymentConfirm = false;
        await LoadRentals(rentalId);
        Success = "Payment confirmed (simulation).";
    });

    private Task CancelRental() => Run(async () =>
    {
        var id = S(SelectedRental, "id");
        await Call("cancel", new { id, end = EndDate });
        await LoadRentals(id);
        ShowCancellation = false;
    }, "Notice recorded. Review the new rental end date and remaining charges.");

    private Task OpenConversation(JsonNode rental, bool problem = false) => Run(async () =>
    {
        SelectedRental = rental;
        Messages = Arr(await Call("messages", new { rentalId = S(rental, "id") }), "messages");
        Message = ""; IsProblem = problem; Screen = "conversation";
    });

    private Task SendMessage() => Run(async () =>
    {
        var rentalId = S(SelectedRental, "id");
        await Call("send", new { rentalId, text = Message, problem = IsProblem });
        Message = ""; IsProblem = false;
        Messages = Arr(await Call("messages", new { rentalId }), "messages");
    }, "Message sent.");

    private Task ReadNotice(JsonNode notice) => Run(async () =>
    {
        await Call("read-notification", new { id = S(notice, "id") });
        SelectedNotification = notice;
        Notifications = Arr(await Call("notifications"), "items");
    });

    private async Task LoadFeed(string listingId) =>
        FeedItems = Arr(await Call("feed", new { listingId }), "items");

    private Task OpenFeed(JsonNode item, bool rented) => Run(async () =>
    {
        var listingId = S(item, rented ? "listingId" : "id");
        await LoadFeed(listingId);
        SelectedFeed = item;
        ShowAnnouncementForm = false;
        AnnouncementTitle = ""; AnnouncementText = "";
        Screen = "feed";
    });

    private Task PublishAnnouncement() => Run(async () =>
    {
        var listingId = S(SelectedFeed, "id");
        await Call("announce", new { listingId, title = AnnouncementTitle, text = AnnouncementText });
        await LoadFeed(listingId);
        ShowAnnouncementForm = false; AnnouncementTitle = ""; AnnouncementText = "";
    }, "Announcement published.");

    private void StartListing()
    {
        SelectedListing = null;
        Draft = new ListingDraft();
        EditMode = "create";
        Error = ""; Success = "";
        Screen = "manage";
    }

    private void ManageListing(JsonNode listing)
    {
        SelectedListing = listing;
        Draft = new ListingDraft
        {
            Id = S(listing, "id"), Type = S(listing, "type"), Name = S(listing, "name"),
            Description = S(listing, "description"), EnglishDescription = S(listing, "englishDescription"),
            City = S(listing, "city"), Address = S(listing, "address")
        };
        Error = ""; Success = ""; EditMode = ""; CloseConfirmation();
    }

    private async Task ReloadOwned(string id = "")
    {
        MyListings = Arr(await Call("owned"), "listings");
        SelectedListing = MyListings.FirstOrDefault(x => S(x, "id") == id);
        EditMode = "";
    }

    private Task SaveListing() => Run(async () =>
    {
        if (EditMode == "create")
        {
            var units = (Draft.Type == "BUILDING" ? Draft.Units : Draft.Units.Take(1)).Select(u => new
            {
                name = u.Name, size = u.Size, furnished = u.Furnished, rent = Ore(u.Rent),
                utilities = Ore(u.Utilities), deposit = Ore(u.Deposit),
                periods = u.Periods.Select(p => new { start = p.Start, end = p.End }).ToArray()
            }).ToArray();
            var result = await Call("create-listing", new
            {
                type = Draft.Type, name = Draft.Name, description = Draft.Description,
                englishDescription = Draft.EnglishDescription, address = Draft.Address, city = Draft.City, units
            });
            await ReloadOwned(S(result, "id"));
        }
        else
        {
            await Call("edit-listing", new { id = Draft.Id, name = Draft.Name, description = Draft.Description, englishDescription = Draft.EnglishDescription, address = Draft.Address, city = Draft.City });
            await ReloadOwned(Draft.Id);
        }
    }, "Listing saved.");

    private void StartUnit(JsonNode? source)
    {
        Unit = new UnitDraft();
        if (source is not null)
        {
            Unit.Id = S(source, "id"); Unit.Name = S(source, "name"); Unit.Size = Int(source, "size");
            Unit.Furnished = B(source, "furnished"); Unit.Rent = Int(source, "rent") / 100m;
            Unit.Utilities = Int(source, "utilities") / 100m; Unit.Deposit = Int(source, "deposit") / 100m;
        }
        EditMode = "unit"; Error = ""; Success = "";
    }

    private Task SaveUnit() => Run(async () =>
    {
        var listingId = S(SelectedListing, "id");
        var input = new Dictionary<string, object>
        {
            ["listingId"] = listingId, ["name"] = Unit.Name, ["size"] = Unit.Size, ["furnished"] = Unit.Furnished,
            ["rent"] = Ore(Unit.Rent), ["utilities"] = Ore(Unit.Utilities), ["deposit"] = Ore(Unit.Deposit)
        };
        if (Unit.Id.Length > 0) input["id"] = Unit.Id;
        await Call("edit-unit", input);
        await ReloadOwned(listingId);
    }, "Room details saved.");

    private void StartPeriod(JsonNode unit, JsonNode? source)
    {
        Period = new PeriodDraft { UnitId = S(unit, "id"), Id = S(source, "id"), Start = S(source, "start"), End = S(source, "end") };
        EditMode = "period"; Error = ""; Success = "";
    }

    private Task SavePeriod() => Run(async () =>
    {
        var listingId = S(SelectedListing, "id");
        var input = new Dictionary<string, string> { ["unitId"] = Period.UnitId, ["start"] = Period.Start, ["end"] = Period.End };
        if (Period.Id.Length > 0) input["id"] = Period.Id;
        await Call("save-period", input);
        await ReloadOwned(listingId);
    }, "Rental period saved.");

    private void AskRemove(string op, string id) { ConfirmOperation = op; ConfirmId = id; Error = ""; Success = ""; }
    private void CloseConfirmation() { ConfirmOperation = ""; ConfirmId = ""; }

    private Task Remove() => Run(async () =>
    {
        var listingId = ConfirmOperation == "remove-listing" ? "" : S(SelectedListing, "id");
        await Call(ConfirmOperation, new { id = ConfirmId });
        CloseConfirmation();
        await ReloadOwned(listingId);
    }, "Removed from public listings.");

    private static string S(JsonNode? n, string key) => (n as JsonObject)?[key]?.ToString() ?? "";
    private static JsonArray Arr(JsonNode? n, string key) => (n as JsonObject)?[key] as JsonArray ?? [];
    private static IEnumerable<JsonNode> Items(JsonNode? n, string key) => Arr(n, key).OfType<JsonNode>();
    private static bool B(JsonNode? n, string key) => bool.TryParse(S(n, key), out var b) && b;
    private static int Int(JsonNode? n, string key) => int.TryParse(S(n, key), out var v) ? v : 0;
    private static int Ore(decimal dkk) => checked((int)decimal.Round(dkk * 100m, 0, MidpointRounding.AwayFromZero));
    private static string Money(int ore) => $"{(ore / 100m).ToString("N2", CultureInfo.GetCultureInfo("en-DK"))} DKK";
    private static bool IsBuilding(JsonNode? n) => S(n, "type") == "BUILDING";
    private static string Kind(JsonNode? n) => IsBuilding(n) ? "Building" : "Apartment";
    private static string ListingStatus(JsonNode? n) => Items(n, "units").SelectMany(u => Items(u, "periods")).Any(p => B(p, "available")) ? "Available periods" : "Currently unavailable";
    private static int ListingRent(JsonNode? n) => Items(n, "units").Select(u => Int(u, "rent") + Int(u, "utilities")).DefaultIfEmpty(0).Min();
    private static string Nice(string s) => s.Length == 0 ? "—" : s[0] + s[1..].ToLowerInvariant().Replace('_', ' ');
    private static bool CanPay(JsonNode? invoice) => new[] { "DUE", "OVERDUE" }.Contains(S(invoice, "status").ToUpperInvariant());
    private bool IsActive(JsonNode? rental) => string.CompareOrdinal(S(rental, "start"), Today) <= 0 && string.CompareOrdinal(Today, S(rental, "end")) < 0;
    private bool CanCancel(JsonNode? rental) => string.CompareOrdinal(S(rental, "end"), Today) > 0;
    private bool IsCurrent(string screen) => Screen == screen;
    private static string RentalTitle(JsonNode? rental) => $"{S(rental, "listingName")} · {S(rental, "unitName")}";

    private sealed class ListingDraft
    {
        public string Id { get; set; } = "";
        public string Type { get; set; } = "APARTMENT";
        public string Name { get; set; } = "";
        public string Description { get; set; } = "";
        public string EnglishDescription { get; set; } = "";
        public string Address { get; set; } = "";
        public string City { get; set; } = "";
        public List<UnitDraft> Units { get; } = [new()];
    }
    private sealed class UnitDraft
    {
        public string Id { get; set; } = "";
        public string Name { get; set; } = "";
        public int Size { get; set; } = 25;
        public bool Furnished { get; set; }
        public decimal Rent { get; set; }
        public decimal Utilities { get; set; }
        public decimal Deposit { get; set; }
        public List<PeriodDraft> Periods { get; } = [new()];
    }
    private sealed class PeriodDraft
    {
        public string Id { get; set; } = "";
        public string UnitId { get; set; } = "";
        public string Start { get; set; } = "";
        public string End { get; set; } = "";
    }
}


