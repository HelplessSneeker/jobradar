package at.bfnoessler.jobradar;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExtractController.class)
class ExtractControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private DeepSeekClient deepSeekClient;

	@Test
	void deepSeekExceptionWirdZuProblemDetail() throws Exception {
		given(this.deepSeekClient.extract(anyString()))
				.willThrow(new DeepSeekException(HttpStatus.SERVICE_UNAVAILABLE, "DeepSeek ist überlastet"));

		this.mockMvc.perform(post("/api/extract").contentType(MediaType.TEXT_PLAIN).content("Java-Entwickler, Wien"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(503))
				.andExpect(jsonPath("$.detail").value("DeepSeek ist überlastet"));
	}

	@Test
	void nurLeerzeichenGibt400OhneDeepSeekAufzurufen() throws Exception {
		this.mockMvc.perform(post("/api/extract").contentType(MediaType.TEXT_PLAIN).content("   \n  "))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.detail").value("Der Anzeigentext ist leer"));

		verify(this.deepSeekClient, never()).extract(anyString());
	}

}
