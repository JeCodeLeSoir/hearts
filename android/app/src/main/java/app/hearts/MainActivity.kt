package app.hearts

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.*
import android.widget.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : Activity() {
    private lateinit var canvas: HeartCanvas
    private lateinit var status: TextView

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                HeartService.ACTION_HEART -> if (::canvas.isInitialized) canvas.burst(canvas.width * .72f, canvas.height * .62f)
                HeartService.ACTION_STATE -> if (::status.isInitialized) status.text = intent.getStringExtra("state") ?: "Connexion…"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(22, 11, 23)
        window.navigationBarColor = Color.rgb(15, 8, 16)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
        }
        if (SecretStore(this).read() == null) showLogin() else showRoomAndStart()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply { addAction(HeartService.ACTION_HEART); addAction(HeartService.ACTION_STATE) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        super.onStop()
    }

    private fun showLogin() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(36), dp(28), dp(36))
            setBackgroundColor(Color.rgb(22, 11, 23))
        }
        root.addView(TextView(this).apply {
            text = "UN ESPACE RIEN QU'À NOUS"
            textSize = 12f; letterSpacing = .16f; setTextColor(Color.rgb(255, 154, 180))
        })
        root.addView(TextView(this).apply {
            text = "Entre dans Hearts."
            textSize = 48f; setTextColor(Color.rgb(255, 248, 244)); setPadding(0, dp(10), 0, dp(28))
        })
        val input = EditText(this).apply {
            hint = "Mot de passe"; textSize = 17f
            setTextColor(Color.WHITE); setHintTextColor(Color.rgb(180, 158, 173))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true); setPadding(dp(16), dp(12), dp(16), dp(12))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 79, 123))
        }
        root.addView(input, LinearLayout.LayoutParams.MATCH_PARENT, dp(56))
        val error = TextView(this).apply { setTextColor(Color.rgb(255, 176, 195)); setPadding(0, dp(8), 0, dp(8)) }
        root.addView(error)
        val button = Button(this).apply {
            text = "Ouvrir notre espace"; isAllCaps = false; textSize = 16f
            setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 79, 123))
            setOnClickListener {
                val value = input.text.toString()
                if (value.isBlank()) { error.text = "Entre le mot de passe."; return@setOnClickListener }
                SecretStore(this@MainActivity).save(value)
                showRoomAndStart()
            }
        }
        root.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        setContentView(root)
    }

    private fun showRoomAndStart() {
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.rgb(22, 11, 23)) }
        canvas = HeartCanvas(this)
        frame.addView(canvas, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), 0, dp(28), 0)
        }
        copy.addView(TextView(this).apply {
            text = "UN BATTEMENT À LA FOIS"; textSize = 11f; letterSpacing = .15f
            setTextColor(Color.rgb(255, 154, 180))
        })
        copy.addView(TextView(this).apply {
            text = "Je pense\nà toi."; textSize = 58f; setTextColor(Color.rgb(255, 248, 244)); includeFontPadding = false
        })
        copy.addView(TextView(this).apply {
            text = "Touche l'écran pour lui envoyer un cœur."; textSize = 15f
            setTextColor(Color.rgb(216, 198, 209)); setPadding(0, dp(18), 0, 0)
        })
        val copyParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL)
        frame.addView(copy, copyParams)
        status = TextView(this).apply {
            text = "Connexion…"; textSize = 13f; setTextColor(Color.rgb(216, 198, 209))
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }
        frame.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(44), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(20), dp(18), 0) })
        setContentView(frame)
        canvas.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                canvas.burst(event.x, event.y)
                ToneGenerator(AudioManager.STREAM_MUSIC, 55).apply {
                    startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
                    android.os.Handler(mainLooper).postDelayed({ release() }, 220)
                }
                startService(Intent(this, HeartService::class.java).setAction(HeartService.ACTION_SEND))
            }
            true
        }
        startForegroundService(Intent(this, HeartService::class.java).setAction(HeartService.ACTION_START))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

private data class Particle(
    var x: Float, var y: Float, val size: Float, val speed: Float,
    val drift: Float, val phase: Float, val color: Int, var age: Float = 0f
)

class HeartCanvas(context: Context) : View(context) {
    private val particles = CopyOnWriteArrayList<Particle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastFrame = System.nanoTime()
    private val palette = intArrayOf(0xffff376f.toInt(), 0xffff6d99.toInt(), 0xffffa1bb.toInt(), 0xffffd2df.toInt(), Color.WHITE)

    init {
        isClickable = true
        contentDescription = "Zone d'envoi de cœurs"
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun burst(x: Float, y: Float) {
        repeat(20) {
            particles += Particle(
                x + Random.nextFloat() * 80f - 40f,
                y + Random.nextFloat() * 36f - 18f,
                Random.nextFloat() * 25f + 18f,
                Random.nextFloat() * 130f + 110f,
                Random.nextFloat() * 90f - 45f,
                Random.nextFloat() * 6.28f,
                palette.random()
            )
        }
        while (particles.size > 90) particles.removeAt(0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val dt = ((now - lastFrame) / 1_000_000_000f).coerceAtMost(.04f)
        lastFrame = now
        val iterator = particles.iterator()
        for (p in iterator) {
            p.age += dt
            p.y -= p.speed * dt
            p.x += (p.drift + sin(p.age * 4f + p.phase) * 55f) * dt
            val progress = p.age / 2.25f
            paint.color = p.color
            paint.alpha = (255 * (1f - progress * progress)).toInt().coerceIn(0, 255)
            drawHeart(canvas, p.x, p.y, p.size, paint)
            if (progress >= 1f) particles.remove(p)
        }
        if (particles.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun drawHeart(canvas: Canvas, x: Float, y: Float, size: Float, paint: Paint) {
        val path = Path().apply {
            moveTo(x, y + size * .85f)
            cubicTo(x - size * 1.35f, y + size * .05f, x - size * .9f, y - size, x, y - size * .35f)
            cubicTo(x + size * .9f, y - size, x + size * 1.35f, y + size * .05f, x, y + size * .85f)
            close()
        }
        paint.setShadowLayer(12f, 0f, 5f, Color.argb(90, 255, 45, 105))
        canvas.drawPath(path, paint)
    }
}
