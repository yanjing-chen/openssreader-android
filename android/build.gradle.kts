// Versions coordinated for GeckoView 159 and AndroidX Kotlin 2.4 metadata.
plugins {
    id("com.android.application") version "9.1.1" apply false
    // Declared on the root plugin classpath, but NOT applied in the app: AGP 9
    // continues to own built-in Kotlin. Resolve KGP 2.4.20 instead of 2.2.x.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
