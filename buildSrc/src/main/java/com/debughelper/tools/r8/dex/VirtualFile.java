// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.dex;

import com.debughelper.tools.r8.dex.IndexedItemCollection;
import com.debughelper.tools.r8.errors.InternalCompilerError;
import com.debughelper.tools.r8.errors.MainDexOverflow;
import com.debughelper.tools.r8.graph.DexApplication;
import com.debughelper.tools.r8.graph.DexCallSite;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexItem;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexMethodHandle;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexProto;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.ObjectToOffsetMapping;
import com.debughelper.tools.r8.naming.ClassNameMapper;
import com.debughelper.tools.r8.naming.NamingLens;
import com.debughelper.tools.r8.utils.DescriptorUtils;
import com.debughelper.tools.r8.utils.FileUtils;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.debughelper.tools.r8.utils.Reporter;
import com.debughelper.tools.r8.utils.StringDiagnostic;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.Predicate;

public class VirtualFile {

  // The fill strategy determine how to distribute classes into dex files.
  enum FillStrategy {
    // Distribute classes in as few dex files as possible filling each dex file as much as possible.
    FILL_MAX,
    // Distribute classes keeping some space for future growth. This is mainly useful together with
    // the package map distribution.
    LEAVE_SPACE_FOR_GROWTH,
    // TODO(sgjesse): Does "minimal main dex" combined with "leave space for growth" make sense?
  }

  public static final int MAX_ENTRIES = Constants.U16BIT_MAX + 1;

  /**
   * When distributing classes across files we aim to leave some space. The amount of space left is
   * driven by this constant.
   */
  private static final int MAX_PREFILL_ENTRIES = MAX_ENTRIES - 5000;

  private final int id;
  private final VirtualFileIndexedItemCollection indexedItems;
  private final IndexedItemTransaction transaction;

  private final com.debughelper.tools.r8.graph.DexProgramClass primaryClass;

  VirtualFile(int id, com.debughelper.tools.r8.naming.NamingLens namingLens) {
    this(id, namingLens, null);
  }

  private VirtualFile(int id, com.debughelper.tools.r8.naming.NamingLens namingLens, com.debughelper.tools.r8.graph.DexProgramClass primaryClass) {
    this.id = id;
    this.indexedItems = new VirtualFileIndexedItemCollection(namingLens);
    this.transaction = new IndexedItemTransaction(indexedItems, namingLens);
    this.primaryClass = primaryClass;
  }

  public int getId() {
    return id;
  }

  public Set<String> getClassDescriptors() {
    Set<String> classDescriptors = new HashSet<>();
    for (com.debughelper.tools.r8.graph.DexProgramClass clazz : indexedItems.classes) {
      boolean added = classDescriptors.add(clazz.type.descriptor.toString());
      assert added;
    }
    return classDescriptors;
  }

  public String getPrimaryClassDescriptor() {
    return primaryClass == null ? null : primaryClass.type.descriptor.toString();
  }

  public static String deriveCommonPrefixAndSanityCheck(List<String> fileNames) {
    Iterator<String> nameIterator = fileNames.iterator();
    String first = nameIterator.next();
    if (!first.toLowerCase().endsWith(com.debughelper.tools.r8.utils.FileUtils.DEX_EXTENSION)) {
      throw new RuntimeException("Illegal suffix for dex file: `" + first + "`.");
    }
    String prefix = first.substring(0, first.length() - com.debughelper.tools.r8.utils.FileUtils.DEX_EXTENSION.length());
    int index = 2;
    while (nameIterator.hasNext()) {
      String next = nameIterator.next();
      if (!next.toLowerCase().endsWith(com.debughelper.tools.r8.utils.FileUtils.DEX_EXTENSION)) {
        throw new RuntimeException("Illegal suffix for dex file: `" + first + "`.");
      }
      if (!next.startsWith(prefix)) {
        throw new RuntimeException("Input filenames lack common prefix.");
      }
      String numberPart =
          next.substring(prefix.length(), next.length() - FileUtils.DEX_EXTENSION.length());
      if (Integer.parseInt(numberPart) != index++) {
        throw new RuntimeException("DEX files are not numbered consecutively.");
      }
    }
    return prefix;
  }

  private static Map<com.debughelper.tools.r8.graph.DexProgramClass, String> computeOriginalNameMapping(
      Collection<com.debughelper.tools.r8.graph.DexProgramClass> classes,
      ClassNameMapper proguardMap) {
    Map<com.debughelper.tools.r8.graph.DexProgramClass, String> originalNames = new HashMap<>();
    classes.forEach((com.debughelper.tools.r8.graph.DexProgramClass c) ->
        originalNames.put(c,
            DescriptorUtils.descriptorToJavaType(c.type.toDescriptorString(), proguardMap)));
    return originalNames;
  }

  private static String extractPrefixToken(int prefixLength, String className, boolean addStar) {
    int index = 0;
    int lastIndex = 0;
    int segmentCount = 0;
    while (lastIndex != -1 && segmentCount++ < prefixLength) {
      index = lastIndex;
      lastIndex = className.indexOf('.', index + 1);
    }
    String prefix = className.substring(0, index);
    if (addStar && segmentCount >= prefixLength) {
      // Full match, add a * to also match sub-packages.
      prefix += ".*";
    }
    return prefix;
  }

  public com.debughelper.tools.r8.graph.ObjectToOffsetMapping computeMapping(com.debughelper.tools.r8.graph.DexApplication application) {
    assert transaction.isEmpty();
    return new ObjectToOffsetMapping(
        application,
        indexedItems.classes,
        indexedItems.protos,
        indexedItems.types,
        indexedItems.methods,
        indexedItems.fields,
        indexedItems.strings,
        indexedItems.callSites,
        indexedItems.methodHandles);
  }

  void addClass(com.debughelper.tools.r8.graph.DexProgramClass clazz) {
    transaction.addClassAndDependencies(clazz);
  }

  public boolean isFull(int maxEntries) {
    return (transaction.getNumberOfMethods() > maxEntries)
        || (transaction.getNumberOfFields() > maxEntries);
  }

  boolean isFull() {
    return isFull(MAX_ENTRIES);
  }

  public int getNumberOfMethods() {
    return transaction.getNumberOfMethods();
  }

  public int getNumberOfFields() {
    return transaction.getNumberOfFields();
  }

  void throwIfFull(boolean hasMainDexList, Reporter reporter) {
    if (!isFull()) {
      return;
    }
    throw reporter.fatalError(
        new MainDexOverflow(
            hasMainDexList,
            transaction.getNumberOfMethods(),
            transaction.getNumberOfFields(),
            MAX_ENTRIES));
  }

  private boolean isFilledEnough(FillStrategy fillStrategy) {
    return isFull(fillStrategy == FillStrategy.FILL_MAX ? MAX_ENTRIES : MAX_PREFILL_ENTRIES);
  }

  public void abortTransaction() {
    transaction.abort();
  }

  public void commitTransaction() {
    transaction.commit();
  }

  public boolean isEmpty() {
    return indexedItems.classes.isEmpty();
  }

  public Collection<com.debughelper.tools.r8.graph.DexProgramClass> classes() {
    return indexedItems.classes;
  }

  public abstract static class Distributor {
    protected final DexApplication application;
    protected final ApplicationWriter writer;
    protected final List<VirtualFile> virtualFiles = new ArrayList<>();

    Distributor(ApplicationWriter writer) {
      this.application = writer.application;
      this.writer = writer;
    }

    public abstract Collection<VirtualFile> run() throws ExecutionException, IOException;
  }

  /**
   * Distribute each type to its individual virtual except for types synthesized during this
   * compilation. Synthesized classes are emitted in the individual virtual files
   * of the input classes they were generated from. Shared synthetic classes
   * may then be distributed in several individual virtual files.
   */
  public static class FilePerInputClassDistributor extends Distributor {

    FilePerInputClassDistributor(ApplicationWriter writer) {
      super(writer);
    }

    @Override
    public Collection<VirtualFile> run() {
      HashMap<com.debughelper.tools.r8.graph.DexProgramClass, VirtualFile> files = new HashMap<>();
      Collection<com.debughelper.tools.r8.graph.DexProgramClass> synthetics = new ArrayList<>();
      // Assign dedicated virtual files for all program classes.
      for (com.debughelper.tools.r8.graph.DexProgramClass clazz : application.classes()) {
        if (clazz.getSynthesizedFrom().isEmpty()) {
          VirtualFile file = new VirtualFile(virtualFiles.size(), writer.namingLens, clazz);
          virtualFiles.add(file);
          file.addClass(clazz);
          files.put(clazz, file);
          // Commit this early, so that we do not keep the transaction state around longer than
          // needed and clear the underlying sets.
          file.commitTransaction();
        } else {
          synthetics.add(clazz);
        }
      }
      for (com.debughelper.tools.r8.graph.DexProgramClass synthetic : synthetics) {
        for (com.debughelper.tools.r8.graph.DexProgramClass inputType : synthetic.getSynthesizedFrom()) {
          VirtualFile file = files.get(inputType);
          file.addClass(synthetic);
          file.commitTransaction();
        }
      }
      return virtualFiles;
    }
  }

  public abstract static class DistributorBase extends Distributor {
    protected Set<com.debughelper.tools.r8.graph.DexProgramClass> classes;
    protected Map<com.debughelper.tools.r8.graph.DexProgramClass, String> originalNames;
    protected final VirtualFile mainDexFile;
    protected final com.debughelper.tools.r8.utils.InternalOptions options;

    DistributorBase(ApplicationWriter writer, com.debughelper.tools.r8.utils.InternalOptions options) {
      super(writer);
      this.options = options;

      // Create the primary dex file. The distribution will add more if needed.
      mainDexFile = new VirtualFile(0, writer.namingLens);
      assert virtualFiles.isEmpty();
      virtualFiles.add(mainDexFile);
      if (writer.markerStrings != null && !writer.markerStrings.isEmpty()) {
        for (com.debughelper.tools.r8.graph.DexString markerString : writer.markerStrings) {
          mainDexFile.transaction.addString(markerString);
        }
        mainDexFile.commitTransaction();
      }

      classes = Sets.newHashSet(application.classes());
      originalNames = computeOriginalNameMapping(classes, application.getProguardMap());
    }

    protected void fillForMainDexList(Set<com.debughelper.tools.r8.graph.DexProgramClass> classes) {
      if (!application.mainDexList.isEmpty()) {
        VirtualFile mainDexFile = virtualFiles.get(0);
        for (com.debughelper.tools.r8.graph.DexType type : application.mainDexList) {
          DexClass clazz = application.definitionFor(type);
          if (clazz != null && clazz.isProgramClass()) {
            com.debughelper.tools.r8.graph.DexProgramClass programClass = (com.debughelper.tools.r8.graph.DexProgramClass) clazz;
            mainDexFile.addClass(programClass);
            classes.remove(programClass);
          } else {
            options.reporter.warning(
                new StringDiagnostic(
                    "Application does not contain `"
                        + type.toSourceString()
                        + "` as referenced in main-dex-list."));
          }
          mainDexFile.commitTransaction();
        }
        mainDexFile.throwIfFull(true, options.reporter);
      }
    }

    TreeSet<com.debughelper.tools.r8.graph.DexProgramClass> sortClassesByPackage(Set<com.debughelper.tools.r8.graph.DexProgramClass> classes,
                                                                                 Map<com.debughelper.tools.r8.graph.DexProgramClass, String> originalNames) {
      TreeSet<com.debughelper.tools.r8.graph.DexProgramClass> sortedClasses = new TreeSet<>(
          (com.debughelper.tools.r8.graph.DexProgramClass a, com.debughelper.tools.r8.graph.DexProgramClass b) -> {
            String originalA = originalNames.get(a);
            String originalB = originalNames.get(b);
            int indexA = originalA.lastIndexOf('.');
            int indexB = originalB.lastIndexOf('.');
            if (indexA == -1 && indexB == -1) {
              // Empty package, compare the class names.
              return originalA.compareTo(originalB);
            }
            if (indexA == -1) {
              // Empty package name comes first.
              return -1;
            }
            if (indexB == -1) {
              // Empty package name comes first.
              return 1;
            }
            String prefixA = originalA.substring(0, indexA);
            String prefixB = originalB.substring(0, indexB);
            int result = prefixA.compareTo(prefixB);
            if (result != 0) {
              return result;
            }
            return originalA.compareTo(originalB);
          });
      sortedClasses.addAll(classes);
      return sortedClasses;
    }
  }

  public static class FillFilesDistributor extends DistributorBase {
    private final FillStrategy fillStrategy;
    private final ExecutorService executorService;

    FillFilesDistributor(ApplicationWriter writer, com.debughelper.tools.r8.utils.InternalOptions options,
        ExecutorService executorService) {
      super(writer, options);
      this.fillStrategy = FillStrategy.FILL_MAX;
      this.executorService = executorService;
    }

    @Override
    public Collection<VirtualFile> run() throws IOException {
      int totalClassNumber = classes.size();
      // First fill required classes into the main dex file.
      fillForMainDexList(classes);
      if (classes.isEmpty()) {
        // All classes ended up in the main dex file, no more to do.
        return virtualFiles;
      }

      List<VirtualFile> filesForDistribution = virtualFiles;
      int fileIndexOffset = 0;
      boolean multidexLegacy = !mainDexFile.isEmpty();
      if (options.minimalMainDex && multidexLegacy) {
        assert !virtualFiles.get(0).isEmpty();
        assert virtualFiles.size() == 1;
        // The main dex file is filtered out, so ensure at least one file for the remaining classes.
        virtualFiles.add(new VirtualFile(1, writer.namingLens));
        filesForDistribution = virtualFiles.subList(1, virtualFiles.size());
        fileIndexOffset = 1;
      }

      if (multidexLegacy && options.enableInheritanceClassInDexDistributor) {
        new InheritanceClassInDexDistributor(mainDexFile, filesForDistribution, classes,
            originalNames, fileIndexOffset, writer.namingLens, writer.application, executorService)
            .distribute();
      } else {
        // Sort the remaining classes based on the original names.
        // This with make classes from the same package be adjacent.
        classes = sortClassesByPackage(classes, originalNames);
        new PackageSplitPopulator(
            filesForDistribution, classes, originalNames, application.dexItemFactory,
            fillStrategy, fileIndexOffset, writer.namingLens)
            .call();
      }
      assert totalClassNumber == virtualFiles.stream().mapToInt(dex -> dex.classes().size()).sum();
      return virtualFiles;
    }
  }

  public static class MonoDexDistributor extends DistributorBase {
    MonoDexDistributor(ApplicationWriter writer, InternalOptions options) {
      super(writer, options);
    }

    @Override
    public Collection<VirtualFile> run() throws ExecutionException, IOException {
      // Add all classes to the main dex file.
      for (com.debughelper.tools.r8.graph.DexProgramClass programClass : classes) {
        mainDexFile.addClass(programClass);
      }
      mainDexFile.commitTransaction();
      mainDexFile.throwIfFull(false, options.reporter);
      return virtualFiles;
    }
  }

  private static class VirtualFileIndexedItemCollection implements com.debughelper.tools.r8.dex.IndexedItemCollection {

    private final com.debughelper.tools.r8.naming.NamingLens namingLens;

    private final Set<com.debughelper.tools.r8.graph.DexProgramClass> classes = Sets.newIdentityHashSet();
    private final Set<com.debughelper.tools.r8.graph.DexProto> protos = Sets.newIdentityHashSet();
    private final Set<com.debughelper.tools.r8.graph.DexType> types = Sets.newIdentityHashSet();
    private final Set<com.debughelper.tools.r8.graph.DexMethod> methods = Sets.newIdentityHashSet();
    private final Set<com.debughelper.tools.r8.graph.DexField> fields = Sets.newIdentityHashSet();
    private final Set<com.debughelper.tools.r8.graph.DexString> strings = Sets.newIdentityHashSet();
    private final Set<com.debughelper.tools.r8.graph.DexCallSite> callSites = Sets.newIdentityHashSet();
    private final Set<com.debughelper.tools.r8.graph.DexMethodHandle> methodHandles = Sets.newIdentityHashSet();

    public VirtualFileIndexedItemCollection(
        com.debughelper.tools.r8.naming.NamingLens namingLens) {
      this.namingLens = namingLens;

    }

    @Override
    public boolean addClass(com.debughelper.tools.r8.graph.DexProgramClass clazz) {
      return classes.add(clazz);
    }

    @Override
    public boolean addField(com.debughelper.tools.r8.graph.DexField field) {
      return fields.add(field);
    }

    @Override
    public boolean addMethod(com.debughelper.tools.r8.graph.DexMethod method) {
      return methods.add(method);
    }

    @Override
    public boolean addString(com.debughelper.tools.r8.graph.DexString string) {
      return strings.add(string);
    }

    @Override
    public boolean addProto(com.debughelper.tools.r8.graph.DexProto proto) {
      return protos.add(proto);
    }

    @Override
    public boolean addType(com.debughelper.tools.r8.graph.DexType type) {
      return types.add(type);
    }

    @Override
    public boolean addCallSite(com.debughelper.tools.r8.graph.DexCallSite callSite) {
      return callSites.add(callSite);
    }

    @Override
    public boolean addMethodHandle(com.debughelper.tools.r8.graph.DexMethodHandle methodHandle) {
      return methodHandles.add(methodHandle);
    }

    int getNumberOfMethods() {
      return methods.size();
    }

    int getNumberOfFields() {
      return fields.size();
    }

    int getNumberOfStrings() {
      return strings.size();
    }

    @Override
    public com.debughelper.tools.r8.graph.DexString getRenamedDescriptor(com.debughelper.tools.r8.graph.DexType type) {
      return namingLens.lookupDescriptor(type);
    }

    @Override
    public com.debughelper.tools.r8.graph.DexString getRenamedName(com.debughelper.tools.r8.graph.DexMethod method) {
      assert namingLens.checkTargetCanBeTranslated(method);
      return namingLens.lookupName(method);
    }

    @Override
    public com.debughelper.tools.r8.graph.DexString getRenamedName(com.debughelper.tools.r8.graph.DexField field) {
      return namingLens.lookupName(field);
    }
  }

  private static class IndexedItemTransaction implements IndexedItemCollection {

    private final VirtualFileIndexedItemCollection base;
    private final com.debughelper.tools.r8.naming.NamingLens namingLens;

    private final Set<com.debughelper.tools.r8.graph.DexProgramClass> classes = new LinkedHashSet<>();
    private final Set<com.debughelper.tools.r8.graph.DexField> fields = new LinkedHashSet<>();
    private final Set<com.debughelper.tools.r8.graph.DexMethod> methods = new LinkedHashSet<>();
    private final Set<com.debughelper.tools.r8.graph.DexType> types = new LinkedHashSet<>();
    private final Set<com.debughelper.tools.r8.graph.DexProto> protos = new LinkedHashSet<>();
    private final Set<com.debughelper.tools.r8.graph.DexString> strings = new LinkedHashSet<>();
    private final Set<com.debughelper.tools.r8.graph.DexCallSite> callSites = new LinkedHashSet<>();
    private final Set<com.debughelper.tools.r8.graph.DexMethodHandle> methodHandles = new LinkedHashSet<>();

    private IndexedItemTransaction(VirtualFileIndexedItemCollection base,
        com.debughelper.tools.r8.naming.NamingLens namingLens) {
      this.base = base;
      this.namingLens = namingLens;
    }

    private <T extends com.debughelper.tools.r8.graph.DexItem> boolean maybeInsert(T item, Set<T> set, Set<T> baseSet) {
      if (baseSet.contains(item) || set.contains(item)) {
        return false;
      }
      set.add(item);
      return true;
    }

    void addClassAndDependencies(com.debughelper.tools.r8.graph.DexProgramClass clazz) {
      clazz.collectIndexedItems(this);
    }

    @Override
    public boolean addClass(com.debughelper.tools.r8.graph.DexProgramClass dexProgramClass) {
      return maybeInsert(dexProgramClass, classes, base.classes);
    }

    @Override
    public boolean addField(com.debughelper.tools.r8.graph.DexField field) {
      return maybeInsert(field, fields, base.fields);
    }

    @Override
    public boolean addMethod(com.debughelper.tools.r8.graph.DexMethod method) {
      return maybeInsert(method, methods, base.methods);
    }

    @Override
    public boolean addString(com.debughelper.tools.r8.graph.DexString string) {
      return maybeInsert(string, strings, base.strings);
    }

    @Override
    public boolean addProto(DexProto proto) {
      return maybeInsert(proto, protos, base.protos);
    }

    @Override
    public boolean addType(com.debughelper.tools.r8.graph.DexType type) {
      return maybeInsert(type, types, base.types);
    }

    @Override
    public boolean addCallSite(DexCallSite callSite) {
      return maybeInsert(callSite, callSites, base.callSites);
    }

    @Override
    public boolean addMethodHandle(DexMethodHandle methodHandle) {
      return maybeInsert(methodHandle, methodHandles, base.methodHandles);
    }

    @Override
    public com.debughelper.tools.r8.graph.DexString getRenamedDescriptor(DexType type) {
      return namingLens.lookupDescriptor(type);
    }

    @Override
    public com.debughelper.tools.r8.graph.DexString getRenamedName(DexMethod method) {
      assert namingLens.checkTargetCanBeTranslated(method);
      return namingLens.lookupName(method);
    }

    @Override
    public DexString getRenamedName(DexField field) {
      return namingLens.lookupName(field);
    }

    int getNumberOfMethods() {
      return methods.size() + base.getNumberOfMethods();
    }

    int getNumberOfFields() {
      return fields.size() + base.getNumberOfFields();
    }

    private <T extends DexItem> void commitItemsIn(Set<T> set, Function<T, Boolean> hook) {
      set.forEach((item) -> {
        boolean newlyAdded = hook.apply(item);
        assert newlyAdded;
      });
      set.clear();
    }

    void commit() {
      commitItemsIn(classes, base::addClass);
      commitItemsIn(fields, base::addField);
      commitItemsIn(methods, base::addMethod);
      commitItemsIn(protos, base::addProto);
      commitItemsIn(types, base::addType);
      commitItemsIn(strings, base::addString);
      commitItemsIn(callSites, base::addCallSite);
      commitItemsIn(methodHandles, base::addMethodHandle);
    }

    void abort() {
      classes.clear();
      fields.clear();
      methods.clear();
      protos.clear();
      types.clear();
      strings.clear();
    }

    public boolean isEmpty() {
      return classes.isEmpty() && fields.isEmpty() && methods.isEmpty() && protos.isEmpty()
          && types.isEmpty() && strings.isEmpty();
    }

    int getNumberOfClasses() {
      return classes.size() + base.classes.size();
    }
  }

  /**
   * Helper class to cycle through the set of virtual files.
   *
   * Iteration starts at the first file and iterates through all files.
   *
   * When {@link VirtualFileCycler#restart()} is called iteration of all files is restarted at the
   * current file.
   *
   * If the fill strategy indicate that the main dex file should be minimal, then the main dex file
   * will not be part of the iteration.
   */
  static class VirtualFileCycler {

    private final List<VirtualFile> files;
    private final com.debughelper.tools.r8.naming.NamingLens namingLens;

    private int nextFileId;
    private Iterator<VirtualFile> allFilesCyclic;
    private Iterator<VirtualFile> activeFiles;

    VirtualFileCycler(List<VirtualFile> files, com.debughelper.tools.r8.naming.NamingLens namingLens, int fileIndexOffset) {
      this.files = files;
      this.namingLens = namingLens;

      nextFileId = files.size() + fileIndexOffset;

      reset();
    }

    void reset() {
      allFilesCyclic = Iterators.cycle(files);
      restart();
    }

    boolean hasNext() {
      return activeFiles.hasNext();
    }

    VirtualFile next() {
      return activeFiles.next();
    }

    /**
     * Get next {@link VirtualFile} and create a new empty one if there is no next available.
     */
    VirtualFile nextOrCreate() {
      if (hasNext()) {
        return activeFiles.next();
      } else {
        VirtualFile newFile = new VirtualFile(nextFileId++, namingLens);
        files.add(newFile);
        allFilesCyclic = Iterators.cycle(files);
        return newFile;
      }
    }

    /**
     * Get next {@link VirtualFile} accepted by the given filter and create a new empty one if there
     * is no next available.
     * @param filter allows to to reject some of the available {@link VirtualFile}. Rejecting empt
     * {@link VirtualFile} is not authorized since it would sometimes prevent to find a result.
     */
    VirtualFile nextOrCreate(Predicate<? super VirtualFile> filter) {
      while (true) {
        VirtualFile dex = nextOrCreate();
        if (dex.isEmpty()) {
          assert filter.test(dex);
          return dex;
        } else if (filter.test(dex)) {
          return dex;
        }
      }
    }

    // Start a new iteration over all files, starting at the current one.
    void restart() {
      activeFiles = Iterators.limit(allFilesCyclic, files.size());
    }

    VirtualFile addFile() {
      VirtualFile newFile = new VirtualFile(nextFileId++, namingLens);
      files.add(newFile);

      reset();
      return newFile;
    }
  }

  /**
   * Distributes the given classes over the files in package order.
   *
   * <p>The populator avoids package splits. Big packages are split into subpackages if their size
   * exceeds 20% of the dex file. This populator also avoids filling files completely to cater for
   * future growth.
   *
   * <p>The populator cycles through the files until all classes have been successfully placed and
   * adds new files to the passed in map if it can't fit in the existing files.
   */
  private static class PackageSplitPopulator implements Callable<Map<String, Integer>> {

    /**
     * debughelper suggests com.company.product for package names, so the components will be at level 4
     */
    private static final int MINIMUM_PREFIX_LENGTH = 4;
    private static final int MAXIMUM_PREFIX_LENGTH = 7;
    /**
     * We allow 1/MIN_FILL_FACTOR of a file to remain empty when moving to the next file, i.e., a
     * rollback with less than 1/MAX_FILL_FACTOR of the total classes in a file will move to the
     * next file.
     */
    private static final int MIN_FILL_FACTOR = 5;

    private final List<com.debughelper.tools.r8.graph.DexProgramClass> classes;
    private final Map<com.debughelper.tools.r8.graph.DexProgramClass, String> originalNames;
    private final com.debughelper.tools.r8.graph.DexItemFactory dexItemFactory;
    private final FillStrategy fillStrategy;
    private final VirtualFileCycler cycler;

    PackageSplitPopulator(
        List<VirtualFile> files,
        Set<com.debughelper.tools.r8.graph.DexProgramClass> classes,
        Map<com.debughelper.tools.r8.graph.DexProgramClass, String> originalNames,
        DexItemFactory dexItemFactory,
        FillStrategy fillStrategy,
        int fileIndexOffset,
        NamingLens namingLens) {
      this.classes = new ArrayList<>(classes);
      this.originalNames = originalNames;
      this.dexItemFactory = dexItemFactory;
      this.fillStrategy = fillStrategy;
      this.cycler = new VirtualFileCycler(files, namingLens, fileIndexOffset);
    }

    static boolean coveredByPrefix(String originalName, String currentPrefix) {
      if (currentPrefix == null) {
        return false;
      }
      if (currentPrefix.endsWith(".*")) {
        return originalName.startsWith(currentPrefix.substring(0, currentPrefix.length() - 2));
      } else {
        return originalName.startsWith(currentPrefix)
            && originalName.lastIndexOf('.') == currentPrefix.length();
      }
    }

    private String getOriginalName(com.debughelper.tools.r8.graph.DexProgramClass clazz) {
      return originalNames != null ? originalNames.get(clazz) : clazz.toString();
    }

    @Override
    public Map<String, Integer> call() throws IOException {
      int prefixLength = MINIMUM_PREFIX_LENGTH;
      int transactionStartIndex = 0;
      int fileStartIndex = 0;
      String currentPrefix = null;
      Map<String, Integer> newPackageAssignments = new LinkedHashMap<>();
      VirtualFile current = cycler.next();
      List<com.debughelper.tools.r8.graph.DexProgramClass> nonPackageClasses = new ArrayList<>();
      for (int classIndex = 0; classIndex < classes.size(); classIndex++) {
        com.debughelper.tools.r8.graph.DexProgramClass clazz = classes.get(classIndex);
        String originalName = getOriginalName(clazz);
        if (!coveredByPrefix(originalName, currentPrefix)) {
          if (currentPrefix != null) {
            current.commitTransaction();
            // Reset the cycler to again iterate over all files, starting with the current one.
            cycler.restart();
            assert !newPackageAssignments.containsKey(currentPrefix);
            newPackageAssignments.put(currentPrefix, current.id);
            // Try to reduce the prefix length if possible. Only do this on a successful commit.
            prefixLength = MINIMUM_PREFIX_LENGTH - 1;
          }
          String newPrefix;
          // Also, we need to avoid new prefixes that are a prefix of previously used prefixes, as
          // otherwise we might generate an overlap that will trigger problems when reusing the
          // package mapping generated here. For example, if an existing map contained
          //   com.debughelper.foo.*
          // but we now try to place some new subpackage
          //   com.debughelper.bar.*,
          // we locally could use
          //   com.debughelper.*.
          // However, when writing out the final package map, we get overlapping patterns
          // com.debughelper.* and com.debughelper.foo.*.
          do {
            newPrefix = extractPrefixToken(++prefixLength, originalName, false);
          } while (currentPrefix != null && currentPrefix.startsWith(newPrefix));
          // Don't set the current prefix if we did not extract one.
          if (!newPrefix.equals("")) {
            currentPrefix = extractPrefixToken(prefixLength, originalName, true);
          }
          transactionStartIndex = classIndex;
        }
        if (currentPrefix != null) {
          assert clazz.superType != null || clazz.type == dexItemFactory.objectType;
          current.addClass(clazz);
        } else {
          assert clazz.superType != null;
          // We don't have a package, add this to a list of classes that we will add last.
          assert current.transaction.classes.isEmpty();
          nonPackageClasses.add(clazz);
          continue;
        }
        if (current.isFilledEnough(fillStrategy) || current.isFull()) {
          current.abortTransaction();
          // We allow for a final rollback that has at most 20% of classes in it.
          // This is a somewhat random number that was empirically chosen.
          if (classIndex - transactionStartIndex > (classIndex - fileStartIndex) / MIN_FILL_FACTOR
              && prefixLength < MAXIMUM_PREFIX_LENGTH) {
            prefixLength++;
          } else {
            // Reset the state to after the last commit and cycle through files.
            // The idea is that we do not increase the number of files, so it has to fit
            // somewhere.
            fileStartIndex = transactionStartIndex;
            if (!cycler.hasNext()) {
              // Special case where we simply will never be able to fit the current package into
              // one dex file. This is currently the case for Strings in jumbo tests, see:
              // b/33227518
              if (current.transaction.getNumberOfClasses() == 0) {
                for (int j = transactionStartIndex; j <= classIndex; j++) {
                  nonPackageClasses.add(classes.get(j));
                }
                transactionStartIndex = classIndex + 1;
              }
              // All files are filled up to the 20% mark.
              cycler.addFile();
            }
            current = cycler.next();
          }
          currentPrefix = null;
          // Go back to previous start index.
          classIndex = transactionStartIndex - 1;
          assert current != null;
        }
      }
      current.commitTransaction();
      assert !newPackageAssignments.containsKey(currentPrefix);
      if (currentPrefix != null) {
        newPackageAssignments.put(currentPrefix, current.id);
      }
      if (nonPackageClasses.size() > 0) {
        addNonPackageClasses(cycler, nonPackageClasses);
      }
      return newPackageAssignments;
    }

    private void addNonPackageClasses(
        VirtualFileCycler cycler, List<com.debughelper.tools.r8.graph.DexProgramClass> nonPackageClasses) {
      cycler.restart();
      VirtualFile current;
      current = cycler.next();
      for (DexProgramClass clazz : nonPackageClasses) {
        if (current.isFilledEnough(fillStrategy)) {
          current = getVirtualFile(cycler);
        }
        current.addClass(clazz);
        while (current.isFull()) {
          // This only happens if we have a huge class, that takes up more than 20% of a dex file.
          current.abortTransaction();
          current = getVirtualFile(cycler);
          boolean wasEmpty = current.isEmpty();
          current.addClass(clazz);
          if (wasEmpty && current.isFull()) {
            throw new InternalCompilerError(
                "Class " + clazz.toString() + " does not fit into a single dex file.");
          }
        }
        current.commitTransaction();
      }
    }

    private VirtualFile getVirtualFile(VirtualFileCycler cycler) {
      VirtualFile current = null;
      while (cycler.hasNext()
          && (current = cycler.next()).isFilledEnough(fillStrategy)) {}
      if (current == null || current.isFilledEnough(fillStrategy)) {
        current = cycler.addFile();
      }
      return current;
    }
  }

}
