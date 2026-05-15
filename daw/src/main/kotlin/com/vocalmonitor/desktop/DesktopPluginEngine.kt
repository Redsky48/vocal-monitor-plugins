package com.vocalmonitor.desktop

import com.vocalmonitor.plugin.VocalMonitorNativePlugin
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin
import java.io.File
import java.net.URLClassLoader
import javax.tools.JavaCompiler
import javax.tools.ToolProvider

/**
 * Desktop counterpart to slim's `NativePluginEngine`.  Same API
 * surface (load → list → newInstance → process) but loads plugins
 * from their **source** (the same `<plugin>.java` files that the
 * Android side ships pre-compiled to `.dex`) — compiled on demand
 * via the JDK's in-process JavaCompiler and loaded through a
 * URLClassLoader parented to the host's classloader.
 *
 * One classloader per loaded plugin so unloading actually releases
 * the class metadata, and so plugin .class files can't accidentally
 * share static state.  The shared `com.vocalmonitor.plugin.*` and
 * `com.vocalmonitor.audio.*` types come from the parent loader so
 * `instanceof VocalMonitorNativePlugin` checks succeed.
 *
 * The repo layout this engine expects:
 *
 *     vocal-monitor-plugins/
 *     ├─ plugins/[category]/[id]/[Name].java         (source)
 *     ├─ scripts/native-stub/com/vocalmonitor/plugin/  (legacy stub copy)
 *     └─ shared/src/main/java/com/vocalmonitor/plugin/  (canonical stubs)
 *
 * Either stub copy works at compile time — the engine looks up the
 * one already on its own classpath at runtime so plugin .java files
 * resolve against the SAME interface bytes the host uses, regardless
 * of which directory their imports nominally point at.
 */
class DesktopPluginEngine(
    /** Repo root.  All other paths derive from here. */
    private val repoRoot: File,
) {
    private val pluginsDir = File(repoRoot, "plugins")
    private val sharedStubsDir = File(repoRoot, "shared/src/main/java")
    private val legacyStubsDir = File(repoRoot, "scripts/native-stub")
    private val buildCache    = File(repoRoot, "daw/build/plugin-classes")

    private val compiler: JavaCompiler =
        ToolProvider.getSystemJavaCompiler()
            ?: error("No JavaCompiler available — DAW needs a JDK, not just a JRE.")

    private data class Loaded(
        val id: String,
        val className: String,
        val klass: Class<out VocalMonitorNativePlugin>,
        val loader: URLClassLoader,
        val fullscreen: Boolean = false,
        val uiKind: String = "canvas",
    )

    private val loaded = mutableMapOf<String, Loaded>()

    /** True when the plugin opted into fullscreen rendering via
     *  `"fullscreen": true` in plugin.json. */
    fun isFullscreen(pluginId: String): Boolean = loaded[pluginId]?.fullscreen == true

    /** UI kind hint from manifest (`canvas` | `spec` | …). */
    fun uiKindOf(pluginId: String): String = loaded[pluginId]?.uiKind ?: "canvas"
    private var sampleRate: Int = 44100

    fun setSampleRate(rate: Int) {
        sampleRate = rate.coerceAtLeast(8000)
    }

    /** All currently-loaded plugin ids, registration order. */
    fun list(): List<String> = loaded.keys.toList()

    fun has(id: String): Boolean = id in loaded

    /**
     * Compile + load one plugin from its source folder and register
     * under [pluginId].  Returns the loaded class on success.
     *
     * [pluginSrcFile] is the `<Name>.java` file inside the plugin
     * folder; [className] is its FQN — both normally come from the
     * plugin's `plugin.json`.
     */
    fun load(
        pluginId: String,
        pluginSrcFile: File,
        className: String,
        fullscreen: Boolean = false,
        uiKind: String = "canvas",
    ): Result<Class<out VocalMonitorNativePlugin>> = runCatching {
        require(pluginSrcFile.isFile) {
            "Plugin source not found: $pluginSrcFile"
        }

        // The plugin compiles against the stub interfaces.  Prefer
        // the canonical shared/ copy; fall back to the legacy
        // scripts/native-stub/ copy (kept for the JEP 330 test-app
        // workflow) if shared/ isn't materialised yet.
        val stubDir = if (sharedStubsDir.isDirectory) sharedStubsDir else legacyStubsDir
        val outDir = File(buildCache, pluginId)
        val classRel = className.replace('.', '/') + ".class"
        val classFile = File(outDir, classRel)

        // Cache hit?  Skip javac when the compiled class is at least
        // as new as the source AND the stub directory.  Stub mtime is
        // cached on the engine so we don't re-walk it 84 times.
        val sourceMtime = pluginSrcFile.lastModified()
        val stubMtime = stubDirLatestMtime(stubDir)
        val freshEnough = classFile.isFile &&
            classFile.lastModified() >= sourceMtime &&
            classFile.lastModified() >= stubMtime

        if (!freshEnough) {
            outDir.deleteRecursively()
            outDir.mkdirs()
            val javac = compiler.getStandardFileManager(null, null, Charsets.UTF_8)
            val sources = listOf(pluginSrcFile) +
                stubDir.walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
            val units = javac.getJavaFileObjectsFromFiles(sources)
            val task = compiler.getTask(
                null, javac, null,
                listOf("--release", "11", "-d", outDir.absolutePath, "-encoding", "UTF-8"),
                null, units,
            )
            val ok = task.call()
            javac.close()
            require(ok) { "javac failed for $pluginId / $className" }
        }

        // URLClassLoader parented to OUR classloader so the loaded
        // plugin resolves com.vocalmonitor.plugin.* against the same
        // interface bytes the engine uses (so instanceof works).
        val loader = URLClassLoader(
            arrayOf(outDir.toURI().toURL()),
            DesktopPluginEngine::class.java.classLoader,
        )
        val raw = loader.loadClass(className)
        require(VocalMonitorNativePlugin::class.java.isAssignableFrom(raw)) {
            "$className does not implement VocalMonitorNativePlugin"
        }
        @Suppress("UNCHECKED_CAST")
        val klass = raw as Class<out VocalMonitorNativePlugin>
        loaded[pluginId]?.loader?.close()
        loaded[pluginId] = Loaded(pluginId, className, klass, loader, fullscreen, uiKind)
        klass
    }

    private var stubMtimeCache: Pair<File, Long>? = null
    private fun stubDirLatestMtime(stubDir: File): Long {
        stubMtimeCache?.let { (dir, mtime) -> if (dir == stubDir) return mtime }
        val m = stubDir.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .map { it.lastModified() }
            .maxOrNull() ?: 0L
        stubMtimeCache = stubDir to m
        return m
    }

    /** Unload + close the per-plugin classloader.  Idempotent. */
    fun unload(pluginId: String) {
        loaded.remove(pluginId)?.loader?.close()
    }

    /** Fresh audio-side instance, ready for process() calls. */
    fun newInstance(pluginId: String): VocalMonitorNativePlugin? {
        val l = loaded[pluginId] ?: return null
        return try {
            val obj = l.klass.getDeclaredConstructor().newInstance()
            obj.init(sampleRate)
            obj
        } catch (_: Throwable) { null }
    }

    /** Visual-side instance (separate from audio per slim's pattern). */
    fun newVisualInstance(pluginId: String): VocalMonitorVisualPlugin? {
        val l = loaded[pluginId] ?: return null
        if (!VocalMonitorVisualPlugin::class.java.isAssignableFrom(l.klass)) return null
        return try {
            val obj = l.klass.getDeclaredConstructor().newInstance()
            obj.init(sampleRate)
            obj as VocalMonitorVisualPlugin
        } catch (_: Throwable) { null }
    }

    /**
     * Pass-through dispatch with optional parameter set.  Mirrors the
     * slim engine's contract — caller treats `false` as "pass dry".
     */
    fun process(
        instance: VocalMonitorNativePlugin,
        input: FloatArray,
        output: FloatArray,
        params: Map<String, Float>,
    ): Boolean = try {
        for ((k, v) in params) instance.setParameter(k, v)
        instance.process(input, output)
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * Convenience: scan `plugins/` for every plugin.json, load each
     * registered plugin.  Useful for a "library" view in the DAW.
     */
    fun loadAllFromRepo(): List<String> {
        val ok = mutableListOf<String>()
        val fails = mutableListOf<Pair<String, String>>()
        val t0 = System.currentTimeMillis()
        println("[DesktopPluginEngine] scanning $pluginsDir (exists=${pluginsDir.isDirectory})")
        var attempted = 0
        var cached = 0
        var compiled = 0
        pluginsDir.listFiles()?.filter { it.isDirectory }?.forEach { catDir ->
            catDir.listFiles()?.filter { it.isDirectory }?.forEach { pluginDir ->
                val manifest = File(pluginDir, "plugin.json")
                if (!manifest.isFile) return@forEach
                val txt = manifest.readText()
                val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(txt)?.groupValues?.get(1)
                    ?: return@forEach
                val engine = Regex("\"engine\"\\s*:\\s*\"([^\"]+)\"").find(txt)?.groupValues?.get(1)
                if (engine != "native") return@forEach
                val className = Regex("\"className\"\\s*:\\s*\"([^\"]+)\"").find(txt)?.groupValues?.get(1)
                    ?: return@forEach
                val javaName = className.substringAfterLast('.') + ".java"
                val src = File(pluginDir, javaName)
                if (!src.isFile) return@forEach
                val fullscreen = Regex("\"fullscreen\"\\s*:\\s*(true|false)")
                    .find(txt)?.groupValues?.get(1) == "true"
                val uiKind = Regex("\"ui_?[Kk]ind\"\\s*:\\s*\"([^\"]+)\"")
                    .find(txt)?.groupValues?.get(1) ?: "canvas"
                attempted++
                // Pre-decide cached vs compile for the log line — load()
                // does the real check, this just classifies it.
                val outDir = File(buildCache, id)
                val classFile = File(outDir, className.replace('.', '/') + ".class")
                val stubDir = if (sharedStubsDir.isDirectory) sharedStubsDir else legacyStubsDir
                val wasCached = classFile.isFile &&
                    classFile.lastModified() >= src.lastModified() &&
                    classFile.lastModified() >= stubDirLatestMtime(stubDir)
                val r = load(id, src, className, fullscreen, uiKind)
                r.onSuccess {
                    ok += id
                    if (wasCached) cached++ else compiled++
                }
                 .onFailure {
                    val msg = it.message ?: it::class.simpleName ?: "?"
                    fails += id to msg
                    println("[DesktopPluginEngine]   $id → FAIL: ${msg.take(200)}")
                 }
            }
        }
        val ms = System.currentTimeMillis() - t0
        println("[DesktopPluginEngine] attempted=$attempted ok=${ok.size} " +
                "(cached=$cached, compiled=$compiled) failed=${fails.size}  in ${ms}ms")
        return ok
    }
}
