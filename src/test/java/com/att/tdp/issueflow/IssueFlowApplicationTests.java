// Smoke test — verifies the Spring application context loads without errors
package com.att.tdp.issueflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class IssueFlowApplicationTests {

	/** §4.2 — The Spring application context starts without errors, confirming all beans wire together correctly. */
	@Test
	void contextLoads() {
	}

}
