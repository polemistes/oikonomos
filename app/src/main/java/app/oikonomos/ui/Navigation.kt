package app.oikonomos.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.oikonomos.AppContainer
import app.oikonomos.ui.invoice.CustomersScreen
import app.oikonomos.ui.invoice.CustomersViewModel
import app.oikonomos.ui.invoice.InvoiceListScreen
import app.oikonomos.ui.invoice.InvoiceListViewModel
import app.oikonomos.ui.invoice.InvoiceScreen
import app.oikonomos.ui.invoice.InvoiceViewModel
import app.oikonomos.ui.list.VoucherListScreen
import app.oikonomos.ui.list.VoucherListViewModel
import app.oikonomos.ui.overview.OverviewScreen
import app.oikonomos.ui.overview.OverviewViewModel
import app.oikonomos.ui.settings.CategoriesScreen
import app.oikonomos.ui.settings.CategoriesViewModel
import app.oikonomos.ui.settings.ProjectsScreen
import app.oikonomos.ui.settings.ProjectsViewModel
import app.oikonomos.ui.settings.SettingsScreen
import app.oikonomos.ui.settings.SettingsViewModel
import app.oikonomos.ui.trip.TripListScreen
import app.oikonomos.ui.trip.TripListViewModel
import app.oikonomos.ui.trip.TripScreen
import app.oikonomos.ui.trip.TripViewModel
import app.oikonomos.ui.voucher.START_MANUAL
import app.oikonomos.ui.voucher.START_SHARED
import app.oikonomos.ui.voucher.VoucherEditScreen
import app.oikonomos.ui.voucher.VoucherEditViewModel
import kotlinx.serialization.Serializable

@Serializable
private object ListRoute

@Serializable
private object InvoicesRoute

@Serializable
private object TripsRoute

@Serializable
private data class TripRoute(val id: Long)

@Serializable
private object OverviewRoute

/** [id] er 0 for et nytt bilag. [start] er en av START-konstantene. */
@Serializable
private data class EditRoute(val id: Long = 0, val start: String = START_MANUAL)

/** [id] er 0 for en ny faktura. */
@Serializable
private data class InvoiceRoute(val id: Long = 0)

@Serializable
private object CustomersRoute

@Serializable
private object SettingsRoute

@Serializable
private object CategoriesRoute

@Serializable
private object ProjectsRoute

@Composable
fun OikonomosNavHost(container: AppContainer) {
    val navController = rememberNavController()

    // Filer som er delt fra en annen app, åpner et nytt bilag. Skjemaet henter dem selv fra innboksen.
    val shared by container.sharedInbox.collectAsStateWithLifecycle()
    LaunchedEffect(shared) {
        if (shared.isNotEmpty()) navController.navigate(EditRoute(start = START_SHARED))
    }

    val tabBar: @Composable (Tab) -> Unit = { current ->
        TabBar(current) { tab ->
            val route: Any = when (tab) {
                Tab.VOUCHERS -> ListRoute
                Tab.TRIPS -> TripsRoute
                Tab.INVOICES -> InvoicesRoute
                Tab.OVERVIEW -> OverviewRoute
            }
            // Fanene bytter plass i stedet for å legge seg oppå hverandre, og husker hvor de var.
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    val openSettings = { navController.navigate(SettingsRoute) }

    NavHost(navController, startDestination = ListRoute) {
        composable<ListRoute> {
            VoucherListScreen(
                viewModel = viewModel(factory = viewModelFactory { initializer { VoucherListViewModel(container.repository) } }),
                store = container.attachments,
                importer = container.importer,
                bottomBar = { tabBar(Tab.VOUCHERS) },
                onOpen = { navController.navigate(EditRoute(id = it)) },
                onNew = { navController.navigate(EditRoute(start = it)) },
                onSettings = openSettings,
            )
        }
        composable<TripsRoute> {
            TripListScreen(
                viewModel = viewModel(factory = viewModelFactory { initializer { TripListViewModel(container.repository) } }),
                bottomBar = { tabBar(Tab.TRIPS) },
                onOpen = { navController.navigate(TripRoute(it)) },
                onSettings = openSettings,
            )
        }
        composable<TripRoute> { entry ->
            val route = entry.toRoute<TripRoute>()
            TripScreen(
                viewModel = viewModel(factory = viewModelFactory { initializer { TripViewModel(route.id, container.repository) } }),
                store = container.attachments,
                onBack = { navController.popFrom(entry) },
                onOpenVoucher = { navController.navigate(EditRoute(id = it)) },
            )
        }
        composable<InvoicesRoute> {
            InvoiceListScreen(
                viewModel = viewModel(factory = viewModelFactory { initializer { InvoiceListViewModel(container.invoices) } }),
                bottomBar = { tabBar(Tab.INVOICES) },
                onOpen = { navController.navigate(InvoiceRoute(id = it)) },
                onNew = { navController.navigate(InvoiceRoute()) },
                onCustomers = { navController.navigate(CustomersRoute) },
                onSettings = openSettings,
            )
        }
        composable<OverviewRoute> {
            OverviewScreen(
                viewModel = viewModel(factory = viewModelFactory { initializer { OverviewViewModel(container) } }),
                bottomBar = { tabBar(Tab.OVERVIEW) },
                onSettings = openSettings,
            )
        }
        composable<EditRoute> { entry ->
            val route = entry.toRoute<EditRoute>()
            VoucherEditScreen(
                viewModel = viewModel(
                    factory = viewModelFactory { initializer { VoucherEditViewModel(route.id, route.start, container) } },
                ),
                start = route.start,
                store = container.attachments,
                onClose = { navController.popFrom(entry) },
            )
        }
        composable<InvoiceRoute> { entry ->
            val route = entry.toRoute<InvoiceRoute>()
            InvoiceScreen(
                viewModel = viewModel(factory = viewModelFactory { initializer { InvoiceViewModel(route.id, container) } }),
                onClose = { navController.popFrom(entry) },
                onSettings = openSettings,
            )
        }
        composable<CustomersRoute> { entry ->
            CustomersScreen(
                viewModel = viewModel(factory = viewModelFactory { initializer { CustomersViewModel(container.invoices) } }),
                onBack = { navController.popFrom(entry) },
            )
        }
        composable<SettingsRoute> { entry ->
            SettingsScreen(
                viewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(container) } }),
                onBack = { navController.popFrom(entry) },
                onCategories = { navController.navigate(CategoriesRoute) },
                onProjects = { navController.navigate(ProjectsRoute) },
            )
        }
        composable<ProjectsRoute> { entry ->
            ProjectsScreen(
                viewModel = viewModel(factory = viewModelFactory { initializer { ProjectsViewModel(container.repository) } }),
                onBack = { navController.popFrom(entry) },
            )
        }
        composable<CategoriesRoute> { entry ->
            CategoriesScreen(
                viewModel = viewModel(
                    factory = viewModelFactory { initializer { CategoriesViewModel(container.database.categoryDao()) } },
                ),
                onBack = { navController.popFrom(entry) },
            )
        }
    }
}

/** Går tilbake bare når skjermen fortsatt er den aktive, så to raske trykk ikke lukker to skjermer. */
private fun NavController.popFrom(entry: NavBackStackEntry) {
    if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) popBackStack()
}
