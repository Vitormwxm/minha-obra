package br.com.minhaobra;

import br.com.minhaobra.dto.Accounts;
import br.com.minhaobra.repository.AccountRepository;
import br.com.minhaobra.security.SecretCrypto;
import br.com.minhaobra.security.Totp;
import br.com.minhaobra.service.BootstrapAdminService;
import com.jayway.jsonpath.JsonPath;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountsIT {
    private static final String SCHEMA="it_accounts_"+UUID.randomUUID().toString().replace("-","");
    private static final String TEMP="Temporary-Access-2026!";
    private static final String NORMAL="My-New-Password-2026!";
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountRepository accounts;
    @Autowired PasswordEncoder passwords;
    @Autowired SecretCrypto crypto;
    @Autowired Totp totp;
    @Autowired Environment environment;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired br.com.minhaobra.service.AccountService accountService;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        String url=required("TEST_DATABASE_URL");
        if(!url.startsWith("jdbc:postgresql:"))throw new IllegalStateException("Real PostgreSQL is required.");
        p.add("spring.datasource.url",()->url);p.add("spring.datasource.username",()->required("TEST_DATABASE_USERNAME"));
        p.add("spring.datasource.password",()->required("TEST_DATABASE_PASSWORD"));
        p.add("spring.datasource.hikari.schema",()->SCHEMA);p.add("spring.flyway.schemas",()->SCHEMA);p.add("spring.flyway.default-schema",()->SCHEMA);
        p.add("app.auth.encryption-key",()->"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        p.add("app.events.enabled",()->"false");
        p.add("app.auth.login-attempts-per-minute",()->"10000");p.add("app.auth.ip-attempts-per-minute",()->"10000");p.add("app.auth.mfa-attempts-per-minute",()->"10000");
        p.add("app.auth.registration-attempts-per-minute", () -> "10000");
    }
    @BeforeEach void clean() {jdbc.execute("TRUNCATE app_account,account_recovery_code,account_idempotency,account_outbox,account_audit,spring_session_attributes,spring_session CASCADE");}
    @AfterAll static void drop() throws Exception {
        try(var c=DriverManager.getConnection(required("TEST_DATABASE_URL"),required("TEST_DATABASE_USERNAME"),required("TEST_DATABASE_PASSWORD"));var s=c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS \""+SCHEMA+"\" CASCADE");
        }
    }

    @Test void csrfIsRequiredForLoginAndRegistrationAndInvalidCredentialsStayGeneric() throws Exception {
        Browser browser=new Browser();browser.csrf();
        assertEquals(403,browser.post("/api/auth/login",Map.of("username","someone","password",TEMP),false,null).statusCode());
        var request = Map.of("username", "someone", "displayName", "Pessoa de teste", "password", NORMAL);
        assertEquals(403, browser.post("/api/auth/register", request, false, UUID.randomUUID()).statusCode());
        assertEquals(400, browser.post("/api/auth/register", request, true, null).statusCode());
        assertTrue(accounts.byUsername("someone").isEmpty());
        var missing=browser.post("/api/auth/login",Map.of("username","missing","password",TEMP));
        insert("existing","USER",true,TEMP);
        var wrong=browser.post("/api/auth/login",Map.of("username","existing","password","Wrong-Password-2026!"));
        assertEquals(401,missing.statusCode());assertEquals(missing.body(),wrong.body());
        assertTrue(missing.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }

    @Test void temporaryPasswordRestrictsBusinessAndRotatesSessionAfterChange() throws Exception {
        UUID id=insert("person","USER",true,TEMP);
        Browser browser=new Browser();var login=browser.login("person",TEMP);
        assertStage(login,"CHANGE_PASSWORD");String old=browser.sessionCookie();
        assertEquals(403,browser.get("/api/account").statusCode());
        assertEquals(403,browser.get("/api/admin/users").statusCode());
        var changed=browser.post("/api/auth/change-password",Map.of("currentPassword",TEMP,"newPassword",NORMAL));
        assertStage(changed,"AUTHENTICATED");assertNotEquals(old,browser.sessionCookie());
        assertEquals(200,browser.get("/api/account").statusCode());
        assertEquals(403,browser.get("/api/admin/users").statusCode());
        assertTrue(passwords.matches(NORMAL,accounts.byId(id).orElseThrow().passwordHash()));
        assertEquals(401,new Browser().login("person",TEMP).statusCode());
        var logout=browser.post("/api/auth/logout",Map.of());assertEquals(204,logout.statusCode());
        assertEquals(401,browser.get("/api/auth/me").statusCode());
    }

    @Test void administratorMustChangePasswordAndEnrollMfaBeforeAdministration() throws Exception {
        bootstrap().create("owner",TEMP);
        Browser admin=new Browser();assertStage(admin.login("owner",TEMP),"CHANGE_PASSWORD");
        assertEquals(403,admin.get("/api/admin/users").statusCode());
        assertEquals(403,admin.post("/api/auth/mfa/setup",Map.of()).statusCode());
        assertStage(admin.post("/api/auth/change-password",Map.of("currentPassword",TEMP,"newPassword",NORMAL)),"MFA_SETUP");
        assertEquals(403,admin.get("/api/admin/users").statusCode());
        var setup=admin.post("/api/auth/mfa/setup",Map.of());assertEquals(200,setup.statusCode());
        String secret=JsonPath.read(setup.body(),"$.secret");
        var confirmation=admin.post("/api/auth/mfa/confirm",Map.of("code",totp.generateCode(secret,Instant.now())));
        assertEquals(200,confirmation.statusCode());assertEquals("AUTHENTICATED",JsonPath.read(confirmation.body(),"$.session.stage"));
        List<String> codes=JsonPath.read(confirmation.body(),"$.recoveryCodes");assertEquals(10,codes.size());
        UUID id=accounts.byUsername("owner").orElseThrow().id();
        assertFalse(accounts.byId(id).orElseThrow().totpSecret().contains(secret));
        assertEquals(10,jdbc.queryForObject("SELECT count(*) FROM account_recovery_code",Integer.class));
        var page=admin.get("/api/admin/users");assertEquals(200,page.statusCode());
        assertFalse(page.body().contains("password"));assertFalse(page.body().contains("totp"));assertFalse(page.body().contains(secret));
        Browser next=new Browser();assertStage(next.login("owner",NORMAL),"MFA_VERIFY");
        assertEquals(403,next.get("/api/admin/users").statusCode());
        assertStage(next.post("/api/auth/mfa/verify",Map.of("code",codes.getFirst())),"AUTHENTICATED");
        Browser replay=new Browser();replay.login("owner",NORMAL);
        assertEquals(401,replay.post("/api/auth/mfa/verify",Map.of("code",codes.getFirst())).statusCode());
    }

    @Test void pendingMfaCannotChangeAnAdministratorsPasswordOrRevokeItsSessions() throws Exception {
        Browser legitimate=admin();var owner=accounts.byUsername("owner").orElseThrow();
        Browser pending=new Browser();assertStage(pending.login("owner",NORMAL),"MFA_VERIFY");
        var denied=pending.post("/api/auth/change-password",Map.of("currentPassword",NORMAL,"newPassword","Final-New-Password2027!"));
        assertEquals(403,denied.statusCode());assertEquals(owner.authVersion(),accounts.byId(owner.id()).orElseThrow().authVersion());
        assertEquals(200,legitimate.get("/api/admin/users").statusCode());
        String secret=crypto.decrypt("totp:"+owner.id(),owner.totpSecret());
        assertStage(pending.post("/api/auth/mfa/verify",Map.of("code",totp.generateCode(secret,Instant.now()))),"AUTHENTICATED");
        assertStage(pending.post("/api/auth/change-password",Map.of("currentPassword",NORMAL,"newPassword","Final-New-Password2027!")),"MFA_VERIFY");
        assertEquals(401,legitimate.get("/api/admin/users").statusCode());
    }

    @Test void registrationIsIdempotentAndNeverStoresPlaintextInEventsOrResults() throws Exception {
        Browser applicant = new Browser();
        UUID key = UUID.randomUUID();
        var body = Map.of("username", "New_Person", "displayName", "Pessoa Nova", "password", NORMAL,
                "role", "ADMIN", "status", "ACTIVE", "mustChangePassword", true);
        HttpResponse<String> created;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> applicant.post("/api/auth/register", body, true, key));
            var second = executor.submit(() -> new Browser().post("/api/auth/register", body, true, key));
            created = first.get(15, TimeUnit.SECONDS);
            var repeated = second.get(15, TimeUnit.SECONDS);
            assertEquals(201, created.statusCode());
            assertEquals(201, repeated.statusCode());
            assertEquals(created.body(), repeated.body());
        }
        assertEquals(Map.of("status", "PENDING_APPROVAL"), JsonPath.read(created.body(), "$"));
        assertEquals(401, applicant.get("/api/auth/me").statusCode());
        assertTrue(applicant.cookies.getCookieStore().getCookies().stream().noneMatch(c -> c.getName().equals("MINHAOBRA_SESSION")));
        var modifiedPassword = Map.of("username", "new_person", "displayName", "Pessoa Nova", "password", TEMP);
        var modifiedName = Map.of("username", "other_person", "displayName", "Pessoa Nova", "password", NORMAL);
        var passwordConflict = applicant.post("/api/auth/register", modifiedPassword, true, key);
        assertEquals(409, passwordConflict.statusCode());
        assertEquals("IDEMPOTENCY_CONFLICT", JsonPath.read(passwordConflict.body(), "$.code"));
        var nameConflict = applicant.post("/api/auth/register", modifiedName, true, key);
        assertEquals(409, nameConflict.statusCode());
        assertEquals("IDEMPOTENCY_CONFLICT", JsonPath.read(nameConflict.body(), "$.code"));
        assertEquals(409, applicant.post("/api/auth/register", modifiedPassword, true, UUID.randomUUID()).statusCode());
        var account = accounts.byUsername("new_person").orElseThrow();
        assertEquals("USER", account.role());
        assertTrue(account.isPendingApproval());
        assertFalse(account.mustChangePassword());
        assertTrue(passwords.matches(NORMAL, account.passwordHash()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app_account", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM account_audit WHERE event_type='ACCOUNT_REQUESTED'", Integer.class));
        for(String value:jdbc.queryForList("SELECT payload FROM account_outbox UNION ALL SELECT response_json FROM account_idempotency",String.class)) {
            assertFalse(value.contains(TEMP));assertFalse(value.contains(NORMAL));assertFalse(value.contains("password_hash"));
        }
    }

    @Test void onlyAdministratorApprovalUnlocksTheApplicantsChosenPassword() throws Exception {
        Browser applicant = new Browser();
        var body = Map.of("username", "requested", "displayName", "Pessoa Solicitante", "password", NORMAL);
        assertEquals(201, applicant.post("/api/auth/register", body, true, UUID.randomUUID()).statusCode());
        var account = accounts.byUsername("requested").orElseThrow();
        UUID target = account.id();
        String path = "/api/admin/users/" + target;
        var version = Map.of("version", account.version());
        var pendingLogin = applicant.login("requested", NORMAL);
        assertEquals(403, pendingLogin.statusCode());
        assertEquals("ACCOUNT_PENDING_APPROVAL", JsonPath.read(pendingLogin.body(), "$.code"));
        assertEquals(401, applicant.get("/api/auth/me").statusCode());
        var wrong = applicant.login("requested", TEMP);
        assertEquals(401, wrong.statusCode());
        assertEquals(applicant.login("unknown", TEMP).body(), wrong.body());
        assertEquals(401, applicant.post(path + "/approve", version, true, UUID.randomUUID()).statusCode());

        insert("other", "USER", false, NORMAL);
        Browser ordinary = new Browser();
        assertStage(ordinary.login("other", NORMAL), "AUTHENTICATED");
        assertEquals(403, ordinary.post(path + "/approve", version, true, UUID.randomUUID()).statusCode());
        Browser administrator = admin();
        var page = administrator.get("/api/admin/users");
        assertEquals(200, page.statusCode());
        assertEquals("requested", JsonPath.read(page.body(), "$.items[0].username"));
        assertEquals("PENDING_APPROVAL", JsonPath.read(page.body(), "$.items[0].status"));
        assertEquals("requested", JsonPath.read(administrator.get("/api/admin/users?search=solicitante").body(), "$.items[0].username"));
        for (String action : List.of("/block", "/unblock")) {
            assertEquals(409, administrator.post(path + action, version, true, UUID.randomUUID()).statusCode());
        }
        assertEquals(409, administrator.post(path + "/temporary-password",
                Map.of("version", account.version(), "temporaryPassword", TEMP), true, UUID.randomUUID()).statusCode());
        assertTrue(accounts.byId(target).orElseThrow().isPendingApproval());

        assertEquals(409, administrator.post(path + "/approve", Map.of("version", account.version() + 1),
                true, UUID.randomUUID()).statusCode());
        UUID approvalKey = UUID.randomUUID();
        var approved = administrator.post(path + "/approve", version, true, approvalKey);
        assertEquals(200, approved.statusCode());
        assertEquals("ACTIVE", JsonPath.read(approved.body(), "$.status"));
        assertEquals(Boolean.FALSE, JsonPath.read(approved.body(), "$.mustChangePassword"));
        assertEquals(approved.body(), administrator.post(path + "/approve", version, true, approvalKey).body());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM account_audit WHERE event_type='ACCOUNT_APPROVED'", Integer.class));
        assertStage(applicant.login("requested", NORMAL), "AUTHENTICATED");
        assertEquals(200, applicant.get("/api/account").statusCode());
    }

    @Test void anOutboxFailureRollsBackAccountAuditAndIdempotencyTogether() {
        long before=jdbc.queryForObject("SELECT count(*) FROM account_audit",Long.class);
        UUID key=UUID.randomUUID();
        jdbc.execute("CREATE FUNCTION reject_account_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Intentional test failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_account_outbox BEFORE INSERT ON account_outbox FOR EACH ROW EXECUTE FUNCTION reject_account_outbox()");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> accountService.register(key,
                    new Accounts.Register("rollback_person", "Pessoa de teste", NORMAL)));
            assertTrue(accounts.byUsername("rollback_person").isEmpty());
            assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM account_audit",Long.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM account_idempotency WHERE request_key=?",Integer.class,key));
        } finally {
            jdbc.execute("DROP TRIGGER reject_account_outbox ON account_outbox");jdbc.execute("DROP FUNCTION reject_account_outbox()");
        }
    }

    @Test void blockingAndResetRevokeEverySessionAndResetDoesNotUnblock() throws Exception {
        Browser admin=admin();UUID target=insert("person","USER",false,NORMAL);
        assertEquals(400,admin.post("/api/admin/users/"+target+"/block",Map.of(),true,UUID.randomUUID()).statusCode());
        assertEquals(400,admin.post("/api/admin/users/"+target+"/block",Collections.singletonMap("version",null),true,UUID.randomUUID()).statusCode());
        assertEquals(400,admin.post("/api/admin/users/"+target+"/temporary-password",Map.of("temporaryPassword",TEMP),true,UUID.randomUUID()).statusCode());
        Map<String,Object> nullVersion=new HashMap<>();nullVersion.put("version",null);nullVersion.put("temporaryPassword",TEMP);
        assertEquals(400,admin.post("/api/admin/users/"+target+"/temporary-password",nullVersion,true,UUID.randomUUID()).statusCode());
        assertEquals(0,accounts.byId(target).orElseThrow().version());
        Browser one=new Browser(),two=new Browser();one.login("person",NORMAL);two.login("person",NORMAL);
        long version=accounts.byId(target).orElseThrow().version();
        var blocked=admin.post("/api/admin/users/"+target+"/block",Map.of("version",version),true,UUID.randomUUID());assertEquals(200,blocked.statusCode());
        assertEquals(401,one.get("/api/auth/me").statusCode());assertEquals(401,two.get("/api/auth/me").statusCode());
        assertEquals(401,new Browser().login("person",NORMAL).statusCode());
        long blockedVersion=accounts.byId(target).orElseThrow().version();
        var reset=admin.post("/api/admin/users/"+target+"/temporary-password",Map.of("version",blockedVersion,"temporaryPassword",TEMP),true,UUID.randomUUID());
        assertEquals(200,reset.statusCode());assertEquals("BLOCKED",JsonPath.read(reset.body(),"$.status"));
        assertTrue((Boolean)JsonPath.read(reset.body(),"$.mustChangePassword"));
        long currentVersion=accounts.byId(target).orElseThrow().version();
        assertEquals(409,admin.post("/api/admin/users/"+target+"/unblock",Map.of("version",version),true,UUID.randomUUID()).statusCode());
        assertEquals(200,admin.post("/api/admin/users/"+target+"/unblock",Map.of("version",currentVersion),true,UUID.randomUUID()).statusCode());
        assertStage(new Browser().login("person",TEMP),"CHANGE_PASSWORD");
    }

    @Test void aRestoredStaleSessionCannotBypassPasswordRevocation() throws Exception {
        Browser admin=admin();UUID target=insert("person","USER",false,NORMAL);Browser browser=new Browser();browser.login("person",NORMAL);
        Map<String,Object> row=jdbc.queryForMap("SELECT * FROM spring_session WHERE principal_name=?",target.toString());
        var attributes=jdbc.queryForList("SELECT attribute_name,attribute_bytes FROM spring_session_attributes WHERE session_primary_id=?",row.get("primary_id"));
        assertFalse(attributes.isEmpty());
        assertEquals(200,admin.post("/api/admin/users/"+target+"/temporary-password",Map.of("version",0,"temporaryPassword",TEMP),true,UUID.randomUUID()).statusCode());
        jdbc.update("INSERT INTO spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name) VALUES (?,?,?,?,?,?,?)",
            row.get("primary_id"),row.get("session_id"),row.get("creation_time"),row.get("last_access_time"),row.get("max_inactive_interval"),row.get("expiry_time"),row.get("principal_name"));
        for(var attribute:attributes)jdbc.update("INSERT INTO spring_session_attributes(session_primary_id,attribute_name,attribute_bytes) VALUES (?,?,?)",row.get("primary_id"),attribute.get("attribute_name"),attribute.get("attribute_bytes"));
        assertEquals(401,browser.get("/api/account").statusCode());
        // An old cookie must not trap the user in an unrecoverable login loop.
        assertStage(browser.login("person",TEMP),"CHANGE_PASSWORD");
    }

    @Test void totpCannotBeConsumedTwiceByConcurrentSessions() throws Exception {
        UUID id=insert("owner","ADMIN",false,NORMAL);String secret=totp.generateSecret();
        jdbc.update("UPDATE app_account SET totp_secret=? WHERE id=?",crypto.encrypt("totp:"+id,secret),id);
        Browser one=new Browser(),two=new Browser();assertStage(one.login("owner",NORMAL),"MFA_VERIFY");two.login("owner",NORMAL);
        String code=totp.generateCode(secret,Instant.now());
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->one.post("/api/auth/mfa/verify",Map.of("code",code)).statusCode());
            var second=executor.submit(()->two.post("/api/auth/mfa/verify",Map.of("code",code)).statusCode());
            var statuses=new ArrayList<>(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS)));Collections.sort(statuses);
            assertEquals(List.of(200,401),statuses);
        }
    }

    @Test void bootstrapIsOneTimeAndDoesNotPromoteAnExistingUser() {
        assertThrows(br.com.minhaobra.service.ApiException.class,()->bootstrap().create("whitespace","            "));
        assertTrue(accounts.byUsername("whitespace").isEmpty());assertEquals(0,accounts.adminCount());
        UUID existing=insert("person","USER",false,NORMAL);
        assertThrows(br.com.minhaobra.service.ApiException.class,()->bootstrap().create("person",TEMP));
        assertEquals("USER",accounts.byId(existing).orElseThrow().role());assertEquals(0,accounts.adminCount());
        bootstrap().create("owner",TEMP);
        assertThrows(br.com.minhaobra.service.ApiException.class,()->bootstrap().create("different",NORMAL));
        assertEquals(1,accounts.adminCount());assertTrue(passwords.matches(TEMP,accounts.byUsername("owner").orElseThrow().passwordHash()));
    }

    @Test void adminCannotBlockOrResetItselfAndLegacyCreationIsUnavailable() throws Exception {
        Browser admin=admin();UUID id=accounts.byUsername("owner").orElseThrow().id();
        assertEquals(403,admin.post("/api/admin/users/"+id+"/block",Map.of("version",0),true,UUID.randomUUID()).statusCode());
        assertEquals(403,admin.post("/api/admin/users/"+id+"/temporary-password",Map.of("version",0,"temporaryPassword",TEMP),true,UUID.randomUUID()).statusCode());
        var removed = admin.post("/api/admin/users", Map.of("username", "legacy_person", "displayName", "Pessoa antiga", "temporaryPassword", TEMP), true, UUID.randomUUID());
        assertTrue(removed.statusCode() >= 400);
        assertTrue(accounts.byUsername("legacy_person").isEmpty());
        insert("person","USER",false,NORMAL);Browser user=new Browser();user.login("person",NORMAL);
        assertEquals(403,user.post("/api/admin/users",Map.of("username","forbidden","displayName","Pessoa","temporaryPassword",TEMP),true,UUID.randomUUID()).statusCode());
    }

    @Test void aWhitespaceOnlyReplacementCannotLockTheUserOut() throws Exception {
        UUID id=insert("person","USER",true,TEMP);Browser browser=new Browser();browser.login("person",TEMP);
        assertEquals(400,browser.post("/api/auth/change-password",Map.of("currentPassword",TEMP,"newPassword","            ")).statusCode());
        assertTrue(accounts.byId(id).orElseThrow().mustChangePassword());
        assertStage(new Browser().login("person",TEMP),"CHANGE_PASSWORD");
    }

    private Browser admin() throws Exception {
        UUID id=insert("owner","ADMIN",false,NORMAL);
        String secret=totp.generateSecret();jdbc.update("UPDATE app_account SET totp_secret=? WHERE id=?",crypto.encrypt("totp:"+id,secret),id);
        String recovery="ABCDEF0123456789ABCDEF0123456789";
        accounts.addRecovery(id,crypto.fingerprint("recovery:"+id,recovery));
        Browser browser=new Browser();assertStage(browser.login("owner",NORMAL),"MFA_VERIFY");
        assertStage(browser.post("/api/auth/mfa/verify",Map.of("code",recovery)),"AUTHENTICATED");return browser;
    }
    private UUID insert(String username,String role,boolean change,String password) {
        var a=accounts.insert(UUID.randomUUID(),username,"Pessoa de teste",passwords.encode(password),role);
        jdbc.update("UPDATE app_account SET must_change_password=? WHERE id=?",change,a.id());return a.id();
    }
    private BootstrapAdminService bootstrap(){return new BootstrapAdminService(accounts,passwords,new TransactionTemplate(transactions));}
    private static void assertStage(HttpResponse<String> r,String expected){assertEquals(200,r.statusCode(),r.body());assertEquals(expected,JsonPath.read(r.body(),"$.stage"));}
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" required; integration tests are not skipped.");return value;}
    private final class Browser {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient http=HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        String uri(String path){return "http://127.0.0.1:"+environment.getProperty("local.server.port")+path;}
        HttpResponse<String> get(String path) throws Exception {return http.send(HttpRequest.newBuilder(URI.create(uri(path))).timeout(Duration.ofSeconds(20)).GET().build(),HttpResponse.BodyHandlers.ofString());}
        void csrf() throws Exception {assertEquals(200,get("/api/auth/csrf").statusCode());}
        HttpResponse<String> login(String username,String password) throws Exception {return post("/api/auth/login",Map.of("username",username,"password",password));}
        HttpResponse<String> post(String path,Object body) throws Exception{return post(path,body,true,null);}
        HttpResponse<String> post(String path,Object body,boolean csrf,UUID key) throws Exception {
            if(csrf)csrf();
            var builder=HttpRequest.newBuilder(URI.create(uri(path))).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
            if(csrf)builder.header("X-XSRF-TOKEN",cookie("XSRF-TOKEN"));if(key!=null)builder.header("Idempotency-Key",key.toString());
            return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body),StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());
        }
        String cookie(String name){return cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals(name)&&!c.hasExpired()).findFirst().orElseThrow().getValue();}
        String sessionCookie(){return cookie("MINHAOBRA_SESSION");}
    }
}
