# UniHome example verification checklist

Use the synthetic example accounts, never team credentials or real information. This checklist describes expected behavior; the acceptance runner reports observed outcomes separately.

| Requirement / case | Screen and success path | Failure or boundary to verify |
| --- | --- | --- |
| FR01 Rent a Room or Apartment | As a tenant, select a unit and landlord-defined period in the catalog. Review and accept the generated contract, simulate first month plus deposit, then find it on the dashboard. | Declining does not rent; changed terms or a competing booking cannot double-book the period. |
| FR02 View Listings and Room Details | Open the root URL while signed out; select an apartment or room. Inspect period, price, utilities and deposit. | An unavailable published unit is viewable but cannot be rented; withdrawn units cannot be rented. |
| FR03 Create an Account | Create a fresh account with a synthetic email and select exactly one role. | Reject a duplicate email and invalid or missing details. |
| FR04 Log In as a Tenant | Sign in to the tenant area. | Invalid password or the wrong role must not grant access. |
| FR05 Log In as a Landlord | Sign in to the landlord area. | Invalid password or a Tenant account must not grant landlord access. |
| FR06 Create a Listing | Publish a standalone apartment, and a building with two rooms and predefined periods. Check the signed-out catalog. | Reject invalid amounts or date ranges; a Tenant cannot publish. |
| FR07 Filter Listing Search Results | Filter by city, rent, date, furnishing and size, then clear filters. | Invalid ranges are rejected; a query with no matches shows an empty state. |
| FR08 Edit an Existing Listing | Change shared display text on the demo building and a price on an unrented room. | Booked periods, the shared address, and snapshotted contract terms stay protected; a different Landlord cannot edit. |
| FR09 Remove a Listing or Room | Withdraw an unrented room and verify other rooms remain listed. | A booked room or period cannot be withdrawn; existing agreements and payments remain. |
| FR10 View the Lease and Utilities Dashboard | Inspect lease dates, contract, rent, utilities, and payment or deposit status. | No rentals is an empty state; one Tenant cannot see another Tenant's contract. |
| FR11 View Own Listing, Tenant and Rent Details | As the owner, open the listing and examine occupancy, current Tenant and recorded payment state. | A different Landlord never sees private tenancy data. |
| FR12 Message the Landlord | Send a message in the conversation for an eligible rental. | Empty messages and unrelated users are rejected. |
| FR13 Message a Tenant | As owning Landlord, reply in the same rental conversation. | A Landlord without a rental relationship cannot read or send to the thread. |
| FR14 Report a Problem to the Landlord | From the rental conversation, send a message describing a problem. | Use the same thread as FR12; no repair ticket or janitor role exists. |
| FR15 View Listings in English | Read English UI labels and available English listing text. | If no English description is recorded, preserve the structured information and label any original text. Do not translate automatically. |
| FR16 Save a Listing | Save a building and its room, including when unavailable. Open saved listings. | Saving twice does not duplicate the same item or reserve availability. |
| FR17 Receive a Saved-Listing Availability Notification | After a previously blocked unit becomes available, view the in-app notification and open its listing. | Following both building and room creates one notification for a single availability event; display current availability when opened. |
| FR18 Create a Building Announcement | Publish a title and message to a building the Landlord owns. | Reject empty content, another Landlord's building, and standalone apartments. |
| FR19 View Building Announcements | As a current Tenant in that building, read the announcement. | A non-tenant or tenant after rental end cannot see the building feed. |
| Simulate a Payment | Confirm an amount already due and check dashboard and owner view for Paid. | No real funds, wallet, or financial details; reject future amounts and do not record a second payment for retries. |
| Cancel a Rental with Notice | On the Tenant dashboard, submit an end date with at least two calendar months' notice. Observe prorated final rent/utilities and the automatic simulated deposit return at effective end. | Reject dates below the minimum. Access ends at the effective date; amounts due remain payable. |

## Runtime and source integrity

- Blazor calls REST, REST calls gRPC, and the data tier is the only app using PostgreSQL. Build and start them as independent processes along with the isolated database.
- Stop and start the example without resetting it. Recheck an API-created message, booking and paid status after the restart.
- The SHA-256 verification utility compares every original SEP3 file with the read-only inventory. It never writes to the original folder.
