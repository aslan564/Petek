package az.petek.app.config

import az.petek.core.security.Secret
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.HexFormat
import java.util.UUID

/** Supplies the identity secret when `PETEK_IDENTITY_SECRET` is left empty. */
fun interface IdentitySecretSource {
    /** @throws ConfigException when no secret can be read or created. */
    fun secret(): Secret
}

/**
 * The machine-local fallback identity secret: `<directory>/identity.secret`, by default `~/.petek/identity.secret`.
 *
 * Test passwords are derived from this secret, so it must stay the same between runs (re-deriving a crashed run's
 * passwords for teardown or login) and must not be guessable. It is created once with 32 bytes from [SecureRandom],
 * stored as hex, readable by the owner only (`600`, directory `700`, where the file system supports POSIX
 * permissions) and never printed. A file that became readable by others is tightened again. Creation is safe against
 * a concurrent process doing the same: the first file to appear wins and both read it.
 */
class IdentitySecretFile(
    private val directory: Path,
    private val random: SecureRandom = SecureRandom(),
) : IdentitySecretSource {
    val path: Path get() = directory.resolve(FILE_NAME)

    override fun secret(): Secret =
        try {
            readExisting() ?: create()
        } catch (e: IOException) {
            throw ConfigException(listOf("cannot read or create the identity secret $path (${e::class.simpleName}: ${e.message})"))
        }

    private fun readExisting(): Secret? {
        val text =
            try {
                Files.readString(path)
            } catch (_: NoSuchFileException) {
                return null
            }
        restrictToOwner(path, FILE_PERMISSIONS)
        val secret = text.trim()
        if (secret.isEmpty()) {
            throw ConfigException(
                listOf(
                    "the identity secret $path is empty; delete it to create a new one " +
                        "(passwords of earlier runs will then change) or set PETEK_IDENTITY_SECRET",
                ),
            )
        }
        return Secret(secret)
    }

    private fun create(): Secret {
        createPrivateDirectory(directory)
        val bytes = ByteArray(SECRET_BYTES).also(random::nextBytes)
        val secret = HexFormat.of().formatHex(bytes)
        val temporary = directory.resolve(".$FILE_NAME.${UUID.randomUUID()}.tmp")
        try {
            createPrivateFile(temporary)
            Files.writeString(temporary, secret + "\n")
            publish(temporary)
        } catch (_: FileAlreadyExistsException) {
            return readExisting() ?: throw IOException("the identity secret disappeared while it was being created")
        } finally {
            Files.deleteIfExists(temporary)
        }
        return Secret(secret)
    }

    /**
     * Makes the complete [temporary] file visible as [path] unless [path] exists: a hard link is created atomically
     * or fails with [FileAlreadyExistsException], so two processes can never both believe their secret won. File
     * systems without hard links fall back to a move, which checks for an existing file first.
     */
    private fun publish(temporary: Path) {
        try {
            Files.createLink(path, temporary)
        } catch (_: UnsupportedOperationException) {
            Files.move(temporary, path)
        }
    }

    private fun createPrivateDirectory(dir: Path) {
        if (Files.isDirectory(dir)) return
        if (supportsPosix(dir)) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS))
        } else {
            Files.createDirectories(dir)
        }
    }

    private fun createPrivateFile(file: Path) {
        if (supportsPosix(directory)) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))
        } else {
            Files.createFile(file)
        }
    }

    /** Best effort: a file owned by someone else cannot be changed, and the secret is still usable. */
    private fun restrictToOwner(
        file: Path,
        permissions: Set<PosixFilePermission>,
    ) {
        if (!supportsPosix(file)) return
        try {
            if (Files.getPosixFilePermissions(file) != permissions) Files.setPosixFilePermissions(file, permissions)
        } catch (_: IOException) {
            // Reading worked, so the secret is available; tightening is only hygiene.
        }
    }

    private fun supportsPosix(path: Path): Boolean {
        val existing = generateSequence(path.toAbsolutePath()) { it.parent }.firstOrNull { Files.exists(it) } ?: return false
        return "posix" in existing.fileSystem.supportedFileAttributeViews()
    }

    companion object {
        const val FILE_NAME = "identity.secret"
        private const val SECRET_BYTES = 32
        private val FILE_PERMISSIONS: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
        private val DIRECTORY_PERMISSIONS: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")

        /** `~/.petek`, where `~` is the `user.home` system property (tests point it at a temporary directory). */
        fun defaultDirectory(): Path = Path.of(System.getProperty("user.home")).resolve(".petek")
    }
}
