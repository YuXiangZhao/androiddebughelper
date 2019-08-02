// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8;

import com.debughelper.tools.r8.KeepForSubclassing;
import com.debughelper.tools.r8.ProgramResource;
import com.debughelper.tools.r8.ProgramResource.Kind;
import com.debughelper.tools.r8.ProgramResourceProvider;
import com.debughelper.tools.r8.ResourceException;
import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.origin.ArchiveEntryOrigin;
import com.debughelper.tools.r8.origin.Origin;
import com.debughelper.tools.r8.origin.PathOrigin;
import com.debughelper.tools.r8.utils.DescriptorUtils;
import com.debughelper.tools.r8.utils.ZipUtils;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Provider for archives of program resources. */
@com.debughelper.tools.r8.KeepForSubclassing
public class ArchiveProgramResourceProvider implements ProgramResourceProvider {

  @KeepForSubclassing
  public interface ZipFileSupplier {
    ZipFile open() throws IOException;
  }

  public static boolean includeClassFileEntries(String entry) {
    return ZipUtils.isClassFile(entry);
  }

  public static boolean includeDexEntries(String entry) {
    return ZipUtils.isDexFile(entry);
  }

  public static boolean includeClassFileOrDexEntries(String entry) {
    return ZipUtils.isClassFile(entry) || ZipUtils.isDexFile(entry);
  }

  private final Origin origin;
  private final ZipFileSupplier supplier;
  private final Predicate<String> include;

  public static ArchiveProgramResourceProvider fromArchive(Path archive) {
    return fromArchive(archive, ArchiveProgramResourceProvider::includeClassFileOrDexEntries);
  }

  public static ArchiveProgramResourceProvider fromArchive(
      Path archive, Predicate<String> include) {
    return fromSupplier(
        new PathOrigin(archive),
        () -> new ZipFile(archive.toFile(), StandardCharsets.UTF_8),
        include);
  }

  public static ArchiveProgramResourceProvider fromSupplier(
      Origin origin, ZipFileSupplier supplier) {
    return fromSupplier(
        origin, supplier, ArchiveProgramResourceProvider::includeClassFileOrDexEntries);
  }

  public static ArchiveProgramResourceProvider fromSupplier(
      Origin origin, ZipFileSupplier supplier, Predicate<String> include) {
    return new ArchiveProgramResourceProvider(origin, supplier, include);
  }

  private ArchiveProgramResourceProvider(
      Origin origin, ZipFileSupplier supplier, Predicate<String> include) {
    assert origin != null;
    assert supplier != null;
    assert include != null;
    this.origin = origin;
    this.supplier = supplier;
    this.include = include;
  }

  private List<com.debughelper.tools.r8.ProgramResource> readArchive() throws IOException {
    List<com.debughelper.tools.r8.ProgramResource> dexResources = new ArrayList<>();
    List<com.debughelper.tools.r8.ProgramResource> classResources = new ArrayList<>();
    try (ZipFile zipFile = supplier.open()) {
      final Enumeration<? extends ZipEntry> entries = zipFile.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        try (InputStream stream = zipFile.getInputStream(entry)) {
          String name = entry.getName();
          Origin entryOrigin = new ArchiveEntryOrigin(name, origin);
          if (include.test(name)) {
            if (ZipUtils.isDexFile(name)) {
              dexResources.add(
                  com.debughelper.tools.r8.ProgramResource.fromBytes(
                      entryOrigin, Kind.DEX, ByteStreams.toByteArray(stream), null));
            } else if (ZipUtils.isClassFile(name)) {
              String descriptor = DescriptorUtils.guessTypeDescriptor(name);
              classResources.add(
                  com.debughelper.tools.r8.ProgramResource.fromBytes(
                      entryOrigin,
                      Kind.CF,
                      ByteStreams.toByteArray(stream),
                      Collections.singleton(descriptor)));
            }
          }
        }
      }
    } catch (ZipException e) {
      throw new CompilationError("Zip error while reading archive" + e.getMessage(), e, origin);
    }
    if (!dexResources.isEmpty() && !classResources.isEmpty()) {
      throw new CompilationError(
          "Cannot create debughelper app from an archive containing both DEX and Java-bytecode content",
          origin);
    }
    return !dexResources.isEmpty() ? dexResources : classResources;
  }

  @Override
  public Collection<ProgramResource> getProgramResources() throws com.debughelper.tools.r8.ResourceException {
    try {
      return readArchive();
    } catch (IOException e) {
      throw new ResourceException(origin, e);
    }
  }
}
