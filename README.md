# 🚀 KI-Gestützte B2B Lead Scoring Engine

Ein automatisiertes, serverloses System zur sofortigen Anreicherung (Enrichment), Qualifizierung und Weiterleitung von eingehenden B2B-Leads mittels Large Language Models (LLMs). 

Dieses Projekt wurde entwickelt, um manuelle CRM-Dateneingaben zu eliminieren, irrelevante Anfragen herauszufiltern und dem Vertriebsteam hochqualifizierte Leads mit maximaler Kaufabsicht (Intent) in Echtzeit zu priorisieren.

## 💡 Geschäftswert (Business Value)
* **Zeitersparnis:** Automatisierte Recherche von Unternehmensdaten (Branche, Mitarbeiter, Umsatz) spart dem Sales-Team Stunden an manueller Arbeit.
* **Priorisierung:** Durch den KI-generierten *Lead Score (1-100)* kann sich der Vertrieb sofort auf die heißesten Leads konzentrieren.
* **Reaktionszeit:** Leads werden in Millisekunden verarbeitet und an den Slack-Channel des Teams gepusht.

## 🧠 Systemarchitektur & Workflow
1. **Webhook-Eingang (Secured):** Ein n8n-Webhook fängt eingehende Lead-Daten (Name, E-Mail, Unternehmen, Nachricht) ab. Der Endpunkt ist durch eine Header-Authentifizierung (`X-Portfolio-Token`) abgesichert.
2. **KI-Anreicherung (Google Gemini 2.5 Flash):** Das LLM analysiert die Nachricht und die E-Mail-Domain, um komplexe Metadaten zu extrahieren:
   * Branche (Industry)
   * Geschätzte Mitarbeiterzahl
   * Geschätzter Jahresumsatz (USD)
   * **Lead Score**, basierend auf Dringlichkeit und Budget-Signalen im Text.
3. **Fehlertolerantes Parsing (JavaScript):** Ein benutzerdefinierter Code-Node fängt die LLM-Antwort ab, bereinigt sie und wandelt sie in ein streng strukturiertes JSON-Format um. Fallback-Logiken verhindern Systemabstürze bei unerwarteten KI-Outputs.
4. **Datenbankspeicherung (Supabase):** Das angereicherte Lead-Profil wird über die REST-API (via `service_role` Key für maximale Sicherheit) als neuer Datensatz in einer PostgreSQL-Datenbank gespeichert.
5. **Echtzeit-Alerts (Slack API):** Ein eigens konfigurierter Slack-Bot (erstellt via App-Manifest, authentifiziert über OAuth Bot Token mit `chat:write` Scopes) postet die Lead-Zusammenfassung in einem formatierten, übersichtlichen Design (White-Label, ohne Automatisierungs-Branding) in den Vertriebskanal.

## 🛠 Tech Stack
* **Workflow Automation:** n8n (Node.js)
* **KI / LLM:** Google AI Studio (Gemini 2.5 Flash API)
* **Datenbank:** Supabase (PostgreSQL, Data API)
* **Kommunikation:** Slack API (Custom Bot App)
* **Infrastruktur & Hosting:** Docker, Railway (Cloud-Deployment)

## ⚡ Live Demo (API testen)
Das System läuft aktuell in Produktion und ist über Header-Auth abgesichert. Kopieren Sie den passenden Befehl für Ihr Betriebssystem in Ihr Terminal, um einen Test-Lead an die Pipeline zu senden:

**🍏 Für macOS / Linux (Terminal / Bash):**
```bash
curl -X POST "https://laughing-octo-potato-production.up.railway.app/webhook/07e45f80-357d-4af0-9efe-e742e94426f9" \
     -H "Content-Type: application/json" \
     -H "X-Portfolio-Token: KiprianDemo2026" \
     -d '{
           "name": "Marcus Schmidt",
           "email": "m.schmidt@enterprise-solutions.de",
           "company": "Enterprise Solutions AG",
           "message": "Wir benötigen im Q4 dringend eine Automatisierungslösung. Das Budget ist freigegeben."
         }'
```

**🪟 Für Windows (PowerShell):**
```powershell
Invoke-RestMethod -Uri "https://laughing-octo-potato-production.up.railway.app/webhook/07e45f80-357d-4af0-9efe-e742e94426f9" `
  -Method Post `
  -Headers @{ "Content-Type"="application/json"; "X-Portfolio-Token"="KiprianDemo2026" } `
  -Body '{"name": "Marcus Schmidt", "email": "m.schmidt@enterprise-solutions.de", "company": "Enterprise Solutions AG", "message": "Wir benötigen im Q4 dringend eine Automatisierungslösung. Das Budget ist freigegeben."}'
```

## 📸 Output-Beispiele (Slack Integration)
Hier sehen Sie, wie das System die Intents und Unternehmensdaten unterschiedlich bewertet (von "nur mal schauen" bis hin zu "Kaufbereit"):
![Slack Demo 0](demo-slack%200.png)
![Slack Demo 1](demo-slack%201.png)
