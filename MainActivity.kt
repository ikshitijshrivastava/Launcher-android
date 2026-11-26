package com.kshitij.minilauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class AppItem(val label: String, val pkg: String, val cls: String)

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private var allApps: List<AppItem> = emptyList()
    private var shown: List<AppItem> = emptyList()
    private lateinit var home: LinearLayout
    private lateinit var favBox: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var adapter: BaseAdapter
    private lateinit var gestures: GestureDetector

    private val autoLaunch: Boolean get() = prefs.getBoolean("auto", false)
    private fun px(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        buildHome()
        buildDrawer()
        root.addView(home, FrameLayout.LayoutParams(-1, -1))
        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))
        drawer.visibility = View.GONE
        setContentView(root)

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || drawer.visibility == View.VISIBLE) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val min = px(80)
                if (abs(dy) > abs(dx)) {
                    if (dy < -min) openDrawer() else if (dy > min) expandNotifications()
                } else {
                    if (dx < -min) openCamera() else if (dx > min) openDialer()
                }
                return true
            }
        })
        gestures.setIsLongpressEnabled(false)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        refreshFavs()
        filter()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeDrawer()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (drawer.visibility == View.VISIBLE) closeDrawer()
    }

    // ---------- UI ----------

    private fun buildHome() {
        home = LinearLayout(this)
        home.orientation = LinearLayout.VERTICAL
        home.setPadding(px(32), px(72), px(32), px(48))

        val clock = TextClock(this)
        clock.format12Hour = "h:mm"
        clock.format24Hour = "HH:mm"
        clock.textSize = 64f
        clock.setTextColor(Color.WHITE)
        clock.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)

        val date = TextClock(this)
        date.format12Hour = "EEEE, d MMMM"
        date.format24Hour = "EEEE, d MMMM"
        date.textSize = 16f
        date.setTextColor(Color.GRAY)

        val spacer = View(this)
        spacer.setOnLongClickListener { showSettings(); true }

        favBox = LinearLayout(this)
        favBox.orientation = LinearLayout.VERTICAL

        home.addView(clock)
        home.addView(date)
        home.addView(spacer, LinearLayout.LayoutParams(-1, 0, 1f))
        home.addView(favBox)
    }

    private fun buildDrawer() {
        drawer = LinearLayout(this)
        drawer.orientation = LinearLayout.VERTICAL
        drawer.setBackgroundColor(Color.BLACK)
        drawer.setPadding(px(32), px(56), px(32), 0)

        search = EditText(this)
        search.hint = "Search apps"
        search.setHintTextColor(Color.DKGRAY)
        search.setTextColor(Color.WHITE)
        search.textSize = 22f
        search.setSingleLine(true)
        search.background = null
        search.setPadding(0, px(8), 0, px(16))
        search.imeOptions = EditorInfo.IME_ACTION_GO
        search.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { filter() }
        })
        search.setOnEditorActionListener { _, _, _ ->
            if (shown.isNotEmpty()) launch(shown[0])
            true
        }

        list = ListView(this)
        list.divider = null
        adapter = object : BaseAdapter() {
            override fun getCount(): Int = shown.size
            override fun getItem(i: Int): Any = shown[i]
            override fun getItemId(i: Int): Long = i.toLong()
            override fun getView(i: Int, v: View?, p: ViewGroup?): View {
                val t = (v as? TextView) ?: TextView(this@MainActivity).apply {
                    textSize = 24f
                    setTextColor(Color.WHITE)
                    setPadding(0, px(12), 0, px(12))
                }
                t.text = shown[i].label
                return t
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, i, _ -> launch(shown[i]) }
        list.setOnItemLongClickListener { _, _, i, _ -> showAppMenu(shown[i]); true }

        drawer.addView(search, LinearLayout.LayoutParams(-1, -2))
        drawer.addView(list, LinearLayout.LayoutParams(-1, -1))
    }

    private fun refreshFavs() {
        favBox.removeAllViews()
        val favs = getFavs().mapNotNull { p -> allApps.firstOrNull { it.pkg == p } }
        if (favs.isEmpty()) {
            val hint = TextView(this)
            hint.text = "Swipe up for apps.\nLong-press an app to add it here."
            hint.setTextColor(Color.GRAY)
            hint.textSize = 16f
            favBox.addView(hint)
        }
        for (a in favs) {
            val t = TextView(this)
            t.text = a.label
            t.textSize = 30f
            t.setTextColor(Color.WHITE)
            t.setPadding(0, px(10), 0, px(10))
            t.setOnClickListener { launch(a) }
            t.setOnLongClickListener { showAppMenu(a); true }
            favBox.addView(t)
        }
    }

    // ---------- Apps ----------

    private fun loadApps() {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        allApps = packageManager.queryIntentActivities(i, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map {
                AppItem(
                    it.loadLabel(packageManager).toString(),
                    it.activityInfo.packageName,
                    it.activityInfo.name
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun matches(label: String, q: String): Boolean {
        if (label.contains(q)) return true
        var i = 0
        for (c in label) {
            if (i < q.length && c == q[i]) i++
        }
        return i == q.length
    }

    private fun filter() {
        val q = search.text.toString().trim().lowercase()
        val hidden = prefs.getStringSet("hidden", emptySet()) ?: emptySet()
        val base = allApps.filter { it.pkg !in hidden }
        shown = if (q.isEmpty()) base
        else base.filter { matches(it.label.lowercase(), q) }
            .sortedBy { if (it.label.lowercase().startsWith(q)) 0 else 1 }
        adapter.notifyDataSetChanged()
        if (autoLaunch && q.length >= 2 && shown.size == 1) launch(shown[0])
    }

    private fun launch(a: AppItem) {
        try {
            val i = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(a.pkg, a.cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(i)
        } catch (e: Exception) {
            toast("Can't open ${a.label}")
        }
        closeDrawer()
    }

    private fun getFavs(): List<String> =
        (prefs.getString("favs", "") ?: "").split("\n").filter { it.isNotEmpty() }

    private fun setFavs(l: List<String>) {
        prefs.edit().putString("favs", l.joinToString("\n")).apply()
    }

    private fun showAppMenu(a: AppItem) {
        val isFav = a.pkg in getFavs()
        val items = arrayOf(
            if (isFav) "Remove from home" else "Add to home",
            "Hide",
            "App info",
            "Uninstall"
        )
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(a.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val f = getFavs().toMutableList()
                        if (isFav) f.remove(a.pkg)
                        else if (f.size >= 8) toast("Home is full (8 apps)")
                        else f.add(a.pkg)
                        setFavs(f)
                    }
                    1 -> {
                        val s = HashSet(prefs.getStringSet("hidden", emptySet()) ?: emptySet())
                        s.add(a.pkg)
                        prefs.edit().putStringSet("hidden", s).apply()
                        setFavs(getFavs().filter { it != a.pkg })
                        toast("Hidden. Unhide from home long-press menu.")
                    }
                    2 -> safe {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))
                        )
                    }
                    3 -> safe {
                        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")))
                    }
                }
                refreshFavs()
                filter()
            }
            .show()
    }

    private fun showSettings() {
        val items = arrayOf(
            "Auto-open single search match: " + (if (autoLaunch) "ON" else "OFF"),
            "Unhide all apps",
            "Default home app settings",
            "System settings"
        )
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Mini Launcher")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> prefs.edit().putBoolean("auto", !autoLaunch).apply()
                    1 -> { prefs.edit().remove("hidden").apply(); filter() }
                    2 -> safe { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                    3 -> safe { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                }
            }
            .show()
    }

    // ---------- Drawer + gestures ----------

    private fun openDrawer() {
        drawer.visibility = View.VISIBLE
        search.requestFocus()
        search.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeDrawer() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(search.windowToken, 0)
        search.setText("")
        drawer.visibility = View.GONE
    }

    private fun expandNotifications() {
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel").invoke(sb)
        } catch (e: Exception) {
            toast("Pull down from the top edge")
        }
    }

    private fun openCamera() = safe { startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }
    private fun openDialer() = safe { startActivity(Intent(Intent.ACTION_DIAL)) }

    private fun safe(block: () -> Unit) {
        try { block() } catch (e: Exception) { toast("Not available on this device") }
    }
}
