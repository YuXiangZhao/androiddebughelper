// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.bisect;

import com.debughelper.tools.r8.bisect.BisectOptions.Result;
import com.debughelper.tools.r8.dex.ApplicationReader;
import com.debughelper.tools.r8.dex.ApplicationWriter;
import com.debughelper.tools.r8.graph.DexApplication;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.utils.AndroidApp;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.debughelper.tools.r8.utils.Timing;
import com.debughelper.tools.r8.OutputMode;
import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.utils.AndroidAppConsumers;
import com.google.common.io.CharStreams;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Bisect {

  private final BisectOptions options;
  private final Timing timing = new Timing("bisect");

  public interface Command {

    BisectOptions.Result apply(DexApplication application) throws Exception;
  }

  private static class StreamReader implements Runnable {

    private final InputStream stream;
    private String result;

    public StreamReader(InputStream stream) {
      this.stream = stream;
    }

    public String getResult() {
      return result;
    }

    @Override
    public void run() {
      try {
        result = CharStreams.toString(new InputStreamReader(stream, StandardCharsets.UTF_8));
        stream.close();
      } catch (IOException e) {
        result = "Failed reading result for stream " + stream;
      }
    }
  }

  public Bisect(BisectOptions options) {
    this.options = options;
  }

  public static DexProgramClass run(BisectState state, Command command, Path output,
      ExecutorService executor)
      throws Exception {
    while (true) {
      DexApplication app = state.bisect();
      state.write();
      if (app == null) {
        return state.getFinalClass();
      }
      if (command == null) {
        writeApp(app, output, executor);
        System.out.println("Bisecting completed with build in " + output + "/");
        System.out.println("Continue bisection by passing either --"
            + BisectOptions.RESULT_GOOD_FLAG + " or --"
            + BisectOptions.RESULT_BAD_FLAG);
        return null;
      }
      state.setPreviousResult(command.apply(app));
    }
  }

  public DexProgramClass run() throws Exception {
    // Setup output directory (or write to a temp dir).
    Path output;
    if (options.output != null) {
      output = options.output.toPath();
    } else {
      File temp = File.createTempFile("bisect", "", new File("/tmp"));
      temp.delete();
      temp.mkdir();
      output = temp.toPath();
    }

    ExecutorService executor = Executors.newWorkStealingPool();
    try {
      DexApplication goodApp = readApp(options.goodBuild, executor);
      DexApplication badApp = readApp(options.badBuild, executor);

      File stateFile = options.stateFile != null
          ? options.stateFile
          : output.resolve("bisect.state").toFile();

      // Setup initial (or saved) bisection state.
      BisectState state = new BisectState(goodApp, badApp, stateFile);
      if (options.stateFile != null) {
        state.read();
      }

      // If a "previous" result is supplied on the command line, record it.
      if (options.result != BisectOptions.Result.UNKNOWN) {
        state.setPreviousResult(options.result);
      }

      // Setup post-build command.
      Command command = null;
      if (options.command != null) {
        command = (application) -> {
          writeApp(application, output, executor);
          return runCommand(output);
        };
      }

      // Run bisection.
      return run(state, command, output, executor);
    } finally {
      executor.shutdown();
    }
  }

  private BisectOptions.Result runCommand(Path output) throws IOException {
    List<String> args = new ArrayList<>();
    args.add("/bin/bash");
    args.add(options.command.toString());
    args.add(output.toString());
    ProcessBuilder builder = new ProcessBuilder(args);
    Process process = builder.start();
    StreamReader stdoutReader = new StreamReader(process.getInputStream());
    StreamReader stderrReader = new StreamReader(process.getErrorStream());
    Thread stdoutThread = new Thread(stdoutReader);
    Thread stderrThread = new Thread(stderrReader);
    stdoutThread.start();
    stderrThread.start();
    try {
      process.waitFor();
      stdoutThread.join();
      stderrThread.join();
    } catch (InterruptedException e) {
      throw new RuntimeException("Execution interrupted", e);
    }
    int result = process.exitValue();
    if (result == 0) {
      return BisectOptions.Result.GOOD;
    } else if (result == 1) {
      return BisectOptions.Result.BAD;
    }
    System.out.println("Failed to run command " + args);
    System.out.println("Exit code: " + result + " (expected 0 for good, 1 for bad)");
    System.out.println("Std out:\n" + stdoutReader.getResult());
    System.out.println("Std err:\n" + stderrReader.getResult());
    throw new com.debughelper.tools.r8.errors.CompilationError("Failed to run command " + args);
  }

  private DexApplication readApp(File apk, ExecutorService executor)
      throws IOException, ExecutionException {
    AndroidApp app = AndroidApp.builder().addProgramFiles(apk.toPath()).build();
    return new ApplicationReader(app, new InternalOptions(), timing).read(executor);
  }

  private static void writeApp(DexApplication app, Path output, ExecutorService executor)
      throws IOException, ExecutionException {
    InternalOptions options = new InternalOptions();
    com.debughelper.tools.r8.utils.AndroidAppConsumers compatSink = new AndroidAppConsumers(options);
    ApplicationWriter writer = new ApplicationWriter(app, options, null, null, null, null, null);
    writer.write(executor);
    compatSink.build().writeToDirectory(output, OutputMode.DexIndexed);
  }

  public static void main(String[] args) throws Exception {
    BisectOptions options = null;
    try {
      options = BisectOptions.parse(args);
    } catch (CompilationError e) {
      System.err.println(e.getMessage());
      BisectOptions.printHelp(System.err);
      return;
    }
    if (options == null) {
      return;
    }
    DexProgramClass clazz = new Bisect(options).run();
    if (clazz != null) {
      System.out.println("Bisection found final bad class " + clazz);
    }
  }
}
