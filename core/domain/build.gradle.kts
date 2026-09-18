import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 資料模型與計算規則：純 Kotlin，不依賴 Android、Room 或 UI。
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 情境變動以 JSON 保存，模型上有 @Serializable。
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
