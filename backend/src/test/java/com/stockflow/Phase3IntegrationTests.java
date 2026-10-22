package com.stockflow;

import java.util.*;
import java.util.concurrent.*;
import com.stockflow.common.error.*;
import com.stockflow.products.*;
import com.stockflow.suppliers.*;
import com.stockflow.users.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "app.jwt.secret=integration-test-only-key-not-for-production-123456")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Phase3IntegrationTests {
    // Isolated schema: last-admin tests must never alter an owner's real accounts.
    static final String SCHEMA = "phase3_test_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void schema(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired SupplierService suppliers;
    @Autowired ProductService products;
    @Autowired UserService users;
    @Autowired UserRepository accounts;
    @Autowired PasswordEncoder passwords;
    MockMvc mvc;
    final org.springframework.test.web.servlet.request.RequestPostProcessor admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    final org.springframework.test.web.servlet.request.RequestPostProcessor staff = jwt().authorities(new SimpleGrantedAuthority("ROLE_STAFF"));

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).addFilters(context.getBean(RequestIdFilter.class)).build();
    }
    @AfterAll void cleanSchema() { jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE"); }
    SupplierDtos.View supplier() { return suppliers.create(new SupplierDtos.Input("Test supplier", "test@example.test", "123", "Address")); }
    ProductDtos.ProductView product(UUID supplierId) { return products.create(new ProductDtos.CreateProduct("T-" + UUID.randomUUID(), "Test product", "Description", "100.00", 5, supplierId)); }

    @Test @Transactional void supplierLifecyclePreservesExistingLinks() {
        var s = supplier(); var p = product(s.id());
        suppliers.patch(s.id(), Map.of("active", false));
        assertThat(suppliers.get(s.id()).active()).isFalse();
        assertThat(products.get(p.id()).supplierId()).isEqualTo(s.id());
        assertThat(products.patch(p.id(), Map.of("name", "Renamed")).supplierId()).isEqualTo(s.id());
        assertThatThrownBy(() -> product(s.id())).isInstanceOf(ApiProblem.class).hasMessageContaining("archived");
        var other = product(null);
        assertThatThrownBy(() -> products.patch(other.id(), Map.of("supplierId", s.id().toString()))).isInstanceOf(ApiProblem.class).hasMessageContaining("archived");
        assertThatThrownBy(() -> suppliers.patch(s.id(), Map.of("active", true))).isInstanceOf(ApiProblem.class);
        Map<String, Object> clear = new HashMap<>(); clear.put("supplierId", null); clear.put("description", null);
        var cleared = products.patch(p.id(), clear);
        assertThat(cleared.supplierId()).isNull(); assertThat(cleared.description()).isNull();
    }

    @Test @Transactional void productPatchingPreservesStockAndValidatesPresence() throws Exception {
        var p = product(null);
        jdbc.update("UPDATE products SET stock_quantity=9 WHERE id=?", p.id());
        // Clear JPA's cached pre-update row to mimic a separate stock transaction.
        context.getBean(jakarta.persistence.EntityManager.class).clear();
        mvc.perform(patch("/api/v1/products/" + p.id()).with(admin).contentType("application/json").content("{\"name\":\"New name\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stockQuantity").value(9)).andExpect(jsonPath("$.description").value("Description"));
        for (String body : new String[]{"{}", "{\"name\":null}", "{\"stockQuantity\":0}", "{\"unitPrice\":null}", "{\"unitPrice\":-1}", "{\"lowStockThreshold\":1.5}", "{\"active\":null}"}) {
            mvc.perform(patch("/api/v1/products/" + p.id()).with(admin).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        products.patch(p.id(), Map.of("active", false));
        assertThat(products.get(p.id()).active()).isFalse();
        assertThatThrownBy(() -> products.patch(p.id(), Map.of("active", true))).isInstanceOf(ApiProblem.class);
    }

    @Test @Transactional void catalogueFiltersAndSupplierContacts() throws Exception {
        var s = supplier(); var p = product(s.id());
        assertThat(products.list("", 0, 20, "name,asc", "true", s.id(), true).totalElements()).isEqualTo(1);
        products.patch(p.id(), Map.of("active", false));
        assertThat(products.list("", 0, 20, "name,asc", "true", s.id(), false).totalElements()).isZero();
        assertThat(products.list("", 0, 20, "name,asc", "all", s.id(), false).totalElements()).isEqualTo(1);
        assertThat(products.list("", 0, 20, "name,asc", "all", s.id(), true).totalElements()).isZero();
        Map<String, Object> patch = new HashMap<>(); patch.put("email", null);
        assertThat(suppliers.patch(s.id(), patch).email()).isNull();
        mvc.perform(patch("/api/v1/suppliers/" + s.id()).with(admin).contentType("application/json").content("{\"phone\":null,\"address\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Test supplier"));
        mvc.perform(patch("/api/v1/suppliers/" + s.id()).with(admin).contentType("application/json").content("{\"email\":\"bad-email\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/suppliers").with(staff).param("active", "bad")).andExpect(status().isBadRequest());
    }

    @Test @Transactional void backendPermissionsAndNoPasswordLeakage() throws Exception {
        var s = supplier(); var p = product(s.id());
        mvc.perform(post("/api/v1/users").with(admin).contentType("application/json")
                .content("{\"email\":\" STAFF@EXAMPLE.TEST \",\"password\":\"test-only-long-password\",\"role\":\"STAFF\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.email").value("staff@example.test"))
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist());
        var created = accounts.findByEmail("staff@example.test").orElseThrow();
        assertThat(passwords.matches("test-only-long-password", created.getPasswordHash())).isTrue();
        mvc.perform(get("/api/v1/users").with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].passwordHash").doesNotExist());
        mvc.perform(get("/api/v1/users").with(staff)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/users/" + created.getId()).with(staff).contentType("application/json").content("{\"active\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/suppliers").with(staff).contentType("application/json").content("{\"name\":\"Not allowed\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/suppliers/" + s.id()).with(staff).contentType("application/json").content("{\"active\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/products/" + p.id()).with(staff).contentType("application/json").content("{\"active\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/suppliers/" + s.id()).with(staff)).andExpect(status().isOk());
    }

    @Test @Transactional void accountValidationAndLastAdminProtection() throws Exception {
        var first = users.create(new UserService.Create("admin@example.test", "test-only-password", "ADMIN"));
        assertThatThrownBy(() -> users.patch(first.id(), Map.of("active", false))).isInstanceOf(ApiProblem.class).hasMessageContaining("administrator");
        assertThatThrownBy(() -> users.patch(first.id(), Map.of("role", "STAFF"))).isInstanceOf(ApiProblem.class);
        var second = users.create(new UserService.Create("other@example.test", "test-only-password", "ADMIN"));
        assertThat(users.patch(first.id(), Map.of("active", false)).active()).isFalse();
        assertThatThrownBy(() -> users.patch(second.id(), Map.of("active", false))).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> users.create(new UserService.Create(" ADMIN@EXAMPLE.TEST ", "test-only-password", "STAFF"))).isInstanceOf(ApiProblem.class).hasMessageContaining("email");
        mvc.perform(post("/api/v1/users").with(admin).contentType("application/json").content("{\"email\":\"new@example.test\",\"password\":\"short\",\"role\":\"STAFF\"}"))
                .andExpect(status().isBadRequest());
        for (String body : new String[]{"{}", "{\"active\":null}", "{\"role\":null}", "{\"role\":\"ROOT\"}", "{\"password\":\"not-allowed\"}"}) {
            mvc.perform(patch("/api/v1/users/" + first.id()).with(admin).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
    }

    @Test void simultaneousAdminRemovalLeavesOneActiveAdmin() throws Exception {
        var first = accounts.saveAndFlush(new UserAccount("race1@example.test", "unused-test-hash", "ADMIN"));
        var second = accounts.saveAndFlush(new UserAccount("race2@example.test", "unused-test-hash", "ADMIN"));
        var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<String>> futures = new ArrayList<>();
            for (UUID id : List.of(first.getId(), second.getId())) futures.add(executor.submit(() -> {
                ready.countDown(); if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start barrier timed out");
                try { users.patch(id, Map.of("role", "STAFF")); return "updated"; }
                catch (ApiProblem problem) { return problem.code(); }
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); go.countDown();
            assertThat(List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("updated", "LAST_ADMIN_REQUIRED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_users WHERE active AND role='ADMIN'", Long.class)).isEqualTo(1);
        } finally { accounts.deleteById(first.getId()); accounts.deleteById(second.getId()); }
    }
}
