# Fixy Backend

Backend inicial de Fixy en `Spring Boot` para correr la landing y un agente de intake.

## Qué incluye

- Landing estática servida desde `src/main/resources/static`
- `POST /api/intake` para clasificar mensajes de clientes o proveedores
- Fallback heurístico para operar sin IA externa
- Integración opcional con OpenAI vía `Responses API`
- `GET /api/health` para chequeo simple

## Requisitos

- JDK 21
- Maven 3.9+

Este servidor hoy no tiene `java` ni `mvn` instalados, así que el repo quedó preparado pero no ejecutado aquí todavía.

## Variables de entorno

```bash
export PORT=8080
export OPENAI_API_KEY=tu_api_key
export OPENAI_MODEL=gpt-4.1-mini
```

Si `OPENAI_API_KEY` no está definida, el backend usa clasificación heurística.

## Ejecutar local

```bash
mvn spring-boot:run
```

o

```bash
mvn clean package
java -jar target/fixy-backend-0.0.1-SNAPSHOT.jar
```

## Endpoints

### `GET /api/health`

Respuesta:

```json
{
  "status": "ok",
  "service": "fixy-backend"
}
```

### `POST /api/intake`

Request:

```json
{
  "message": "Se me rompio la ducha y pierde agua en Solymar",
  "contactName": "Lucia",
  "phone": "093551242",
  "channel": "whatsapp"
}
```

Response:

```json
{
  "leadType": "cliente",
  "serviceCategory": "plomeria",
  "area": "Solymar",
  "urgency": "alta",
  "summary": "Problema de plomeria reportado por Lucia",
  "missingFields": [
    "direccion exacta",
    "foto del problema"
  ],
  "suggestedReply": "Gracias, ya estamos revisando tu caso. Compartenos direccion exacta y, si puedes, una foto para coordinar mas rapido.",
  "agentSource": "heuristic"
}
```

## Próximos pasos recomendados

1. Instalar JDK y Maven en el servidor.
2. Probar `POST /api/intake` con casos reales.
3. Agregar persistencia en PostgreSQL o Supabase.
4. Conectar la API oficial de WhatsApp cuando el flujo manual ya esté validado.
