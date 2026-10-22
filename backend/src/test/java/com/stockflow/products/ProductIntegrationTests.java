package com.stockflow.products;

import java.util.UUID;
import com.stockflow.common.error.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.jwt.secret=integration-test-only-key-not-for-production-123456")
@Transactional
class ProductIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired ProductRepository products;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    String prefix;
    RequestPostProcessor admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    RequestPostProcessor staff = jwt().authorities(new SimpleGrantedAuthority("ROLE_STAFF"));

    @BeforeEach void setup() {
        prefix = ("T-" + UUID.randomUUID()).toUpperCase(java.util.Locale.ROOT);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
                .addFilters(context.getBean(RequestIdFilter.class)).build();
    }
    String body(String sku, String price, String threshold) {
        return "{\"sku\":\"" + sku + "\",\"name\":\" White Rice 1kg \",\"unitPrice\":\"" + price
                + "\",\"lowStockThreshold\":" + threshold + "}";
    }

    @Test void createsNormalizedProductWithZeroStock() throws Exception {
        mvc.perform(post("/api/v1/products").with(admin).contentType("application/json")
                .content(body(" " + prefix.toLowerCase() + " ", "450.5", "5")))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.sku").value(prefix.toUpperCase()))
                .andExpect(jsonPath("$.name").value("White Rice 1kg"))
                .andExpect(jsonPath("$.unitPrice").value("450.50"))
                .andExpect(jsonPath("$.stockQuantity").value(0))
                .andExpect(jsonPath("$.active").value(true));
        assertThat(products.existsBySku(prefix.toUpperCase())).isTrue();
    }

    @Test void rejectsNormalizedDuplicateSku() throws Exception {
        mvc.perform(post("/api/v1/products").with(admin).contentType("application/json").content(body(prefix, "1", "0")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/products").with(admin).contentType("application/json").content(body(prefix.toLowerCase(), "1", "0")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));
    }

    @Test void rejectsInvalidMoneyAndThreshold() throws Exception {
        for (String price : new String[]{"-1", "1.001", "10000000000.00", "NaN", ""}) {
            mvc.perform(post("/api/v1/products").with(admin).contentType("application/json").content(body(prefix, price, "0")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("unitPrice"));
        }
        for (String threshold : new String[]{"-1", "null", "2147483648", "1.5"}) {
            mvc.perform(post("/api/v1/products").with(admin).contentType("application/json").content(body(prefix, "0.00", threshold)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test void rejectsInvalidSkuAndBlankName() throws Exception {
        mvc.perform(post("/api/v1/products").with(admin).contentType("application/json").content(body("invalid sku!", "1", "0")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/products").with(admin).contentType("application/json")
                .content(body(prefix, "1", "0").replace(" White Rice 1kg ", "   ")))
                .andExpect(status().isBadRequest());
    }

    @Test void refusesClientStockAndUnknownFields() throws Exception {
        for (String field : new String[]{"stockQuantity", "active", "total", "unexpected"}) {
            String json = body(prefix, "1", "0").replace("}", ",\"" + field + "\":12}");
            mvc.perform(post("/api/v1/products").with(admin).contentType("application/json").content(json))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test void staffReadAllowedAndCreateForbidden() throws Exception {
        mvc.perform(post("/api/v1/products").with(staff).contentType("application/json").content(body(prefix, "1", "0")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/products").with(staff)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/products")).andExpect(status().isUnauthorized());
    }

    @Test void detailAndMissingOrMalformedIds() throws Exception {
        Product product = products.saveAndFlush(new Product(prefix, "Test", null, new java.math.BigDecimal("1.00"), 0, null));
        mvc.perform(get("/api/v1/products/" + product.getId()).with(staff))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(product.getId().toString()));
        mvc.perform(get("/api/v1/products/" + UUID.randomUUID()).with(staff))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        mvc.perform(get("/api/v1/products/not-a-uuid").with(staff)).andExpect(status().isBadRequest());
    }

    @Test void paginationAndLiteralSearch() throws Exception {
        String sku = prefix.toUpperCase();
        products.saveAndFlush(new Product(sku + "_ONE", "One", null, new java.math.BigDecimal("1.00"), 0, null));
        products.saveAndFlush(new Product(sku + "-TWO", "Two", null, new java.math.BigDecimal("2.00"), 0, null));
        mvc.perform(get("/api/v1/products").with(staff).param("search", sku.toLowerCase()).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(get("/api/v1/products").with(staff).param("search", sku + "_"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/products").with(staff).param("search", sku).param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(1));
    }

    @Test void validatesQueryBoundsAndSort() throws Exception {
        for (String[] pair : new String[][]{{"page", "-1"}, {"size", "0"}, {"size", "101"}, {"sort", "passwordHash,asc"},
                {"sort", "name,bad"}, {"size", "abc"}, {"page", "2147483647"}}) {
            mvc.perform(get("/api/v1/products").with(staff).param(pair[0], pair[1])).andExpect(status().isBadRequest());
        }
    }

    @Test void unknownAndArchivedSupplierRejected() throws Exception {
        String base = body(prefix, "1", "0");
        mvc.perform(post("/api/v1/products").with(admin).contentType("application/json")
                .content(base.replace("}", ",\"supplierId\":\"" + UUID.randomUUID() + "\"}")))
                .andExpect(status().isNotFound());
        UUID supplier = UUID.randomUUID();
        jdbc.update("INSERT INTO suppliers(id,name,active) VALUES (?, 'Archived', false)", supplier);
        mvc.perform(post("/api/v1/products").with(admin).contentType("application/json")
                .content(base.replace("}", ",\"supplierId\":\"" + supplier + "\"}")))
                .andExpect(status().isConflict());
    }

    @Test void openApiAvailableWithoutAccount() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/v1/products'].post").exists());
    }
}
