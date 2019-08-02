// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8;

import static com.debughelper.tools.r8.utils.FileUtils.DEX_EXTENSION;

import com.debughelper.tools.r8.utils.ArchiveBuilder;
import com.debughelper.tools.r8.utils.DescriptorUtils;
import com.debughelper.tools.r8.utils.DirectoryBuilder;
import com.debughelper.tools.r8.utils.FileUtils;
import com.debughelper.tools.r8.utils.OutputBuilder;
import com.debughelper.tools.r8.utils.ZipUtils;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Consumer for DEX encoded programs.
 *
 * <p>This consumer receives DEX file content for each Java class-file input.
 */
@KeepForSubclassing
public interface DexFilePerClassFileConsumer extends ProgramConsumer {

  /**
   * Callback to receive DEX data for a single Java class-file input and its companion classes.
   *
   * <p>There is no guaranteed order and files might be written concurrently.
   *
   * <p>The consumer is expected not to throw, but instead report any errors via the diagnostics
   * {@param handler}. If an error is reported via {@param handler} and no exceptions are thrown,
   * then the compiler guaranties to exit with an error.
   *
   * @param primaryClassDescriptor Class descriptor of the class from the input class-file.
   * @param data DEX encoded data.
   * @param descriptors Class descriptors for all classes defined in the DEX data.
   * @param handler Diagnostics handler for reporting.
   */
  void accept(
      String primaryClassDescriptor,
      byte[] data,
      Set<String> descriptors,
      DiagnosticsHandler handler);

  /** Empty consumer to request the production of the resource but ignore its value. */
  static DexFilePerClassFileConsumer emptyConsumer() {
    return ForwardingConsumer.EMPTY_CONSUMER;
  }

  /** Forwarding consumer to delegate to an optional existing consumer. */
  @Keep
  class ForwardingConsumer implements DexFilePerClassFileConsumer {

    private static final DexFilePerClassFileConsumer EMPTY_CONSUMER = new ForwardingConsumer(null);

    private final DexFilePerClassFileConsumer consumer;

    public ForwardingConsumer(DexFilePerClassFileConsumer consumer) {
      this.consumer = consumer;
    }

    @Override
    public DataResourceConsumer getDataResourceConsumer() {
      return consumer != null ? consumer.getDataResourceConsumer() : null;
    }

    @Override
    public void accept(
        String primaryClassDescriptor,
        byte[] data,
        Set<String> descriptors,
        DiagnosticsHandler handler) {
      if (consumer != null) {
        consumer.accept(primaryClassDescriptor, data, descriptors, handler);
      }
    }

    @Override
    public void finished(DiagnosticsHandler handler) {
      if (consumer != null) {
        consumer.finished(handler);
      }
    }
  }

  /** Consumer to write program resources to an output. */
  @Keep
  class ArchiveConsumer extends ForwardingConsumer
      implements DataResourceConsumer, InternalProgramOutputPathConsumer {
    private final OutputBuilder outputBuilder;
    protected final boolean consumeDataResources;

    private static String getDexFileName(String classDescriptor) {
      assert classDescriptor != null && DescriptorUtils.isClassDescriptor(classDescriptor);
      return DescriptorUtils.getClassBinaryNameFromDescriptor(classDescriptor) + DEX_EXTENSION;
    }

    public ArchiveConsumer(Path archive) {
      this(archive, null, false);
    }

    public ArchiveConsumer(Path archive, boolean consumeDataResouces) {
      this(archive, null, consumeDataResouces);
    }

    public ArchiveConsumer(Path archive, DexFilePerClassFileConsumer consumer) {
      this(archive, consumer, false);
    }

    public ArchiveConsumer(Path archive, DexFilePerClassFileConsumer consumer, boolean consumeDataResouces) {
      super(consumer);
      this.outputBuilder = new ArchiveBuilder(archive);
      this.consumeDataResources = consumeDataResouces;
      this.outputBuilder.open();
      if (getDataResourceConsumer() != null) {
        this.outputBuilder.open();
      }
    }

    @Override
    public DataResourceConsumer getDataResourceConsumer() {
      return consumeDataResources ? this : null;
    }

    @Override
    public void accept(
        String primaryClassDescriptor,
        byte[] data,
        Set<String> descriptors,
        DiagnosticsHandler handler) {
      super.accept(primaryClassDescriptor, data, descriptors, handler);
      outputBuilder.addFile(getDexFileName(primaryClassDescriptor), data, handler);
    }

    @Override
    public void accept(DataDirectoryResource directory, DiagnosticsHandler handler) {
      outputBuilder.addDirectory(directory.getName(), handler);
    }

    @Override
    public void accept(DataEntryResource file, DiagnosticsHandler handler) {
      outputBuilder.addFile(file.getName(), file, handler);
    }

    @Override
    public void finished(DiagnosticsHandler handler) {
      super.finished(handler);
      outputBuilder.close(handler);
    }

    @Override
    public Path internalGetOutputPath() {
      return outputBuilder.getPath();
    }

    public static void writeResources(
        Path archive,
        List<ProgramResource> resources,
        Map<Resource, String> primaryClassDescriptors)
        throws IOException, ResourceException {
      OpenOption[] options =
          new OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
      try (Closer closer = Closer.create()) {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive, options))) {
          for (ProgramResource resource : resources) {
            String primaryClassDescriptor = primaryClassDescriptors.get(resource);
            String entryName = getDexFileName(primaryClassDescriptor);
            byte[] bytes = ByteStreams.toByteArray(closer.register(resource.getByteStream()));
            ZipUtils.writeToZipStream(out, entryName, bytes, ZipEntry.STORED);
          }
        }
      }
    }
  }

  /** Directory consumer to write program resources to a directory. */
  @Keep
  class DirectoryConsumer extends ForwardingConsumer
      implements DataResourceConsumer, InternalProgramOutputPathConsumer {
    private final OutputBuilder outputBuilder;
    protected final boolean consumeDataResouces;

    private static String getDexFileName(String classDescriptor) {
      assert classDescriptor != null && DescriptorUtils.isClassDescriptor(classDescriptor);
      return DescriptorUtils.getClassBinaryNameFromDescriptor(classDescriptor) + DEX_EXTENSION;
    }

    public DirectoryConsumer(Path directory) {
      this(directory, null, false);
    }

    public DirectoryConsumer(Path directory, boolean consumeDataResouces) {
      this(directory, null, consumeDataResouces);
    }

    public DirectoryConsumer(Path directory, DexFilePerClassFileConsumer consumer) {
      this(directory, consumer, false);
    }

    public DirectoryConsumer(
        Path directory, DexFilePerClassFileConsumer consumer, boolean consumeDataResouces) {
      super(consumer);
      this.outputBuilder = new DirectoryBuilder(directory);
      this.consumeDataResouces = consumeDataResouces;
    }

    @Override
    public void accept(
        String primaryClassDescriptor,
        byte[] data,
        Set<String> descriptors,
        DiagnosticsHandler handler) {
      super.accept(primaryClassDescriptor, data, descriptors, handler);
      outputBuilder.addFile(getDexFileName(primaryClassDescriptor), data, handler);
    }


    @Override
    public void accept(DataDirectoryResource directory, DiagnosticsHandler handler) {
      outputBuilder.addDirectory(directory.getName(), handler);
    }

    @Override
    public void accept(DataEntryResource file, DiagnosticsHandler handler) {
      outputBuilder.addFile(file.getName(), file, handler);
    }
    @Override
    public void finished(DiagnosticsHandler handler) {
      super.finished(handler);
    }

    @Override
    public Path internalGetOutputPath() {
      return outputBuilder.getPath();
    }

    public static void writeResources(
        Path directory,
        List<ProgramResource> resources,
        Map<Resource, String> primaryClassDescriptors)
        throws IOException, ResourceException {
      try (Closer closer = Closer.create()) {
        for (ProgramResource resource : resources) {
          String primaryClassDescriptor = primaryClassDescriptors.get(resource);
          Path target = getTargetDexFile(directory, primaryClassDescriptor);
          writeFile(ByteStreams.toByteArray(closer.register(resource.getByteStream())), target);
        }
      }
    }

    private static Path getTargetDexFile(Path directory, String primaryClassDescriptor) {
      return directory.resolve(ArchiveConsumer.getDexFileName(primaryClassDescriptor));
    }

    private static void writeFile(byte[] contents, Path target) throws IOException {
      Files.createDirectories(target.getParent());
      FileUtils.writeToFile(target, null, contents);
    }
  }
}
