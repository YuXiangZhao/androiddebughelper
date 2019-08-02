// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.optimize.lambda.kotlin;

import com.debughelper.tools.r8.graph.DexValue.DexValueNull;
import com.debughelper.tools.r8.graph.ClassAccessFlags;
import com.debughelper.tools.r8.graph.DexAnnotation;
import com.debughelper.tools.r8.graph.DexAnnotationSet;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexProto;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.DexTypeList;
import com.debughelper.tools.r8.graph.DexValue;
import com.debughelper.tools.r8.graph.EnclosingMethodAttribute;
import com.debughelper.tools.r8.graph.InnerClassAttribute;
import com.debughelper.tools.r8.graph.MethodAccessFlags;
import com.debughelper.tools.r8.graph.ParameterAnnotationsList;
import com.debughelper.tools.r8.ir.optimize.lambda.LambdaGroupClassBuilder;
import com.debughelper.tools.r8.ir.synthetic.SynthesizedCode;
import com.debughelper.tools.r8.ir.synthetic.SyntheticSourceCode;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

// Builds components of kotlin lambda group class.
abstract class KotlinLambdaGroupClassBuilder<T extends KotlinLambdaGroup>
    extends LambdaGroupClassBuilder<T> implements KotlinLambdaConstants {

  final KotlinLambdaGroupId id;

  KotlinLambdaGroupClassBuilder(T group, DexItemFactory factory, String origin) {
    super(group, factory, origin);
    this.id = group.id();
  }

  abstract SyntheticSourceCode createInstanceInitializerSourceCode(
          com.debughelper.tools.r8.graph.DexType groupClassType, com.debughelper.tools.r8.graph.DexProto initializerProto);

  // Always generate public final classes.
  @Override
  protected ClassAccessFlags buildAccessFlags() {
    return PUBLIC_LAMBDA_CLASS_FLAGS;
  }

  // Take the attribute from the group, if exists.
  @Override
  protected EnclosingMethodAttribute buildEnclosingMethodAttribute() {
    return id.enclosing;
  }

  // Take the attribute from the group, if exists.
  @Override
  protected List<com.debughelper.tools.r8.graph.InnerClassAttribute> buildInnerClasses() {
    return !id.hasInnerClassAttribute() ? Collections.emptyList()
        : Lists.newArrayList(new InnerClassAttribute(
            id.innerClassAccess, group.getGroupClassType(), null, null));
  }

  @Override
  protected com.debughelper.tools.r8.graph.DexAnnotationSet buildAnnotations() {
    // Kotlin-style lambdas supported by the merged may only contain optional signature and
    // kotlin metadata annotations. We remove the latter, but keep the signature if present.
    String signature = id.signature;
    return signature == null ? com.debughelper.tools.r8.graph.DexAnnotationSet.empty()
        : new com.debughelper.tools.r8.graph.DexAnnotationSet(new com.debughelper.tools.r8.graph.DexAnnotation[]{
            DexAnnotation.createSignatureAnnotation(signature, factory)});
  }

  @Override
  protected com.debughelper.tools.r8.graph.DexEncodedMethod[] buildVirtualMethods() {
    // All virtual method are dispatched on $id$ field.
    //
    // For each of the virtual method name/signatures seen in the group
    // we generate a correspondent method in lambda group class with same
    // name/signatures dispatching the call to appropriate code taken
    // from the lambda class.

    Map<com.debughelper.tools.r8.graph.DexString, Map<com.debughelper.tools.r8.graph.DexProto, List<com.debughelper.tools.r8.graph.DexEncodedMethod>>> methods = collectVirtualMethods();
    List<com.debughelper.tools.r8.graph.DexEncodedMethod> result = new ArrayList<>();

    for (Entry<com.debughelper.tools.r8.graph.DexString, Map<com.debughelper.tools.r8.graph.DexProto, List<com.debughelper.tools.r8.graph.DexEncodedMethod>>> upper : methods.entrySet()) {
      com.debughelper.tools.r8.graph.DexString methodName = upper.getKey();
      for (Entry<com.debughelper.tools.r8.graph.DexProto, List<com.debughelper.tools.r8.graph.DexEncodedMethod>> inner : upper.getValue().entrySet()) {
        // Methods for unique name/signature pair.
        com.debughelper.tools.r8.graph.DexProto methodProto = inner.getKey();
        List<com.debughelper.tools.r8.graph.DexEncodedMethod> implMethods = inner.getValue();

        boolean isMainMethod =
            id.mainMethodName == methodName && id.mainMethodProto == methodProto;

        // For bridge methods we still use same PUBLIC FINAL as for the main method,
        // since inlining removes BRIDGE & SYNTHETIC attributes from the bridge methods
        // anyways and our new method is a product of inlining.
        MethodAccessFlags accessFlags = MAIN_METHOD_FLAGS.copy();

        // Mark all the impl methods for force inlining
        // LambdaGroupVirtualMethodSourceCode relies on.
        for (com.debughelper.tools.r8.graph.DexEncodedMethod implMethod : implMethods) {
          if (implMethod != null) {
            implMethod.markForceInline();
          }
        }

        result.add(new com.debughelper.tools.r8.graph.DexEncodedMethod(
            factory.createMethod(group.getGroupClassType(), methodProto, methodName),
            accessFlags,
            isMainMethod ? id.mainMethodAnnotations : com.debughelper.tools.r8.graph.DexAnnotationSet.empty(),
            isMainMethod ? id.mainMethodParamAnnotations : com.debughelper.tools.r8.graph.ParameterAnnotationsList.empty(),
            new com.debughelper.tools.r8.ir.synthetic.SynthesizedCode(
                new KotlinLambdaVirtualMethodSourceCode(factory, group.getGroupClassType(),
                    methodProto, group.getLambdaIdField(factory), implMethods))));
      }
    }

    return result.toArray(new com.debughelper.tools.r8.graph.DexEncodedMethod[result.size()]);
  }

  // Build a map of virtual methods with unique name/proto pointing to a list of methods
  // from lambda classes implementing appropriate logic. The indices in the list correspond
  // to lambda ids. Note that some of the slots in the lists may be empty, indicating the
  // fact that corresponding lambda does not have a virtual method with this signature.
  private Map<com.debughelper.tools.r8.graph.DexString, Map<com.debughelper.tools.r8.graph.DexProto, List<com.debughelper.tools.r8.graph.DexEncodedMethod>>> collectVirtualMethods() {
    Map<DexString, Map<com.debughelper.tools.r8.graph.DexProto, List<com.debughelper.tools.r8.graph.DexEncodedMethod>>> methods = new LinkedHashMap<>();
    int size = group.size();
    group.forEachLambda(info -> {
      for (com.debughelper.tools.r8.graph.DexEncodedMethod method : info.clazz.virtualMethods()) {
        List<com.debughelper.tools.r8.graph.DexEncodedMethod> list = methods
            .computeIfAbsent(method.method.name,
                k -> new LinkedHashMap<>())
            .computeIfAbsent(method.method.proto,
                k -> Lists.newArrayList(Collections.nCopies(size, null)));
        assert list.get(info.id) == null;
        list.set(info.id, method);
      }
    });
    return methods;
  }

  @Override
  protected com.debughelper.tools.r8.graph.DexEncodedMethod[] buildDirectMethods() {
    // We only build an instance initializer and optional class
    // initializer for stateless lambdas.

    boolean needsSingletonInstances = group.isStateless() && group.hasAnySingletons();
    com.debughelper.tools.r8.graph.DexType groupClassType = group.getGroupClassType();

    com.debughelper.tools.r8.graph.DexEncodedMethod[] result = new com.debughelper.tools.r8.graph.DexEncodedMethod[needsSingletonInstances ? 2 : 1];
    // Instance initializer mapping parameters into capture fields.
    DexProto initializerProto = group.createConstructorProto(factory);
    result[0] = new com.debughelper.tools.r8.graph.DexEncodedMethod(
        factory.createMethod(groupClassType, initializerProto, factory.constructorMethodName),
        CONSTRUCTOR_FLAGS_RELAXED,  // always create access-relaxed constructor.
        com.debughelper.tools.r8.graph.DexAnnotationSet.empty(),
        com.debughelper.tools.r8.graph.ParameterAnnotationsList.empty(),
        new com.debughelper.tools.r8.ir.synthetic.SynthesizedCode(createInstanceInitializerSourceCode(groupClassType, initializerProto)));

    // Static class initializer for stateless lambdas.
    if (needsSingletonInstances) {
      result[1] = new DexEncodedMethod(
          factory.createMethod(groupClassType,
              factory.createProto(factory.voidType),
              factory.classConstructorMethodName),
          CLASS_INITIALIZER_FLAGS,
          com.debughelper.tools.r8.graph.DexAnnotationSet.empty(),
          ParameterAnnotationsList.empty(),
          new SynthesizedCode(new ClassInitializerSourceCode(factory, group)));
    }

    return result;
  }

  @Override
  protected com.debughelper.tools.r8.graph.DexEncodedField[] buildInstanceFields() {
    // Lambda id field plus other fields defined by the capture signature.
    String capture = id.capture;
    int size = capture.length();
    com.debughelper.tools.r8.graph.DexEncodedField[] result = new com.debughelper.tools.r8.graph.DexEncodedField[1 + size];

    result[0] = new com.debughelper.tools.r8.graph.DexEncodedField(group.getLambdaIdField(factory),
        CAPTURE_FIELD_FLAGS_RELAXED, com.debughelper.tools.r8.graph.DexAnnotationSet.empty(), null);

    for (int id = 0; id < size; id++) {
      result[id + 1] = new com.debughelper.tools.r8.graph.DexEncodedField(group.getCaptureField(factory, id),
          CAPTURE_FIELD_FLAGS_RELAXED, com.debughelper.tools.r8.graph.DexAnnotationSet.empty(), null);
    }

    return result;
  }

  @Override
  protected com.debughelper.tools.r8.graph.DexEncodedField[] buildStaticFields() {
    if (!group.isStateless()) {
      return com.debughelper.tools.r8.graph.DexEncodedField.EMPTY_ARRAY;
    }
    // One field for each singleton lambda in the group.
    List<com.debughelper.tools.r8.graph.DexEncodedField> result = new ArrayList<>(group.size());
    group.forEachLambda(info -> {
      if (group.isSingletonLambda(info.clazz.type)) {
        result.add(new com.debughelper.tools.r8.graph.DexEncodedField(group.getSingletonInstanceField(factory, info.id),
            SINGLETON_FIELD_FLAGS, DexAnnotationSet.empty(), DexValue.DexValueNull.NULL));
      }
    });
    assert result.isEmpty() == !group.hasAnySingletons();
    return result.toArray(new DexEncodedField[result.size()]);
  }

  @Override
  protected com.debughelper.tools.r8.graph.DexTypeList buildInterfaces() {
    return new DexTypeList(new DexType[]{id.iface});
  }
}
