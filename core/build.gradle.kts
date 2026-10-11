plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies { implementation(libs.jsoup); testImplementation(libs.junit) }
tasks.test { useJUnit() }
