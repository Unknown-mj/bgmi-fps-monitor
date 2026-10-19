plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

// The project lives in OneDrive, which locks files while syncing and breaks
// Gradle's clean-up. Keep build output outside OneDrive.
allprojects {
    layout.buildDirectory.set(file("${System.getProperty("user.home")}/.fps-monitor-build/${project.name}"))
}
