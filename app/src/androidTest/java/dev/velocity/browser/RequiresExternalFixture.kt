package dev.velocity.browser

/** Excluded from default smoke; explicit fixture/consent arguments are required. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresExternalFixture
