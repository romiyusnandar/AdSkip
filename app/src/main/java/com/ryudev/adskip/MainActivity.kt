package com.ryudev.adskip

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ryudev.adskip.ui.theme.AdSkipTheme

class MainActivity : ComponentActivity() {
    private lateinit var updateManager: UpdateManager
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var hasPromptedThisForeground = false
    private var hasCheckedForUpdates = false
    private var lastAutoUpdateEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateManager = UpdateManager(applicationContext)
        lastAutoUpdateEnabled = UpdateManager.isAutoUpdateEnabled(this)
        AutoSkipService.syncFeatureEnabled(this)
        AutoSkipService.clearStaleNotificationIfNeeded(this)
        enableEdgeToEdge()
        setContent {
            AdSkipTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        onAutoUpdateToggled = { enabled ->
                            UpdateManager.setAutoUpdateEnabled(this, enabled)
                            if (enabled) {
                                hasCheckedForUpdates = false
                                checkForUpdatesIfNeeded()
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AutoSkipService.syncFeatureEnabled(this)
        AutoSkipService.clearStaleNotificationIfNeeded(this)
        requestNotificationPermissionIfNeeded()
        checkForUpdatesIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        val currentAutoUpdateEnabled = UpdateManager.isAutoUpdateEnabled(this)
        if (currentAutoUpdateEnabled && !lastAutoUpdateEnabled) {
            hasCheckedForUpdates = false
        }
        lastAutoUpdateEnabled = currentAutoUpdateEnabled

        resumePendingInstallIfPossible()
        checkForUpdatesIfNeeded()
    }

    override fun onStop() {
        hasPromptedThisForeground = false
        super.onStop()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (hasPromptedThisForeground) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            hasPromptedThisForeground = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun checkForUpdatesIfNeeded() {
        if (!UpdateManager.isAutoUpdateEnabled(this)) return
        if (hasCheckedForUpdates) return
        hasCheckedForUpdates = true

        updateManager.checkForUpdates(
            currentVersion = currentAppVersion(),
            onUpdateAvailable = { update ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.update_found_downloading, update.newVersion),
                        Toast.LENGTH_SHORT
                    ).show()
                    updateManager.downloadUpdate(update)
                }
            },
            onError = { error ->
                runOnUiThread {
                    Log.w("Update", error)
                }
            }
        )
    }

    private fun resumePendingInstallIfPossible() {
        if (!UpdateManager.isAutoUpdateEnabled(this)) return
        val pendingUri = updateManager.consumePendingInstallUri() ?: return
        if (updateManager.canInstallPackages()) {
            UpdateManager.setUpdateStatus(this, UpdateManager.STATUS_READY)
            updateManager.installApk(pendingUri)
        } else {
            UpdateManager.setUpdateStatus(this, UpdateManager.STATUS_WAITING_PERMISSION)
            updateManager.savePendingInstallUri(pendingUri)
        }
    }

    private fun currentAppVersion(): String {
        return packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
    }
}

fun isAccessibilityServiceEnabled(context: Context, service: Class<out AccessibilityService>): Boolean {
    val expectedComponentName = ComponentName(context, service)
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServices)
    while (colonSplitter.hasNext()) {
        val componentName = colonSplitter.next()
        val enabledService = ComponentName.unflattenFromString(componentName)
        if (enabledService != null && enabledService == expectedComponentName) {
            return true
        }
    }
    return false
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onAutoUpdateToggled: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val isFeatureActive by AutoSkipService.isFeatureEnabled
    var isAutoUpdateEnabled by remember {
        mutableStateOf(UpdateManager.isAutoUpdateEnabled(context))
    }
    var updateStatus by remember {
        mutableStateOf(UpdateManager.getUpdateStatus(context))
    }
    var isAccessibilityEnabled by remember {
        mutableStateOf(isAccessibilityServiceEnabled(context, AutoSkipService::class.java))
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled =
                    isAccessibilityServiceEnabled(context, AutoSkipService::class.java)
                isAutoUpdateEnabled = UpdateManager.isAutoUpdateEnabled(context)
                updateStatus = UpdateManager.getUpdateStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "v${getAppVersion(context)}",
                style = MaterialTheme.typography.headlineSmall,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = stringResource(R.string.hero_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.hero_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isAccessibilityEnabled) {
                    colorScheme.primaryContainer
                } else {
                    colorScheme.errorContainer
                }
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (isAccessibilityEnabled) {
                        colorScheme.primary.copy(alpha = 0.15f)
                    } else {
                        colorScheme.error.copy(alpha = 0.15f)
                    }
                ) {
                    Icon(
                        modifier = Modifier.padding(10.dp),
                        imageVector = if (isAccessibilityEnabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isAccessibilityEnabled) colorScheme.primary else colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.status_system),
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (isAccessibilityEnabled) {
                            stringResource(R.string.accessibility_status_on)
                        } else {
                            stringResource(R.string.accessibility_status_off)
                        },
                        color = if (isAccessibilityEnabled) {
                            colorScheme.onPrimaryContainer
                        } else {
                            colorScheme.onErrorContainer
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorScheme.secondaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                val isServiceSwitchEnabled = isAccessibilityEnabled
                val isServiceSwitchChecked = isAccessibilityEnabled && isFeatureActive
                SettingSwitchRow(
                    title = stringResource(R.string.run_service),
                    subtitle = if (isServiceSwitchChecked) {
                        stringResource(R.string.service_state_running)
                    } else {
                        stringResource(R.string.service_state_standby)
                    },
                    checked = isServiceSwitchChecked,
                    enabled = isServiceSwitchEnabled,
                    icon = Icons.Default.Settings,
                    onCheckedChange = { AutoSkipService.setFeatureEnabled(context, it) }
                )

                Spacer(
                    modifier = Modifier
                        .height(1.dp)
                        .fillMaxWidth()
                        .background(colorScheme.onSecondaryContainer.copy(alpha = 0.2f))
                )

                SettingSwitchRow(
                    title = stringResource(R.string.auto_update_title),
                    subtitle = if (isAutoUpdateEnabled) stringResource(updateStatusToTextRes(updateStatus))
                    else stringResource(R.string.auto_update_status_disabled),
                    checked = isAutoUpdateEnabled,
                    enabled = true,
                    icon = Icons.Default.Warning,
                    onCheckedChange = {
                        isAutoUpdateEnabled = it
                        onAutoUpdateToggled(it)
                        updateStatus = UpdateManager.getUpdateStatus(context)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
//
//        Button(
//            onClick = {
//                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
//                context.startActivity(intent)
//            },
//            modifier = Modifier.fillMaxWidth(),
//            colors = ButtonDefaults.buttonColors(
//                containerColor = colorScheme.primaryContainer,
//                contentColor = colorScheme.onPrimaryContainer
//            )
//        ) {
//            Row(
//                verticalAlignment = Alignment.CenterVertically,
//                horizontalArrangement = Arrangement.Center
//            ) {
//                Icon(Icons.Default.Settings, contentDescription = null)
//                Spacer(modifier = Modifier.width(8.dp))
//                Text(
//                    if (isAccessibilityEnabled) {
//                        stringResource(R.string.open_accessibility_settings)
//                    } else {
//                        stringResource(R.string.enable_in_accessibility)
//                    }
//                )
//            }
//        }

//        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = { openYouTube(context) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = colorScheme.error,
                contentColor = colorScheme.onError
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.open_youtube))
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        NavigationBar(
            modifier = Modifier.fillMaxWidth(),
            containerColor = Color.Transparent
        ) {
            NavigationBarItem(
                selected = true,
                onClick = {},
                icon = { Icon(Icons.Default.Home, contentDescription = null) },
                label = { Text(stringResource(R.string.nav_dashboard)) }
            )
            NavigationBarItem(
                selected = false,
                onClick = { openYouTube(context) },
                icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                label = { Text(stringResource(R.string.nav_youtube)) }
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onCheckedChange: (Boolean) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.65f)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

private fun updateStatusToTextRes(status: String): Int {
    return when (status) {
        UpdateManager.STATUS_CHECKING -> R.string.auto_update_status_checking
        UpdateManager.STATUS_DOWNLOADING -> R.string.auto_update_status_downloading
        UpdateManager.STATUS_READY -> R.string.auto_update_status_ready
        UpdateManager.STATUS_WAITING_PERMISSION -> R.string.auto_update_status_waiting_permission
        UpdateManager.STATUS_ERROR -> R.string.auto_update_status_error
        else -> R.string.auto_update_status_enabled
    }
}

private fun getAppVersion(context: Context): String {
    return try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }
}

private fun openYouTube(context: Context) {
    val appIntent = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
    if (appIntent != null) {
        context.startActivity(appIntent)
        return
    }

    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
    context.startActivity(webIntent)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    AdSkipTheme {
        MainScreen()
    }
}
