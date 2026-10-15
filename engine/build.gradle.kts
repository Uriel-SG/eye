plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// SCA/licenze: questo e' org.bouncycastle:bcprov-jdk18on (versione nel version catalog, `bouncycastle`),
// RIPACCHETTATO qui (solo .class, nome file bcprov-slim.jar) e quindi invisibile agli scanner di dipendenze:
// va incluso a mano nel perimetro SCA (scansionare la configurazione `bcSource`). Licenza: vedi NOTICE.
// bcprov contiene ~1,2 MB di risorse non-classe (tabelle PQC) che R8 non elimina: usiamo solo Ed25519,
// quindi si ripacchettano le sole classi, senza pqc/ e senza META-INF.
val bcSource: Configuration by configurations.creating { isCanBeConsumed = false }
val slimBouncyCastle = tasks.register<Jar>("slimBouncyCastle") {
    archiveBaseName.set("bcprov-slim")
    destinationDirectory.set(layout.buildDirectory.dir("slim"))
    from(provider { bcSource.map { zipTree(it) } }) {
        include("org/bouncycastle/**/*.class")
        exclude("org/bouncycastle/pqc/**", "META-INF/**")
    }
    includeEmptyDirs = false
}

dependencies {
    bcSource(libs.bouncycastle.bcprov)
    implementation(libs.kotlinx.serialization.json)
    implementation(files(slimBouncyCastle))
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // Il test corpus legge ../corpus/*.jsonl relativo alla directory del modulo.
    workingDir = projectDir
    testLogging { showStandardStreams = true }
}
