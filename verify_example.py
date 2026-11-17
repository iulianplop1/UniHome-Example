"""Acceptance checks for the isolated UniHome verification instance, not the team app.

First run:  python verify_example.py initial
After restarting the same verification instance with the printed date:
            python verify_example.py after-end
"""
from argparse import ArgumentParser
from calendar import monthrange
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta
from decimal import Decimal, ROUND_HALF_UP
import json
from pathlib import Path
import socket
import subprocess
import sys
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, Request, build_opener
from uuid import uuid4

ORIGIN = "http://127.0.0.1:28080"
API = ORIGIN + "/api/demo/"
PASS = "Example123!"
CURRENT = None


def api(action, fields=None, *, token=None, allow=(), method="POST"):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    url = ORIGIN + action if action.startswith("/api/listings") else API + action
    request = Request(url, json.dumps(fields or {}).encode() if method == "POST" else None, headers, method=method)
    try:
        with build_opener(ProxyHandler({})).open(request, timeout=20) as result:
            status, raw = result.status, result.read()
    except HTTPError as result:
        status, raw = result.code, result.read()
    except URLError as error:
        raise AssertionError(
            f"{action}: verification API is not reachable at 127.0.0.1:28080 ({error.reason})."
        ) from error
    try:
        data = json.loads(raw) if raw else {}
    except (ValueError, UnicodeError) as error:
        raise AssertionError(f"{action}: non-JSON response (HTTP {status})") from error
    expected = allow or range(200, 300)
    assert status in expected, f"{action}: HTTP {status}, expected {expected}: {data}"
    return status, data


def take(action, fields=None, token=None):
    return api(action, fields, token=token)[1]


def deny(action, fields=None, token=None, expected=(400, 401, 403, 404, 409, 422)):
    status, payload = api(action, fields, token=token, allow=expected)
    assert payload.get("error"), f"{action}: expected an error message (HTTP {status})"
    return status


def check(label, task):
    global CURRENT
    CURRENT = label
    out = task()
    print(f"PASS {label}", flush=True)
    return out


def user(email, role, password=PASS, name="Synthetic User"):
    return {"name": name, "email": email, "role": role, "password": password}


def seed_accounts():
    return {
        "landlord": take("login", user("landlord@example.test", "LANDLORD"))["token"],
        "tenant": take("login", user("tenant@example.test", "TENANT"))["token"],
        "visitor": take("login", user("visitor@example.test", "TENANT"))["token"],
        "other": take("login", user("other-landlord@example.test", "LANDLORD"))["token"],
    }


def find(items, **criteria):
    result = [x for x in items if all(x.get(k) == v for k, v in criteria.items())]
    assert len(result) == 1, f"Expected one item matching {criteria}, got {len(result)}"
    return result[0]


def add_months(d, months):
    y, m = divmod(d.year * 12 + d.month - 1 + months, 12)
    m += 1
    return date(y, m, min(d.day, monthrange(y, m)[1]))


def iso(d):
    return d.isoformat()


def item_key(item):
    return item.get("listingId"), item.get("unitId")


def new_item(label, begin, end, kind="APARTMENT"):
    def unit(name):
        return {"name": name, "size": 31, "furnished": True, "rent": 400000,
                "utilities": 60000, "deposit": 700000,
                "periods": [{"start": iso(begin), "end": iso(end)}]}
    return {"name": label, "description": "Syntetisk bolig", "englishDescription": "",
            "address": "Demo Street 42", "city": "Aalborg", "type": kind,
            "units": [unit("Demo apartment")] if kind == "APARTMENT"
                     else [unit("Demo room A"), unit("Demo room B")]}


def initial(state_file):
    run = uuid4().hex[:10]
    today = date.fromisoformat(take("ping")["today"])
    keys = check("FR04/FR05 tenant and landlord sign in", seed_accounts)
    tenant, landlord, visitor, other = (keys[k] for k in ("tenant", "landlord", "visitor", "other"))
    for params in (user("tenant@example.test", "LANDLORD"),
                   user("landlord@example.test", "TENANT"),
                   user("tenant@example.test", "TENANT", "WrongPass123!")):
        deny("login", params)
    print("PASS FR04/FR05 invalid credentials and wrong role rejected")

    def register():
        params = user(f"verification-{run}@example.test", "TENANT")
        deny("register", dict(params, role="ADMIN"))
        registered = take("register", params)
        deny("register", params)
        reauth = take("login", params)
        assert reauth["id"] == registered["id"] and reauth["role"] == "TENANT"
        new_owner = user(f"verification-owner-{run}@example.test", "LANDLORD")
        created_owner = take("register", new_owner)
        logged_owner = take("login", new_owner)
        assert logged_owner["id"] == created_owner["id"] and logged_owner["role"] == "LANDLORD"
        return reauth["token"]
    newcomer = check("FR03 registration, duplicate email, invalid role and subsequent sign-in", register)

    def public_listings():
        published = take("catalog")["listings"]
        building = find(published, name="Nordhavn House")
        apartment = find(published, name="Canal Studio")
        details = take("listing", {"id": building["id"]})
        alder = find(details["units"], name="Alder Room")
        assert building["type"] == "BUILDING" and apartment["type"] == "APARTMENT"
        assert any(p["available"] is False for p in alder["periods"])
        assert len(details["units"]) >= 2
        fake = str(uuid4())
        deny("listing", {"id": fake})
        return details, apartment, alder
    def legacy_listings():
        _, seed = api("/api/listings", method="GET")
        assert find(seed, name="Room near campus")
        legacy = {"name": f"Reference PoC {run}", "description": "Verification instance only."}
        _, created = api("/api/listings", legacy, allow=(201,))
        _, retrieved = api("/api/listings/" + created["id"], method="GET")
        assert all(retrieved[k] == v for k, v in legacy.items())
        api("/api/listings/" + str(uuid4()), method="GET", allow=(404,))
        api("/api/listings", {"name": "", "description": "Required name"}, allow=(400,))
        return created["id"]
    legacy_id = check("Original PoC listing API preserved: create, list, get, invalid input and 404", legacy_listings)

    building, apartment, alder = check("FR02 anonymous catalog, rooms, unavailable offers, missing listing", public_listings)
    building_id, alder_id = building["id"], alder["id"]

    def publish():
        begin = add_months(today, 1)
        end = add_months(begin, 3)
        req = new_item(f"Acceptance Apartment {run}", begin, end)
        deny("create-listing", req, tenant)
        bad = new_item("Invalid demo amount", begin, end)
        bad["units"][0]["rent"] = -1
        deny("create-listing", bad, landlord)
        deny("create-listing", dict(req, country="DE"), landlord)
        aid = take("create-listing", req, landlord)["id"]
        bid = take("create-listing", new_item(f"Acceptance Building {run}", begin, end, "BUILDING"), landlord)["id"]
        a = take("listing", {"id": aid})
        b = take("listing", {"id": bid})
        assert len(a["units"]) == 1 and len(b["units"]) == 2
        deny("save-period", {"unitId": a["units"][0]["id"], "start": iso(end), "end": iso(begin)}, landlord)
        return a, b
    created, created_building = check("FR06 publish apartment and two-room building; role, price and date validation", publish)

    def filters():
        criteria = {"city": "Aalborg", "minRent": 399999, "maxRent": 400001,
                    "date": created["units"][0]["periods"][0]["start"], "minSize": 30, "furnished": True}
        matches = take("catalog", criteria)["listings"]
        assert find(matches, id=created["id"])
        assert take("catalog", {"city": "Nonexistent-Verification-City"})["listings"] == []
        deny("catalog", {"minRent": 450000, "maxRent": 350000})
    check("FR07 multiple filters, empty result, invalid price range", filters)

    assert created["description"] == "Syntetisk bolig" and not created.get("englishDescription")
    print("PASS FR15 source language still available and no generated translation; UI fallback is a browser check")

    original = find(take("rentals", token=tenant)["rentals"], unitId=alder_id)
    existing_rental_id = original["id"]
    assert original.get("contract")
    assert original["rent"] + original["utilities"] > 0

    def ownership():
        assert find(take("owned", token=landlord)["listings"], id=building_id)
        assert not any(x["id"] == building_id for x in take("owned", token=other)["listings"])
        assert find(take("rentals", token=landlord)["rentals"], id=existing_rental_id)
        assert not any(x["id"] == existing_rental_id for x in take("rentals", token=visitor)["rentals"])
        assert not any(x["id"] == existing_rental_id for x in take("rentals", token=other)["rentals"])
        deny("rentals")
    check("FR10/FR11 dashboard, owner view, authentication and scoped rental details", ownership)

    def edit():
        deny("edit-listing", {"id": building_id, "name": "Not my building"}, other)
        deny("edit-listing", {"id": building_id, "address": "Protected address"}, landlord)
        take("edit-listing", {"id": building_id, "description": f"Shared display text {run}"}, landlord)
        snapshot = find(take("rentals", token=tenant)["rentals"], id=existing_rental_id)
        assert snapshot["contract"] == original["contract"] and snapshot["rent"] == original["rent"]
        room = created_building["units"][0]
        change = {k: room[k] for k in ("id", "name", "size", "furnished", "rent", "utilities", "deposit")}
        change.update(listingId=created_building["id"], rent=425000)
        take("edit-unit", change, landlord)
        details = take("listing", {"id": created_building["id"]})
        fresh_room = find(details["units"], id=room["id"])
        assert fresh_room["rent"] == 425000
        period, = fresh_room["periods"]
        updated_dates = {"unitId": room["id"], "id": period["id"],
                         "start": iso(add_months(today, 2)), "end": iso(add_months(today, 5))}
        deny("save-period", updated_dates, other)
        take("save-period", updated_dates, landlord)
        owned_after = find(take("owned", token=landlord)["listings"], id=created_building["id"])
        actual = find(find(owned_after["units"], id=room["id"])["periods"], id=period["id"])
        assert actual["start"] == updated_dates["start"] and actual["end"] == updated_dates["end"]
        take("remove-period", {"id": period["id"]}, landlord)
        taken_period = find(alder["periods"], start=original["start"])
        deny("remove-period", {"id": taken_period["id"]}, landlord)
    check("FR08 display edits, independent room edit, protected address and unchanged contract", edit)

    def withdraw():
        deny("remove-unit", {"id": alder_id}, landlord)
        deny("remove-listing", {"id": building_id}, landlord)
        room, sibling = created_building["units"]
        deny("remove-unit", {"id": room["id"]}, other)
        take("remove-unit", {"id": room["id"]}, landlord)
        units = take("listing", {"id": created_building["id"]})["units"]
        assert all(x["id"] != room["id"] for x in units) and find(units, id=sibling["id"])
        standalone = take("create-listing", new_item(f"Withdrawn Apartment {run}", add_months(today, 1),
                                                      add_months(today, 2)), landlord)["id"]
        take("remove-listing", {"id": standalone}, landlord)
        deny("listing", {"id": standalone})
        assert not any(x["id"] == standalone for x in take("catalog")["listings"])
    check("FR09 withdraw one room, keep sibling, reject booked or foreign removal", withdraw)

    def race_booking():
        unit = created["units"][0]
        period = unit["periods"][0]
        pid = period["id"]
        stale = take("quote", {"periodId": pid}, newcomer)
        change = {k: unit[k] for k in ("id", "name", "size", "furnished", "rent", "utilities", "deposit")}
        change.update(listingId=created["id"], rent=410000)
        take("edit-unit", change, landlord)
        deny("book", {"quoteId": stale["id"], "accepted": True, "confirmed": True}, newcomer)
        alternate = take("save-period", {"unitId": unit["id"],
                        "start": iso(date.fromisoformat(period["start"]) + timedelta(days=2)),
                        "end": period["end"]}, landlord)["id"]
        quotes = (take("quote", {"periodId": pid}, newcomer),
                  take("quote", {"periodId": alternate}, visitor))
        assert quotes[0]["firstPayment"] == (
            quotes[0]["rent"] + quotes[0]["utilities"] + quotes[0]["deposit"]
        )
        assert quotes[0]["contract"]
        deny("book", {"quoteId": quotes[0]["id"], "accepted": False, "confirmed": True}, newcomer)
        deny("book", {"quoteId": quotes[0]["id"], "accepted": True, "confirmed": False}, newcomer)
        deny("book", {"quoteId": quotes[0]["id"], "accepted": True, "confirmed": True}, visitor)

        def book(position):
            return api("book",
                       {"quoteId": quotes[position]["id"], "accepted": True, "confirmed": True},
                       token=(newcomer, visitor)[position], allow=(200, 201, 400, 409, 422))
        with ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(book, range(2)))
        success = [(i, result[1]["id"]) for i, result in enumerate(results) if result[0] < 300]
        assert len(success) == 1, f"Expected one booking winner, got {results!r}"
        winner, booked = success[0]
        status, retry = book(winner)
        assert status < 300 and retry["id"] == booked
        matching = [x for x in take("rentals", token=landlord)["rentals"] if x["listingId"] == created["id"]]
        assert len(matching) == 1 and matching[0]["id"] == booked
        assert str(matching[0]["invoices"][0]["status"]).upper() == "PAID"
        periods = take("listing", {"id": created["id"]})["units"][0]["periods"]
        assert all(find(periods, id=p)["available"] is False for p in (pid, alternate))
        return booked
    booked = check("FR01 accepted contract + payment, stale quote rejected, exactly one overlapping booking, safe retry", race_booking)

    def messaging():
        fields = {"rentalId": existing_rental_id}
        deny("messages", fields, visitor)
        deny("send", dict(fields, text="Unauthorized", problem=False), other)
        deny("send", dict(fields, text="   ", problem=False), tenant)
        entries = [(tenant, f"Tenant hello {run}", False),
                   (landlord, f"Landlord reply {run}", False),
                   (tenant, f"Problem: a tap leaks {run}", True)]
        for token, content, problem in entries:
            take("send", dict(fields, text=content, problem=problem), token)
        out = take("messages", fields, landlord)["messages"]
        assert all(find(out, text=content)["problem"] == problem for _, content, problem in entries)
        return entries[-1][1]
    message = check("FR12/FR13/FR14 both parties message and report a problem; outsiders and blank text denied", messaging)

    def payment():
        rows = original["invoices"]
        unpaid = [x for x in rows if str(x["status"]).upper() in ("DUE", "OVERDUE")]
        future = [x for x in rows if x["due"] > iso(today)]
        assert any(str(x["status"]).upper() == "OVERDUE" for x in unpaid)
        assert future
        deny("pay", {"id": future[0]["id"], "confirmed": True}, tenant)
        selected = min(unpaid, key=lambda i: i["due"])
        pid = selected["id"]
        deny("pay", {"id": pid, "confirmed": True}, visitor)
        deny("pay", {"id": pid, "confirmed": False}, tenant)
        take("pay", {"id": pid, "confirmed": True}, tenant)
        api("pay", {"id": pid, "confirmed": True}, token=tenant, allow=(200, 201, 400, 409, 422))
        landlord_view = find(take("rentals", token=landlord)["rentals"], id=existing_rental_id)
        assert str(find(landlord_view["invoices"], id=pid)["status"]).upper() == "PAID"
        return pid
    invoice_id = check("Simulate Payment due/overdue, duplicate retry, future and cross-tenant rejection", payment)

    def saves():
        for field, value in (("listingId", building_id), ("unitId", alder_id)):
            take("save", {field: value}, tenant)
            api("save", {field: value}, token=tenant, allow=(200, 201, 409))
        items = take("saved", token=tenant)["items"]
        assert len({item_key(x) for x in items}) == len(items)
        assert find(items, unitId=alder_id)
        assert any(x["listingId"] == building_id and not x.get("unitId") for x in items)
        notices = take("notifications", token=tenant)["items"]
        return [x["id"] for x in notices]
    previous_notifications = check("FR16 save building + unavailable room, no duplicates or reservations", saves)

    def feeds():
        fields = {"listingId": building_id}
        title = f"Verification notice {run}"
        deny("announce", dict(fields, title="", text="Missing title"), landlord)
        deny("announce", dict(fields, title=title, text="Not my building"), other)
        deny("announce", {"listingId": apartment["id"], "title": title, "text": "Apartments have no feed"}, landlord)
        take("announce", dict(fields, title=title, text="Synthetic building announcement."), landlord)
        assert find(take("feed", fields, tenant)["items"], title=title)
        deny("feed", fields, visitor)
    check("FR18/FR19 announcement publish and current-tenant-only read, no standalone feed", feeds)

    def cancellation():
        minimum = add_months(today, 2)
        deny("cancel", {"id": existing_rental_id, "end": iso(minimum - timedelta(days=1))}, tenant)
        deny("cancel", {"id": existing_rental_id, "end": iso(minimum)}, visitor)
        take("cancel", {"id": existing_rental_id, "end": iso(minimum)}, tenant)
        updated = find(take("rentals", token=tenant)["rentals"], id=existing_rental_id)
        assert updated["end"] == iso(minimum)
        assert updated["originalEnd"] == original["originalEnd"]
        final = next(i for i in updated["invoices"] if i["end"] == iso(minimum))
        full = next(i for i in original["invoices"] if i["start"] == final["start"])
        numerator = (minimum - date.fromisoformat(final["start"])).days
        denominator = (date.fromisoformat(full["end"]) - date.fromisoformat(full["start"])).days
        assert 0 < numerator < denominator, f"Fixture final period did not need proration: {final}"
        for field in ("rent", "utilities"):
            expected = int((Decimal(full[field]) * numerator / denominator).quantize(
                Decimal("1"), rounding=ROUND_HALF_UP
            ))
            assert final[field] == expected, (field, final[field], expected)
        assert str(updated["depositStatus"]).upper() != "REFUNDED"
        assert take("feed", {"listingId": building_id}, tenant)["items"]
        return minimum
    end = check("Cancellation exactly at minimum, reject one day early, final rent and utilities prorated", cancellation)

    state_file.write_text(json.dumps({
        "start": iso(today), "effectiveEnd": iso(end), "buildingId": building_id,
        "unitId": alder_id, "rentalId": existing_rental_id, "paidInvoiceId": invoice_id,
        "bookedId": booked, "message": message, "previousNotifications": previous_notifications,
        "createdListingId": created["id"], "legacyId": legacy_id,
    }, indent=2), encoding="utf-8")
    print(f"Initial checks passed. Restart the same verification instance with -DemoToday {end}; then run after-end.")
    print(f"Persisted acceptance checkpoints: {state_file}")


def after_end(state_file):
    s = json.loads(state_file.read_text(encoding="utf-8"))
    today = take("ping")["today"]
    assert today == s["effectiveEnd"], f"Expected test clock {s['effectiveEnd']}, got {today}"
    k = seed_accounts()
    tenant, landlord = k["tenant"], k["landlord"]

    def persisted():
        tenant_rentals = take("rentals", token=tenant)["rentals"]
        ended = find(tenant_rentals, id=s["rentalId"])
        assert ended["end"] == s["effectiveEnd"] and ended["status"].upper() == "ENDED"
        invoice = find(ended["invoices"], id=s["paidInvoiceId"])
        assert invoice["status"].upper() == "PAID"
        assert invoice["paidOn"] == s["start"]
        take("pay", {"id": invoice["id"], "confirmed": True}, tenant)
        confirmed = find(take("rentals", token=tenant)["rentals"], id=s["rentalId"])
        assert find(confirmed["invoices"], id=invoice["id"])["paidOn"] == s["start"]
        assert find(take("rentals", token=landlord)["rentals"], id=s["bookedId"])
        assert take("listing", {"id": s["createdListingId"]})["id"] == s["createdListingId"]
        _, old = api("/api/listings/" + s["legacyId"], method="GET")
        assert old["id"] == s["legacyId"]
        assert find(take("messages", {"rentalId": s["rentalId"]}, landlord)["messages"], text=s["message"])
        return ended
    ended = check("Persistence of listings, booking, cancellation, payment and problem message after restart", persisted)
    assert ended["depositStatus"].upper() == "REFUNDED"
    print("PASS Automatic simulated deposit refund at effective end")

    def notice():
        deny("feed", {"listingId": s["buildingId"]}, tenant)
        items = take("notifications", token=tenant)["items"]
        new = [n for n in items if n["unitId"] == s["unitId"] and n["id"] not in s["previousNotifications"]]
        assert len(new) == 1, f"Expected one availability notification for the room, got {new}"
        take("read-notification", {"id": new[0]["id"]}, tenant)
        items2 = take("notifications", token=tenant)["items"]
        assert find(items2, id=new[0]["id"])["read"] is True
        assert len([n for n in items2 if n["unitId"] == s["unitId"] and n["id"] not in s["previousNotifications"]]) == 1
    check("FR17/FR19 deduplicated availability notification and feed access revoked at rental end", notice)


def stopped(_state_file):
    ports = (25283, 28080, 29090, 25432)
    if sys.platform == "win32":
        # Probe the Windows listener table directly. A filtered loopback SYN
        # can time out rather than refuse a connection on an unused port.
        command = (
            "$ErrorActionPreference='Stop'; "
            "Get-NetTCPConnection -State Listen | "
            "Select-Object -ExpandProperty LocalPort"
        )
        result = subprocess.run(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command],
            capture_output=True, text=True, timeout=20,
        )
        assert result.returncode == 0, f"Unable to read Windows listener table: {result.stderr.strip()}"
        listeners = {int(line.strip()) for line in result.stdout.splitlines() if line.strip()}
        remaining = sorted(listeners.intersection(ports))
        assert not remaining, f"Verification services still listen on {remaining}"
        print("PASS All four verification ports closed after teardown (Windows listener table)")
        return

    remaining = []
    for port in ports:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=1):
                remaining.append(port)
        except ConnectionRefusedError:
            pass
        except OSError as exc:
            raise AssertionError(f"Cannot verify local port {port} is closed: {exc}") from exc
    assert not remaining, f"Verification services still listen on {remaining}"
    print("PASS All four verification ports closed after teardown")


if __name__ == "__main__":
    args = ArgumentParser(description=__doc__)
    args.add_argument("phase", choices=("initial", "after-end", "stopped"))
    args.add_argument("--state", type=Path, default=Path("verification-state.json"))
    opt = args.parse_args()
    try:
        {"initial": initial, "after-end": after_end, "stopped": stopped}[opt.phase](opt.state)
    except (AssertionError, KeyError, OSError, StopIteration, subprocess.TimeoutExpired, ValueError) as exc:
        print(f"FAIL {CURRENT or opt.phase}: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
