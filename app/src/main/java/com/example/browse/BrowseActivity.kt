package com.example.browse

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * Native Netflix-style browse grid for wow-drama.com.
 *
 * Listing pages are scraped natively (fast, smooth D-pad navigation);
 * pressing a card opens the existing WebView MainActivity only for the
 * drama detail / episode / player page.
 */
class BrowseActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "wow_tv_prefs"
        private const val KEY_LAST_CATEGORY = "key_last_category_url"
        private const val BACK_EXIT_INTERVAL_MS = 2500L

        private const val URL_HOME = "https://wow-drama.com/"
        private const val URL_CHINA = "https://wow-drama.com/cat_category/cn-2024-th/"
        private const val URL_THAI = "https://wow-drama.com/category/the-series-th/"
        private const val URL_KOREA = "https://wow-drama.com/cat_category/korea-thai-sound/"
        private const val URL_JAPAN = "https://wow-drama.com/cat_category/jp-thai-sound/"
        private const val SEARCH_URL = "https://wow-drama.com/?s="

        private const val TYPE_CARD = 0
        private const val TYPE_FOOTER = 1
    }

    private lateinit var topBarScroll: HorizontalScrollView
    private lateinit var topBar: LinearLayout
    private lateinit var grid: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var btnRetry: TextView
    private lateinit var searchOverlay: LinearLayout
    private lateinit var searchInput: EditText

    private lateinit var adapter: MovieAdapter
    private var btnSearch: TextView? = null

    private var currentUrl: String? = null
    private var nextPageUrl: String? = null
    private var isLoading = false
    private var lastBackTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)

        topBarScroll = findViewById(R.id.browseTopBarScroll)
        topBar = findViewById(R.id.browseTopBar)
        grid = findViewById(R.id.browseGrid)
        loading = findViewById(R.id.browseLoading)
        errorBox = findViewById(R.id.browseError)
        errorText = findViewById(R.id.browseErrorText)
        btnRetry = findViewById(R.id.btnBrowseRetry)
        searchOverlay = findViewById(R.id.browseSearchOverlay)
        searchInput = findViewById(R.id.browseSearchInput)

        adapter = MovieAdapter { card -> openDetail(card) }
        val manager = GridLayoutManager(this, 5)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.getItemViewType(position) == TYPE_FOOTER) 5 else 1
        }
        grid.layoutManager = manager
        grid.adapter = adapter

        setupTopBar()
        setupSearchOverlay()
        setupBackNavigation()

        findViewById<TextView>(R.id.btnBrowseOpenWeb).setOnClickListener { openWebMode() }
        btnRetry.setOnClickListener { currentUrl?.let { loadUrl(it, append = false) } }

        val last = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_LAST_CATEGORY, null)
        loadUrl(last ?: URL_CHINA, append = false)
    }

    // ==================== Top bar ====================

    private fun setupTopBar() {
        val categories = listOf(
            "🏠 ทั้งหมด" to URL_HOME,
            "🇨🇳 จีน" to URL_CHINA,
            "🇹🇭 ไทย" to URL_THAI,
            "🇰🇷 เกาหลี" to URL_KOREA,
            "🇯🇵 ญี่ปุ่น" to URL_JAPAN
        )
        var chinaButton: TextView? = null
        for ((index, entry) in categories.withIndex()) {
            val button = makeTopButton(entry.first)
            button.setOnClickListener { selectCategory(entry.second) }
            if (index == 1) chinaButton = button
            topBar.addView(button)
        }

        val searchButton = makeTopButton("🔍 ค้นหา")
        searchButton.setOnClickListener { openSearch() }
        topBar.addView(searchButton)
        btnSearch = searchButton

        val webButton = makeTopButton("🌐 โหมดเว็บ")
        webButton.setOnClickListener { openWebMode() }
        topBar.addView(webButton)

        // Start D-pad navigation on the default category
        chinaButton?.post { chinaButton?.requestFocus() }
    }

    private fun makeTopButton(label: String): TextView {
        val density = resources.displayMetrics.density
        val button = TextView(this)
        button.text = label
        button.textSize = 14f
        button.setTextColor(ContextCompat.getColor(this, R.color.white))
        button.gravity = Gravity.CENTER
        button.isFocusable = true
        button.isFocusableInTouchMode = true
        button.setBackgroundResource(R.drawable.tv_nav_button_bg)
        button.setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            (40 * density).toInt()
        )
        lp.marginEnd = (10 * density).toInt()
        lp.gravity = Gravity.CENTER_VERTICAL
        button.layoutParams = lp
        button.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) topBarScroll.smoothScrollTo(v.left - 40, 0)
        }
        return button
    }

    private fun selectCategory(url: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putString(KEY_LAST_CATEGORY, url).apply()
        loadUrl(url, append = false)
    }

    // ==================== Loading ====================

    private fun loadUrl(url: String, append: Boolean) {
        if (isLoading) return
        isLoading = true
        errorBox.visibility = View.GONE
        loading.visibility = View.VISIBLE
        if (!append) {
            currentUrl = url
            nextPageUrl = null
            adapter.setCards(emptyList())
        }

        lifecycleScope.launch {
            val result = WowScraper.loadPage(url)
            isLoading = false
            loading.visibility = View.GONE

            if (result == null) {
                if (append) {
                    Toast.makeText(this@BrowseActivity, "โหลดหน้าถัดไปไม่สำเร็จ ลองอีกครั้ง", Toast.LENGTH_SHORT).show()
                    adapter.setHasMore(true)
                } else {
                    showError(
                        if (url.contains("?s=")) "ไม่พบผลการค้นหา"
                        else "โหลดรายการหนังไม่สำเร็จ"
                    )
                }
                return@launch
            }

            grid.visibility = View.VISIBLE
            if (append) adapter.addCards(result.cards) else adapter.setCards(result.cards)
            nextPageUrl = result.nextPageUrl
            adapter.setHasMore(nextPageUrl != null)
            if (!append) grid.scrollToPosition(0)
        }
    }

    private fun showError(message: String) {
        errorText.text = message
        errorBox.visibility = View.VISIBLE
        btnRetry.requestFocus()
    }

    // ==================== Actions ====================

    private fun openDetail(card: MovieCard) {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra(MainActivity.EXTRA_URL, card.detailUrl)
        startActivity(intent)
    }

    private fun openWebMode() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    // ==================== Search overlay ====================

    private fun setupSearchOverlay() {
        findViewById<TextView>(R.id.btnCancelBrowseSearch).setOnClickListener { closeSearch() }
        findViewById<TextView>(R.id.btnSubmitBrowseSearch).setOnClickListener { submitSearch() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                submitSearch()
                true
            } else {
                false
            }
        }
    }

    private fun openSearch() {
        searchOverlay.visibility = View.VISIBLE
        searchInput.setText("")
        searchInput.requestFocus()
    }

    private fun closeSearch() {
        searchOverlay.visibility = View.GONE
        btnSearch?.requestFocus()
    }

    private fun submitSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty()) {
            Toast.makeText(this, "กรุณาใส่คำค้นหา", Toast.LENGTH_SHORT).show()
            return
        }
        searchOverlay.visibility = View.GONE
        loadUrl(SEARCH_URL + URLEncoder.encode(query, "UTF-8"), append = false)
    }

    // ==================== Back handling ====================

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (searchOverlay.visibility == View.VISIBLE) {
                    closeSearch()
                    return
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackTime < BACK_EXIT_INTERVAL_MS) {
                    finish()
                } else {
                    lastBackTime = now
                    Toast.makeText(
                        this@BrowseActivity,
                        "กดปุ่มย้อนกลับอีกครั้งเพื่อออกจากแอป",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }

    // ==================== RecyclerView adapter ====================

    inner class MovieAdapter(private val onCardClick: (MovieCard) -> Unit) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = ArrayList<MovieCard>()
        private var hasMore = false

        fun setCards(list: List<MovieCard>) {
            items.clear()
            items.addAll(list)
            hasMore = false
            notifyDataSetChanged()
        }

        fun addCards(list: List<MovieCard>) {
            val start = items.size
            items.addAll(list)
            notifyItemRangeInserted(start, list.size)
        }

        fun setHasMore(more: Boolean) {
            if (more == hasMore) return
            hasMore = more
            if (more) notifyItemInserted(items.size) else notifyItemRemoved(items.size)
        }

        override fun getItemCount(): Int = items.size + if (hasMore) 1 else 0

        override fun getItemViewType(position: Int): Int =
            if (position == items.size) TYPE_FOOTER else TYPE_CARD

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_FOOTER) {
                FooterHolder(inflater.inflate(R.layout.item_browse_load_more, parent, false))
            } else {
                CardHolder(inflater.inflate(R.layout.item_movie_card, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is FooterHolder -> {
                    holder.itemView.setOnClickListener {
                        nextPageUrl?.let { loadUrl(it, append = true) }
                    }
                }
                is CardHolder -> bindCard(holder, items[position])
            }
        }

        private fun bindCard(holder: CardHolder, card: MovieCard) {
            holder.title.text = card.title

            bindBadge(holder.badge, card.badge)
            bindBadge(holder.imdb, card.imdb)
            bindBadge(holder.epInfo, card.epInfo)
            bindBadge(holder.year, card.year)

            if (card.posterUrl != null) {
                holder.poster.visibility = View.VISIBLE
                holder.poster.load(card.posterUrl) { crossfade(true) }
            } else {
                holder.poster.visibility = View.INVISIBLE
            }

            holder.itemView.setOnClickListener { onCardClick(card) }
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.07f else 1f
                v.animate().scaleX(scale).scaleY(scale).setDuration(120L).start()
                holder.title.setTextColor(
                    if (hasFocus) 0xFF00D2FF.toInt() else 0xFFFFFFFF.toInt()
                )
            }
        }

        private fun bindBadge(view: TextView, text: String?) {
            if (text == null) {
                view.visibility = View.GONE
            } else {
                view.visibility = View.VISIBLE
                view.text = text
            }
        }

        inner class CardHolder(view: View) : RecyclerView.ViewHolder(view) {
            val poster: ImageView = view.findViewById(R.id.cardPoster)
            val title: TextView = view.findViewById(R.id.cardTitle)
            val badge: TextView = view.findViewById(R.id.cardBadge)
            val imdb: TextView = view.findViewById(R.id.cardImdb)
            val epInfo: TextView = view.findViewById(R.id.cardEp)
            val year: TextView = view.findViewById(R.id.cardYear)
        }

        inner class FooterHolder(view: View) : RecyclerView.ViewHolder(view)
    }
}
