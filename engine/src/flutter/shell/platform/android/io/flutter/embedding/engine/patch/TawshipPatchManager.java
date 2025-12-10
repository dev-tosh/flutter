// Copyright 2024 Tawship. All rights reserved.
// Modified Flutter engine to support OTA patches

package io.flutter.embedding.engine.patch;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * TawshipPatchManager handles downloading and applying OTA patches for Flutter apps.
 *
 * Patches are ZIP files containing:
 * - lib/<arch>/libapp.so (compiled Dart AOT snapshot)
 * - assets/flutter_assets/... (optional assets)
 *
 * The patched libapp.so is placed in: app_data/tawship/lib/<arch>/libapp.so
 * The FlutterLoader will automatically use this patched version if it exists.
 */
public class TawshipPatchManager {
  private static final String TAG = "TawshipPatchManager";
  private static final String PATCH_DIR = "tawship";
  private static final String LIB_DIR = "lib";
  private static final String ASSETS_DIR = "assets";

  private final Context context;
  private final String appStoragePath;

  public TawshipPatchManager(Context context, String appStoragePath) {
    this.context = context;
    this.appStoragePath = appStoragePath;
  }

  /**
   * Downloads a patch ZIP file from the given URL.
   *
   * @param patchUrl URL to download the patch from
   * @param expectedChecksum Expected SHA-256 checksum (optional, can be null)
   * @param progressCallback Callback for download progress (0-100)
   * @return Path to downloaded patch file
   * @throws IOException If download fails
   * @throws SecurityException If checksum doesn't match
   */
  public String downloadPatch(String patchUrl, String expectedChecksum, ProgressCallback progressCallback)
      throws IOException, SecurityException {
    Log.i(TAG, "Downloading patch from: " + patchUrl);

    File patchDir = new File(appStoragePath, PATCH_DIR);
    if (!patchDir.exists()) {
      patchDir.mkdirs();
    }

    File patchFile = new File(patchDir, "patch_" + System.currentTimeMillis() + ".zip");

    HttpURLConnection connection = null;
    InputStream inputStream = null;
    FileOutputStream outputStream = null;

    try {
      URL url = new URL(patchUrl);
      connection = (HttpURLConnection) url.openConnection();
      connection.setConnectTimeout(30000);
      connection.setReadTimeout(60000);
      connection.connect();

      int responseCode = connection.getResponseCode();
      if (responseCode != HttpURLConnection.HTTP_OK) {
        throw new IOException("Failed to download patch: HTTP " + responseCode);
      }

      int contentLength = connection.getContentLength();
      inputStream = connection.getInputStream();
      outputStream = new FileOutputStream(patchFile);

      byte[] buffer = new byte[8192];
      long totalBytesRead = 0;
      int bytesRead;

      while ((bytesRead = inputStream.read(buffer)) != -1) {
        outputStream.write(buffer, 0, bytesRead);
        totalBytesRead += bytesRead;

        if (progressCallback != null && contentLength > 0) {
          int progress = (int) ((totalBytesRead * 100) / contentLength);
          progressCallback.onProgress(progress);
        }
      }

      outputStream.flush();
      Log.i(TAG, "Patch downloaded successfully: " + patchFile.getAbsolutePath());

      // Verify checksum if provided
      if (expectedChecksum != null && !expectedChecksum.isEmpty()) {
        String actualChecksum = calculateSHA256(patchFile);
        if (!expectedChecksum.equalsIgnoreCase(actualChecksum)) {
          patchFile.delete();
          throw new SecurityException("Patch checksum mismatch. Expected: " + expectedChecksum + ", Got: " + actualChecksum);
        }
        Log.i(TAG, "Patch checksum verified");
      }

      return patchFile.getAbsolutePath();
    } finally {
      if (inputStream != null) {
        try {
          inputStream.close();
        } catch (IOException e) {
          Log.w(TAG, "Error closing input stream", e);
        }
      }
      if (outputStream != null) {
        try {
          outputStream.close();
        } catch (IOException e) {
          Log.w(TAG, "Error closing output stream", e);
        }
      }
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  /**
   * Applies a patch ZIP file by extracting libapp.so and assets.
   *
   * @param patchFilePath Path to the patch ZIP file
   * @param aotLibraryName Name of the AOT library (e.g., "libapp.so")
   * @return true if patch was applied successfully
   * @throws IOException If extraction fails
   */
  public boolean applyPatch(String patchFilePath, String aotLibraryName) throws IOException {
    Log.i(TAG, "Applying patch: " + patchFilePath);

    File patchFile = new File(patchFilePath);
    if (!patchFile.exists()) {
      throw new IOException("Patch file not found: " + patchFilePath);
    }

    // Get device architecture
    String cpuArch = System.getProperty("os.arch");
    String archDir = mapCpuArchToFlutterArch(cpuArch);
    if (archDir == null) {
      throw new IOException("Unknown CPU architecture: " + cpuArch);
    }

    // Create target directories
    File libTargetDir = new File(appStoragePath, PATCH_DIR + File.separator + LIB_DIR + File.separator + archDir);
    File assetsTargetDir = new File(appStoragePath, PATCH_DIR + File.separator + ASSETS_DIR);

    if (!libTargetDir.exists()) {
      libTargetDir.mkdirs();
    }
    if (!assetsTargetDir.exists()) {
      assetsTargetDir.mkdirs();
    }

    // Extract ZIP file
    ZipInputStream zipInputStream = null;
    try {
      zipInputStream = new ZipInputStream(new FileInputStream(patchFile));
      ZipEntry entry;

      while ((entry = zipInputStream.getNextEntry()) != null) {
        String entryName = entry.getName();

        // Extract libapp.so for this architecture
        if (entryName.equals("lib/" + archDir + "/" + aotLibraryName) ||
            entryName.equals("lib/" + archDir + "/libapp.so")) {
          File libappSo = new File(libTargetDir, aotLibraryName);
          extractFile(zipInputStream, libappSo);
          Log.i(TAG, "Extracted libapp.so to: " + libappSo.getAbsolutePath());
        }
        // Extract assets
        else if (entryName.startsWith("assets/")) {
          String relativePath = entryName.substring("assets/".length());
          File assetFile = new File(assetsTargetDir, relativePath);

          // Create parent directories if needed
          File parentDir = assetFile.getParentFile();
          if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
          }

          extractFile(zipInputStream, assetFile);
          Log.d(TAG, "Extracted asset: " + relativePath);
        }

        zipInputStream.closeEntry();
      }

      Log.i(TAG, "Patch applied successfully");
      return true;
    } finally {
      if (zipInputStream != null) {
        try {
          zipInputStream.close();
        } catch (IOException e) {
          Log.w(TAG, "Error closing ZIP stream", e);
        }
      }
    }
  }

  /**
   * Extracts a single file from ZIP input stream to target file.
   */
  private void extractFile(ZipInputStream zipInputStream, File targetFile) throws IOException {
    FileOutputStream outputStream = null;
    try {
      outputStream = new FileOutputStream(targetFile);
      byte[] buffer = new byte[8192];
      int bytesRead;
      while ((bytesRead = zipInputStream.read(buffer)) != -1) {
        outputStream.write(buffer, 0, bytesRead);
      }
      outputStream.flush();
    } finally {
      if (outputStream != null) {
        try {
          outputStream.close();
        } catch (IOException e) {
          Log.w(TAG, "Error closing output stream", e);
        }
      }
    }
  }

  /**
   * Calculates SHA-256 checksum of a file.
   */
  private String calculateSHA256(File file) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (Exception e) {
      throw new IOException("Failed to get SHA-256 instance", e);
    }

    FileInputStream inputStream = new FileInputStream(file);
    byte[] buffer = new byte[8192];
    int bytesRead;

    try {
      while ((bytesRead = inputStream.read(buffer)) != -1) {
        digest.update(buffer, 0, bytesRead);
      }
    } finally {
      inputStream.close();
    }

    byte[] hashBytes = digest.digest();
    StringBuilder sb = new StringBuilder();
    for (byte b : hashBytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  /**
   * Maps CPU architecture to Flutter architecture directory name.
   */
  private String mapCpuArchToFlutterArch(String cpuArch) {
    switch (cpuArch.toLowerCase()) {
      case "aarch64":
        return "arm64-v8a";
      case "armv7l":
      case "arm":
        return "armeabi-v7a";
      case "x86_64":
        return "x86_64";
      case "x86":
        return "x86";
      default:
        Log.w(TAG, "Unmapped CPU architecture: " + cpuArch);
        return null;
    }
  }

  /**
   * Cleans up old patch files.
   */
  public void cleanupOldPatches() {
    File patchDir = new File(appStoragePath, PATCH_DIR);
    File[] patchFiles = patchDir.listFiles((dir, name) -> name.startsWith("patch_") && name.endsWith(".zip"));

    if (patchFiles != null && patchFiles.length > 0) {
      // Keep only the most recent patch file
      File mostRecent = null;
      long mostRecentTime = 0;

      for (File file : patchFiles) {
        long lastModified = file.lastModified();
        if (lastModified > mostRecentTime) {
          mostRecentTime = lastModified;
          mostRecent = file;
        }
      }

      // Delete all except the most recent
      for (File file : patchFiles) {
        if (!file.equals(mostRecent)) {
          file.delete();
        }
      }
    }
  }

  /**
   * Callback interface for download progress.
   */
  public interface ProgressCallback {
    void onProgress(int percent);
  }
}

