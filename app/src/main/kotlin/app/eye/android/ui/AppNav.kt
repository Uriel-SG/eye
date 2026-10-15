package app.eye.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.eye.android.R
import app.eye.android.core.Catalog
import app.eye.engine.Source
import app.eye.android.ui.theme.extras

object Route {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val PASTE = "paste"
    const val QR = "qr"
    const val VERDICT = "verdict"
    const val CATALOG = "catalog"
    const val CARD = "catalog/{id}"
    const val HISTORY = "history"
    const val HISTORY_ITEM = "history/{id}"
    fun historyItem(id: Long) = "history/$id"
    const val SETTINGS = "settings"
    const val LICENSES = "licenses"
    const val AUTO_INFO = "auto_info"
    fun card(id: String) = "catalog/$id"
}

private class Tab(val route: String, val label: Int, val icon: ImageVector)

private val Tabs = listOf(
    Tab(Route.HOME, R.string.nav_home, Icons.Rounded.Home),
    Tab(Route.CATALOG, R.string.nav_catalog, Icons.Rounded.MenuBook),
    Tab(Route.HISTORY, R.string.nav_history, Icons.Rounded.History),
    Tab(Route.SETTINGS, R.string.nav_settings, Icons.Rounded.Settings),
)

@Composable
fun AppNav(vm: AppViewModel) {
    val nav = rememberNavController()
    val settings by vm.settings.collectAsState()
    val state by vm.state.collectAsState()
    val family = androidx.compose.material3.MaterialTheme.extras.familyMode
    val backEntry by nav.currentBackStackEntryAsState()
    val current = backEntry?.destination?.route

    val seen = settings.onboardingSeen
    if (seen == null) return // preferenze in caricamento

    // Share Target o immagine ricevuta: apre la schermata Incolla.
    val imageBusy = state is CheckState.Reading || state is CheckState.NoText || state is CheckState.OcrUnavailable
    LaunchedEffect(imageBusy, seen) {
        if (seen && imageBusy && current != Route.PASTE) nav.navigate(Route.PASTE) { launchSingleTop = true }
    }
    val openCatalog by vm.openCatalog.collectAsState()
    LaunchedEffect(openCatalog, seen) {
        if (openCatalog && seen) { nav.navigate(Route.CATALOG) { launchSingleTop = true }; vm.catalogOpened() }
    }
    val qr by vm.qr.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(app.eye.android.ui.scan.ScanContract()) { raw -> raw?.let(vm::onQr) }
    val startQr = { scanLauncher.launch(Unit) }
    // QR arrivato dalla fotocamera o da un'immagine: mostra l'anteprima (il link non si apre mai).
    LaunchedEffect(qr) {
        if (qr != null && current != Route.QR) nav.navigate(Route.QR) { launchSingleTop = true }
    }
    LaunchedEffect(state) {
        if (state is CheckState.Done) nav.navigate(Route.VERDICT) {
            if (current == Route.PASTE) popUpTo(Route.PASTE) { inclusive = true }
            launchSingleTop = true
        }
    }

    val showBar = current in Tabs.map { it.route }
    val tabs = if (family) Tabs.filter { it.route == Route.HOME || it.route == Route.SETTINGS } else Tabs
    Scaffold(
        // Gli insets di sistema li gestiscono gli Scaffold delle singole schermate: qui nessuno, per non applicarli due volte.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = current == t.route,
                        onClick = {
                            val stack = if (runCatching { nav.getBackStackEntry(Route.HOME) }.isSuccess) listOf(Route.HOME) else emptyList()
                            when (val plan = TabNav.plan(stack, t.route)) {
                                TabNav.Plan.PopToHome -> if (!nav.popBackStack(Route.HOME, false)) nav.navigate(Route.HOME) { popUpTo(nav.graph.id) { inclusive = true } }
                                TabNav.Plan.ResetToHome -> nav.navigate(Route.HOME) { popUpTo(nav.graph.id) { inclusive = true }; launchSingleTop = true }
                                is TabNav.Plan.Open -> nav.navigate(plan.route) { popUpTo(Route.HOME); launchSingleTop = true }
                            }
                        },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                        alwaysShowLabel = true,
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = if (seen) Route.HOME else Route.ONBOARDING, modifier = Modifier.padding(pad)) {
            composable(Route.ONBOARDING) {
                OnboardingScreen {
                    vm.setOnboardingSeen()
                    nav.navigate(Route.HOME) { popUpTo(Route.ONBOARDING) { inclusive = true } }
                }
            }
            composable(Route.HOME) {
                HomeScreen(
                    onPaste = { nav.navigate(Route.PASTE) },
                    onSettings = { nav.navigate(Route.SETTINGS) },
                    onCatalog = { nav.navigate(Route.CATALOG) },
                    onCatalogCard = { nav.navigate(Route.card(it)) },
                    onHistory = { nav.navigate(Route.HISTORY) },
                    onPasteCheck = { vm.check(it, Source.PASTE) },
                    onQr = startQr,
                    onImage = { vm.ocr(ctx, it) },
                    ongoing = Catalog.ongoing(3, vm.catalog.collectAsState().value),
                )
            }
            composable(Route.PASTE) { PasteScreen(vm) { nav.popBackStack() } }
            composable(Route.QR) {
                qr?.let { q -> QrScreen(q, state, onCheck = { vm.check(q.raw, Source.QR) }, onBack = { vm.clearQr(); vm.reset(); nav.popBackStack() }) }
            }
            composable(Route.VERDICT) {
                val s = state
                if (s is CheckState.Done) VerdictScreen(s.result, vm, onClose = { vm.reset(); nav.popBackStack(Route.HOME, false) })
            }
            composable(Route.CATALOG) { CatalogScreen(vm) { nav.navigate(Route.card(it)) } }
            composable(Route.CARD) { e -> CatalogDetailScreen(vm, e.arguments?.getString("id").orEmpty()) { nav.popBackStack() } }
            composable(Route.HISTORY) { HistoryScreen(vm) { nav.navigate(Route.historyItem(it)) { launchSingleTop = true } } }
            composable(Route.HISTORY_ITEM, arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType })) { e ->
                HistoryDetailScreen(vm, e.arguments?.getLong("id") ?: -1L, { nav.popBackStack() })
            }
            composable(Route.SETTINGS) { SettingsScreen(vm, onBack = null, onAutoInfo = { nav.navigate(Route.AUTO_INFO) }, onLicenses = { nav.navigate(Route.LICENSES) }) }
            composable(Route.LICENSES) { LicensesScreen { nav.popBackStack() } }
            composable(Route.AUTO_INFO) { AutoProtectInfoScreen(vm) { nav.popBackStack() } }
        }
    }
}
