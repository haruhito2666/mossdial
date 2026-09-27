package com.mossdial.data

import com.mossdial.server.StaticFiles
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

class WebRootFileStore(root: File) {
    private val rootDirectory: File

    init {
        if (isSymbolicLink(root)) {
            throw SecurityException("The web root must not be a symbolic link")
        }
        rootDirectory = root.canonicalFile
        if (!rootDirectory.exists() && !rootDirectory.mkdirs()) {
            throw IOException("Unable to create the web root")
        }
        if (!Files.isDirectory(rootDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("The web root is not a directory")
        }
    }

    fun resolve(relativePath: String): File? {
        val safePath = safeRelativePath(relativePath) ?: return null
        val candidate = if (safePath.isEmpty()) rootDirectory else File(rootDirectory, safePath)
        val canonical = try {
            candidate.canonicalFile
        } catch (_: IOException) {
            return null
        }
        if (!isWithinRoot(canonical) || hasSymlinkComponent(safePath)) return null
        return canonical
    }

    fun list(relativePath: String = ""): List<ManagedFile> {
        val directory = requireExisting(relativePath)
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalArgumentException("The selected path is not a directory")
        }
        val children = directory.listFiles() ?: throw IOException("Unable to read the directory")
        return children.mapNotNull { child ->
            val relative = relativePathOf(child, canonical = false) ?: return@mapNotNull null
            if (isSymbolicLink(child)) return@mapNotNull null
            val canonical = try {
                child.canonicalFile
            } catch (_: IOException) {
                return@mapNotNull null
            }
            if (!isWithinRoot(canonical)) return@mapNotNull null
            try {
                val attributes = Files.readAttributes(
                    child.toPath(),
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS
                )
                if (attributes.isSymbolicLink) return@mapNotNull null
                ManagedFile(
                    relativePath = relative,
                    name = child.name,
                    isDirectory = attributes.isDirectory,
                    sizeBytes = if (attributes.isDirectory) 0L else attributes.size(),
                    type = if (attributes.isDirectory) "Directory" else StaticFiles.contentType(child),
                    lastModifiedMillis = attributes.lastModifiedTime().toMillis()
                )
            } catch (_: Exception) {
                null
            }
        }.sortedWith(
            compareByDescending<ManagedFile> { it.isDirectory }
                .thenBy { it.name.lowercase(Locale.ROOT) }
                .thenBy { it.name }
        )
    }

    fun createTextFile(
        relativeDirectory: String,
        fileName: String,
        content: String = ""
    ): ManagedFile {
        val directory = requireDirectory(relativeDirectory)
        val name = validateName(fileName)
        val target = File(directory, name)
        ensureTargetSafe(target)
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw java.nio.file.FileAlreadyExistsException(target.toString())
        }
        Files.newOutputStream(
            target.toPath(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        ).use { output ->
            output.write(content.toByteArray(StandardCharsets.UTF_8))
        }
        return describe(target)
    }

    fun createDirectory(relativeDirectory: String, directoryName: String): ManagedFile {
        val parent = requireDirectory(relativeDirectory)
        val name = validateName(directoryName)
        val target = File(parent, name)
        ensureTargetSafe(target)
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw java.nio.file.FileAlreadyExistsException(target.toString())
        }
        Files.createDirectory(target.toPath())
        return describe(target)
    }

    fun importFile(
        relativeDirectory: String,
        fileName: String,
        source: InputStream,
        mimeType: String? = null
    ): ManagedFile {
        val directory = requireDirectory(relativeDirectory)
        val name = validateName(fileName)
        val target = File(directory, name)
        ensureTargetSafe(target)
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw java.nio.file.FileAlreadyExistsException(target.toString())
        }
        val temporary = Files.createTempFile(
            directory.toPath(),
            ".mossdial-import-",
            ".tmp"
        )
        var moved = false
        try {
            Files.newOutputStream(
                temporary,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            ).use { output ->
                source.copyTo(output)
            }
            ensureTargetSafe(target)
            if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw java.nio.file.FileAlreadyExistsException(target.toString())
            }
            Files.move(temporary, target.toPath())
            moved = true
            return describe(target, mimeType)
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(temporary)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun rename(relativePath: String, newName: String): ManagedFile {
        val source = requireExisting(relativePath)
        if (source == rootDirectory) {
            throw IllegalArgumentException("The web root cannot be renamed")
        }
        val parent = source.parentFile ?: throw IOException("The selected item has no parent")
        val name = validateName(newName)
        val destination = File(parent, name)
        ensureTargetSafe(source)
        ensureTargetSafe(destination)
        if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw java.nio.file.FileAlreadyExistsException(destination.toString())
        }
        Files.move(source.toPath(), destination.toPath())
        return describe(destination)
    }

    fun delete(relativePath: String) {
        val target = requireExisting(relativePath)
        if (target == rootDirectory) {
            throw IllegalArgumentException("The web root cannot be deleted")
        }
        deleteTree(target)
    }

    private fun requireExisting(relativePath: String): File {
        val file = resolve(relativePath)
            ?: throw IllegalArgumentException("The selected path is not allowed")
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw FileNotFoundException("The selected item no longer exists")
        }
        return file
    }

    private fun requireDirectory(relativePath: String): File {
        val directory = requireExisting(relativePath)
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalArgumentException("The selected path is not a directory")
        }
        return directory
    }

    private fun describe(file: File, mimeType: String? = null): ManagedFile {
        ensureTargetSafe(file)
        val relative = relativePathOf(file, canonical = false)
            ?: throw SecurityException("The selected path is outside the web root")
        val attributes = Files.readAttributes(
            file.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
        if (attributes.isSymbolicLink) {
            throw SecurityException("Symbolic links are not allowed")
        }
        val isDirectory = attributes.isDirectory
        return ManagedFile(
            relativePath = relative,
            name = file.name,
            isDirectory = isDirectory,
            sizeBytes = if (isDirectory) 0L else attributes.size(),
            type = if (isDirectory) {
                "Directory"
            } else {
                mimeType?.takeIf { it.isNotBlank() } ?: StaticFiles.contentType(file)
            },
            lastModifiedMillis = attributes.lastModifiedTime().toMillis()
        )
    }

    private fun deleteTree(file: File) {
        if (isSymbolicLink(file)) {
            deleteFile(file)
            return
        }
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        val canonical = try {
            file.canonicalFile
        } catch (_: IOException) {
            throw IOException("Unable to resolve the selected item")
        }
        if (!isWithinRoot(canonical)) {
            throw SecurityException("The selected path is outside the web root")
        }
        val attributes = Files.readAttributes(
            file.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
        if (attributes.isSymbolicLink) {
            deleteFile(file)
            return
        }
        if (attributes.isDirectory) {
            val children = file.listFiles()
                ?: if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("Unable to read the selected directory")
                } else {
                    return
                }
            for (child in children) {
                if (relativePathOf(child, canonical = false) == null) {
                    throw SecurityException("A child is outside the web root")
                }
                deleteTree(child)
            }
        }
        deleteFile(file)
    }

    private fun deleteFile(file: File) {
        try {
            Files.deleteIfExists(file.toPath())
        } catch (error: IOException) {
            throw IOException("Unable to delete the selected item", error)
        }
    }

    private fun ensureTargetSafe(target: File) {
        val relative = relativePathOf(target, canonical = false)
            ?: throw SecurityException("The selected path is outside the web root")
        if (hasSymlinkComponent(relative)) {
            throw SecurityException("Symbolic links are not allowed")
        }
        val canonical = try {
            target.canonicalFile
        } catch (_: IOException) {
            throw SecurityException("The selected path cannot be resolved")
        }
        if (!isWithinRoot(canonical)) {
            throw SecurityException("The selected path is outside the web root")
        }
    }

    private fun safeRelativePath(relativePath: String): String? {
        if (relativePath.isEmpty()) return ""
        if (relativePath.startsWith('/') || '\\' in relativePath) return null
        if (relativePath.any { it == '\u0000' || it.isISOControl() }) return null
        val segments = relativePath.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        return relativePath
    }

    private fun hasSymlinkComponent(relativePath: String): Boolean {
        if (relativePath.isEmpty()) return false
        var current = rootDirectory
        for (segment in relativePath.split('/')) {
            current = File(current, segment)
            if (isSymbolicLink(current)) return true
        }
        return false
    }

    private fun relativePathOf(file: File, canonical: Boolean): String? {
        val base = if (canonical) rootDirectory else rootDirectory.absoluteFile
        val target = if (canonical) file.canonicalFile else file.absoluteFile
        val basePath = base.toPath().normalize()
        val targetPath = target.toPath().normalize()
        if (!targetPath.startsWith(basePath)) return null
        val relative = basePath.relativize(targetPath).toString()
            .replace(File.separatorChar, '/')
        return if (relative == ".") "" else relative
    }

    private fun isWithinRoot(file: File): Boolean =
        file.absoluteFile.toPath().normalize().startsWith(rootDirectory.toPath().normalize())

    private fun validateName(name: String): String {
        if (
            name.isBlank() ||
            name.length > 255 ||
            name == "." ||
            name == ".." ||
            '/' in name ||
            '\\' in name ||
            name.any { it == '\u0000' || it.isISOControl() } ||
            name.trim() != name
        ) {
            throw IllegalArgumentException("Invalid file name")
        }
        return name
    }
}

data class ManagedFile(
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val type: String,
    val lastModifiedMillis: Long
)

private fun isSymbolicLink(file: File): Boolean = try {
    Files.isSymbolicLink(file.toPath())
} catch (_: Exception) {
    true
}
