plugins {
    java
    id("net.fabricmc.fabric-loom-remap")
}

extra["minecraftVersion"] = "1.21.11"
extra["fabricApiVersion"] = "0.141.6+1.21.11"
extra["javaVersion"] = 21

apply(from = "../fabric-remapped/adapter.gradle")
