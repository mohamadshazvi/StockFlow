package com.stockflow;

import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import com.stockflow.common.error.*;
import com.stockflow.inventory.*;
import com.stockflow.orders.*;
import com.stockflow.products.*;
import com.stockflow.users.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class OrderLifecycleIntegrationTests {
    static final String SCHEMA = "lifecycle_test_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void schema(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }
    @Autowired WebApplicationContext context;
    @Autowired OrderService orders;
    @Autowired OrderLifecycleService lifecycle;
    @Autowired ProductService products;
    @Autowired InventoryService inventory;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    MockMvc mvc;
    UUID actor, other, admin, product;
    RequestPostProcessor staff;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).addFilters(context.getBean(RequestIdFilter.class)).build();
        actor = user("STAFF"); other = user("STAFF"); admin = user("ADMIN");
        product = product("Lifecycle", 0);
        staff = jwt().jwt(j -> j.subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_STAFF"));
    }
    UUID user(String role) { return users.saveAndFlush(new UserAccount(UUID.randomUUID() + "@example.test", "unused-test-hash", role)).getId(); }
    UUID product(String name, int stock) {
        var id = products.create(new ProductDtos.CreateProduct("LC-" + UUID.randomUUID(), name, null, "1.25", 0, null)).id();
        if (stock > 0) movement(id, "RECEIPT", stock);
        return id;
    }
    void movement(UUID id, String type, int quantity) { inventory.record(new MovementDtos.Input(id, type, quantity, "Test stock", null), admin); }
    OrderDtos.View draft(int quantity) { return draft(new OrderDtos.ItemInput(product, quantity)); }
    OrderDtos.View draft(OrderDtos.ItemInput... lines) { return orders.get(orders.create(new OrderDtos.Create("Lifecycle customer", "LIFECYCLE", List.of(lines)), actor).id()); }
    void balance(UUID id, int expected) {
        assertThat(products.get(id).stockQuantity()).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(quantity_delta),0) FROM stock_movements WHERE product_id=?", Long.class, id)).isEqualTo((long) expected);
    }
    long effects(UUID id, String type) { return jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE order_id=? AND type=?", Long.class, id, type); }
    @AfterAll void cleanup() { jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE"); }

    @Test void confirmsMultipleItemsWithExactSignedMovementsAndAudit() throws Exception {
        movement(product, "RECEIPT", 10); var second = product("Second", 7);
        var draft = draft(new OrderDtos.ItemInput(second, 3), new OrderDtos.ItemInput(product, 4));
        mvc.perform(post("/api/v1/orders/" + draft.id() + "/confirm").with(staff)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED")).andExpect(jsonPath("$.confirmedBy").value(actor.toString()))
                .andExpect(jsonPath("$.confirmedAt").isString()).andExpect(jsonPath("$.total").value("8.75"));
        balance(product, 6); balance(second, 4); assertThat(effects(draft.id(), "ORDER_CONFIRMATION")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT quantity_delta FROM stock_movements WHERE order_id=? ORDER BY quantity_delta", Integer.class, draft.id())).containsExactly(-4, -3);
        assertThat(jdbc.queryForList("SELECT DISTINCT created_by FROM stock_movements WHERE order_id=?", UUID.class, draft.id())).containsExactly(actor);
        assertThat(jdbc.queryForList("SELECT DISTINCT reference FROM stock_movements WHERE order_id=?", String.class, draft.id())).containsExactly(draft.orderNumber());
    }

    @Test void insufficientItemLeavesEveryBalanceMovementAndOrderUnchanged() throws Exception {
        movement(product, "RECEIPT", 5); var empty = product("Empty", 0);
        var draft = draft(new OrderDtos.ItemInput(product, 2), new OrderDtos.ItemInput(empty, 1));
        mvc.perform(post("/api/v1/orders/" + draft.id() + "/confirm").with(staff)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        assertThat(orders.get(draft.id()).status()).isEqualTo("DRAFT"); assertThat(orders.get(draft.id()).confirmedAt()).isNull();
        assertThat(effects(draft.id(), "ORDER_CONFIRMATION")).isZero(); balance(product, 5); balance(empty, 0);
    }

    @Test void twoOrdersCompeteForOneUnit() throws Exception {
        movement(product, "RECEIPT", 1); var first = draft(1); var second = draft(1);
        assertThat(race(() -> lifecycle.confirm(first.id(), actor).status(), () -> lifecycle.confirm(second.id(), other).status()))
                .containsExactlyInAnyOrder("CONFIRMED", "INSUFFICIENT_STOCK");
        balance(product, 0);
        assertThat(effects(first.id(), "ORDER_CONFIRMATION") + effects(second.id(), "ORDER_CONFIRMATION")).isEqualTo(1);
    }

    @Test void concurrentAndSequentialConfirmationsDeductOnceAndPreserveFirstAudit() throws Exception {
        movement(product, "RECEIPT", 5); var draft = draft(2);
        assertThat(race(() -> lifecycle.confirm(draft.id(), actor).status(), () -> lifecycle.confirm(draft.id(), other).status())).containsOnly("CONFIRMED");
        var saved = orders.get(draft.id());
        assertThat(lifecycle.confirm(draft.id(), admin)).isEqualTo(saved);
        balance(product, 3); assertThat(effects(draft.id(), "ORDER_CONFIRMATION")).isEqualTo(1);
    }

    @Test void concurrentAndSequentialCancellationsRestoreOnceAndKeepConfirmationAudit() throws Exception {
        movement(product, "RECEIPT", 5); var confirmed = lifecycle.confirm(draft(2).id(), actor);
        assertThat(race(() -> lifecycle.cancel(confirmed.id(), actor).status(), () -> lifecycle.cancel(confirmed.id(), other).status())).containsOnly("CANCELLED");
        var cancelled = orders.get(confirmed.id());
        assertThat(cancelled.confirmedAt()).isEqualTo(confirmed.confirmedAt()); assertThat(cancelled.confirmedBy()).isEqualTo(actor);
        assertThat(lifecycle.cancel(confirmed.id(), admin)).isEqualTo(cancelled);
        balance(product, 5); assertThat(effects(confirmed.id(), "ORDER_CANCELLATION")).isEqualTo(1);
    }

    @Test void confirmVersusCancelRaceEndsCancelledWithConsistentLedger() throws Exception {
        movement(product, "RECEIPT", 1); var draft = draft(1);
        var results = race(() -> lifecycle.confirm(draft.id(), actor).status(), () -> lifecycle.cancel(draft.id(), other).status());
        assertThat(results.get(1)).isEqualTo("CANCELLED"); assertThat(results.get(0)).isIn("CONFIRMED", "INVALID_ORDER_STATE");
        assertThat(orders.get(draft.id()).status()).isEqualTo("CANCELLED"); balance(product, 1);
        assertThat(effects(draft.id(), "ORDER_CONFIRMATION")).isEqualTo(effects(draft.id(), "ORDER_CANCELLATION"));
    }

    @Test void draftCancellationNeverWritesStockAndCannotBeConfirmedAgain() throws Exception {
        var draft = draft(3); var cancelled = lifecycle.cancel(draft.id(), actor);
        assertThat(cancelled.confirmedAt()).isNull(); assertThat(cancelled.cancelledBy()).isEqualTo(actor);
        assertThat(lifecycle.cancel(draft.id(), other)).isEqualTo(cancelled);
        mvc.perform(post("/api/v1/orders/" + draft.id() + "/confirm").with(staff)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_ORDER_STATE"));
        balance(product, 0); assertThat(effects(draft.id(), "ORDER_CANCELLATION")).isZero();
    }

    @Test void archivalBlocksConfirmationButNotRestorationOrRepeatConfirmation() throws Exception {
        movement(product, "RECEIPT", 5); var draft = draft(1); var confirmed = lifecycle.confirm(draft(2).id(), actor);
        products.patch(product, Map.of("active", false));
        mvc.perform(post("/api/v1/orders/" + draft.id() + "/confirm").with(staff)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_ARCHIVED"));
        assertThat(lifecycle.confirm(confirmed.id(), other)).isEqualTo(confirmed);
        lifecycle.cancel(confirmed.id(), other); balance(product, 5);
    }

    @Test void cancellationOverflowRollsBackAllItemsAndAudit() throws Exception {
        movement(product, "RECEIPT", 1); var second = product("Second", 1);
        var confirmed = lifecycle.confirm(draft(new OrderDtos.ItemInput(product, 1), new OrderDtos.ItemInput(second, 1)).id(), actor);
        movement(product, "RECEIPT", Integer.MAX_VALUE);
        mvc.perform(post("/api/v1/orders/" + confirmed.id() + "/cancel").with(staff)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STOCK_LIMIT_EXCEEDED"));
        assertThat(orders.get(confirmed.id())).isEqualTo(confirmed); assertThat(effects(confirmed.id(), "ORDER_CANCELLATION")).isZero();
        balance(product, Integer.MAX_VALUE); balance(second, 0);
    }

    @Test void adjustmentsAndCatalogueUpdatesCannotOverwriteConfirmation() throws Exception {
        movement(product, "RECEIPT", 10); var draft = draft(3);
        assertThat(race(() -> lifecycle.confirm(draft.id(), actor).status(), () -> { movement(product, "ADJUSTMENT", -2); return "adjusted"; }))
                .containsExactly("CONFIRMED", "adjusted"); balance(product, 5);
        var next = draft(1);
        assertThat(race(() -> lifecycle.confirm(next.id(), actor).status(), () -> { products.patch(product, Map.of("name", "Updated catalogue")); return "updated"; }))
                .containsExactly("CONFIRMED", "updated"); balance(product, 4);
        assertThat(products.get(product).name()).isEqualTo("Updated catalogue");
    }

    @Test void overlappingMultiProductOrdersUseSameLockOrder() throws Exception {
        movement(product, "RECEIPT", 3); var second = product("Other", 3);
        var first = draft(new OrderDtos.ItemInput(product, 1), new OrderDtos.ItemInput(second, 1));
        var next = draft(new OrderDtos.ItemInput(second, 1), new OrderDtos.ItemInput(product, 1));
        assertThat(race(() -> lifecycle.confirm(first.id(), actor).status(), () -> lifecycle.confirm(next.id(), other).status())).containsOnly("CONFIRMED");
        balance(product, 1); balance(second, 1);
    }

    @Test void secondMovementFailureRollsBackPreviouslyFlushedEffectsForBothTransitions() {
        movement(product, "RECEIPT", 3); var second = product("Second", 3);
        var draft = draft(new OrderDtos.ItemInput(product, 1), new OrderDtos.ItemInput(second, 1));
        // Reject the second UUID-sorted item so the first product/movement have already flushed.
        UUID last = List.of(product, second).stream().max(Comparator.comparing(UUID::toString)).orElseThrow();
        jdbc.execute("CREATE FUNCTION fail_lifecycle_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.product_id='" + last + "'::uuid AND NEW.order_id='" + draft.id() + "'::uuid THEN RAISE EXCEPTION 'Injected ledger failure' USING ERRCODE='23514'; END IF; RETURN NEW; END; $$");
        try {
            jdbc.execute("CREATE TRIGGER fail_lifecycle_insert BEFORE INSERT ON stock_movements FOR EACH ROW EXECUTE FUNCTION fail_lifecycle_insert()");
            assertThatThrownBy(() -> lifecycle.confirm(draft.id(), actor)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(orders.get(draft.id())).isEqualTo(draft); balance(product, 3); balance(second, 3);
            assertThat(effects(draft.id(), "ORDER_CONFIRMATION")).isZero();
            jdbc.execute("DROP TRIGGER fail_lifecycle_insert ON stock_movements");
            var confirmed = lifecycle.confirm(draft.id(), actor);
            jdbc.execute("CREATE TRIGGER fail_lifecycle_insert BEFORE INSERT ON stock_movements FOR EACH ROW EXECUTE FUNCTION fail_lifecycle_insert()");
            assertThatThrownBy(() -> lifecycle.cancel(draft.id(), actor)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(orders.get(draft.id())).isEqualTo(confirmed); balance(product, 2); balance(second, 2);
            assertThat(effects(draft.id(), "ORDER_CANCELLATION")).isZero();
        } finally { jdbc.execute("DROP TRIGGER IF EXISTS fail_lifecycle_insert ON stock_movements"); jdbc.execute("DROP FUNCTION fail_lifecycle_insert()"); }
    }

    @Test void realProductLockTimeoutReturnsResourceBusyAfterRollback() throws Exception {
        movement(product, "RECEIPT", 2); var draft = draft(1);
        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement("SELECT id FROM products WHERE id=? FOR UPDATE")) {
                lock.setObject(1, product); lock.executeQuery().close();
                mvc.perform(post("/api/v1/orders/" + draft.id() + "/confirm").with(staff)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_BUSY"));
                assertThat(orders.get(draft.id())).isEqualTo(draft); assertThat(effects(draft.id(), "ORDER_CONFIRMATION")).isZero();
            } finally { holder.rollback(); }
        }
        balance(product, 2);
        assertThat(lifecycle.confirm(draft.id(), actor).status()).isEqualTo("CONFIRMED"); balance(product, 1);
    }

    @Test void apiRejectsBodiesMissingOrdersAndInactiveActors() throws Exception {
        var draft = draft(1);
        for (String action : List.of("confirm", "cancel")) {
            mvc.perform(post("/api/v1/orders/" + draft.id() + "/" + action)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v1/orders/" + UUID.randomUUID() + "/" + action).with(staff)).andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/orders/" + draft.id() + "/" + action).with(staff).contentType("application/json").content("{\"createdBy\":\"spoofed\"}"))
                    .andExpect(status().isBadRequest());
        }
        jdbc.update("UPDATE app_users SET active=false WHERE id=?", actor);
        mvc.perform(post("/api/v1/orders/" + draft.id() + "/cancel").with(staff)).andExpect(status().isUnauthorized());
        assertThat(orders.get(draft.id())).isEqualTo(draft);
    }

    @Test void realDeadlockHasNoPartialEffectsAndCanBeRetried() throws Exception {
        movement(product, "RECEIPT", 2); var draft = draft(1);
        try (var holder = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try {
                int holderPid;
                try (var statement = holder.createStatement(); var result = statement.executeQuery("SELECT pg_backend_pid()")) { result.next(); holderPid = result.getInt(1); }
                try (var lock = holder.prepareStatement("SELECT id FROM products WHERE id=? FOR UPDATE")) { lock.setObject(1, product); lock.executeQuery().close(); }
                var response = executor.submit(() -> mvc.perform(post("/api/v1/orders/" + draft.id() + "/confirm").with(staff)).andReturn());
                // Observe the actual product-lock wait, not a timing sleep, before making the cycle.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    waiting = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))", Boolean.class, holderPid));
                    if (waiting) break;
                    Thread.onSpinWait();
                }
                assertThat(waiting).as("lifecycle transaction must hold order lock and wait for product").isTrue();
                boolean holderVictim = false;
                try (var lock = holder.prepareStatement("SELECT id FROM orders WHERE id=? FOR UPDATE")) { lock.setObject(1, draft.id()); lock.executeQuery().close(); }
                catch (java.sql.SQLException exception) { assertThat(exception.getSQLState()).isEqualTo("40P01"); holderVictim = true; }
                finally { holder.rollback(); }
                var result = response.get(12, TimeUnit.SECONDS);
                // PostgreSQL can choose either participant as victim. Validate both legitimate outcomes.
                if (holderVictim) {
                    assertThat(result.getResponse().getStatus()).isEqualTo(200); balance(product, 1);
                } else {
                    assertThat(result.getResponse().getStatus()).isEqualTo(409);
                    assertThat(result.getResponse().getContentAsString()).contains("RESOURCE_BUSY");
                    assertThat(orders.get(draft.id())).isEqualTo(draft); balance(product, 2);
                    assertThat(effects(draft.id(), "ORDER_CONFIRMATION")).isZero();
                }
            } finally { holder.rollback(); }
        }
        assertThat(lifecycle.confirm(draft.id(), actor).status()).isEqualTo("CONFIRMED");
        balance(product, 1); assertThat(effects(draft.id(), "ORDER_CONFIRMATION")).isEqualTo(1);
    }

    @Test void uniqueOrderEffectIndexRejectsDuplicateMovement() {
        movement(product, "RECEIPT", 1); var confirmed = lifecycle.confirm(draft(1).id(), actor);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO stock_movements(id,product_id,type,quantity_delta,order_id,created_by) VALUES (?,?,'ORDER_CONFIRMATION',-1,?,?)", UUID.randomUUID(), product, confirmed.id(), actor))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        balance(product, 0); assertThat(effects(confirmed.id(), "ORDER_CONFIRMATION")).isEqualTo(1);
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
