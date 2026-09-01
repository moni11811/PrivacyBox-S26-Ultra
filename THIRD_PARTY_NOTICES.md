# Third-party notices

This inventory is generated from the locked `releaseRuntimeClasspath` used by the unsigned release APK. Artifact SHA-256 values are enforced separately by `gradle/verification-metadata.xml`. All 35 locked runtime components below are licensed under Apache License 2.0 according to their published Maven POMs, except `com.google.guava:listenablefuture:1.0`, whose minimal POM omits a license element; the upstream Guava project declares Apache-2.0.

A copy of the license is provided at [`third_party/licenses/Apache-2.0.txt`](third_party/licenses/Apache-2.0.txt). This inventory records dependency metadata; it is not a legal opinion or a substitute for reviewing upstream notices before publication.

## Locked release runtime components

- `androidx.activity:activity:1.13.0`
- `androidx.annotation:annotation-experimental:1.4.1`
- `androidx.annotation:annotation-jvm:1.9.1`
- `androidx.annotation:annotation:1.9.1`
- `androidx.arch.core:core-common:2.2.0`
- `androidx.arch.core:core-runtime:2.2.0`
- `androidx.collection:collection-jvm:1.4.2`
- `androidx.collection:collection:1.4.2`
- `androidx.compose.runtime:runtime-annotation-android:1.9.0`
- `androidx.compose.runtime:runtime-annotation:1.9.0`
- `androidx.concurrent:concurrent-futures:1.1.0`
- `androidx.core:core-ktx:1.18.0`
- `androidx.core:core-viewtree:1.0.0`
- `androidx.core:core:1.18.0`
- `androidx.interpolator:interpolator:1.0.0`
- `androidx.lifecycle:lifecycle-common:2.6.2`
- `androidx.lifecycle:lifecycle-livedata-core:2.6.2`
- `androidx.lifecycle:lifecycle-runtime:2.6.2`
- `androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.2`
- `androidx.lifecycle:lifecycle-viewmodel:2.6.2`
- `androidx.navigationevent:navigationevent-android:1.0.0`
- `androidx.navigationevent:navigationevent:1.0.0`
- `androidx.profileinstaller:profileinstaller:1.4.0`
- `androidx.savedstate:savedstate:1.2.1`
- `androidx.startup:startup-runtime:1.1.1`
- `androidx.tracing:tracing:1.2.0`
- `androidx.versionedparcelable:versionedparcelable:1.1.1`
- `com.google.guava:listenablefuture:1.0`
- `org.jetbrains.kotlin:kotlin-stdlib:2.2.10`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.9.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0`
- `org.jetbrains:annotations:23.0.0`
- `org.jspecify:jspecify:1.0.0`

## Authoritative upstreams

- AndroidX: <https://android.googlesource.com/platform/frameworks/support/>
- Kotlin and kotlinx.coroutines: <https://github.com/JetBrains/kotlin> and <https://github.com/Kotlin/kotlinx.coroutines>
- Guava/listenablefuture: <https://github.com/google/guava>
- JetBrains annotations: <https://github.com/JetBrains/java-annotations>
- JSpecify: <https://github.com/jspecify/jspecify>

## Build-only material

The source repository also carries the Gradle 9.1.0 wrapper under Apache-2.0; the same license copy applies. Android Gradle Plugin, test libraries, the JDK, GitHub Actions, and Gitleaks are build or hosted-workflow inputs rather than APK runtime components and are not represented as APK dependencies above.
