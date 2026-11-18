# Especificación del protocolo de login de Second Life — Hito M1 de Ephora Viewer

- **Proyecto:** Ephora Viewer (`com.ephora.viewer`, repo `yossfu/EphoraViewer`)
- **Alcance:** solo el login real contra el grid de Second Life. Nada simulado: este
  documento describe el intercambio exacto que la app debe hacer con el servidor
  de login de Linden Lab. El circuito UDP posterior es el hito M2 y aquí solo se
  resume.
- **Fecha de la investigación:** 2026-11-18.
- **Estado:** especificación para implementar. No es código; no se ha probado
  contra el servidor real (no hay credenciales de prueba en este documento ni
  se ha hecho ningún login real todavía). Todo lo marcado "sin verificar" debe
  confirmarse con la primera prueba real de login.
- **Idioma:** los nombres de campos, URLs y valores de protocolo están en inglés
  tal cual los define el protocolo; el resto del documento está en español.

## Fuentes primarias (en orden de autoridad)

1. **Visor oficial de Second Life** (`secondlife/viewer`, GitHub), rama principal
   consultada 2026-11-18:
   - `indra/newview/lllogininstance.cpp` — `LLLoginInstance::constructAuthParams()`
     (parámetros de la petición y lista `options`), `handleLoginFailure()` (códigos
     `reason`), `construct_start_string()`, constantes de timeout/reintentos.
   - `indra/newview/llsecapi.cpp` — `LLCredential::getLoginParams()` (campos
     `first`/`last`/`passwd`).
   - `indra/newview/llpanellogin.cpp` — el hash MD5 de la contraseña
     (`LLMD5` + `hex_digest`).
   - `indra/viewer_components/login/lllogin.h` — documentación del módulo de login
     (máquina de estados y lista de parámetros XML-RPC).
   - `indra/newview/llappviewer.cpp` — `LL_PLATFORM_KEY` (`"win"`/`"mac"`/`"lnx"`).
2. **libopenmetaverse** (`openmetaversefoundation/libopenmetaverse`, GitHub):
   - `OpenMetaverse/Login.cs` — `LoginParams` (valores por defecto),
     `BeginLogin()` (construcción del mapa LLSD y del Hashtable XML-RPC),
     `LoginResponseData.Parse()` (todos los campos de la respuesta),
     `NetworkManager.GetMAC()/GetPlatform()`.
   - `OpenMetaverse/Settings.cs` — `AGNI_LOGIN_SERVER`, `ADITI_LOGIN_SERVER`,
     `LOGIN_TIMEOUT`, `CAPS_TIMEOUT`, `USE_LLSD_LOGIN`.
   - `OpenMetaverseTypes/Utils.cs` — `Utils.MD5()` (hash con prefijo `$1$`).
   - `OpenMetaverse/Capabilities/CapsClient.cs` — Content-Type del POST LLSD.
3. **Wiki de Second Life** (Linden Lab):
   - `Lslsimcaps` (wiki.secondlife.com/wiki/Lslsimcaps) — ejemplo completo
     (2007) de petición XML-RPC `login_to_simulator` y del uso posterior de
     `seed_capability`. El ejemplo es antiguo pero el núcleo del protocolo
     no ha cambiado.
   - `Current login protocols` — **no se pudo leer**: desde 2026 la wiki exige
     iniciar sesión con cuenta de Second Life para ver esa página (comprobado
     2026-11-18: "You need to be signed in to view this page"). Sus datos se
     sustituyeron por el código del visor oficial y libopenmetaverse.
   - `Current Sim Capabilities` — formato de la petición de capabilities al
     seed capability (array LLSD de nombres, respuesta mapa LLSD).
4. **Material local de referencia** (solo consulta, nunca base de código):
   `~/workspace/linkpoint/Linkpoint-0.5/Linkpoint/src/main/kotlin/com/linkpoint/slproto/auth/`
   - `SLAuth.kt`, `SLAuthParams.kt`, `SLAuthReply.kt`, `SLAuthSystem.kt`.
   - Es código descompilado de Lumiya/Linkpoint con errores conocidos; se usa
     solo para contrastar, y cada divergencia con el visor oficial o libomv
     está anotada en la sección 9.
5. **Soporte de Linden Lab** (mensajes de error visibles por el usuario):
   - `community.secondlife.com/knowledgebase/english/login-failure-r47/`
     ("Login failure").

---

## 1. Endpoints de login

| Rejilla | URL completa | Verificado en |
|---|---|---|
| Principal (**Agni**) | `https://login.agni.lindenlab.com/cgi-bin/login.cgi` | libomv `Settings.cs:45` (`AGNI_LOGIN_SERVER`); `SLAuthParams.kt:46`; wiki `Lslsimcaps` |
| Beta (**Aditi**) | `https://login.aditi.lindenlab.com/cgi-bin/login.cgi` | libomv `Settings.cs:48` (`ADITI_LOGIN_SERVER`) |

- **Método HTTP:** `POST`. Solo HTTPS; nunca HTTP en claro.
- **El servidor de login por defecto en libomv es Agni** (`LOGIN_SERVER =
  AGNI_LOGIN_SERVER`, `Settings.cs:54`). La app debe ofrecer el selector
  Agni/Aditi que ya existe en el formulario de M0 y usar la URL correspondiente.
- No hay endpoint distinto por versión de visor: la misma URL atiende a todos
  los visores.

## 2. Transporte: XML-RPC o LLSD-XML

El mismo endpoint acepta **dos serializaciones** de la misma petición, con el
mismo método `login_to_simulator`:

| Variante | Cuerpo | `Content-Type` | Quién la usa |
|---|---|---|---|
| **XML-RPC (clásica)** | `<methodCall><methodName>login_to_simulator</methodName><params><param><value><struct>…` | `text/xml` (Lumiya usa `text/xml; charset=utf-8`) | Visor oficial (vía `LLXMLRPCListener`), libomv por defecto, Lumiya |
| **LLSD-XML** | `<llsd><map><key>first</key><string>…` | `application/llsd+xml` | libomv con `USE_LLSD_LOGIN = true` |

Detalles verificados:

- **libomv usa XML-RPC por defecto:** `USE_LLSD_LOGIN = false` en
  `Settings.cs:60`. La variante LLSD es opt-in. La respuesta a ambas
  variantes tiene la misma estructura de campos.
- **El visor oficial resuelve primero la URL por DNS SRV** antes de publicar
  (`lllogin.h`: "LLLogin makes a request for a SRV record from the uri
  provided by the connect method"). libomv y Lumiya **no** hacen ese paso:
  publican directamente a la URL. La publicación directa funciona; el paso
  SRV es un refinamiento del visor oficial, no un requisito. Para M1,
  **publicar directamente** a la URL de la sección 1.
- **Diferencia de tipos entre variantes:** en XML-RPC el campo `login` de la
  respuesta llega como **string** (`"true"` / `"false"` / `"indeterminate"`);
  en LLSD-XML llega como **booleano**. El parser debe aceptar ambas formas.
  Igual ocurre con `agree_to_tos` / `read_critical` (string `"true"` en
  XML-RPC, booleano en LLSD) y con los enteros (`<int>` / `<i4>` en XML-RPC).
- **Decisión para M1:** usar **XML-RPC con `Content-Type: text/xml`**, que es
  el camino por defecto de libomv, el que usa Lumiya y el documentado en el
  ejemplo de la wiki. Es el camino con más referencias cruzadas. La variante
  LLSD queda como alternativa si XML-RPC fallara en pruebas.
- Cabeceras que envía Lumiya (`SLAuth.kt`): `Content-Type: text/xml` y
  `Connection: close`. La cabecera `Connection: close` es opcional; no es
  parte del protocolo.

## 3. La petición

### 3.1 Ejemplo completo (XML-RPC, con los valores de M1)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<methodCall>
  <methodName>login_to_simulator</methodName>
  <params>
    <param>
      <value>
        <struct>
          <member><name>first</name><value><string>Nombre</string></value></member>
          <member><name>last</name><value><string>Apellido</string></value></member>
          <member><name>passwd</name><value><string>$1$0123456789abcdef0123456789abcdef</string></value></member>
          <member><name>start</name><value><string>last</string></value></member>
          <member><name>channel</name><value><string>Ephora Viewer</string></value></member>
          <member><name>version</name><value><string>0.1.0</string></value></member>
          <member><name>platform</name><value><string>Android</string></value></member>
          <member><name>platform_version</name><value><string>14</string></value></member>
          <member><name>mac</name><value><string>AA:BB:CC:DD:EE:FF</string></value></member>
          <member><name>id0</name><value><string>00000000-0000-0000-0000-000000000000</string></value></member>
          <member><name>agree_to_tos</name><value><string>true</string></value></member>
          <member><name>read_critical</name><value><string>true</string></value></member>
          <member><name>viewer_digest</name><value><string></string></value></member>
          <member><name>last_exec_event</name><value><int>0</int></value></member>
          <member><name>options</name><value><array><data>
            <value><string>inventory-root</string></value>
            <value><string>inventory-skeleton</string></value>
            <value><string>inventory-lib-root</string></value>
            <value><string>inventory-lib-owner</string></value>
            <value><string>inventory-skel-lib</string></value>
            <value><string>buddy-list</string></value>
            <value><string>display_names</string></value>
            <value><string>adult_compliant</string></value>
            <value><string>login-flags</string></value>
            <value><string>global-textures</string></value>
            <value><string>max-agent-groups</string></value>
            <value><string>gestures</string></value>
            <value><string>event_categories</string></value>
            <value><string>event_notifications</string></value>
            <value><string>classified_categories</string></value>
            <value><string>initial-outfit</string></value>
            <value><string>ui-config</string></value>
          </data></array></value></member>
        </struct>
      </value>
    </param>
  </params>
</methodCall>
```

Notas sobre el ejemplo: `mac` e `id0` muestran valores de ejemplo, no reales
(ver 3.2). Los valores de `first`/`last`/`passwd` son los que escribe el
usuario. **Escapar siempre en XML** (`&` → `&amp;`, `<` → `&lt;`,
`>` → `&gt;`, `"` → `&quot;`, `'` → `&apos;`): un nombre o contraseña con
esos caracteres rompería la petición si no se escapa (así lo hace
`SLAuth.kt:escapeXml`).

### 3.2 Tabla de campos de la petición

Obligatorio = el servidor lo espera en un login normal. "Visor" = lo envía el
visor oficial; "libomv" = lo envía libopenmetaverse; "Lumiya" = lo envía el
código de referencia local.

| Campo | Tipo | Obligatorio | Origen | Significado y valor para Ephora M1 |
|---|---|---|---|---|
| `first` | string | **Sí** | todos | Nombre del avatar, primera parte. Tal cual lo escribe el usuario (sin normalizar a minúsculas). |
| `last` | string | **Sí** | todos | Apellido del avatar. Para cuentas nuevas sin apellido es `Resident`. El formulario de M0 ya pide nombre y apellido por separado. |
| `passwd` | string | **Sí** | todos | `"$1$"` + MD5 hexadecimal de la contraseña (ver sección 4). Nunca la contraseña en claro. |
| `start` | string | **Sí** | todos | Dónde aparecer: `"last"` (última posición, valor por defecto en visor y libomv), `"home"` (casa), o `"uri:NombreRegión&x&y&z"` (lugar concreto). Ver 3.3. |
| `channel` | string | **Sí** | todos | Nombre del visor tal como se identifica ante el grid. libomv advierte que un canal vacío es violación de las ToS en algunos grids. **Ephora: `"Ephora Viewer"`.** |
| `version` | string | **Sí** | todos | Versión del visor. Visor oficial: su número de versión. **Ephora: la `versionName` de la app (`"0.1.0"` en M0).** |
| `platform` | string | **Sí** | todos | Sistema operativo del cliente. El visor oficial envía `"win"`, `"mac"` o `"lnx"` (`LL_PLATFORM_KEY`); libomv lo mismo (`GetPlatform()`). Lumiya envía `"Android"`. **Ephora: `"Android"`** (precedente Lumiya; el servidor lo usa para estadísticas, no para autenticar). |
| `platform_version` | string | Sí | visor, libomv | Versión del SO. **Ephora: `Build.VERSION.RELEASE`** (p. ej. `"14"`), como ya hace `SLAuth.kt`. |
| `platform_string` | string | No | solo visor | Nombre legible del SO. No lo envían libomv ni Lumiya. **M1: no enviar.** |
| `mac` | string | Sí | todos | Identificador (opaco) de la máquina. Ver 3.4. |
| `id0` | string | Sí | todos | Segundo identificador (opaco) de la máquina. Ver 3.4. |
| `agree_to_tos` | bool/string | Sí | todos | Aceptación de las Condiciones de Servicio. El visor oficial envía `false` en el primer intento y `true` solo después de que el usuario acepte en el diálogo de ToS; libomv y Lumiya envían `true` directamente. **M1: `true` directo** (sin diálogo de ToS en la app todavía; ver sección 6, caso `reason = "tos"`). |
| `read_critical` | bool/string | Sí | todos | El usuario ha leído los mensajes críticos del grid. Mismo comportamiento que `agree_to_tos`: visor oficial `false` primero, libomv/Lumiya `true` directo. **M1: `true` directo.** |
| `viewer_digest` | string | Sí (puede ser vacío) | todos | Hash MD5 del ejecutable del visor; solo relevante cuando el canal es un visor oficial de SL. La wiki lo documenta como `"00000000-0000-0000-0000-000000000000"` para otros casos; libomv envía cadena vacía. **M1: cadena vacía.** No copiar el UUID fijo que trae `SLAuth.kt` (`f50cfcc3-…`), que es un valor propio de Lumiya sin verificar. |
| `last_exec_event` | int | Sí | visor, libomv | Cómo terminó la última ejecución, para estadísticas: `0` normal, `1` se congeló, `2` cierre forzado por error, `3` otro crash (enum `LastExecStatus` de libomv). **M1: `0`** (o el valor real si la app lo registra; no es prioritario). |
| `last_exec_duration` | int | No | solo visor | Duración en segundos de la última ejecución. **M1: no enviar.** |
| `last_exec_session_id` | string (UUID) | No | solo visor | `session_id` de la última sesión. **M1: no enviar.** |
| `address_size` | int | No | solo visor | `32` o `64` según la arquitectura. **M1: no enviar.** |
| `host_id` | string | No | solo visor | Identificador de host guardado. **M1: no enviar.** |
| `extended_errors` | bool | No | solo visor | Si es `true`, el servidor devuelve además `message_id` y `message_args` en los errores. El visor oficial lo envía en `true`. **M1: enviar `true`** para poder mostrar errores precisos. |
| `token` | string | No | solo visor | Token de sesión MFA. Vacío en login normal. **M1: no enviar** (o vacío). |
| `mfa_hash` | string | No | solo visor | Hash MFA guardado. Solo relevante si la cuenta usa autenticación en dos pasos. **M1: no enviar; si el servidor responde `reason = "mfa_challenge"`, mostrar el mensaje y no reintentar** (ver sección 6). |
| `user-agent` | string | No | solo Lumiya | `SLAuth.kt` envía `"user-agent": "Linkpoint"`. **No aparece ni en libomv ni en el visor oficial.** No verificado. **M1: no enviar.** |
| `skipoptional` | bool | No | solo doc antigua | Aparece en la documentación de `lllogin.h` como parámetro XML-RPC, pero no se encontró en el código actual del visor ni en libomv. **Sin verificar. M1: no enviar.** |
| `options` | array de strings | Sí | todos | Lista de secciones extra que se piden en la respuesta (ver 3.5). |

### 3.3 El campo `start`

- `"last"` — entrar en la última posición (por defecto).
- `"home"` — entrar en la casa del avatar.
- `"uri:<Región>&<x>&<y>&<z>"` — entrar en una región y coordenadas concretas,
  por ejemplo `"uri:Ahern&128&128&25"`. Así lo construyen el visor oficial
  (`construct_start_string()`) y libomv (`StartLocation(sim, x, y, z)`).
  Los separadores son `&` literales dentro del string (hay que escaparlos
  como `&amp;` en el XML).
- **M1:** el formulario ofrece "última posición" y "casa" (como ya hace el
  formulario de M0). El formato `uri:` queda para teletransporte posterior,
  no para M1.

### 3.4 Los campos `mac` e `id0`: qué son de verdad

Ninguno de los dos es una dirección MAC real verificada por el servidor.
Son **identificadores opacos de la instalación** que el servidor usa para
estadísticas y para detectar inicios de sesión desde máquinas distintas.
Las tres fuentes los generan de forma distinta y las tres funcionan:

| Fuente | `mac` | `id0` |
|---|---|---|
| Visor oficial | hash MD5 del ID único de la máquina (`llHashedUniqueID`) | número de serie del disco (`mSerialNumber`) |
| libomv | MAC real de la primera tarjeta de red con formato `AA:BB:CC:DD:EE:FF`; si no hay, 12 caracteres aleatorios | el mismo valor que `mac` |
| Lumiya (`SLAuth.kt`) | `MD5("android_id")` (hash, no una MAC) | UUID de cliente generado una vez |
| Lumiya (`SLAuthSystem.kt`) | MAC aleatoria generada por login | UUID aleatorio por login |

Consecuencias para Ephora M1:

- **No hay que leer la MAC real del teléfono** (en Android moderno además
  está restringida). Basta un identificador estable.
- **Debe ser estable entre logins de la misma instalación:** generarlo una
  vez y guardarlo (p. ej. en `SharedPreferences` privado o en el almacén de
  la app). Generar uno nuevo en cada login, como hace `SLAuthSystem.kt`,
  funciona pero el servidor vería cada login como una máquina nueva.
- **Ephora M1:** un UUID aleatorio persistente como `id0`, y para `mac` el
  MD5 en hexadecimal de ese mismo identificador (formato de 32 caracteres
  hexadecimales en minúscula), o una cadena con formato MAC derivada de él.
  Documentar la elección en el código; lo importante es la estabilidad, no
  el formato exacto.

### 3.5 El array `options`: qué secciones se piden en la respuesta

Cada string del array pide al servidor que incluya esa sección en la
respuesta. Pedir una opción que luego no se interpreta no rompe nada, pero
engorda la respuesta. La lista completa es la unión del visor oficial
(`lllogininstance.cpp`) y libomv (`LoginParams()`):

| Opción | Qué devuelve | La interpreta libomv |
|---|---|---|
| `inventory-root` | carpeta raíz del inventario (`folder_id`) | Sí |
| `inventory-skeleton` | esqueleto del inventario (carpetas, sin objetos) | Sí |
| `inventory-lib-root` | raíz de la biblioteca | Sí |
| `inventory-lib-owner` | propietario de la biblioteca (`agent_id`) | Sí |
| `inventory-skel-lib` | esqueleto de la biblioteca | Sí |
| `inventory-meat` | contenido completo del inventario | No (el visor la tiene comentada) |
| `initial-outfit` | nombre de la carpeta del atuendo inicial | Sí (`folder_name`) |
| `gestures` | gestos del avatar | No |
| `display_names` | nombres para mostrar | No |
| `event_categories` | categorías de eventos | No |
| `event_notifications` | notificaciones de eventos | No |
| `classified_categories` | categorías de clasificados | No |
| `adult_compliant` | declara que el visor entiende el acceso Adulto (`A`) además de PG y M | No (pero es obligatoria para entrar en regiones Adult) |
| `buddy-list` | lista de amigos (`buddy_id`, derechos) | Sí |
| `newuser-config` | configuración de usuario nuevo | No |
| `ui-config` | configuración de interfaz | No |
| `advanced-mode` | solo para estadísticas del servidor | No |
| `max-agent-groups` | número máximo de grupos del agente | Sí |
| `map-server-url` | URL del servidor de mapas | No |
| `voice-config` | configuración de voz | No |
| `tutorial_setting` / `tutorial_settings` | ajustes del tutorial | No (ojo: el visor usa `tutorial_setting`, libomv `tutorial_settings`; la forma del visor es la de referencia) |
| `login-flags` | flags de login, entre ellos `ever_logged_in` (`"Y"`/`"N"`) | Sí (detecta primera vez) |
| `global-textures` | texturas globales | No |
| `god-connect` | solo con modo dios activado | No |

**Conjunto mínimo recomendado para M1** (todo lo que M1 necesita interpretar,
sin pedir secciones que no se van a usar todavía):

```
inventory-root, inventory-skeleton, inventory-lib-root, inventory-lib-owner,
inventory-skel-lib, buddy-list, display_names, adult_compliant, login-flags,
global-textures, max-agent-groups, gestures, event_categories,
event_notifications, classified_categories, initial-outfit, ui-config
```

Es el conjunto del ejemplo de la sección 3.1 (el de `SLAuth.kt` más
`display_names`, `login-flags`, `global-textures` y `max-agent-groups`).
`map-server-url`, `voice-config`, `newuser-config` y `advanced-mode` se
dejan fuera en M1 porque la app aún no los usa; se añadirán cuando el hito
que los necesite llegue. La voz (`voice-config`) es M5 o posterior, no M1.

## 4. El hash de la contraseña

Fórmula exacta (verificada en tres fuentes independientes):

```
passwd = "$1$" + MD5_HEX_MINÚSCULAS(contraseña)
```

- **MD5** de los bytes de la contraseña, en **hexadecimal minúsculo**
  (32 caracteres). Total con el prefijo: **35 caracteres**.
- El prefijo es literalmente `$1$` (dólar, uno, dólar).
- El visor oficial lo calcula así (`llpanellogin.cpp`):
  `LLMD5 pass((const U8*)password.c_str())` y `pass.hex_digest(md5pass)`,
  es decir, MD5 de los bytes del texto tal cual se escribe (UTF-8).
  Después guarda ese hex como `secret` y envía
  `passwd = "$1$" + secret` (`llsecapi.cpp:getLoginParams`).
- libomv (`Utils.MD5`) devuelve lo mismo: `"$1$" + hex`.
- El ejemplo LSL de la wiki lo llama "UTF-8 MD5" y calcula el MD5 sobre
  la contraseña codificada en UTF-8.
- **Nunca se envía la contraseña en claro.** Tampoco se registra en logs
  ni se guarda en claro (ver sección 8).
- La detección de "¿ya es un hash?" de libomv: si la cadena mide 35
  caracteres o empieza por `"$1$"`, no se vuelve a hashear
  (`BeginLogin()`: `if (Password.Length != 35 && !Password.StartsWith("$1$"))`).
  Para Ephora esto no aplica: la app siempre recibe la contraseña en claro
  del campo de texto y la hashea una vez.

### Discrepancia anotada: UTF-8 frente a ASCII

`Utils.MD5()` de libomv calcula el hash con `ASCIIEncoding.Default`
(ASCII, no UTF-8). El visor oficial y la referencia LSL usan los bytes
UTF-8. **Para contraseñas solo con caracteres ASCII ambas coinciden**
(ASCII y UTF-8 son idénticos en ese rango). Para contraseñas con caracteres
no ASCII (acentos, ñ, etc.) el hash difiere entre una y otra codificación.

**Decisión para M1:** MD5 sobre los bytes **UTF-8**
(`password.toByteArray(Charsets.UTF_8)` en Kotlin, o
`MessageDigest.getInstance("MD5")` como ya hace `SLAuthSystem.kt`).
Es lo que hacen el visor oficial y la referencia LSL. El caso de
contraseña no ASCII es **sin verificar contra el servidor real** y debe
probarse con una cuenta de prueba cuando llegue la primera prueba real.

### Nota: el truncado a 16 caracteres de Lumiya NO se copia

`SLAuth.getPasswordHash()` recorta la contraseña a 16 caracteres antes de
hashearla. No hay evidencia en el visor oficial ni en libomv de que el
servidor haga eso; si la contraseña real tuviera más de 16 caracteres, el
truncado rompería el login. **Ephora no recorta.** Si el sistema de cuentas
de Second Life limita las contraseñas a 16 caracteres, ese límite es del
formulario web de Linden Lab, no del protocolo; es un punto **sin verificar**
y no afecta al cálculo del hash.

## 5. La respuesta en éxito

### 5.1 Cómo saber que fue éxito

- XML-RPC: el cuerpo es `<methodResponse><params><param><value><struct>`
  con el miembro `<name>login</name>` y valor string `"true"`.
- LLSD-XML: el mapa contiene `login` como booleano `true`.
- Además viene `message` con el mensaje del día (MOTD), que la app puede
  mostrar pero que no es un error.

### 5.2 Campos de la respuesta (éxito)

Tabla construida a partir de `LoginResponseData.Parse()` de libomv y de
`SLAuthReply.kt` (que parsea exactamente los campos que el cliente debe
usar). "Tipo" es el tipo lógico; en XML-RPC los UUID llegan como string,
los enteros como `<int>`/`<i4>` y los booleanos como string `"true"`/`"false"`.

| Campo | Tipo | Para qué sirve (y qué hace Ephora con él) |
|---|---|---|
| `login` | string `"true"` / bool | `"true"` = éxito. Es el campo que decide todo. |
| `session_id` | UUID | **ID de sesión. Imprescindible.** Identifica la sesión ante el simulador y en el circuito UDP (M2). Guardarlo en memoria de sesión (no persistirlo entre arranques). |
| `secure_session_id` | UUID | ID de sesión segura, para operaciones sensibles (p. ej. dinero, capacidades seguras). Guardarlo junto a `session_id`. |
| `agent_id` | UUID | **UUID del avatar. Imprescindible.** Es la identidad del usuario en el mundo. Guardarlo. |
| `first_name` | string | Nombre confirmado por el servidor (puede llevar comillas; libomv hace `.Trim('"')`). Mostrarlo en la UI. |
| `last_name` | string | Apellido confirmado por el servidor. Mostrarlo en la UI. |
| `start_location` | string | Dónde se entró realmente (`"last"`, `"home"` o la URI pedida). Informativo. |
| `agent_access` | string | Nivel de acceso de madurez del agente: `"PG"`, `"M"` o `"A"` (también hay `agent_access_max` y `agent_region_access`). Determina a qué regiones se puede entrar. |
| `look_at` | vector3 (string en notación LLSD) | Dirección inicial de la mirada, p. ej. `"[0.99, 0.0, 0.0]"`. Para la cámara inicial (M3). |
| `home` | mapa (string en notación LLSD) | Casa del avatar: `region_handle` (array de 2 enteros → handle de 64 bits), `position` y `look_at`. Para el botón "ir a casa". |
| `circuit_code` | entero (uint32) | **Código del circuito. Imprescindible para M2.** Se envía en el primer paquete UDP al simulador para reclamar el circuito. Guardarlo. |
| `region_x`, `region_y` | enteros | Coordenadas de la región en la rejilla (múltiplos de 256, en metros globales). Para el mapa y para calcular el handle de región. |
| `sim_ip` | string | **IP del simulador. Imprescindible para M2.** Dirección IPv4 en texto (p. ej. `"216.82.42.55"`). |
| `sim_port` | entero | **Puerto UDP del simulador. Imprescindible para M2.** |
| `seed_capability` | string (URL) | **URL de la capability semilla. Imprescindible.** Es la puerta de entrada a todas las capabilities HTTP del simulador. Se usa en el paso siguiente al login (ver sección 7). |
| `buddy-list` | array de mapas | Amigos: `buddy_id` (UUID), `buddy_rights_given`, `buddy_rights_has` (enteros). Para la lista de contactos. |
| `seconds_since_epoch` | entero | Hora del servidor (segundos desde 1970-01-01). Para sincronizar relojes y sellos de tiempo. |
| `inventory-root` | array de 1 mapa | Carpeta raíz del inventario: `folder_id` (UUID). Base del inventario (M5). |
| `inventory-skeleton` | array de mapas | Esqueleto del inventario: cada carpeta con `folder_id`, `parent_id`, `name`, `type`, `version`. |
| `inventory-lib-root` | array de 1 mapa | Raíz de la biblioteca: `folder_id`. |
| `inventory-lib-owner` | array de 1 mapa | Propietario de la biblioteca: `agent_id`. |
| `inventory-skel-lib` | array de mapas | Esqueleto de la biblioteca. |
| `udp_blacklist` | string | Lista de direcciones UDP bloqueadas (si la hay). Informativo. |
| `max-agent-groups` | entero | Número máximo de grupos a los que puede pertenecer el agente. Si falta, libomv usa `-1` (sin límite conocido). |
| `openid_url` | string | URL OpenID del agente (si el servidor la envía). No usar en M1. |
| `agent_appearance_service` | string (URL) | URL del servicio de apariencia del agente (para el avatar, M4). Guardarla. |
| `cof_version` | entero | Versión de la carpeta "Current Outfit". Para el atuendo (M4). |
| `initial-outfit` | array de mapas | Atuendo inicial: `folder_name`. Para el avatar (M4). |
| `login-flags` | array de mapas | Flags de login; `ever_logged_in` vale `"N"` si es la primera vez que la cuenta entra (libomv: `FirstLogin`). Para mostrar la bienvenida de primer login. |
| `message` | string | Mensaje del día (MOTD) en éxito; mensaje de error en fallo. Mostrarlo al usuario en éxito si no está vacío. |
| `reason` | string | Vacío en éxito. En fallo indica la causa (ver sección 6). |
| `message_id`, `message_args` | string, mapa | Solo si se envió `extended_errors = true`. Identificador localizable del error y sus argumentos (p. ej. `message_args["VERSION"]` en `reason = "update"`). |

### 5.3 Ejemplo de respuesta (XML-RPC, éxito)

Reconstruido a partir de los campos documentados arriba; **no es una
respuesta real capturada** (los UUID, IP y valores son de ejemplo):

```xml
<?xml version="1.0"?>
<methodResponse>
  <params>
    <param>
      <value>
        <struct>
          <member><name>login</name><value><string>true</string></value></member>
          <member><name>session_id</name><value><string>11111111-2222-3333-4444-555555555555</string></value></member>
          <member><name>secure_session_id</name><value><string>66666666-7777-8888-9999-000000000000</string></value></member>
          <member><name>agent_id</name><value><string>aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee</string></value></member>
          <member><name>first_name</name><value><string>Nombre</string></value></member>
          <member><name>last_name</name><value><string>Apellido</string></value></member>
          <member><name>circuit_code</name><value><int>123456789</int></value></member>
          <member><name>region_x</name><value><int>256000</int></value></member>
          <member><name>region_y</name><value><int>256000</int></value></member>
          <member><name>sim_ip</name><value><string>216.82.42.55</string></value></member>
          <member><name>sim_port</name><value><int>13000</int></value></member>
          <member><name>seed_capability</name><value><string>https://sim1234.agni.lindenlab.com:12043/cap/00000000-0000-0000-0000-000000000000</string></value></member>
          <member><name>message</name><value><string>Welcome to Second Life!</string></value></member>
          <member><name>seconds_since_epoch</name><value><int>1794999999</int></value></member>
          <!-- … inventory-root, buddy-list, etc. según las options pedidas … -->
        </struct>
      </value>
    </param>
  </params>
</methodResponse>
```

**Regla de parseo para M1:** el parser debe tolerar campos ausentes
(devolver `null` / valor por defecto) y tipos flexibles (string `"true"` o
booleano, entero como string o como int). El servidor no garantiza el orden
de los miembros. Si faltan `session_id`, `agent_id`, `circuit_code`,
`sim_ip`, `sim_port` o `seed_capability` en una respuesta con `login =
"true"`, la app debe tratarlo como **login fallido con mensaje honesto**
("el servidor respondió sin los datos necesarios"), no como éxito parcial.

## 6. Respuestas de error: cómo se ven y cómo mostrarlas

### 6.1 Forma general del fallo

Con `login = "false"` (string en XML-RPC, booleano en LLSD), la respuesta
trae siempre:

| Campo | Significado |
|---|---|
| `login` | `"false"` |
| `reason` | código corto de la causa (ver 6.2) |
| `message` | **mensaje en texto legible del servidor. Es lo que se muestra al usuario, tal cual.** |
| `message_id`, `message_args` | solo si se envió `extended_errors = true`; clave del mensaje localizable y sus argumentos |

**Regla de oro de honestidad (M1):** ante un fallo, la app muestra el
`message` del servidor tal cual (o, si viene vacío, un texto genérico
honesto: "El servidor de Second Life rechazó el inicio de sesión" más el
`reason` si lo hay). Nunca se inventa un motivo, nunca se muestra un éxito
falso, nunca se reintenta a escondidas con otros datos.

### 6.2 Códigos `reason` (visor oficial, `handleLoginFailure`)

| `reason` | Qué significa | Qué debe hacer la app |
|---|---|---|
| `key` | Nombre de usuario o contraseña incorrectos (o cuenta no encontrada). Es el error más común. | Mostrar el `message` del servidor. No reintentar. Sugerir revisar la contraseña (distingue mayúsculas) y el enlace de recuperación de la web de SL. |
| `presence` | El avatar sigue conectado: "The system is logging you out..." (presencia atascada tras un cierre o crash). | Mostrar el mensaje y decir que hay que esperar unos minutos antes de reintentar. No reintentar automáticamente. (Fuente de usuario: KB "Login failure" de Linden Lab.) |
| `connect` | No se pudo conectar con un simulador ("Login failed. Unable to connect to a simulator."). | Mostrar el mensaje. Sugerir comprobar la red y probar otra región de inicio (`start = "home"` o `"last"`). |
| `tos` | Hay que aceptar las Condiciones de Servicio. El `message` trae el texto de las ToS. | Mostrar el texto y pedir aceptación explícita del usuario; si acepta, **repetir el login con `agree_to_tos = true`** (es el flujo del visor oficial: `handleTOSResponse`). Si no acepta, no entrar. |
| `critical` | Mensaje crítico del grid que hay que leer. Puede traer `error_code` y `certificate`. | Mostrar el mensaje (y el código si lo hay); si el usuario confirma haberlo leído, **repetir el login con `read_critical = true`**. |
| `update` | El visor debe actualizarse: `message_args["VERSION"]` trae la versión exigida. | Mostrar el mensaje con la versión. Para un visor de terceros con canal propio este caso no debería darse; si se da, mostrarlo tal cual y no fingir la versión. |
| `mfa_challenge` | La cuenta exige autenticación en dos pasos (MFA). El `message_id` indica qué mensaje mostrar. | Mostrar el mensaje. **M1 no implementa MFA**: no hay campo para el código. No reintentar. (Flujo MFA completo: hito posterior, sin verificar.) |
| *(vacío u otro)* | Causa desconocida. | Mostrar el `message` si lo hay; si no, "El inicio de sesión falló por un motivo desconocido" con el `reason` si existe. No reintentar. |

### 6.3 La respuesta `indeterminate`: no es un error, es una redirección

Si `login` es `"indeterminate"`, el servidor pide reintentar en otro sitio:

| Campo | Significado |
|---|---|
| `next_url` | nueva URL de login a la que publicar |
| `next_method` | nuevo método (normalmente `login_to_simulator` otra vez) |
| `next_options` | opciones adicionales que hay que añadir a las ya enviadas |
| `next_duration` | duración sugerida (segundos) |

Flujo: publicar de nuevo la **misma petición** (mismos parámetros y
`options` originales **más** `next_options`) a `next_url` con
`next_method`. libomv lo hace en `Parse(Hashtable)` y el visor oficial en
el estado `indeterminate` del módulo de login. **M1 debe implementar este
caso** (es barato: es el mismo POST a otra URL). Si la redirección vuelve a
dar `indeterminate`, repetir como mucho 2-3 veces y después fallar con
mensaje honesto; nunca bucle infinito.

### 6.4 Fallos a nivel HTTP / XML-RPC

| Situación | Cómo se ve | Qué hace la app |
|---|---|---|
| HTTP no 200 (p. ej. 500, 503) | código de estado HTTP | "No se pudo contactar con el servidor de login (HTTP 503)". No tratarlo como contraseña incorrecta. |
| Tiempo de espera agotado | sin respuesta | "Tiempo de espera agotado al contactar con el servidor". Ofrecer reintentar manualmente. |
| Sin red / DNS | excepción de red | "Sin conexión a internet" (o el error de red real). |
| Cuerpo vacío o XML roto | respuesta no parseable | "El servidor devolvió una respuesta no válida". |
| `<fault>` XML-RPC | `<fault><value><struct>` con `faultCode` y `faultString` | Mostrar el `faultString` como mensaje. (`SLAuthReply.kt` ya contempla este caso.) |

### 6.5 Errores típicos que verá el usuario (lenguaje de la KB de Linden Lab)

- **Contraseña incorrecta** (`key`): revisar mayúsculas; si se olvidó,
  recuperar desde la web de Second Life.
- **"The system is logging you out..."** (`presence`): esperar 5–15 minutos
  y reintentar; es presencia atascada, no un error de la app.
- **"Your account is not accessible until (fecha/hora)"**: cuenta bloqueada
  por Linden Lab (sanción) o cierre de sesión en curso. Mostrar el mensaje
  tal cual; no es algo que la app pueda resolver.
- **"You cannot log into Second Life from this location"**: bloqueo por
  Linden Lab desde esa IP/máquina. Mostrarlo tal cual.
- **"Despite our best efforts, something has gone wrong" (DOBE)**: problema
  de red o del grid; revisar https://status.secondlifegrid.net.
- **"Login failed. Unable to connect to a simulator."** (`connect`): el
  login autenticó pero no hay simulador disponible; probar otra región.

## 7. Qué hace el cliente inmediatamente después del login

Resumen (el detalle del circuito UDP es el hito M2; aquí solo el orden y
los datos):

1. **Guardar en memoria de sesión (no en disco, salvo `id0`/`mac`):**
   `session_id`, `secure_session_id`, `agent_id`, `circuit_code`,
   `sim_ip`, `sim_port`, `region_x`, `region_y`, `seed_capability`,
   `inventory-root` (UUID), `agent_appearance_service`,
   `first_name`/`last_name`, `agent_access`. La contraseña en claro se
   descarta en cuanto se calcula el hash; el hash `$1$…` no se guarda en
   M1 salvo que se decida explícitamente el "recordar sesión" (ver 8).
2. **Mostrar el MOTD** (`message`) si venía en la respuesta.
3. **Abrir el circuito UDP con el simulador (M2):** conectar por UDP a
   `sim_ip:sim_port` y reclamar el circuito con `circuit_code`,
   `session_id` y `agent_id` (paquete `UseCircuitCode` y derivados).
   Sin este paso no hay mundo: el login por sí solo no conecta al avatar.
4. **Pedir las capabilities con la seed capability:** POST HTTPS al
   `seed_capability` con un array LLSD de los nombres de capabilities que
   se quieren (p. ej. `["MapLayer", "InventoryAPIv3", …]`). La respuesta
   es un mapa LLSD nombre → URL. Formato verificado en la wiki
   (`Current Sim Capabilities` y `Lslsimcaps`):
   - Petición: `<llsd><array><string>MapLayer</string></array></llsd>`
     con `Content-Type` LLSD (`text/html` en el ejemplo LSL antiguo;
     libomv usa `application/llsd+xml` para sus POST de capabilities).
   - Respuesta: `<llsd><map><key>MapLayer</key><string>https://…</string>
     </map></llsd>`.
   - La lista completa de nombres de capabilities que pide el visor
     oficial está en `LLViewerRegionImpl::buildCapabilityNames`
     (`indra/newview/llviewerregion.cpp`); en M1 basta con pedir las
     necesarias para el siguiente hito.
5. **Después:** el avatar ya está "dentro" a nivel de protocolo; el mundo
   visible (M3) y el avatar (M4) se construyen sobre ese circuito y esas
   capabilities.

## 8. Seguridad y red

- **Solo HTTPS.** El login lleva la contraseña (hasheada) y devuelve los
  identificadores de sesión. Nunca HTTP.
- **Validación de certificados con el almacén del sistema.** libomv pone
  `ServicePointManager.CertificatePolicy = new TrustAllCertificatePolicy()`
  (acepta cualquier certificado) en `BeginLogin()`. **Eso es un
  antipatrón y NO se copia:** Ephora debe validar el certificado del
  servidor con el almacén de confianza de Android y fallar si no es
  válido. Si el certificado de `login.agni.lindenlab.com` no valida en
  algún dispositivo, eso es un problema a reportar, no a sortear.
- **Sin registro de credenciales.** Ni la contraseña en claro, ni el hash
  `$1$…`, ni `session_id` / `secure_session_id` / `circuit_code` van a los
  logs de la app. El log de depuración de la petición que hace el visor
  oficial excluye explícitamente las credenciales
  (`lllogininstance.cpp`: "log request_params _before_ adding the
  credentials").
- **Contraseña en memoria lo mínimo:** leerla del campo, calcular el hash,
  limpiar el campo y la referencia. No guardarla en `SharedPreferences`
  ni en disco en M1. Si más adelante se añade "recordar contraseña", será
  con el almacén cifrado de Android (EncryptedSharedPreferences) y con
  decisión explícita del usuario; fuera del alcance de M1.
- **MD5 es débil, pero es lo que exige el protocolo.** El hash `$1$`+MD5
  es el formato que el servidor espera desde 2007; no se puede sustituir
  por SHA-256. La confidencialidad real la da TLS, no el hash.
- **Timeouts y reintentos:**
  - libomv: `LOGIN_TIMEOUT = 60 s` y `CAPS_TIMEOUT = 60 s`
    (`Settings.cs:109,112`).
  - Visor oficial: timeout de login configurable entre 10 y 180 s
    (`LOGIN_SRV_TIMEOUT_MIN/MAX`) y **`LOGIN_MAX_RETRIES = 0`: el visor
    no reintenta el login automáticamente**.
  - **M1: timeout 60 s, sin reintento automático.** Un reintento solo si
    el usuario pulsa de nuevo "Entrar" o si el servidor devuelve
    `indeterminate` (caso 6.3, máximo 2–3 redirecciones).
- **Un solo intento por pulsación.** El botón se desactiva mientras el
  login está en curso y se reactiva con el resultado (éxito o error).
- **La petición se hace en un hilo de fondo** (nunca en el hilo de UI),
  con cancelación si el usuario sale de la pantalla de login.
- **Nombre de usuario:** el formulario de M0 pide nombre y apellido por
  separado. Si un usuario escribe un nombre único con espacio ("Nombre
  Apellido"), dividir por el primer espacio (así lo hace `SLAuth.kt`).
  El apellido por defecto para cuentas sin apellido es `Resident`.

## 9. Discrepancias entre fuentes y puntos sin verificar

| # | Punto | Fuentes | Decisión para M1 |
|---|---|---|---|
| D1 | Codificación del MD5 de la contraseña: UTF-8 (visor oficial, LSL) frente a ASCII (libomv `Utils.MD5`) | Visor: `LLMD5(password.c_str())`; LSL: "UTF-8 MD5"; libomv: `ASCIIEncoding.Default` | MD5 sobre bytes UTF-8. Idéntico para ASCII. El caso no ASCII queda **sin verificar** contra el servidor real. |
| D2 | `mac`: MAC real (libomv) frente a hash MD5 de ID (visor) frente a MD5 de `android_id` (Lumiya) | Las tres funcionan según su propio código | Identificador estable derivado de un UUID persistente; el formato exacto no lo valida el servidor (ver 3.4). |
| D3 | `id0`: igual que `mac` (libomv) frente a número de serie (visor) frente a UUID de cliente (Lumiya) | Ídem | UUID persistente propio de la instalación. |
| D4 | `agree_to_tos` / `read_critical`: `false` primero y diálogo (visor) frente a `true` directo (libomv, Lumiya) | Visor: `handleTOSResponse`; libomv: `AgreeToTos = true` | `true` directo en M1; si el servidor responde `reason = "tos"` o `"critical"`, mostrar el mensaje y reintentar solo con aceptación explícita. |
| D5 | `viewer_digest`: vacío (libomv) frente a UUID cero (wiki) frente a UUID fijo `f50cfcc3-…` (Lumiya) | — | Cadena vacía. El UUID fijo de Lumiya no se copia: es un valor ajeno sin verificar. |
| D6 | `user-agent`: solo lo envía Lumiya (`"Linkpoint"`) | `SLAuth.kt:62`; ausente en visor y libomv | No enviar. Sin verificar. |
| D7 | `platform`: `"win"`/`"mac"`/`"lnx"` (visor, libomv) frente a `"Android"` (Lumiya) | Visor: `LL_PLATFORM_KEY`; Lumiya: `PLATFORM = "Android"` | `"Android"`. Es informativo, no de autenticación. |
| D8 | `skipoptional`: solo en la documentación antigua de `lllogin.h` | No aparece en el código actual consultado | No enviar. Sin verificar. |
| D9 | Truncado de la contraseña a 16 caracteres antes del hash (Lumiya) | Solo `SLAuth.kt:getPasswordHash` | No truncar. Sin verificar si SL limita las contraseñas a 16 caracteres en su web. |
| D10 | ¿Sigue aceptando `login.cgi` el XML-RPC clásico en 2026? | libomv lo usa por defecto (`USE_LLSD_LOGIN = false`); el análisis de la APK oficial de SL de 2026 (Lumiya-Redux) dice que el endpoint XML-RPC "sigue referenciado" y que "la ruta de login existente de Lumiya sigue siendo válida" | Sí, con confianza alta, pero **la confirmación definitiva es la primera prueba real de login** (M1). |
| D11 | Lista de `options`: el visor usa `tutorial_setting` y libomv `tutorial_settings` | `lllogininstance.cpp:195` frente a `LoginParams()` | Usar `tutorial_setting` (visor oficial). |
| D12 | La wiki `Current login protocols` exige login desde 2026 | Comprobado 2026-11-18 | No se pudo contrastar contra esa página; sus datos se sustituyeron por el código del visor y libomv. |
| D13 | MFA (`mfa_hash`/`token`/`mfa_challenge`) | Solo el visor oficial lo implementa | M1 no implementa MFA; ante `mfa_challenge`, mostrar el mensaje y parar. Sin verificar el flujo completo. |
| D14 | ¿Valida el servidor el `channel` contra una lista? | Sin evidencia en ninguna fuente consultada | No. Los visores de terceros usan su propio canal (Firestorm, Lumiya). Sin verificar al 100% hasta la primera prueba. |

## 10. Checklist de implementación (M1)

- [ ] Habilitar el botón de login solo cuando nombre, apellido y contraseña
      no estén vacíos.
- [ ] Construir la petición XML-RPC exacta de la sección 3.1 con los
      campos de la tabla 3.2 y el conjunto mínimo de `options` (3.5).
- [ ] Calcular `passwd = "$1$" + MD5_HEX_UTF8(contraseña)` (sección 4).
- [ ] Generar y persistir `id0` (UUID) y derivar `mac` una sola vez por
      instalación (sección 3.4).
- [ ] POST HTTPS a la URL de la rejilla elegida, timeout 60 s, sin
      reintento automático, en hilo de fondo.
- [ ] Parsear la respuesta tolerando tipos flexibles y campos ausentes;
      distinguir `"true"` / `"false"` / `"indeterminate"`.
- [ ] Éxito: guardar los 6 datos imprescindibles (`session_id`,
      `secure_session_id`, `agent_id`, `circuit_code`, `sim_ip`,
      `sim_port`, `seed_capability`) y pasar a M2.
- [ ] Fallo: mostrar el `message` del servidor tal cual; mapear `reason`
      según la tabla 6.2; `tos`/`critical` piden aceptación y reintento
      explícito; `indeterminate` re-publica a `next_url` (máx. 2–3 veces).
- [ ] No registrar credenciales ni identificadores de sesión en logs.
- [ ] Validar el certificado TLS con el almacén del sistema (nunca
      "confiar en todos").
- [ ] Probar con la rejilla Aditi primero si hay cuenta de prueba, y con
      Agni después. Registrar qué campos devolvió realmente el servidor
      (puede traer más o menos de los documentados) y actualizar este
      documento con lo observado.

## Fuentes (URLs)

- Visor oficial, login:
  `https://github.com/secondlife/viewer/blob/main/indra/newview/lllogininstance.cpp`
- Visor oficial, credenciales:
  `https://github.com/secondlife/viewer/blob/main/indra/newview/llsecapi.cpp`
- Visor oficial, hash de contraseña:
  `https://github.com/secondlife/viewer/blob/main/indra/newview/llpanellogin.cpp`
- Visor oficial, módulo de login (documentación):
  `https://github.com/secondlife/viewer/blob/main/indra/viewer_components/login/lllogin.h`
- libopenmetaverse, login:
  `https://github.com/openmetaversefoundation/libopenmetaverse/blob/master/OpenMetaverse/Login.cs`
- libopenmetaverse, ajustes (endpoints y timeouts):
  `https://github.com/openmetaversefoundation/libopenmetaverse/blob/master/OpenMetaverse/Settings.cs`
- libopenmetaverse, `Utils.MD5`:
  `https://github.com/openmetaversefoundation/libopenmetaverse/blob/master/OpenMetaverseTypes/Utils.cs`
- libopenmetaverse, POST LLSD:
  `https://github.com/openmetaversefoundation/libopenmetaverse/blob/master/OpenMetaverse/Capabilities/CapsClient.cs`
- Wiki Second Life, ejemplo de login:
  `https://wiki.secondlife.com/wiki/Lslsimcaps`
- Wiki Second Life, capabilities:
  `https://wiki.secondlife.com/wiki/Current_Sim_Capabilities`
- Wiki Second Life, arquitectura del servidor:
  `https://wiki.secondlife.com/wiki/Server_architecture`
- Soporte Linden Lab, errores de login:
  `https://community.secondlife.com/knowledgebase/english/login-failure-r47/`
- Referencia local (solo consulta):
  `~/workspace/linkpoint/Linkpoint-0.5/Linkpoint/src/main/kotlin/com/linkpoint/slproto/auth/SLAuth.kt`,
  `SLAuthParams.kt`, `SLAuthReply.kt`, `SLAuthSystem.kt`
- Análisis APK oficial 2026 (Lumiya-Redux):
  `https://github.com/kaleaon/lumiya-redux/blob/HEAD/docs/secondlife_apk_2026_analysis.md`
