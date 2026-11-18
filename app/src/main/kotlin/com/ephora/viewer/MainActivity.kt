package com.ephora.viewer

import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ephora.viewer.auth.DeviceIdentity
import com.ephora.viewer.auth.Grid
import com.ephora.viewer.auth.LoginCredentials
import com.ephora.viewer.auth.LoginResult
import com.ephora.viewer.auth.LoginSession
import com.ephora.viewer.auth.SLLogin
import com.ephora.viewer.net.ConnectionState
import com.ephora.viewer.net.UdpCircuit
import com.ephora.viewer.net.uiText
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
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hito M0 + M1 + M2 de Ephora Viewer.
 *
 * Lo que esta pantalla hace de verdad:
 * - Crea un motor Filament real (Engine, Renderer, Scene, View, Camera),
 *   carga el cubo de prueba `assets/cube.glb` con gltfio, lo ilumina con
 *   una luz direccional y lo renderiza en el SurfaceView. Arrastrar el dedo
 *   gira la cámara alrededor del cubo; si no se toca, gira solo despacio.
 * - Hito 1: login REAL con Second Life (XML-RPC contra login.cgi, ver
 *   `auth/SLLogin.kt` y `docs/login-protocol-spec.md`). La sesión queda
 *   solo en memoria.
 * - Hito 2: circuito UDP real con el simulador (ver `net/UdpCircuit.kt`
 *   y `docs/udp-circuit-spec.md`): handshake, región, capabilities.
 *   Tras el login no hay lobby: una pantalla de carga honesta muestra
 *   el estado del circuito y se convierte en "Conectado" al terminar.
 *
 * Lo que NO hace (y no finge hacer): todavía no hay mundo ni avatar;
 * el cubo sigue siendo la escena de prueba hasta M3. Ningún error se
 * disfraza de éxito: lo que dice el servidor es lo que se muestra.
 */
class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "EphoraViewer"
        private const val CAMERA_RADIUS = 3.0
        private const val AUTO_ROTATE_SPEED = 0.002
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

    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private lateinit var camera: Camera
    private var cameraEntity: Int = 0
    private var lightEntity: Int = 0

    private var swapChain: SwapChain? = null
    private var assetLoader: AssetLoader? = null
    private var cubeAsset: FilamentAsset? = null

    // Órbita de la cámara alrededor del cubo (origen de la escena).
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
                        // honesta del circuito UDP; el cubo de Filament sigue
                        // girando detrás hasta M3.
                        val circuit = UdpCircuit(result.session.simIp, result.session.simPort, result.session, loginTimeMs)
                        udpCircuit = circuit
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
            loadTestCube()
            sceneStatusText.text = getString(R.string.scene_status_ready)
        } catch (e: Exception) {
            // Filament sigue activo; solo el modelo de prueba no se cargó.
            Log.e(TAG, "No se pudo cargar la escena de prueba (cube.glb)", e)
            sceneStatusText.text = getString(R.string.scene_status_error)
        }
    }

    /** Carga el cubo de prueba (glTF 2.0 binario, material PBR morado Ephora). */
    private fun loadTestCube() {
        val bytes = assets.open("cube.glb").use { it.readBytes() }
        val buffer: ByteBuffer = ByteBuffer.wrap(bytes)
        val loader = AssetLoader(engine, UbershaderProvider(engine), EntityManager.get())
        assetLoader = loader
        val asset = loader.createAsset(buffer)
        if (asset == null) {
            Log.e(TAG, "createAsset devolvió null para cube.glb")
            sceneStatusText.text = getString(R.string.scene_status_error)
            return
        }
        cubeAsset = asset
        ResourceLoader(engine).loadResources(asset)
        asset.releaseSourceData()
        scene.addEntities(asset.entities)
    }

    private fun updateCamera() {
        val eyeX = CAMERA_RADIUS * cos(elevation) * sin(azimuth)
        val eyeY = CAMERA_RADIUS * sin(elevation)
        val eyeZ = CAMERA_RADIUS * cos(elevation) * cos(azimuth)
        camera.lookAt(eyeX, eyeY, eyeZ, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        swapChain = engine.createSwapChain(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        view.viewport = Viewport(0, 0, width, height)
        val aspect = width.toDouble() / height.toDouble()
        camera.setProjection(45.0, aspect, 0.1, 100.0, Camera.Fov.VERTICAL)
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
        // Cerrar el circuito UDP al salir (CloseCircuit + logout si procede).
        udpCircuit?.stop(true)
        udpCircuit = null
        choreographer.removeFrameCallback(frameCallback)
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = null
        cubeAsset?.let { assetLoader?.destroyAsset(it) }
        cubeAsset = null
        assetLoader = null
        EntityManager.get().destroy(lightEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyRenderer(renderer)
        engine.destroy()
    }
}
