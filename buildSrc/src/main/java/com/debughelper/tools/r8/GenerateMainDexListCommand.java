// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8;

import com.debughelper.tools.r8.BaseCommand;
import com.debughelper.tools.r8.StringConsumer;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.origin.CommandLineOrigin;
import com.debughelper.tools.r8.origin.Origin;
import com.debughelper.tools.r8.shaking.ProguardConfigurationParser;
import com.debughelper.tools.r8.shaking.ProguardConfigurationRule;
import com.debughelper.tools.r8.shaking.ProguardConfigurationSource;
import com.debughelper.tools.r8.shaking.ProguardConfigurationSourceFile;
import com.debughelper.tools.r8.shaking.ProguardConfigurationSourceStrings;
import com.debughelper.tools.r8.utils.AndroidApp;
import com.debughelper.tools.r8.utils.DefaultDiagnosticsHandler;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.debughelper.tools.r8.utils.Reporter;
import com.debughelper.tools.r8.utils.StringDiagnostic;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class GenerateMainDexListCommand extends com.debughelper.tools.r8.BaseCommand {

  private final ImmutableList<ProguardConfigurationRule> mainDexKeepRules;
  private final com.debughelper.tools.r8.StringConsumer mainDexListConsumer;
  private final DexItemFactory factory;
  private final Reporter reporter;

  public static class Builder extends BaseCommand.Builder<GenerateMainDexListCommand, Builder> {

    private final DexItemFactory factory = new DexItemFactory();
    private final List<ProguardConfigurationSource> mainDexRules = new ArrayList<>();
    private com.debughelper.tools.r8.StringConsumer mainDexListConsumer = null;

    private Builder() {
    }

    private Builder(DiagnosticsHandler diagnosticsHandler) {
      super(diagnosticsHandler);
    }


    @Override
    GenerateMainDexListCommand.Builder self() {
      return this;
    }

    /**
     * Add proguard configuration file resources for automatic main dex list calculation.
     */
    public GenerateMainDexListCommand.Builder addMainDexRulesFiles(Path... paths) {
      guard(() -> {
        for (Path path : paths) {
          mainDexRules.add(new ProguardConfigurationSourceFile(path));
        }
      });
      return self();
    }

    /**
     * Add proguard configuration file resources for automatic main dex list calculation.
     */
    public GenerateMainDexListCommand.Builder addMainDexRulesFiles(List<Path> paths) {
      guard(() -> {
        for (Path path : paths) {
          mainDexRules.add(new ProguardConfigurationSourceFile(path));
        }
      });
      return self();
    }

    /**
     * Add proguard configuration for automatic main dex list calculation.
     */
    public GenerateMainDexListCommand.Builder addMainDexRules(List<String> lines, Origin origin) {
      guard(() -> mainDexRules.add(
          new ProguardConfigurationSourceStrings(lines, Paths.get("."), origin)));
      return self();
    }

    /**
     * Set the output file for the main-dex list.
     *
     * If the file exists it will be overwritten.
     */
    public GenerateMainDexListCommand.Builder setMainDexListOutputPath(Path mainDexListOutputPath) {
      mainDexListConsumer = new com.debughelper.tools.r8.StringConsumer.FileConsumer(mainDexListOutputPath);
      return self();
    }

    public GenerateMainDexListCommand.Builder setMainDexListConsumer(
        com.debughelper.tools.r8.StringConsumer mainDexListConsumer) {
      this.mainDexListConsumer = mainDexListConsumer;
      return self();
    }

    @Override
    protected GenerateMainDexListCommand makeCommand() {
      // If printing versions ignore everything else.
      if (isPrintHelp() || isPrintVersion()) {
        return new GenerateMainDexListCommand(isPrintHelp(), isPrintVersion());
      }

      ImmutableList<ProguardConfigurationRule> mainDexKeepRules;
      if (this.mainDexRules.isEmpty()) {
        mainDexKeepRules = ImmutableList.of();
      } else {
        ProguardConfigurationParser parser =
            new ProguardConfigurationParser(factory, getReporter());
        parser.parse(mainDexRules);
        mainDexKeepRules = parser.getConfig().getRules();
      }

      return new GenerateMainDexListCommand(
          factory, getAppBuilder().build(), mainDexKeepRules, mainDexListConsumer, getReporter());
    }
  }

  static final String USAGE_MESSAGE = String.join("\n", ImmutableList.of(
      "Usage: maindex [options] <input-files>",
      " where <input-files> are JAR files",
      " and options are:",
      "  --lib <file>             # Add <file> as a library resource.",
      "  --main-dex-rules <file>  # Proguard keep rules for classes to place in the",
      "                           # primary dex file.",
      "  --main-dex-list <file>   # List of classes to place in the primary dex file.",
      "  --main-dex-list-output <file>  # Output the full main-dex list in <file>.",
      "  --version                # Print the version.",
      "  --help                   # Print this message."));


  public static GenerateMainDexListCommand.Builder builder() {
    return new GenerateMainDexListCommand.Builder();
  }

  public static GenerateMainDexListCommand.Builder builder(DiagnosticsHandler diagnosticsHandler) {
    return new GenerateMainDexListCommand.Builder(diagnosticsHandler);
  }

  public static GenerateMainDexListCommand.Builder parse(String[] args) {
    GenerateMainDexListCommand.Builder builder = builder();
    parse(args, builder);
    return builder;
  }

  public com.debughelper.tools.r8.StringConsumer getMainDexListConsumer() {
    return mainDexListConsumer;
  }

  private static void parse(String[] args, GenerateMainDexListCommand.Builder builder) {
    for (int i = 0; i < args.length; i++) {
      String arg = args[i].trim();
      if (arg.length() == 0) {
        continue;
      } else if (arg.equals("--help")) {
        builder.setPrintHelp(true);
      } else if (arg.equals("--version")) {
        builder.setPrintVersion(true);
      } else if (arg.equals("--lib")) {
        builder.addLibraryFiles(Paths.get(args[++i]));
      } else if (arg.equals("--main-dex-rules")) {
        builder.addMainDexRulesFiles(Paths.get(args[++i]));
      } else if (arg.equals("--main-dex-list")) {
        builder.addMainDexListFiles(Paths.get(args[++i]));
      } else if (arg.equals("--main-dex-list-output")) {
        builder.setMainDexListOutputPath(Paths.get(args[++i]));
      } else {
        if (arg.startsWith("--")) {
          builder.getReporter().error(new StringDiagnostic("Unknown option: " + arg,
              CommandLineOrigin.INSTANCE));
        }
        builder.addProgramFiles(Paths.get(arg));
      }
    }
  }

  private GenerateMainDexListCommand(
      DexItemFactory factory,
      AndroidApp inputApp,
      ImmutableList<ProguardConfigurationRule> mainDexKeepRules,
      StringConsumer mainDexListConsumer,
      Reporter reporter) {
    super(inputApp);
    this.factory = factory;
    this.mainDexKeepRules = mainDexKeepRules;
    this.mainDexListConsumer = mainDexListConsumer;
    this.reporter = reporter;
  }

  private GenerateMainDexListCommand(boolean printHelp, boolean printVersion) {
    super(printHelp, printVersion);
    this.factory = new DexItemFactory();
    this.mainDexKeepRules = ImmutableList.of();
    this.mainDexListConsumer = null;
    this.reporter = new Reporter(new DefaultDiagnosticsHandler());
  }

  @Override
  InternalOptions getInternalOptions() {
    InternalOptions internal = new InternalOptions(factory, reporter);
    internal.mainDexKeepRules = mainDexKeepRules;
    internal.mainDexListConsumer = mainDexListConsumer;
    internal.minimalMainDex = internal.debug;
    internal.enableSwitchMapRemoval = false;
    internal.enableInlining = false;
    return internal;
  }
}

