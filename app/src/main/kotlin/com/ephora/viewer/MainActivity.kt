package com.ephora.viewer

import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hito M0 de Ephora Viewer.
 *
 * Lo que esta pantalla hace de verdad:
 * - Crea un motor Filament real (Engine, Renderer, Scene, View, Camera),
 *   carga el cubo de prueba `assets/cube.glb` con gltfio, lo ilumina con
 *   una luz direccional y lo renderiza en el SurfaceView. Arrastrar el dedo
 *   gira la cámara alrededor del cubo; si no se toca, gira solo despacio.
 * - Muestra el formulario de login en diseño (nombre, apellido, contraseña,
 *   red), con el botón DESACTIVADO.
 *
 * Lo que NO hace (y no finge hacer): no hay login real, no hay conexión a
 * Second Life, no hay mundo ni avatar. El login real con LLSD es el hito 1.
 */
class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "EphoraViewer"
        private const val CAMERA_RADIUS = 3.0
        private const val AUTO_ROTATE_SPEED = 0.002
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var sceneStatusText: TextView

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

        // El botón de login está desactivado: el login real llega en el hito 1.
        // No hay ningún intento de autenticación en M0.
        val loginButton: Button = findViewById(R.id.button_login)
        loginButton.isEnabled = false

        // Obligatorio según la documentación oficial de Filament:
        // "You must always first initialize Filament by calling Filament.init()".
        // Sin esta llamada, la librería nativa no se carga y Engine.create()
        // falla con UnsatisfiedLinkError (crash real reportado por Rerir, M0).
        Filament.init()

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
