plugins {
    kotlin("jvm") version "2.2.21"
    application
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.jonnyzzz.mcpSteroid.demo.DemoByJonnyzzzKt")
}

kotlin {
    jvmToolchain(21)
}
