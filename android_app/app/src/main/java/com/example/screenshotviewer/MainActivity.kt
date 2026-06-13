package com.example.screenshotviewer

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        /** 小部件点击时传入，指定要打开的标签页：0 = small，1 = large。 */
        const val EXTRA_OPEN_SLOT = "open_slot"
    }

    private lateinit var configTabLayout: TabLayout
    private lateinit var serverUrlInput: TextInputEditText
    private lateinit var usernameInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var rememberPasswordCheckbox: CheckBox
    private lateinit var autoLoginCheckbox: CheckBox
    private lateinit var connectButton: Button
    private lateinit var widgetSettingsButton: Button
    private lateinit var sortButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var imagesRecyclerView: RecyclerView
    private lateinit var imageAdapter: ImageAdapter

    private lateinit var sharedPreferences: SharedPreferences

    private var baseUrl = ""          // 含站点前缀，例如 http://host:5005/small
    private var originUrl = ""        // 仅 协议+主机+端口，例如 http://host:5005（登录用）
    private var siteKey = ""          // 站点标识，例如 small / large
    private var subPath = ""          // browse 之后的子路径，例如 releasing
    private var currentImages = mutableListOf<FileItem>()
    private var sortDescending = false

    private var currentSlot = 0       // 当前标签页：0 = small，1 = large
    private val slotTitles = listOf("small", "large")

    private val STORAGE_PERMISSION_CODE = 100
    private val PREFS_NAME = "ScreenshotViewerPrefs"
    private val KEY_SERVER_URL = "server_url"
    private val KEY_USERNAME = "username"
    private val KEY_PASSWORD = "password"
    private val KEY_REMEMBER_PASSWORD = "remember_password"
    private val KEY_AUTO_LOGIN = "auto_login"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        initViews()
        setupTabs()
        loadSavedCredentials()
        setupRecyclerView()
        setupListeners()
        setupTabListener()
        requestStoragePermission()

        // 处理来自小部件的「打开指定标签页」请求：
        //   large 小部件 -> 选中 large 标签（会触发 switchSlot，内部按需自动登录）
        //   small / 无指定 -> 走默认的自动登录逻辑
        val openSlot = intent.getIntExtra(EXTRA_OPEN_SLOT, -1)
        if (openSlot == 1) {
            configTabLayout.getTabAt(1)?.select()
        } else if (autoLoginCheckbox.isChecked) {
            autoLogin()
        }

        // 点击小部件进入：立即对该小部件再拉一次 count_api，刷新显示的数量
        refreshWidgetFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // App 已在运行时再次从小部件点击进入：切换到对应标签页
        val slot = intent.getIntExtra(EXTRA_OPEN_SLOT, -1)
        if (slot in 0..1) {
            configTabLayout.getTabAt(slot)?.select()
        }
        // 同样立即刷新被点击的小部件数量
        refreshWidgetFromIntent(intent)
    }

    /** 若 Intent 来自小部件点击（带有有效的 widget id），立即触发一次后台刷新。 */
    private fun refreshWidgetFromIntent(intent: Intent) {
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            CountWidgetProvider.requestRefresh(this, widgetId)
        }
    }

    private fun initViews() {
        configTabLayout = findViewById(R.id.configTabLayout)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        rememberPasswordCheckbox = findViewById(R.id.rememberPasswordCheckbox)
        autoLoginCheckbox = findViewById(R.id.autoLoginCheckbox)
        connectButton = findViewById(R.id.connectButton)
        widgetSettingsButton = findViewById(R.id.widgetSettingsButton)
        sortButton = findViewById(R.id.sortButton)
        progressBar = findViewById(R.id.progressBar)
        imagesRecyclerView = findViewById(R.id.imagesRecyclerView)
    }

    // ===== 标签页（small / large 两套配置） =====

    private fun setupTabs() {
        configTabLayout.addTab(configTabLayout.newTab().setText(slotTitles[0]))
        configTabLayout.addTab(configTabLayout.newTab().setText(slotTitles[1]))
    }

    private fun setupTabListener() {
        configTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                switchSlot(tab.position)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun switchSlot(slot: Int) {
        if (slot == currentSlot) return
        // 先保存当前标签页正在编辑的内容
        saveInputsToSlot(currentSlot)
        currentSlot = slot
        loadSlot(slot)

        // 清空当前显示的图片列表
        currentImages.clear()
        imageAdapter.updateImages(currentImages)
        sortButton.visibility = View.GONE

        // 若启用自动登录且字段齐全，则自动连接该标签页
        if (autoLoginCheckbox.isChecked) {
            autoLogin()
        }
    }

    // 每个标签页用独立的 key；slot 0 沿用旧 key（兼容老配置）
    private fun urlKey(slot: Int) = if (slot == 0) KEY_SERVER_URL else "${KEY_SERVER_URL}_$slot"
    private fun userKey(slot: Int) = if (slot == 0) KEY_USERNAME else "${KEY_USERNAME}_$slot"
    private fun passKey(slot: Int) = if (slot == 0) KEY_PASSWORD else "${KEY_PASSWORD}_$slot"

    private fun defaultUrlFor(slot: Int) = if (slot == 0)
        "http://192.168.1.100:5005/small/browse/releasing"
    else
        "http://192.168.1.100:5005/large/browse/releasing"

    // 标签页与站点强绑定：slot 0 -> small，slot 1 -> large
    private fun siteForSlot(slot: Int) = slotTitles[slot]

    /**
     * 把地址里的站点段（第一个路径段）强制改为指定 site。
     * 例如 http://IP:5005/large/browse/releasing + small
     *   -> http://IP:5005/small/browse/releasing
     * 这样无论用户在哪个标签里手输了什么 site，都会被纠正为该标签对应的站点，
     * 避免 small 标签被存成 large 地址（反之亦然）。
     */
    private fun enforceSite(rawUrl: String, site: String): String {
        val input = rawUrl.trim()
        val schemeIdx = input.indexOf("://")
        if (schemeIdx == -1) return input            // 非法地址，原样返回，交给后续校验
        val afterScheme = input.substring(schemeIdx + 3)
        val firstSlash = afterScheme.indexOf('/')
        val origin = if (firstSlash == -1) input
                     else input.substring(0, schemeIdx + 3 + firstSlash)
        if (firstSlash == -1) return "$origin/$site"
        val pathPart = afterScheme.substring(firstSlash + 1)
        val segs = pathPart.split("/").filter { it.isNotEmpty() }.toMutableList()
        if (segs.isEmpty()) return "$origin/$site"
        segs[0] = site                               // 替换站点段
        return "$origin/${segs.joinToString("/")}"
    }

    /** 读取当前输入框地址，按当前标签校正站点段后写回输入框，并返回校正后的地址。 */
    private fun currentCorrectedUrl(): String {
        val corrected = enforceSite(serverUrlInput.text.toString().trim(), siteForSlot(currentSlot))
        serverUrlInput.setText(corrected)
        return corrected
    }

    private fun loadSlot(slot: Int) {
        // 读出存储值后强制校正站点段，避免历史污染（如 small 标签存成了 large 地址）
        val stored = sharedPreferences.getString(urlKey(slot), defaultUrlFor(slot)) ?: defaultUrlFor(slot)
        serverUrlInput.setText(enforceSite(stored, siteForSlot(slot)))
        usernameInput.setText(sharedPreferences.getString(userKey(slot), "admin"))
        if (rememberPasswordCheckbox.isChecked) {
            passwordInput.setText(sharedPreferences.getString(passKey(slot), "admin123"))
        } else {
            passwordInput.setText("")
        }
    }

    private fun saveInputsToSlot(slot: Int) {
        val editor = sharedPreferences.edit()
        editor.putString(urlKey(slot), enforceSite(serverUrlInput.text.toString(), siteForSlot(slot)))
        editor.putString(userKey(slot), usernameInput.text.toString())
        if (rememberPasswordCheckbox.isChecked) {
            editor.putString(passKey(slot), passwordInput.text.toString())
        }
        editor.apply()
    }

    private fun loadSavedCredentials() {
        // 全局开关（两个标签页共用）
        rememberPasswordCheckbox.isChecked = sharedPreferences.getBoolean(KEY_REMEMBER_PASSWORD, true)
        autoLoginCheckbox.isChecked = sharedPreferences.getBoolean(KEY_AUTO_LOGIN, false)
        // 默认加载第一个标签页（small）
        loadSlot(0)
    }

    private fun saveCredentials() {
        val editor = sharedPreferences.edit()
        // 保存当前标签页的配置（地址按当前标签强制校正站点段）
        editor.putString(urlKey(currentSlot), enforceSite(serverUrlInput.text.toString(), siteForSlot(currentSlot)))
        editor.putString(userKey(currentSlot), usernameInput.text.toString())
        editor.putBoolean(KEY_REMEMBER_PASSWORD, rememberPasswordCheckbox.isChecked)
        editor.putBoolean(KEY_AUTO_LOGIN, autoLoginCheckbox.isChecked)
        if (rememberPasswordCheckbox.isChecked) {
            editor.putString(passKey(currentSlot), passwordInput.text.toString())
        } else {
            editor.remove(passKey(currentSlot))
        }
        editor.apply()
    }

    private fun autoLogin() {
        val url = currentCorrectedUrl()
        val username = usernameInput.text.toString().trim()
        val password = passwordInput.text.toString().trim()

        if (url.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty()) {
            if (parseTargetUrl(url)) {
                loginAndLoadImages(username, password)
            }
        }
    }

    /**
     * 解析用户输入的完整地址，例如：
     *   http://127.0.0.1:5005/small/browse/releasing
     * 得到：
     *   originUrl = http://127.0.0.1:5005   （登录用，/login 是全局路由）
     *   siteKey   = small
     *   subPath   = releasing               （保持原始编码，不做 decode）
     *   baseUrl   = http://127.0.0.1:5005/small
     * 返回是否解析成功。
     */
    private fun parseTargetUrl(raw: String): Boolean {
        val input = raw.trim()
        val schemeIdx = input.indexOf("://")
        if (schemeIdx == -1) return false

        val afterScheme = input.substring(schemeIdx + 3)
        val firstSlash = afterScheme.indexOf('/')
        if (firstSlash == -1) return false   // 只有 origin，缺少站点

        originUrl = input.substring(0, schemeIdx + 3 + firstSlash).trimEnd('/')
        val pathPart = afterScheme.substring(firstSlash + 1)
        val segs = pathPart.split("/").filter { it.isNotEmpty() }
        if (segs.isEmpty()) return false

        siteKey = segs[0]
        subPath = if (segs.size >= 2 && segs[1] == "browse") {
            segs.drop(2).joinToString("/")          // /site/browse/<subPath>
        } else {
            segs.drop(1).joinToString("/")          // /site/<subPath>（容错）
        }
        baseUrl = "$originUrl/$siteKey"
        return true
    }

    private fun setupRecyclerView() {
        imageAdapter = ImageAdapter(
            images = currentImages,
            baseUrl = baseUrl,
            onImageClick = { image -> openImageViewer(image) },
            onDownloadClick = { image -> downloadImage(image) },
            onDeleteClick = { image -> confirmDelete(image) }
        )
        imagesRecyclerView.layoutManager = GridLayoutManager(this, 2)
        imagesRecyclerView.adapter = imageAdapter
    }

    private fun setupListeners() {
        connectButton.setOnClickListener {
            val url = currentCorrectedUrl()
            val username = usernameInput.text.toString().trim()
            val password = passwordInput.text.toString().trim()

            if (url.isEmpty() || username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "请填写所有字段", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!parseTargetUrl(url)) {
                Toast.makeText(this, "地址格式不正确，应类似 http://IP:5005/small/browse/releasing", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            loginAndLoadImages(username, password)
        }

        sortButton.setOnClickListener {
            sortDescending = !sortDescending
            applySort()
            sortButton.text = if (sortDescending) "排序：从新到旧" else "排序：从旧到新"
        }

        widgetSettingsButton.setOnClickListener {
            openWidgetConfig(currentSlot)
        }
    }

    /**
     * 打开当前标签页对应的桌面小部件配置界面：
     *   slot 0 -> small 小部件，slot 1 -> large 小部件。
     * 每种类型最多一个实例；若桌面尚未添加该小部件，则提示用户先去添加。
     */
    private fun openWidgetConfig(slot: Int) {
        val providerClass = if (slot == 1)
            LargeCountWidgetProvider::class.java
        else
            SmallCountWidgetProvider::class.java

        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(ComponentName(this, providerClass))

        if (ids.isEmpty()) {
            Toast.makeText(
                this,
                "桌面上还没有「${slotTitles[slot]}」小部件，请先长按桌面添加后再设置",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val intent = Intent(this, CountWidgetConfigActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, ids[0])
        }
        startActivity(intent)
    }

    private fun applySort() {
        val comparator = compareBy<FileItem> { it.name }
        val sorted = if (sortDescending) {
            currentImages.sortedWith(comparator.reversed())
        } else {
            currentImages.sortedWith(comparator)
        }
        currentImages.clear()
        currentImages.addAll(sorted)
        imageAdapter.updateImages(currentImages)
    }

    private fun loginAndLoadImages(username: String, password: String) {
        lifecycleScope.launch {
            try {
                showLoading(true)

                // 登录走 origin（/login 是全局路由，不带站点前缀）
                val apiService = RetrofitClient.getClient(originUrl + "/")
                val loginResponse = apiService.login(username, password)

                if (loginResponse.isSuccessful) {
                    Toast.makeText(this@MainActivity, "登录成功", Toast.LENGTH_SHORT).show()

                    // 保存凭据
                    saveCredentials()

                    // 重新创建adapter，使用含站点前缀的 baseUrl
                    setupRecyclerView()

                    loadImages(subPath)
                } else {
                    Toast.makeText(this@MainActivity, "登录失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "错误: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private suspend fun loadImages(path: String = subPath) {
        withContext(Dispatchers.IO) {
            try {
                // baseUrl 含站点前缀，browse/<path> 即 /<site>/browse/<path>
                val apiService = RetrofitClient.getClient(baseUrl + "/")
                val response = apiService.browseDirectory(path)

                if (response.isSuccessful) {
                    val html = response.body()?.string() ?: ""
                    val images = parseImagesFromHtml(html)

                    withContext(Dispatchers.Main) {
                        currentImages.clear()
                        currentImages.addAll(images)
                        applySort()
                        sortButton.visibility = if (currentImages.isNotEmpty()) View.VISIBLE else View.GONE
                        sortButton.text = if (sortDescending) "排序：从新到旧" else "排序：从旧到新"
                        Toast.makeText(
                            this@MainActivity,
                            "找到 ${images.size} 张图片",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "加载图片失败: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun parseImagesFromHtml(html: String): List<FileItem> {
        val images = mutableListOf<FileItem>()

        try {
            // 从 HTML 中提取图片信息
            // 查找所有 data-type="image" 的 div
            // 合并版服务器的链接带站点前缀：/<site>/stream/... 与 /<site>/view/...
            // 用 /[^/]+/ 跳过站点段，捕获 stream 之后的相对路径（保持原始编码）
            val pattern = """<div class="file-item" data-type="image">.*?src="/[^/]+/stream/([^"]+)".*?<a href="/[^/]+/view/[^"]+">([^<]+)</a>.*?<div class="file-size">([^<]+)</div>""".toRegex(RegexOption.DOT_MATCHES_ALL)

            pattern.findAll(html).forEach { matchResult ->
                try {
                    // 保留原始的URL编码路径，不要decode
                    val encodedPath = matchResult.groupValues[1]
                    val name = matchResult.groupValues[2]
                    val sizeStr = matchResult.groupValues[3]

                    // 解析文件大小
                    val size = parseSizeString(sizeStr)

                    images.add(
                        FileItem(
                            name = name,
                            is_dir = false,
                            path = encodedPath,  // 保存已编码的路径
                            file_type = "image",
                            size = size
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return images
    }

    private fun parseSizeString(sizeStr: String): Long {
        try {
            val parts = sizeStr.trim().split(" ")
            if (parts.size == 2) {
                val value = parts[0].toDouble()
                val unit = parts[1]

                return when (unit) {
                    "B" -> value.toLong()
                    "KB" -> (value * 1024).toLong()
                    "MB" -> (value * 1024 * 1024).toLong()
                    "GB" -> (value * 1024 * 1024 * 1024).toLong()
                    else -> 0L
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return 0L
    }

    private fun openImageViewer(image: FileItem) {
        val position = currentImages.indexOf(image)
        val gson = Gson()
        val imagesJson = gson.toJson(currentImages)

        val intent = Intent(this, ImageViewerActivity::class.java).apply {
            putExtra("IMAGES_JSON", imagesJson)
            putExtra("POSITION", position)
            putExtra("BASE_URL", baseUrl)
        }
        startActivity(intent)
    }

    private fun downloadImage(image: FileItem) {
        lifecycleScope.launch {
            try {
                showLoading(true)
                val success = downloadImageToLocal(image)
                if (success) {
                    Toast.makeText(
                        this@MainActivity,
                        "已保存到 DCIM/Screenshots/",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "保存失败: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private suspend fun downloadImageToLocal(image: FileItem): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val client = RetrofitClient.getOkHttpClient()
                val request = Request.Builder()
                    .url("$baseUrl/stream/${image.path}")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext false

                val imageBytes = response.body?.bytes() ?: return@withContext false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+: Use MediaStore API
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, image.name)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Screenshots")
                    }

                    val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    uri?.let {
                        contentResolver.openOutputStream(it)?.use { outputStream ->
                            outputStream.write(imageBytes)
                        }
                    } ?: return@withContext false
                } else {
                    // Android 9 and below: Use legacy file storage
                    val screenshotsDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                        "Screenshots"
                    )
                    if (!screenshotsDir.exists()) {
                        screenshotsDir.mkdirs()
                    }

                    val outputFile = File(screenshotsDir, image.name)
                    FileOutputStream(outputFile).use { output ->
                        output.write(imageBytes)
                    }

                    // Notify media scanner
                    val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                    intent.data = android.net.Uri.fromFile(outputFile)
                    sendBroadcast(intent)
                }

                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    private fun confirmDelete(image: FileItem) {
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除 ${image.name} 吗?")
            .setPositiveButton("删除") { _, _ -> deleteImage(image) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteImage(image: FileItem) {
        lifecycleScope.launch {
            try {
                showLoading(true)
                val apiService = RetrofitClient.getClient(baseUrl + "/")
                val response = apiService.deleteFile(image.path)

                if (response.isSuccessful && response.body()?.success == true) {
                    Toast.makeText(this@MainActivity, "删除成功", Toast.LENGTH_SHORT).show()
                    // Reload images
                    loadImages()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "删除失败: ${response.body()?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "删除失败: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        connectButton.isEnabled = !show
    }

    private fun requestStoragePermission() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+: Request READ_MEDIA_IMAGES
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.READ_MEDIA_IMAGES
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.READ_MEDIA_IMAGES),
                        STORAGE_PERMISSION_CODE
                    )
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // Android 10-12: No permission needed for MediaStore
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                // Android 6-9: Request WRITE_EXTERNAL_STORAGE
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        STORAGE_PERMISSION_CODE
                    )
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "存储权限已授予", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "需要存储权限才能保存图片", Toast.LENGTH_LONG).show()
            }
        }
    }
}
