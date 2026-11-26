plugins {
    java
    id("net.fabricmc.fabric-loom-remap")
}

extra["minecraftVersion"] = "1.20.1"
extra["fabricApiVersion"] = "0.92.5+1.20.1"
extra["javaVersion"] = 17

apply(from = "../fabric-remapped/adapter.gradle")
