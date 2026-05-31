package com.example.blockerps

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import com.example.blockerps.ui.theme.BlockerPSTheme
import java.util.Calendar
/*此段code100%由gemini生成*/
data class MyAppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    var isMonitored: Boolean,
    var usedSeconds: Int,
    var limitMinutes: Int,
    var limitSeconds: Int
)

class MainActivity : ComponentActivity() {

    companion object {
        private const val BPS_TAG = "BPS_ConsoleAct"
        private const val CONFIG_PREF_NAME = "BlockerData"
    }

    // State tracking for package sync - initialized on startup
    private val localSnapshotApps = mutableStateListOf<MyAppInfo>()
    private val uiReqPermissionPrompt = mutableStateOf(false)

    // Cached shared preferences to prevent repeated IO on main thread
    private val sharedPrefs by lazy { getSharedPreferences(CONFIG_PREF_NAME, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Setup edge-to-edge layout for modern UI flow
        WindowCompat.setDecorFitsSystemWindows(window, false)

        try {
            scanAndPopulatePackages()
        } catch (ex: Exception) {
            Log.e(BPS_TAG, "CRITICAL: Package scan pipeline aborted.", ex)
        }

        setContent {
            BlockerPSTheme {
                var isSplashActive by remember { mutableStateOf(true) }
                if (isSplashActive) {
                    SplashVideoSurface {
                        isSplashActive = false
                        Log.d(BPS_TAG, "Splash video completed. Routing to terminal board.")
                    }
                } else {
                    DashboardConsoleView()
                    if (uiReqPermissionPrompt.value) SecurityAuthorizationModal()
                }
            }
        }
    }

    private fun grabDeviceSystemRuntime(): Long {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usm == null) {
            Log.w(BPS_TAG, "UsageStatsManager fallback triggered. System service unreachable.")
            return 0L
        }

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val tStart = cal.timeInMillis
        val tEnd = System.currentTimeMillis()

        return try {
            val queryResult = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, tStart, tEnd)
            queryResult?.sumOf { it.totalTimeInForeground } ?: 0L
        } catch (e: SecurityException) {
            Log.e(BPS_TAG, "Permission dynamic revocation caught during query stats", e)
            0L
        } catch (ex: Exception) {
            Log.e(BPS_TAG, "Unexpected structural error in UsageStats query", ex)
            0L
        }
    }

    override fun onResume() {
        super.onResume()
        evaluateWindowLayoutSafety()
        verifyRequiredSecurityTokens()
        syncStateFromStorage()
    }

    private fun evaluateWindowLayoutSafety() {
        // Anti-cheat handler: prevents bypass via split-screen multi-window hooks
        if (isInMultiWindowMode || isInPictureInPictureMode) {
            Log.w(BPS_TAG, "Exploit attempt: MultiWindow/PIP detected. Refusing background state.")
            val rescueIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            startActivity(rescueIntent)
        }
    }

    private fun verifyRequiredSecurityTokens() {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        val usageMode = ops?.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        val overlayGranted = Settings.canDrawOverlays(this)

        // UI state lock depending on complete security allowance
        uiReqPermissionPrompt.value = (usageMode != AppOpsManager.MODE_ALLOWED) || !overlayGranted

        if (!uiReqPermissionPrompt.value) {
            kickstartMonitorDaemon()
        }
    }

    private fun kickstartMonitorDaemon() {
        try {
            val daemonIntent = Intent(this, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(daemonIntent)
            } else {
                startService(daemonIntent)
            }
        } catch (ex: Exception) {
            Log.e(BPS_TAG, "Daemon service boot sequence failed", ex)
        }
    }

    @Composable
    private fun SecurityAuthorizationModal() {
        var modalStepIndex by remember { mutableStateOf(1) }
        AlertDialog(
            onDismissRequest = { /* Contractual enforcement: lock UI until permissions cleared */ },
            containerColor = Color(0xFF252525),
            shape = RoundedCornerShape(24.dp),
            title = { Text(if (modalStepIndex == 1) "🔒 需要權限" else "🛡️ 需要權限", color = Color.White) },
            text = { Text(if (modalStepIndex == 1) "請開啟「使用量存取」權限，否則無法計算 App 使用時間。" else "請開啟「顯示在其他程式上方」權限，否則無法進行攔截。", color = Color.LightGray) },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            if (modalStepIndex == 1) {
                                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                modalStepIndex = 2
                            } else {
                                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                            }
                        } catch (err: Exception) {
                            Log.e(BPS_TAG, "OS System Settings intent target not found", err)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF81C784))
                ) { Text("立即前往", color = Color.Black, fontWeight = FontWeight.Bold) }
            }
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DashboardConsoleView() {
        var queryFilterToken by remember { mutableStateOf("") }
        val inputFocusMgr = LocalFocusManager.current
        val filteredList = localSnapshotApps.filter { it.name.contains(queryFilterToken, ignoreCase = true) }

        val activeMinutesValue = grabDeviceSystemRuntime() / 1000 / 60
        val trackingCount = localSnapshotApps.count { it.isMonitored }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Blocker+網癮救贖系統", fontWeight = FontWeight.Black, letterSpacing = 1.sp) },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color(0xFF121212), titleContentColor = Color.White)
                )
            },
            containerColor = Color(0xFF121212)
        ) { edgePadding ->
            Column(modifier = Modifier.padding(edgePadding).fillMaxSize().padding(horizontal = 16.dp)) {

                // Telemetry metrics hardware display
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    color = Color(0xFF1E1E1E),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color(0xFF333333))
                ) {
                    Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceAround) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("今日裝置總使用", color = Color.Gray, fontSize = 12.sp)
                            Text("$activeMinutesValue min", color = Color(0xFF81C784), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        Box(modifier = Modifier.width(1.dp).height(40.dp).background(Color(0xFF333333)))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("監控中", color = Color.Gray, fontSize = 12.sp)
                            Text("$trackingCount 個", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }
                }

                OutlinedTextField(
                    value = queryFilterToken,
                    onValueChange = { queryFilterToken = it },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    placeholder = { Text("搜尋應用程式...", color = Color.DarkGray) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = Color(0xFF81C784)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { inputFocusMgr.clearFocus() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF81C784), unfocusedBorderColor = Color(0xFF333333)
                    )
                )

                Button(
                    onClick = { hardResetDiagnosticCounters() },
                    modifier = Modifier.fillMaxWidth().height(50.dp).padding(vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF252525), contentColor = Color.LightGray),
                    border = BorderStroke(1.dp, Color(0xFF333333))
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("重置紀錄(方便測試)", fontWeight = FontWeight.Medium)
                }

                LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(filteredList, key = { it.packageName }) { targetNode -> AppControlRowRenderer(targetNode) }
                }
            }
        }
    }

    @Composable
    private fun AppControlRowRenderer(node: MyAppInfo) {
        val totalSecLimits = node.limitMinutes * 60 + node.limitSeconds
        val isThresholdViolated = node.isMonitored && node.usedSeconds >= totalSecLimits && totalSecLimits > 0
        val isUiEditable = node.usedSeconds == 0

        val elementBgColor = if (isThresholdViolated) Color(0xFF2D1616) else Color(0xFF1E1E1E)
        val semanticAccent = if (isThresholdViolated) Color(0xFFFF5252) else Color(0xFF81C784)

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = elementBgColor,
            border = BorderStroke(1.dp, if (!isUiEditable) Color.DarkGray else Color(0xFF333333))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(45.dp).clip(CircleShape).background(Color(0xFF252525)).padding(8.dp)) {
                        Image(node.icon.toBitmap().asImageBitmap(), null, Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(node.name, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                        Text(text = if (isThresholdViolated) "⚠️ 限制已達標" else "今日: ${node.usedSeconds / 60}分 ${node.usedSeconds % 60}秒", color = semanticAccent, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = node.isMonitored,
                        onCheckedChange = { checkState -> executePreferenceToggle(node, checkState) },
                        enabled = isUiEditable,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF81C784))
                    )
                }

                AnimatedVisibility(visible = node.isMonitored) {
                    Column {
                        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFF333333))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Build, null, tint = if (isUiEditable) Color.Gray else Color.Red, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (isUiEditable) "設定限制" else "🔒 已鎖定調整", color = if (isUiEditable) Color.Gray else Color.Red, fontSize = 12.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                InputNumericalCell(node.limitMinutes.toString(), "分", isUiEditable) { valChange -> writeLimitUpdate(node.packageName, valChange, node.limitSeconds) }
                                Text(" : ", color = Color.White)
                                InputNumericalCell(node.limitSeconds.toString(), "秒", isUiEditable) { valChange -> writeLimitUpdate(node.packageName, node.limitMinutes, valChange) }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun InputNumericalCell(rawTextValue: String, indicatorLabel: String, interactiveEnabled: Boolean, onDeltaCommit: (Int) -> Unit) {
        OutlinedTextField(
            value = rawTextValue,
            onValueChange = { text -> if (text.all { it.isDigit() }) onDeltaCommit(text.toIntOrNull() ?: 0) },
            modifier = Modifier.width(70.dp),
            label = { Text(indicatorLabel, fontSize = 9.sp) },
            enabled = interactiveEnabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                disabledTextColor = Color.DarkGray,
                focusedBorderColor = Color(0xFF81C784),
                unfocusedBorderColor = Color(0xFF444444)
            )
        )
    }

    @Composable
    private fun SplashVideoSurface(onVideoEnd: () -> Unit) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        try {
                            val uriPath = "android.resource://${packageName}/raw/start_anim"
                            setVideoURI(Uri.parse(uriPath))
                            setOnPreparedListener { player -> player.start() }
                            setOnCompletionListener { onVideoEnd() }
                        } catch (ex: Exception) {
                            Log.e(BPS_TAG, "Splash rendering error, bypassing screen", ex)
                            onVideoEnd() // Fail-safe bridge
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    private fun writeLimitUpdate(pkg: String, minutes: Int, seconds: Int) {
        val safeSeconds = if (seconds >= 60) 59 else seconds
        sharedPrefs.edit()
            .putInt("${pkg}_limit_min", minutes)
            .putInt("${pkg}_limit_sec", safeSeconds)
            .apply()
        syncStateFromStorage()
    }

    private fun executePreferenceToggle(appData: MyAppInfo, isChecked: Boolean) {
        val globalMonitoredSet = sharedPrefs.getStringSet("monitored_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (isChecked) {
            globalMonitoredSet.add(appData.packageName)
        } else {
            globalMonitoredSet.remove(appData.packageName)
        }
        sharedPrefs.edit().putStringSet("monitored_apps", globalMonitoredSet).apply()
        syncStateFromStorage()
    }

    private fun hardResetDiagnosticCounters() {
        val txEditor = sharedPrefs.edit()
        localSnapshotApps.forEach { item -> txEditor.putInt("${item.packageName}_seconds", 0) }
        txEditor.apply()
        syncStateFromStorage()
        Toast.makeText(this, "✅ 紀錄已歸零，控制權已恢復", Toast.LENGTH_SHORT).show()
    }

    private fun scanAndPopulatePackages() {
        val pm = packageManager
        val queryIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)

        val parsedList = pm.queryIntentActivities(queryIntent, 0).map { info ->
            val pkg = info.activityInfo.packageName
            MyAppInfo(
                name = info.loadLabel(pm).toString(),
                packageName = pkg,
                icon = info.loadIcon(pm),
                isMonitored = sharedPrefs.getStringSet("monitored_apps", emptySet())?.contains(pkg) == true,
                usedSeconds = sharedPrefs.getInt("${pkg}_seconds", 0),
                limitMinutes = sharedPrefs.getInt("${pkg}_limit_min", 5),
                limitSeconds = sharedPrefs.getInt("${pkg}_limit_sec", 0)
            )
        }.distinctBy { it.packageName }.filter { it.packageName != packageName }

        localSnapshotApps.clear()
        localSnapshotApps.addAll(parsedList)
    }

    private fun syncStateFromStorage() {
        // Linear sweep update mapping preferences delta into current jetpack compose state
        localSnapshotApps.forEachIndexed { i, currentMeta ->
            localSnapshotApps[i] = currentMeta.copy(
                isMonitored = sharedPrefs.getStringSet("monitored_apps", emptySet())?.contains(currentMeta.packageName) == true,
                usedSeconds = sharedPrefs.getInt("${currentMeta.packageName}_seconds", 0),
                limitMinutes = sharedPrefs.getInt("${currentMeta.packageName}_limit_min", 5),
                limitSeconds = sharedPrefs.getInt("${currentMeta.packageName}_limit_sec", 0)
            )
        }
    }
}