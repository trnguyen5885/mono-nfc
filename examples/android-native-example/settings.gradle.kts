val localNfcCoreRepository = providers.gradleProperty("nfcCoreLocalRepo").orNull
    ?.let(::file)
    ?: file("sdk/vppos-nfc-maven")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven(url = uri(localNfcCoreRepository))
        google()
        mavenCentral()
    }
}

rootProject.name = "android-native-example"
include(":app")
