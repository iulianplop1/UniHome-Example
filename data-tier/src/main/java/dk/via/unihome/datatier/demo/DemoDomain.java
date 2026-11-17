package dk.via.unihome.datatier.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Transactional reference implementation. Transport adapters do not own business rules or connect to the DB. */
@Service
@EnableScheduling
public class DemoDomain {
    private static final ZoneId ZONE = ZoneId.of("Europe/Copenhagen");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long DEMO_LOCK = 152831808019090L;
    private final JdbcTemplate db;
    private final String frozenDate;
    private final String clockFile;

    public DemoDomain(JdbcTemplate db, @Value("${demo.today:}") String frozenDate,
                      @Value("${demo.clock.file:}") String clockFile) {
        this.db = db;
        this.frozenDate = frozenDate;
        this.clockFile = clockFile;
    }

    public static final class Problem extends RuntimeException {
        public final int status;
        public Problem(int status, String message) { super(message); this.status = status; }
    }

    private static Problem bad(String message) { return new Problem(400, message); }
    private static Problem forbidden(String message) { return new Problem(403, message); }
    private static Problem missing(String message) { return new Problem(404, message); }
    private static Problem conflict(String message) { return new Problem(409, message); }

    private record User(UUID id, String name, String email, String role) {
        boolean tenant() { return role.equals("TENANT"); }
    }

    @Transactional
    public Map<String, Object> call(String action, String bearer, Map<String, Object> input) {
        Map<String, Object> in = input == null ? Map.of() : input;
        LocalDate now = today();
        if (action.equals("ping")) return map("status", "ok", "today", now.toString());
        // The demo serializes commands across service instances using one transaction-scoped DB lock.
        // This keeps writes, availability transitions and retry handling atomic, without process-local state.
        lock();
        if (action.equals("register") || action.equals("login")) return signIn(action, in);
        refresh(now);
        if (action.equals("catalog")) return catalog(in, now);
        if (action.equals("listing")) {
            var l = listing(id(in, "id"));
            if (!b(l, "active")) throw missing("This listing is no longer published.");
            return listingView(l, now, false);
        }
        var user = authorize(bearer);
        return switch (action) {
            case "logout" -> {
                db.update("DELETE FROM demo_session WHERE token_hash = ?", digest(rawToken(bearer)));
                yield ok();
            }
            case "owned" -> {
                landlord(user);
                yield map("listings", rows("SELECT * FROM demo_listing WHERE owner_id=? AND active ORDER BY name", user.id)
                        .stream().map(l -> listingView(l, now, true)).toList());
            }
            case "create-listing" -> createListing(user, in, now);
            case "edit-listing" -> editListing(user, in, now);
            case "edit-unit" -> editUnit(user, in, now);
            case "save-period" -> savePeriod(user, in, now);
            case "remove-period" -> removePeriod(user, in, now);
            case "remove-unit" -> removeUnit(user, in, now);
            case "remove-listing" -> removeListing(user, in, now);
            case "quote" -> quote(user, in, now);
            case "book" -> book(user, in, now);
            case "rentals" -> rentals(user, now);
            case "pay" -> pay(user, in, now);
            case "cancel" -> cancel(user, in, now);
            case "messages" -> messages(user, in);
            case "send" -> send(user, in);
            case "save" -> save(user, in);
            case "unsave" -> unsave(user, in);
            case "saved" -> saved(user, now);
            case "notifications" -> notifications(user);
            case "read-notification" -> readNotification(user, in);
            case "feed" -> feed(user, in, now);
            case "announce" -> announce(user, in);
            default -> throw missing("Unknown demo action.");
        };
    }

    private void lock() { db.queryForList("SELECT pg_advisory_xact_lock(?)", DEMO_LOCK); }

    private LocalDate today() {
        if (!clockFile.isBlank()) {
            try {
                Path p = Path.of(clockFile);
                if (Files.exists(p)) return LocalDate.parse(Files.readString(p).strip());
            } catch (Exception ex) {
                throw new IllegalStateException("DEMO_CLOCK_FILE must contain an ISO date", ex);
            }
        }
        return frozenDate.isBlank() ? LocalDate.now(ZONE) : LocalDate.parse(frozenDate.strip());
    }

    private static Map<String,Object> map(Object... pairs) {
        Map<String,Object> m = new LinkedHashMap<>();
        for (int i=0; i<pairs.length; i+=2) m.put((String)pairs[i], pairs[i+1]);
        return m;
    }

    private static Map<String,Object> ok() { return map("ok", true); }
    private static Map<String,Object> result(UUID id) { return map("id", id.toString()); }
    private static UUID newId() { return UUID.randomUUID(); }
    private List<Map<String,Object>> rows(String sql, Object... args) { return db.queryForList(sql, args); }
    private Map<String,Object> one(String sql, Object... args) {
        return rows(sql, args).stream().findFirst().orElse(null);
    }
    private static String s(Map<String,Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString().strip();
    }
    private static int n(Map<String,Object> m, String key) { return ((Number)m.get(key)).intValue(); }
    private static boolean b(Map<String,Object> m, String key) { return Boolean.TRUE.equals(m.get(key)); }
    private static UUID uuid(Map<String,Object> m, String key) { return (UUID)m.get(key); }
    private static LocalDate day(Map<String,Object> m, String key) {
        Object v=m.get(key);
        return v == null ? null : LocalDate.parse(v.toString());
    }
    private static String required(Map<String,Object> m, String key, int limit) {
        String value = s(m,key);
        if (value.isBlank() || value.length()>limit) throw bad(key+" must be between 1 and "+limit+" characters.");
        return value;
    }
    private static String optional(Map<String,Object> m, String key, int limit) {
        String value = s(m,key);
        if(value.length()>limit) throw bad(key+" is too long.");
        return value;
    }
    private static UUID id(Map<String,Object> m,String key) {
        try { return UUID.fromString(required(m,key,36)); }
        catch(IllegalArgumentException ex) { throw bad("Invalid "+key+"."); }
    }
    private static LocalDate date(Map<String,Object> m,String key) {
        try { return LocalDate.parse(required(m,key,10)); }
        catch(java.time.DateTimeException ex) { throw bad("Invalid "+key+". Use YYYY-MM-DD."); }
    }
    private static int integer(Map<String,Object> m,String key,int min,int max) {
        try {
            int v=new BigDecimal(required(m,key,14)).intValueExact();
            if (v < min || v > max) throw bad(key+" must be between "+min+" and "+max+".");
            return v;
        } catch(NumberFormatException | ArithmeticException ex) { throw bad(key+" must be a whole number."); }
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> objects(Map<String,Object> in, String key) {
        Object o=in.get(key);
        if (o == null) return List.of();
        if (!(o instanceof List<?> a) || a.size() > 100 || a.stream().anyMatch(x->!(x instanceof Map)))
            throw bad("Invalid "+key+".");
        return (List<Map<String,Object>>)(List<?>) a;
    }

    private static String rawToken(String value) {
        if (value == null) return "";
        return value.startsWith("Bearer ") ? value.substring(7).strip() : value.strip();
    }

    private static byte[] bytes(int count) { byte[] a = new byte[count]; RANDOM.nextBytes(a); return a; }
    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static byte[] password(String pass,byte[] salt) {
        var spec=new PBEKeySpec(pass.toCharArray(),salt,140_000,256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (Exception e) { throw new IllegalStateException(e); }
        finally { spec.clearPassword(); }
    }
    private static String passwordHash(String pass) {
        byte[] salt = bytes(16);
        return encode(salt)+"."+encode(password(pass,salt));
    }
    private static boolean passwordMatches(String pass,String value) {
        String[] pieces=value.split("\\.",2);
        var decoder=Base64.getUrlDecoder();
        return MessageDigest.isEqual(decoder.decode(pieces[1]),password(pass,decoder.decode(pieces[0])));
    }
    private static User user(Map<String,Object> row) {
        return new User(uuid(row,"id"),s(row,"name"),s(row,"email"),s(row,"role"));
    }
    private User authorize(String bearer) {
        String token=rawToken(bearer);
        var account = token.isBlank() || token.length()>256 ? null : one("""
            SELECT a.* FROM demo_session ses JOIN demo_account a ON a.id=ses.account_id
            WHERE ses.token_hash=? AND ses.expires_at>now()
            """, digest(token));
        if (account==null) throw new Problem(401,"Sign in to continue.");
        return user(account);
    }
    private static void tenant(User u) { if (!u.tenant()) throw forbidden("A Tenant account is required."); }
    private static void landlord(User u) { if (u.tenant()) throw forbidden("A Landlord account is required."); }

    private Map<String,Object> signIn(String action,Map<String,Object> in) {
        String email=required(in,"email",254).toLowerCase(Locale.ROOT);
        Object passRaw=in.get("password");
        String pass=passRaw instanceof String value ? value : "";
        if (pass.isBlank() || pass.length()>128) throw bad("Enter a valid password.");
        String role=required(in,"role",20).toUpperCase(Locale.ROOT);
        if (!Set.of("TENANT","LANDLORD").contains(role)) throw bad("Choose Tenant or Landlord.");
        var row=one("SELECT * FROM demo_account WHERE email=?",email);
        User user;
        if(action.equals("register")) {
            if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw bad("Enter a valid email address.");
            if (pass.length()<8) throw bad("Password must be at least 8 characters.");
            if (row!=null) throw conflict("That email cannot be used to register. Try signing in.");
            user=new User(newId(),required(in,"name",100),email,role);
            db.update("INSERT INTO demo_account(id,name,email,password_hash,role) VALUES(?,?,?,?,?)",
                    user.id,user.name,user.email,passwordHash(pass),role);
        } else {
            if (row==null || !passwordMatches(pass,s(row,"password_hash")) || !role.equals(s(row,"role")))
                throw new Problem(401,"The credentials do not match an account for this role.");
            user=user(row);
        }
        String token=encode(bytes(32));
        db.update("INSERT INTO demo_session(token_hash,account_id,expires_at) VALUES(?,?,now()+interval '12 hours')",
                digest(token),user.id);
        return map("id",user.id.toString(),"name",user.name,"email",user.email,"role",user.role,"token",token);
    }

    private Map<String,Object> listing(UUID id) {
        var row=one("SELECT * FROM demo_listing WHERE id=?",id);
        if(row==null) throw missing("Listing not found.");
        return row;
    }
    private Map<String,Object> unit(UUID id) {
        var row=one("SELECT * FROM demo_unit WHERE id=?",id);
        if(row==null) throw missing("Unit not found.");
        return row;
    }
    private Map<String,Object> period(UUID id) {
        var row=one("SELECT * FROM demo_period WHERE id=?",id);
        if(row==null) throw missing("Rental period not found.");
        return row;
    }
    private Map<String,Object> ownedListing(User user,UUID id) {
        landlord(user);
        var row=listing(id);
        if(!user.id.equals(uuid(row,"owner_id"))) throw forbidden("You do not own this listing.");
        if(!b(row,"active")) throw missing("This listing has been withdrawn.");
        return row;
    }
    private Map<String,Object> ownedUnit(User user,UUID id) {
        var row=unit(id);
        ownedListing(user,uuid(row,"listing_id"));
        if(!b(row,"active")) throw missing("This unit has been withdrawn.");
        return row;
    }
    private boolean overlaps(UUID unitId,LocalDate start,LocalDate end) {
        return one("""
            SELECT 1 FROM demo_rental WHERE unit_id=? AND start_date < ? AND end_date > ? LIMIT 1
            """,unitId,end,start)!=null;
    }
    private boolean unitBooked(UUID unitId,LocalDate now) {
        return one("SELECT 1 FROM demo_rental WHERE unit_id=? AND end_date>? LIMIT 1",unitId,now)!=null;
    }
    private boolean listingBooked(UUID listingId,LocalDate now) {
        return one("""
            SELECT 1 FROM demo_rental r JOIN demo_unit u ON u.id=r.unit_id
            WHERE u.listing_id=? AND r.end_date>? LIMIT 1
            """,listingId,now)!=null;
    }
    private boolean isAvailable(Map<String,Object> p,LocalDate now) {
        var start=day(p,"start_date");
        return b(p,"active")&&!start.isBefore(now)&&!overlaps(uuid(p,"unit_id"),start,day(p,"end_date"));
    }
    private List<Map<String,Object>> periodViews(UUID unitId,LocalDate now,boolean owned) {
        return rows("SELECT * FROM demo_period WHERE unit_id=? AND active ORDER BY start_date,id",unitId)
                .stream().filter(p->owned||day(p,"end_date").isAfter(now))
                .map(p->map("id",s(p,"id"),"start",s(p,"start_date"),"end",s(p,"end_date"),
                        "available",isAvailable(p,now),
                        "booked",overlaps(unitId,day(p,"start_date"),day(p,"end_date")))).toList();
    }
    private Map<String,Object> listingView(Map<String,Object> l, LocalDate now,boolean owned) {
        var units=rows("SELECT * FROM demo_unit WHERE listing_id=? AND active ORDER BY name",uuid(l,"id"))
                .stream().map(u->map("id",s(u,"id"),"name",s(u,"name"),"size",n(u,"size"),"furnished",b(u,"furnished"),
                        "rent",n(u,"rent"),"utilities",n(u,"utilities"),"deposit",n(u,"deposit"),
                        "booked",unitBooked(uuid(u,"id"),now),"periods",periodViews(uuid(u,"id"),now,owned))).toList();
        boolean available=units.stream().anyMatch(u->objects(u,"periods").stream().anyMatch(p->b(p,"available")));
        return map("id",s(l,"id"),"type",s(l,"type"),"name",s(l,"name"),"description",s(l,"description"),
                "englishDescription",s(l,"english_description"),"address",s(l,"address"),"city",s(l,"city"),
                "units",units,"available",available,"booked",listingBooked(uuid(l,"id"),now));
    }
    private Map<String,Object> catalog(Map<String,Object> in,LocalDate now) {
        String city=s(in,"city").toLowerCase(Locale.ROOT);
        int floor=s(in,"minRent").isBlank()?0:integer(in,"minRent",0,100_000_000);
        int ceiling=s(in,"maxRent").isBlank()?Integer.MAX_VALUE:integer(in,"maxRent",0,100_000_000);
        int minSize=s(in,"minSize").isBlank()?0:integer(in,"minSize",0,100_000);
        LocalDate wanted=s(in,"date").isBlank()?null:date(in,"date");
        String furnished=s(in,"furnished");
        if(floor>ceiling) throw bad("The maximum rent must equal or exceed the minimum rent.");
        if(wanted!=null&&wanted.isBefore(now)) throw bad("Choose an availability date that is not in the past.");
        if(!furnished.isBlank()&&!Set.of("true","false").contains(furnished))
            throw bad("Select a valid furnishing filter.");
        var found = new ArrayList<Map<String,Object>>();
        for(var l:rows("SELECT * FROM demo_listing WHERE active ORDER BY name")) {
            if(!s(l,"city").toLowerCase(Locale.ROOT).contains(city)) continue;
            var v=listingView(l,now,false);
            var filtered=objects(v,"units").stream().filter(u->n(u,"rent")>=floor&&n(u,"rent")<=ceiling
                    &&n(u,"size")>=minSize&&(furnished.isBlank()||b(u,"furnished")==Boolean.parseBoolean(furnished))
                    &&(wanted==null||objects(u,"periods").stream().anyMatch(p->
                        b(p,"available")&&!wanted.isBefore(day(p,"start"))&&wanted.isBefore(day(p,"end"))))).toList();
            if(!filtered.isEmpty()) {
                v.put("units",filtered);
                v.put("available",filtered.stream().anyMatch(u->objects(u,"periods").stream().anyMatch(p->b(p,"available"))));
                found.add(v);
            }
        }
        return map("listings",found);
    }
    private void validateCountry(Map<String,Object> in) {
        String country=s(in,"country");
        if(!country.isBlank()&&!country.equalsIgnoreCase("DK")&&!country.equalsIgnoreCase("Denmark"))
            throw bad("UniHome reference listings must be in Denmark.");
    }
    private Map<String,Object> createListing(User user,Map<String,Object> in,LocalDate now) {
        landlord(user);
        validateCountry(in);
        String type=required(in,"type",20);
        var units=objects(in,"units");
        if(!Set.of("APARTMENT","BUILDING").contains(type)||units.isEmpty()||type.equals("APARTMENT")&&units.size()!=1)
            throw bad("Provide one apartment unit or at least one building room.");
        var l=newId();
        db.update("""
            INSERT INTO demo_listing(id,owner_id,type,name,description,english_description,address,city)
            VALUES(?,?,?,?,?,?,?,?)
            """, l,user.id,type,required(in,"name",120),required(in,"description",5000),
                optional(in,"englishDescription",5000),required(in,"address",240),required(in,"city",120));
        for(var u:units) {
            var unitId=insertUnit(l,u);
            for(var p:objects(u,"periods")) insertPeriod(unitId,p,now);
        }
        refresh(now);
        return result(l);
    }
    private UUID insertUnit(UUID listingId,Map<String,Object> in) {
        var id=newId();
        db.update("""
            INSERT INTO demo_unit(id,listing_id,name,size,furnished,rent,utilities,deposit) VALUES(?,?,?,?,?,?,?,?)
            """,id,listingId,required(in,"name",120),integer(in,"size",1,100_000),b(in,"furnished"),
                integer(in,"rent",1,100_000_000),integer(in,"utilities",0,100_000_000),integer(in,"deposit",0,100_000_000));
        return id;
    }
    private Map<String,Object> editListing(User user,Map<String,Object> in,LocalDate now) {
        var id=id(in,"id"); var l=ownedListing(user,id);
        validateCountry(in);
        String address=in.containsKey("address")?required(in,"address",240):s(l,"address");
        String city=in.containsKey("city")?required(in,"city",120):s(l,"city");
        if((!address.equals(s(l,"address"))||!city.equals(s(l,"city")))&&listingBooked(id,now))
            throw conflict("The address cannot be changed while there is a booked unit.");
        db.update("UPDATE demo_listing SET name=?,description=?,english_description=?,address=?,city=? WHERE id=?",
                in.containsKey("name")?required(in,"name",120):s(l,"name"),
                in.containsKey("description")?required(in,"description",5000):s(l,"description"),
                in.containsKey("englishDescription")?optional(in,"englishDescription",5000):s(l,"english_description"),
                address,city,id);
        return result(id);
    }
    private Map<String,Object> editUnit(User user,Map<String,Object> in,LocalDate now) {
        if(s(in,"id").isBlank()) {
            var l=id(in,"listingId");
            if(!s(ownedListing(user,l),"type").equals("BUILDING")) throw bad("Only a building can add a room.");
            var id=insertUnit(l,in);
            refresh(now); return result(id);
        }
        var id=id(in,"id"); var u=ownedUnit(user,id);
        if(!s(in,"listingId").isBlank()&&!uuid(u,"listing_id").equals(id(in,"listingId")))
            throw bad("The unit is not part of that listing.");
        if(unitBooked(id,now)) throw conflict("A booked unit cannot be edited. Other rooms and unbooked periods may be changed.");
        db.update("UPDATE demo_unit SET name=?,size=?,furnished=?,rent=?,utilities=?,deposit=? WHERE id=?",
                required(in,"name",120),integer(in,"size",1,100_000),b(in,"furnished"),
                integer(in,"rent",1,100_000_000),integer(in,"utilities",0,100_000_000),integer(in,"deposit",0,100_000_000),id);
        return result(id);
    }
    private static void checkPeriod(LocalDate start,LocalDate end,LocalDate now) {
        if(start.isBefore(now)||!end.isAfter(start)||end.isAfter(start.plusYears(50)))
            throw bad("Use a current or future start date and an end date after it (within 50 years).");
    }
    private UUID insertPeriod(UUID unitId,Map<String,Object> in,LocalDate now) {
        LocalDate start=date(in,"start"), end=date(in,"end");
        checkPeriod(start,end,now);
        UUID p=newId();
        db.update("INSERT INTO demo_period(id,unit_id,start_date,end_date) VALUES(?,?,?,?)",p,unitId,start,end);
        return p;
    }
    private Map<String,Object> savePeriod(User user,Map<String,Object> in,LocalDate now) {
        var unitId=id(in,"unitId"); ownedUnit(user,unitId);
        UUID pid;
        if(s(in,"id").isBlank()) pid=insertPeriod(unitId,in,now);
        else {
            pid=id(in,"id");
            var p=period(pid);
            if(!unitId.equals(uuid(p,"unit_id"))||!b(p,"active")) throw missing("This period is not offered by the selected unit.");
            LocalDate start=date(in,"start"),end=date(in,"end");
            checkPeriod(start,end,now);
            if(overlaps(unitId,day(p,"start_date"),day(p,"end_date")) || overlaps(unitId,start,end))
                throw conflict("Booked dates cannot be changed.");
            db.update("UPDATE demo_period SET start_date=?, end_date=? WHERE id=?",start,end,pid);
        }
        refresh(now); return result(pid);
    }
    private Map<String,Object> removePeriod(User user,Map<String,Object> in,LocalDate now) {
        var pid=id(in,"id"); var p=period(pid); var uid=uuid(p,"unit_id");
        ownedUnit(user,uid);
        if(overlaps(uid,day(p,"start_date"),day(p,"end_date"))) throw conflict("A booked period cannot be removed.");
        db.update("UPDATE demo_period SET active=false WHERE id=?",pid);
        refresh(now); return ok();
    }
    private Map<String,Object> removeUnit(User user,Map<String,Object> in,LocalDate now) {
        var uid=id(in,"id"); var u=ownedUnit(user,uid); var lid=uuid(u,"listing_id");
        if(unitBooked(uid,now)) throw conflict("A booked room or apartment cannot be removed.");
        db.update("UPDATE demo_unit SET active=false WHERE id=?",uid);
        if(!b(unit(uid),"active") && one("SELECT 1 FROM demo_unit WHERE listing_id=? AND active",lid)==null)
            db.update("UPDATE demo_listing SET active=false WHERE id=?",lid);
        refresh(now); return ok();
    }
    private Map<String,Object> removeListing(User user,Map<String,Object> in,LocalDate now) {
        var lid=id(in,"id"); ownedListing(user,lid);
        if(listingBooked(lid,now)) throw conflict("A listing with booked units cannot be removed.");
        db.update("UPDATE demo_listing SET active=false WHERE id=?",lid);
        refresh(now); return ok();
    }

    private static String contract(User user,Map<String,Object> l,Map<String,Object> u,Map<String,Object> p) {
        return "UniHome illustrative rental contract (simulation)\n"
                +"Tenant: "+user.name+"\nProperty: "+s(l,"name")+" — "+s(l,"address")+", "+s(l,"city")+", Denmark\n"
                +"Unit: "+s(u,"name")+"\nStart: "+s(p,"start_date")+" · End (exclusive): "+s(p,"end_date")
                +"\nMonthly rent: "+dkk(n(u,"rent"))+" · Fixed utilities: "+dkk(n(u,"utilities"))
                +"\nMonthly total: "+dkk(n(u,"rent")+n(u,"utilities"))+" · Deposit: "+dkk(n(u,"deposit"))
                +"\nAccept the contract and confirm the simulated first payment and deposit to complete the booking. "
                +"The earliest cancellation date is two calendar months after notice. The deposit refund is simulated.";
    }
    private static String dkk(int ore) { return BigDecimal.valueOf(ore,2).toPlainString()+" DKK"; }
    private void requireAvailable(Map<String,Object> l,Map<String,Object> u,Map<String,Object> p,LocalDate now) {
        if(!b(l,"active")||!b(u,"active")||!isAvailable(p,now))
            throw conflict("That period is no longer available. No rental or payment was recorded.");
    }
    private Map<String,Object> quote(User user,Map<String,Object> in,LocalDate now) {
        tenant(user);
        var pid=id(in,"periodId"); var p=period(pid); var u=unit(uuid(p,"unit_id")); var l=listing(uuid(u,"listing_id"));
        requireAvailable(l,u,p,now);
        var start=day(p,"start_date");var end=day(p,"end_date");
        int rent=n(u,"rent"),utilities=n(u,"utilities"),deposit=n(u,"deposit");
        int first=prorated(rent,start,start.plusMonths(1),end)+prorated(utilities,start,start.plusMonths(1),end)+deposit;
        var q=newId(); String contract=contract(user,l,u,p);
        db.update("INSERT INTO demo_quote(id,tenant_id,period_id,contract,expires_at) VALUES(?,?,?,?,now()+interval '30 minutes')",
                q,user.id,pid,contract);
        return map("id",q.toString(),"contract",contract,"firstPayment",first,"rent",rent,
                "utilities",utilities,"deposit",deposit,"start",start.toString(),"end",end.toString());
    }
    private Map<String,Object> book(User user,Map<String,Object> in,LocalDate now) {
        tenant(user);
        if(!b(in,"accepted")||!b(in,"confirmed")) throw bad("Accept the contract and confirm the simulated payment.");
        var quoteId=id(in,"quoteId");
        var previous=one("SELECT id FROM demo_rental WHERE tenant_id=? AND quote_id=?",user.id,quoteId);
        if(previous!=null) return result(uuid(previous,"id"));
        var q=one("SELECT * FROM demo_quote WHERE id=? AND tenant_id=? AND expires_at>now()",quoteId,user.id);
        if(q==null) throw conflict("The quote is no longer valid. Request a new quote.");
        var pid=uuid(q,"period_id"); var p=period(pid);var uid=uuid(p,"unit_id");var u=unit(uid);var l=listing(uuid(u,"listing_id"));
        requireAvailable(l,u,p,now);
        String contract=contract(user,l,u,p);
        if(!contract.equals(s(q,"contract"))) throw conflict("The contract terms changed. Request and accept a new quote.");
        var start=day(p,"start_date");var end=day(p,"end_date");var rid=newId();
        int rent=n(u,"rent"),utilities=n(u,"utilities"),deposit=n(u,"deposit");
        insertRental(rid,quoteId,uid,pid,user.id,uuid(l,"owner_id"),start,end,s(l,"name"),s(u,"name"),
                rent,utilities,deposit,contract,now);
        refresh(now);
        return result(rid);
    }
    private void insertRental(UUID rid,UUID qid,UUID uid,UUID pid,UUID tenant,UUID landlord,LocalDate start,
                              LocalDate end,String listingName,String unitName,int rent,int utilities,int deposit,
                              String contract,LocalDate paidOn) {
        db.update("""
            INSERT INTO demo_rental(id,quote_id,unit_id,period_id,tenant_id,landlord_id,start_date,end_date,original_end,
                listing_name,unit_name,rent,utilities,deposit,contract)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,rid,qid,uid,pid,tenant,landlord,start,end,end,listingName,unitName,rent,utilities,deposit,contract);
        invoices(rid,start,end,rent,utilities,deposit,paidOn);
    }
    private static int prorated(int cents,LocalDate start,LocalDate fullEnd,LocalDate end) {
        if(!end.isBefore(fullEnd)) return cents;
        long full=ChronoUnit.DAYS.between(start,fullEnd);
        long days=ChronoUnit.DAYS.between(start,end);
        return Math.toIntExact((cents*days + full/2)/full);
    }
    private void invoices(UUID rid,LocalDate start,LocalDate end,int rent,int utilities,int deposit,LocalDate firstPaid) {
        for(int month=0;start.plusMonths(month).isBefore(end);month++) {
            LocalDate from=start.plusMonths(month), next=start.plusMonths(month+1), to=next.isAfter(end)?end:next;
            db.update("""
                INSERT INTO demo_invoice(id,rental_id,start_date,end_date,due,rent,utilities,deposit,paid_on)
                VALUES(?,?,?,?,?,?,?,?,?)
                ON CONFLICT (rental_id, start_date) DO UPDATE SET end_date=EXCLUDED.end_date,
                    rent=EXCLUDED.rent,utilities=EXCLUDED.utilities
                WHERE demo_invoice.paid_on IS NULL
                """,newId(),rid,from,to,from,prorated(rent,from,next,to),prorated(utilities,from,next,to),
                    month==0?deposit:0,month==0?firstPaid:null);
        }
    }
    private Map<String,Object> rental(User user,UUID id) {
        var r=one("""
            SELECT r.*,u.listing_id,t.name AS tenant_name,l.name AS landlord_name,property.type AS listing_type
            FROM demo_rental r JOIN demo_unit u ON u.id=r.unit_id
            JOIN demo_account t ON t.id=r.tenant_id JOIN demo_account l ON l.id=r.landlord_id JOIN demo_listing property ON property.id=u.listing_id
            WHERE r.id=?
            """,id);
        if(r==null) throw missing("Rental not found.");
        if(!user.id.equals(uuid(r,user.tenant()?"tenant_id":"landlord_id")))
            throw forbidden("This rental is not part of your account.");
        return r;
    }
    private List<Map<String,Object>> invoiceViews(UUID rid,LocalDate now) {
        return rows("SELECT * FROM demo_invoice WHERE rental_id=? ORDER BY start_date",rid).stream().map(p->{
            var due=day(p,"due"); var paid=day(p,"paid_on");
            String status=paid!=null?"PAID":due.isAfter(now)?"FUTURE":due.isBefore(now)?"OVERDUE":"DUE";
            return map("id",s(p,"id"),"start",s(p,"start_date"),"end",s(p,"end_date"),
                    "due",due.toString(),"paidOn",paid==null?null:paid.toString(),"rent",n(p,"rent"),
                    "utilities",n(p,"utilities"),"deposit",n(p,"deposit"),
                    "amount",n(p,"rent")+n(p,"utilities")+n(p,"deposit"),"status",status);
        }).toList();
    }
    private Map<String,Object> rentals(User user,LocalDate now) {
        // Role is selected by the server, not interpolated from input.
        String field=user.tenant()?"tenant_id":"landlord_id";
        List<Map<String,Object>> views=new ArrayList<>();
        for(var item:rows("SELECT id FROM demo_rental WHERE "+field+"=? ORDER BY start_date DESC,id",user.id)) {
            var rid=uuid(item,"id"); var r=rental(user,rid);
            var start=day(r,"start_date"); var end=day(r,"end_date");
            String status=!end.isAfter(now)?"ENDED":start.isAfter(now)?"UPCOMING":"ACTIVE";
            var refund=day(r,"refunded_at");
            views.add(map("id",rid.toString(),"unitId",s(r,"unit_id"),"listingId",s(r,"listing_id"),
                    "listingName",s(r,"listing_name"),"listingType",s(r,"listing_type"),"unitName",s(r,"unit_name"),"tenantName",s(r,"tenant_name"),
                    "landlordName",s(r,"landlord_name"),"start",start.toString(),"end",end.toString(),
                    "originalEnd",s(r,"original_end"),"rent",n(r,"rent"),"utilities",n(r,"utilities"),
                    "deposit",n(r,"deposit"),"depositStatus",n(r,"deposit")==0?"NOT_REQUIRED":refund==null?"PAID":"REFUNDED",
                    "refundedOn",refund==null?null:refund.toString(),"contract",s(r,"contract"),"status",status,
                    "invoices",invoiceViews(rid,now)));
        }
        return map("rentals",views);
    }
    private Map<String,Object> pay(User user,Map<String,Object> in,LocalDate now) {
        tenant(user);
        if(!b(in,"confirmed")) throw bad("Confirm the simulated payment.");
        var id=id(in,"id");var invoice=one("SELECT * FROM demo_invoice WHERE id=?",id);
        if(invoice==null) throw missing("Invoice not found.");
        rental(user,uuid(invoice,"rental_id"));
        if(day(invoice,"due").isAfter(now)&&day(invoice,"paid_on")==null) throw conflict("Future months cannot be paid.");
        db.update("UPDATE demo_invoice SET paid_on=? WHERE id=? AND paid_on IS NULL",now,id);
        return ok();
    }
    private Map<String,Object> cancel(User user,Map<String,Object> in,LocalDate now) {
        tenant(user);
        var rid=id(in,"id");var r=rental(user,rid);
        var start=day(r,"start_date");var prev=day(r,"end_date");var next=date(in,"end");
        if(next.equals(prev)&&prev.isBefore(day(r,"original_end"))) return ok();
        if(next.isBefore(now.plusMonths(2))) throw conflict("Give at least two calendar months’ notice.");
        if(!next.isAfter(start)||!next.isBefore(prev)) throw conflict("Choose an end date within the remaining rental.");
        db.update("UPDATE demo_rental SET end_date=? WHERE id=?",next,rid);
        db.update("DELETE FROM demo_invoice WHERE rental_id=? AND start_date>=? AND paid_on IS NULL",rid,next);
        invoices(rid,start,next,n(r,"rent"),n(r,"utilities"),n(r,"deposit"),null);
        refresh(now);
        return ok();
    }

    private Map<String,Object> messages(User user,Map<String,Object> in) {
        UUID rid=id(in,"rentalId"); rental(user,rid);
        return map("messages",rows("""
            SELECT m.id,a.name AS sender_name,m.text,m.problem,m.created_at::text AS timestamp
            FROM demo_message m JOIN demo_account a ON a.id=m.sender_id
            WHERE m.rental_id=? ORDER BY m.created_at,m.id
            """,rid).stream().map(m->map("id",s(m,"id"),"senderName",s(m,"sender_name"),"text",s(m,"text"),
                "problem",b(m,"problem"),"createdAt",s(m,"timestamp"))).toList());
    }
    private Map<String,Object> send(User user,Map<String,Object> in) {
        UUID rid=id(in,"rentalId"); rental(user,rid);
        UUID mid=newId();
        db.update("INSERT INTO demo_message(id,rental_id,sender_id,text,problem) VALUES(?,?,?,?,?)",
                mid,rid,user.id,required(in,"text",4000),b(in,"problem"));
        return result(mid);
    }
    private static boolean unitTarget(Map<String,Object> in) {
        boolean unit=!s(in,"unitId").isBlank(), listing=!s(in,"listingId").isBlank();
        if(unit==listing) throw bad("Select either a listing or a unit.");
        return unit;
    }
    private Map<String,Object> save(User user,Map<String,Object> in) {
        tenant(user);
        boolean isUnit=unitTarget(in); UUID target=id(in,isUnit?"unitId":"listingId");
        var u=isUnit?unit(target):null;
        var l=listing(isUnit?uuid(u,"listing_id"):target);
        if(!b(l,"active")||isUnit&&!b(u,"active")) throw missing("This item is no longer published.");
        db.update("""
            INSERT INTO demo_saved(tenant_id,target_id,listing_id,unit_id) VALUES(?,?,?,?)
            ON CONFLICT DO NOTHING
            """,user.id,target,uuid(l,"id"),isUnit?target:null);
        return ok();
    }
    private Map<String,Object> unsave(User user,Map<String,Object> in) {
        tenant(user);
        db.update("DELETE FROM demo_saved WHERE tenant_id=? AND target_id=?",
                user.id,id(in,unitTarget(in)?"unitId":"listingId"));
        return ok();
    }
    private Map<String,Object> saved(User user,LocalDate now) {
        tenant(user);
        return map("items",rows("""
            SELECT s.listing_id,s.unit_id,l.name AS listing_name, u.name AS unit_name,
                   CASE WHEN s.unit_id IS NULL THEN EXISTS(
                     SELECT 1 FROM demo_unit room JOIN demo_availability av ON av.unit_id=room.id
                     WHERE room.listing_id=l.id AND av.available
                   ) ELSE COALESCE(a.available,false) END AS available
            FROM demo_saved s JOIN demo_listing l ON l.id=s.listing_id
            LEFT JOIN demo_unit u ON u.id=s.unit_id LEFT JOIN demo_availability a ON a.unit_id=s.unit_id
            WHERE s.tenant_id=? ORDER BY l.name,u.name
            """,user.id).stream().map(x->map("listingId",s(x,"listing_id"),
                "unitId",x.get("unit_id")==null?null:s(x,"unit_id"),
                "name",s(x,"unit_id").isBlank()?s(x,"listing_name"):s(x,"listing_name")+" · "+s(x,"unit_name"),
                "available",b(x,"available"))).toList());
    }
    private Map<String,Object> notifications(User user) {
        tenant(user);
        return map("items",rows("SELECT *, created_at::text AS timestamp FROM demo_notification WHERE tenant_id=? ORDER BY created_at DESC,id",
                user.id).stream().map(x->map("id",s(x,"id"),"listingId",s(x,"listing_id"),"unitId",s(x,"unit_id"),
                "name",s(x,"name"),"createdAt",s(x,"timestamp"),"read",b(x,"read"))).toList());
    }
    private Map<String,Object> readNotification(User user,Map<String,Object> in) {
        tenant(user);
        if(db.update("UPDATE demo_notification SET read=true WHERE id=? AND tenant_id=?",id(in,"id"),user.id)==0)
            throw missing("Notification not found.");
        return ok();
    }
    private Map<String,Object> feed(User user,Map<String,Object> in,LocalDate now) {
        UUID lid=id(in,"listingId");var l=listing(lid);
        if(!s(l,"type").equals("BUILDING")) throw missing("Standalone apartments have no building feed.");
        if(user.tenant()) {
            if(one("""
                SELECT 1 FROM demo_rental r JOIN demo_unit u ON u.id=r.unit_id
                WHERE u.listing_id=? AND r.tenant_id=? AND r.start_date<=? AND r.end_date>? LIMIT 1
                """,lid,user.id,now,now)==null) throw forbidden("The feed is only for current tenants of this building.");
        } else if(!user.id.equals(uuid(l,"owner_id"))) throw forbidden("You do not own this building.");
        return map("items",rows("SELECT *,created_at::text AS timestamp FROM demo_announcement WHERE listing_id=? ORDER BY created_at DESC,id",
                lid).stream().map(x->map("id",s(x,"id"),"title",s(x,"title"),"text",s(x,"text"),
                "createdAt",s(x,"timestamp"))).toList());
    }
    private Map<String,Object> announce(User user,Map<String,Object> in) {
        UUID lid=id(in,"listingId");var l=ownedListing(user,lid);
        if(!s(l,"type").equals("BUILDING")) throw bad("Publish announcements only for a building.");
        UUID aid=newId();
        db.update("INSERT INTO demo_announcement(id,listing_id,title,text) VALUES(?,?,?,?)",
                aid,lid,required(in,"title",150),required(in,"text",5000));
        return result(aid);
    }

    private void refresh(LocalDate now) {
        db.update("UPDATE demo_rental SET refunded_at=end_date WHERE deposit>0 AND refunded_at IS NULL AND end_date<=?",now);
        var states=rows("""
            SELECT u.id,u.listing_id, l.name||' · '||u.name AS name,
                   a.available AS previous,
                   (u.active AND l.active AND EXISTS(
                       SELECT 1 FROM demo_period p WHERE p.unit_id=u.id AND p.active AND p.start_date>=?
                       AND NOT EXISTS(SELECT 1 FROM demo_rental r WHERE r.unit_id=u.id
                           AND r.start_date < p.end_date AND r.end_date > p.start_date)
                   )) AS current
            FROM demo_unit u JOIN demo_listing l ON l.id=u.listing_id
            LEFT JOIN demo_availability a ON a.unit_id=u.id
            """,now);
        for(var state:states) {
            boolean available=b(state,"current");var uid=uuid(state,"id");var lid=uuid(state,"listing_id");
            if(state.get("previous")!=null && b(state,"previous")==available) continue;
            if(available && Boolean.FALSE.equals(state.get("previous"))) {
                // One DISTINCT tenant per unit transition, even if both the unit and its listing were saved.
                for(var saved:rows("SELECT DISTINCT tenant_id FROM demo_saved WHERE target_id IN (?,?)",uid,lid)) {
                    db.update("INSERT INTO demo_notification(id,tenant_id,listing_id,unit_id,name) VALUES(?,?,?,?,?)",
                            newId(),uuid(saved,"tenant_id"),lid,uid,s(state,"name"));
                }
            }
            db.update("""
                INSERT INTO demo_availability(unit_id,available) VALUES(?,?)
                ON CONFLICT (unit_id) DO UPDATE SET available=EXCLUDED.available
                """,uid,available);
        }
    }

    @Scheduled(initialDelay=60000,fixedDelay=60000)
    @Transactional
    public void tick() {
        lock();
        refresh(today());
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        lock();
        LocalDate now=today();
        // Seed only an empty demo. Never recreate withdrawn fixture rows at application restart.
        if(one("SELECT 1 FROM demo_account LIMIT 1")!=null) { refresh(now); return; }
        User owner=seedUser("Louise Landlord","landlord@example.test","LANDLORD");
        User tenant=seedUser("Taylor Tenant","tenant@example.test","TENANT");
        seedUser("Val Visitor","visitor@example.test","TENANT");
        User other=seedUser("Omar Other","other-landlord@example.test","LANDLORD");
        UUID building=seedListing(owner,"BUILDING","Nordhavn House","Fælles køkken og rolige værelser.",
                "A shared kitchen and quiet rooms near the harbour.","Example Harbour 12","Copenhagen");
        UUID alder=insertUnit(building,map("name","Alder Room","size",16,"furnished",true,
                "rent",440000,"utilities",48000,"deposit",440000));
        UUID birch=insertUnit(building,map("name","Birch Room","size",19,"furnished",true,
                "rent",490000,"utilities",52000,"deposit",490000));
        LocalDate leaseStart=now.minusMonths(3).minusDays(5), leaseEnd=now.plusMonths(5);
        UUID leasePeriod=newId();
        db.update("INSERT INTO demo_period(id,unit_id,start_date,end_date) VALUES(?,?,?,?)",
                leasePeriod,alder,leaseStart,leaseEnd);
        insertPeriod(alder,map("start",now.plusMonths(3),"end",now.plusMonths(9)),now);
        insertPeriod(birch,map("start",now.plusDays(14),"end",now.plusMonths(8)),now);
        UUID rid=newId();
        insertRental(rid,null,alder,leasePeriod,tenant.id,owner.id,leaseStart,leaseEnd,"Nordhavn House","Alder Room",
                440000,48000,440000,contract(tenant,listing(building),unit(alder),period(leasePeriod)),leaseStart);
        db.update("INSERT INTO demo_saved(tenant_id,target_id,listing_id,unit_id) VALUES(?,?,?,NULL),(?,?,?,?)",
                tenant.id,building,building,tenant.id,alder,building,alder);
        db.update("INSERT INTO demo_announcement(id,listing_id,title,text) VALUES(?,?,?,?)",
                newId(),building,"Welcome to Nordhavn House","Shared kitchen orientation is on Monday at 18:00.");
        db.update("INSERT INTO demo_message(id,rental_id,sender_id,text,problem) VALUES(?,?,?,?,false)",
                newId(),rid,owner.id,"Welcome! Please use this conversation if you have any questions.");
        UUID studio=seedListing(owner,"APARTMENT","Canal Studio","Lys studiolejlighed med altan.",
                "Bright studio apartment with a small balcony.","Example Canal 7","Aarhus");
        UUID studioUnit=insertUnit(studio,map("name","Studio","size",37,"furnished",false,
                "rent",660000,"utilities",61000,"deposit",660000));
        insertPeriod(studioUnit,map("start",now.plusDays(7),"end",now.plusMonths(6)),now);
        UUID flat=seedListing(other,"APARTMENT","Parkside Flat","Lejlighed med udsigt til parken.",
                "","Example Park 4","Odense");
        UUID flatUnit=insertUnit(flat,map("name","Flat","size",48,"furnished",false,
                "rent",710000,"utilities",65000,"deposit",710000));
        insertPeriod(flatUnit,map("start",now.plusDays(10),"end",now.plusMonths(7)),now);
        refresh(now);
    }
    private User seedUser(String name,String email,String role) {
        UUID id=newId();
        db.update("INSERT INTO demo_account(id,name,email,password_hash,role) VALUES(?,?,?,?,?)",
                id,name,email,passwordHash("Example123!"),role);
        return new User(id,name,email,role);
    }
    private UUID seedListing(User owner,String type,String name,String desc,String english,String address,String city) {
        UUID id=newId();
        db.update("""
            INSERT INTO demo_listing(id,owner_id,type,name,description,english_description,address,city)
            VALUES(?,?,?,?,?,?,?,?)
            """,id,owner.id,type,name,desc,english,address,city);
        return id;
    }
}

