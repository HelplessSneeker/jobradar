# jobradar

Spring Boot 4.1, Java 21, PostgreSQL. Extrahiert strukturierte Daten aus Stellenanzeigen über die
DeepSeek-API.

## Fallen — hier gilt Spring Boot 4, nicht 3

Diese Punkte unterscheiden sich von Boot 3 und sind die üblichen Fehlerquellen:

- **Jackson 3**: Klassen kommen aus `tools.jackson.*` (z. B. `tools.jackson.databind.ObjectMapper`),
  **nur** die Annotationen bleiben in `com.fasterxml.jackson.annotation` (`@JsonProperty` usw.).
  Jackson-3-Exceptions sind unchecked, also kein `try`/`catch` um `readValue` nötig.
- **RestClient**: kommt aus `spring-boot-starter-restclient` (eigenes Starter-Artefakt in Boot 4,
  nicht mehr Teil des Web-Starters). `RestClient.Builder` injizieren, nicht selbst bauen.
- **Web-Starter** heißt `spring-boot-starter-webmvc`, der Test-Starter `spring-boot-starter-webmvc-test`.

## DeepSeek-API

- `POST https://api.deepseek.com/chat/completions`, OpenAI-Format, Header `Authorization: Bearer <key>`
- Modell: `deepseek-flash`
- `"thinking": {"type": "disabled"}` — sonst denkt das Modell und das kostet Tokens und Zeit
- JSON-Modus (`"response_format": {"type": "json_object"}`) **hängt**, wenn der System-Prompt nicht
  das Wort „JSON" und ein Beispielobjekt enthält. Beides muss drinbleiben.
- API-Felder sind snake_case → `@JsonProperty` an den Record-Komponenten
- Nach jedem Aufruf `usage` loggen: `prompt_tokens`, `prompt_cache_hit_tokens`, `completion_tokens`
  und die daraus gerechneten Kosten

## Konventionen

- Key und Preise über Properties, nie hartcodiert. `.env` wird via
  `spring.config.import=optional:file:.env[.properties]` eingelesen und ist nicht eingecheckt.
- Wenig Code: Controller, Client, Records. Keine Service-Schicht „auf Vorrat", kein Spring AI.
- Was nicht in der Anzeige steht, ist `null` — das Modell darf nichts raten.
