// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.shaking;

import com.debughelper.tools.r8.dex.IndexedItemCollection;
import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.graph.AppInfoWithSubtyping;
import com.debughelper.tools.r8.graph.DexAnnotation;
import com.debughelper.tools.r8.graph.DexAnnotationSet;
import com.debughelper.tools.r8.graph.DexApplication;
import com.debughelper.tools.r8.graph.DexCallSite;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexMethodHandle;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexProto;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.UseRegistry;
import com.google.common.collect.Maps;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Calculate the list of classes required in the main dex to allow legacy multidex loading.
 * Classes required in the main dex are:
 * <li> The classes with code executed before secondary dex files are installed.
 * <li> The "direct dependencies" of those classes, ie the classes required by dexopt.
 * <li> Annotation classes with a possible enum value and all classes annotated by them.
 */
public class MainDexListBuilder {
  private final Set<DexType> baseClasses;
  private final AppInfoWithSubtyping appInfo;
  private final Set<DexType> mainDexTypes = new HashSet<>();
  private final DirectReferencesCollector codeDirectReferenceCollector =
      new DirectReferencesCollector();
  private final AnnotationDirectReferenceCollector annotationDirectReferenceCollector =
      new AnnotationDirectReferenceCollector();
  private final Map<DexType, Boolean> annotationTypeContainEnum;
  private final DexApplication dexApplication;

  /**
   * @param baseClasses Classes which code may be executed before secondary dex files loading.
   * @param application the dex appplication.
   */
  public MainDexListBuilder(Set<DexType> baseClasses, DexApplication application) {
    this.dexApplication = application;
    this.appInfo = new AppInfoWithSubtyping(dexApplication);
    this.baseClasses =
        baseClasses.stream().filter(this::isProgramClass).collect(Collectors.toSet());
    DexClass enumType = appInfo.definitionFor(appInfo.dexItemFactory.enumType);
    if (enumType == null) {
      throw new CompilationError("Tracing for legacy multi dex is not possible without all"
          + " classpath libraries (java.lang.Enum is missing)");
    }
    DexClass annotationType = appInfo.definitionFor(appInfo.dexItemFactory.annotationType);
    if (annotationType == null) {
      throw new CompilationError("Tracing for legacy multi dex is not possible without all"
          + " classpath libraries (java.lang.annotation.Annotation is missing)");
    }
    annotationTypeContainEnum =
        Maps.newHashMapWithExpectedSize(
            appInfo.subtypes(appInfo.dexItemFactory.annotationType).size());
  }

  public Set<DexType> run() {
    traceMainDexDirectDependencies();
    traceRuntimeAnnotationsWithEnumForMainDex();
    return mainDexTypes.stream().filter(this::isProgramClass).collect(Collectors.toSet());
  }

  private void traceRuntimeAnnotationsWithEnumForMainDex() {
    for (DexProgramClass clazz : dexApplication.classes()) {
      DexType dexType = clazz.type;
      if (mainDexTypes.contains(dexType)) {
        continue;
      }
      if (isAnnotation(dexType) && isAnnotationWithEnum(dexType)) {
        addMainDexType(dexType);
        continue;
      }
      clazz.forEachAnnotation(annotation -> {
        if (!mainDexTypes.contains(dexType)
            && annotation.visibility == DexAnnotation.VISIBILITY_RUNTIME
            && isAnnotationWithEnum(annotation.annotation.type)) {
          addMainDexType(dexType);
        }
      });
    }
  }

  private boolean isAnnotationWithEnum(DexType dexType) {
    Boolean value = annotationTypeContainEnum.get(dexType);
    if (value == null) {
      DexClass clazz = appInfo.definitionFor(dexType);
      if (clazz == null) {
        // Information is missing lets be conservative.
        value = Boolean.TRUE;
      } else {
        value = Boolean.FALSE;
        // Browse annotation values types in search for enum.
        // Each annotation value is represented by a virtual method.
        for (DexEncodedMethod method : clazz.virtualMethods()) {
          DexProto proto = method.method.proto;
          if (proto.parameters.isEmpty()) {
            DexType valueType = proto.returnType.toBaseType(appInfo.dexItemFactory);
            if (isEnum(valueType)) {
              value = Boolean.TRUE;
              break;
            } else if (isAnnotation(valueType) && isAnnotationWithEnum(valueType)) {
              value = Boolean.TRUE;
              break;
            }
          }
        }
      }
      annotationTypeContainEnum.put(dexType, value);
    }
    return value.booleanValue();
  }

  private boolean isEnum(DexType valueType) {
    return valueType.isSubtypeOf(appInfo.dexItemFactory.enumType, appInfo);
  }

  private boolean isAnnotation(DexType valueType) {
    return valueType.isSubtypeOf(appInfo.dexItemFactory.annotationType, appInfo);
  }

  private boolean isProgramClass(DexType dexType) {
    DexClass clazz = appInfo.definitionFor(dexType);
    return clazz != null && clazz.isProgramClass();
  }

  private void traceMainDexDirectDependencies() {
    for (DexType type : baseClasses) {
      DexClass clazz = appInfo.definitionFor(type);
      if (clazz == null) {
        // Happens for library classes.
        continue;
      }
      addMainDexType(type);
      // Super and interfaces are live, no need to add them.
      traceAnnotationsDirectDendencies(clazz.annotations);
      clazz.forEachField(field -> addMainDexType(field.field.type));
      clazz.forEachMethod(method -> {
        traceMethodDirectDependencies(method.method);
        method.registerCodeReferences(codeDirectReferenceCollector);
      });
    }
  }

  private void traceAnnotationsDirectDendencies(DexAnnotationSet annotations) {
    annotations.collectIndexedItems(annotationDirectReferenceCollector);
  }

  private void traceMethodDirectDependencies(DexMethod method) {
    DexProto proto = method.proto;
    addMainDexType(proto.returnType);
    for (DexType parameterType : proto.parameters.values) {
      addMainDexType(parameterType);
    }
  }

  private void addMainDexType(DexType type) {
    // Consider only component type of arrays
    type = type.toBaseType(appInfo.dexItemFactory);

    if (!type.isClassType()) {
      return;
    }

    DexClass clazz = appInfo.definitionFor(type);
    if (clazz == null) {
      // Happens for library classes.
      return;
    }
    addMainDexType(clazz);
  }

  private void addMainDexType(DexClass dexClass) {
    DexType type = dexClass.type;
    if (mainDexTypes.add(type)) {
      if (dexClass.superType != null) {
        addMainDexType(dexClass.superType);
      }
      for (DexType interfaze : dexClass.interfaces.values) {
        addMainDexType(interfaze);
      }
    }
  }

  private class DirectReferencesCollector extends UseRegistry {


    private DirectReferencesCollector() {
    }

    @Override
    public boolean registerInvokeVirtual(DexMethod method) {
      return registerInvoke(method);
    }

    @Override
    public boolean registerInvokeDirect(DexMethod method) {
      return registerInvoke(method);
    }

    @Override
    public boolean registerInvokeStatic(DexMethod method) {
      return registerInvoke(method);
    }

    @Override
    public boolean registerInvokeInterface(DexMethod method) {
      return registerInvoke(method);
    }

    @Override
    public boolean registerInvokeSuper(DexMethod method) {
      return registerInvoke(method);
    }

    protected boolean registerInvoke(DexMethod method) {
      addMainDexType(method.getHolder());
      traceMethodDirectDependencies(method);
      return true;
    }

    @Override
    public boolean registerInstanceFieldWrite(DexField field) {
      return registerFieldAccess(field);
    }

    @Override
    public boolean registerInstanceFieldRead(DexField field) {
      return registerFieldAccess(field);
    }

    @Override
    public boolean registerStaticFieldRead(DexField field) {
      return registerFieldAccess(field);
    }

    @Override
    public boolean registerStaticFieldWrite(DexField field) {
      return registerFieldAccess(field);
    }

    protected boolean registerFieldAccess(DexField field) {
      addMainDexType(field.getHolder());
      addMainDexType(field.type);
      return true;
    }

    @Override
    public boolean registerNewInstance(DexType type) {
      addMainDexType(type);
      return true;
    }

    @Override
    public boolean registerTypeReference(DexType type) {
      addMainDexType(type);
      return true;
    }
  }

  private class AnnotationDirectReferenceCollector implements IndexedItemCollection {

    @Override
    public boolean addClass(DexProgramClass dexProgramClass) {
      addMainDexType(dexProgramClass.type);
      return false;
    }

    @Override
    public boolean addField(DexField field) {
      addMainDexType(field.getHolder());
      addMainDexType(field.type);
      return false;
    }

    @Override
    public boolean addMethod(DexMethod method) {
      addMainDexType(method.getHolder());
      addProto(method.proto);
      return false;
    }

    @Override
    public boolean addString(DexString string) {
      return false;
    }

    @Override
    public boolean addProto(DexProto proto) {
      addMainDexType(proto.returnType);
      Collections.addAll(mainDexTypes, proto.parameters.values);
      return false;
    }

    @Override
    public boolean addType(DexType type) {
      addMainDexType(type);
      return false;
    }

    @Override
    public boolean addCallSite(DexCallSite callSite) {
      throw new AssertionError("CallSite are not supported when tracing for legacy multi dex");
    }

    @Override
    public boolean addMethodHandle(DexMethodHandle methodHandle) {
      throw new AssertionError(
          "DexMethodHandle are not supported when tracing for legacy multi dex");
    }
  }
}
