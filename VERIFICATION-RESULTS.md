# UniHome example — observed verification

The browser walkthroughs used synthetic accounts on the separate verification ports. The running demo on port 15283 retains its own seed data. See [the reproduction checklist](VERIFICATION-CHECKLIST.md) for the steps and additional boundary cases.

| Case | Screen | Observed result |
| --- | --- | --- |
| FR01 Rent a Room or Apartment | Catalog → Room → Contract | Accepted the generated contract and confirmed first-month rent plus deposit. The browser disabled confirmation before acceptance. The API rejected a stale quote, allowed exactly one of two competing bookings, and returned the same booking on retry. |
| FR02 View Listings and Room Details | Catalog → Details | Guest saw published results on the root page and opened room details with dates and prices. API checks included unavailable offers and a missing listing. |
| FR03 Create an Account | Create account | Created a new synthetic account in the browser. API checks rejected duplicate email and an invalid role. |
| FR04 Log In as a Tenant | Sign in → Tenant | Tenant sign-in opened role-specific navigation. API checks rejected invalid credentials and a role mismatch. |
| FR05 Log In as a Landlord | Sign in → Landlord | Invalid password rejected in the browser; a valid Landlord account signed in. |
| FR06 Create a Listing | Manage listings → New listing | Published an apartment in the browser. The API also published a two-room building and rejected tenant publication, invalid prices, and bad dates. |
| FR07 Filter Listing Search Results | Catalog → Filters | Copenhagen returned the matching listing in the browser. API checks covered multiple filters, no results, and an invalid price range. |
| FR08 Edit an Existing Listing | Manage listings → Edit | Renamed a booked building in the browser. API verified display edits, a sibling-room edit, protected address, and preservation of accepted contract terms. |
| FR09 Remove a Listing or Room | Manage listings → Withdraw | Browser withdrew an unbooked apartment and rejected withdrawal of the booked building. API withdrew a separate room without affecting its sibling and rejected booked or foreign removal. |
| FR10 View the Lease and Utilities Dashboard | My rentals → Rental details | Browser showed the contract, rent, fixed utilities, monthly total, prorated final charge, paid status, and refunded deposit. API masked other tenants’ rental details. |
| FR11 View Own Listing, Tenant and Rent Details | Manage listings / Tenants and rent | Owner viewed tenant and payment information in the browser; API checks kept private rental data scoped to the parties. |
| FR12 Message the Landlord | Messages → Rental | Tenant sent a message; blank text and unrelated access were rejected by the API. |
| FR13 Message a Tenant | Messages → Rental | Landlord replied in the same thread. API checks rejected unrelated participants. |
| FR14 Report a Problem to the Landlord | Messages → Report a problem | Tenant sent a problem report in the same conversation. API poll after restart confirmed the message persisted. |
| FR15 View Listings in English | Catalog → Details | Browser showed English copy when supplied, and labeled original text on a Danish-only fixture without generating a translation. |
| FR16 Save a Listing | Listing details → Save / Saved listings | Browser displayed a saved building and room. API accepted saves for the same building and its unavailable room with no duplicate save or reservation. |
| FR17 Receive a Saved-Listing Availability Notification | Notifications | Tenant opened the notification to the available room in the browser. After restart, the API found exactly one notification despite both saved entries and verified its read status. |
| FR18 Create a Building Announcement | Building feed → Publish | Landlord published an announcement in the browser. API verified ownership restrictions and rejected standalone-apartment announcements. |
| FR19 View Building Announcements | Building feed | Ended tenant saw no eligible building feed. With an active rental after a restart, the tenant read the persisted announcement. API also verified current-tenant access and denial at the effective end. |
| Simulate a Payment | My rentals → Invoices | Browser confirmed an overdue amount and moved it to Paid. API checks covered due and overdue, duplicate retries, future-payment rejection, and cross-tenant rejection. |
| Cancel a Rental with Notice | My rentals → Request cancellation | Browser rejected one day below the minimum and accepted exactly two calendar months. Post-restart checks verified prorated final rent and utilities, automatic simulated deposit refund, and revoked feed access. |

## Build, restart, and source integrity

- Both Java tiers compiled; 7 JUnit tests passed (6 data-tier, 1 application-tier). The final Blazor build finished with 0 warnings and 0 errors.
- The isolated REST acceptance suite passed 17 initial groups and 3 post-restart groups with PostgreSQL. Restarting kept bookings, contracts, cancellations, payment status, listings, and messages.
- The reset script archived the verification database, recreated the three seed listings, and left the demo alone. After stopping verification, its four ports no longer listened.
- The demo returned HTTP 200 and its REST ping returned `status: ok`. Ports 15283, 18080, 19090, and 15432 listened on `127.0.0.1` only.
- SHA-256 comparison of the original SEP3 folder: 876 files scanned; 0 added, 0 removed, 0 modified. The team database and Google Doc were not edited.
