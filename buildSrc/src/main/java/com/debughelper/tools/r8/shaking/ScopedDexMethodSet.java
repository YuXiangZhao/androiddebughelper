// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.shaking;

import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.utils.MethodSignatureEquivalence;
import com.google.common.base.Equivalence;
import com.google.common.base.Equivalence.Wrapper;
import java.util.HashMap;
import java.util.Map;

class ScopedDexMethodSet {

  private static final Equivalence<DexMethod> METHOD_EQUIVALENCE = MethodSignatureEquivalence.get();

  private final ScopedDexMethodSet parent;
  private final Map<Wrapper<DexMethod>, DexEncodedMethod> items = new HashMap<>();

  public ScopedDexMethodSet() {
    this(null);
  }

  private ScopedDexMethodSet(ScopedDexMethodSet parent) {
    this.parent = parent;
  }

  public ScopedDexMethodSet newNestedScope() {
    return new ScopedDexMethodSet(this);
  }

  private DexEncodedMethod lookup(Wrapper<DexMethod> item) {
    DexEncodedMethod ownMethod = items.get(item);
    return ownMethod != null ? ownMethod : (parent != null ? parent.lookup(item) : null);
  }

  private boolean contains(Wrapper<DexMethod> item) {
    return lookup(item) != null;
  }

  public boolean addMethod(DexEncodedMethod method) {
    Wrapper<DexMethod> wrapped = METHOD_EQUIVALENCE.wrap(method.method);
    if (contains(wrapped)) {
      return false;
    }
    items.put(wrapped, method);
    return true;
  }

  public boolean addMethodIfMoreVisible(DexEncodedMethod method) {
    Wrapper<DexMethod> wrapped = METHOD_EQUIVALENCE.wrap(method.method);
    DexEncodedMethod existing = lookup(wrapped);
    if (existing == null || method.accessFlags.isMoreVisibleThan(existing.accessFlags)) {
      items.put(wrapped, method);
      return true;
    }
    return false;
  }

  public ScopedDexMethodSet getParent() {
    return parent;
  }
}
