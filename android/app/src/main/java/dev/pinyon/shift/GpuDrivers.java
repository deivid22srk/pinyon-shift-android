/*
 ******************************************************************************
 * Pinyon Shift - Vulkan GPU driver manager (AdrenoTools packages, libadrenotools)
 ******************************************************************************
 * Ported from the XenDroid/AdrenoTools driver package format (the same ZIPs
 * the community ships for Turnip, e.g. K11MCH1/AdrenoToolsDrivers):
 *
 *   driver.zip
 *    ├── meta.json                 { "libName"|"libraryName", "name",
 *    │                               "author", "packageVersion", "vendor",
 *    │                               "minAPI", ... }
 *    └── lib/ARM64-v8A/libvulkan_*.so  (or the .so at the zip root)
 *
 * The ShiftGlue SDK loads the active driver itself: the native side receives
 * REX_ANDROID_DRIVERS_DIR (the folder returned by driversRoot()) and the
 * REX_ANDROID_GPU_DRIVER environment variable (a folder name below it), then
 * copies that folder into internal storage and opens the driver through
 * libadrenotools, falling back to the system driver when it fails. The folder
 * must therefore hold meta.json with "libraryName" (or exactly one .so) —
 * importZip() writes the meta.json the SDK expects.
 */

package dev.pinyon.shift;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

public final class GpuDrivers {

    private static final String TAG = "PinyonShiftDrivers";

    /** Error with a message ready to show to the player. */
    public static class DriverImportException extends IOException {
        public DriverImportException(String message) {
            super(message);
        }
    }

    /** One installed driver (metadata for the picker UI). */
    public static class InstalledDriver {
        public final String folderName;
        public final String displayName;
        public final String version;
        public final String author;

        InstalledDriver(String folderName, String displayName, String version, String author) {
            this.folderName = folderName;
            this.displayName = displayName;
            this.version = version;
            this.author = author;
        }
    }

    private GpuDrivers() {
    }

    // ---------------------------------------------------------------- paths

    /** The root the native runtime receives as REX_ANDROID_DRIVERS_DIR. */
    public static File driversRoot(Context context) {
        return new File(context.getFilesDir(), "drivers");
    }

    // ------------------------------------------------------------- listing

    /** Installed drivers, most recent first. */
    public static List<InstalledDriver> list(Context context) {
        File[] folders = driversRoot(context).listFiles(File::isDirectory);
        if (folders == null) {
            return Collections.emptyList();
        }
        Arrays.sort(folders, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        List<InstalledDriver> drivers = new ArrayList<>();
        for (File folder : folders) {
            InstalledDriver driver = readDriver(folder);
            if (driver != null) {
                drivers.add(driver);
            }
        }
        return drivers;
    }

    private static InstalledDriver readDriver(File folder) {
        File meta = new File(folder, "meta.json");
        if (!meta.isFile()) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(readText(meta));
            String library = firstNonEmpty(json, "libraryName", "libName");
            if (library.isEmpty()) {
                return null;
            }
            String name = firstNonEmpty(json, "name", "description");
            if (name.isEmpty()) {
                name = library;
            }
            return new InstalledDriver(folder.getName(), name,
                    firstNonEmpty(json, "packageVersion", "version"), firstNonEmpty(json, "author"));
        } catch (Exception e) {
            Log.w(TAG, "Unreadable driver metadata in " + folder, e);
            return null;
        }
    }

    // -------------------------------------------------------------- removal

    public static void remove(Context context, String folderName) {
        // Keep the name sane: only ever delete direct children of the root.
        if (folderName == null || folderName.isEmpty()
                || folderName.contains("/") || folderName.contains("..")) {
            return;
        }
        delete(new File(driversRoot(context), folderName));
    }

    // --------------------------------------------------------------- import

    /**
     * Imports an AdrenoTools driver ZIP (Mesa Turnip and friends) picked
     * through SAF: validates the metadata and the ELF libraries, extracts all
     * .so files (multi-file packages such as adpkg need their companions) and
     * writes the meta.json the native loader reads. Returns the new entry.
     */
    public static InstalledDriver importZip(Context context, Uri zipUri)
            throws DriverImportException {
        File root = driversRoot(context);
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new DriverImportException("Could not create the drivers folder.");
        }

        // Pass 1: entry names + the package metadata.
        List<String> entries = new ArrayList<>();
        String metaJson = null;
        try (InputStream input = context.getContentResolver().openInputStream(zipUri)) {
            if (input == null) {
                throw new DriverImportException("Could not open the selected file.");
            }
            try (ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name == null || entry.isDirectory()) {
                        continue;
                    }
                    if (isMetaJson(name) && metaJson == null) {
                        byte[] bytes = readStream(zip);
                        metaJson = bytes != null ? new String(bytes, "UTF-8") : "";
                    } else {
                        entries.add(name);
                    }
                    zip.closeEntry();
                }
            }
        } catch (DriverImportException e) {
            throw e;
        } catch (ZipException e) {
            throw new DriverImportException("The selected .zip is not a valid archive.");
        } catch (IOException e) {
            throw new DriverImportException("Could not read the selected .zip — pick it again.");
        }

        if (metaJson == null) {
            throw new DriverImportException(
                    "No meta.json in the .zip — this is not an AdrenoTools driver package.");
        }
        JSONObject meta;
        try {
            meta = new JSONObject(metaJson);
        } catch (Exception e) {
            throw new DriverImportException("The driver's meta.json is corrupt.");
        }

        String libraryName = firstNonEmpty(meta, "libName", "libraryName").trim();
        String displayName = firstNonEmpty(meta, "name", "description");
        if (displayName.isEmpty()) {
            displayName = libraryName.isEmpty() ? "GPU driver" : libraryName;
        }
        String version = firstNonEmpty(meta, "packageVersion", "version");
        String author = firstNonEmpty(meta, "author");

        int minApi = meta.optInt("minAPI", meta.optInt("minApi", 0));
        if (minApi > 0 && minApi > Build.VERSION.SDK_INT) {
            throw new DriverImportException("This driver needs Android " + minApi
                    + " (meta.json minAPI); this device runs Android " + Build.VERSION.SDK_INT + ".");
        }

        // Locate the driver library inside the zip. Real packages differ: the
        // .so sits at the root, under lib/ARM64-v8A/, arm64-v8a/ ... Prefer,
        // in order: basename == libName, a vulkan/turnip/freedreno name, the
        // first .so seen.
        List<String> soEntries = new ArrayList<>();
        for (String entry : entries) {
            if (entry.toLowerCase().endsWith(".so")) {
                soEntries.add(entry);
            }
        }
        if (soEntries.isEmpty()) {
            throw new DriverImportException("No .so library in the .zip — the driver is broken.");
        }
        String mainEntry = null;
        if (!libraryName.isEmpty()) {
            for (String entry : soEntries) {
                if (basename(entry).equalsIgnoreCase(libraryName)) {
                    mainEntry = entry;
                    break;
                }
            }
            if (mainEntry == null) {
                throw new DriverImportException("Driver library '" + libraryName
                        + "' was not found in the .zip.");
            }
        }
        if (mainEntry == null) {
            for (String entry : soEntries) {
                String lower = basename(entry).toLowerCase();
                if (lower.contains("vulkan") || lower.contains("turnip")
                        || lower.contains("freedreno")) {
                    mainEntry = entry;
                    break;
                }
            }
        }
        if (mainEntry == null) {
            mainEntry = soEntries.get(0);
        }
        final String mainBasename = basename(mainEntry);
        if (libraryName.isEmpty()) {
            libraryName = mainBasename;
        }

        // Pass 2: extract every .so (original basenames — companion libraries
        // are resolved by DT_NEEDED in the same folder), validating the ELF
        // headers. Extract to a temp folder first so a failed import leaves
        // nothing behind.
        String id = newFolderId(displayName, version);
        File staging = new File(root, ".import_" + id);
        delete(staging);
        if (!staging.mkdirs()) {
            throw new DriverImportException("Could not stage the driver import.");
        }
        File destination = new File(root, id);
        try (InputStream input = context.getContentResolver().openInputStream(zipUri)) {
            if (input == null) {
                throw new DriverImportException("Could not open the selected file.");
            }
            try (ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name == null || entry.isDirectory()
                            || !name.toLowerCase().endsWith(".so")) {
                        continue;
                    }
                    File target = new File(staging, basename(name));
                    copyElfValidating(zip, target, basename(name).equals(mainBasename));
                    zip.closeEntry();
                }
            }
            if (!new File(staging, mainBasename).isFile()) {
                throw new DriverImportException(
                        "The main library (" + mainBasename + ") was not extracted — broken zip.");
            }
            // The metadata the native loader reads ("libraryName") plus the
            // fields this screen displays.
            JSONObject stored = new JSONObject();
            stored.put("libraryName", libraryName);
            stored.put("name", displayName);
            if (!version.isEmpty()) {
                stored.put("packageVersion", version);
            }
            if (!author.isEmpty()) {
                stored.put("author", author);
            }
            writeText(new File(staging, "meta.json"), stored.toString(2));
            delete(destination);
            if (!staging.renameTo(destination)) {
                throw new DriverImportException("Could not finalize the driver import.");
            }
        } catch (DriverImportException e) {
            delete(staging);
            throw e;
        } catch (Exception e) {
            Log.w(TAG, "Driver import failed", e);
            delete(staging);
            throw new DriverImportException("Import failed — check free space and try again.");
        }
        return new InstalledDriver(id, displayName, version, author);
    }

    /**
     * Copies one .so from the zip, validating the ELF header: the magic
     * always; for the main library also ELF64, EM_AARCH64 (183) and ET_DYN
     * (3) — x86_64 drivers or relocatable objects used to import fine and
     * fail at boot without a useful message.
     */
    private static void copyElfValidating(InputStream zip, File destination, boolean validateArch)
            throws IOException, DriverImportException {
        byte[] header = new byte[20];
        int read = 0;
        while (read < header.length) {
            int n = zip.read(header, read, header.length - read);
            if (n < 0) {
                throw new DriverImportException("The driver library is truncated.");
            }
            read += n;
        }
        if (!(header[0] == 0x7F && header[1] == 'E' && header[2] == 'L' && header[3] == 'F')) {
            throw new DriverImportException(
                    "The library inside the .zip is not an ELF binary (incompatible driver).");
        }
        if (validateArch) {
            int elfClass = header[4] & 0xFF;                                   // 2 = ELF64
            int eType = ((header[17] & 0xFF) << 8) | (header[16] & 0xFF);      // 3 = ET_DYN
            int eMachine = ((header[19] & 0xFF) << 8) | (header[18] & 0xFF);   // 183 = AARCH64
            if (elfClass != 2 || eMachine != 183 || eType != 3) {
                throw new DriverImportException(
                        "The main library is not a shared arm64-v8a ELF (class=" + elfClass
                                + " machine=" + eMachine + " type=" + eType
                                + ") — incompatible driver.");
            }
        }
        try (FileOutputStream out = new FileOutputStream(destination)) {
            out.write(header);
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = zip.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        }
        // The loader refuses non-executable library files.
        if (!destination.setReadable(true, true) || !destination.setExecutable(true, true)) {
            Log.i(TAG, "Could not adjust permissions on " + destination);
        }
    }

    // -------------------------------------------------------------- helpers

    private static boolean isMetaJson(String name) {
        String lower = name.toLowerCase();
        return lower.equals("meta.json") || lower.endsWith("/meta.json");
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String firstNonEmpty(JSONObject json, String... keys) {
        for (String key : keys) {
            String value = json.optString(key, "");
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String newFolderId(String name, String version) {
        String stamp = Long.toString(System.currentTimeMillis(), 36);
        StringBuilder slug = new StringBuilder();
        for (char c : name.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                slug.append(c);
            }
            if (slug.length() >= 16) {
                break;
            }
        }
        if (slug.length() == 0) {
            slug.append("driver");
        }
        StringBuilder cleanVersion = new StringBuilder();
        if (version != null) {
            for (char c : version.toCharArray()) {
                if (Character.isLetterOrDigit(c) || c == '.') {
                    cleanVersion.append(c);
                }
            }
        }
        return slug + "_" + cleanVersion + "_" + stamp;
    }

    private static String readText(File file) throws IOException {
        return new String(readBytes(file), "UTF-8");
    }

    private static byte[] readBytes(File file) throws IOException {
        try (InputStream in = new java.io.FileInputStream(file)) {
            return readStream(in);
        }
    }

    private static byte[] readStream(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static void writeText(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes("UTF-8"));
        }
    }

    private static void delete(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    delete(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
