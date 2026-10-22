package com.stockflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.jwt.secret=integration-test-only-key-not-for-production-123456")
class StockFlowApplicationTests {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void contextLoads() {
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = true", Integer.class))
				.isGreaterThanOrEqualTo(1);
	}

}
