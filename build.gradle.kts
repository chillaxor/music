plugins {
    id("com.android.application") version "8.6.1" apply false
    // Kotlin 2.4：端侧 LLM 依赖 LiteRT-LM 0.18.x，其 AAR 用 Kotlin 2.4 metadata 编译，
    // 低版本编译器会报 "incompatible version of Kotlin ... expected 2.0.0" 直接编译失败。
    // 兼容性核对：KGP 2.4.x 要求 AGP ≥ 8.5.2（本项目 8.6.1 ✅）、Gradle 7.6.3–9.5.0（本项目 8.9 ✅）。
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
}
