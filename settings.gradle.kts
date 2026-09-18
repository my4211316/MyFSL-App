pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MyFSL"

// :app          畫面、導覽、Hilt 進入點（Android application）
// :core:data    Room 資料庫、DataStore 設定、Repository、備份（Android library）
// :core:domain  資料模型與所有計算規則，純 Kotlin，不能碰 Android（可在 JVM 直接測試）
include(":app", ":core:data", ":core:domain")
