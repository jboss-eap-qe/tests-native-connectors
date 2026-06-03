package org.jboss.connectors.test.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Enumeration;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts a WildFly or EAP distribution ZIP to a per-instance directory
 * under {@code target/native-servers/}.
 *
 * <p>Each worker gets its own extracted copy because tests modify the
 * configuration (Elytron realms, AJP listeners, deployments) and these
 * changes persist in {@code standalone.xml}. The original config is backed up
 * during extraction and restored by {@link WildFlyWorker} before each test.
 *
 * <p>ZIP location priority:
 * <ol>
 *   <li>{@code -Dwildfly.zip.path} system property</li>
 *   <li>{@code WILDFLY_ZIP_PATH} environment variable</li>
 *   <li>Auto-detect from {@code distributions/*.zip}</li>
 * </ol>
 *
 * <p>Post-extraction setup: makes {@code bin/*.sh} executable (POSIX only)
 * and creates a management user ({@code admin/admin}) for Creaper connections.
 */
public final class NativeServerExtractor {

    private static final Logger log = LoggerFactory.getLogger(NativeServerExtractor.class);

    private static final Path NATIVE_SERVERS_DIR = Path.of("target", "native-servers");

    private static final String MGMT_USER = "admin";
    private static final String MGMT_PASSWORD = "admin";

    private NativeServerExtractor() {
    }

    public static Path extract(String instanceName) {
        Path zipPath = getWildFlyZipPath();
        if (zipPath == null || !zipPath.toFile().exists()) {
            throw new RuntimeException("No WildFly ZIP found. Set -Dwildfly.zip.path or "
                    + "place a wildfly-*.zip / jboss-eap-*.zip in distributions/");
        }

        return extractZip(zipPath, instanceName);
    }

    public static Path extractZip(Path zipPath, String instanceName) {
        Path instanceDir = NATIVE_SERVERS_DIR.resolve(instanceName);
        String rootDir = detectZipRootDir(zipPath.toFile());

        if (rootDir == null) {
            throw new RuntimeException("Could not detect root directory in ZIP: " + zipPath);
        }

        Path serverHome = instanceDir.resolve(rootDir);

        if (Files.isDirectory(serverHome.resolve("bin"))) {
            log.info("Reusing existing extraction for '{}': {}", instanceName, serverHome);
            return serverHome;
        }

        log.info("Extracting {} to {} for instance '{}'", zipPath.getFileName(), instanceDir, instanceName);

        try {
            Files.createDirectories(instanceDir);
            unzip(zipPath, instanceDir);
            makeScriptsExecutable(serverHome.resolve("bin"));
            addManagementUser(serverHome);
            backupOriginalConfig(serverHome);
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract WildFly ZIP for '" + instanceName + "'", e);
        }

        log.info("Extraction complete for '{}': {}", instanceName, serverHome);
        return serverHome;
    }

    static Path getWildFlyZipPath() {
        String zipPath = System.getProperty("wildfly.zip.path");
        if (zipPath != null) {
            return Paths.get(zipPath);
        }

        zipPath = System.getenv("WILDFLY_ZIP_PATH");
        if (zipPath != null) {
            return Paths.get(zipPath);
        }

        File distDir = new File("distributions");
        if (distDir.exists() && distDir.isDirectory()) {
            File[] zips = distDir.listFiles((dir, name) ->
                (name.startsWith("wildfly-") && name.endsWith(".zip")) ||
                (name.startsWith("jboss-eap-") && name.endsWith(".zip")));

            if (zips != null && zips.length > 0) {
                return zips[0].toPath();
            }
        }

        return null;
    }

    static String detectZipRootDir(File zipFile) {
        try (ZipFile zf = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            if (entries.hasMoreElements()) {
                String firstName = entries.nextElement().getName();
                int slash = firstName.indexOf('/');
                if (slash > 0) {
                    return firstName.substring(0, slash);
                }
            }
        } catch (IOException e) {
            log.warn("Could not inspect ZIP to detect root directory: {}", e.getMessage());
        }
        return null;
    }

    private static void unzip(Path zipPath, Path targetDir) throws IOException {
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path entryPath = targetDir.resolve(entry.getName()).normalize();

                if (!entryPath.startsWith(targetDir)) {
                    throw new IOException("ZIP entry outside target directory: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (InputStream is = zf.getInputStream(entry)) {
                        Files.copy(is, entryPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    private static void makeScriptsExecutable(Path binDir) {
        if (!Files.isDirectory(binDir)) {
            return;
        }

        try {
            Set<PosixFilePermission> execPerms = PosixFilePermissions.fromString("rwxr-xr-x");
            Files.list(binDir)
                    .filter(p -> p.toString().endsWith(".sh"))
                    .forEach(script -> {
                        try {
                            Files.setPosixFilePermissions(script, execPerms);
                        } catch (UnsupportedOperationException e) {
                            // Windows — .bat scripts don't need +x
                        } catch (IOException e) {
                            log.warn("Failed to make {} executable: {}", script.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to list scripts in {}: {}", binDir, e.getMessage());
        }
    }

    private static void backupOriginalConfig(Path serverHome) throws IOException {
        Path config = serverHome.resolve("standalone/configuration/standalone.xml");
        Path backup = serverHome.resolve("standalone/configuration/standalone.xml.original");
        if (Files.exists(config) && !Files.exists(backup)) {
            Files.copy(config, backup);
            log.info("Backed up original standalone.xml in {}", serverHome);
        }
    }

    private static void addManagementUser(Path serverHome) throws Exception {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String script = isWindows ? "add-user.bat" : "add-user.sh";
        Path scriptPath = serverHome.resolve("bin").resolve(script);

        if (!Files.exists(scriptPath)) {
            log.warn("add-user script not found: {}", scriptPath);
            return;
        }

        CommandResult result = NativeProcessManager.execCommand(
                serverHome.toAbsolutePath(),
                scriptPath.toAbsolutePath().toString(), "-u", MGMT_USER, "-p", MGMT_PASSWORD);

        if (!result.isSuccess()) {
            if (result.getStderr().contains("already exists")) {
                log.debug("Management user '{}' already exists in {}", MGMT_USER, serverHome);
            } else {
                log.warn("add-user failed for '{}' (exit {}): {}",
                        serverHome, result.getExitCode(), result.getStderr());
            }
        } else {
            log.info("Created management user '{}' in {}", MGMT_USER, serverHome);
        }
    }
}
