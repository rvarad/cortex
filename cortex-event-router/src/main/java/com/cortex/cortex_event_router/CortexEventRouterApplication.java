package com.cortex.cortex_event_router;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(excludeName = {
		"org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
		"org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
		"org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
		"org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
		"org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
		"org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration"
})
public class CortexEventRouterApplication {

	public static void main(String[] args) {
		SpringApplication.run(CortexEventRouterApplication.class, args);
	}

}
