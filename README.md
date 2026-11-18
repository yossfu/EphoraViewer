# Ephora Viewer

Visor de Second Life para Android, escrito desde cero en Kotlin con el motor
de render [Filament](https://github.com/google/filament) de Google.

> **Estado: hito 0 (M0).** El proyecto compila, instala y abre con una escena
> de prueba de Filament y el diseño de la pantalla de login. **No hay login
> real, no hay conexión a Second Life, no hay mundo ni avatar todavía.** Nada
> de lo que la app muestra promete funcionalidad que no esté implementada:
> cada hito se verifica antes de darse por hecho.

## Hitos

- **M0 — Esqueleto (en curso).** Proyecto Gradle (AGP 8.1.4, Kotlin 1.9.22,
  SDK 34, minSdk 24), paquete `com.ephora.viewer`. MainActivity con una
  escena Filament real: un cubo PBR con luz direccional y órbita táctil, y
  el formulario de login en diseño con el botón desactivado. Compila en
  GitHub Actions y produce un APK debug.
- **M1 — Login real.** Autenticación LLSD contra
  `login.agni.lindenlab.com`: sesión, agente y región. Sin simular nada.
- **M2 — Circuito UDP.** Conexión a la región (LLUDP), handshake y
  actualizaciones de objetos.
- **M3 — Mundo.** Terreno, prims y texturas renderizados con Filament.
- **M4 — Avatar.** Malla, esqueleto y texturas horneadas (Bakes on Mesh).
- **M5 — Chat e inventario.** Comunicación y gestión de objetos del avatar.

## Compilar

El proyecto no trae Gradle wrapper: el workflow de GitHub Actions instala
Gradle 8.5 con `gradle/actions/setup-gradle@v6` y compila con
`gradle assembleDebug`. En local, con Gradle 8.5 instalado:

```sh
gradle assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/`.

## Licencia

MIT — ver [LICENSE](LICENSE).

Second Life es una marca registrada de Linden Research, Inc. (Linden Lab).
Ephora Viewer no está afiliado a Linden Lab ni patrocinado por ella.
