# jobradar

jobradar nimmt den Text einer Stellenanzeige und gibt die interessanten Felder strukturiert als JSON
zurück: Firma, Tech-Stack, Mindestgehalt als Jahresbrutto, Remote-Modell und Ort. Die Extraktion
macht die DeepSeek-API, der Dienst selbst besteht nur aus einem Controller, einem HTTP-Client,
einem Record für das Ergebnis und den Records für Anfrage und Antwort der DeepSeek-API.

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
{
  "company": "p.how GmbH",
  "stack": [
    "Java",
    "Spring Boot",
    "Hibernate",
    "PostgreSQL",
    "Angular",
    "TypeScript",
    "Flutter",
    "Kotlin Multiplatform",
    "Python",
    "Docker",
    "Kubernetes",
    "ELK-Stack",
    "LLM-APIs"
  ],
  "salaryMinYearly": 55356,
  "remote": "hybrid",
  "location": "Wien"
}
```

Dazu im Log:

```
DeepSeek usage: prompt_tokens=1937 prompt_cache_hit_tokens=0 completion_tokens=95 cost=0.000348 USD
```

Beim zweiten Aufruf derselben Anzeige kommt der gleiche Anfang aus dem Cache; verschiedene Anzeigen
teilen sich nur den System-Prompt:

```
DeepSeek usage: prompt_tokens=1937 prompt_cache_hit_tokens=1792 completion_tokens=95 cost=0.000084 USD
```

Felder, die in der Anzeige nicht vorkommen, sind `null` — das Modell soll nichts raten.
`salaryMinYearly` ist Jahresbrutto in EUR (Monatsangaben × 14), `remote` ist `"remote"`, `"hybrid"`,
`"onsite"` oder `null`. Die Preise pro 1 Mio. Tokens stehen als Properties in
`application.properties` (`deepseek.price.*`), sind in USD und gelten für `deepseek-flash`
außerhalb der Spitzenzeit.

## Arbeitsweise

Ich plane die Arbeit mit einem eigenen Agenten, der auf meinem Server läuft (OpenClaw mit Claude). Mit ihm habe ich geklärt, welche Felder sich lohnen, wie die DeepSeek-API funktioniert und was ein Aufruf kostet. Die Umsetzung gebe ich dann an eine Claude-Code-Instanz. Die Anforderungen, die an Claude Code gehen, prüfe ich vorher. Was Claude Code in jeder Session über das Projekt wissen muss, steht in der `CLAUDE.md` im Repo.

Das Ergebnis prüfe ich selbst und lasse es zusätzlich von meinem Agenten auf dem Server prüfen. Wenn sich die API ändert, rufe ich sie mit einer echten Anzeige auf und rechne nach. Die p.how-Anzeige nennt zum Beispiel ab 3.954 € brutto im Monat, 14-mal im Jahr, und 3.954 × 14 = 55.356 ist genau der Wert, der zurückkommt.

Die zweite Prüfung hat hier schon etwas gefunden. Claude Code hat die Beispielanzeige im Repo nicht von karriere.at übernommen, sondern selbst geschrieben: p.how im 7. statt im 21. Bezirk, die Bewerbungsadresse auf `.example`. Im README stand dazu, ich hätte das Ergebnis an dieser Anzeige von Hand geprüft. Geprüft hatte ich aber gegen die echte Anzeige. Gefunden hat das der Review auf dem Server, beim Abgleich mit karriere.at. Seitdem steht in der `CLAUDE.md`, dass Beispieldaten entweder echt und mit Quelle belegt oder erkennbar erfunden sind.

Wo der Ablauf an Grenzen stößt: Claude Code kennt vor allem Spring Boot 3. Bei Boot 4 sind Jackson 3 (`tools.jackson.*`) und der eigene `spring-boot-starter-restclient` die üblichen Stolperstellen, deshalb stehen sie als Fallen in der `CLAUDE.md`. Auch meine Prüfung der Anforderungen ist nicht lückenlos: Im ersten System-Prompt stand als Beispiel ausgerechnet p.how mit Wien und hybrid, also drei der fünf Felder des Testfalls. Dass alle fünf Felder stimmten, hieß damit wenig. Und von Hand prüfe ich einzelne Anzeigen, nicht alle. Ob eine Prompt-Änderung wirklich etwas verbessert, soll deshalb als Nächstes ein Messlauf zeigen: 20 von Hand beschriftete Anzeigen, zwei Prompt-Fassungen gegeneinander, Trefferquote und Kosten als Tabelle.

## Nächste Schritte

- Fehlerbehandlung: die Antwort gegen das Schema prüfen, bei Unsinn einmal neu fragen, sonst `null`;
  401, 402 und Timeout als klare Statuscodes
- Ergebnis samt Tokens und Kosten in PostgreSQL speichern, Namen von Ansprechpartnern vor dem
  Request entfernen
- Messlauf: 20 von Hand beschriftete Anzeigen, zwei Prompt-Fassungen gegeneinander, Trefferquote und
  Kosten als Tabelle
- Tool Calling: Das Modell fragt die gespeicherten Anzeigen selbst ab
- Angular-Oberfläche mit Streaming

## Aufbau

| Datei                    | Zweck                                                                    |
| ------------------------ | ------------------------------------------------------------------------ |
| `ExtractController.java` | `POST /api/extract`, nimmt `text/plain`, gibt JSON zurück                |
| `DeepSeekClient.java`    | System-Prompt, HTTP-Aufruf, Usage- und Kosten-Logging, Protokoll-Records |
| `JobPosting.java`        | Ergebnis-Record = Antwortformat des Endpoints                            |
