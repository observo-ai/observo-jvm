// observo-jvm — JVM-side artifacts of the Kotlin/JVM ↔ Observo bridge (OB-542).
//
// Modules:
//   :observo-gradle-plugin — the `ai.observo` Gradle plugin (OB-548).
//       Build-time only: resolves the platform observo-cli binary and
//       orchestrates it.
//
// The runtime listener artifacts (`observo-junit5` / `observo-testng`,
// OB-547) land here as sibling modules once built. They are deliberately
// absent for now: per OB-547 they are not a pilot blocker — the CLI already
// derives the link manifest from allure-results / testng-results.xml.
//
// Module directory name == published artifactId, deliberately: the Maven
// coordinates are derived from the Gradle project name, so a module called
// `plugin` would publish as `ai.observo:plugin`.

rootProject.name = "observo-jvm"

include(":observo-gradle-plugin")
