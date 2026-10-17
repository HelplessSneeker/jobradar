package at.bfnoessler.jobradar;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class DeepSeekClient {

	private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

	private static final String SYSTEM_PROMPT = """
			Du extrahierst Daten aus Stellenanzeigen und antwortest ausschliesslich mit einem JSON-Objekt \
			in genau diesem Format:
			{"company": "Muster Software GmbH", "stack": ["Python", "Django"], "salaryMinYearly": 48000, "remote": "onsite", "location": "Graz"}
			Regeln:
			- Was nicht in der Anzeige steht, ist null. Nichts raten, nichts erfinden.
			- salaryMinYearly ist das Jahresbrutto in EUR als ganze Zahl; Monatsangaben mal 14.
			- remote ist genau einer der Werte "remote", "hybrid", "onsite" oder null.
			""";

	private final RestClient restClient;

	private final ObjectMapper objectMapper;

	private final double inputPrice;

	private final double cacheHitPrice;

	private final double outputPrice;

	DeepSeekClient(RestClient.Builder builder, ObjectMapper objectMapper,
			@Value("${deepseek.api-key}") String apiKey,
			@Value("${deepseek.price.input}") double inputPrice,
			@Value("${deepseek.price.cache-hit}") double cacheHitPrice,
			@Value("${deepseek.price.output}") double outputPrice) {
		this.restClient = builder
				.baseUrl("https://api.deepseek.com")
				.defaultHeader("Authorization", "Bearer " + apiKey)
				.build();
		this.objectMapper = objectMapper;
		this.inputPrice = inputPrice;
		this.cacheHitPrice = cacheHitPrice;
		this.outputPrice = outputPrice;
	}

	JobPosting extract(String jobAd) {
		ChatResponse response = this.restClient.post()
				.uri("/chat/completions")
				.body(new ChatRequest(
						"deepseek-flash",
						List.of(new Message("system", SYSTEM_PROMPT), new Message("user", jobAd)),
						new Thinking("disabled"),
						new ResponseFormat("json_object"),
						500))
				.retrieve()
				.body(ChatResponse.class);

		Usage usage = response.usage();
		log.info("DeepSeek usage: prompt_tokens={} prompt_cache_hit_tokens={} completion_tokens={} cost={} USD",
				usage.promptTokens(), usage.promptCacheHitTokens(), usage.completionTokens(), cost(usage));

		return this.objectMapper.readValue(response.choices().getFirst().message().content(), JobPosting.class);
	}

	/** Cache-Treffer sind billiger, der Rest der Prompt-Tokens zahlt den vollen Eingabepreis. */
	private String cost(Usage usage) {
		int uncachedPromptTokens = usage.promptTokens() - usage.promptCacheHitTokens();
		double usd = (uncachedPromptTokens * this.inputPrice
				+ usage.promptCacheHitTokens() * this.cacheHitPrice
				+ usage.completionTokens() * this.outputPrice) / 1_000_000;
		return String.format(Locale.ROOT, "%.6f", usd);
	}

	record ChatRequest(String model, List<Message> messages, Thinking thinking,
			@JsonProperty("response_format") ResponseFormat responseFormat,
			@JsonProperty("max_tokens") int maxTokens) {
	}

	record Message(String role, String content) {
	}

	record Thinking(String type) {
	}

	record ResponseFormat(String type) {
	}

	record ChatResponse(List<Choice> choices, Usage usage) {
	}

	record Choice(Message message) {
	}

	record Usage(@JsonProperty("prompt_tokens") int promptTokens,
			@JsonProperty("prompt_cache_hit_tokens") int promptCacheHitTokens,
			@JsonProperty("completion_tokens") int completionTokens) {
	}

}
