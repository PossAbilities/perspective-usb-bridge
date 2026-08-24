plugins {
    kotlin("jvm") version "2.1.20"
    application
}

repositories { mavenCentral() }
// No toolchain pin: the harness just needs whichever JDK 17+ is running Gradle.
application { mainClass.set("HarnessKt") }

/*
 * The harness compiles the *real* USB/IP sources from the Android app against a
 * handful of android.hardware.usb stubs, so the wire protocol can be exercised
 * on a plain JVM with no device, no Android SDK and no emulator.
 *
 * Only the platform-free files are pulled in; MainActivity and UsbBridgeService
 * need the full framework and are covered by the normal Android build.
 */
val bridgeSources = kotlin.sourceSets["main"].kotlin
bridgeSources.srcDir(layout.projectDirectory.dir("../../android/app/src/main/java"))
bridgeSources.filter.exclude(
    // Need the full Android framework: covered by the normal Android build.
    "**/MainActivity.kt",
    "**/UsbBridgeService.kt",
    "**/MediaCapture.kt",
    "**/MediaBridgeService.kt"
)

tasks.named("run") { description = "Runs the USB/IP protocol conformance harness." }
