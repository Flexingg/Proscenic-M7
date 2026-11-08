package com.proscenic.m7

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.proscenic.m7.ui.MapDashboardScreen
import com.proscenic.m7.ui.RehomeAndHaScreen
import com.proscenic.m7.ui.SchedulesScreen
import com.proscenic.m7.ui.VacuumViewModel
import com.proscenic.m7.ui.components.FloatingIslandNavBar
import com.proscenic.m7.ui.components.LivingFlowNavTab
import com.proscenic.m7.ui.theme.LivingFlowTheme
import com.proscenic.m7.ui.theme.ProscenicLivingFlowTheme

/**
 * Main Activity & App Shell for the Proscenic M7 / M7 Pro Local Controller,
 * designed in OPPO's ColorOS 17 **Living Flow Design** (流体设计) language.
 *
 * - Edge-to-edge full-bleed canvas/content underneath floating glass chrome.
 * - Floating pill island navigation bar (`FloatingIslandNavBar`) inset 16dp above the bottom safe area,
 *   supporting horizontal swipe-to-switch and a companion circular glass D-Pad remote button.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ProscenicLivingFlowApp()
        }
    }
}

@Composable
fun ProscenicLivingFlowApp(
    viewModel: VacuumViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showRemoteModal by remember { mutableStateOf(false) }

    val navTabs = remember {
        listOf(
            LivingFlowNavTab(
                title = "Floor Map",
                selectedIcon = Icons.Filled.Map,
                unselectedIcon = Icons.Outlined.Map
            ),
            LivingFlowNavTab(
                title = "Schedules",
                selectedIcon = Icons.Filled.Schedule,
                unselectedIcon = Icons.Outlined.Schedule
            ),
            LivingFlowNavTab(
                title = "Pairing & HA",
                selectedIcon = Icons.Filled.Router,
                unselectedIcon = Icons.Outlined.Router
            )
        )
    }

    ProscenicLivingFlowTheme(
        darkThemeOverride = uiState.darkThemeOverride,
        reduceTransparency = uiState.reduceTransparency,
        reduceMotionOverride = uiState.reduceMotion,
        highContrast = uiState.highContrast
    ) {
        val colors = LivingFlowTheme.colors
        val motion = LivingFlowTheme.motion

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.bgBase)
                .statusBarsPadding()
        ) {
            // Full-bleed screen content running underneath the floating navigation island
            AnimatedContent(
                targetState = selectedTabIndex,
                transitionSpec = {
                    fadeIn(animationSpec = motion.morphSpring()) togetherWith
                        fadeOut(animationSpec = motion.gentleSpring())
                },
                label = "screenTransition",
                modifier = Modifier.fillMaxSize()
            ) { tabIdx ->
                when (tabIdx) {
                    0 -> MapDashboardScreen(
                        uiState = uiState,
                        viewModel = viewModel,
                        showRemoteModal = showRemoteModal,
                        onDismissRemoteModal = { showRemoteModal = false },
                        onOpenRemoteModal = { showRemoteModal = true },
                        onNavigateToPairing = { selectedTabIndex = 2 }
                    )
                    1 -> SchedulesScreen(
                        uiState = uiState,
                        viewModel = viewModel
                    )
                    else -> RehomeAndHaScreen(
                        uiState = uiState,
                        viewModel = viewModel
                    )
                }
            }

            // ColorOS 17 Floating Island Navigation Pill + Separate Companion Circular Glass Action
            FloatingIslandNavBar(
                tabs = navTabs,
                selectedIndex = selectedTabIndex,
                onSelectIndex = { selectedTabIndex = it },
                sideActionIcon = Icons.Filled.Gamepad,
                sideActionContentDescription = "Manual D-Pad Remote Control",
                sideActionActive = showRemoteModal,
                onSideActionClick = {
                    selectedTabIndex = 0
                    showRemoteModal = true
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 14.dp)
            )
        }
    }
}
