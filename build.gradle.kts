// Root build. Holds only coordinates shared by every module — each module
// owns its own plugins and dependencies.
//
// Versioning (decided 2026-07-16): observo-jvm versions INDEPENDENTLY of
// observo-cli. The compatibility contract between the two is the `version`
// field inside observo-link-manifest.json (see manifest.go: ManifestVersion),
// not matching release numbers. The CLI ships fast and mostly for non-JVM
// reasons; lock-stepping would publish identical bytes under new numbers and
// imply a compatibility guarantee we do not test.

allprojects {
    group = "ai.observo"
    // Release CI passes -PobservoVersion=<tag without the leading v>.
    version = (findProperty("observoVersion") as String?) ?: "0.1.0-SNAPSHOT"
}
