// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.dex;

import com.debughelper.tools.r8.DataResourceProvider;
import com.debughelper.tools.r8.DataResourceProvider.Visitor;
import com.debughelper.tools.r8.dex.Marker;
import com.debughelper.tools.r8.dex.MixedSectionCollection;
import com.debughelper.tools.r8.dex.VirtualFile;
import com.debughelper.tools.r8.DataDirectoryResource;
import com.debughelper.tools.r8.DataEntryResource;
import com.debughelper.tools.r8.DataResourceConsumer;
import com.debughelper.tools.r8.DexIndexedConsumer;
import com.debughelper.tools.r8.ProgramResourceProvider;
import com.debughelper.tools.r8.ResourceException;
import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.graph.DexAnnotation;
import com.debughelper.tools.r8.graph.DexAnnotationDirectory;
import com.debughelper.tools.r8.graph.DexAnnotationSet;
import com.debughelper.tools.r8.graph.DexApplication;
import com.debughelper.tools.r8.graph.DexCode;
import com.debughelper.tools.r8.graph.DexDebugInfo;
import com.debughelper.tools.r8.graph.DexEncodedArray;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.DexTypeList;
import com.debughelper.tools.r8.graph.DexValue;
import com.debughelper.tools.r8.graph.EnclosingMethodAttribute;
import com.debughelper.tools.r8.graph.InnerClassAttribute;
import com.debughelper.tools.r8.graph.ObjectToOffsetMapping;
import com.debughelper.tools.r8.graph.ParameterAnnotationsList;
import com.debughelper.tools.r8.naming.NamingLens;
import com.debughelper.tools.r8.naming.ProguardMapSupplier;
import com.debughelper.tools.r8.utils.DescriptorUtils;
import com.debughelper.tools.r8.utils.ExceptionUtils;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.debughelper.tools.r8.utils.ThreadUtils;
import com.google.common.collect.ObjectArrays;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public class ApplicationWriter {

  public final com.debughelper.tools.r8.graph.DexApplication application;
  public final String deadCode;
  public final com.debughelper.tools.r8.naming.NamingLens namingLens;
  public final String proguardSeedsData;
  public final com.debughelper.tools.r8.utils.InternalOptions options;
  public List<DexString> markerStrings;
  public com.debughelper.tools.r8.DexIndexedConsumer programConsumer;
  public final com.debughelper.tools.r8.naming.ProguardMapSupplier proguardMapSupplier;

  private static class SortAnnotations extends MixedSectionCollection {

    @Override
    public boolean add(com.debughelper.tools.r8.graph.DexAnnotationSet dexAnnotationSet) {
      // Annotation sets are sorted by annotation types.
      dexAnnotationSet.sort();
      return true;
    }

    @Override
    public boolean add(com.debughelper.tools.r8.graph.DexAnnotation annotation) {
      // The elements of encoded annotation must be sorted by name.
      annotation.annotation.sort();
      return true;
    }

    @Override
    public boolean add(DexEncodedArray dexEncodedArray) {
      // Dex values must potentially be sorted, eg, for DexValueAnnotation.
      for (DexValue value : dexEncodedArray.values) {
        value.sort();
      }
      return true;
    }

    @Override
    public boolean add(com.debughelper.tools.r8.graph.DexProgramClass dexClassData) {
      return true;
    }

    @Override
    public boolean add(DexCode dexCode) {
      return true;
    }

    @Override
    public boolean add(DexDebugInfo dexDebugInfo) {
      return true;
    }

    @Override
    public boolean add(DexTypeList dexTypeList) {
      return true;
    }

    @Override
    public boolean add(ParameterAnnotationsList parameterAnnotationsList) {
      return true;
    }

    @Override
    public boolean setAnnotationsDirectoryForClass(com.debughelper.tools.r8.graph.DexProgramClass clazz,
                                                   DexAnnotationDirectory annotationDirectory) {
      return true;
    }
  }

  public ApplicationWriter(
      com.debughelper.tools.r8.graph.DexApplication application,
      com.debughelper.tools.r8.utils.InternalOptions options,
      List<com.debughelper.tools.r8.dex.Marker> markers,
      String deadCode,
      com.debughelper.tools.r8.naming.NamingLens namingLens,
      String proguardSeedsData,
      com.debughelper.tools.r8.naming.ProguardMapSupplier proguardMapSupplier) {
    this(
        application,
        options,
        markers,
        deadCode,
        namingLens,
        proguardSeedsData,
        proguardMapSupplier,
        null);
  }

  public ApplicationWriter(
      com.debughelper.tools.r8.graph.DexApplication application,
      com.debughelper.tools.r8.utils.InternalOptions options,
      List<com.debughelper.tools.r8.dex.Marker> markers,
      String deadCode,
      com.debughelper.tools.r8.naming.NamingLens namingLens,
      String proguardSeedsData,
      com.debughelper.tools.r8.naming.ProguardMapSupplier proguardMapSupplier,
      DexIndexedConsumer consumer) {
    assert application != null;
    this.application = application;
    assert options != null;
    this.options = options;
    if (markers != null && !markers.isEmpty()) {
      this.markerStrings = new ArrayList<>();
      for (Marker marker : markers) {
        this.markerStrings.add(application.dexItemFactory.createString(marker.toString()));
      }
    }
    this.deadCode = deadCode;
    this.namingLens = namingLens;
    this.proguardSeedsData = proguardSeedsData;
    this.proguardMapSupplier = proguardMapSupplier;
    this.programConsumer = consumer;
  }

  private Iterable<com.debughelper.tools.r8.dex.VirtualFile> distribute(ExecutorService executorService)
      throws ExecutionException, IOException {
    // Distribute classes into dex files.
    com.debughelper.tools.r8.dex.VirtualFile.Distributor distributor;
    if (options.isGeneratingDexFilePerClassFile()) {
      distributor = new com.debughelper.tools.r8.dex.VirtualFile.FilePerInputClassDistributor(this);
    } else if (!options.canUseMultidex()
        && options.mainDexKeepRules.isEmpty()
        && application.mainDexList.isEmpty()
        && options.enableMainDexListCheck) {
      distributor = new com.debughelper.tools.r8.dex.VirtualFile.MonoDexDistributor(this, options);
    } else {
      distributor = new com.debughelper.tools.r8.dex.VirtualFile.FillFilesDistributor(this, options, executorService);
    }

    return distributor.run();
  }

  public void write(ExecutorService executorService) throws IOException, ExecutionException {
    application.timing.begin("DexApplication.write");
    try {
      insertAttributeAnnotations();

      application.dexItemFactory.sort(namingLens);
      assert this.markerStrings == null
          || this.markerStrings.isEmpty()
          || application.dexItemFactory.extractMarker() != null;

      SortAnnotations sortAnnotations = new SortAnnotations();
      application.classes().forEach((clazz) -> clazz.addDependencies(sortAnnotations));

      // Collect the indexed items sets for all files and perform JumboString processing.
      // This is required to ensure that shared code blocks have a single and consistent code
      // item that is valid for all dex files.
      // Use a linked hash map as the order matters when addDexProgramData is called below.
      Map<com.debughelper.tools.r8.dex.VirtualFile, Future<com.debughelper.tools.r8.graph.ObjectToOffsetMapping>> offsetMappingFutures = new LinkedHashMap<>();
      for (com.debughelper.tools.r8.dex.VirtualFile newFile : distribute(executorService)) {
        if (!newFile.isEmpty()) {
          offsetMappingFutures
              .put(newFile, executorService.submit(() -> {
                com.debughelper.tools.r8.graph.ObjectToOffsetMapping mapping = newFile.computeMapping(application);
                rewriteCodeWithJumboStrings(mapping, newFile.classes(), application);
                return mapping;
              }));
        }
      }

      // Wait for all spawned futures to terminate to ensure jumbo string writing is complete.
      com.debughelper.tools.r8.utils.ThreadUtils.awaitFutures(offsetMappingFutures.values());

      // Generate the dex file contents.
      List<Future<Boolean>> dexDataFutures = new ArrayList<>();
      try {
        for (VirtualFile virtualFile : offsetMappingFutures.keySet()) {
          assert !virtualFile.isEmpty();
          final com.debughelper.tools.r8.graph.ObjectToOffsetMapping mapping = offsetMappingFutures.get(virtualFile).get();
          dexDataFutures.add(
              executorService.submit(
                  () -> {
                    byte[] result = writeDexFile(mapping);
                    if (programConsumer != null) {
                      programConsumer.accept(
                          virtualFile.getId(),
                          result,
                          virtualFile.getClassDescriptors(),
                          options.reporter);
                    } else if (virtualFile.getPrimaryClassDescriptor() != null) {
                      options
                          .getDexFilePerClassFileConsumer()
                          .accept(
                              virtualFile.getPrimaryClassDescriptor(),
                              result,
                              virtualFile.getClassDescriptors(),
                              options.reporter);
                    } else {
                      options
                          .getDexIndexedConsumer()
                          .accept(
                              virtualFile.getId(),
                              result,
                              virtualFile.getClassDescriptors(),
                              options.reporter);
                    }
                    return true;
                  }));
        }
      } catch (InterruptedException e) {
        throw new RuntimeException("Interrupted while waiting for future.", e);
      }

      // Clear out the map, as it is no longer needed.
      offsetMappingFutures.clear();
      // Wait for all files to be processed before moving on.
      ThreadUtils.awaitFutures(dexDataFutures);
      // Fail if there are pending errors, e.g., the program consumers may have reported errors.
      options.reporter.failIfPendingErrors();
      // Supply info to all additional resource consumers.
      supplyAdditionalConsumers(
          application, namingLens, options, deadCode, proguardMapSupplier, proguardSeedsData);
    } finally {
      application.timing.end();
    }
  }

  public static void supplyAdditionalConsumers(
      com.debughelper.tools.r8.graph.DexApplication application,
      com.debughelper.tools.r8.naming.NamingLens namingLens,
      InternalOptions options,
      String deadCode,
      ProguardMapSupplier proguardMapSupplier,
      String proguardSeedsData) {
    if (options.configurationConsumer != null) {
      com.debughelper.tools.r8.utils.ExceptionUtils.withConsumeResourceHandler(
          options.reporter, options.configurationConsumer,
          options.proguardConfiguration.getParsedConfiguration());
    }
    if (options.usageInformationConsumer != null && deadCode != null) {
      com.debughelper.tools.r8.utils.ExceptionUtils.withConsumeResourceHandler(
          options.reporter, options.usageInformationConsumer, deadCode);
    }
    // Write the proguard map file after writing the dex files, as the map writer traverses
    // the DexProgramClass structures, which are destructively updated during dex file writing.
    if (proguardMapSupplier != null && options.proguardMapConsumer != null) {
      com.debughelper.tools.r8.utils.ExceptionUtils.withConsumeResourceHandler(
          options.reporter, options.proguardMapConsumer, proguardMapSupplier.get());
    }
    if (options.proguardSeedsConsumer != null && proguardSeedsData != null) {
      com.debughelper.tools.r8.utils.ExceptionUtils.withConsumeResourceHandler(
          options.reporter, options.proguardSeedsConsumer, proguardSeedsData);
    }
    if (options.mainDexListConsumer != null) {
      ExceptionUtils.withConsumeResourceHandler(
          options.reporter, options.mainDexListConsumer, writeMainDexList(application, namingLens));
    }
    DataResourceConsumer dataResourceConsumer = options.dataResourceConsumer;
    if (dataResourceConsumer != null) {

      List<com.debughelper.tools.r8.DataResourceProvider> dataResourceProviders = application.programResourceProviders
          .stream()
          .map(ProgramResourceProvider::getDataResourceProvider)
          .filter(Objects::nonNull)
          .collect(Collectors.toList());

      for (com.debughelper.tools.r8.DataResourceProvider dataResourceProvider : dataResourceProviders) {
        try {
          dataResourceProvider.accept(new com.debughelper.tools.r8.DataResourceProvider.Visitor() {
            @Override
            public void visit(DataDirectoryResource directory) {
              dataResourceConsumer.accept(directory, options.reporter);
              options.reporter.failIfPendingErrors();
            }

            @Override
            public void visit(DataEntryResource file) {
              dataResourceConsumer.accept(file, options.reporter);
              options.reporter.failIfPendingErrors();
            }
          });
        } catch (ResourceException e) {
          throw new CompilationError(e.getMessage(), e);
        }
      }
    }
  }

  private void insertAttributeAnnotations() {
    // Convert inner-class attributes to DEX annotations
    for (com.debughelper.tools.r8.graph.DexProgramClass clazz : application.classes()) {
      EnclosingMethodAttribute enclosingMethod = clazz.getEnclosingMethod();
      List<com.debughelper.tools.r8.graph.InnerClassAttribute> innerClasses = clazz.getInnerClasses();
      if (enclosingMethod == null && innerClasses.isEmpty()) {
        continue;
      }

      // EnclosingMember translates directly to an enclosing class/method if present.
      List<com.debughelper.tools.r8.graph.DexAnnotation> annotations = new ArrayList<>(1 + innerClasses.size());
      if (enclosingMethod != null) {
        if (enclosingMethod.getEnclosingMethod() != null) {
          annotations.add(
              com.debughelper.tools.r8.graph.DexAnnotation.createEnclosingMethodAnnotation(
                  enclosingMethod.getEnclosingMethod(), options.itemFactory));
        } else {
          // At this point DEX can't distinguish between local classes and member classes based on
          // the enclosing class annotation itself.
          annotations.add(
              com.debughelper.tools.r8.graph.DexAnnotation.createEnclosingClassAnnotation(
                  enclosingMethod.getEnclosingClass(), options.itemFactory));
        }
      }

      // Each inner-class entry becomes a inner-class (or inner-class & enclosing-class pair) if
      // it relates to the present class. If it relates to the outer-type (and is named) it becomes
      // part of the member-classes annotation.
      if (!innerClasses.isEmpty()) {
        List<com.debughelper.tools.r8.graph.DexType> memberClasses = new ArrayList<>(innerClasses.size());
        for (InnerClassAttribute innerClass : innerClasses) {
          if (clazz.type == innerClass.getInner()) {
            if (enclosingMethod == null
                && (innerClass.getOuter() == null || innerClass.isAnonymous())) {
              options.warningMissingEnclosingMember(
                  clazz.type, clazz.origin, clazz.getClassFileVersion());
            } else {
              annotations.add(
                  com.debughelper.tools.r8.graph.DexAnnotation.createInnerClassAnnotation(
                      innerClass.getInnerName(), innerClass.getAccess(), options.itemFactory));
              if (innerClass.getOuter() != null && innerClass.isNamed()) {
                annotations.add(
                    com.debughelper.tools.r8.graph.DexAnnotation.createEnclosingClassAnnotation(
                        innerClass.getOuter(), options.itemFactory));
              }
            }
          } else if (clazz.type == innerClass.getOuter() && innerClass.isNamed()) {
            memberClasses.add(innerClass.getInner());
          }
        }
        if (!memberClasses.isEmpty()) {
          annotations.add(
              com.debughelper.tools.r8.graph.DexAnnotation.createMemberClassesAnnotation(memberClasses, options.itemFactory));
        }
      }

      if (!annotations.isEmpty()) {
        // Append the annotations to annotations array of the class.
        com.debughelper.tools.r8.graph.DexAnnotation[] copy =
            ObjectArrays.concat(
                clazz.annotations.annotations,
                annotations.toArray(new com.debughelper.tools.r8.graph.DexAnnotation[annotations.size()]),
                DexAnnotation.class);
        clazz.annotations = new DexAnnotationSet(copy);
      }

      // Clear the attribute structures now that they are represented in annotations.
      clazz.clearEnclosingMethod();
      clazz.clearInnerClasses();
    }
  }

  /**
   * Rewrites the code for all methods in the given file so that they use JumboString for at
   * least the strings that require it in mapping.
   * <p>
   * If run multiple times on a class, the lowest index that is required to be a JumboString will
   * be used.
   */
  private void rewriteCodeWithJumboStrings(com.debughelper.tools.r8.graph.ObjectToOffsetMapping mapping,
                                           Collection<com.debughelper.tools.r8.graph.DexProgramClass> classes, com.debughelper.tools.r8.graph.DexApplication application) {
    // Do not bail out early if forcing jumbo string processing.
    if (!options.testing.forceJumboStringProcessing) {
      // If there are no strings with jumbo indices at all this is a no-op.
      if (!mapping.hasJumboStrings()) {
        return;
      }
      // If the globally highest sorting string is not a jumbo string this is also a no-op.
      if (application.highestSortingString != null &&
          application.highestSortingString.slowCompareTo(mapping.getFirstJumboString()) < 0) {
        return;
      }
    }
    // At least one method needs a jumbo string.
    for (DexProgramClass clazz : classes) {
      clazz.forEachMethod(method -> method.rewriteCodeWithJumboStrings(
          mapping, application, options.testing.forceJumboStringProcessing));
    }
  }

  private byte[] writeDexFile(ObjectToOffsetMapping mapping) {
    FileWriter fileWriter = new FileWriter(mapping, application, options, namingLens);
    // Collect the non-fixed sections.
    fileWriter.collect();
    // Generate and write the bytes.
    return fileWriter.generate();
  }

  private static String mapMainDexListName(com.debughelper.tools.r8.graph.DexType type, com.debughelper.tools.r8.naming.NamingLens namingLens) {
    return DescriptorUtils.descriptorToJavaType(namingLens.lookupDescriptor(type).toString())
        .replace('.', '/') + ".class";
  }

  private static String writeMainDexList(DexApplication application, NamingLens namingLens) {
    StringBuilder builder = new StringBuilder();
    List<com.debughelper.tools.r8.graph.DexType> list = new ArrayList<>(application.mainDexList);
    list.sort(DexType::slowCompareTo);
    list.forEach(
        type -> builder.append(mapMainDexListName(type, namingLens)).append('\n'));
    return builder.toString();
  }
}
