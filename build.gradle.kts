plugins {
    java
    kotlin("jvm") version "2.0.21" apply false
    id("biz.aQute.bnd.builder") version "7.0.0" apply false
}

allprojects {
    repositories {
        mavenCentral()
        maven { url = uri("https://repo.eclipse.org/content/groups/releases/") }
    }
}
