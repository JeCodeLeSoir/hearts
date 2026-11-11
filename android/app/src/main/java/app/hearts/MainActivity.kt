package app.hearts

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.sin
import kotlin.random.Random

private object Ui {
    val bg = Color.rgb(8, 10, 13)
    val panel = Color.rgb(10, 16, 19)
    val ink = Color.rgb(244, 247, 237)
    val muted = Color.rgb(152, 174, 175)
    val cyan = Color.rgb(0, 240, 255)
    val yellow = Color.rgb(244, 240, 0)
    val pink = Color.rgb(255, 43, 136)
}

class MainActivity : Activity() {
    private lateinit var canvas: HeartCanvas
    private lateinit var status: TextView

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                HeartService.ACTION_HEART -> if (::canvas.isInitialized) {
                    val x = intent.getFloatExtra("x", .5f).coerceIn(0f, 1f)
                    val y = intent.getFloatExtra("y", .5f).coerceIn(0f, 1f)
                    canvas.burst(canvas.width * x, canvas.height * y)
                }
                HeartService.ACTION_STATE -> if (::status.isInitialized) status.text = "■ ${(intent.getStringExtra("state") ?: "Connexion…").uppercase()}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.bg
        window.navigationBarColor = Ui.bg
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
        val frame = FrameLayout(this).apply { setBackgroundColor(Ui.bg) }
        applySafeArea(frame)
        frame.addView(CyberBackdrop(this), FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
            background = panel(Ui.cyan, Ui.panel)
        }
        card.addView(TextView(this).apply {
            text = "ACCESS NODE                                      01"
            textSize = 10f; letterSpacing = .12f; setTextColor(Ui.cyan); typeface = Typeface.MONOSPACE
        })
        card.addView(TextView(this).apply {
            text = "CONNEXION PRIVÉE // DEUX PERSONNES"
            textSize = 10f; letterSpacing = .1f; setTextColor(Ui.yellow); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(32), 0, dp(8))
        })
        card.addView(TextView(this).apply {
            text = "ENTRE DANS\nHEARTS."
            textSize = 46f; letterSpacing = -.04f; setTextColor(Ui.ink); typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        })
        card.addView(TextView(this).apply {
            text = "Un signal. Un battement. Reçu instantanément de l'autre côté."
            textSize = 15f; setTextColor(Ui.muted); setLineSpacing(0f, 1.25f)
            setPadding(0, dp(20), 0, dp(28))
        })
        card.addView(TextView(this).apply {
            text = "MOT DE PASSE"; textSize = 10f; letterSpacing = .12f
            setTextColor(Ui.cyan); typeface = Typeface.MONOSPACE; setPadding(0, 0, 0, dp(8))
        })
        val input = EditText(this).apply {
            hint = "••••••••"; textSize = 17f
            setTextColor(Ui.ink); setHintTextColor(Ui.muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true); setPadding(dp(16), dp(12), dp(16), dp(12)); typeface = Typeface.MONOSPACE
            background = panel(Ui.cyan, Color.rgb(7, 18, 21))
        }
        card.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        val error = TextView(this).apply {
            setTextColor(Ui.pink); setPadding(0, dp(8), 0, dp(8)); minHeight = dp(34); typeface = Typeface.MONOSPACE
        }
        card.addView(error)
        val button = Button(this).apply {
            text = "INITIALISER LE LIEN     ›"; isAllCaps = false; textSize = 14f; letterSpacing = .08f
            setTextColor(Ui.bg); typeface = Typeface.DEFAULT_BOLD
            backgroundTintList = android.content.res.ColorStateList.valueOf(Ui.yellow)
            setOnClickListener {
                val value = input.text.toString()
                if (value.isBlank()) { error.text = "Entre le mot de passe."; return@setOnClickListener }
                SecretStore(this@MainActivity).save(value)
                showRoomAndStart()
            }
        }
        card.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        frame.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(20), dp(28), dp(20), dp(28))
        })
        frame.addView(TextView(this).apply {
            text = "CH//PRIVATE-LINK"; textSize = 9f; letterSpacing = .12f; setTextColor(Ui.cyan); typeface = Typeface.MONOSPACE
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(40), Gravity.TOP or Gravity.START).apply { setMargins(dp(22), dp(20), 0, 0) })
        frame.addView(TextView(this).apply {
            text = "ENCRYPTION ACTIVE"; textSize = 9f; letterSpacing = .1f; setTextColor(Ui.muted); typeface = Typeface.MONOSPACE
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(34), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(22), 0, 0, dp(12)) })
        setContentView(frame)
    }

    private fun showRoomAndStart() {
        val frame = FrameLayout(this).apply { setBackgroundColor(Ui.bg) }
        applySafeArea(frame)
        canvas = HeartCanvas(this)
        frame.addView(canvas, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        frame.addView(TextView(this).apply {
            text = "CH"; gravity = Gravity.CENTER; textSize = 11f; setTextColor(Ui.cyan); typeface = Typeface.MONOSPACE
            background = panel(Ui.cyan, Color.TRANSPARENT)
        }, FrameLayout.LayoutParams(dp(44), dp(40), Gravity.TOP or Gravity.START).apply { setMargins(dp(20), dp(20), 0, 0) })
        frame.addView(TextView(this).apply {
            text = "HEARTS//LINK\nPRIVATE CHANNEL"; textSize = 10f; setTextColor(Ui.ink)
            typeface = Typeface.MONOSPACE; setLineSpacing(0f, 1.15f)
        }, FrameLayout.LayoutParams(dp(210), dp(48), Gravity.TOP or Gravity.START).apply { setMargins(dp(76), dp(20), 0, 0) })
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), 0, dp(24), 0)
        }
        copy.addView(TextView(this).apply {
            text = "NEURAL TRANSMISSION // READY"; textSize = 10f; letterSpacing = .1f
            setTextColor(Ui.yellow); typeface = Typeface.DEFAULT_BOLD
        })
        copy.addView(TextView(this).apply {
            text = "JE PENSE\nÀ TOI."; textSize = 58f; letterSpacing = -.04f
            setTextColor(Ui.ink); typeface = Typeface.DEFAULT_BOLD; includeFontPadding = false
        })
        copy.addView(TextView(this).apply {
            text = "[ TAP ANYWHERE ]  //  ENVOIE UN CŒUR"; textSize = 11f
            setTextColor(Ui.muted); typeface = Typeface.MONOSPACE; setPadding(0, dp(20), 0, 0)
        })
        val copyParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL)
        frame.addView(copy, copyParams)
        status = TextView(this).apply {
            text = "■ CONNEXION…"; textSize = 10f; setTextColor(Ui.cyan); typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(9), dp(12), dp(9)); background = panel(Ui.cyan, Color.rgb(7, 18, 21))
        }
        frame.addView(status, FrameLayout.LayoutParams(dp(154), dp(44), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(20), dp(18), 0) })
        frame.addView(TextView(this).apply {
            text = "X.042 / Y.077\nNIGHT CHANNEL"; textSize = 9f; gravity = Gravity.END
            setTextColor(Ui.cyan); typeface = Typeface.MONOSPACE
        }, FrameLayout.LayoutParams(dp(150), dp(50), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(20), dp(18)) })
        setContentView(frame)
        frame.isClickable = true
        frame.contentDescription = "Zone d'envoi de cœurs"
        frame.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                canvas.burst(event.x, event.y)
                ToneGenerator(AudioManager.STREAM_MUSIC, 55).apply {
                    startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
                    android.os.Handler(mainLooper).postDelayed({ release() }, 220)
                }
                startService(Intent(this, HeartService::class.java).apply {
                    action = HeartService.ACTION_SEND
                    putExtra("x", (event.x / canvas.width).coerceIn(0f, 1f))
                    putExtra("y", (event.y / canvas.height).coerceIn(0f, 1f))
                })
            }
            true
        }
        startForegroundService(Intent(this, HeartService::class.java).setAction(HeartService.ACTION_START))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun applySafeArea(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun panel(stroke: Int, fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(fill); setStroke(dp(1), stroke); cornerRadius = 0f
    }
}

class CyberBackdrop(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Ui.bg)
        paint.strokeWidth = 1f
        paint.color = Color.argb(28, 0, 240, 255)
        val step = width / 7f
        for (i in -2..9) canvas.drawLine(width / 2f, height * .48f, i * step, height.toFloat(), paint)
        var y = height * .52f
        var gap = 34f
        while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, paint); y += gap; gap *= 1.18f }
        paint.color = Color.argb(18, 255, 255, 255)
        var scan = 0f
        while (scan < height) { canvas.drawLine(0f, scan, width.toFloat(), scan, paint); scan += 8f }
        paint.color = Color.argb(30, 255, 43, 136)
        canvas.drawCircle(width * .78f, height * .2f, width * .35f, paint)
    }
}

private data class Particle(
    var x: Float, var y: Float, val size: Float, val speed: Float,
    val drift: Float, val phase: Float, val color: Int, var age: Float = 0f
)

class HeartCanvas(context: Context) : View(context) {
    private val particles = ArrayList<Particle>(48)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val unitHeart = Path().apply {
        moveTo(0f, .85f)
        cubicTo(-1.35f, .05f, -.9f, -1f, 0f, -.35f)
        cubicTo(.9f, -1f, 1.35f, .05f, 0f, .85f)
        close()
    }
    private var hudBitmap: Bitmap? = null
    private var lastFrame = System.nanoTime()
    private val palette = intArrayOf(Ui.pink, 0xffff5fa7.toInt(), Ui.cyan, Ui.yellow, Color.WHITE)

    init {
        isClickable = false
    }

    fun burst(x: Float, y: Float) {
        repeat(12) {
            particles += Particle(
                x + Random.nextFloat() * 80f - 40f,
                y + Random.nextFloat() * 36f - 18f,
                Random.nextFloat() * 23f + 18f,
                Random.nextFloat() * 130f + 110f,
                Random.nextFloat() * 90f - 45f,
                Random.nextFloat() * 6.28f,
                palette.random()
            )
        }
        while (particles.size > 48) particles.removeAt(0)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        hudBitmap?.recycle()
        if (w > 0 && h > 0) {
            hudBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { drawHud(Canvas(it)) }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hudBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        val now = System.nanoTime()
        val dt = ((now - lastFrame) / 1_000_000_000f).coerceAtMost(.04f)
        lastFrame = now
        for (index in particles.lastIndex downTo 0) {
            val p = particles[index]
            p.age += dt
            p.y -= p.speed * dt
            p.x += (p.drift + sin(p.age * 4f + p.phase) * 55f) * dt
            val progress = p.age / 1.85f
            paint.color = p.color
            paint.alpha = (255 * (1f - progress * progress)).toInt().coerceIn(0, 255)
            drawHeart(canvas, p.x, p.y, p.size, paint)
            if (progress >= 1f) particles.removeAt(index)
        }
        if (particles.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun drawHud(canvas: Canvas) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = Color.argb(45, 0, 240, 255)
        val horizon = height * .5f
        for (i in -3..10) canvas.drawLine(width / 2f, horizon, i * width / 7f, height.toFloat(), paint)
        var y = horizon + 36f
        var gap = 42f
        while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, paint); y += gap; gap *= 1.19f }
        paint.color = Color.argb(24, 255, 255, 255)
        var scan = 0f
        while (scan < height) { canvas.drawLine(0f, scan, width.toFloat(), scan, paint); scan += 8f }
        paint.style = Paint.Style.FILL
        paint.color = Ui.yellow; canvas.drawRect(8f, height * .39f, 16f, height * .49f, paint)
        paint.color = Ui.pink; canvas.drawRect(8f, height * .505f, 16f, height * .61f, paint)
    }

    private fun drawHeart(canvas: Canvas, x: Float, y: Float, size: Float, paint: Paint) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(size, size)
        canvas.drawPath(unitHeart, paint)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        hudBitmap?.recycle()
        hudBitmap = null
        super.onDetachedFromWindow()
    }
}
