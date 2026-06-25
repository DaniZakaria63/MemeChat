import org.gradle.api.Project
import java.io.File
import java.util.Properties

object Secrets {
    private val props = Properties()

    private fun load(rootDir: File) {
        if (props.isNotEmpty()) return
        listOf("secrets.properties", "keystore.properties").forEach { name ->
            val f = File(rootDir, name)
            if (f.exists()) {
                f.inputStream().use { props.load(it) }
            }
        }
    }

    private fun gitRevisionCount(): Int = try {
        Runtime.getRuntime().exec("git rev-list --count HEAD")
            .inputStream.bufferedReader().readText().trim().toInt()
    } catch (e: Exception) {
        1
    }

    fun get(project: Project, name: String): String {
        load(project.rootProject.projectDir)
        return System.getenv(name)
            ?: props.getProperty(name)
            ?: project.properties[name]?.toString()?.removeSurrounding("\"")
            ?: ""
    }

    fun versionCode(project: Project): Int =
        (project.findProperty("appVersionCode") as? String)?.toIntOrNull()
            ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
            ?: gitRevisionCount()

    fun versionName(project: Project): String =
        (project.findProperty("appVersionName") as? String)
            ?: System.getenv("APP_VERSION_NAME")
            ?: "0.1.${gitRevisionCount()}"
}
