package com.dannr.chengzikb.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dannr.chengzikb.ui.backup.BackupScreen
import com.dannr.chengzikb.ui.course.CourseEditScreen
import com.dannr.chengzikb.ui.course.CoursePickerScreen
import com.dannr.chengzikb.ui.main.MainScreen
import com.dannr.chengzikb.ui.main.TimetableManagerScreen
import com.dannr.chengzikb.ui.settings.AboutScreen
import com.dannr.chengzikb.ui.settings.HolidaySchemeScreen
import com.dannr.chengzikb.ui.settings.IcsImportScreen
import com.dannr.chengzikb.ui.settings.PeriodsEditorScreen
import com.dannr.chengzikb.ui.settings.TermSettingsScreen

/** 顶层路由名 */
object Routes {
    const val MAIN = "main"
    const val COURSE_ADD = "course/add"
    const val COURSE_EDIT = "course/edit"
    const val COURSE_PICK = "course/pick" // 选已有课程排到某个空格
    const val TERM = "term"
    const val PERIODS = "periods"
    const val BACKUP = "backup"
    const val ABOUT = "about"
    const val TABLES = "tables"
    const val ICS_IMPORT = "ics_import"
    const val HOLIDAYS = "holidays"
}

/**
 * 顶层导航：main（含底部三 Tab）；新增/编辑/作息/学期/备份为覆盖子页。
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Routes.MAIN,
        // 滑动 + 轻淡，避免“只有淡入”
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(470)) + fadeIn(tween(240))
        },
        exitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(400)) + fadeOut(tween(170))
        },
        popEnterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(470))
        },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(400)) + fadeOut(tween(160))
        },
    ) {
        composable(Routes.MAIN) {
            MainScreen(
                onOpenAdd = { day, period, seedSlot ->
                    navController.navigate("${Routes.COURSE_ADD}/$day/$period?seedSlot=${if (seedSlot) 1 else 0}")
                },
                onPickExistingCourse = { day, period ->
                    navController.navigate("${Routes.COURSE_PICK}/$day/$period")
                },
                onEditCourse = { course ->
                    navController.navigate("${Routes.COURSE_EDIT}/${course.id}")
                },
                onOpenTerm = { navController.navigate(Routes.TERM) },
                onOpenPeriods = { navController.navigate(Routes.PERIODS) },
                onOpenBackup = { navController.navigate(Routes.BACKUP) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                onOpenTables = { navController.navigate(Routes.TABLES) },
                onOpenIcs = { navController.navigate(Routes.ICS_IMPORT) },
                onOpenHolidays = { navController.navigate(Routes.HOLIDAYS) },
            )
        }
        composable(
            route = "${Routes.COURSE_ADD}/{day}/{period}?seedSlot={seedSlot}",
            arguments = listOf(
                navArgument("day") { type = NavType.IntType },
                navArgument("period") { type = NavType.IntType },
                navArgument("seedSlot") { type = NavType.IntType; defaultValue = 0 },
            ),
        ) { entry ->
            val day = entry.arguments?.getInt("day") ?: 1
            val period = entry.arguments?.getInt("period") ?: 0
            val seedSlot = entry.arguments?.getInt("seedSlot") == 1
            CourseEditScreen(
                courseId = null,
                defaultDay = day,
                defaultPeriod = period,
                seedFromSlot = seedSlot,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            // 编辑既有课程；可选 preseedDay/preseedPeriod=从“选已有课”进入时预设的空格位置
            route = "${Routes.COURSE_EDIT}/{courseId}?preseedDay={preseedDay}&preseedPeriod={preseedPeriod}",
            arguments = listOf(
                navArgument("courseId") { type = NavType.LongType },
                navArgument("preseedDay") { type = NavType.IntType; defaultValue = -1 },
                navArgument("preseedPeriod") { type = NavType.IntType; defaultValue = -1 },
            ),
        ) { entry ->
            val courseId = entry.arguments?.getLong("courseId")
            val preseedDay = entry.arguments?.getInt("preseedDay")?.takeIf { it >= 1 }
            val preseedPeriod = entry.arguments?.getInt("preseedPeriod")?.takeIf { it >= 0 }
            CourseEditScreen(
                courseId = courseId,
                defaultDay = 1,
                defaultPeriod = 0,
                preseedDay = preseedDay,
                preseedPeriod = preseedPeriod,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "${Routes.COURSE_PICK}/{day}/{period}",
            arguments = listOf(
                navArgument("day") { type = NavType.IntType },
                navArgument("period") { type = NavType.IntType },
            ),
        ) { entry ->
            val day = entry.arguments?.getInt("day") ?: 1
            val period = entry.arguments?.getInt("period") ?: 0
            CoursePickerScreen(
                day = day,
                period = period,
                onBack = { navController.popBackStack() },
                onPick = { courseId ->
                    // 加完即进入该课编辑页；清掉中间的选课页，返回时直接回课表
                    navController.navigate("${Routes.COURSE_EDIT}/$courseId?preseedDay=$day&preseedPeriod=$period") {
                        popUpTo(Routes.MAIN)
                    }
                },
            )
        }
        composable(Routes.TERM) {
            TermSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.PERIODS) {
            PeriodsEditorScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.BACKUP) {
            BackupScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.TABLES) {
            TimetableManagerScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.HOLIDAYS) {
            HolidaySchemeScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.ICS_IMPORT) {
            IcsImportScreen(
                onBack = { navController.popBackStack() },
                onOpenTerm = { navController.navigate(Routes.TERM) },
                onOpenPeriods = { navController.navigate(Routes.PERIODS) },
            )
        }
    }
}
