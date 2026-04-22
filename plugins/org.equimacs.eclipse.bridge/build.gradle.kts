plugins {
    `java-library`
    id("biz.aQute.bnd.builder")
}

dependencies {
    // OSGi & Eclipse APIs (Provided by runtime)
    compileOnly("org.eclipse.platform:org.eclipse.osgi:3.19.0")
    compileOnly("org.eclipse.platform:org.eclipse.ui:3.203.0")
    compileOnly("org.eclipse.platform:org.eclipse.core.runtime:3.30.0")
    compileOnly("org.eclipse.platform:org.eclipse.debug.core:3.21.100")
    compileOnly("org.eclipse.jdt:org.eclipse.jdt.debug:3.21.300")
    compileOnly("org.eclipse.platform:org.eclipse.core.resources:3.19.0")
    compileOnly("org.eclipse.jdt:org.eclipse.jdt.core:3.38.0")
    
    // CDT
    compileOnly("org.eclipse.cdt:org.eclipse.cdt.debug.core:8.7.0")

    // Dependencies to EMBED in the plugin
    implementation("com.google.code.gson:gson:2.11.0")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.0")
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Configure Bnd to use our existing MANIFEST.MF as a template or generate a new one
// For now, let's let Bnd generate it based on our needs but keep it compatible
tasks.jar {
    manifest {
        attributes(
            "Bundle-SymbolicName" to "org.equimacs.eclipse.bridge",
            "Bundle-Version" to "1.0.0.qualifier",
            "Bundle-Activator" to "org.equimacs.eclipse.bridge.Activator",
            "Bundle-ActivationPolicy" to "eager",
            "Automatic-Module-Name" to "org.equimacs.eclipse.bridge"
        )
    }
}
