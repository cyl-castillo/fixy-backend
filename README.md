# Fixy Backend

Backend inicial de Fixy en `Spring Boot` para correr la landing y un agente de intake.

## Qué incluye

- Landing estática servida desde `src/main/resources/static`
- `POST /api/intake` para clasificar mensajes de clientes o proveedores
- Flujo público conversacional para clientes en `POST /api/public/leads`
- Registro público de proveedores en `POST /api/public/providers`
- Panel operativo protegido con Basic Auth en `/ops.html`
- Fallback heurístico para operar sin IA externa
- Integración opcional con OpenAI vía `Responses API`
- `GET /api/health` para chequeo simple

## Requisitos

- JDK 21
- Maven 3.9+

## Variables de entorno

```bash
export PORT=8080
export OPENAI_API_KEY=tu_api_key
export OPENAI_MODEL=gpt-4.1-mini
export FIXY_OPS_USERNAME=ops
export FIXY_OPS_PASSWORD=cambia-esto
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

### `POST /api/public/leads`

Crea un caso público desde la landing y devuelve el estado interpretado por el agente.

```json
{
  "name": "Lucia",
  "phone": "093551242",
  "problem": "Se me rompio la ducha y pierde agua en Solymar",
  "channel": "web"
}
```

### `PATCH /api/public/leads/{id}/context`

Agrega contexto faltante, por ejemplo zona o detalles adicionales.

```json
{
  "location": "Pocitos",
  "notes": "Es en apartamento, salta la llave general"
}
```

### `POST /api/public/leads/{id}/matches`

Genera opciones de proveedores si el caso tiene categoría y zona suficientes.

### `POST /api/public/providers`

Registra un proveedor interesado desde la landing.

```json
{
  "name": "Ana",
  "phone": "099888777",
  "message": "Soy electricista, trabajo en Pocitos y hago urgencias",
  "channel": "web-provider"
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

1. Probar el flujo público completo con casos reales.
2. Conectar PostgreSQL o Supabase en producción.
3. Conectar la API oficial de WhatsApp cuando el flujo manual ya esté validado.
