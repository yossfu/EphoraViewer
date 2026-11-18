package com.ephora.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View as AndroidView
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ephora.viewer.auth.DeviceIdentity
import com.ephora.viewer.auth.Grid
import com.ephora.viewer.auth.LoginCredentials
import com.ephora.viewer.auth.LoginResult
import com.ephora.viewer.auth.LoginSession
import com.ephora.viewer.auth.SLLogin
import com.ephora.viewer.net.CameraVectors
import com.ephora.viewer.net.ConnectionState
import com.ephora.viewer.net.UdpCircuit
import com.ephora.viewer.net.uiText
import com.ephora.viewer.util.AppLog
import com.ephora.viewer.world.Materials
import com.ephora.viewer.world.PrimGeometry
import com.ephora.viewer.world.TerrainMesh
import com.ephora.viewer.world.WorldScene
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.Gltfio
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hito M0 + M1 + M2 + M3 de Ephora Viewer.
 *
 * Lo que esta pantalla hace de verdad:
 * - Crea un motor Filament real (Engine, Renderer, Scene, View, Camera)
 *   con una luz direccional y lo renderiza en el SurfaceView. Arrastrar
 *   el dedo gira la cámara alrededor del avatar; si no se toca, gira
 *   solo despacio.
 * - Hito 1: login REAL con Second Life (XML-RPC contra login.cgi, ver
 *   `auth/SLLogin.kt` y `docs/login-protocol-spec.md`). La sesión queda
 *   solo en memoria.
 * - Hito 2: circuito UDP real con el simulador (ver `net/UdpCircuit.kt`
 *   y `docs/udp-circuit-spec.md`): handshake, región, capabilities.
 *   Tras el login no hay lobby: una pantalla de carga honesta muestra
 *   el estado del circuito y se convierte en "Conectado" al terminar.
 * - M2.1: panel de registro retráctil (botón flotante "Log"): muestra en
 *   vivo lo que pasa por dentro (login, paquetes UDP, handshake, caps)
 *   con botones para copiarlo, compartirlo y limpiarlo. El registro
 *   nunca contiene session_id, agent_id ni circuit_code.
 * - Hito 3: escena 3D real del mundo (ver `world/` y
 *   `docs/world-scene-spec.md`): terreno desde LayerData, prims desde
 *   ObjectUpdate, agua a la altura del RegionHandshake y cámara en
 *   tercera persona sobre el avatar. Sin texturas ni avatares todavía
 *   (M4/M5): los prims son geometría procedural con colores de
 *   depuración, y los sculpt/mesh se ven como cajas magenta.
 *
 * Lo que NO hace (y no finge hacer): todavía no hay avatar ni texturas.
 * Ningún error se disfraza de éxito: lo que dice el servidor es lo que
 * se muestra.
 */
class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "EphoraViewer"
        private const val CAMERA_RADIUS = 3.0
        private const val AUTO_ROTATE_SPEED = 0.002
        // DIAGNÓSTICO TEMPORAL (regresión run #15, 2026-11-18): carga de
        // materiales desactivada para confirmar si el cierre instantáneo
        // viene de Material.Builder().build() en onCreate. Con esto la
        // app arranca como en M2 (sin mundo 3D). QUITAR (poner en false)
        // en cuanto se confirme o descarte; NUNCA subir un build final
        // con este flag en true.
        private const val DISABLE_WORLD_DIAGNOSTIC = true
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var sceneStatusText: TextView

    // Sesión de login real (hito 1). Solo vive en memoria: nunca se
    // guarda en disco. El circuito UDP del hito 2 la usará.
    private var currentSession: LoginSession? = null

    // Circuito UDP con el simulador (hito 2) y momento del login, para
    // el límite de 60 s de las capabilities (la info pendiente del agente
    // solo vive 60 s en el servidor tras el login).
    private var udpCircuit: UdpCircuit? = null
    private var loginTimeMs: Long = 0L

    // Panel de registro retráctil (M2.1): refresco en vivo del texto.
    private val logHandler = Handler(Looper.getMainLooper())
    private var logRunnable: Runnable? = null

    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private lateinit var camera: Camera
    private var cameraEntity: Int = 0
    private var lightEntity: Int = 0

    private var swapChain: SwapChain? = null

    // Escena 3D del mundo (hito M3). Se crea en initFilament, tras
    // Engine/Scene/View/Camera. Si los materiales compilados (.filamat)
    // no están, queda en null y la app sigue como en M2 (sin mundo).
    private var worldScene: WorldScene? = null
    private var terrainMesh: TerrainMesh? = null
    private var lastSceneStatus: String? = null

    // Órbita de la cámara alrededor del avatar (objetivo = avatar).
    private var azimuth = 0.6
    private var elevation = 0.35
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragging = false

    private val choreographer = Choreographer.getInstance()
    // lateinit var: el callback se referencia a sí mismo al reprogramarse,
    // y Kotlin no permite usar un val dentro de su propio inicializador.
    private lateinit var frameCallback: Choreographer.FrameCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.filament_surface)
        sceneStatusText = findViewById(R.id.text_scene_status)

        // Hito 1: login REAL con Second Life (nada simulado).
        // En M0 este botón estaba desactivado; ahora conecta de verdad
        // contra login.cgi (ver auth/SLLogin.kt).
        val firstNameInput: EditText = findViewById(R.id.edit_first_name)
        val lastNameInput: EditText = findViewById(R.id.edit_last_name)
        val passwordInput: EditText = findViewById(R.id.edit_password)
        val gridSpinner: Spinner = findViewById(R.id.spinner_grid)
        val loginStatusText: TextView = findViewById(R.id.text_login_status)
        val loginButton: Button = findViewById(R.id.button_login)
        loginButton.isEnabled = true
        loginButton.setOnClickListener {
            val first = firstNameInput.text.toString().trim()
            // Apellido vacío = "Resident" (cuentas nuevas sin apellido).
            val last = lastNameInput.text.toString().trim().ifEmpty { "Resident" }
            // La contraseña no se recorta: puede llevar espacios.
            val password = passwordInput.text.toString()
            if (first.isEmpty() || password.isEmpty()) {
                loginStatusText.text = getString(R.string.login_empty_fields)
                return@setOnClickListener
            }
            val grid = if (gridSpinner.selectedItemPosition == 1) Grid.ADITI else Grid.AGNI
            loginButton.isEnabled = false
            loginStatusText.text = getString(R.string.login_connecting)
            val (mac, id0) = DeviceIdentity.getOrCreate(this)
            loginTimeMs = System.currentTimeMillis()
            val credentials = LoginCredentials(first, last, password, start = "last", grid = grid)
            SLLogin.login(credentials, mac, id0) { result ->
                // El callback ya llega en el hilo principal.
                when (result) {
                    is LoginResult.Success -> {
                        currentSession = result.session // solo en memoria
                        // Hito 2: sin lobby. Del login a la pantalla de carga
                        // honesta del circuito UDP; la escena 3D (hito 3) se
                        // va llenando con terreno y prims reales.
                        val circuit = UdpCircuit(result.session.simIp, result.session.simPort, result.session, loginTimeMs)
                        udpCircuit = circuit
                        circuit.worldSceneRef = worldScene
                        circuit.onStateChanged = { st ->
                            loginStatusText.text = st.uiText() + when (st) {
                                is ConnectionState.Connected -> " (${st.position.first}, ${st.position.second}, ${st.position.third})"
                                else -> ""
                            }
                            // Si el handshake falla, el botón vuelve a activarse
                            // para reintentar el login manualmente.
                            if (st is ConnectionState.Failed) loginButton.isEnabled = true
                        }
                        circuit.start()
                    }
                    is LoginResult.Failure -> {
                        // Mensaje del servidor tal cual; nunca se inventa el motivo.
                        loginStatusText.text = result.message
                        loginButton.isEnabled = true
                    }
                }
            }
        }

        // Panel de registro retráctil (M2.1): botón flotante "Log" que abre
        // un panel con el registro en vivo (AppLog), con Copiar, Compartir,
        // Limpiar y Cerrar. El registro nunca contiene session_id, agent_id
        // ni circuit_code.
        val logPanel: AndroidView = findViewById(R.id.log_panel)
        val logText: TextView = findViewById(R.id.text_log)
        val logScroll: ScrollView = findViewById(R.id.scroll_log)
        val showLogButton: Button = findViewById(R.id.button_show_log)
        val refresh = object : Runnable {
            override fun run() {
                if (logPanel.visibility == AndroidView.VISIBLE) {
                    logText.text = AppLog.snapshot().ifEmpty { getString(R.string.log_empty) }
                    logScroll.post { logScroll.fullScroll(AndroidView.FOCUS_DOWN) }
                    logHandler.postDelayed(this, 500)
                }
            }
        }
        logRunnable = refresh
        showLogButton.setOnClickListener {
            logPanel.visibility = AndroidView.VISIBLE
            logHandler.post(refresh)
        }
        findViewById<Button>(R.id.button_close_log).setOnClickListener {
            logPanel.visibility = AndroidView.GONE
            logHandler.removeCallbacks(refresh)
        }
        findViewById<Button>(R.id.button_clear_log).setOnClickListener {
            AppLog.clear()
        }
        findViewById<Button>(R.id.button_copy_log).setOnClickListener {
            val clip = ClipData.newPlainText("Ephora log", AppLog.snapshot())
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.button_share_log).setOnClickListener {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Ephora Viewer — registro")
                putExtra(Intent.EXTRA_TEXT, AppLog.snapshot())
            }
            startActivity(Intent.createChooser(share, getString(R.string.log_share)))
        }

        // Obligatorio según la documentación oficial de Filament:
        // "You must always first initialize Filament by calling Filament.init()".
        // Sin esta llamada, la librería nativa no se carga y Engine.create()
        // falla con UnsatisfiedLinkError (crash real reportado por Rerir, M0).
        Filament.init()

        // Igual de obligatorio para gltfio: Filament.init() solo carga
        // libfilament-jni.so. La librería nativa de gltfio (libgltfio-jni.so)
        // se carga con Gltfio.init(); sin ella, UbershaderProvider falla con
        // UnsatisfiedLinkError (segundo crash real, run #5). Diagnóstico de la
        // IA de Perchance, verificado por Agni contra el AAR gltfio-android
        // 1.56.0 de Maven Central (Gltfio.init() carga "gltfio-jni", y el .so
        // viene para los 4 ABI). Debe ir después de Filament.init() y antes
        // de cualquier uso de AssetLoader/UbershaderProvider/ResourceLoader.
        Gltfio.init()

        initFilament()

        frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
            choreographer.postFrameCallback(frameCallback)
            if (!dragging) {
                azimuth += AUTO_ROTATE_SPEED
            }
            updateCamera()
            worldScene?.syncNow()
            updateSceneStatus()
            val chain = swapChain
            if (chain != null) {
                if (renderer.beginFrame(chain, frameTimeNanos)) {
                    renderer.render(view)
                    renderer.endFrame()
                }
            }
        }

        surfaceView.holder.addCallback(this)
        surfaceView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    dragging = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    azimuth -= (event.x - lastTouchX) * 0.01
                    elevation = (elevation + (event.y - lastTouchY) * 0.01).coerceIn(-1.2, 1.2)
                    lastTouchX = event.x
                    lastTouchY = event.y
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun initFilament() {
        engine = Engine.create()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        cameraEntity = EntityManager.get().create()
        camera = engine.createCamera(cameraEntity)

        view.scene = scene
        view.camera = camera
        view.antiAliasing = View.AntiAliasing.FXAA

        // Fondo oscuro sólido; la luz direccional es la que ilumina el cubo.
        scene.skybox = Skybox.Builder().color(0.04f, 0.04f, 0.07f, 1.0f).build(engine)

        lightEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 1.0f, 1.0f)
            .intensity(100000.0f)
            .direction(0.5f, -1.0f, -0.5f)
            .castShadows(false)
            .build(engine, lightEntity)
        scene.addEntity(lightEntity)

        try {
            createWorldScene()
            sceneStatusText.text = getString(R.string.world_waiting_login)
        } catch (e: Exception) {
            // Filament sigue activo; solo la escena 3D no se creó
            // (normalmente, porque el workflow no generó los .filamat).
            Log.e(TAG, "No se pudo crear la escena 3D", e)
            AppLog.add("SCENE", "Escena 3D no creada: ${e.message}")
            sceneStatusText.text = getString(R.string.scene_status_error)
        }
    }

    // Hito 3: crea la escena del mundo (terreno, prims, agua, cámara).
    // Los .filamat los compila el workflow de Actions con matc 1.56.0;
    // si no están, devuelve null y la app sigue como en M2.
    private fun createWorldScene() {
        if (DISABLE_WORLD_DIAGNOSTIC) {
            AppLog.add("SCENE", "Diagnóstico: carga de materiales desactivada (regresión run #15)")
            sceneStatusText.text = getString(R.string.scene_status_error)
            return
        }
        val mats = Materials.load(engine, assets)
        if (mats == null) {
            AppLog.add("SCENE", "Materiales .filamat no encontrados (sin mundo 3D)")
            sceneStatusText.text = getString(R.string.scene_status_error)
            return
        }
        val (terrainMat, waterMat) = mats
        val mesh = TerrainMesh(engine, scene, Materials.tint(engine, terrainMat, 1f, 1f, 1f), Materials.waterInstance(waterMat))
        terrainMesh = mesh
        val primGeo = PrimGeometry(engine)
        worldScene = WorldScene(engine, scene, mesh, primGeo, Materials.primInstances(engine, terrainMat))
        // Altura del agua y posición del avatar, si ya llegaron.
        udpCircuit?.regionInfo?.let { mesh.setWater(it.waterHeight) }
        udpCircuit?.movementInfo?.let { worldScene?.setAvatarSl(it.position) }
    }

    // Cámara en tercera persona: orbita alrededor del avatar (hito M3).
    // El objetivo es el avatar + 1.2 m (pecho); el ojo orbita con la
    // misma fórmula de M0. Además empuja los vectores reales de la
    // cámara al circuito UDP (AgentUpdate) cada frame.
    private fun updateCamera() {
        val target = worldScene?.avatarTarget() ?: floatArrayOf(0f, 0f, 0f)
        val eyeX = (target[0] + CAMERA_RADIUS * cos(elevation) * sin(azimuth)).toDouble()
        val eyeY = (target[1] + CAMERA_RADIUS * sin(elevation)).toDouble()
        val eyeZ = (target[2] + CAMERA_RADIUS * cos(elevation) * cos(azimuth)).toDouble()
        camera.lookAt(eyeX, eyeY, eyeZ, target[0].toDouble(), target[1].toDouble(), target[2].toDouble(), 0.0, 1.0, 0.0)
        pushCameraVectors(eyeX, eyeY, eyeZ, target)
    }

    // Vectores de cámara en marco SL para AgentUpdate: centro = objetivo,
    // at = dirección de la mirada, up = (0,0,1), left = up × at.
    // Conversión Filament -> SL: (x, y, z)_F -> (x, -z, y)_SL.
    private fun pushCameraVectors(eyeX: Double, eyeY: Double, eyeZ: Double, target: FloatArray) {
        val ws = worldScene ?: return
        val cx = target[0]
        val cy = -target[2]
        val cz = target[1]
        var axF = target[0] - eyeX
        var ayF = target[1] - eyeY
        var azF = target[2] - eyeZ
        val len = sqrt(axF * axF + ayF * ayF + azF * azF)
        if (len < 1e-6) return
        axF /= len
        ayF /= len
        azF /= len
        val ax = axF.toFloat()
        val ay = (-azF).toFloat()
        val az = ayF.toFloat()
        val ux = 0f
        val uy = 0f
        val uz = 1f
        val lx = uy * az - uz * ay
        val ly = uz * ax - ux * az
        val lz = ux * ay - uy * ax
        val v = CameraVectors(cx, cy, cz, ax, ay, az, lx, ly, lz, ux, uy, uz, 1024f)
        ws.setExternal(v)
        udpCircuit?.updateCamera(v)
    }

    // Texto honesto del estado de la escena (hito M3): antes del login
    // espera; luego, terreno y objetos según van llegando.
    private fun updateSceneStatus() {
        val ws = worldScene
        val text = when {
            ws == null -> getString(R.string.scene_status_error)
            udpCircuit == null -> getString(R.string.world_waiting_login)
            ws.terrainPatches == 0 -> getString(R.string.loading_terrain)
            ws.objectCount == 0 -> getString(R.string.loading_objects)
            else -> getString(R.string.status_objects_patches, ws.objectCount, ws.terrainPatches)
        }
        if (text != lastSceneStatus) {
            lastSceneStatus = text
            sceneStatusText.text = text
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        swapChain = engine.createSwapChain(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        view.viewport = Viewport(0, 0, width, height)
        val aspect = width.toDouble() / height.toDouble()
        // Far 1500: la región mide 256 m de diagonal (~362 m); con far
        // 100 los prims lejanos se recortarían. Near se mantiene en 0.1.
        camera.setProjection(45.0, aspect, 0.1, 1500.0, Camera.Fov.VERTICAL)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = null
    }

    override fun onResume() {
        super.onResume()
        choreographer.postFrameCallback(frameCallback)
    }

    override fun onPause() {
        super.onPause()
        choreographer.removeFrameCallback(frameCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        logRunnable?.let { logHandler.removeCallbacks(it) }
        // Cerrar el circuito UDP al salir (CloseCircuit + logout si procede).
        udpCircuit?.stop(true)
        udpCircuit = null
        choreographer.removeFrameCallback(frameCallback)
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = null
        // La escena 3D se destruye ANTES que el motor Filament.
        worldScene?.destroy()
        worldScene = null
        terrainMesh = null
        EntityManager.get().destroy(lightEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyRenderer(renderer)
        engine.destroy()
    }
}
