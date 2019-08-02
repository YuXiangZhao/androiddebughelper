// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.optimize.lambda;

import com.debughelper.tools.r8.ir.optimize.lambda.CodeProcessor.Strategy;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.AppInfo;
import com.debughelper.tools.r8.graph.AppInfoWithSubtyping;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.kotlin.Kotlin;
import com.debughelper.tools.r8.utils.ThrowingConsumer;
import com.google.common.collect.Lists;
import com.google.common.io.BaseEncoding;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Predicate;

// Represents a group of lambda classes which potentially can be represented
// by the same lambda _group_ class. Each lambda class inside the group is
// assigned an integer id.
//
// NOTE: access to lambdas in lambda group is NOT thread-safe.
public abstract class LambdaGroup {
  public final LambdaGroupId id;

  // Lambda group class name. Is intended to be stable and uniques.
  // In current implementation is generated in following way:
  //       <optional-package>.-$$LambdaGroup$<HASH>
  // with HASH generated based on names of the lambda class names
  // of lambdas included in the group.
  private com.debughelper.tools.r8.graph.DexType classType;

  // Maps lambda classes belonging to the group into the index inside the
  // group. Note usage of linked hash map to keep insertion ordering stable.
  private final Map<com.debughelper.tools.r8.graph.DexType, LambdaInfo> lambdas = new LinkedHashMap<>();

  public static class LambdaInfo {
    public int id;
    public final com.debughelper.tools.r8.graph.DexProgramClass clazz;

    LambdaInfo(int id, com.debughelper.tools.r8.graph.DexProgramClass clazz) {
      this.id = id;
      this.clazz = clazz;
    }
  }

  public LambdaGroup(LambdaGroupId id) {
    this.id = id;
  }

  public final com.debughelper.tools.r8.graph.DexType getGroupClassType() {
    assert classType != null;
    return classType;
  }

  public final int size() {
    return lambdas.size();
  }

  public final void forEachLambda(Consumer<LambdaInfo> action) {
    assert verifyLambdaIds(false);
    for (LambdaInfo info : lambdas.values()) {
      action.accept(info);
    }
  }

  public final boolean anyLambda(Predicate<LambdaInfo> predicate) {
    assert verifyLambdaIds(false);
    for (LambdaInfo info : lambdas.values()) {
      if (predicate.test(info)) {
        return true;
      }
    }
    return false;
  }

  final boolean shouldAddToMainDex(AppInfo appInfo) {
    // We add the group class to main index if any of the
    // lambda classes it replaces is added to main index.
    for (com.debughelper.tools.r8.graph.DexType type : lambdas.keySet()) {
      if (appInfo.isInMainDexList(type)) {
        return true;
      }
    }
    return false;
  }

  public final boolean containsLambda(com.debughelper.tools.r8.graph.DexType lambda) {
    return lambdas.containsKey(lambda);
  }

  public final int lambdaId(com.debughelper.tools.r8.graph.DexType lambda) {
    assert lambdas.containsKey(lambda);
    return lambdas.get(lambda).id;
  }

  protected final com.debughelper.tools.r8.graph.DexEncodedField[] lambdaCaptureFields(com.debughelper.tools.r8.graph.DexType lambda) {
    assert lambdas.containsKey(lambda);
    return lambdas.get(lambda).clazz.instanceFields();
  }

  protected final com.debughelper.tools.r8.graph.DexEncodedField lambdaSingletonField(com.debughelper.tools.r8.graph.DexType lambda) {
    assert lambdas.containsKey(lambda);
    DexEncodedField[] fields = lambdas.get(lambda).clazz.staticFields();
    assert fields.length < 2;
    return fields.length == 0 ? null : fields[0];
  }

  // Contains less than 2 elements?
  final boolean isTrivial() {
    return lambdas.size() < 2;
  }

  final void add(com.debughelper.tools.r8.graph.DexProgramClass lambda) {
    assert !lambdas.containsKey(lambda.type);
    lambdas.put(lambda.type, new LambdaInfo(lambdas.size(), lambda));
  }

  final void remove(com.debughelper.tools.r8.graph.DexType lambda) {
    assert lambdas.containsKey(lambda);
    lambdas.remove(lambda);
  }

  final void compact() {
    assert verifyLambdaIds(false);
    int lastUsed = -1;
    int lastSeen = -1;
    for (Entry<DexType, LambdaInfo> entry : lambdas.entrySet()) {
      Integer index = entry.getValue().id;
      assert lastUsed <= lastSeen && lastSeen < index;
      lastUsed++;
      lastSeen = index;
      if (lastUsed < index) {
        entry.getValue().id = lastUsed;
      }
    }
    assert verifyLambdaIds(true);
  }

  public abstract CodeProcessor.Strategy getCodeStrategy();

  public abstract ThrowingConsumer<DexClass, LambdaStructureError> lambdaClassValidator(
          Kotlin kotlin, AppInfoWithSubtyping appInfo);

  // Package for a lambda group class to be created in.
  protected abstract String getTypePackage();

  protected abstract String getGroupSuffix();

  final DexProgramClass synthesizeClass(com.debughelper.tools.r8.graph.DexItemFactory factory) {
    assert classType == null;
    assert verifyLambdaIds(true);
    List<LambdaInfo> lambdas = Lists.newArrayList(this.lambdas.values());
    classType = factory.createType(
        "L" + getTypePackage() + "-$$LambdaGroup$" + getGroupSuffix() + createHash(lambdas) + ";");
    return getBuilder(factory).synthesizeClass();
  }

  protected abstract LambdaGroupClassBuilder getBuilder(DexItemFactory factory);

  private String createHash(List<LambdaInfo> lambdas) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      ObjectOutputStream out = new ObjectOutputStream(bytes);

      // We will generate SHA-1 hash of the list of lambda classes represented in the group.
      for (LambdaInfo lambda : lambdas) {
        DexString descriptor = lambda.clazz.type.descriptor;
        out.writeInt(descriptor.size); // To avoid same-prefix problem
        out.write(descriptor.content);
      }
      out.close();

      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      digest.update(bytes.toByteArray());
      return BaseEncoding.base64Url().omitPadding().encode(digest.digest());
    } catch (NoSuchAlgorithmException | IOException ex) {
      throw new Unreachable("Cannot get SHA-1 message digest");
    }
  }

  private boolean verifyLambdaIds(boolean strict) {
    int previous = -1;
    for (LambdaInfo info : lambdas.values()) {
      assert strict ? (previous + 1) == info.id : previous < info.id;
      previous = info.id;
    }
    return true;
  }

  public static class LambdaStructureError extends Exception {
    final boolean reportable;

    public LambdaStructureError(String cause) {
      this(cause, true);
    }

    public LambdaStructureError(String cause, boolean reportable) {
      super(cause);
      this.reportable = reportable;
    }
  }
}
