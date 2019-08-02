// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.benchmarks;

import static com.debughelper.tools.r8.benchmarks.BenchmarkUtils.printRuntimeNanoseconds;

import com.debughelper.tools.r8.D8Command;
import com.debughelper.tools.r8.D8Command.Builder;
import com.debughelper.tools.r8.DexFilePerClassFileConsumer;
import com.debughelper.tools.r8.DexFilePerClassFileConsumer.ForwardingConsumer;
import com.debughelper.tools.r8.ProgramResource;
import com.debughelper.tools.r8.ProgramResource.Kind;
import com.debughelper.tools.r8.origin.Origin;
import com.debughelper.tools.r8.origin.PathOrigin;
import com.debughelper.tools.r8.utils.DescriptorUtils;
import com.debughelper.tools.r8.utils.ThreadUtils;
import com.debughelper.tools.r8.utils.ZipUtils;
import com.debughelper.tools.r8.ClassFileResourceProvider;
import com.debughelper.tools.r8.CompilationFailedException;
import com.debughelper.tools.r8.CompilationMode;
import com.debughelper.tools.r8.D8;
import com.debughelper.tools.r8.DexIndexedConsumer;
import com.debughelper.tools.r8.DiagnosticsHandler;
import com.debughelper.tools.r8.ProgramConsumer;
import com.debughelper.tools.r8.ResourceException;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

public class FrameworkIncrementalDexingBenchmark {
  private static final int ITERATIONS = 100;
  private static final int API = 24;
  private static final Path JAR_DESUGARED =
      Paths.get("third_party", "framework", "framework_14082017_desugared.jar");
  private static final Path JAR_NOT_DESUGARED =
      Paths.get("third_party", "framework", "framework_14082017.jar");
  private static final Path LIB =
      Paths.get("third_party", "debughelper_jar", "lib-v" + API, "debughelper.jar");

  static class InMemoryClassPathProvider implements ClassFileResourceProvider {
    Origin origin;
    Map<String, byte[]> resources;

    InMemoryClassPathProvider(Path archive) throws IOException {
      origin = new PathOrigin(archive);
      ImmutableMap.Builder<String, byte[]> builder = ImmutableMap.builder();
      ZipUtils.iter(
          archive.toString(),
          (entry, stream) -> {
            String name = entry.getName();
            if (ZipUtils.isClassFile(name)) {
              String descriptor = DescriptorUtils.guessTypeDescriptor(name);
              builder.put(descriptor, ByteStreams.toByteArray(stream));
            }
          });
      resources = builder.build();
    }

    @Override
    public Set<String> getClassDescriptors() {
      return resources.keySet();
    }

    @Override
    public com.debughelper.tools.r8.ProgramResource getProgramResource(String descriptor) {
      byte[] bytes = resources.get(descriptor);
      return bytes == null
          ? null
          : com.debughelper.tools.r8.ProgramResource.fromBytes(
              new EntryOrigin(descriptor, origin),
              com.debughelper.tools.r8.ProgramResource.Kind.CF,
              bytes,
              Collections.singleton(descriptor));
    }
  }

  static class EntryOrigin extends Origin {
    final String descriptor;

    public EntryOrigin(String descriptor, Origin parent) {
      super(parent);
      this.descriptor = descriptor;
    }

    @Override
    public String part() {
      return descriptor;
    }
  }

  private static String title(String title, boolean desugar) {
    return "FrameworkIncremental" + (desugar ? title : "NoDesugar" + title);
  }

  private static void compileAll(
      Path input,
      InMemoryClassPathProvider provider,
      boolean desugar,
      Map<String, com.debughelper.tools.r8.ProgramResource> outputs,
      ExecutorService executor)
      throws IOException, com.debughelper.tools.r8.CompilationFailedException {

    com.debughelper.tools.r8.ProgramConsumer consumer =
        new com.debughelper.tools.r8.DexFilePerClassFileConsumer.ForwardingConsumer(null) {
          @Override
          public synchronized void accept(
              String primaryClassDescriptor,
              byte[] data,
              Set<String> descriptors,
              com.debughelper.tools.r8.DiagnosticsHandler handler) {
            com.debughelper.tools.r8.ProgramResource resource =
                com.debughelper.tools.r8.ProgramResource.fromBytes(Origin.unknown(), com.debughelper.tools.r8.ProgramResource.Kind.DEX, data, descriptors);
            for (String descriptor : descriptors) {
              assert !outputs.containsKey(descriptor);
              if (provider.resources.containsKey(descriptor)) {
                outputs.put(descriptor, resource);
              }
            }
          }
        };

    long start = System.nanoTime();
    com.debughelper.tools.r8.D8.run(
        com.debughelper.tools.r8.D8Command.builder()
            .setMinApiLevel(API)
            .setIntermediate(true)
            .setMode(com.debughelper.tools.r8.CompilationMode.DEBUG)
            .addProgramFiles(input)
            .addLibraryFiles(LIB)
            .setDisableDesugaring(!desugar)
            .setProgramConsumer(consumer)
            .build(),
        executor);
    printRuntimeNanoseconds(title("DexAll", desugar), System.nanoTime() - start);
  }

  private static void compileGroupsOf(
      int count,
      List<String> descriptors,
      InMemoryClassPathProvider provider,
      boolean desugar,
      Map<String, com.debughelper.tools.r8.ProgramResource> outputs,
      ExecutorService executor)
      throws IOException, com.debughelper.tools.r8.CompilationFailedException {
    ProgramConsumer consumer =
        new com.debughelper.tools.r8.DexFilePerClassFileConsumer.ForwardingConsumer(null) {
          @Override
          public synchronized void accept(
              String primaryClassDescriptor,
              byte[] data,
              Set<String> descriptors,
              DiagnosticsHandler handler) {
            com.debughelper.tools.r8.ProgramResource resource =
                com.debughelper.tools.r8.ProgramResource.fromBytes(Origin.unknown(), com.debughelper.tools.r8.ProgramResource.Kind.DEX, data, descriptors);
            for (String descriptor : descriptors) {
              if (provider.resources.containsKey(descriptor)) {
                outputs.put(descriptor, resource);
              }
            }
          }
        };

    descriptors.sort(String::compareTo);
    int increment = descriptors.size() / ITERATIONS;
    long start = System.nanoTime();
    for (int iteration = 0; iteration < ITERATIONS; iteration++) {
      int index = iteration * increment;
      com.debughelper.tools.r8.D8Command.Builder builder =
          com.debughelper.tools.r8.D8Command.builder()
              .setMinApiLevel(API)
              .setIntermediate(true)
              .setMode(com.debughelper.tools.r8.CompilationMode.DEBUG)
              .addClasspathResourceProvider(provider)
              .addLibraryFiles(LIB)
              .setProgramConsumer(consumer)
              .setDisableDesugaring(!desugar);
      for (int j = 0; j < count; j++) {
        builder.addClassProgramData(provider.resources.get(descriptors.get(index + j)),
            Origin.unknown());
      }
      com.debughelper.tools.r8.D8.run(builder.build(), executor);
    }
    printRuntimeNanoseconds(title("DexGroupsOf" + count, desugar), System.nanoTime() - start);
  }

  private static void merge(
          boolean desugar, Map<String, com.debughelper.tools.r8.ProgramResource> outputs, ExecutorService executor)
      throws IOException, com.debughelper.tools.r8.CompilationFailedException, com.debughelper.tools.r8.ResourceException {
    com.debughelper.tools.r8.D8Command.Builder builder =
        com.debughelper.tools.r8.D8Command.builder()
            .setMinApiLevel(API)
            .setIntermediate(false)
            .setMode(CompilationMode.DEBUG)
            .setProgramConsumer(DexIndexedConsumer.emptyConsumer())
            .setDisableDesugaring(true);
    for (com.debughelper.tools.r8.ProgramResource input : outputs.values()) {
      try (InputStream inputStream = input.getByteStream()) {
        builder.addDexProgramData(ByteStreams.toByteArray(inputStream), input.getOrigin());
      }
    }
    long start = System.nanoTime();
    D8.run(
        builder // never need to desugar when merging dex.
            .build(),
        executor);
    printRuntimeNanoseconds(title("DexMerge", desugar), System.nanoTime() - start);
  }

  public static void main(String[] args)
      throws IOException, CompilationFailedException, ResourceException {
    boolean desugar = Arrays.asList(args).contains("--desugar");
    Path input = desugar ? JAR_NOT_DESUGARED : JAR_DESUGARED;
    InMemoryClassPathProvider provider = new InMemoryClassPathProvider(input);
    List<String> descriptors = new ArrayList<>(provider.getClassDescriptors());
    Map<String, com.debughelper.tools.r8.ProgramResource> outputs = new HashMap<>(provider.getClassDescriptors().size());
    int threads = Integer.min(Runtime.getRuntime().availableProcessors(), 16) / 2;
    ExecutorService executor = ThreadUtils.getExecutorService(threads);
    try {
      compileAll(input, provider, desugar, outputs, executor);
      compileGroupsOf(1, descriptors, provider, desugar, outputs, executor);
      compileGroupsOf(10, descriptors, provider, desugar, outputs, executor);
      compileGroupsOf(100, descriptors, provider, desugar, outputs, executor);
      merge(desugar, outputs, executor);
      // TODO: We should run dex2oat to verify the compilation.
    } finally {
      executor.shutdown();
    }
  }
}
