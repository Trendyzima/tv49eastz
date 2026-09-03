plugins {
    `java-gradle-plugin`
}

group = "com.tv49eastz.build"
version = "1.0.0"

gradlePlugin {
    plugins {
        create("producerCompositor") {
            id = "com.tv49eastz.producer-compositor"
            implementationClass = "com.tv49eastz.build.ProducerCompositorPlugin"
        }
    }
}
