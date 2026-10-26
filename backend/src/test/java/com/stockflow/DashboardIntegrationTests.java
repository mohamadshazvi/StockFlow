package com.stockflow;

import java.util.*;
import com.stockflow.common.error.RequestIdFilter;
import com.stockflow.dashboard.DashboardController;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "app.jwt.secret=integration-test-only-key-not-for-production-123456")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
class DashboardIntegrationTests {
    static final String SCHEMA = "dashboard_test_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void schema(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }
    @Autowired WebApplicationContext context;
    @Autowired DashboardController dashboard;
    @Autowired ProductService products;
    @Autowired InventoryService inventory;
    @Autowired OrderService orders;
    @Autowired OrderLifecycleService lifecycle;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    UUID actor;
    RequestPostProcessor admin;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).addFilters(context.getBean(RequestIdFilter.class)).build();
        actor = users.saveAndFlush(new UserAccount(UUID.randomUUID() + "@example.test", "unused-test-hash", "ADMIN")).getId();
        admin = jwt().jwt(j -> j.subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
    @AfterAll void cleanup() { jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE"); }
    UUID product(String price, int threshold) { return products.create(new ProductDtos.CreateProduct("DS-" + UUID.randomUUID(), "Dashboard test", null, price, threshold, null)).id(); }
    void receive(UUID product, int quantity) { inventory.record(new MovementDtos.Input(product, "RECEIPT", quantity, null, null), actor); }
    OrderDtos.View draft(UUID product, int quantity) { return orders.create(new OrderDtos.Create(null, null, List.of(new OrderDtos.ItemInput(product, quantity))), actor); }

    @Test void emptyDashboardUsesZeroCountsAndDecimalString() throws Exception {
        mvc.perform(get("/api/v1/dashboard/summary").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.activeProductCount").value(0)).andExpect(jsonPath("$.lowStockCount").value(0))
                .andExpect(jsonPath("$.draftOrderCount").value(0)).andExpect(jsonPath("$.confirmedOrderValue").value("0.00"))
                .andExpect(jsonPath("$.currency").value("LKR"));
    }
    @Test void lowStockIncludesEqualityAndZeroButExcludesArchived() {
        var equal = product("1.00", 2); receive(equal, 2);
        product("1.00", 0);
        var above = product("1.00", 1); receive(above, 2);
        var archived = product("1.00", 5); products.patch(archived, Map.of("active", false));
        assertThat(dashboard.summary().activeProductCount()).isEqualTo(3);
        assertThat(dashboard.summary().lowStockCount()).isEqualTo(2);
    }
    @Test void confirmationAndCancellationUpdateMetricsWithoutCountingDraftOrCancelledValue() {
        var product = product("0.10", 2); receive(product, 5);
        var first = draft(product, 3); var second = draft(product, 1); draft(product, 1);
        assertThat(dashboard.summary().draftOrderCount()).isEqualTo(3);
        lifecycle.confirm(first.id(), actor);
        assertThat(dashboard.summary().confirmedOrderValue()).isEqualTo("0.30");
        assertThat(dashboard.summary().draftOrderCount()).isEqualTo(2);
        assertThat(dashboard.summary().lowStockCount()).isEqualTo(1);
        lifecycle.cancel(second.id(), actor); lifecycle.cancel(first.id(), actor);
        assertThat(dashboard.summary().confirmedOrderValue()).isEqualTo("0.00");
        assertThat(dashboard.summary().draftOrderCount()).isEqualTo(1);
        assertThat(dashboard.summary().lowStockCount()).isZero();
    }
    @Test void archivedProductsDoNotRemoveConfirmedOrderValueAndCatalogueChangesDoNotRepriceIt() {
        var product = product("12.50", 0); receive(product, 3);
        lifecycle.confirm(draft(product, 2).id(), actor);
        products.patch(product, Map.of("unitPrice", "99.99", "active", false));
        assertThat(dashboard.summary().activeProductCount()).isZero();
        assertThat(dashboard.summary().confirmedOrderValue()).isEqualTo("25.00");
    }
    @Test void aggregateRetainsExactLargeAmountsAcrossMultipleOrders() {
        var product = product("9999999999.99", 0); receive(product, 2000000);
        lifecycle.confirm(draft(product, 1000000).id(), actor); lifecycle.confirm(draft(product, 1000000).id(), actor);
        assertThat(dashboard.summary().confirmedOrderValue()).isEqualTo("19999999999980000.00");
    }
    @Test void bothRolesCanReadButAnonymousCannot() throws Exception {
        mvc.perform(get("/api/v1/dashboard/summary")).andExpect(status().isUnauthorized());
        var staff = jwt().authorities(new SimpleGrantedAuthority("ROLE_STAFF"));
        mvc.perform(get("/api/v1/dashboard/summary").with(staff)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/dashboard/summary").with(admin)).andExpect(status().isOk());
    }
    @Test void unlikeCurrenciesAreNeverSilentlyAdded() throws Exception {
        var product = product("1.00", 0); receive(product, 1); var confirmed = lifecycle.confirm(draft(product, 1).id(), actor);
        jdbc.update("UPDATE orders SET currency='USD' WHERE id=?", confirmed.id());
        mvc.perform(get("/api/v1/dashboard/summary").with(admin)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));
    }
}
