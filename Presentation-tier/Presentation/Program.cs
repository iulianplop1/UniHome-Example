using Presentation.Components;
using Presentation.Services;

var builder = WebApplication.CreateBuilder(args);

// Add services to the container.
builder.Services.AddRazorComponents()
    .AddInteractiveServerComponents();

builder.Services.AddHttpClient<ListingService>(client =>
    client.BaseAddress = new Uri(builder.Configuration["ApplicationTier:BaseUrl"]!));

builder.Services.AddHttpClient<DemoApi>(client =>
    client.BaseAddress = new Uri(builder.Configuration["ApplicationTier:BaseUrl"]!));
builder.Services.AddScoped<DemoSession>();

var app = builder.Build();

// Configure the HTTP request pipeline.
if (!app.Environment.IsDevelopment())
{
    app.UseExceptionHandler("/Error", createScopeForErrors: true);
    // The default HSTS value is 30 days. You may want to change this for production scenarios, see https://aka.ms/aspnetcore-hsts.
    app.UseHsts();
}

app.UseStatusCodePagesWithReExecute("/not-found", createScopeForStatusCodePages: true);
// Isolated reference server: loopback-only HTTP.

app.UseAntiforgery();

app.MapStaticAssets();
app.MapRazorComponents<App>()
    .AddInteractiveServerRenderMode();

app.Run();
