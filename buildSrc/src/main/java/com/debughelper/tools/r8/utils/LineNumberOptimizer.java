// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.utils;

import com.debughelper.tools.r8.graph.Code;
import com.debughelper.tools.r8.graph.DexApplication;
import com.debughelper.tools.r8.graph.DexCode;
import com.debughelper.tools.r8.graph.DexDebugEvent;
import com.debughelper.tools.r8.graph.DexDebugEventBuilder;
import com.debughelper.tools.r8.graph.DexDebugEventVisitor;
import com.debughelper.tools.r8.graph.DexDebugInfo;
import com.debughelper.tools.r8.graph.DexDebugPositionState;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.naming.ClassNaming;
import com.debughelper.tools.r8.naming.ClassNaming.Builder;
import com.debughelper.tools.r8.naming.MemberNaming;
import com.debughelper.tools.r8.naming.MemberNaming.FieldSignature;
import com.debughelper.tools.r8.naming.MemberNaming.MethodSignature;
import com.debughelper.tools.r8.utils.DescriptorUtils;
import com.debughelper.tools.r8.ir.code.Position;
import com.debughelper.tools.r8.naming.ClassNameMapper;
import com.debughelper.tools.r8.naming.NamingLens;
import com.debughelper.tools.r8.naming.Range;
import com.google.common.base.Suppliers;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public class LineNumberOptimizer {

  // EventFilter is a visitor for DebugEvents, splits events into two sinks:
  // - Forwards non-positional events unchanged into a BypassedEventReceiver
  // - Forwards positional events, accumulated into DexDebugPositionStates, into
  //   positionEventReceiver.
  private static class EventFilter implements DexDebugEventVisitor {
    private final BypassedEventReceiver bypassedEventReceiver;
    private final PositionEventReceiver positionEventReceiver;

    private interface BypassedEventReceiver {
      void receiveBypassedEvent(DexDebugEvent event);
    }

    private interface PositionEventReceiver {
      void receivePositionEvent(DexDebugPositionState positionState);
    }

    private final DexDebugPositionState positionState;

    private EventFilter(
        int startLine,
        DexMethod method,
        BypassedEventReceiver bypassedEventReceiver,
        PositionEventReceiver positionEventReceiver) {
      positionState = new DexDebugPositionState(startLine, method);
      this.bypassedEventReceiver = bypassedEventReceiver;
      this.positionEventReceiver = positionEventReceiver;
    }

    @Override
    public void visit(DexDebugEvent.SetPrologueEnd event) {
      bypassedEventReceiver.receiveBypassedEvent(event);
    }

    @Override
    public void visit(DexDebugEvent.SetEpilogueBegin event) {
      bypassedEventReceiver.receiveBypassedEvent(event);
    }

    @Override
    public void visit(DexDebugEvent.StartLocal event) {
      bypassedEventReceiver.receiveBypassedEvent(event);
    }

    @Override
    public void visit(DexDebugEvent.EndLocal event) {
      bypassedEventReceiver.receiveBypassedEvent(event);
    }

    @Override
    public void visit(DexDebugEvent.RestartLocal event) {
      bypassedEventReceiver.receiveBypassedEvent(event);
    }

    @Override
    public void visit(DexDebugEvent.AdvancePC advancePC) {
      positionState.visit(advancePC);
    }

    @Override
    public void visit(DexDebugEvent.AdvanceLine advanceLine) {
      positionState.visit(advanceLine);
    }

    @Override
    public void visit(DexDebugEvent.SetInlineFrame setInlineFrame) {
      positionState.visit(setInlineFrame);
    }

    @Override
    public void visit(DexDebugEvent.Default defaultEvent) {
      positionState.visit(defaultEvent);
      positionEventReceiver.receivePositionEvent(positionState);
    }

    @Override
    public void visit(DexDebugEvent.SetFile setFile) {
      positionState.visit(setFile);
    }
  }

  // PositionRemapper is a stateful function which takes a position (represented by a
  // DexDebugPositionState) and returns a remapped Position.
  private interface PositionRemapper {
    com.debughelper.tools.r8.ir.code.Position createRemappedPosition(DexDebugPositionState positionState);
  }

  private static class IdentityPositionRemapper implements PositionRemapper {
    @Override
    public com.debughelper.tools.r8.ir.code.Position createRemappedPosition(DexDebugPositionState positionState) {
      return new com.debughelper.tools.r8.ir.code.Position(
          positionState.getCurrentLine(),
          positionState.getCurrentFile(),
          positionState.getCurrentMethod(),
          positionState.getCurrentCallerPosition());
    }
  }

  private static class OptimizingPositionRemapper implements PositionRemapper {
    private int nextLineNumber = 1;

    @Override
    public com.debughelper.tools.r8.ir.code.Position createRemappedPosition(DexDebugPositionState positionState) {
      com.debughelper.tools.r8.ir.code.Position newPosition =
          new com.debughelper.tools.r8.ir.code.Position(
              nextLineNumber,
              positionState.getCurrentFile(),
              positionState.getCurrentMethod(),
              null);
      ++nextLineNumber;
      return newPosition;
    }
  }

  // PositionEventEmitter is a stateful function which converts a Position into series of
  // position-related DexDebugEvents and puts them into a processedEvents list.
  private static class PositionEventEmitter {
    private final DexItemFactory dexItemFactory;
    private int startLine = -1;
    private final DexMethod method;
    private int previousPc = DexDebugEventBuilder.NO_PC_INFO;
    private com.debughelper.tools.r8.ir.code.Position previousPosition = null;
    private final List<DexDebugEvent> processedEvents;

    private PositionEventEmitter(
        DexItemFactory dexItemFactory, DexMethod method, List<DexDebugEvent> processedEvents) {
      this.dexItemFactory = dexItemFactory;
      this.method = method;
      this.processedEvents = processedEvents;
    }

    private void emitPositionEvents(int currentPc, com.debughelper.tools.r8.ir.code.Position currentPosition) {
      if (previousPosition == null) {
        startLine = currentPosition.line;
        previousPosition = new com.debughelper.tools.r8.ir.code.Position(startLine, null, method, null);
      }
      DexDebugEventBuilder.emitAdvancementEvents(
          previousPc,
          previousPosition,
          currentPc,
          currentPosition,
          processedEvents,
          dexItemFactory);
      previousPc = currentPc;
      previousPosition = currentPosition;
    }

    private int getStartLine() {
      assert (startLine >= 0);
      return startLine;
    }
  }

  // We will be remapping positional debug events and collect them as MappedPositions.
  private static class MappedPosition {
    private final DexMethod method;
    private final int originalLine;
    private final com.debughelper.tools.r8.ir.code.Position caller;
    private final int obfuscatedLine;

    private MappedPosition(
            DexMethod method, int originalLine, com.debughelper.tools.r8.ir.code.Position caller, int obfuscatedLine) {
      this.method = method;
      this.originalLine = originalLine;
      this.caller = caller;
      this.obfuscatedLine = obfuscatedLine;
    }
  }

  public static com.debughelper.tools.r8.naming.ClassNameMapper run(
          DexApplication application, com.debughelper.tools.r8.naming.NamingLens namingLens, boolean identityMapping) {
    IdentityHashMap<DexString, List<DexProgramClass>> classesOfFiles = new IdentityHashMap<>();
    com.debughelper.tools.r8.naming.ClassNameMapper.Builder classNameMapperBuilder = ClassNameMapper.builder();
    // Collect which files contain which classes that need to have their line numbers optimized.
    for (DexProgramClass clazz : application.classes()) {

      // TODO(tamaskenez) fix b/69356670 and remove the conditional skipping.
      if (!clazz.getSynthesizedFrom().isEmpty()) {
        continue;
      }

      IdentityHashMap<DexString, List<DexEncodedMethod>> methodsByName =
          groupMethodsByName(namingLens, clazz);

      // At this point we don't know if we really need to add this class to the builder.
      // It depends on whether any methods/fields are renamed or some methods contain positions.
      // Create a supplier which creates a new, cached ClassNaming.Builder on-demand.
      DexString renamedClassName = namingLens.lookupDescriptor(clazz.getType());
      Supplier<com.debughelper.tools.r8.naming.ClassNaming.Builder> onDemandClassNamingBuilder =
          Suppliers.memoize(
              () ->
                  classNameMapperBuilder.classNamingBuilder(
                      DescriptorUtils.descriptorToJavaType(renamedClassName.toString()),
                      clazz.toString()));

      // If the class is renamed add it to the classNamingBuilder.
      addClassToClassNaming(clazz, renamedClassName, onDemandClassNamingBuilder);

      // First transfer renamed fields to classNamingBuilder.
      addFieldsToClassNaming(namingLens, clazz, onDemandClassNamingBuilder);

      // Then process the methods.
      for (List<DexEncodedMethod> methods : methodsByName.values()) {
        if (methods.size() > 1) {
          // If there are multiple methods with the same name (overloaded) then sort them for
          // deterministic behaviour: the algorithm will assign new line numbers in this order.
          // Methods with different names can share the same line numbers, that's why they don't
          // need to be sorted.
          sortMethods(methods);
        }

        PositionRemapper positionRemapper =
            identityMapping ? new IdentityPositionRemapper() : new OptimizingPositionRemapper();

        for (DexEncodedMethod method : methods) {
          List<MappedPosition> mappedPositions = new ArrayList<>();

          if (doesContainPositions(method)) {
            // Do the actual processing for each method.
            DexCode dexCode = method.getCode().asDexCode();
            DexDebugInfo debugInfo = dexCode.getDebugInfo();
            List<DexDebugEvent> processedEvents = new ArrayList<>();

            // Our pipeline will be:
            // [debugInfo.events] -> eventFilter -> positionRemapper -> positionEventEmitter ->
            // [processedEvents]
            PositionEventEmitter positionEventEmitter =
                new PositionEventEmitter(
                    application.dexItemFactory, method.method, processedEvents);

            EventFilter eventFilter =
                new EventFilter(
                    debugInfo.startLine,
                    method.method,
                    processedEvents::add,
                    positionState -> {
                      int currentLine = positionState.getCurrentLine();
                      assert currentLine >= 0;
                      com.debughelper.tools.r8.ir.code.Position position = positionRemapper.createRemappedPosition(positionState);
                      mappedPositions.add(
                          new MappedPosition(
                              positionState.getCurrentMethod(),
                              currentLine,
                              positionState.getCurrentCallerPosition(),
                              position.line));
                      positionEventEmitter.emitPositionEvents(
                          positionState.getCurrentPc(), position);
                    });
            for (DexDebugEvent event : debugInfo.events) {
              event.accept(eventFilter);
            }

            DexDebugInfo optimizedDebugInfo =
                new DexDebugInfo(
                    positionEventEmitter.getStartLine(),
                    debugInfo.parameters,
                    processedEvents.toArray(new DexDebugEvent[processedEvents.size()]));

            // TODO(tamaskenez) Remove this as soon as we have external tests testing not only the
            // remapping but whether the non-positional debug events remain intact.
            if (identityMapping) {
              assert optimizedDebugInfo.startLine == debugInfo.startLine;
              assert optimizedDebugInfo.events.length == debugInfo.events.length;
              for (int i = 0; i < debugInfo.events.length; ++i) {
                assert optimizedDebugInfo.events[i].equals(debugInfo.events[i]);
              }
            }
            dexCode.setDebugInfo(optimizedDebugInfo);
          }

          com.debughelper.tools.r8.naming.MemberNaming.MethodSignature originalSignature = com.debughelper.tools.r8.naming.MemberNaming.MethodSignature.fromDexMethod(method.method);

          DexString obfuscatedNameDexString = namingLens.lookupName(method.method);
          String obfuscatedName = obfuscatedNameDexString.toString();

          // Add simple "a() -> b" mapping if we won't have any other with concrete line numbers
          if (mappedPositions.isEmpty()) {
            // But only if it's been renamed.
            if (obfuscatedNameDexString != method.method.name) {
              onDemandClassNamingBuilder
                  .get()
                  .addMappedRange(null, originalSignature, null, obfuscatedName);
            }
            continue;
          }

          Map<DexMethod, com.debughelper.tools.r8.naming.MemberNaming.MethodSignature> signatures = new IdentityHashMap<>();
          signatures.put(method.method, originalSignature);

          com.debughelper.tools.r8.naming.MemberNaming memberNaming = new com.debughelper.tools.r8.naming.MemberNaming(originalSignature, obfuscatedName);
          onDemandClassNamingBuilder.get().addMemberEntry(memberNaming);

          // Update memberNaming with the collected positions, merging multiple positions into a
          // single region whenever possible.
          for (int i = 0; i < mappedPositions.size(); /* updated in body */ ) {
            MappedPosition firstPosition = mappedPositions.get(i);
            int j = i + 1;
            MappedPosition lastPosition = firstPosition;
            for (; j < mappedPositions.size(); j++) {
              // Break if this position cannot be merged with lastPosition.
              MappedPosition mp = mappedPositions.get(j);
              // Note that mp.caller and lastPosition.class must be deep-compared since multiple
              // inlining passes lose the canonical property of the positions.
              if ((mp.method != lastPosition.method)
                  || (mp.originalLine - lastPosition.originalLine
                      != mp.obfuscatedLine - lastPosition.obfuscatedLine)
                  || !Objects.equals(mp.caller, lastPosition.caller)) {
                break;
              }
              lastPosition = mp;
            }
            com.debughelper.tools.r8.naming.Range obfuscatedRange =
                new com.debughelper.tools.r8.naming.Range(firstPosition.obfuscatedLine, lastPosition.obfuscatedLine);
            com.debughelper.tools.r8.naming.Range originalRange = new Range(firstPosition.originalLine, lastPosition.originalLine);

            com.debughelper.tools.r8.naming.ClassNaming.Builder classNamingBuilder = onDemandClassNamingBuilder.get();
            classNamingBuilder.addMappedRange(
                obfuscatedRange,
                signatures.computeIfAbsent(
                    firstPosition.method,
                    m ->
                        com.debughelper.tools.r8.naming.MemberNaming.MethodSignature.fromDexMethod(
                            m, firstPosition.method.holder != clazz.getType())),
                originalRange,
                obfuscatedName);
            com.debughelper.tools.r8.ir.code.Position caller = firstPosition.caller;
            while (caller != null) {
              Position finalCaller = caller;
              classNamingBuilder.addMappedRange(
                  obfuscatedRange,
                  signatures.computeIfAbsent(
                      caller.method,
                      m ->
                          com.debughelper.tools.r8.naming.MemberNaming.MethodSignature.fromDexMethod(
                              m, finalCaller.method.holder != clazz.getType())),
                  Math.max(caller.line, 0), // Prevent against "no-position".
                  obfuscatedName);
              caller = caller.callerPosition;
            }
            i = j;
          }
        } // for each method of the group
      } // for each method group, grouped by name
    } // for each class
    return classNameMapperBuilder.build();
  }

  // Sort by startline, then DexEncodedMethod.slowCompare.
  // Use startLine = 0 if no debuginfo.
  private static void sortMethods(List<DexEncodedMethod> methods) {
    methods.sort(
        (lhs, rhs) -> {
          Code lhsCode = lhs.getCode();
          Code rhsCode = rhs.getCode();
          DexCode lhsDexCode =
              lhsCode == null || !lhsCode.isDexCode() ? null : lhsCode.asDexCode();
          DexCode rhsDexCode =
              rhsCode == null || !rhsCode.isDexCode() ? null : rhsCode.asDexCode();
          DexDebugInfo lhsDebugInfo = lhsDexCode == null ? null : lhsDexCode.getDebugInfo();
          DexDebugInfo rhsDebugInfo = rhsDexCode == null ? null : rhsDexCode.getDebugInfo();
          int lhsStartLine = lhsDebugInfo == null ? 0 : lhsDebugInfo.startLine;
          int rhsStartLine = rhsDebugInfo == null ? 0 : rhsDebugInfo.startLine;
          int startLineDiff = lhsStartLine - rhsStartLine;
          if (startLineDiff != 0) return startLineDiff;
          return DexEncodedMethod.slowCompare(lhs, rhs);
        });
  }

  @SuppressWarnings("ReturnValueIgnored")
  private static void addClassToClassNaming(DexProgramClass clazz, DexString renamedClassName,
      Supplier<com.debughelper.tools.r8.naming.ClassNaming.Builder> onDemandClassNamingBuilder) {
    // We do know we need to create a ClassNaming.Builder if the class itself had been renamed.
    if (!clazz.toString().equals(renamedClassName.toString())) {
      // Not using return value, it's registered in classNameMapperBuilder
      onDemandClassNamingBuilder.get();
    }
  }

  private static void addFieldsToClassNaming(com.debughelper.tools.r8.naming.NamingLens namingLens, DexProgramClass clazz,
                                             Supplier<com.debughelper.tools.r8.naming.ClassNaming.Builder> onDemandClassNamingBuilder) {
    clazz.forEachField(
        dexEncodedField -> {
          DexField dexField = dexEncodedField.field;
          DexString renamedName = namingLens.lookupName(dexField);
          if (renamedName != dexField.name) {
            com.debughelper.tools.r8.naming.MemberNaming.FieldSignature signature =
                new com.debughelper.tools.r8.naming.MemberNaming.FieldSignature(dexField.name.toString(), dexField.type.toString());
            com.debughelper.tools.r8.naming.MemberNaming memberNaming = new com.debughelper.tools.r8.naming.MemberNaming(signature, renamedName.toString());
            onDemandClassNamingBuilder.get().addMemberEntry(memberNaming);
          }
        });
  }

  private static IdentityHashMap<DexString, List<DexEncodedMethod>> groupMethodsByName(
          NamingLens namingLens, DexProgramClass clazz) {
    IdentityHashMap<DexString, List<DexEncodedMethod>> methodsByName =
        new IdentityHashMap<>(clazz.directMethods().length + clazz.virtualMethods().length);
    clazz.forEachMethod(
        method -> {
          // Add method only if renamed or contains positions.
          if (namingLens.lookupName(method.method) != method.method.name
              || doesContainPositions(method)) {
            methodsByName.compute(
                method.method.name,
                (name, methods) -> {
                  if (methods == null) {
                    methods = new ArrayList<>();
                  }
                  methods.add(method);
                  return methods;
                });
          }
        });
    return methodsByName;
  }

  private static boolean doesContainPositions(DexEncodedMethod method) {
    Code code = method.getCode();
    if (code == null || !code.isDexCode()) {
      return false;
    }
    DexDebugInfo debugInfo = code.asDexCode().getDebugInfo();
    if (debugInfo == null) {
      return false;
    }
    for (DexDebugEvent event : debugInfo.events) {
      if (event instanceof DexDebugEvent.Default) {
        return true;
      }
    }
    return false;
  }
}
