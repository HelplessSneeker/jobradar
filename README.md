# jobradar

jobradar nimmt den Text einer Stellenanzeige und gibt die interessanten Felder strukturiert als JSON
zurück: Firma, Tech-Stack, Mindestgehalt als Jahresbrutto, Remote-Modell und Ort. Die Extraktion
macht die DeepSeek-API, der Dienst selbst besteht nur aus einem Controller, einem HTTP-Client und
zwei Records.

## Start

Docker muss laufen — Spring Boot startet die PostgreSQL aus `compose.yaml` beim App-Start selbst.

`.env` im Projektverzeichnis anlegen (ist in `.gitignore`):

```properties
DEEPSEEK_API_KEY=sk-...
```

Dann:

```bash
./mvnw spring-boot:run
```

Läuft auf Port 8080.

## Aufruf

Anzeigentext in eine Datei legen (`anzeige.txt` liegt als Beispiel dabei) und schicken:

```bash
curl -X POST localhost:8080/api/extract \
  -H 'Content-Type: text/plain' \
  --data-binary @anzeige.txt
```

Antwort für die p.how-Anzeige:

```json
{"company":"p.how GmbH","stack":["Java","Spring Boot","Angular"],"salaryMinYearly":55356,"remote":"hybrid","location":"Wien"}
```

Dazu im Log:

```
DeepSeek usage: prompt_tokens=414 prompt_cache_hit_tokens=0 completion_tokens=47 cost=0.000090 EUR
```

Beim zweiten Aufruf greift DeepSeeks Prompt-Cache für den System-Prompt, die Kosten fallen um gut
40 Prozent:

```
DeepSeek usage: prompt_tokens=414 prompt_cache_hit_tokens=256 completion_tokens=47 cost=0.000053 EUR
```

Felder, die in der Anzeige nicht vorkommen, sind `null` — das Modell soll nichts raten.
`salaryMinYearly` ist Jahresbrutto in EUR (Monatsangaben × 14), `remote` ist `"remote"`, `"hybrid"`,
`"onsite"` oder `null`. Die Preise pro 1 Mio. Tokens stehen als Properties in
`application.properties` (`deepseek.price.*`) und gelten für `deepseek-flash` außerhalb der
Spitzenzeit.

## Arbeitsweise

Plan und Recherche habe ich mit meinem KI-Assistenten gemacht: Welche Felder lohnen sich, wie sieht
die DeepSeek-API aus, was kostet ein Aufruf. Die Umsetzung lief dann über einen Prompt an Claude
Code — Controller, Client, Records, Kostenrechnung.

Das Ergebnis habe ich von Hand nachgeprüft, nicht nur überflogen: Die Anzeige nennt ein
Bruttomonatsgehalt ab EUR 3.954, und 3.954 × 14 = 55.356 — genau der Wert, den der Endpoint
zurückgibt. Auch die übrigen Felder stimmen mit dem Anzeigentext überein.

Wo es hakt: Claude Code kennt vor allem Spring Boot 3. Bei Boot 4 sind deshalb Jackson 3
(`tools.jackson.*`) und der eigene `spring-boot-starter-restclient` die üblichen Stolperstellen —
dazu der DeepSeek-JSON-Modus, der ohne das Wort „JSON" und ein Beispiel im System-Prompt hängt. Diese
Fallen stehen in der `CLAUDE.md`, damit sie in jeder Session gelten und nicht jedes Mal neu
erklärt werden müssen.

## Nächste Schritte

- Fehlerbehandlung: API-Fehler und unbrauchbare Modellantworten als sinnvolle HTTP-Statuscodes
  statt als 500
- Tool Calling gegen PostgreSQL, damit das Modell extrahierte Anzeigen ablegen und abfragen kann
- Messlauf mit 20 von Hand beschrifteten Anzeigen, um die Extraktionsqualität zu beziffern
- Angular-Oberfläche mit Streaming, damit die Felder beim Extrahieren einzeln erscheinen

## Aufbau

| Datei | Zweck |
| --- | --- |
| `ExtractController.java` | `POST /api/extract`, nimmt `text/plain`, gibt JSON zurück |
| `DeepSeekClient.java` | System-Prompt, HTTP-Aufruf, Usage- und Kosten-Logging, Protokoll-Records |
| `JobPosting.java` | Ergebnis-Record = Antwortformat des Endpoints |
