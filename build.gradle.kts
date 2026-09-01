import org.gradle.api.artifacts.dsl.LockMode

plugins {
  alias(libs.plugins.android.application) apply false
}

allprojects {
  dependencyLocking {
    lockAllConfigurations()
    lockMode.set(LockMode.STRICT)
  }
}
