package com.stockflow;

import java.util.*;
import java.util.concurrent.*;
import com.stockflow.common.error.*;
import com.stockflow.inventory.*;
import com.stockflow.products.*;
import com.stockflow.users.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "app.jwt.secret=integration-test-only-key-not-for-production-123456")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoryIntegrationTests {
    // Committed transactions are essential for real locking/rollback tests.
    // All test-owned rows are isolated from the owner's data and dropped with this schema.
    static final String SCHEMA = "inventory_test_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void schema(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }
    @Autowired WebApplicationContext context;
    @Autowired InventoryService inventory;
    @Autowired ProductService products;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    UUID actor, productId;
    RequestPostProcessor admin;
    final RequestPostProcessor staff = jwt().authorities(new SimpleGrantedAuthority("ROLE_STAFF"));

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).addFilters(context.getBean(RequestIdFilter.class)).build();
        actor = users.saveAndFlush(new UserAccount("inventory-" + UUID.randomUUID() + "@example.test", "unused-test-hash", "ADMIN")).getId();
        productId = products.create(new ProductDtos.CreateProduct("INV-" + UUID.randomUUID(), "Inventory test", null, "10.00", 2, null)).id();
        admin = jwt().jwt(j -> j.subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
    @AfterAll void cleanup() { jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE"); }
    MovementDtos.View record(String type, int delta, String reason) {
        return inventory.record(new MovementDtos.Input(productId, type, delta, reason, "TEST-REFERENCE"), actor);
    }
    String body(String type, String quantity, String reason) {
        return "{\"productId\":\"" + productId + "\",\"type\":\"" + type + "\",\"quantityDelta\":" + quantity + ",\"reason\":" + reason + "}";
    }
    long count() { return jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE product_id=?", Long.class, productId); }
    void reconciles(int expected) {
        assertThat(products.get(productId).stockQuantity()).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(quantity_delta),0) FROM stock_movements WHERE product_id=?", Long.class, productId)).isEqualTo((long) expected);
    }

    @Test void receiptApiRecordsActorAndHistory() throws Exception {
        mvc.perform(post("/api/v1/stock-movements").with(admin).contentType("application/json")
                .content(body("RECEIPT", "10", "null").replace("}", ",\"reference\":\" DELIVERY-1 \"}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.quantityDelta").value(10))
                .andExpect(jsonPath("$.reference").value("DELIVERY-1")).andExpect(jsonPath("$.createdBy").value(actor.toString()))
                .andExpect(jsonPath("$.productId").value(productId.toString())).andExpect(jsonPath("$.createdAt").isString());
        reconciles(10);
        mvc.perform(get("/api/v1/products/" + productId + "/stock-movements").with(staff))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].type").value("RECEIPT"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test void signedAdjustmentsReconcileLedger() {
        record("RECEIPT", 10, null);
        var added = record("ADJUSTMENT", 3, " Count correction ");
        assertThat(added.reason()).isEqualTo("Count correction");
        record("ADJUSTMENT", -4, "Damaged items");
        reconciles(9); assertThat(count()).isEqualTo(3);
    }

    @Test void invalidQuantitiesReasonsAndReservedFieldsFail() throws Exception {
        for (String[] input : new String[][]{{"RECEIPT", "0", "null"}, {"RECEIPT", "-1", "null"},
                {"ADJUSTMENT", "0", "\"reason\""}, {"ADJUSTMENT", "1", "null"}, {"ADJUSTMENT", "1", "\"   \""},
                {"ORDER_CONFIRMATION", "-1", "null"}, {"RECEIPT", "1.5", "null"}, {"RECEIPT", "2147483648", "null"},
                {"RECEIPT", "null", "null"}}) {
            mvc.perform(post("/api/v1/stock-movements").with(admin).contentType("application/json").content(body(input[0], input[1], input[2])))
                    .andExpect(status().isBadRequest());
        }
        for (String field : new String[]{"createdBy", "createdAt", "orderId", "stockQuantity", "unexpected"}) {
            mvc.perform(post("/api/v1/stock-movements").with(admin).contentType("application/json")
                    .content(body("RECEIPT", "1", "null").replace("}", ",\"" + field + "\":null}")))
                    .andExpect(status().isBadRequest());
        }
        assertThat(count()).isZero(); reconciles(0);
    }

    @Test void insufficientStockAndOverflowLeaveNoPartialWrites() throws Exception {
        record("RECEIPT", 1, null);
        mvc.perform(post("/api/v1/stock-movements").with(admin).contentType("application/json").content(body("ADJUSTMENT", "-2", "\"Stock correction\"")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        reconciles(1); assertThat(count()).isEqualTo(1);
        record("RECEIPT", Integer.MAX_VALUE - 1, null);
        mvc.perform(post("/api/v1/stock-movements").with(admin).contentType("application/json").content(body("RECEIPT", "1", "null")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STOCK_LIMIT_EXCEEDED"));
        reconciles(Integer.MAX_VALUE); assertThat(count()).isEqualTo(2);
    }

    @Test void archivedProductsRejectManualWritesButKeepHistory() throws Exception {
        record("RECEIPT", 2, null); products.patch(productId, Map.of("active", false));
        mvc.perform(post("/api/v1/stock-movements").with(admin).contentType("application/json").content(body("RECEIPT", "1", "null")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_ARCHIVED"));
        mvc.perform(get("/api/v1/products/" + productId + "/stock-movements").with(staff)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        reconciles(2);
    }

    @Test void permissionsAndMissingResources() throws Exception {
        mvc.perform(post("/api/v1/stock-movements").with(staff).contentType("application/json").content(body("RECEIPT", "1", "null")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/stock-movements").contentType("application/json").content(body("RECEIPT", "1", "null")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/stock-movements").with(admin).contentType("application/json")
                .content(body("RECEIPT", "1", "null").replace(productId.toString(), UUID.randomUUID().toString())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/products/" + UUID.randomUUID() + "/stock-movements").with(staff)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/products/not-a-uuid/stock-movements").with(staff)).andExpect(status().isBadRequest());
        assertThat(count()).isZero();
    }

    @Test void actorRoleAndActivityAreCheckedBeforeWriting() throws Exception {
        jdbc.update("UPDATE app_users SET role='STAFF' WHERE id=?", actor);
        mvc.perform(post("/api/v1/stock-movements").with(admin).contentType("application/json").content(body("RECEIPT", "1", "null")))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE app_users SET active=false WHERE id=?", actor);
        mvc.perform(post("/api/v1/stock-movements").with(admin).contentType("application/json").content(body("RECEIPT", "1", "null")))
                .andExpect(status().isUnauthorized());
        reconciles(0); assertThat(count()).isZero();
    }

    @Test void paginatedHistoryAndQueryValidation() throws Exception {
        record("RECEIPT", 1, null); record("RECEIPT", 2, null); record("RECEIPT", 3, null);
        mvc.perform(get("/api/v1/products/" + productId + "/stock-movements").with(staff).param("size", "2").param("page", "1").param("sort", "createdAt,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].quantityDelta").value(3));
        for (String[] pair : new String[][]{{"page", "-1"}, {"size", "0"}, {"size", "101"}, {"sort", "type,asc"}, {"sort", "createdAt,invalid"}}) {
            mvc.perform(get("/api/v1/products/" + productId + "/stock-movements").with(staff).param(pair[0], pair[1])).andExpect(status().isBadRequest());
        }
        reconciles(6);
    }

    @Test void ledgerInsertFailureRollsBackAlreadyFlushedBalance() {
        jdbc.execute("CREATE FUNCTION fail_inventory_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected test failure' USING ERRCODE='23514'; END; $$");
        jdbc.execute("CREATE TRIGGER fail_inventory_insert BEFORE INSERT ON stock_movements FOR EACH ROW EXECUTE FUNCTION fail_inventory_insert()");
        try {
            assertThatThrownBy(() -> record("RECEIPT", 10, null)).isInstanceOf(DataIntegrityViolationException.class);
            reconciles(0); assertThat(count()).isZero();
        } finally { jdbc.execute("DROP TRIGGER fail_inventory_insert ON stock_movements"); jdbc.execute("DROP FUNCTION fail_inventory_insert()"); }
    }

    @Test void ledgerCannotBeEditedOrDeletedEvenWithDirectSql() {
        UUID movement = record("RECEIPT", 4, null).id();
        assertThatThrownBy(() -> jdbc.update("UPDATE stock_movements SET quantity_delta=5 WHERE id=?", movement)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM stock_movements WHERE id=?", movement)).isInstanceOf(DataIntegrityViolationException.class);
        reconciles(4); assertThat(count()).isEqualTo(1);
    }

    @Test void simultaneousRemovalsCannotMakeStockNegative() throws Exception {
        record("RECEIPT", 1, null);
        var results = race(() -> { record("ADJUSTMENT", -1, "Concurrent removal"); return "saved"; },
                () -> { try { record("ADJUSTMENT", -1, "Concurrent removal"); return "saved"; } catch (ApiProblem problem) { return problem.code(); } });
        // Either thread can be the winner, so both tasks normalize business conflicts.
        assertThat(results).containsExactlyInAnyOrder("saved", "INSUFFICIENT_STOCK");
        reconciles(0); assertThat(count()).isEqualTo(2);
    }

    @Test void simultaneousReceiptsDoNotLoseUpdates() throws Exception {
        assertThat(race(() -> { record("RECEIPT", 2, null); return "saved"; }, () -> { record("RECEIPT", 3, null); return "saved"; }))
                .containsOnly("saved");
        reconciles(5); assertThat(count()).isEqualTo(2);
    }

    @Test void catalogueUpdateAndReceiptUseSameProductLock() throws Exception {
        assertThat(race(() -> { products.patch(productId, Map.of("name", "Updated catalogue")); return "saved"; },
                () -> { record("RECEIPT", 7, null); return "saved"; })).containsOnly("saved");
        reconciles(7); assertThat(products.get(productId).name()).isEqualTo("Updated catalogue");
    }

    List<String> race(Callable<String> first, Callable<String> second) throws Exception {
        var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<String>>();
            for (var action : List.of(first, second)) futures.add(executor.submit(() -> {
                ready.countDown(); if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start barrier timed out");
                try { return action.call(); } catch (ApiProblem problem) { return problem.code(); }
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); go.countDown();
            return List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS));
        }
    }
}
