package com.david.biblioteca

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.david.biblioteca.databinding.ActivityGameBinding
import com.david.biblioteca.databinding.ItemGameCardBinding
import java.io.File

class GameActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameBinding
    private lateinit var game: SyncGame
    private var color = Color.parseColor("#A259FF")
    private val handler = Handler(Looper.getMainLooper())
    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val user = Session(this).user ?: run { finish(); return }
        val data = OfflineStore(this, user.id).loadSync() ?: run { finish(); return }
        val gameId = intent.getLongExtra("GAME_ID", 0)
        game = data.juegos.firstOrNull { it.id == gameId } ?: run { finish(); return }
        if (game.parejas.size < 2) { finish(); return }

        val subject = data.materias.firstOrNull { it.id == game.subjectId }
        color = try { Color.parseColor(subject?.color ?: "#A259FF") } catch (e: Exception) { color }
        binding.header.setBackgroundColor(color)
        binding.btnBack.setOnClickListener { finish() }
        binding.tvTitle.text = game.titulo
        binding.btnAgain.setOnClickListener { recreate() } // vuelve a empezar con otro orden

        when (game.tipo) {
            "memoria" -> startMemory()
            "reloj" -> startClock()
            else -> startMatch()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    // Utilidades para dibujar fichas

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Crea una ficha dentro de una fila. */
    private fun newTile(row: LinearLayout, heightDp: Int): ItemGameCardBinding {
        val tile = ItemGameCardBinding.inflate(layoutInflater, row, false)
        val params = LinearLayout.LayoutParams(0, dp(heightDp), 1f)
        params.setMargins(dp(5), dp(5), dp(5), dp(5))
        row.addView(tile.root, params)
        return tile
    }

    private fun newRow(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        binding.gameArea.addView(row)
        return row
    }

    /** Colores de una ficha: fondo y borde. */
    private fun paint(tile: ItemGameCardBinding, background: Int, border: Int) {
        tile.card.setCardBackgroundColor(background)
        tile.card.strokeColor = border
    }

    /** Muestra la palabra de la pareja. */
    private fun showWord(tile: ItemGameCardBinding, pair: GamePair) {
        tile.img.visibility = View.GONE
        tile.tvText.textSize = 16f
        tile.tvText.text = pair.palabra
    }

    /** Muestra la "imagen" de la pareja: foto guardada en el celular, o emoji/texto. */
    private fun showImage(tile: ItemGameCardBinding, pair: GamePair) {
        val photo = pair.archivo?.let { File(filesDir, "imagenes/$it") }
        if (photo != null && photo.exists()) {
            tile.tvText.text = ""
            tile.img.visibility = View.VISIBLE
            tile.img.setImageURI(Uri.fromFile(photo))
        } else {
            tile.img.visibility = View.GONE
            val text = pair.imagen ?: "🖼️"
            tile.tvText.textSize = if (text.length <= 2) 34f else 20f
            tile.tvText.text = text
        }
    }

    private fun hideFace(tile: ItemGameCardBinding) {
        tile.img.visibility = View.GONE
        tile.tvText.textSize = 30f
        tile.tvText.text = "❓"
    }

    private fun win(message: String) {
        timer?.cancel()
        binding.layoutWin.visibility = View.VISIBLE
        binding.tvWin.text = message
    }

    private val white get() = Color.WHITE
    private val green get() = ContextCompat.getColor(this, R.color.green)
    private val greenSoft get() = ContextCompat.getColor(this, R.color.green_soft)
    private val red get() = ContextCompat.getColor(this, R.color.red)
    private val gray get() = ContextCompat.getColor(this, R.color.gray_light)

    // Memoria de parejas

    private data class Card(val pair: Int, val isImage: Boolean)

    private fun startMemory() {
        binding.tvInstructions.text = "Toca dos cartas: la palabra y su imagen."
        val pairs = game.parejas.shuffled().take(6)
        val cards = pairs.indices.flatMap { listOf(Card(it, false), Card(it, true)) }.shuffled()
        val tiles = mutableListOf<ItemGameCardBinding>()
        val open = mutableListOf<Int>()
        val found = mutableSetOf<Int>()
        var attempts = 0
        binding.tvStatus.text = "Intentos: 0"

        fun showCard(i: Int) {
            val card = cards[i]
            if (card.isImage) showImage(tiles[i], pairs[card.pair]) else showWord(tiles[i], pairs[card.pair])
            paint(tiles[i], white, color)
        }

        fun hideCard(i: Int) {
            hideFace(tiles[i])
            paint(tiles[i], color, color)
            tiles[i].tvText.setTextColor(Color.WHITE)
        }

        cards.indices.chunked(3).forEach { rowIndexes ->
            val row = newRow()
            rowIndexes.forEach { i ->
                val tile = newTile(row, 110)
                tiles.add(tile)
                tile.card.setOnClickListener {
                    if (i in open || cards[i].pair in found || open.size >= 2) return@setOnClickListener
                    open.add(i)
                    tile.tvText.setTextColor(ContextCompat.getColor(this, R.color.ink))
                    showCard(i)

                    if (open.size == 2) {
                        attempts++
                        binding.tvStatus.text = "Intentos: $attempts"
                        val (a, b) = open
                        if (cards[a].pair == cards[b].pair) {
                            // ¡Pareja encontrada!
                            found.add(cards[a].pair)
                            paint(tiles[a], greenSoft, green)
                            paint(tiles[b], greenSoft, green)
                            open.clear()
                            if (found.size == pairs.size) win("¡Encontraste todas las parejas en $attempts intentos!")
                        } else {
                            // No son pareja: se voltean después de un momento
                            handler.postDelayed({
                                hideCard(a)
                                hideCard(b)
                                open.clear()
                            }, 900)
                        }
                    }
                }
            }
            repeat(3 - rowIndexes.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
        }
        tiles.indices.forEach { hideCard(it) }
    }

    // Une palabra e imagen

    private fun startMatch() {
        binding.tvInstructions.text = "Toca una palabra y luego su imagen."
        val pairs = game.parejas.shuffled().take(6)
        val wordOrder = pairs.indices.shuffled()
        val imageOrder = pairs.indices.shuffled()
        val wordTiles = mutableMapOf<Int, ItemGameCardBinding>()
        val imageTiles = mutableMapOf<Int, ItemGameCardBinding>()
        val matched = mutableSetOf<Int>()
        var selected: Int? = null
        binding.tvStatus.text = "0 de ${pairs.size}"

        fun refreshWords() {
            wordTiles.forEach { (i, tile) ->
                when {
                    i in matched -> paint(tile, greenSoft, green)
                    i == selected -> paint(tile, color, color)
                    else -> paint(tile, white, gray)
                }
                tile.tvText.setTextColor(if (i == selected && i !in matched) Color.WHITE else ContextCompat.getColor(this, R.color.ink))
            }
        }

        pairs.indices.forEach { rowIndex ->
            val row = newRow()
            // Izquierda: palabra
            val w = wordOrder[rowIndex]
            val wordTile = newTile(row, 90)
            showWord(wordTile, pairs[w])
            wordTiles[w] = wordTile
            wordTile.card.setOnClickListener {
                if (w in matched) return@setOnClickListener
                selected = w
                refreshWords()
            }
            // Derecha: imagen
            val im = imageOrder[rowIndex]
            val imageTile = newTile(row, 90)
            showImage(imageTile, pairs[im])
            paint(imageTile, white, gray)
            imageTiles[im] = imageTile
            imageTile.card.setOnClickListener {
                val sel = selected ?: return@setOnClickListener
                if (im in matched) return@setOnClickListener
                if (sel == im) {
                    // ¡Correcto!
                    matched.add(im)
                    paint(imageTile, greenSoft, green)
                    selected = null
                    binding.tvStatus.text = "${matched.size} de ${pairs.size}"
                    if (matched.size == pairs.size) win("¡Uniste todas las parejas!")
                } else {
                    // Incorrecto: se pone roja un momento
                    paint(imageTile, Color.parseColor("#FFE5E5"), red)
                    handler.postDelayed({ if (im !in matched) paint(imageTile, white, gray) }, 700)
                }
                refreshWords()
            }
        }
        refreshWords()
    }

    //  Quiz contra el reloj

    private fun startClock() {
        binding.tvInstructions.text = "¿Qué va con la palabra? Tienes 30 segundos."
        binding.tvQuestion.visibility = View.VISIBLE
        val rounds = game.parejas.shuffled()
        var round = 0
        var points = 0
        var seconds = 30
        var answering = true

        fun finishClock(timeUp: Boolean) {
            binding.gameArea.removeAllViews()
            binding.tvQuestion.visibility = View.GONE
            win(
                if (timeUp) "¡Se acabó el tiempo! Hiciste $points puntos de ${rounds.size}."
                else "¡Terminaste! $points de ${rounds.size} correctas."
            )
        }

        fun showRound() {
            if (round >= rounds.size) { finishClock(false); return }
            answering = true
            val current = rounds[round]
            binding.tvQuestion.text = current.palabra
            binding.gameArea.removeAllViews()
            // La correcta + 2 al azar, revueltas
            val options = (listOf(current) + rounds.filter { it != current }.shuffled().take(2)).shuffled()
            options.forEach { option ->
                val row = newRow()
                val tile = newTile(row, 84)
                showImage(tile, option)
                paint(tile, white, color)
                tile.card.setOnClickListener {
                    if (!answering) return@setOnClickListener
                    answering = false
                    if (option == current) {
                        points++
                        paint(tile, greenSoft, green)
                    } else {
                        paint(tile, Color.parseColor("#FFE5E5"), red)
                    }
                    binding.tvStatus.text = "⏱️ $seconds s   ⭐ $points"
                    handler.postDelayed({ round++; showRound() }, 700)
                }
            }
        }

        binding.tvStatus.text = "⏱️ 30 s   ⭐ 0"
        timer = object : CountDownTimer(30_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                seconds = (millisUntilFinished / 1000).toInt()
                binding.tvStatus.text = "⏱️ $seconds s   ⭐ $points"
            }

            override fun onFinish() {
                handler.removeCallbacksAndMessages(null)
                if (binding.layoutWin.visibility != View.VISIBLE) finishClock(true)
            }
        }.start()
        showRound()
    }
}