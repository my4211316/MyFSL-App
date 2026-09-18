import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 資料層：Room、DataStore、Repository、完整備份、當機紀錄。
plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "tw.myfsl.app.core.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    // 每個資料庫版本的 schema 都要進版控，MigrationTest 會拿來比對。
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core:domain"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // hilt-android 內建依賴的 AndroidX 版本比 App 用的舊；對齊到 App 的版本，
    // 讓這個模組單獨跑測試時和 App 用同一組函式庫。
    constraints {
        val lifecycle = libs.versions.lifecycle.get()
        implementation("androidx.lifecycle:lifecycle-common:$lifecycle")
        implementation("androidx.lifecycle:lifecycle-runtime:$lifecycle")
        implementation("androidx.lifecycle:lifecycle-viewmodel:$lifecycle")
        implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:$lifecycle")
        implementation("androidx.lifecycle:lifecycle-livedata:$lifecycle")
        implementation("androidx.lifecycle:lifecycle-livedata-core:$lifecycle")
        implementation("androidx.activity:activity:${libs.versions.activityCompose.get()}")
        implementation("androidx.core:core:${libs.versions.coreKtx.get()}")
        implementation("androidx.collection:collection:1.5.0") // Compose BOM 帶的版本
    }
}
