package com.stockflow;

import java.util.*;
import java.util.concurrent.*;
import com.stockflow.common.error.RequestIdFilter;
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
class OrderIntegrationTests {
    static final String SCHEMA = "orders_test_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void schema(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }
    @Autowired WebApplicationContext context;
    @Autowired OrderService orders;
    @Autowired ProductService products;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    UUID actor, product;
    RequestPostProcessor staff;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).addFilters(context.getBean(RequestIdFilter.class)).build();
        actor = users.saveAndFlush(new UserAccount("orders-" + UUID.randomUUID() + "@example.test", "unused-test-hash", "STAFF")).getId();
        product = products.create(new ProductDtos.CreateProduct("ORD-" + UUID.randomUUID(), "Snapshot product", null, "0.10", 0, null)).id();
        staff = jwt().jwt(j -> j.subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_STAFF"));
    }
    @AfterAll void cleanup() { jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE"); }
    String item(UUID id, String quantity) { return "{\"productId\":\"" + id + "\",\"quantity\":" + quantity + "}"; }
    String body(String items) { return "{\"customerName\":\" Customer \",\"reference\":\" REF \",\"items\":[" + items + "]}"; }
    OrderDtos.View create(int quantity) { return orders.create(new OrderDtos.Create("Customer", "REF", List.of(new OrderDtos.ItemInput(product, quantity))), actor); }
    long count() { return jdbc.queryForObject("SELECT count(*) FROM orders WHERE created_by=?", Long.class, actor); }

    @Test void staffCreatesAndReadsAuthoritativeDraftWithoutStockEffects() throws Exception {
        mvc.perform(post("/api/v1/orders").with(staff).contentType("application/json").content(body(item(product, "3"))))
                .andExpect(status().isCreated()).andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/orders/")))
                .andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.total").value("0.30"))
                .andExpect(jsonPath("$.currency").value("LKR")).andExpect(jsonPath("$.customerName").value("Customer"))
                .andExpect(jsonPath("$.reference").value("REF")).andExpect(jsonPath("$.createdBy").value(actor.toString()))
                .andExpect(jsonPath("$.items[0].unitPriceSnapshot").value("0.10"));
        assertThat(products.get(product).stockQuantity()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements", Long.class)).isZero();
        mvc.perform(get("/api/v1/orders").with(staff)).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].items").doesNotExist());
    }
    @Test void savedSnapshotsSurviveCatalogueEditsAndArchival() throws Exception {
        var draft = create(7);
        products.patch(product, Map.of("name", "Renamed", "unitPrice", "99.99", "active", false));
        mvc.perform(get("/api/v1/orders/" + draft.id()).with(staff)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("0.70")).andExpect(jsonPath("$.items[0].nameSnapshot").value("Snapshot product"))
                .andExpect(jsonPath("$.items[0].unitPriceSnapshot").value("0.10"));
    }
    @Test void emptyOversizedDuplicateAndInvalidQuantitiesAreRejected() throws Exception {
        List<String> inputs = new ArrayList<>(List.of("", item(product, "1") + "," + item(product, "2"), "null"));
        for (String quantity : List.of("0", "-1", "1000001", "1.5", "null")) inputs.add(item(product, quantity));
        inputs.add(String.join(",", Collections.nCopies(101, item(product, "1"))));
        for (String input : inputs) mvc.perform(post("/api/v1/orders").with(staff).contentType("application/json").content(body(input))).andExpect(status().isBadRequest());
        assertThat(count()).isZero();
    }
    @Test void clientOwnedPricesTotalsStatusAndActorsAreRejected() throws Exception {
        for (String field : List.of("total", "currency", "status", "createdBy", "createdAt", "orderNumber"))
            mvc.perform(post("/api/v1/orders").with(staff).contentType("application/json").content(body(item(product, "1")).replace("{\"customerName", "{\"" + field + "\":null,\"customerName"))).andExpect(status().isBadRequest());
        for (String field : List.of("unitPrice", "unitPriceSnapshot", "lineTotal", "skuSnapshot"))
            mvc.perform(post("/api/v1/orders").with(staff).contentType("application/json").content(body(item(product, "1").replace("}", ",\"" + field + "\":null}")))).andExpect(status().isBadRequest());
        assertThat(count()).isZero();
    }
    @Test void archivedAndMissingProductsRejectWholeDraft() throws Exception {
        var second = products.create(new ProductDtos.CreateProduct("SECOND-" + UUID.randomUUID(), "Second", null, "1.00", 0, null)).id();
        products.patch(second, Map.of("active", false));
        mvc.perform(post("/api/v1/orders").with(staff).contentType("application/json").content(body(item(product, "1") + "," + item(second, "1"))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/orders").with(staff).contentType("application/json").content(body(item(UUID.randomUUID(), "1"))))
                .andExpect(status().isNotFound());
        assertThat(count()).isZero();
    }
    @Test void failedItemInsertRollsBackOrder() {
        jdbc.execute("CREATE FUNCTION fail_order_item() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test failure' USING ERRCODE='23514'; END; $$");
        jdbc.execute("CREATE TRIGGER fail_order_item BEFORE INSERT ON order_items FOR EACH ROW EXECUTE FUNCTION fail_order_item()");
        try { assertThatThrownBy(() -> create(1)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); assertThat(count()).isZero(); }
        finally { jdbc.execute("DROP TRIGGER fail_order_item ON order_items"); jdbc.execute("DROP FUNCTION fail_order_item()"); }
    }
    @Test void sequenceExpandsAndConcurrentDraftsGetUniqueNumbers() throws Exception {
        jdbc.queryForObject("SELECT setval('order_number_seq', 99999999)", Long.class);
        assertThat(create(1).orderNumber()).isEqualTo("SF-100000000");
        var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<String> action = () -> { ready.countDown(); if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout"); return create(1).orderNumber(); };
            var first = executor.submit(action); var second = executor.submit(action);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); go.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isNotEqualTo(second.get(15, TimeUnit.SECONDS));
        }
    }
    @Test void listFiltersPaginationAndInvalidQueries() throws Exception {
        var first = create(1); create(2); create(3);
        mvc.perform(get("/api/v1/orders").with(staff).param("status", "DRAFT").param("createdFrom", first.createdAt().minusSeconds(1).toString()).param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2));
        mvc.perform(get("/api/v1/orders").with(staff).param("status", "CONFIRMED")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        for (String[] pair : new String[][]{{"status", "WRONG"}, {"size", "101"}, {"page", "-1"}, {"sort", "status,asc"}, {"createdFrom", "bad-date"}})
            mvc.perform(get("/api/v1/orders").with(staff).param(pair[0], pair[1])).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders").with(staff).param("createdFrom", "2026-01-02T00:00:00Z").param("createdTo", "2026-01-01T00:00:00Z")).andExpect(status().isBadRequest());
    }
    @Test void authenticationAndMissingOrder() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType("application/json").content(body(item(product, "1")))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/orders/" + UUID.randomUUID()).with(staff)).andExpect(status().isNotFound());
        jdbc.update("UPDATE app_users SET active=false WHERE id=?", actor);
        mvc.perform(post("/api/v1/orders").with(staff).contentType("application/json").content(body(item(product, "1")))).andExpect(status().isUnauthorized());
    }
    @Test void maximumQuantityAndPriceStayExact() {
        products.patch(product, Map.of("unitPrice", "9999999999.99"));
        assertThat(create(1000000).total()).isEqualTo("9999999999990000.00");
    }

    @Test void hundredDistinctItemsHaveExactTotalsAndNoInventoryEffects() {
        var input = new ArrayList<OrderDtos.ItemInput>();
        for (int index = 0; index < 100; index++) {
            UUID id = products.create(new ProductDtos.CreateProduct("BULK-" + UUID.randomUUID(), "Bulk item", null, "0.10", 0, null)).id();
            input.add(new OrderDtos.ItemInput(id, 3));
        }
        var draft = orders.create(new OrderDtos.Create(null, null, input), actor);
        assertThat(draft.items()).hasSize(100);
        assertThat(draft.total()).isEqualTo("30.00");
        assertThat(jdbc.queryForObject("SELECT sum(stock_quantity) FROM products", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements", Long.class)).isZero();
    }

    @Test void dateFiltersUseInclusiveStartAndExclusiveEnd() throws Exception {
        var draft = create(1);
        // Explicit microsecond-aligned database time avoids driver precision differences.
        var boundary = java.time.Instant.parse("2026-06-01T00:00:00Z");
        jdbc.update("UPDATE orders SET created_at=? WHERE id=?", java.sql.Timestamp.from(boundary), draft.id());
        assertThat(orders.list("", boundary, boundary.plusSeconds(1), 0, 100, "createdAt,asc").content())
                .extracting(OrderDtos.Summary::id).contains(draft.id());
        assertThat(orders.list("", boundary.minusSeconds(1), boundary, 0, 100, "createdAt,asc").content())
                .extracting(OrderDtos.Summary::id).doesNotContain(draft.id());
        var admin = jwt().jwt(j -> j.subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        mvc.perform(get("/api/v1/orders/" + draft.id()).with(admin)).andExpect(status().isOk());
    }
}
