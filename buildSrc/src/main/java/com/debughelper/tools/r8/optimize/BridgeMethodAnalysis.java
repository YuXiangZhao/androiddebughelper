// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.optimize;

import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.GraphLense;
import com.debughelper.tools.r8.ir.code.Invoke.Type;
import com.debughelper.tools.r8.logging.Log;
import com.debughelper.tools.r8.optimize.InvokeSingleTargetExtractor;
import com.debughelper.tools.r8.optimize.InvokeSingleTargetExtractor.InvokeKind;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.debughelper.tools.r8.ir.code.Invoke;

import java.util.IdentityHashMap;
import java.util.Map;

public class BridgeMethodAnalysis {

  private final GraphLense lense;
  private final AppInfoWithLiveness appInfo;
  private final Map<DexMethod, DexMethod> bridgeTargetToBridgeMap = new IdentityHashMap<>();

  public BridgeMethodAnalysis(GraphLense lense, AppInfoWithLiveness appInfo) {
    this.lense = lense;
    this.appInfo = appInfo;
  }

  public GraphLense run() {
    for (DexClass clazz : appInfo.classes()) {
      clazz.forEachMethod(this::identifyBridgeMethod);
    }
    return new BridgeLense(lense, bridgeTargetToBridgeMap);
  }

  private void identifyBridgeMethod(DexEncodedMethod method) {
    // The tree pruner can mark bridge methods abstract if they are not reachable but cannot
    // be removed.
    if (method.accessFlags.isBridge() && !method.accessFlags.isAbstract()) {
      com.debughelper.tools.r8.optimize.InvokeSingleTargetExtractor targetExtractor = new InvokeSingleTargetExtractor();
      method.getCode().registerCodeReferences(targetExtractor);
      DexMethod target = targetExtractor.getTarget();
      InvokeKind kind = targetExtractor.getKind();
      if (target != null && target.getArity() == method.method.getArity()) {
        assert !method.accessFlags.isPrivate() && !method.accessFlags.isConstructor();
        if (kind == InvokeKind.STATIC) {
          assert method.accessFlags.isStatic();
          DexMethod actualTarget = lense.lookupMethod(target, method, Invoke.Type.STATIC);
          DexEncodedMethod targetMethod = appInfo.lookupStaticTarget(actualTarget);
          if (targetMethod != null) {
            addForwarding(method, targetMethod);
          }
        } else if (kind == InvokeKind.VIRTUAL) {
          // TODO(herhut): Add support for bridges with multiple targets.
          DexMethod actualTarget = lense.lookupMethod(target, method, Invoke.Type.VIRTUAL);
          DexEncodedMethod targetMethod = appInfo.lookupSingleVirtualTarget(actualTarget);
          if (targetMethod != null) {
            addForwarding(method, targetMethod);
          }
        }
      }
    }
  }

  private void addForwarding(DexEncodedMethod method, DexEncodedMethod target) {
    // This is a single target bridge we can inline.
    if (Log.ENABLED) {
      Log.info(getClass(), "Adding bridge forwarding %s -> %s.", method.method,
          target.method);
    }
    // If we manage to rewrite all invocations, the bridge will be the only invocation of the target
    // of the bridge and the target will get inlined. This should happen in most cases. For the few
    // other cases, we might have inserted some extra checkcast instructions for the return type.
    bridgeTargetToBridgeMap.put(target.method, method.method);
  }


  private static class BridgeLense extends GraphLense {

    private final GraphLense previousLense;
    private final Map<DexMethod, DexMethod> bridgeTargetToBridgeMap;

    private BridgeLense(GraphLense previousLense,
        Map<DexMethod, DexMethod> bridgeTargetToBridgeMap) {
      this.previousLense = previousLense;
      this.bridgeTargetToBridgeMap = bridgeTargetToBridgeMap;
    }

    @Override
    public DexType lookupType(DexType type) {
      return previousLense.lookupType(type);
    }

    @Override
    public DexMethod lookupMethod(DexMethod method, DexEncodedMethod context, Invoke.Type type) {
      DexMethod previous = previousLense.lookupMethod(method, context, type);
      DexMethod bridge = bridgeTargetToBridgeMap.get(previous);
      // Do not forward calls from a bridge method to itself while the bridge method is still
      // a bridge.
      if (bridge == null || (context.accessFlags.isBridge() && bridge == context.method)) {
        return previous;
      }
      return bridge;
    }

    @Override
    public DexField lookupField(DexField field) {
      return previousLense.lookupField(field);
    }

    @Override
    public boolean isContextFreeForMethods() {
      return false;
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append("------ BridgeMap ------").append(System.lineSeparator());
      for (Map.Entry<DexMethod, DexMethod> entry : bridgeTargetToBridgeMap.entrySet()) {
        builder.append(entry.getKey().toSourceString()).append(" -> ");
        builder.append(entry.getValue().toSourceString()).append(System.lineSeparator());
      }
      builder.append("-----------------------").append(System.lineSeparator());
      builder.append(previousLense.toString());
      return builder.toString();
    }
  }
}
