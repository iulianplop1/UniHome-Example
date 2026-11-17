# UniHome SEP3 — local reference example

This is an independent teaching copy of the Group 5 UniHome proof of concept. It follows the current Analysis use cases without deploying to a shared host. It does not use the team database, share accounts, push changes, or change the Google Doc. The example uses synthetic data and simulated payments only.

## Run locally

Requirements: PowerShell 7 on Windows, Java 25, .NET 10, PostgreSQL 18 command-line tools, and Python 3.11+ for verification. The first build uses Maven 3.9.16 and may need local dependency caches or access to the package repository.

From this directory in PowerShell:

```powershell
.\Start-Example.ps1
.\Stop-Example.ps1
.\Start-Example.ps1 -SkipBuild
```

Open [the local UniHome demo](http://127.0.0.1:15283). The catalog is the landing page; sign in for role-specific screens.

To archive the existing example data and start fresh, use `.\Reset-Example.ps1`. Reset only the example instance you intend to discard.

| Account | Role | Fixture |
| --- | --- | --- |
| `landlord@example.test` | Landlord | Manages Nordhavn House and Canal Studio |
| `tenant@example.test` | Tenant | Rents Alder Room; has payment history and saved listings |
| `visitor@example.test` | Tenant | No rental relationship with the demonstration building |
| `other-landlord@example.test` | Landlord | Separately owns Parkside Flat |

All four example accounts use `Example123!`. Do not use these credentials anywhere else.

## Architecture

| Service | Default loopback port | Verification loopback port |
| --- | ---: | ---: |
| C# Blazor Server | 15283 | 25283 |
| Java Spring Boot REST application tier | 18080 | 28080 |
| Java Spring Boot gRPC data tier | 19090 | 29090 |
| PostgreSQL example cluster | 15432 | 25432 |

The network path is **Blazor Server → REST/JSON → application tier → gRPC/Protobuf → data tier → PostgreSQL**. Only the data tier uses PostgreSQL at runtime; administrative setup and database tests use it separately. The original proof-of-concept listing routes remain a reference, but use the new catalog and demo workflows for the Analysis use cases.

## Verify without changing the demo instance

Use a **fresh verification instance**. The initial phase intentionally creates synthetic records and cancels one fixture rental, so it must not run against the default demo or the team service.

```powershell
.\Start-Example.ps1 -Instance verification -RunTests
python .\verify_example.py initial
$end = (Get-Content -LiteralPath .\verification-state.json -Raw | ConvertFrom-Json).effectiveEnd
.\Stop-Example.ps1 -Instance verification
.\Start-Example.ps1 -Instance verification -SkipBuild -DemoToday $end
python .\verify_example.py after-end
.\Stop-Example.ps1 -Instance verification
python .\verify_example.py stopped
```

Do not reset between the two phases. The second phase uses the retained database to check persistence and date-driven transitions. To start over, reset only the verification instance. See [the 21-case verification checklist](VERIFICATION-CHECKLIST.md) for manual checks and [the observed verification results](VERIFICATION-RESULTS.md) for this PC’s run.

The read-only source check compares the original SEP3 directory to its prior SHA-256 inventory. From this directory in the provided workspace:

```powershell
python .\verify_original.py 'C:\Users\iulia\Downloads\SEP3' '..\..\work\sep3-audit\inventory.json'
```

## Demo semantics

- All amounts are integer øre across the application boundary and are presented in DKK. Payments and the deposit refund affect example records only.
- Rental intervals use an exclusive end date to prevent overlap. A booking snapshots the terms it accepted; catalog display edits cannot rewrite that contract.
- Cancellation requires at least two calendar months of notice. The final billing period is prorated. At the effective end, the example records the deposit as refunded automatically and revokes the building feed entitlement.
- Availability notifications are in-app only. Problem reports are messages in the rental conversation, not tickets. There is no administrator role, payment processor, wallet, email delivery, or automatic translation.

## Sources used

The code foundation is the local UniHome proof of concept at commit `bb816eda923c06500fb44637f3c9609b5290dfb0`. Behavior follows the current SEP3 Analysis use cases and the clarified deposit, proration, and display-edit decisions. The SEP3 slides, architecture diagram, PoC guide, PRO3/.NET examples, VIA project and report guidelines, and SEP2 use-case/test examples guided architecture, reproducibility, and verification. The project review covered 876 original files and 815 archive entries; compiled and proprietary artifacts were inspected rather than fully decompiled.
