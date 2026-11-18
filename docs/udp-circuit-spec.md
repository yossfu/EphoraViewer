# Especificación del protocolo UDP de Second Life (circuito + handshake) — Hito M2 de Ephora Viewer

- **Proyecto:** Ephora Viewer (`com.ephora.viewer`, repo `yossfu/EphoraViewer`)
- **Alcance:** solo el circuito UDP con el simulador y el handshake de región. Nada
  simulado: este documento describe el intercambio exacto de paquetes que la app
  debe hacer con el simulador de Linden Lab después del login. El mundo 3D
  (terreno, prims, texturas) es el hito M3 y aquí solo se prepara.
- **Fecha de la investigación:** 2026-11-18.
- **Estado:** especificación para implementar. No es código; no se ha probado
  contra el simulador real. Todo lo marcado "sin verificar" debe confirmarse
  con la primera prueba real de handshake en el móvil.
- **Idioma:** los nombres de mensajes, campos, URLs y valores de protocolo están
  en inglés tal cual los define el protocolo; el resto del documento está en
  español.
- **Depende de:** hito M1 (login real XML-RPC, `docs/login-protocol-spec.md`).
  Sin una sesión válida de M1 (`session_id`, `agent_id`, `circuit_code`,
  `sim_ip`, `sim_port`, `seed_capability`) no hay circuito que abrir.

## Fuentes primarias (en orden de autoridad)

1. **Visor oficial de Second Life** (`secondlife/viewer`, GitHub), rama
   `develop` consultada 2026-11-18:
   - `scripts/messages/message_template.msg` (18.334 líneas, versión 2.0) —
     la plantilla de mensajes: números, frecuencias, bloques y campos de cada
     mensaje. Es la referencia normativa para todos los números de esta
     especificación.
   - `indra/newview/llviewerregion.cpp` — `LLViewerRegionImpl::buildCapabilityNames()`
     (lista completa de capabilities que pide el visor oficial),
     `requestBaseCapabilitiesCoro()` (reintentos de la petición al seed
     capability), `setSeedCapability()` y `getCapability()`.
2. **libopenmetaverse** (`openmetaversefoundation/libopenmetaverse`, GitHub),
   rama `master` consultada 2026-11-18:
   - `OpenMetaverse/NetworkManager.cs` — `Connect()`, `RegionHandshakeHandler`,
     `StartPingCheckHandler`, `CompletePingCheckHandler`, `KickUserHandler`,
     `Shutdown()`, `BeginLogout()`/`Logout()`.
   - `OpenMetaverse/Simulator.cs` — `Connect()`, `UseCircuitCode()`,
     `SendPing()`, `PacketReceived()`, `SendAcks()`, `ResendUnacked()`,
     `Disconnect()`, `SendPacketData()`, `SetSeedCaps()`; enums `RegionFlags`,
     `RegionProtocols`, `SimAccess`.
   - `OpenMetaverse/Settings.cs` — todos los timeouts e intervalos
     (`LOGIN_TIMEOUT`, `SIMULATOR_TIMEOUT`, `PING_INTERVAL`, etc.).
   - `OpenMetaverse/AgentThrottle.cs` — canales del throttle, límites,
     valores por defecto y construcción del array de 28 bytes.
   - `OpenMetaverse/AgentManager.cs` — `CompleteAgentMovement()`.
   - `OpenMetaverse/AgentManagerMovement.cs` — `AgentMovement.SendUpdate()`,
     `SendManualUpdate()`, `SetFOVVerticalAngle()`, enum `ControlFlags`
     (32 bits), `UpdateTimer_Elapsed()`.
3. **Wiki de Second Life** (Linden Lab):
   - `Packet Layout` (wiki.secondlife.com/wiki/Packet_Layout) — cabecera,
     flags, número de mensaje, bloques, ACKs añadidos, extensiones 2023–2024.
   - `Zerocode` — compresión por run-length de ceros.
   - `AgentThrottle` — orden de los 7 canales del throttle.
   - `How movement works` — `AgentUpdate` hasta 20 veces por segundo, enviado
     una vez por frame si cambia.
   - `Capabilities` — flujo general de capabilities (el detalle del formato
     está en la spec M1, sección de capabilities).
4. **Material local de referencia** (solo consulta, nunca base de código):
   - `~/workspace/linkpoint/Linkpoint-0.5/Linkpoint/src/main/assets/message_template.msg`
     — copia local de la plantilla (coincide en los números usados aquí:
     `UseCircuitCode` Low 3, `RegionHandshake` Low 148,
     `RegionHandshakeReply` Low 149, `CompleteAgentMovement` Low 249,
     `AgentMovementComplete` Low 250).
   - `~/workspace/linkpoint/Linkpoint-0.5/Linkpoint/src/main/kotlin/com/linkpoint/slproto/SLAgentCircuit.kt`
     — código descompilado de Lumiya/Linkpoint con errores conocidos; se usa
     solo para contrastar el orden de envío y los valores de algunos campos
     (`SendUseCode`, `SendCompleteAgentMovement`, `SendAgentFOV`,
     `HandleRegionHandshake`, `DoAgentPause`/`DoAgentResume`). Cada
     divergencia con el visor oficial o libomv está anotada en la sección 10.

---

## 1. El circuito UDP: qué es

- Después del login, el cliente abre un **socket UDP** (`DatagramSocket` en
  Android) contra `sim_ip:sim_port` de la respuesta de M1. Ese par es el
  simulador (la región) donde entra el avatar. No hay TCP en el circuito.
- Un **circuito** es la relación cliente↔simulador identificada por el
  `circuit_code` del login. El primer paquete del cliente es siempre
  `UseCircuitCode` con ese código; hasta que el simulador lo acepta, no hay
  circuito.
- Cada **datagrama UDP contiene exactamente un mensaje** (wiki `Packet
  Layout`). No hay fragmentación a nivel de mensaje: si un mensaje no cabe
  en el MTU, no se envía en varios paquetes, se descarta o se divide a nivel
  de aplicación (fuera de M2).
- **El circuito UDP no está cifrado.** Lo sabe todo el que pueda ver el
  tráfico: `session_id`, `agent_id` y `circuit_code` viajan en claro en
  casi todos los paquetes. Ver sección 9: nunca van a los logs.
- El cliente debe mantener **dos hilos** (como libomv `NetworkManager`):
  uno de recepción ("Incoming UDP packet dispatcher") y uno de envío
  ("Outgoing UDP packet dispatcher"). En Android, nunca en el hilo de UI.
  El socket debe sobrevivir a los cambios de actividad; pertenece a un
  servicio o a un objeto de aplicación, no a la `Activity`.
- Un cliente puede tener circuitos con **varias regiones a la vez**
  (región principal + vecinas); en M2 solo hay una: la región del login.
  Los mensajes `EnableSimulator` (Low 151) / `DisableSimulator` (Low 152)
  abren/cierran circuitos con regiones vecinas y quedan **fuera de M2**.

## 2. Formato del paquete UDP

### 2.1 Cabecera (6 bytes, siempre)

| Bytes | Contenido |
|---|---|
| 0 | Flags (ver 2.2) |
| 1–4 | Número de secuencia, entero sin signo **big-endian** (wiki `Packet Layout`) |
| 5 | Número de bytes de cabecera extra. Normalmente `0`. Si no es cero, hay información extra de cabecera; un cliente que no la espera debe saltar esos bytes y seguir en el payload |

- El número de secuencia es **estrictamente creciente por paquete enviado**
  dentro de un circuito. Recomendado: empezar en `1` e incrementar en 1 por
  paquete. El máximo es `MAX_SEQUENCE = 0xFFFFFF` (libomv `Settings.cs`):
  al llegar ahí, el contador vuelve a empezar (wrap). El valor inicial
  exacto no se verificó en esta sesión (ver D7).
- El número de secuencia se usa para los ACKs y para la deduplicación de
  paquetes fiables entrantes (ver 6.1).

### 2.2 Flags del byte 0

| Bit | Valor | Nombre | Significado |
|---|---|---|---|
| 7 | `0x80` | `LL_ZERO_CODE_FLAG` | El cuerpo del paquete está comprimido con zerocode (ver 2.5) |
| 6 | `0x40` | `LL_RELIABLE_FLAG` | Paquete fiable: el receptor debe confirmarlo con ACK (ver 6.1) |
| 5 | `0x20` | `LL_RESENT_FLAG` | Este paquete es una retransmisión de uno ya enviado |
| 4 | `0x10` | `LL_ACK_FLAG` | El paquete lleva ACKs añadidos al final (ver 2.6) |
| 0–3 | — | — | Sin usar actualmente |

### 2.3 Número de mensaje (a partir del byte 6)

- Codificación de longitud variable, **big-endian**, según la frecuencia del
  mensaje (wiki `Packet Layout`, plantilla `message_template.msg`):
  - **High:** 1 byte, `0x01`–`0xFE`.
  - **Medium:** 2 bytes, `0xFF01`–`0xFFFE`.
  - **Low:** 4 bytes, `0xFFFF0001`–`0xFFFFFFF9`.
  - **Fixed:** 4 bytes, `0xFFFFFFFA`–`0xFFFFFFFF`.
- El número identifica el mensaje; la plantilla define sus bloques y campos.
  El resto del paquete se decodifica según esa plantilla.

### 2.4 Bloques y campos del cuerpo

- El cuerpo se compone de **bloques**. Cada bloque tiene un tipo de
  repetición en la plantilla:
  - `Single`: aparece exactamente una vez, sin bytes de cabecera.
  - `Fixed`: número fijo de repeticiones, sin bytes de cabecera.
  - `Multiple`: número de repeticiones fijado por el mensaje, sin bytes de
    cabecera (el count viene de otro campo; no se usa en los mensajes de M2).
  - `Variable`: 1 byte con el número de repeticiones, antes de los datos.
  - `Variable 2`: **2 bytes little-endian** con el número de repeticiones
    (un count de 7 es `0x07 0x00`) (wiki `Packet Layout`).
- Los nombres de bloque de la plantilla son solo para lectura humana: **no
  se codifican en el paquete**.
- Tipos de campo y tamaños (plantilla `message_template.msg`):

| Tipo | Bytes | Notas |
|---|---|---|
| `U8` / `S8` / `BOOL` | 1 | `BOOL`: `0` = falso, distinto de `0` = verdadero |
| `U16` / `S16` | 2 | little-endian (ver D11) |
| `U32` / `S32` | 4 | little-endian (ver D11) |
| `U64` / `S64` | 8 | little-endian (ver D11) |
| `F32` | 4 | little-endian, IEEE 754. Verificado: `AgentThrottle.cs` usa `Utils.FloatToBytes` (little-endian) con corrección explícita si la plataforma fuera big-endian |
| `F64` | 8 | little-endian (ver D11) |
| `LLUUID` | 16 | Orden de bytes sin verificar en esta sesión (ver D2) |
| `LLVector3` | 12 | 3 × `F32` little-endian (X, Y, Z) |
| `LLQuaternion` | 12 | 3 × `F32` (X, Y, Z); el componente W se reconstruye. Sin verificar en esta sesión (ver D3) |
| `IPADDR` | 4 | Los 4 octetos en orden |
| `IPPORT` | 2 | Orden de bytes sin verificar en esta sesión (ver D2) |
| `Variable 1` | 1 + N | 1 byte de longitud + N bytes de texto/datos |
| `Variable 2` | 2 + N | 2 bytes little-endian de longitud + N bytes |
| `Fixed` | N | N bytes fijos (N lo define la plantilla) |

- **Cadenas de texto (`Variable 1` / `Variable 2`): UTF-8.** Nota de
  contraste: Lumiya usa helpers `stringToVariableOEM` /
  `stringFromVariableOEM`; si un nombre de región con caracteres no ASCII se
  decodifica mal, revisar la codificación (se espera UTF-8).
- **Regla de oro de endianness:** cabecera (secuencia, número de mensaje)
  **big-endian**; cuerpo (campos numéricos, counts `Variable 2`, floats)
  **little-endian**. Es la asimetría clásica del protocolo SL y la causa más
  probable de un handshake que "no se entiende" con el simulador.
- **Extensiones 2023–2024** (wiki `Packet Layout`, revisión dic 2024): los
  mensajes con bytes de más respecto a la plantilla se aceptan y decodifican
  (no se rechazan como error); un mensaje que termina justo antes del byte
  de count de un bloque `Variable` final se lee como cero repeticiones. El
  decodificador de Ephora debe tolerar ambas cosas.

### 2.5 Zerocode (compresión de ceros)

- Si el flag `0x80` está activo, el cuerpo está comprimido (wiki `Zerocode`):
  cada serie de 1 a 255 bytes cero se codifica como **2 bytes: primero el
  cero (`0x00`), luego el count**. Ejemplo: 255 ceros → `0x00 0xFF`.
  Un único cero → `0x00 0x01`. Un byte cero nunca aparece "en crudo" salvo
  como este escape.
- Decodificación: al leer `0x00`, el byte siguiente N significa "N bytes
  cero". Todo lo demás se copia tal cual.
- La wiki anota en la contabilidad de longitud: "4 bytes para el número de
  mensaje (+1 si el paquete es zerocoded)". **D1 RESUELTO con el primer
  `RegionHandshake` real (2026-11-18, log del móvil de Rerir): el número de
  mensaje va DENTRO de la región zerocodificada.** La prueba: el
  `RegionHandshake` (Low 148 = `0xFFFF0094`) llegó como `FF FF 00 01 94` —
  el `0x00` del número se escapa como `00 01`, así que el número ocupa
  5 bytes y ese "+1" de la wiki es exactamente ese byte extra. Antes del
  arreglo, Ephora leía el número fuera de la región zerocodificada y el
  `RegionHandshake` se interpretaba como `Low(1)` y se descartaba en
  silencio: el handshake se quedaba en "Esperando datos de la región…"
  aunque el simulador ya estaba enviando datos. La implementación correcta
  (ver `net/SLPacket.kt`): número + cuerpo se zerocodifican/decodifican
  juntos, en ambos sentidos; los ACKs añadidos quedan fuera.
- Qué mensajes de M2 son zerocoded (plantilla): `RegionHandshake`,
  `RegionHandshakeReply`, `AgentThrottle`, `AgentUpdate`, `LogoutReply`.
  Qué mensajes NO lo son: `UseCircuitCode`, `CompleteAgentMovement`,
  `AgentMovementComplete`, `StartPingCheck`, `CompletePingCheck`.

### 2.6 ACKs añadidos al final del paquete

- Cuando el flag `0x10` (`LL_ACK_FLAG`) está activo, el paquete lleva al
  final una lista de números de secuencia confirmados: cada ACK son **4
  bytes big-endian** (el número de secuencia del paquete que se confirma) y
  **el último byte del paquete es el número de ACKs** que lleva.
  (wiki `Packet Layout`: "Bytes (6 + extra + (data length)) through acks").
- Los ACKs añadidos no forman parte del mensaje: el decodificador debe
  separarlos antes de decodificar el cuerpo, usando ese último byte.
- Además existe el mensaje dedicado `PacketAck` (Fixed `0xFFFFFFFB`,
  `NotTrusted`, `Unencoded`): bloque `Packets Variable { ID U32 }` — una
  lista de secuencias confirmadas; la cantidad se deduce de la longitud del
  paquete. libomv lo usa para enviar todos los ACKs pendientes de golpe
  (`SendAcks()`, ver 6.1).

### 2.7 Tamaño máximo

- `MAX_PACKET_SIZE = 1200` bytes (libomv `Settings.cs`). Los paquetes UDP
  de SL se mantienen por debajo de ese tamaño para no fragmentar. Si un
  paquete zerocodificado supera el MTU al codificarlo, libomv quita el flag
  de zerocode y lo envía sin comprimir (`SendPacketData` en `Simulator.cs`).
  Ephora debe hacer lo mismo: nunca enviar un datagrama mayor de 1200 bytes.
- La recepción debe tolerar datagramas de hasta al menos 1200 bytes; libomv
  usa un buffer de 8192 bytes para el zero-decode (`PacketReceived`).

---

## 3. Secuencia de handshake (orden completo)

### 3.1 Resumen

| Paso | Dirección | Mensaje | Nº | Fiable | Qué demuestra |
|---|---|---|---|---|---|
| 1 | Cliente → sim | `UseCircuitCode` | Low 3 | Sí | "Soy el agente del login, abre mi circuito" |
| 2 | Sim → cliente | (ACK de UseCircuitCode) | — | — | El sim aceptó el circuito |
| 3 | Cliente → sim | `CompleteAgentMovement` | Low 249 | Sí* | "El agente entra en la región; espéralo" |
| 4 | Sim → cliente | `RegionHandshake` | Low 148 | Sí | Datos de la región (nombre, flags, terreno) |
| 5 | Cliente → sim | `RegionHandshakeReply` | Low 149 | ¿? (D4) | "Región inicializada, empieza a enviarme objetos" |
| 6 | Sim → cliente | `AgentMovementComplete` | Low 250 | No | Posición inicial, look-at, handle de región |
| 7 | Cliente → sim | `AgentThrottle` | Low 81 | No | Ancho de banda por canal |
| 8 | Cliente → sim | `AgentUpdate` | High 4 | No | Cámara/posición, cada 500 ms |

\* La fiabilidad de `CompleteAgentMovement` en el visor oficial no se
verificó en esta sesión; Lumiya la envía fiable (ver D5).

**Orden real según libomv** (`Simulator.Connect()`, `Simulator.cs:1126`,
y `NetworkManager.Connect()`):
`UseCircuitCode(true)` (esperando ACK hasta `LOGIN_TIMEOUT`) →
`CompleteAgentMovement` (si `moveToSim`) → espera del evento de conexión
(`ConnectedEvent`, que se dispara al procesar `RegionHandshake`) hasta
`LOGIN_TIMEOUT` → `AgentThrottle` → `AgentUpdate`. Es decir,
`CompleteAgentMovement` se envía **antes** de recibir `RegionHandshake`.
Lumiya hace lo mismo en su forma: `SendUseCode()` fiable y, al recibir su
ACK, `SendCompleteAgentMovement()` (`SLAgentCircuit.kt:1830-1845`).

**Orden relativo no garantizado:** `RegionHandshake` (paso 4) y
`AgentMovementComplete` (paso 6) los envía el simulador y pueden llegar en
cualquier orden. El cliente **no debe asumir** que uno precede al otro: el
handshake se considera completo cuando se han recibido ambos y se ha enviado
`RegionHandshakeReply`. `AgentUpdate` no debe enviarse antes de
`AgentMovementComplete` (ver 4.3).

**Timeout global del handshake:** `LOGIN_TIMEOUT = 60 s` (libomv
`Settings.cs`). Si en 60 s no se completa, libomv registra "Giving up on
waiting for RegionHandshake" y elimina el simulador de la lista. Ephora
debe mostrar el error honesto (ver 8) y no reintentar sin límite.

### 3.2 Paso 1 — `UseCircuitCode` (cliente → sim)

- **Número:** Low 3. **Trusted:** No. **Zerocoded:** No.
- **Fiable:** sí. libomv `UseCircuitCode(bool waitForAck)` envía el paquete
  y, si `waitForAck`, espera el evento `GotUseCircuitCodeAck` hasta
  `LOGIN_TIMEOUT`; si expira, registra "Failed to get ACK for UseCircuitCode
  packet" (`Simulator.cs:1258`). Lumiya lo marca `isReliable = true`
  (`SLAgentCircuit.kt:1830-1837`).
- **Campos** (plantilla `message_template.msg`):

| Bloque | Campo | Tipo | Valor en M2 |
|---|---|---|---|
| `CircuitCode` (Single) | `Code` | U32 | `circuit_code` del login (M1) |
| `CircuitCode` (Single) | `SessionID` | LLUUID | `session_id` del login (M1) |
| `CircuitCode` (Single) | `ID` | LLUUID | `agent_id` del login (M1) |

- Es el **primer paquete** que se envía al `sim_ip:sim_port` del login.
  Sin este paso no hay circuito: el simulador descarta cualquier otro
  paquete de un agente sin circuito.
- El `circuit_code` es un entero de 32 bits entregado por el login; **no**
  lo genera el cliente ni se reutiliza el de otra sesión.

### 3.3 Paso 4 — `RegionHandshake` (sim → cliente)

- **Número:** Low 148. **Trusted:** Sí. **Zerocoded:** Sí.
  **Fiable:** sí (lo envía el simulador como fiable).
- Lo envía la región después de recibir `UseCircuitCode`. Contiene toda la
  información estática de la región. El cliente debe **guardarla** (nombre,
  flags, alturas de terreno, IDs de texturas de terreno) porque M3 la usará
  para construir el mundo.
- **Campos** (plantilla `message_template.msg`):

| Bloque | Campo | Tipo | Significado |
|---|---|---|---|
| `RegionInfo` (Single) | `RegionFlags` | U32 | Flags de la región (ver 3.3.1) |
| `RegionInfo` (Single) | `SimAccess` | U8 | Nivel de acceso (ver 3.3.2) |
| `RegionInfo` (Single) | `SimName` | Variable 1 | Nombre de la región (texto UTF-8) |
| `RegionInfo` (Single) | `SimOwner` | LLUUID | UUID del propietario de la región |
| `RegionInfo` (Single) | `IsEstateManager` | BOOL | Si el agente es estate manager |
| `RegionInfo` (Single) | `WaterHeight` | F32 | Altura del agua en metros |
| `RegionInfo` (Single) | `BillableFactor` | F32 | Factor de facturación |
| `RegionInfo` (Single) | `CacheID` | LLUUID | ID de caché de la región (único por región, estable ante renombres) |
| `RegionInfo` (Single) | `TerrainBase0`–`TerrainBase3` | LLUUID | Texturas base del terreno (4) |
| `RegionInfo` (Single) | `TerrainDetail0`–`TerrainDetail3` | LLUUID | Texturas de detalle del terreno (4) |
| `RegionInfo` (Single) | `TerrainStartHeight00/01/10/11` | F32 | Alturas iniciales del terreno (4 esquinas) |
| `RegionInfo` (Single) | `TerrainHeightRange00/01/10/11` | F32 | Rangos de altura del terreno (4 esquinas) |
| `RegionInfo2` (Single) | `RegionID` | LLUUID | UUID único de la región |
| `RegionInfo3` (Single) | `CPUClassID` | S32 | Clase de CPU del simulador |
| `RegionInfo3` (Single) | `CPURatio` | S32 | Ratio de CPU |
| `RegionInfo3` (Single) | `ColoName` | Variable 1 | Nombre del colo / datacenter |
| `RegionInfo3` (Single) | `ProductSKU` | Variable 1 | SKU del producto de la región |
| `RegionInfo3` (Single) | `ProductName` | Variable 1 | Nombre del producto |
| `RegionInfo4` (Variable) | `RegionFlagsExtended` | U64 | Flags extendidos (64 bits) |
| `RegionInfo4` (Variable) | `RegionProtocols` | U64 | Protocolos que soporta la región (ver 3.3.3) |

- `RegionInfo4` es un bloque `Variable`: puede venir con 0 repeticiones
  (regiones antiguas) o con 1. Si viene, libomv sobrescribe `Flags` con
  `RegionFlagsExtended` (`NetworkManager.cs:2163`, `RegionHandshakeHandler`).
- Qué hace libomv al recibirlo (`RegionHandshakeHandler`): copia todos los
  campos al objeto `Simulator` (`CacheID` → `Simulator.ID`, `SimName`,
  `SimOwner`, texturas y alturas de terreno, `WaterHeight`, `RegionFlags` →
  `Flags`, `BillableFactor`, `SimAccess` → `Access`, `RegionID`, `ColoName`,
  `CPUClass`/`CPURatio`, `ProductName`/`ProductSKU`); si hay `RegionInfo4`,
  guarda `Protocols` y sobrescribe `Flags`; luego envía
  `RegionHandshakeReply`; marca `simulator.connected = true`,
  `handshakeComplete = true` y dispara `ConnectedEvent`.

#### 3.3.1 `RegionFlags` (U32, libomv `Simulator.cs`, enum `RegionFlags: ulong`)

| Flag | Valor | Significado |
|---|---|---|
| `AllowDamage` | 1<<0 | Daños permitidos |
| `AllowLandmark` | 1<<1 | Landmarks permitidos |
| `AllowSetHome` | 1<<2 | Se puede fijar home |
| `ResetHomeOnTeleport` | 1<<3 | Reset de home al teleportar |
| `SunFixed` | 1<<4 | Sol fijo |
| `TaxFree` | 1<<5 | Libre de impuestos |
| `BlockTerraform` | 1<<6 | Terraformación bloqueada |
| `BlockLandResell` | 1<<7 | Reventa de tierra bloqueada |
| `Sandbox` | 1<<8 | Es un sandbox |
| `NullLayer` | 1<<9 | Capa nula |
| `SkipAgentAction` | 1<<10 | Saltar acciones de agente |
| `SkipUpdateInterestList` | 1<<11 | Saltar lista de interés |
| `SkipCollisions` | 1<<12 | Sin colisiones |
| `SkipScripts` | 1<<13 | Sin scripts |
| `SkipPhysics` | 1<<14 | Sin física |
| `ExternallyVisible` | 1<<15 | Visible externamente |
| `MainlandVisible` | 1<<16 | Visible en mainland |
| `PublicAllowed` | 1<<17 | Público permitido |
| `BlockDwell` | 1<<18 | Dwell bloqueado |
| `NoFly` | 1<<19 | Vuelo prohibido |
| `AllowDirectTeleport` | 1<<20 | Teleport directo permitido |
| `EstateSkipScripts` | 1<<21 | Estate salta scripts |
| `RestrictPushObject` | 1<<22 | Push de objetos restringido |
| `DenyAnonymous` | 1<<23 | Anónimos denegados |
| `DenyIdentified` | 1<<24 | Identificados denegados |
| `DenyTransacted` | 1<<25 | Con transacciones denegados |
| `AllowParcelChanges` | 1<<26 | Cambios de parcela permitidos |
| `AbuseEmailToEstateOwner` | 1<<27 | Abusos por email al estate owner |
| `AllowVoice` | 1<<28 | Voz permitida |
| `BlockParcelSearch` | 1<<29 | Búsqueda de parcelas bloqueada |
| `DenyAgeUnverified` | 1<<30 | Edad no verificada denegada |

#### 3.3.2 `SimAccess` (U8, libomv `Simulator.cs`, enum `SimAccess: byte`)

| Valor | Significado |
|---|---|
| 0 | `Unknown` |
| 7 | `Trial` |
| 13 | `PG` |
| 21 | `Mature` |
| 42 | `Adult` |
| 254 | `Down` (región caída) |
| 255 | `NonExistent` (región inexistente) |

- Si `SimAccess` es `Down` o `NonExistent`, el login no debería haber
  entregado esa región; si llega, es un error del servidor y debe mostrarse
  como tal (ver 8).

#### 3.3.3 `RegionProtocols` (U64, libomv `Simulator.cs`)

| Flag | Valor | Significado |
|---|---|---|
| `AgentAppearanceService` | 1<<0 | Servicio de apariencia por capability |
| `SelfAppearanceSupport` | 1<<2 (= 4) | La región soporta apariencia propia |

- Este es el valor que libomv devuelve en `RegionHandshakeReply.Flags`:
  `(uint)RegionProtocols.SelfAppearanceSupport` (= 4). Ver D6: Lumiya
  envía `Flags = 0`.

### 3.4 Paso 5 — `RegionHandshakeReply` (cliente → sim)

- **Número:** Low 149. **Trusted:** No. **Zerocoded:** Sí.
- Se envía **después de haber inicializado la región y cargado su caché**
  (plantilla: "sent after the viewer initialized the region and loaded its
  cache"). Después de este mensaje, el simulador empieza a enviar datos de
  objetos.
- **Campos** (plantilla):

| Bloque | Campo | Tipo | Valor en M2 |
|---|---|---|---|
| `AgentData` (Single) | `AgentID` | LLUUID | `agent_id` del login |
| `AgentData` (Single) | `SessionID` | LLUUID | `session_id` del login |
| `RegionInfo` (Single) | `Flags` | U32 | Ver nota abajo |

- **Valor de `Flags`:** libomv envía `SelfAppearanceSupport` (= 4)
  (`NetworkManager.cs:2163`). Lumiya envía `0`
  (`SLAgentCircuit.kt:1386-1395`, `HandleRegionHandshake`). **Divergencia
  sin resolver (ver D6).** Para M2 se recomienda el valor de libomv (4),
  porque es el del cliente de referencia moderno y anuncia soporte de
  apariencia propia; si la primera prueba muestra problemas, probar con 0
  y anotar el resultado.
- **Fiabilidad sin verificar (ver D4):** ni la plantilla ni el código de
  libomv consultado en esta sesión dejan claro si se envía fiable. Lumiya
  no marca `isReliable` en este envío. M2 lo enviará **fiable** por
  prudencia (es el mensaje que desbloquea el flujo de objetos; si se pierde,
  el sim no envía nada) y lo confirmará con la primera prueba.

### 3.5 Paso 3 — `CompleteAgentMovement` (cliente → sim)

- **Número:** Low 249. **Trusted:** No. **Zerocoded:** No.
- Significado (plantilla): "agent is coming into the region; the region
  should be expecting the agent" — el agente entra en la región; la región
  debe esperarlo.
- **Campos** (plantilla):

| Bloque | Campo | Tipo | Valor en M2 |
|---|---|---|---|
| `AgentData` (Single) | `AgentID` | LLUUID | `agent_id` del login |
| `AgentData` (Single) | `SessionID` | LLUUID | `session_id` del login |
| `AgentData` (Single) | `CircuitCode` | U32 | `circuit_code` del login |

- libomv: `AgentManager.CompleteAgentMovement(Simulator)` construye el
  paquete con esos tres campos y lo envía (`AgentManager.cs:5999-6015`).
  Lumiya lo marca `isReliable = true` (`SLAgentCircuit.kt:771-779`).

### 3.6 Paso 6 — `AgentMovementComplete` (sim → cliente)

- **Número:** Low 250. **Trusted:** No. **Zerocoded:** No.
- Es la confirmación del simulador de que el movimiento del agente está
  completo. A partir de aquí el agente "está" en la región.
- **Campos** (plantilla):

| Bloque | Campo | Tipo | Significado |
|---|---|---|---|
| `AgentData` (Single) | `AgentID` | LLUUID | `agent_id` |
| `AgentData` (Single) | `SessionID` | LLUUID | `session_id` |
| `Data` (Single) | `Position` | LLVector3 | Posición inicial del avatar (coordenadas locales de región, metros) |
| `Data` (Single) | `LookAt` | LLVector3 | Dirección de mirada inicial |
| `Data` (Single) | `RegionHandle` | U64 | Handle de la región (ver D8) |
| `Data` (Single) | `Timestamp` | U32 | Marca de tiempo del simulador |
| `SimData` (Single) | `ChannelVersion` | Variable 2 | Versión del canal del simulador (texto) |

- **La posición inicial del avatar viene de aquí**, no del login. M3
  usará `Position` y `LookAt` para colocar la cámara. En M2 basta con
  guardarlos y mostrar la región y la posición en la pantalla de carga.
- `ChannelVersion` es texto (p. ej. la versión del software del sim);
  mostrarlo en la pantalla de carga es honesto y útil para depurar.

### 3.7 Cierre del circuito (logout)

- `LogoutRequest` (Low 252, fiable): `AgentData { AgentID, SessionID }`.
  `LogoutReply` (Low 253, fiable, zerocoded): `AgentData { AgentID,
  SessionID }` + `InventoryData Variable { ItemID LLUUID }`.
- libomv `BeginLogout`/`Logout`: envía `LogoutRequest` y espera
  `LoggedOut` hasta `LOGOUT_TIMEOUT = 5 s`; si expira, cierra por timeout
  de red (`Shutdown(NetworkTimeout)`).
- `CloseCircuit` (Fixed `0xFFFFFFFD`, `NotTrusted`, `Unencoded`): sin
  campos. Se envía al desconectar. libomv `Disconnect(bool
  sendCloseCircuit)` lo envía si se pide, y siempre cierra el socket.
- `KickUser` (sim → cliente): el simulador expulsa al agente con un campo
  `Reason` (texto). libomv `KickUserHandler` lee `Reason` y llama a
  `Shutdown(ServerInitiated, message)`. **El motivo debe mostrarse tal cual
  llega**, nunca inventado. El número de mensaje no se verificó en esta
  sesión: buscarlo en `message_template.msg` al implementar (ver D14).


---

## 4. Inmediatamente después del handshake

### 4.1 Pings: `StartPingCheck` / `CompletePingCheck` (medición de lag)

- **Números:** `StartPingCheck` High 1, `CompletePingCheck` High 2.
  Ambos `NotTrusted`, `Unencoded`, **no fiables**.
- **Campos** (plantilla):

| Mensaje | Bloque | Campo | Tipo | Significado |
|---|---|---|---|---|
| `StartPingCheck` | `PingID` (Single) | `PingID` | U8 | Identificador del ping (byte, incremental) |
| `StartPingCheck` | `PingID` (Single) | `OldestUnacked` | U32 | El número de secuencia no confirmado más antiguo del emisor |
| `CompletePingCheck` | `PingID` (Single) | `PingID` | U8 | El mismo `PingID` recibido |

- **El cliente envía `StartPingCheck`** cada `PING_INTERVAL = 2200 ms`
  (libomv `Settings.cs`, `Simulator.SendPing()`, `Simulator.cs:1806`):
  `PingID = Stats.LastPingID++` (byte, con wrap), `OldestUnacked` = la
  clave más antigua de `NeedAck` (0 si no hay paquetes pendientes),
  `Reliable = false`. Guarda `LastPingSent = TickCount`.
- **El cliente responde a `StartPingCheck` del simulador** con
  `CompletePingCheck` con el mismo `PingID`, `Reliable = false`
  (libomv `StartPingCheckHandler`, `NetworkManager.cs:1918`). La nota del
  código dice que `OldestUnacked` solo lo usa el visor para podar su buffer
  de detección de duplicados.
- **Al recibir `CompletePingCheck`**, libomv calcula
  `LastLag = TickCount - LastPingSent` y suma `ReceivedPongs++`
  (`CompletePingCheckHandler`, `NetworkManager.cs:1948`). Ese `LastLag` es
  el lag que la app puede mostrar (milisegundos, ida y vuelta).
- El temporizador de ping solo corre si `SEND_PINGS = true` (libomv
  `Settings.cs`; por defecto, sí). En M2: enviar pings cada 2200 ms y
  responder a los del sim; mostrar el lag en la pantalla de carga / HUD de
  depuración. No enviar pings antes del handshake.

### 4.2 `AgentThrottle` (ancho de banda por canal)

- **Número:** Low 81. **Trusted:** No. **Zerocoded:** Sí.
  **Fiable:** no (libomv lo envía con `SendPacket` sin flag fiable).
- **Campos** (plantilla):

| Bloque | Campo | Tipo | Valor en M2 |
|---|---|---|---|
| `AgentData` (Single) | `AgentID` | LLUUID | `agent_id` del login |
| `AgentData` (Single) | `SessionID` | LLUUID | `session_id` del login |
| `AgentData` (Single) | `CircuitCode` | U32 | `circuit_code` del login |
| `Throttle` (Single) | `GenCounter` | U32 | `0` (libomv `AgentThrottle.Set()`) |
| `Throttle` (Single) | `Throttles` | Variable 1 | 28 bytes: 7 × `F32` little-endian (ver abajo) |

- **Los 7 canales, en este orden** (wiki `AgentThrottle`, libomv
  `AgentThrottle.ToBytes()`, `AgentThrottle.cs:424-450`):

| # | Canal | Qué regula |
|---|---|---|
| 1 | `Resend` | Reenvío de paquetes no confirmados |
| 2 | `Land` | `LayerData` de terreno |
| 3 | `Wind` | `LayerData` de viento |
| 4 | `Cloud` | `LayerData` de nubes |
| 5 | `Task` | Datos de objetos (prims) |
| 6 | `Texture` | Texturas |
| 7 | `Asset` | Assets descargados |

- **Valores por defecto de libomv** (`AgentThrottle.cs:285-293`):
  constructor con `Total = 1536000.0f` (1500 KBps, "default high total").
  El setter de `Total` reparte con multiplicadores y luego cada canal se
  limita a su máximo/mínimo. Valores efectivos resultantes:

| Canal | Fórmula (Total = 1.536.000) | Sin clamp | Con clamp (valor a enviar) | Límites del setter |
|---|---|---|---|---|
| `Resend` | 1.536.000 × 0,1 | 153.600 | **150.000** | 10.000 – 150.000 |
| `Land` | 1.536.000 × 0,52 / 3 | 266.240 | **170.000** | 0 – 170.000 |
| `Wind` | 1.536.000 × 0,05 | 76.800 | **34.000** | 0 – 34.000 |
| `Cloud` | 1.536.000 × 0,05 | 76.800 | **34.000** | 0 – 34.000 |
| `Task` | 1.536.000 × 0,704 / 3 | 360.448 | **360.448** | 4.000 – 446.000 |
| `Texture` | 1.536.000 × 0,704 / 3 | 360.448 | **360.448** | 4.000 – 446.000 |
| `Asset` | 1.536.000 × 0,484 / 3 | 247.808 | **220.000** | 10.000 – 220.000 |

- Suma efectiva: 1.328.896. Las unidades son **bits por segundo** según los
  comentarios de libomv ("Maximum bits per second for …"). La wiki
  `AgentThrottle` dice "maximum bytes per second" — **discrepancia
  documentada (ver D9)**; los valores numéricos son los de libomv y son los
  que se envían. El comentario de `Settings.cs` (`SEND_AGENT_THROTTLE`)
  advierte: sin throttle, la conexión queda limitada muy por debajo del
  mínimo y puede haber problemas de conexión; el throttle por defecto de
  libomv equivale al ajuste máximo de ancho de banda del cliente oficial.
- **Cuándo se envía:** libomv lo envía una vez tras el handshake
  (`Simulator.Connect()`: `AgentThrottle` si `SEND_AGENT_THROTTLE`, después
  de esperar `ConnectedEvent`). M2: enviarlo una vez tras
  `AgentMovementComplete` (o tras `RegionHandshakeReply`, si el orden real
  difiere — ver 3.1). Reenviarlo solo si cambian los valores (p. ej. ajuste
  de calidad en M3+); no hay reenvío periódico.
- `GenCounter` es un contador de generación: `0` en el primer envío;
  incrementarlo si se reenvía con valores nuevos (libomv siempre envía 0
  en `Set()`; el incremento entre reenvíos no se verificó, ver D10).

### 4.3 `AgentUpdate` (cámara y controles, cada 500 ms)

- **Número:** High 4. **Trusted:** No. **Zerocoded:** Sí.
  **Fiable:** no (libomv `SendUpdate(false, …)` por defecto).
- Es el mensaje que mantiene al simulador informado de la cámara y de los
  controles del avatar. El servidor es autoritativo sobre la posición del
  agente; el cliente es autoritativo sobre la posición y rotación de la
  cámara (wiki `How movement works`).
- **Campos** (plantilla):

| Bloque | Campo | Tipo | Significado |
|---|---|---|---|
| `AgentData` (Single) | `AgentID` | LLUUID | `agent_id` del login |
| `AgentData` (Single) | `SessionID` | LLUUID | `session_id` del login |
| `AgentData` (Single) | `BodyRotation` | LLQuaternion | Rotación del cuerpo del avatar |
| `AgentData` (Single) | `HeadRotation` | LLQuaternion | Rotación de la cabeza |
| `AgentData` (Single) | `State` | U8 | 0 = caminando, 1 = mouselook, 2 = escribiendo |
| `AgentData` (Single) | `CameraCenter` | LLVector3 | Posición de la cámara (coordenadas locales de región) |
| `AgentData` (Single) | `CameraAtAxis` | LLVector3 | Eje "adelante" de la cámara |
| `AgentData` (Single) | `CameraLeftAxis` | LLVector3 | Eje "izquierda" de la cámara |
| `AgentData` (Single) | `CameraUpAxis` | LLVector3 | Eje "arriba" de la cámara |
| `AgentData` (Single) | `Far` | F32 | Plano lejano (far clip) de la cámara |
| `AgentData` (Single) | `ControlFlags` | U32 | Bits de control de movimiento (ver 4.3.1) |
| `AgentData` (Single) | `Flags` | U8 | Flags del agente (`AgentFlags`) |

- **Frecuencia:** `DEFAULT_AGENT_UPDATE_INTERVAL = 500 ms` (libomv
  `Settings.cs`). `UpdateTimer_Elapsed` envía `SendUpdate(false,
  CurrentSim)` si `Connected` y `SEND_AGENT_UPDATES`
  (`AgentManagerMovement.cs:1498-1512`). El visor oficial lo envía hasta
  20 veces por segundo, una vez por frame, si algo cambia (wiki `How
  movement works`). Para M2, 500 ms es suficiente: el avatar está quieto y
  solo se mueve la cámara.
- **Regla crítica: NO enviar `AgentUpdate` antes de `AgentMovementComplete`.**
  libomv `SendUpdate(bool, Simulator)` lo impide explícitamente
  (`AgentManagerMovement.cs:1202-1208`): "Since version 1.40.4 of the Linden
  simulator, sending this update causes corruption of the agent position in
  the simulator". Si se envía antes, la posición del avatar se corrompe en
  el simulador.
- **Control de duplicados** (libomv `SendUpdate`, `AgentManagerMovement.cs:1224-1258`):
  si nada cambió (controles = 0, ejes de cámara, centro, estado, rotaciones
  y `Far` idénticos), se cuenta un duplicado; el paquete solo se envía
  mientras `duplicateCount < 10` (o si
  `DISABLE_AGENT_UPDATE_DUPLICATE_CHECK`). Es decir: con todo quieto, se
  envían 10 actualizaciones idénticas y luego se calla hasta que algo
  cambie. Ephora debe copiar este comportamiento: no saturar el circuito
  con `AgentUpdate` idénticos.
- **Valores iniciales en M2** (avatar quieto, cámara en órbita): `State = 0`,
  `ControlFlags = 0`, `BodyRotation`/`HeadRotation` = identidad (o la
  orientación inicial), `CameraCenter` = posición de la cámara de Filament
  (convertida a coordenadas locales de región), ejes de cámara desde la
  cámara de Filament, `Far` = far clip de la cámara (p. ej. 512 m — valor a
  confirmar en M3), `Flags = 0`.
- **Discrepancia en libomv (ver D14):** en `SendUpdate` el código asigna
  `CameraAtAxis = Camera.LeftAxis` y `CameraLeftAxis = Camera.AtAxis`
  (`AgentManagerMovement.cs:1212-1216` y `1298-1304`) — los ejes parecen
  intercambiados respecto a los nombres de campo. No copiar a ciegas:
  verificar contra el visor oficial o contra el comportamiento real antes
  de fijar la asignación.

#### 4.3.1 `ControlFlags` (U32, 32 bits, libomv `AgentManagerMovement.cs:82-220`)

| Bit | Nombre | Significado |
|---|---|---|
| 0 | `AGENT_CONTROL_AT_POS` | Avanzar (W / ↑) |
| 1 | `AGENT_CONTROL_AT_NEG` | Retroceder (S / ↓) |
| 2 | `AGENT_CONTROL_LEFT_POS` | Izquierda (Shift+A / ←) |
| 3 | `AGENT_CONTROL_LEFT_NEG` | Derecha (Shift+D / →) |
| 4 | `AGENT_CONTROL_UP_POS` | Subir / saltar (E) |
| 5 | `AGENT_CONTROL_UP_NEG` | Bajar / agacharse (C) |
| 6–7 | `AGENT_CONTROL_PITCH_POS/NEG` | Pitch (sin uso) |
| 8–9 | `AGENT_CONTROL_YAW_POS/NEG` | Yaw (sin uso) |
| 10 | `AGENT_CONTROL_FAST_AT` | Correr adelante (con teclado) |
| 11 | `AGENT_CONTROL_FAST_LEFT` | Correr lateral (con teclado) |
| 12 | `AGENT_CONTROL_FAST_UP` | Correr vertical (con teclado) |
| 13 | `AGENT_CONTROL_FLY` | Volar |
| 14 | `AGENT_CONTROL_STOP` | Parar |
| 15 | `AGENT_CONTROL_FINISH_ANIM` | Terminar animación |
| 16 | `AGENT_CONTROL_STAND_UP` | Levantarse |
| 17 | `AGENT_CONTROL_SIT_ON_GROUND` | Sentarse en el suelo |
| 18 | `AGENT_CONTROL_MOUSELOOK` | Mouselook activo |
| 19–24 | `AGENT_CONTROL_NUDGE_*` | Nudges (legado, pulsaciones cortas) |
| 25 | `AGENT_CONTROL_TURN_LEFT` | Girar a la izquierda |
| 26 | `AGENT_CONTROL_TURN_RIGHT` | Girar a la derecha |
| 27 | `AGENT_CONTROL_AWAY` | Ausente (away) |
| 28 | `AGENT_CONTROL_LBUTTON_DOWN` | Botón izquierdo pulsado |
| 29 | `AGENT_CONTROL_LBUTTON_UP` | Botón izquierdo soltado |
| 30 | `AGENT_CONTROL_ML_LBUTTON_DOWN` | Botón izquierdo en mouselook (pulsado) |
| 31 | `AGENT_CONTROL_ML_LBUTTON_UP` | Botón izquierdo en mouselook (soltado) |

- En M2 todos los controles son `0` (avatar quieto). Los bits de
  movimiento se usarán en M3+ con los controles táctiles.

### 4.4 `AgentFOV` (campo de visión, opcional en M2)

- **Número:** ver `message_template.msg` (no se verificó en esta sesión;
  buscarlo al implementar — es un mensaje Low). **Campos:**
  `AgentData { AgentID, SessionID, CircuitCode }` +
  `FOVBlock { GenCounter U32, VerticalAngle F32 }`.
- libomv `SetFOVVerticalAngle(float angle)` (`AgentManagerMovement.cs:1476-1494`):
  `GenCounter = 0`, `VerticalAngle = angle`, envío sin flag fiable
  explícito. Lumiya lo envía **fiable** con `GenCounter = 0` y
  `VerticalAngle = 3.0543263f` (`SLAgentCircuit.kt:760-769`).
- M2 puede enviarlo una vez tras el handshake con el valor de Lumiya
  (3,0543263 rad ≈ 175°); el valor del visor oficial (FOV vertical real de
  la cámara) no se verificó en esta sesión (ver D12). Si no se envía, el
  simulador usa su valor por defecto; no es bloqueante.

---

## 5. Capabilities: la petición al seed capability

- Tras el handshake, el cliente pide las **capabilities** (URLs HTTPS de
  servicios del simulador) con un POST al `seed_capability` del login.
  El formato (spec M1, sección de capabilities; wiki `Current Sim
  Capabilities`): **POST HTTPS con un array LLSD de nombres** de
  capabilities; la respuesta es un **mapa LLSD nombre → URL**.
  Content-Type: `application/llsd+xml`. Timeout: `CAPS_TIMEOUT = 60 s`
  (libomv `Settings.cs`).
- El visor oficial pide ~130 capabilities
  (`LLViewerRegionImpl::buildCapabilityNames`, `llviewerregion.cpp:6006-6252`).
  **M2 no pide todas**: pide el mínimo necesario para verificar el circuito
  y preparar M3. La lista mínima propuesta:

| Capability | Para qué en M2 |
|---|---|
| `EventQueueGet` | Cola de eventos del simulador (long-poll). Es el canal por el que llegan gran parte de los eventos; imprescindible desde M2 para no quedarse sordo |
| `SimulatorFeatures` | Qué soporta el simulador (se pide tras recibir la concesión) |
| `FetchInventory2` | Inventario (preparar M5; en M2 basta con verificar que la URL llega) |
| `FetchInventoryDescendents2` | Contenido de carpetas de inventario |
| `FetchLib2` | Librería de inventario |
| `FetchLibDescendents2` | Contenido de la librería |
| `GetDisplayNames` | Nombres visibles de avatares |
| `MapLayer` | Mapa de la región |
| `AgentPreferences` | Preferencias del agente |
| `ChatSessionRequest` | Sesiones de chat (preparar M5) |
| `UpdateAvatarAppearance` | Apariencia del avatar (preparar M4) |
| `ProductInfoRequest` | Información del producto de la región |
| `ServerReleaseNotes` | Notas de la versión del simulador |
| `ViewerAsset` | Subida/descarga de assets del visor |

- **La lista mínima es una propuesta de Ephora, no un hecho verificado
  (ver D13):** el simulador podría exigir alguna capability más para que el
  `EventQueueGet` o el inventario funcionen. Si la primera prueba muestra
  que algo no llega, ampliar la lista con la del visor oficial
  (`llviewerregion.cpp:6006-6252`, incluyendo lo que añade
  `AISAPI::getCapNames()`).
- **Reintentos:** el visor oficial reintenta hasta
  `MAX_CAP_REQUEST_ATTEMPTS = 30` (`llviewerregion.cpp:188`), pero con una
  advertencia clave en el propio código (líneas 182-184): "The server only
  keeps our pending agent info for 60 seconds. We want to allow for seed
  cap retry, but its not useful after that 60 seconds." Es decir: reintentar
  más de 60 s tras el login es inútil. M2: **máximo 3 intentos** antes de
  rendirse con error honesto (el valor antiguo
  `MAX_SEED_CAP_ATTEMPTS_BEFORE_LOGIN = 3` del visor), con espera entre
  intentos, y nunca más allá de 60 s desde el login.
- **Si la URL del seed está vacía:** error no recuperable (el visor
  oficial: "Failed to get seed capabilities, and can not determine url!",
  `requestBaseCapabilitiesCoro`). Mostrarlo tal cual.
- **Si una capability pedida no viene en la respuesta:** no es un error
  fatal; se guarda solo lo que llegó y se sigue. Lo que falte se anotará
  como no disponible (nunca se inventa una URL).
- El `EventQueueGet` es un long-poll: la petición se mantiene abierta y el
  servidor responde con eventos. Su implementación completa (bucle de
  eventos, ACK de eventos) es M3; en M2 basta con obtener la URL y, si es
  posible, abrir una primera petición para verificar que responde.
- `SimulatorFeatures` se pide **después** de recibir la concesión base:
  el visor oficial lanza `requestSimulatorFeatureCoro` al recibir esa
  capability (`llviewerregion.cpp:6360-6368`). M2 puede hacer lo mismo o
  dejarlo para M3; no es bloqueante.

---

## 6. Mantenimiento del circuito

### 6.1 Paquetes fiables: ACKs, retransmisión y deduplicación

- **Enviar fiable:** poner el flag `0x40` (`LL_RELIABLE_FLAG`). El
  receptor debe confirmar el número de secuencia con un ACK (añadido al
  final de otro paquete con flag `0x10`, o con un mensaje `PacketAck`
  dedicado). El emisor guarda el paquete en `NeedAck` hasta que llega su
  ACK.
- **Enviar ACKs:** libomv acumula los ACKs pendientes en `PendingAcks` y
  los envía cuando hay `>= MAX_PENDING_ACKS = 10`, o en cada tick del
  temporizador (`SendAcks()`, `Simulator.cs:2236`): construye un
  `PacketAckPacket` con todos los pendientes, `Reliable = false`. Los ACKs
  también se añaden al final de paquetes normales (ver 2.6).
- **Retransmisión** (`ResendUnacked()`, `Simulator.cs:2296`): cada tick se
  revisan los paquetes en `NeedAck` con antigüedad mayor de
  `RESEND_TIMEOUT = 4000 ms`. Si `ResendCount < MAX_RESEND_COUNT = 3`, se
  pone el flag `0x20` (`MSG_RESENT`) y se reenvía; si no, se descarta de
  `NeedAck` con el registro "Dropping packet #N after M failed attempts".
  **Nunca hay reintentos infinitos: 3 reintentos y se abandona.**
- **Recepción de ACKs** (`PacketReceived`, `Simulator.cs:1962`): al
  procesar ACKs añadidos o un `PacketAck`, se eliminan esos números de
  secuencia de `NeedAck`. El ACK de `UseCircuitCode` dispara el evento
  `GotUseCircuitCodeAck` que desbloquea el handshake.
- **Deduplicación:** los paquetes fiables entrantes se guardan en
  `PacketArchive` (tamaño `PACKET_ARCHIVE_SIZE = 1000`) y si llega un
  duplicado se descarta **sin disparar el callback** (no se procesa dos
  veces). Ephora debe hacer lo mismo: un `RegionHandshake` duplicado no
  debe reiniciar el handshake.
- **Temporizador de red:** `AckTimer` cada `NETWORK_TICK_INTERVAL = 500 ms`
  ejecuta `SendAcks()` + `ResendUnacked()` (`AckTimer_Elapsed`,
  `Simulator.cs:2396`).

### 6.2 Pings y detección de desconexión

- **Pings:** cada `PING_INTERVAL = 2200 ms` se envía `StartPingCheck`
  (ver 4.1). El lag medido (`LastLag`) sirve para mostrar el estado de la
  conexión.
- **Desconexión por silencio:** `SIMULATOR_TIMEOUT = 30 s` (libomv
  `Settings.cs`): milisegundos sin recibir **ningún** paquete antes de
  asumir que la conexión se perdió. libomv `DisconnectTimer` dispara
  `Shutdown(NetworkTimeout)`; `PacketReceived` pone
  `DisconnectCandidate = false` con cada paquete recibido (cualquier
  paquete, no solo pings, mantiene vivo el circuito).
- **Cierre ordenado:** `Disconnect(sendCloseCircuit)` detiene los
  temporizadores (ACK, estadísticas, ping), desconecta las caps, envía
  `CloseCircuit` si se pidió y cierra el socket (`Simulator.cs:1352`).
- **Cambio de red en el móvil:** si Android cambia de WiFi a datos (o
  viceversa), el socket UDP queda roto: el `sim_ip` ya no es alcanzable
  desde la nueva interfaz y el circuito muere por timeout. No hay
  "reconexión UDP" que lo salve: hay que **volver a hacer login** (M1) para
  obtener un `circuit_code` nuevo. La app debe detectar el cambio de red
  y mostrarlo con honestidad ("Conexión perdida por cambio de red; vuelve a
  iniciar sesión"), nunca reintentar el handshake con el código viejo en
  bucle.

### 6.3 Constantes de red (libomv `Settings.cs`, todas verificadas)

| Constante | Valor | Uso |
|---|---|---|
| `LOGIN_TIMEOUT` | 60.000 ms | Espera del handshake y del ACK de `UseCircuitCode` |
| `LOGOUT_TIMEOUT` | 5.000 ms | Espera de `LogoutReply` |
| `CAPS_TIMEOUT` | 60.000 ms | Timeout de peticiones de capabilities |
| `RESEND_TIMEOUT` | 4.000 ms | Antigüedad para retransmitir un paquete fiable sin ACK |
| `SIMULATOR_TIMEOUT` | 30.000 ms | Silencio máximo antes de asumir desconexión |
| `TELEPORT_TIMEOUT` | 40.000 ms | Teleport (fuera de M2) |
| `PING_INTERVAL` | 2.200 ms | Intervalo de `StartPingCheck` |
| `DEFAULT_AGENT_UPDATE_INTERVAL` | 500 ms | Intervalo de `AgentUpdate` |
| `NETWORK_TICK_INTERVAL` | 500 ms | Tick de `SendAcks` + `ResendUnacked` |
| `MAX_PACKET_SIZE` | 1.200 bytes | Tamaño máximo de datagrama |
| `MAX_SEQUENCE` | `0xFFFFFF` | Máximo del contador de secuencia |
| `PACKET_ARCHIVE_SIZE` | 1.000 | Tamaño del archivo de deduplicación |
| `MAX_PENDING_ACKS` | 10 | ACKs pendientes antes de enviarlos juntos |
| `MAX_RESEND_COUNT` | 3 | Reintentos máximos por paquete fiable |
| `SEND_AGENT_UPDATES` | `true` | Enviar `AgentUpdate` |
| `SEND_AGENT_THROTTLE` | `true` | Enviar `AgentThrottle` |
| `SEND_PINGS` | `true` | Enviar pings |
| `ENABLE_CAPS` | `true` | Usar capabilities |
| `THROTTLE_OUTGOING_PACKETS` | `true` | Cola de salida con throttle |

### 6.4 Qué hace el cliente si el simulador no responde

1. **Sin ACK de `UseCircuitCode` en 60 s:** error "El simulador no
   respondió al abrir el circuito". No reenviar `UseCircuitCode`
   indefinidamente: libomv lo envía una vez y espera; si expira, abandona.
   M2 puede reintentarlo **una vez** (2 intentos en total) y luego rendirse
   con el error.
2. **Sin `RegionHandshake` en 60 s:** error "La región no envió sus datos
   (handshake incompleto)". Rendirse; no inventar datos de región.
3. **Sin `AgentMovementComplete` tras `CompleteAgentMovement`:** esperar
   hasta el timeout global de 60 s desde el inicio del handshake; si no
   llega, error honesto. No enviar `AgentUpdate` nunca en este estado.
4. **Silencio de 30 s con circuito abierto:** desconexión por timeout
   (`SIMULATOR_TIMEOUT`); cerrar el circuito, mostrar "Conexión con la
   región perdida" y ofrecer volver al login. No reconectar solo.
5. **`KickUser` del simulador:** mostrar el `Reason` tal cual y cerrar.
6. **Errores de socket local** (puerto bloqueado, sin red, DNS de
   `sim_ip`): son errores del dispositivo/red, no del simulador; el mensaje
   debe decirlo ("Sin conexión de red" / "No se pudo abrir el socket UDP").

---

## 7. Qué datos del login M1 se usan en cada paquete

Datos disponibles en `LoginSession` (M1, `auth/SLLogin.kt:33-47`):
`sessionId`, `secureSessionId`, `agentId`, `firstName`, `lastName`,
`circuitCode`, `simIp`, `simPort`, `regionX`, `regionY`,
`seedCapability`, `agentAccess`, `inventoryRoot?`, `motd?`.

| Dato de M1 | Dónde se usa en M2 | Dónde NO se usa |
|---|---|---|
| `circuitCode` (Int) | `UseCircuitCode.Code`, `CompleteAgentMovement.CircuitCode`, `AgentThrottle.CircuitCode`, `AgentFOV.CircuitCode` | En ningún otro paquete |
| `sessionId` | `UseCircuitCode.SessionID`, `CompleteAgentMovement.SessionID`, `RegionHandshakeReply.SessionID`, `AgentThrottle.SessionID`, `AgentUpdate.SessionID`, `AgentFOV.SessionID`, `LogoutRequest.SessionID` | — |
| `agentId` | `UseCircuitCode.ID`, `CompleteAgentMovement.AgentID`, `RegionHandshakeReply.AgentID`, `AgentThrottle.AgentID`, `AgentUpdate.AgentID`, `AgentFOV.AgentID`, `LogoutRequest.AgentID` | — |
| `simIp` + `simPort` | Destino UDP de **todos** los paquetes del circuito | — |
| `regionX` + `regionY` | Para calcular el handle de región y contrastarlo con `AgentMovementComplete.RegionHandle` (fórmula sin verificar, ver D8) | No se envían en ningún paquete |
| `seedCapability` | POST de capabilities (sección 5), por HTTPS | Nunca por UDP |
| `secureSessionId` | **No se usa en ningún paquete UDP de M2.** No aparece en la plantilla de mensajes ni en el código de libomv consultado para el handshake. Su uso posterior es por HTTPS (capabilities que lo requieran) y queda fuera de M2 | Nunca por UDP |
| `firstName` / `lastName` | Solo para mostrar "Conectado como …" en la UI | No se envían por UDP |
| `agentAccess` | Solo para mostrar el nivel de acceso; no se envía | No se envía por UDP |
| `inventoryRoot?` | Se usará con `FetchInventory2` (sección 5) | No se envía por UDP |
| `motd?` | Solo para mostrar el mensaje del día si lo hay | No se envía por UDP |

- **La contraseña no viaja por UDP jamás.** Ya se usó (hasheada) en el
  login y no vuelve a aparecer.
- **`mac` / `id0` (identidad del dispositivo, M1):** no se usan en el
  circuito UDP. Solo existen en la petición de login.

---

## 8. Errores en esta fase y cómo mostrarlos

Regla general (la misma de M1): **mostrar el motivo real cuando se conoce,
decir "motivo desconocido" cuando no, y nunca fingir conexión.** La pantalla
de carga debe mostrar en qué paso está ("Abriendo circuito…", "Esperando
datos de la región…", "Entrando en la región…", "Pidiendo capabilities…")
y, si falla, en qué paso falló.

| Fallo | Cómo se detecta | Qué debe mostrar la app |
|---|---|---|
| Sin red / socket UDP no se abre | Excepción al crear el `DatagramSocket` o al resolver `simIp` | "Sin conexión de red. Revisa tu conexión e inténtalo de nuevo." |
| `UseCircuitCode` sin ACK (60 s) | Expira `LOGIN_TIMEOUT` sin `GotUseCircuitCodeAck` | "El simulador no respondió al abrir el circuito. Puede estar caído o saturado." |
| Sin `RegionHandshake` (60 s) | Expira `LOGIN_TIMEOUT` sin handshake | "La región no envió sus datos. Inténtalo de nuevo más tarde." |
| Sin `AgentMovementComplete` (60 s) | Expira el timeout global sin el mensaje | "La región no confirmó la entrada del avatar." |
| Región caída / inexistente | `SimAccess` = 254 (`Down`) o 255 (`NonExistent`) en `RegionHandshake` | "La región está caída o no existe (código de acceso: …)." |
| Expulsado por el simulador | `KickUser` con `Reason` | El `Reason` tal cual: "El simulador te expulsó: <motivo>". Si no hay motivo, "El simulador cerró la conexión sin dar un motivo." |
| Silencio 30 s con circuito abierto | `SIMULATOR_TIMEOUT` | "Conexión con la región perdida (sin respuesta durante 30 s)." |
| Seed capability vacía | `seedCapability` vacío o nulo | "El login no entregó capabilities para esta región. Vuelve a iniciar sesión." |
| Capabilities sin respuesta (60 s) | Expira `CAPS_TIMEOUT` | "La región no respondió a la petición de capabilities." |
| Capabilities incompletas | Faltan capabilities de la lista mínima | "Conectado, pero faltan servicios de la región: <nombres>. Algunas funciones no estarán disponibles." (seguir, no bloquear) |
| Cambio de red (WiFi ↔ datos) | El sistema avisa o el circuito muere por timeout | "Conexión perdida por cambio de red. Vuelve a iniciar sesión." |

- **Nunca mostrar "Conectado" antes de `AgentMovementComplete`.**
  "Conectado" significa: handshake completo + capabilities mínimas
  recibidas. Antes de eso, la pantalla dice en qué paso está.
- **Nunca mostrar datos inventados:** si no llegó `RegionHandshake`, no
  hay nombre de región que mostrar; si no llegó `AgentMovementComplete`,
  no hay posición que mostrar.

---

## 9. Seguridad y red

- **UDP sin cifrado.** El protocolo SL no cifra el circuito: todo lo que
  viaja por UDP (incluidos `session_id`, `agent_id`, `circuit_code`) puede
  leerlo quien vea el tráfico de red. Es una limitación del protocolo,
  no un bug de Ephora, y no se puede "arreglar" desde el cliente. Las
  capabilities, en cambio, van por HTTPS.
- **Nunca en logs:** `session_id`, `secure_session_id`, `agent_id`,
  `circuit_code`. Tampoco en `Logcat` en compilaciones de depuración que
  Rerir pueda compartir, ni en pantallas de error, ni en informes de
  crash. Lo que sí se puede registrar: números de mensaje, tamaños de
  paquete, tiempos, nombres de región, `SimAccess`, códigos de error
  genéricos y el lag. Regla práctica: si un valor identifica la sesión,
  no se registra.
- **La contraseña no existe en M2.** No se guarda, no se reenvía, no se
  registra. Si algún código la pide para el circuito, es un error.
- **Puertos y NAT:** el cliente abre un socket UDP local (puerto
  efímero) y envía a `sim_port` (típicamente 13000–13050, el valor exacto
  lo da el login). No hay que abrir puertos entrantes en el móvil: el NAT
  mantiene el mapeo mientras haya tráfico en ambos sentidos, y los pings
  cada 2200 ms lo mantienen vivo. Si el móvil está detrás de un NAT
  estricto o un firewall que bloquea UDP saliente, el handshake fallará
  con timeout (ver 8).
- **Android:** permisos `INTERNET` y `ACCESS_NETWORK_STATE` (ya
  declarados en M0). El socket vive fuera del hilo de UI, en un hilo
  dedicado o en corrutinas con `Dispatchers.IO`. En segundo plano, Doze
  puede pausar la red: si la app pasa a segundo plano con el circuito
  abierto, el circuito morirá por timeout al volver; la app debe
  detectarlo y ofrecer re-login, no asumir que sigue vivo.
- **No usar `TrustAll` ni desactivar la verificación TLS** en las
  peticiones HTTPS de capabilities (la misma regla de M1).
- **Validación de origen:** libomv `PacketReceived` rechaza paquetes de
  una IP inesperada (distinta de la del simulador) con un aviso. Ephora
  debe hacer lo mismo: solo aceptar datagramas de `sim_ip:sim_port`.


---

## 10. Discrepancias y puntos sin verificar

Todo lo de esta sección **no** es un hecho: es una duda anotada para
resolverla con la primera prueba real o con una segunda fuente. Nada de
aquí debe copiarse al código como si estuviera confirmado.

| Nº | Punto | Estado |
|---|---|---|
| D1 | El "+1 si el paquete es zerocoded" de la wiki `Packet Layout`, y si el número de mensaje está dentro o fuera de la región zerocodificada | **RESUELTO 2026-11-18 con el primer `RegionHandshake` real (log del móvil): el número va DENTRO de la región zerocodificada.** `0xFFFF0094` llegó como `FF FF 00 01 94`. Número + cuerpo se codifican/decodifican juntos; ACKs añadidos fuera. Ver sección 2.5 y `net/SLPacket.kt` |
| D2 | Orden de bytes de `LLUUID` (16 bytes) e `IPPORT` (2 bytes) en el cuerpo | Sin verificar en esta sesión. Se asume orden de red (big-endian) para ambos, como en libomv, pero no se abrió el código que lo confirma |
| D3 | Empaquetado de `LLQuaternion`: 12 bytes (X, Y, Z) con W reconstruida | Sin verificar en esta sesión. Es el empaquetado clásico del protocolo SL, pero no se confirmó contra la plantilla ni contra código |
| D4 | Si `RegionHandshakeReply` se envía fiable | Sin verificar. Ni la plantilla ni el código de libomv consultado lo dejan claro; Lumiya no marca `isReliable`. M2 lo envía fiable por prudencia |
| D5 | Si `CompleteAgentMovement` es fiable en el visor oficial | Sin verificar. Lumiya lo envía fiable (`SLAgentCircuit.kt:771-779`); libomv lo envía con `SendPacket` sin flag explícito visible en el código consultado |
| D6 | Valor de `RegionHandshakeReply.Flags`: libomv envía `SelfAppearanceSupport` (= 4), Lumiya envía `0` | Divergencia real entre dos fuentes. M2 usa 4 (libomv); si la primera prueba muestra problemas, probar con 0 y anotar el resultado |
| D7 | Valor inicial del contador de secuencia (se recomienda empezar en 1) | Sin verificar en esta sesión. Lo importante es que sea estrictamente creciente y que el wrap sea en `0xFFFFFF` |
| D8 | Fórmula del handle de región a partir de `region_x`/`region_y` del login (se asume `(x << 32) \| y`) | Sin verificar en esta sesión. La primera prueba lo confirma: comparar con `AgentMovementComplete.RegionHandle` |
| D9 | Unidades de los valores del throttle: libomv dice "bits por segundo" en sus comentarios; la wiki `AgentThrottle` dice "bytes por segundo" | Discrepancia entre fuentes. Los valores numéricos son los de libomv y son los que se envían; la unidad no cambia los bytes enviados |
| D10 | `AgentThrottle.GenCounter` en reenvíos con valores nuevos (libomv siempre envía 0 en `Set()`) | Sin verificar. M2 envía 0 siempre; si se reenvía con valores nuevos, incrementar y anotar |
| D11 | Endianness de los campos del cuerpo (`U16`/`U32`/`U64`/`F64` little-endian) | Inferencia desde libomv (`BitConverter`, little-endian en la plataforma) y desde la wiki (`Variable 2` little-endian). No se verificó campo a campo: la clase base `Packet.cs` de libomv no se localizó (dos rutas devolvieron 404). Confirmado para `F32` por `AgentThrottle.cs` |
| D12 | Valor de `AgentFOV.VerticalAngle` del visor oficial (Lumiya envía 3,0543263f) | Sin verificar contra el visor oficial. El envío de `AgentFOV` es opcional en M2 |
| D13 | La lista mínima de capabilities de la sección 5 es una propuesta de Ephora | No probada. El simulador podría exigir alguna capability más para `EventQueueGet` o inventario. La lista completa del visor oficial está en `llviewerregion.cpp:6006-6252` |
| D14 | En `AgentMovement.SendUpdate` de libomv, `CameraAtAxis` recibe `Camera.LeftAxis` y `CameraLeftAxis` recibe `Camera.AtAxis` (parecen intercambiados) | Posible bug de libomv o convención interna de su clase `Camera`. No copiar a ciegas: verificar contra el visor oficial o el comportamiento real |
| D15 | Números de mensaje no verificados en esta sesión: `KickUser`, `AgentFOV`, `AgentPause`/`AgentResume` | Buscarlos en `message_template.msg` (rama `develop`) al implementar. No usar números de memoria. `AgentPause`/`AgentResume` existen en Lumiya (`DoAgentPause`/`DoAgentResume` con `lastPauseId`) pero quedan fuera de M2 |
| D16 | `MAX_SEED_CAP_ATTEMPTS_BEFORE_LOGIN = 3` viene de una versión de 2012 del visor (blame de Alchemy Viewer) | El visor actual usa `MAX_CAP_REQUEST_ATTEMPTS = 30` con la nota de los 60 s. M2 usa 3 intentos como límite prudente; no es un valor del visor actual |
| D17 | libomv es inconsistente en el guarda de `AgentUpdate`: `SendUpdate` exige `AgentMovementComplete`, pero `SendManualUpdate` exige `HandshakeComplete` | Anotado tal cual se encontró (`AgentManagerMovement.cs:1206` y `:1376`). M2 usa la regla estricta: no enviar antes de `AgentMovementComplete` |
| D18 | Si el simulador exige recibir `AgentThrottle` antes de empezar a enviar datos de objetos | No verificado en esta sesión. libomv lo envía tras el handshake y antes de `AgentUpdate`; M2 hace lo mismo |

---

## 11. Checklist de implementación para M2

### 11.1 Archivos Kotlin a crear

Ruta base: `app/src/main/kotlin/com/ephora/viewer/` (los archivos de M1
están en `auth/`; los de M2 van en `net/`). **Sin dependencias nuevas:**
`java.net.DatagramSocket`, corrutinas de Kotlin (ya disponibles) y el
parser XML de M1.

| Archivo | Qué hace |
|---|---|
| `net/SLPacket.kt` | Serialización y parseo de paquetes: cabecera de 6 bytes, flags, secuencia big-endian, número de mensaje 1/2/4 bytes, bloques (`Single`/`Variable`/`Variable 2`), tipos de campo de la sección 2.4, zerocode (codificar/decodificar), ACKs añadidos (separar el último byte). Incluye `PacketAck` (Fixed `0xFFFFFFFB`) |
| `net/MessageTypes.kt` | Constantes: números y frecuencias de todos los mensajes de M2 (`UseCircuitCode` Low 3, `RegionHandshake` Low 148, `RegionHandshakeReply` Low 149, `AgentThrottle` Low 81, `CompleteAgentMovement` Low 249, `AgentMovementComplete` Low 250, `LogoutRequest` Low 252, `LogoutReply` Low 253, `StartPingCheck` High 1, `CompletePingCheck` High 2, `AgentUpdate` High 4, `PacketAck` Fixed `0xFFFFFFFB`, `CloseCircuit` Fixed `0xFFFFFFFD`). Solo los verificados en la plantilla |
| `net/UdpCircuit.kt` | El circuito: `DatagramSocket`, hilo de recepción y cola de envío, contador de secuencia, `NeedAck` + retransmisión (4000 ms, máx. 3), `PendingAcks` + `PacketAck` (cada 500 ms o 10 ACKs), deduplicación (`PacketArchive`, 1000), pings cada 2200 ms + cálculo de lag, `SIMULATOR_TIMEOUT` 30 s, `CloseCircuit`, validación de origen (solo `sim_ip:sim_port`). Expone una máquina de estados (ver 11.2) y nunca registra `session_id`/`agent_id`/`circuit_code` |
| `net/RegionHandshake.kt` | Parseo de `RegionHandshake` a `RegionInfo` (todos los campos de 3.3, con `RegionInfo4` opcional), construcción de `RegionHandshakeReply` (Flags = 4, ver D6), y parseo de `AgentMovementComplete` (posición, look-at, handle, `ChannelVersion`). Guarda `RegionFlags`/`SimAccess`/`RegionProtocols` como enums |
| `net/AgentThrottle.kt` | Valores por defecto de la sección 4.2 (Resend 150.000, Land 170.000, Wind 34.000, Cloud 34.000, Task 360.448, Texture 360.448, Asset 220.000), construcción del array de 28 bytes (7 × `F32` little-endian) y del paquete `AgentThrottle` (`GenCounter = 0`) |
| `net/AgentUpdateSender.kt` | `AgentUpdate` cada 500 ms con el control de duplicados (10 idénticos y callar), guarda de no enviar antes de `AgentMovementComplete`, `ControlFlags` (todos a 0 en M2) y conversión de la cámara de Filament a `CameraCenter`/ejes/`Far` (ver D14 antes de fijar la asignación de ejes) |
| `net/CapsClient.kt` | POST HTTPS del array LLSD de capabilities al `seed_capability` (reutilizar el cliente HTTP de M1: 60 s, sin `TrustAll`), parseo del mapa LLSD nombre → URL, reintentos máx. 3 y nunca más de 60 s desde el login. Guarda las URLs en un mapa en memoria |
| `net/LlsdXml.kt` | Construcción y parseo de LLSD-XML (array de strings y mapa string → string). Separado de `XmlRpc.kt` de M1 porque LLSD-XML no es XML-RPC aunque comparta el parser XML |
| `net/ConnectionState.kt` | Máquina de estados del handshake (sealed class): `Idle`, `OpeningCircuit`, `WaitingHandshake`, `WaitingMovementComplete`, `RequestingCaps`, `Connected`, `Failed(mensaje)`, `Disconnected(motivo)`. Cada transición con su timeout. La UI solo observa este estado |
| `MainActivity.kt` (modificar) | Tras el login exitoso de M1, pasar a la pantalla de carga honesta: muestra el estado actual de `ConnectionState` ("Abriendo circuito…", "Esperando datos de la región…", "Entrando en la región…", "Pidiendo capabilities…") y luego "Conectado como <nombre> en <región>". El cubo de Filament sigue girando detrás hasta M3. Sin pantalla de "lobby" (decisión del usuario) |

### 11.2 Orden de implementación

1. `SLPacket.kt` + `MessageTypes.kt` con tests unitarios locales de
   codificación/decodificación (un `RegionHandshake` de ejemplo construido
   a mano debe decodificar `SimName` legible y `WaterHeight` sensato).
2. `UdpCircuit.kt` + `RegionHandshake.kt`: solo handshake, sin
   capabilities. La app llega a `AgentMovementComplete` y muestra región,
   posición y `ChannelVersion`.
3. `AgentThrottle.kt` + `AgentUpdateSender.kt` + pings.
4. `CapsClient.kt` + `LlsdXml.kt`: pedir la lista mínima y mostrar qué
   llegó.
5. `ConnectionState.kt` + integración en `MainActivity` (pantalla de carga
   honesta + errores de la sección 8).
6. Cierre: `LogoutRequest`/`LogoutReply` + `CloseCircuit` al salir o al
   cerrar sesión.

### 11.3 Orden de pruebas en el móvil (Rerir)

1. **Handshake sin mundo:** instalar el APK, login correcto, y comprobar
   que la pantalla de carga avanza hasta "Conectado como josskky resident
   en <región>" con la posición inicial visible. El cubo sigue girando
   (esperado: M2 no trae mundo). **Criterio de éxito:** `AgentMovementComplete`
   recibido y `RegionHandshake` decodificado con nombre de región legible.
2. **Validar la decodificación:** si el nombre de la región sale con
   caracteres raros o la posición es absurda (p. ej. miles de millones),
   es un error de endianness o de zerocode (D1, D11): anotar los valores
   exactos y volver a la spec.
3. **Capabilities:** comprobar que llegan `EventQueueGet`,
   `SimulatorFeatures`, `FetchInventory2` y `GetDisplayNames`. Si falta
   alguna, anotar cuál (ver D13).
4. **Estabilidad:** dejar la app 1–2 minutos conectada: el lag debe
   mostrarse (pings cada 2200 ms) y la app no debe cerrarse ni desconectar.
   Probar salir y volver a entrar (logout limpio).
5. **Errores:** modo avión justo después del login (debe salir el error de
   timeout de handshake, no un crash); cambio de WiFi a datos con el
   circuito abierto (debe detectar la pérdida y pedir re-login).
6. **Qué traer de cada prueba:** el paso exacto en el que se quedó la
   pantalla de carga, el mensaje de error literal, y (si hay crash) el log
   con los datos de sesión ya eliminados. Nunca traer `session_id`,
   `agent_id` ni `circuit_code` en los reportes.

### 11.4 Qué NO entra en M2

- Terreno, prims, texturas, objetos (M3).
- Avatar, esqueleto, apariencia (M4). `UpdateAvatarAppearance` solo se
  pide como capability; no se usa todavía.
- Chat, inventario, mapa (M5+). `EventQueueGet` solo se verifica; su bucle
  de eventos es M3.
- Teleport (`CrossedRegion`, `EnableSimulator`), voz, grupos, dinero.
- `AgentPause`/`AgentResume`: existen en Lumiya para pausar el agente al
  ir a segundo plano; en M2 la app simplemente se desconecta por timeout
  si pasa a segundo plano (ver 9).

---

## Apéndice A — Tabla rápida de mensajes de M2

| Mensaje | Nº | Frec. | Dir. | Fiable | Zerocoded | Campos clave |
|---|---|---|---|---|---|---|
| `PacketAck` | `0xFFFFFFFB` | Fixed | ambas | No | No | `Packets Variable { ID U32 }` |
| `OpenCircuit` | `0xFFFFFFFC` | Fixed | sim→cli | — | No | `CircuitInfo { IP, Port }` |
| `CloseCircuit` | `0xFFFFFFFD` | Fixed | ambas | — | No | (sin campos) |
| `UseCircuitCode` | 3 | Low | cli→sim | Sí | No | `Code U32, SessionID, ID` |
| `StartPingCheck` | 1 | High | ambas | No | No | `PingID U8, OldestUnacked U32` |
| `CompletePingCheck` | 2 | High | ambas | No | No | `PingID U8` |
| `AgentUpdate` | 4 | High | cli→sim | No | Sí | `AgentID, SessionID, BodyRotation, HeadRotation, State, CameraCenter, CameraAtAxis, CameraLeftAxis, CameraUpAxis, Far, ControlFlags, Flags` |
| `AgentThrottle` | 81 | Low | cli→sim | No | Sí | `AgentID, SessionID, CircuitCode, GenCounter, Throttles (28 B)` |
| `RegionHandshake` | 148 | Low | sim→cli | Sí | Sí | `RegionInfo, RegionInfo2, RegionInfo3, RegionInfo4?` |
| `RegionHandshakeReply` | 149 | Low | cli→sim | ¿? (D4) | Sí | `AgentID, SessionID, Flags U32` |
| `SimulatorViewerTimeMessage` | 150 | Low | sim→cli | — | — | Fuera de M2 |
| `EnableSimulator` | 151 | Low | sim→cli | — | — | Fuera de M2 |
| `DisableSimulator` | 152 | Low | sim→cli | — | — | Fuera de M2 |
| `CompleteAgentMovement` | 249 | Low | cli→sim | Sí* (D5) | No | `AgentID, SessionID, CircuitCode` |
| `AgentMovementComplete` | 250 | Low | sim→cli | No | No | `AgentID, SessionID, Position, LookAt, RegionHandle, Timestamp, ChannelVersion` |
| `LogoutRequest` | 252 | Low | cli→sim | Sí | No | `AgentID, SessionID` |
| `LogoutReply` | 253 | Low | sim→cli | Sí | Sí | `AgentID, SessionID, InventoryData Variable { ItemID }` |
| `CoarseLocationUpdate` | 6 | Medium | sim→cli | — | — | Fuera de M2 |
| `CrossedRegion` | 7 | Medium | sim→cli | — | — | Teleport; fuera de M2 |

Fuentes de los números: plantilla `message_template.msg` (rama `develop`
del visor oficial) y copia local en Linkpoint (coinciden en los mensajes
de M2). "Fiable" indica cómo lo envía el emisor de referencia (libomv /
Lumiya); la plantilla no codifica la fiabilidad.
