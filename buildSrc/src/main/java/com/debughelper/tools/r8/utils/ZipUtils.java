// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.utils;

import static com.debughelper.tools.r8.utils.FileUtils.CLASS_EXTENSION;
import static com.debughelper.tools.r8.utils.FileUtils.DEX_EXTENSION;
import static com.debughelper.tools.r8.utils.FileUtils.MODULE_INFO_CLASS;

import com.debughelper.tools.r8.errors.CompilationError;
import com.google.common.io.ByteStreams;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class ZipUtils {

  public interface OnEntryHandler {
    void onEntry(ZipEntry entry, InputStream input) throws IOException;
  }

  public static void iter(String zipFileStr, OnEntryHandler handler) throws IOException {
    try (ZipFile zipFile = new ZipFile(zipFileStr, StandardCharsets.UTF_8)) {
      final Enumeration<? extends ZipEntry> entries = zipFile.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        try (InputStream entryStream = zipFile.getInputStream(entry)) {
          handler.onEntry(entry, entryStream);
        }
      }
    }
  }

  public static List<File> unzip(String zipFile, File outDirectory) throws IOException {
    return unzip(zipFile, outDirectory, (entry) -> true);
  }

  public static List<File> unzip(String zipFile, File outDirectory, Predicate<ZipEntry> filter)
      throws IOException {
    final Path outDirectoryPath = outDirectory.toPath();
    final List<File> outFiles = new ArrayList<>();
      iter(zipFile, (entry, input) -> {
        String name = entry.getName();
        if (!entry.isDirectory() && filter.test(entry)) {
          if (name.contains("..")) {
            // Protect against malicious archives.
            throw new CompilationError("Invalid entry name \"" + name + "\"");
          }
          Path outPath = outDirectoryPath.resolve(name);
          File outFile = outPath.toFile();
          outFile.getParentFile().mkdirs();
          try (OutputStream output = new FileOutputStream(outFile)) {
            ByteStreams.copy(input, output);
          }
          outFiles.add(outFile);
        }
      });
    return outFiles;
  }

  public static void writeToZipStream(
      ZipOutputStream stream, String entry, byte[] content, int compressionMethod)
      throws IOException {
    writeToZipStream(stream, entry, content, compressionMethod, false);
  }

  public static void writeToZipStream(
      ZipOutputStream stream,
      String entry,
      byte[] content,
      int compressionMethod,
      boolean setZeroTime)
      throws IOException {
    CRC32 crc = new CRC32();
    crc.update(content);
    ZipEntry zipEntry = new ZipEntry(entry);
    zipEntry.setMethod(compressionMethod);
    zipEntry.setSize(content.length);
    zipEntry.setCrc(crc.getValue());
    if (setZeroTime) {
      zipEntry.setTime(0);
    }
    stream.putNextEntry(zipEntry);
    stream.write(content);
    stream.closeEntry();
  }

  public static boolean isDexFile(String entry) {
    String name = entry.toLowerCase();
    return name.endsWith(DEX_EXTENSION);
  }

  public static boolean isClassFile(String entry) {
    String name = entry.toLowerCase();
    if (name.endsWith(MODULE_INFO_CLASS)) {
      return false;
    }
    return name.endsWith(CLASS_EXTENSION);
  }
}
