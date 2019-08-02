// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.graph;

import com.debughelper.tools.r8.dex.IndexedItemCollection;
import com.debughelper.tools.r8.dex.MixedSectionCollection;
import com.debughelper.tools.r8.graph.DexValue.DexValueAnnotation;
import com.debughelper.tools.r8.graph.DexValue.DexValueArray;
import com.debughelper.tools.r8.graph.DexValue.DexValueInt;
import com.debughelper.tools.r8.graph.DexValue.DexValueMethod;
import com.debughelper.tools.r8.graph.DexValue.DexValueNull;
import com.debughelper.tools.r8.graph.DexValue.DexValueString;
import com.debughelper.tools.r8.graph.DexValue.DexValueType;
import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.utils.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

public class DexAnnotation extends DexItem {
  public static final int VISIBILITY_BUILD = 0x00;
  public static final int VISIBILITY_RUNTIME = 0x01;
  public static final int VISIBILITY_SYSTEM = 0x02;
  public final int visibility;
  public final DexEncodedAnnotation annotation;

  public DexAnnotation(int visibility, DexEncodedAnnotation annotation) {
    this.visibility = visibility;
    this.annotation = annotation;
  }

  @Override
  public int hashCode() {
    return visibility + annotation.hashCode() * 3;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other instanceof DexAnnotation) {
      DexAnnotation o = (DexAnnotation) other;
      return (visibility == o.visibility) && annotation.equals(o.annotation);
    }
    return false;
  }

  @Override
  public String toString() {
    return visibility + " " + annotation;
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems,
      DexMethod method, int instructionOffset) {
    annotation.collectIndexedItems(indexedItems, method, instructionOffset);
  }

  @Override
  void collectMixedSectionItems(MixedSectionCollection mixedItems) {
    mixedItems.add(this);
  }

  public static DexAnnotation createEnclosingClassAnnotation(DexType enclosingClass,
      DexItemFactory factory) {
    return createSystemValueAnnotation(factory.annotationEnclosingClass, factory,
        new DexValue.DexValueType(enclosingClass));
  }

  public static DexType getEnclosingClassFromAnnotation(
      DexAnnotation annotation, DexItemFactory factory) {
    DexValue.DexValueType typeValue =
        (DexValue.DexValueType) getSystemValueAnnotationValue(factory.annotationEnclosingClass, annotation);
    return typeValue == null ? null : typeValue.value;
  }

  public static DexAnnotation createEnclosingMethodAnnotation(DexMethod enclosingMethod,
      DexItemFactory factory) {
    return createSystemValueAnnotation(factory.annotationEnclosingMethod, factory,
        new DexValue.DexValueMethod(enclosingMethod));
  }

  public static DexMethod getEnclosingMethodFromAnnotation(
      DexAnnotation annotation, DexItemFactory factory) {
    DexValue.DexValueMethod methodValue =
        (DexValue.DexValueMethod)
            getSystemValueAnnotationValue(factory.annotationEnclosingMethod, annotation);
    return methodValue == null ? null : methodValue.value;
  }

  public static boolean isEnclosingClassAnnotation(DexAnnotation annotation,
      DexItemFactory factory) {
    return annotation.annotation.type == factory.annotationEnclosingClass;
  }

  public static boolean isEnclosingMethodAnnotation(DexAnnotation annotation,
      DexItemFactory factory) {
    return annotation.annotation.type == factory.annotationEnclosingMethod;
  }

  public static boolean isInnerClassAnnotation(DexAnnotation annotation, DexItemFactory factory) {
    return annotation.annotation.type == factory.annotationInnerClass;
  }

  public static boolean isMemberClassesAnnotation(
      DexAnnotation annotation, DexItemFactory factory) {
    return annotation.annotation.type == factory.annotationMemberClasses;
  }

  public static DexAnnotation createInnerClassAnnotation(
      DexString clazz, int access, DexItemFactory factory) {
    return new DexAnnotation(
        VISIBILITY_SYSTEM,
        new DexEncodedAnnotation(
            factory.annotationInnerClass,
            new DexAnnotationElement[] {
              new DexAnnotationElement(
                  factory.createString("accessFlags"), DexValue.DexValueInt.create(access)),
              new DexAnnotationElement(
                  factory.createString("name"),
                  (clazz == null) ? DexValue.DexValueNull.NULL : new DexValue.DexValueString(clazz))
            }));
  }

  public static com.debughelper.tools.r8.utils.Pair<DexString, Integer> getInnerClassFromAnnotation(
      DexAnnotation annotation, DexItemFactory factory) {
    assert isInnerClassAnnotation(annotation, factory);
    DexAnnotationElement[] elements = annotation.annotation.elements;
    com.debughelper.tools.r8.utils.Pair<DexString, Integer> result = new Pair<>();
    for (DexAnnotationElement element : elements) {
      if (element.name == factory.createString("name")) {
        if (element.value instanceof DexValue.DexValueString) {
          result.setFirst(((DexValue.DexValueString) element.value).getValue());
        }
      } else {
        assert element.name == factory.createString("accessFlags");
        result.setSecond(((DexValue.DexValueInt) element.value).getValue());
      }
    }
    return result;
  }

  public static DexAnnotation createMemberClassesAnnotation(List<DexType> classes,
      DexItemFactory factory) {
    DexValue[] values = new DexValue[classes.size()];
    for (int i = 0; i < classes.size(); i++) {
      values[i] = new DexValue.DexValueType(classes.get(i));
    }
    return createSystemValueAnnotation(factory.annotationMemberClasses, factory,
        new DexValue.DexValueArray(values));
  }

  public static List<DexType> getMemberClassesFromAnnotation(
      DexAnnotation annotation, DexItemFactory factory) {
    DexValue.DexValueArray membersArray =
        (DexValue.DexValueArray) getSystemValueAnnotationValue(factory.annotationMemberClasses, annotation);
    if (membersArray == null) {
      return null;
    }
    List<DexType> types = new ArrayList<>(membersArray.getValues().length);
    for (DexValue value : membersArray.getValues()) {
      types.add(((DexValue.DexValueType) value).value);
    }
    return types;
  }

  public static DexAnnotation createSourceDebugExtensionAnnotation(DexValue value,
      DexItemFactory factory) {
    return new DexAnnotation(VISIBILITY_SYSTEM,
        new DexEncodedAnnotation(factory.annotationSourceDebugExtension,
            new DexAnnotationElement[] {
              new DexAnnotationElement(factory.createString("value"), value)
            }));
  }

  public static DexAnnotation createMethodParametersAnnotation(DexValue[] names,
      DexValue[] accessFlags, DexItemFactory factory) {
    assert names.length == accessFlags.length;
    return new DexAnnotation(VISIBILITY_SYSTEM,
        new DexEncodedAnnotation(factory.annotationMethodParameters,
            new DexAnnotationElement[]{
                new DexAnnotationElement(
                    factory.createString("names"),
                    new DexValue.DexValueArray(names)),
                new DexAnnotationElement(
                    factory.createString("accessFlags"),
                    new DexValue.DexValueArray(accessFlags))
            }));
  }

  public static DexAnnotation createAnnotationDefaultAnnotation(DexType type,
      List<DexAnnotationElement> defaults, DexItemFactory factory) {
    return createSystemValueAnnotation(factory.annotationDefault, factory,
        new DexValue.DexValueAnnotation(
            new DexEncodedAnnotation(type,
                defaults.toArray(new DexAnnotationElement[defaults.size()])))
    );
  }

  public static DexAnnotation createSignatureAnnotation(String signature, DexItemFactory factory) {
    return createSystemValueAnnotation(factory.annotationSignature, factory,
        compressSignature(signature, factory));
  }

  public static String getSignature(DexAnnotation signatureAnnotation) {
    DexValue.DexValueArray elements = (DexValue.DexValueArray) signatureAnnotation.annotation.elements[0].value;
    StringBuilder signature = new StringBuilder();
    for (DexValue element : elements.getValues()) {
      signature.append(((DexValue.DexValueString) element).value.toString());
    }
    return signature.toString();
  }

  public static DexAnnotation createThrowsAnnotation(DexValue[] exceptions,
      DexItemFactory factory) {
    return createSystemValueAnnotation(factory.annotationThrows, factory,
        new DexValue.DexValueArray(exceptions));
  }

  private static DexAnnotation createSystemValueAnnotation(DexType type, DexItemFactory factory,
      DexValue value) {
    return new DexAnnotation(VISIBILITY_SYSTEM,
        new DexEncodedAnnotation(type, new DexAnnotationElement[]{
            new DexAnnotationElement(factory.createString("value"), value)
        }));
  }

  private static DexValue getSystemValueAnnotationValue(DexType type, DexAnnotation annotation) {
    assert annotation.visibility == VISIBILITY_SYSTEM;
    assert annotation.annotation.type == type;
    return annotation.annotation.elements.length == 0
        ? null
        : annotation.annotation.elements[0].value;
  }

  public static boolean isThrowingAnnotation(DexAnnotation annotation,
      DexItemFactory factory) {
    return annotation.annotation.type == factory.annotationThrows;
  }

  public static boolean isSignatureAnnotation(DexAnnotation annotation,
      DexItemFactory factory) {
    return annotation.annotation.type == factory.annotationSignature;

  }

  public static boolean isAnnotationDefaultAnnotation(DexAnnotation annotation,
      DexItemFactory factory) {
    return annotation.annotation.type == factory.annotationDefault;
  }


  public static boolean isSourceDebugExtension(DexAnnotation annotation,
      DexItemFactory factory) {
    return annotation.annotation.type == factory.annotationSourceDebugExtension;
  }

  public static boolean isParameterNameAnnotation(DexAnnotation annotation,
      DexItemFactory factory) {
    return annotation.annotation.type == factory.annotationMethodParameters;
  }

  /**
   * As a simple heuristic for compressing a signature by splitting on fully qualified class names
   * and make them individual part. All other parts of the signature are simply grouped and separate
   * the names.
   * For examples, "()Ljava/lang/List<Lfoo/bar/Baz;>;" splits into:
   * <pre>
   *   ["()", "Ljava/lang/List<", "Lfoo/bar/Baz;", ">;"]
   * </pre>
   */
  private static DexValue compressSignature(String signature, DexItemFactory factory) {
    final int length = signature.length();
    List<DexValue> parts = new ArrayList<>();

    for (int at = 0; at < length; /*at*/) {
      char c = signature.charAt(at);
      int endAt = at + 1;
      if (c == 'L') {
        // Scan to ';' or '<' and consume them.
        while (endAt < length) {
          c = signature.charAt(endAt);
          if (c == ';' || c == '<') {
            endAt++;
            break;
          }
          endAt++;
        }
      } else {
        // Scan to 'L' without consuming it.
        while (endAt < length) {
          c = signature.charAt(endAt);
          if (c == 'L') {
            break;
          }
          endAt++;
        }
      }

      parts.add(toDexValue(signature.substring(at, endAt), factory));
      at = endAt;
    }

    return new DexValue.DexValueArray(parts.toArray(new DexValue[parts.size()]));
  }

  private static DexValue toDexValue(String string, DexItemFactory factory) {
    return new DexValue.DexValueString(factory.createString(string));
  }

  public static Collection<DexType> readAnnotationSynthesizedClassMap(
      DexProgramClass programClass,
      DexItemFactory dexItemFactory) {
    DexAnnotation foundAnnotation = programClass.annotations.getFirstMatching(
        dexItemFactory.annotationSynthesizedClassMap);
    if (foundAnnotation != null) {
      if (foundAnnotation.annotation.elements.length != 1) {
        throw new com.debughelper.tools.r8.errors.CompilationError(
            getInvalidSynthesizedClassMapMessage(programClass, foundAnnotation));
      }
      DexAnnotationElement value = foundAnnotation.annotation.elements[0];
      if (!value.name.toSourceString().equals("value")) {
        throw new com.debughelper.tools.r8.errors.CompilationError(
            getInvalidSynthesizedClassMapMessage(programClass, foundAnnotation));
      }
      if (!(value.value instanceof DexValue.DexValueArray)) {
        throw new com.debughelper.tools.r8.errors.CompilationError(
            getInvalidSynthesizedClassMapMessage(programClass, foundAnnotation));
      }
      DexValue.DexValueArray existingList = (DexValue.DexValueArray) value.value;
      Collection<DexType> synthesized = new ArrayList<>(existingList.values.length);
      for (DexValue element : existingList.getValues()) {
        if (!(element instanceof DexValue.DexValueType)) {
          throw new CompilationError(
              getInvalidSynthesizedClassMapMessage(programClass, foundAnnotation));
        }
        synthesized.add(((DexValue.DexValueType) element).value);
      }
      return synthesized;
    }
    return Collections.emptyList();
  }

  private static String getInvalidSynthesizedClassMapMessage(
      DexProgramClass annotatedClass,
      DexAnnotation invalidAnnotation) {
    return annotatedClass.toSourceString()
        + " is annotated with invalid "
        + invalidAnnotation.annotation.type.toString()
        + ": " + invalidAnnotation.toString();
  }

  public static DexAnnotation createAnnotationSynthesizedClassMap(
      TreeSet<DexType> synthesized,
      DexItemFactory dexItemFactory) {
    DexValue.DexValueType[] values = synthesized.stream()
        .map(DexValue.DexValueType::new)
        .toArray(DexValue.DexValueType[]::new);
    DexValue.DexValueArray array = new DexValue.DexValueArray(values);
    DexAnnotationElement pair =
        new DexAnnotationElement(dexItemFactory.createString("value"), array);
    return new DexAnnotation(
        VISIBILITY_BUILD,
        new DexEncodedAnnotation(
            dexItemFactory.annotationSynthesizedClassMap, new DexAnnotationElement[]{pair}));
  }

  public static boolean isSynthesizedClassMapAnnotation(DexAnnotation annotation,
      DexItemFactory factory) {
    return annotation.annotation.type == factory.annotationSynthesizedClassMap;
  }
}
