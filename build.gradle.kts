// Top-level build file. 版本統一在 gradle/libs.versions.toml 管理。
// 子模組用到的 com.android.library 與 org.jetbrains.kotlin.jvm 分別和
// com.android.application、org.jetbrains.kotlin.android 在同一個 plugin 套件裡，這裡載入後子模組直接用 id 套用。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
