package dev.leonardo.mt6899

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.leonardo.mt6899.ui.AppTheme
import dev.leonardo.mt6899.ui.CpuScreen
import dev.leonardo.mt6899.ui.DisplayScreen
import dev.leonardo.mt6899.ui.GpuScreen
import dev.leonardo.mt6899.ui.HomeScreen
import dev.leonardo.mt6899.ui.LogsScreen
import dev.leonardo.mt6899.ui.NetScreen
import dev.leonardo.mt6899.ui.ProfilesScreen
import dev.leonardo.mt6899.ui.SystemScreen
import dev.leonardo.mt6899.ui.ThermalScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                AppRoot(vm)
            }
        }
    }
}

@Composable
fun AppRoot(vm: MainViewModel) {
    var tab by remember { mutableStateOf(AppTab.Home) }
    val snack = remember { SnackbarHostState() }
    val ctx = LocalContext.current

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // leitura ao vivo enquanto uma aba "ao vivo" está aberta
    LaunchedEffect(vm.phase, tab) {
        if (vm.phase != Phase.Ready) return@LaunchedEffect
        when (tab) {
            AppTab.Cpu, AppTab.Gpu, AppTab.System -> vm.refreshNodes()
            AppTab.Thermal -> { vm.refreshNodes(); vm.refreshExtras() }
            AppTab.Profiles -> {}
            else -> {}
        }
        while (true) {
            when (tab) {
                AppTab.Home -> { vm.refreshLive(); vm.refreshProfileState() }
                AppTab.Cpu, AppTab.Gpu, AppTab.Thermal -> vm.refreshLive()
                AppTab.Profiles -> vm.refreshProfileState()
                AppTab.Display -> vm.refreshDisplay()
                else -> {}
            }
            delay(2000)
        }
    }

    LaunchedEffect(vm.message) {
        val m = vm.message
        if (m != null) {
            vm.message = null
            snack.showSnackbar(m)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        containerColor = MaterialTheme.colorScheme.background
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (vm.phase) {
                Phase.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                Phase.NoRoot -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text("Root não disponível", style = MaterialTheme.typography.titleLarge)
                        Text("Este app precisa de root (Magisk, KernelSU ou APatch). Conceda a permissão de superusuário e tente de novo.")
                        Button(onClick = { vm.retry() }) { Text("Tentar de novo") }
                    }
                }
                Phase.Ready -> {
                    ScrollableTabRow(
                        selectedTabIndex = tab.ordinal,
                        edgePadding = 8.dp,
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        for (t in AppTab.entries) {
                            Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.title) })
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        when (tab) {
                            AppTab.Home -> HomeScreen(vm)
                            AppTab.Cpu -> CpuScreen(vm)
                            AppTab.Gpu -> GpuScreen(vm)
                            AppTab.Thermal -> ThermalScreen(vm)
                            AppTab.System -> SystemScreen(vm)
                            AppTab.Profiles -> ProfilesScreen(vm)
                            AppTab.Display -> DisplayScreen(vm)
                            AppTab.Net -> NetScreen(vm)
                            AppTab.Logs -> LogsScreen(vm)
                        }
                    }
                }
            }
        }
    }
}
