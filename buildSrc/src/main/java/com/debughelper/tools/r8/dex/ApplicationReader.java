// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.dex;

import static com.debughelper.tools.r8.graph.ClassKind.CLASSPATH;
import static com.debughelper.tools.r8.graph.ClassKind.LIBRARY;
import static com.debughelper.tools.r8.graph.ClassKind.PROGRAM;

import com.debughelper.tools.r8.ProgramResource;
import com.debughelper.tools.r8.ProgramResource.Kind;
import com.debughelper.tools.r8.dex.DexParser;
import com.debughelper.tools.r8.graph.ClassKind;
import com.debughelper.tools.r8.ClassFileResourceProvider;
import com.debughelper.tools.r8.ResourceException;
import com.debughelper.tools.r8.StringResource;
import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.graph.DexApplication;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexClasspathClass;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexLibraryClass;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.JarApplicationReader;
import com.debughelper.tools.r8.graph.JarClassFileReader;
import com.debughelper.tools.r8.graph.LazyLoadedDexApplication;
import com.debughelper.tools.r8.naming.ClassNameMapper;
import com.debughelper.tools.r8.utils.AndroidApiLevel;
import com.debughelper.tools.r8.utils.AndroidApp;
import com.debughelper.tools.r8.utils.ClassProvider;
import com.debughelper.tools.r8.utils.ClasspathClassCollection;
import com.debughelper.tools.r8.utils.DescriptorUtils;
import com.debughelper.tools.r8.utils.DexVersion;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.debughelper.tools.r8.utils.LibraryClassCollection;
import com.debughelper.tools.r8.utils.MainDexList;
import com.debughelper.tools.r8.utils.ProgramClassCollection;
import com.debughelper.tools.r8.utils.StringDiagnostic;
import com.debughelper.tools.r8.utils.ThreadUtils;
import com.debughelper.tools.r8.utils.Timing;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public class ApplicationReader {

  private final com.debughelper.tools.r8.utils.InternalOptions options;
  private final DexItemFactory itemFactory;
  private final com.debughelper.tools.r8.utils.Timing timing;
  private final com.debughelper.tools.r8.utils.AndroidApp inputApp;

  public interface ProgramClassConflictResolver {
    com.debughelper.tools.r8.graph.DexProgramClass resolveClassConflict(com.debughelper.tools.r8.graph.DexProgramClass a, com.debughelper.tools.r8.graph.DexProgramClass b);
  }

  public ApplicationReader(AndroidApp inputApp, InternalOptions options, Timing timing) {
    this.options = options;
    itemFactory = options.itemFactory;
    this.timing = timing;
    this.inputApp = inputApp;
  }

  public com.debughelper.tools.r8.graph.DexApplication read() throws IOException, ExecutionException {
    return read((com.debughelper.tools.r8.StringResource) null);
  }

  public com.debughelper.tools.r8.graph.DexApplication read(com.debughelper.tools.r8.StringResource proguardMap) throws IOException, ExecutionException {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      return read(proguardMap, executor);
    } finally {
      executor.shutdown();
    }
  }

  public final com.debughelper.tools.r8.graph.DexApplication read(ExecutorService executorService)
      throws IOException, ExecutionException {
    return read(null, executorService, com.debughelper.tools.r8.utils.ProgramClassCollection::resolveClassConflictImpl);
  }

  public final com.debughelper.tools.r8.graph.DexApplication read(com.debughelper.tools.r8.StringResource proguardMap, ExecutorService executorService)
      throws IOException, ExecutionException {
    return read(proguardMap, executorService, ProgramClassCollection::resolveClassConflictImpl);
  }

  public final com.debughelper.tools.r8.graph.DexApplication read(
      com.debughelper.tools.r8.StringResource proguardMap,
      ExecutorService executorService,
      ProgramClassConflictResolver resolver)
      throws IOException, ExecutionException {
    timing.begin("DexApplication.read");
    final com.debughelper.tools.r8.graph.LazyLoadedDexApplication.Builder builder =
        com.debughelper.tools.r8.graph.DexApplication.builder(itemFactory, timing, resolver);
    try {
      List<Future<?>> futures = new ArrayList<>();
      // Still preload some of the classes, primarily for two reasons:
      // (a) class lazy loading is not supported for DEX files
      //     now and current implementation of parallel DEX file
      //     loading will be lost with on-demand class loading.
      // (b) some of the class file resources don't provide information
      //     about class descriptor.
      // TODO: try and preload less classes.
      readProguardMap(proguardMap, builder, executorService, futures);
      readMainDexList(builder, executorService, futures);
      ClassReader classReader = new ClassReader(executorService, futures);
      classReader.readSources();
      ThreadUtils.awaitFutures(futures);
      classReader.initializeLazyClassCollection(builder);
      builder.addProgramResourceProviders(inputApp.getProgramResourceProviders());
    } catch (com.debughelper.tools.r8.ResourceException e) {
      throw options.reporter.fatalError(new StringDiagnostic(e.getMessage(), e.getOrigin()));
    } finally {
      timing.end();
    }
    return builder.build();
  }

  private int verifyOrComputeMinApiLevel(int computedMinApiLevel, DexReader dexReader) {
    com.debughelper.tools.r8.utils.DexVersion version = DexVersion.getDexVersion(dexReader.getDexVersion());
    if (options.minApiLevel == com.debughelper.tools.r8.utils.AndroidApiLevel.getDefault().getLevel()) {
      computedMinApiLevel = Math
          .max(computedMinApiLevel, com.debughelper.tools.r8.utils.AndroidApiLevel.getMinAndroidApiLevel(version).getLevel());
    } else if (!version
        .matchesApiLevel(AndroidApiLevel.getAndroidApiLevel(options.minApiLevel))) {
      throw new com.debughelper.tools.r8.errors.CompilationError("Dex file with version '" + version.getIntValue() +
          "' cannot be used with min sdk level '" + options.minApiLevel + "'.");
    }
    return computedMinApiLevel;
  }

  private void readProguardMap(
      com.debughelper.tools.r8.StringResource map,
      com.debughelper.tools.r8.graph.DexApplication.Builder<?> builder,
      ExecutorService executorService,
      List<Future<?>> futures) {
    // Read the Proguard mapping file in parallel with DexCode and DexProgramClass items.
    if (map == null) {
      return;
    }
    futures.add(
        executorService.submit(
            () -> {
              try {
                String content = map.getString();
                builder.setProguardMap(ClassNameMapper.mapperFromString(content));
              } catch (IOException | com.debughelper.tools.r8.ResourceException e) {
                throw new CompilationError("Failure to read proguard map file", e, map.getOrigin());
              }
            }));
  }

  private void readMainDexList(DexApplication.Builder<?> builder, ExecutorService executorService,
                               List<Future<?>> futures) {
    if (inputApp.hasMainDexList()) {
      futures.add(executorService.submit(() -> {
        for (StringResource resource : inputApp.getMainDexListResources()) {
          builder.addToMainDexList(MainDexList.parseList(resource, itemFactory));
        }

        builder.addToMainDexList(
            inputApp.getMainDexClasses()
                .stream()
                .map(clazz -> itemFactory.createType(DescriptorUtils.javaTypeToDescriptor(clazz)))
                .collect(Collectors.toList()));
      }));
    }
  }

  private final class ClassReader {
    private final ExecutorService executorService;
    private final List<Future<?>> futures;

    // We use concurrent queues to collect classes
    // since the classes can be collected concurrently.
    private final Queue<com.debughelper.tools.r8.graph.DexProgramClass> programClasses = new ConcurrentLinkedQueue<>();
    private final Queue<com.debughelper.tools.r8.graph.DexClasspathClass> classpathClasses = new ConcurrentLinkedQueue<>();
    private final Queue<com.debughelper.tools.r8.graph.DexLibraryClass> libraryClasses = new ConcurrentLinkedQueue<>();
    // Jar application reader to share across all class readers.
    private final com.debughelper.tools.r8.graph.JarApplicationReader application = new com.debughelper.tools.r8.graph.JarApplicationReader(options);

    ClassReader(ExecutorService executorService, List<Future<?>> futures) {
      this.executorService = executorService;
      this.futures = futures;
    }

    private <T extends com.debughelper.tools.r8.graph.DexClass> void readDexSources(
            List<com.debughelper.tools.r8.ProgramResource> dexSources, com.debughelper.tools.r8.graph.ClassKind classKind, Queue<T> classes)
        throws IOException, com.debughelper.tools.r8.ResourceException {
      if (dexSources.size() > 0) {
        List<com.debughelper.tools.r8.dex.DexParser> dexParsers = new ArrayList<>(dexSources.size());
        int computedMinApiLevel = options.minApiLevel;
        for (com.debughelper.tools.r8.ProgramResource input : dexSources) {
          DexReader dexReader = new DexReader(input);
          computedMinApiLevel = verifyOrComputeMinApiLevel(computedMinApiLevel, dexReader);
          dexParsers.add(new com.debughelper.tools.r8.dex.DexParser(dexReader, classKind, itemFactory, options.reporter));
        }
        options.minApiLevel = computedMinApiLevel;
        for (com.debughelper.tools.r8.dex.DexParser dexParser : dexParsers) {
          dexParser.populateIndexTables();
        }
        // Read the DexCode items and DexProgramClass items in parallel.
        if (!options.skipReadingDexCode) {
          for (DexParser dexParser : dexParsers) {
            futures.add(executorService.submit(() -> {
              dexParser.addClassDefsTo(
                  classKind.bridgeConsumer(classes::add)); // Depends on Methods, Code items etc.
            }));
          }
        }
      }
    }

    private <T extends com.debughelper.tools.r8.graph.DexClass> void readClassSources(
            List<com.debughelper.tools.r8.ProgramResource> classSources, com.debughelper.tools.r8.graph.ClassKind classKind, Queue<T> classes) {
      com.debughelper.tools.r8.graph.JarClassFileReader reader = new JarClassFileReader(
          application, classKind.bridgeConsumer(classes::add));
      // Read classes in parallel.
      for (com.debughelper.tools.r8.ProgramResource input : classSources) {
        futures.add(
            executorService.submit(
                () -> {
                  try (InputStream is = input.getByteStream()) {
                    reader.read(input.getOrigin(), classKind, is);
                  }
                  // No other way to have a void callable, but we want the IOException from the
                  // previous
                  // line to be wrapped into an ExecutionException.
                  return null;
                }));
      }
    }

    void readSources() throws IOException, ResourceException {
      Collection<com.debughelper.tools.r8.ProgramResource> resources = inputApp.computeAllProgramResources();
      List<com.debughelper.tools.r8.ProgramResource> dexResources = new ArrayList<>(resources.size());
      List<com.debughelper.tools.r8.ProgramResource> cfResources = new ArrayList<>(resources.size());
      for (com.debughelper.tools.r8.ProgramResource resource : resources) {
        if (resource.getKind() == com.debughelper.tools.r8.ProgramResource.Kind.DEX) {
          dexResources.add(resource);
        } else {
          assert resource.getKind() == com.debughelper.tools.r8.ProgramResource.Kind.CF;
          cfResources.add(resource);
        }
      }
      readDexSources(dexResources, com.debughelper.tools.r8.graph.ClassKind.PROGRAM, programClasses);
      readClassSources(cfResources, com.debughelper.tools.r8.graph.ClassKind.PROGRAM, programClasses);
    }

    private <T extends DexClass> com.debughelper.tools.r8.utils.ClassProvider<T> buildClassProvider(com.debughelper.tools.r8.graph.ClassKind classKind,
                                                                                                    Queue<T> preloadedClasses, List<com.debughelper.tools.r8.ClassFileResourceProvider> resourceProviders,
                                                                                                    JarApplicationReader reader) {
      List<com.debughelper.tools.r8.utils.ClassProvider<T>> providers = new ArrayList<>();

      // Preloaded classes.
      if (!preloadedClasses.isEmpty()) {
        providers.add(com.debughelper.tools.r8.utils.ClassProvider.forPreloadedClasses(classKind, preloadedClasses));
      }

      // Class file resource providers.
      for (ClassFileResourceProvider provider : resourceProviders) {
        providers.add(com.debughelper.tools.r8.utils.ClassProvider.forClassFileResources(classKind, provider, reader));
      }

      // Combine if needed.
      if (providers.isEmpty()) {
        return null;
      }
      return providers.size() == 1 ? providers.get(0)
          : com.debughelper.tools.r8.utils.ClassProvider.combine(classKind, providers);
    }

    void initializeLazyClassCollection(LazyLoadedDexApplication.Builder builder) {
      // Add all program classes to the builder.
      for (DexProgramClass clazz : programClasses) {
        builder.addProgramClass(clazz.asProgramClass());
      }

      // Create classpath class collection if needed.
      com.debughelper.tools.r8.utils.ClassProvider<DexClasspathClass> classpathClassProvider = buildClassProvider(com.debughelper.tools.r8.graph.ClassKind.CLASSPATH,
          classpathClasses, inputApp.getClasspathResourceProviders(), application);
      if (classpathClassProvider != null) {
        builder.setClasspathClassCollection(new ClasspathClassCollection(classpathClassProvider));
      }

      // Create library class collection if needed.
      ClassProvider<DexLibraryClass> libraryClassProvider = buildClassProvider(com.debughelper.tools.r8.graph.ClassKind.LIBRARY,
          libraryClasses, inputApp.getLibraryResourceProviders(), application);
      if (libraryClassProvider != null) {
        builder.setLibraryClassCollection(new LibraryClassCollection(libraryClassProvider));
      }
    }
  }
}
