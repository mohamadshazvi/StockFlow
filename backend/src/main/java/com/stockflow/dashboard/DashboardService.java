package com.stockflow.dashboard;

import java.util.Currency;
import java.util.Locale;
import com.stockflow.common.error.ApiProblem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.stockflow.dashboard.DashboardDtos.Summary;

@Service
@Transactional(readOnly = true)
public class DashboardService {
    private final JdbcTemplate jdbc;
    private final String currency;
    public DashboardService(JdbcTemplate jdbc, @Value("${app.currency:LKR}") String currency) {
        this.jdbc = jdbc; this.currency = currency.trim().toUpperCase(Locale.ROOT);
        if (Currency.getInstance(this.currency).getDefaultFractionDigits() != 2) throw new IllegalArgumentException("APP_CURRENCY must have two fractional digits.");
    }
    public Summary summary() {
        // One statement: all four aggregates observe the same committed snapshot.
        return jdbc.queryForObject("""
                WITH product_counts AS (
                    SELECT count(*) FILTER (WHERE active) AS active_count,
                           count(*) FILTER (WHERE active AND stock_quantity <= low_stock_threshold) AS low_count
                    FROM products
                ), order_counts AS (
                    SELECT count(*) FILTER (WHERE status='DRAFT') AS draft_count,
                           coalesce(sum(total) FILTER (WHERE status='CONFIRMED'),0) AS confirmed_value,
                           count(*) FILTER (WHERE status='CONFIRMED' AND currency<>?) AS currency_mismatch
                    FROM orders
                ) SELECT * FROM product_counts CROSS JOIN order_counts
                """, (rs, row) -> {
                    if (rs.getLong("currency_mismatch") > 0) throw new ApiProblem(HttpStatus.CONFLICT, "CURRENCY_MISMATCH", "Confirmed orders contain a different currency. Restore the correct business currency configuration before viewing totals.");
                    return new Summary(rs.getLong("active_count"), rs.getLong("low_count"), rs.getLong("draft_count"), rs.getBigDecimal("confirmed_value").setScale(2).toPlainString(), currency);
                }, currency);
    }
}
