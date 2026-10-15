// Compile-only stubs for the hidden HIDL classes (android.os.HwBinder & co). The real classes live
// in the device framework; the root daemon uses them when it runs under app_process.
plugins {
    `java-library`
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
