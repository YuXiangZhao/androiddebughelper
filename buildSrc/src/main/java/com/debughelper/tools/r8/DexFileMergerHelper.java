// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8;

import com.debughelper.tools.r8.dex.ApplicationReader;
import com.debughelper.tools.r8.dex.ApplicationWriter;
import com.debughelper.tools.r8.dex.Marker;
import com.debughelper.tools.r8.graph.AppInfo;
import com.debughelper.tools.r8.graph.DexApplication;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.naming.NamingLens;
import com.debughelper.tools.r8.utils.AndroidApp;
import com.debughelper.tools.r8.utils.ExceptionUtils;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.debughelper.tools.r8.utils.ThreadUtils;
import com.debughelper.tools.r8.utils.Timing;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

public class DexFileMergerHelper {

  private final Map<String, Integer> inputOrdering;

  private DexFileMergerHelper(Map<String, Integer> inputOrdering) {
    this.inputOrdering = inputOrdering;
  }

  private DexProgramClass keepFirstProgramClassConflictResolver(
      DexProgramClass a, DexProgramClass b) {
    String aPath = a.getOrigin().parent().part();
    String bPath = b.getOrigin().parent().part();
    Integer aIndex = inputOrdering.get(aPath);
    Integer bIndex = inputOrdering.get(bPath);
    if (aIndex == null || bIndex == null) {
      StringBuilder builder = new StringBuilder();
      builder.append("Class parent paths not found among input paths: ");
      if (aIndex == null) {
        builder.append(aPath);
      }
      if (bIndex == null) {
        if (aIndex == null) {
          builder.append(", ");
        }
        builder.append(bPath);
      }
      throw new RuntimeException(builder.toString());
    }
    return aIndex <= bIndex ? a.get() : b.get();
  }

  public static void run(
      D8Command command, Boolean minimalMainDex, Map<String, Integer> inputOrdering)
      throws CompilationFailedException {
    InternalOptions options = command.getInternalOptions();
    ExceptionUtils.withD8CompilationHandler(
        options.reporter,
        () -> runInternal(command.getInputApp(), options, minimalMainDex, inputOrdering));
  }

  private static void runInternal(
      AndroidApp inputApp,
      InternalOptions options,
      Boolean minimalMainDex,
      Map<String, Integer> inputOrdering)
      throws IOException {
    options.enableDesugaring = false;
    options.enableMainDexListCheck = false;
    options.minimalMainDex = minimalMainDex;
    options.enableMinification = false;
    options.enableInlining = false;
    options.outline.enabled = false;

    ExecutorService executor = ThreadUtils.getExecutorService(ThreadUtils.NOT_SPECIFIED);
    try {
      try {
        Timing timing = new Timing("DexFileMerger");
        DexApplication app =
            new ApplicationReader(inputApp, options, timing)
                .read(
                    null,
                    executor,
                    new DexFileMergerHelper(inputOrdering)::keepFirstProgramClassConflictResolver);
        AppInfo appInfo = new AppInfo(app);
        app = D8.optimize(app, appInfo, options, timing, executor);

        List<Marker> markers = app.dexItemFactory.extractMarkers();

        assert !options.hasMethodsFilter();
        new ApplicationWriter(app, options, markers, null, NamingLens.getIdentityLens(), null, null)
            .write(executor);
        options.printWarnings();
      } catch (ExecutionException e) {
        R8.unwrapExecutionException(e);
        throw new AssertionError(e); // unwrapping method should have thrown
      } finally {
        options.signalFinishedToConsumers();
      }
    } finally {
      executor.shutdown();
    }
  }

  public static void runD8ForTesting(D8Command command, boolean dontCreateMarkerInD8)
      throws IOException {
    InternalOptions options = command.getInternalOptions();
    options.testing.dontCreateMarkerInD8 = dontCreateMarkerInD8;
    D8.runForTesting(command.getInputApp(), options);
  }
}
