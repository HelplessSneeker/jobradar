package at.bfnoessler.jobradar;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = { "spring.docker.compose.skip.in-tests=false", "deepseek.api-key=***" })
class JobradarApplicationTests {

	@Test
	void contextLoads() {
	}

}
