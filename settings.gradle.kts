// observo-jvm — JVM-side artifacts of the Kotlin/JVM ↔ Observo bridge (OB-542).
//
// Modules:
//   :observo-gradle-plugin — the `ai.observo` Gradle plugin (OB-548).
//       Build-time only: resolves the platform observo-cli binary and
//       orchestrates it.
//   :observo-manifest — the JVM-side wire model + JSON writer for
//       observo-link-manifest.json (OB-547). The Java mirror of the CLI's
//       manifest.go; the `version` field is the compatibility contract.
//   :observo-junit5 / :observo-testng — runtime listeners (OB-547) that
//       emit the manifest directly from a live run, so `observo jvm push
//       --manifest` and the coverage verdict get result + source_ref the
//       static allure/testng reports can't always carry. Test-runtime only,
//       zero third-party dependencies (hand-rolled JSON) so they can never
//       clash with a client's own Jackson/Allure.
//
// Module directory name == published artifactId, deliberately: the Maven
// coordinates are derived from the Gradle project name, so a module called
// `plugin` would publish as `ai.observo:plugin`.

rootProject.name = "observo-jvm"

include(":observo-gradle-plugin")
include(":observo-manifest")
include(":observo-junit5")
include(":observo-testng")
