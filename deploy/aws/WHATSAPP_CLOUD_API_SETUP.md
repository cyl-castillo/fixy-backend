# WhatsApp Cloud API — Setup para Fixy

Paso a paso para activar el número WhatsApp dedicado de Fixy en la API
oficial de Meta. Estimado: 30-60 min de tu lado + 1-24 hs de aprobación
del template para iniciar conversaciones.

---

## 0. Antes de empezar — backup

El número Fixy hoy está en **WhatsApp Business App** (la app móvil). Al
registrarlo en Cloud API, **WhatsApp Mobile App deja de funcionar con ese
número**. Pierdes el historial de chats de la app.

**Hacé esto antes**:
- Exportá chats importantes desde la app (Configuración → Cuenta →
  Historial → Exportar chat).
- Avisá a los contactos actuales que va a haber un cambio.

## 1. Crear cuenta Meta for Developers

1. Ir a https://developers.facebook.com/
2. Click "Get Started" (arriba a la derecha) → log in con tu cuenta
   personal de Facebook.
3. Aceptás términos de developer. Listo, ya sos developer.

## 2. Crear Meta Business Account (si no tenés)

1. Ir a https://business.facebook.com/
2. Click "Crear cuenta" → poné "Fixy" como nombre del negocio + tu nombre
   personal + tu email.
3. Confirmá email.
4. **NO** hace falta verificación de business todavía. Eso lo hacemos
   después cuando escalemos.

## 3. Crear App de WhatsApp en Meta for Developers

1. https://developers.facebook.com/apps/ → "Create App"
2. Use case: **"Other"**
3. App type: **"Business"**
4. Display name: `Fixy Backend`
5. Contact email: tu email
6. Business account: elegí "Fixy" (la que creaste en paso 2)
7. Click "Create App"
8. Te pide password de Facebook → confirmá.

## 4. Agregar producto WhatsApp a la App

1. En el dashboard de la app → en "Add products to your app" buscá
   **"WhatsApp"** → click "Set up"
2. Si no tenés WhatsApp Business Account todavía, te pide crearla → "Continue"
3. Se abre la página "WhatsApp Business Platform"
4. Vas a ver una sección "Send and receive messages" con:
   - Un **número de prueba** que te da Meta (gratis, para tests, marcado
     con "Test phone number")
   - Un **From** que vas a usar como `PHONE_NUMBER_ID` del número de prueba

**No usás el número de prueba para Fixy. Vamos a registrar el tuyo.**

## 5. Registrar tu número dedicado de Fixy

1. En el panel izquierdo: "API Setup" → arriba dice "From" con el número
   de prueba → click el dropdown → **"Add phone number"**
2. Te pide:
   - Display name: `Fixy` (este nombre lo ve el destinatario)
   - Category: `Local services`
   - Description: `Marketplace de servicios del hogar en Uruguay`
3. Click "Continue" → te pide el número.
4. Poné el número dedicado de Fixy con prefijo país: `+598 XX XXX XXX`.
5. Método de verificación: **SMS** o **Llamada**. Recomiendo SMS.
6. Recibís un código de 6 dígitos → lo ponés → verificado.

⚠️ **Al verificar, WhatsApp Business App del celular pierde acceso a ese
número**. El número queda "API only".

## 6. Obtener credenciales del número

En "API Setup" vas a ver:
- **Phone number ID** — un número largo (ej `123456789012345`).
- **WhatsApp Business Account ID** — otro número largo.
- **Temporary access token** — válido 24 hs.

**Anotalos pero no los pegues acá**.

## 7. Crear System User Token permanente

El token temporal vence en 24h. Para que el backend no se rompa cada
día, generamos un token que nunca vence.

1. Ir a https://business.facebook.com/settings/system-users
2. Click "Add" → System User name `fixy-backend` → role **Admin** → Create.
3. En el system user creado: "Add Assets" → seleccionar **WhatsApp Account**
   → la cuenta de Fixy → permisos: ✅ Manage WhatsApp Business Account.
4. "Generate New Token" → seleccionar la app `Fixy Backend` → expira:
   **Never** → permisos: `whatsapp_business_messaging` y
   `whatsapp_business_management` → Generate Token.
5. Copiá el token (formato `EAAxxxxxxxx...`). **Te lo muestra una vez,
   guardalo bien**.

## 8. Configurar webhook (acá necesito tu input)

Vas a apuntar el webhook a `https://api.fixy.com.uy/api/webhooks/whatsapp`.

1. En el panel WhatsApp de la app → "Configuration" → "Webhook" → "Edit"
2. Callback URL: `https://api.fixy.com.uy/api/webhooks/whatsapp`
3. Verify token: elegí una string secreta (ej `fixy-wh-2026-xxxxx`).
   Anotala — la pongo yo en el backend.
4. Click "Verify and save". Si está OK, Meta hace un GET al webhook y
   responde 200. Si falla, es que el backend no está deployado todavía
   (espera mi confirmación).
5. Suscribir eventos: ✅ `messages`. Es lo único que necesitamos.

## 9. Crear template `provider_lead_notification`

Sin template aprobado, no podemos **iniciar** conversación con un
proveedor que nunca nos escribió. Por eso creamos un template ya.

1. En el panel WhatsApp → "Message Templates" → "Create Template"
2. Category: **Utility** (gratis las primeras 1000/mes y aprobación
   más rápida que Marketing).
3. Name: `provider_lead_notification`
4. Language: **Spanish** (es) — usa esta, no "Spanish (UY)" si no aparece.
5. Header: ninguno.
6. Body:
   ```
   Hola, soy Fixy. Tengo un pedido de {{1}} en {{2}}, urgencia {{3}}.
   ¿Lo podés tomar? Respondé SÍ o NO PUEDO, y si querés más detalle te
   los paso por acá.
   ```
7. Sample values:
   - `{{1}}`: `plomería`
   - `{{2}}`: `Solymar`
   - `{{3}}`: `alta`
8. Footer (opcional): `Fixy — servicios del hogar`
9. Buttons (opcional, recomiendo): Quick Reply
   - Button 1: `SÍ`
   - Button 2: `NO PUEDO`
10. Submit → status va a quedar en `Pending` (1-24 hs).

## 10. Lo que me pasás a mí

Cuando tengas:

- **PHONE_NUMBER_ID** (paso 6)
- **WHATSAPP_BUSINESS_ACCOUNT_ID** (paso 6)
- **System User token permanente** (paso 7)
- **Webhook verify token** (paso 8, lo elegiste vos)

Me los pasás. **Idealmente con `!` en el chat** para no dejarlos visibles
en el transcripto:

```
! cat > /tmp/wa-creds.env << 'EOF'
WHATSAPP_PHONE_NUMBER_ID=...
WHATSAPP_BUSINESS_ACCOUNT_ID=...
WHATSAPP_ACCESS_TOKEN=EAAxxxxxxxx...
WHATSAPP_WEBHOOK_VERIFY_TOKEN=fixy-wh-2026-xxxxx
EOF
echo "creds escritas"
```

Yo los meto en `/etc/fixy-backend.env` en prod y reinicio. Listo.

## 11. Probar end-to-end

Una vez todo seteado:

1. Un proveedor te escribe "hola" al WhatsApp de Fixy → backend recibe
   webhook, guarda lo necesario, abre ventana de 24h.
2. Un cliente hace un pedido en fixy.com.uy → matching encuentra al
   proveedor → backend envía template `provider_lead_notification` →
   le llega al proveedor.
3. Proveedor responde "SÍ" → backend marca lead como ASSIGNED + postea
   confirmación al cliente en el chat web.
4. Si el cliente y proveedor siguen chateando: las respuestas van
   relay backend ↔ WhatsApp ↔ chat web.

## Costos esperados

- **Conversaciones business-initiated (Utility)**: gratis las primeras
  1000/mes, después ~$0.005-0.015 USD por conversación en UY (al cutoff
  Jan 2026).
- **Conversaciones service-initiated (cliente/proveedor escribe primero)**:
  gratis ilimitadas.

Con bajos volúmenes (10-50 leads/día) entrás libre. Cuando crezcas, hay
que monitorear.
