// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.shaking;

import static com.debughelper.tools.r8.utils.DescriptorUtils.javaTypeToDescriptor;

import com.debughelper.tools.r8.dex.Constants;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.logging.Log;
import com.debughelper.tools.r8.origin.Origin;
import com.debughelper.tools.r8.shaking.FilteredClassPath;
import com.debughelper.tools.r8.shaking.ProguardAlwaysInlineRule;
import com.debughelper.tools.r8.shaking.ProguardAssumeValuesRule;
import com.debughelper.tools.r8.shaking.ProguardCheckDiscardRule;
import com.debughelper.tools.r8.shaking.ProguardClassNameList;
import com.debughelper.tools.r8.shaking.ProguardClassSpecification;
import com.debughelper.tools.r8.shaking.ProguardConfiguration.Builder;
import com.debughelper.tools.r8.shaking.ProguardConfigurationSourceFile;
import com.debughelper.tools.r8.shaking.ProguardIdentifierNameStringRule;
import com.debughelper.tools.r8.shaking.ProguardKeepPackageNamesRule;
import com.debughelper.tools.r8.shaking.ProguardMemberRule;
import com.debughelper.tools.r8.shaking.ProguardMemberRuleReturnValue;
import com.debughelper.tools.r8.shaking.ProguardPathList;
import com.debughelper.tools.r8.shaking.ProguardRuleParserException;
import com.debughelper.tools.r8.shaking.ProguardTypeMatcher.ClassOrType;
import com.debughelper.tools.r8.shaking.ProguardTypeMatcher.MatchSpecificType;
import com.debughelper.tools.r8.shaking.ProguardWhyAreYouKeepingRule;
import com.debughelper.tools.r8.shaking.ProguardWildcard.BackReference;
import com.debughelper.tools.r8.shaking.ProguardWildcard.Pattern;
import com.debughelper.tools.r8.utils.IdentifierUtils;
import com.debughelper.tools.r8.utils.InternalOptions.PackageObfuscationMode;
import com.debughelper.tools.r8.utils.LongInterval;
import com.debughelper.tools.r8.utils.Reporter;
import com.debughelper.tools.r8.utils.StringDiagnostic;
import com.debughelper.tools.r8.position.Position;
import com.debughelper.tools.r8.position.TextPosition;
import com.debughelper.tools.r8.position.TextRange;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class ProguardConfigurationParser {

  private final ProguardConfiguration.Builder configurationBuilder;

  private final DexItemFactory dexItemFactory;

  private final Reporter reporter;
  private final boolean failOnPartiallyImplementedOptions;

  private static final List<String> IGNORED_SINGLE_ARG_OPTIONS = ImmutableList.of(
      "protomapping",
      "target");

  private static final List<String> IGNORED_OPTIONAL_SINGLE_ARG_OPTIONS = ImmutableList.of(
      "runtype",
      "laststageoutput");

  private static final List<String> IGNORED_FLAG_OPTIONS = ImmutableList.of(
      "forceprocessing",
      "dontusemixedcaseclassnames",
      "dontpreverify",
      "experimentalshrinkunusedprotofields",
      "filterlibraryjarswithorginalprogramjars",
      "dontskipnonpubliclibraryclasses",
      "dontskipnonpubliclibraryclassmembers",
      "invokebasemethod",
      // TODO(b/62524562): we may support this later.
      "mergeinterfacesaggressively",
      "debughelper");

  private static final List<String> IGNORED_CLASS_DESCRIPTOR_OPTIONS = ImmutableList.of(
      "isclassnamestring",
      "whyarenotsimple");

  private static final List<String> WARNED_SINGLE_ARG_OPTIONS = ImmutableList.of(
      // TODO(b/37137994): -outjars should be reported as errors, not just as warnings!
      "outjars");

  private static final List<String> WARNED_FLAG_OPTIONS = ImmutableList.of(
      // TODO(b/73707846): add support -addconfigurationdebugging
      "addconfigurationdebugging");

  private static final List<String> WARNED_CLASS_DESCRIPTOR_OPTIONS = ImmutableList.of(
      // TODO(b/73708157): add support -assumenoexternalsideeffects <class_spec>
      "assumenoexternalsideeffects",
      // TODO(b/73707404): add support -assumenoescapingparameters <class_spec>
      "assumenoescapingparameters",
      // TODO(b/73708085): add support -assumenoexternalreturnvalues <class_spec>
      "assumenoexternalreturnvalues");

  // Those options are unsupported and are treated as compilation errors.
  // Just ignoring them would produce outputs incompatible with user expectations.
  private static final List<String> UNSUPPORTED_FLAG_OPTIONS =
      ImmutableList.of("skipnonpubliclibraryclasses");

  public ProguardConfigurationParser(
      DexItemFactory dexItemFactory, Reporter reporter) {
    this(dexItemFactory, reporter, true);
  }

  public ProguardConfigurationParser(
      DexItemFactory dexItemFactory, Reporter reporter, boolean failOnPartiallyImplementedOptions) {
    this.dexItemFactory = dexItemFactory;
    configurationBuilder = ProguardConfiguration.builder(dexItemFactory, reporter);

    this.reporter = reporter;
    this.failOnPartiallyImplementedOptions = failOnPartiallyImplementedOptions;
  }

  public ProguardConfiguration.Builder getConfigurationBuilder() {
    return configurationBuilder;
  }

  private void validate() {
    if (configurationBuilder.isKeepParameterNames() && configurationBuilder.isObfuscating()) {
      // The flag -keepparameternames has only effect when minifying, so ignore it if we
      // are not.
      throw reporter.fatalError(new StringDiagnostic(
          "-keepparameternames is not supported",
          configurationBuilder.getKeepParameterNamesOptionOrigin(),
          configurationBuilder.getKeepParameterNamesOptionPosition()));
    }
  }

  /**
   * Returns the Proguard configuration with default rules derived from empty rules added.
   */
  public ProguardConfiguration getConfig() {
    validate();
    return configurationBuilder.build();
  }

  /**
   * Returns the Proguard configuration from exactly the rules parsed, without any
   * defaults derived from empty rules.
   */
  public ProguardConfiguration getConfigRawForTesting() {
    validate();
    return configurationBuilder.buildRaw();
  }

  public void parse(Path path) {
    parse(ImmutableList.of(new com.debughelper.tools.r8.shaking.ProguardConfigurationSourceFile(path)));
  }

  public void parse(ProguardConfigurationSource source) {
    parse(ImmutableList.of(source));
  }

  public void parse(List<ProguardConfigurationSource> sources) {
    for (ProguardConfigurationSource source : sources) {
      try {
        new ProguardConfigurationSourceParser(source).parse();
      } catch (IOException e) {
        reporter.error(new StringDiagnostic("Failed to read file: " + e.getMessage(),
            source.getOrigin()));
      } catch (com.debughelper.tools.r8.shaking.ProguardRuleParserException e) {
        reporter.error(e, MoreObjects.firstNonNull(e.getCause(), e));
      }
    }
    reporter.failIfPendingErrors();
  }

  private static enum IdentifierType {
    CLASS_NAME,
    ANY
  }

  private class ProguardConfigurationSourceParser {
    private final String name;
    private final String contents;
    private int position = 0;
    private int positionAfterInclude = 0;
    private int line = 1;
    private int lineStartPosition = 0;
    private Path baseDirectory;
    private final Origin origin;

    ProguardConfigurationSourceParser(ProguardConfigurationSource source) throws IOException {
      contents = source.get();
      baseDirectory = source.getBaseDirectory();
      name = source.getName();
      this.origin = source.getOrigin();
    }

    public void parse() throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      do {
        skipWhitespace();
      } while (parseOption());
      // Collect the parsed configuration.
      configurationBuilder.addParsedConfiguration(
          contents.substring(positionAfterInclude, contents.length()));
    }

    private boolean parseOption()
        throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      if (eof()) {
        return false;
      }
      if (acceptArobaseInclude()) {
        return true;
      }
      com.debughelper.tools.r8.position.TextPosition optionStart = getPosition();
      expectChar('-');
      if (parseIgnoredOption() ||
          parseIgnoredOptionAndWarn(optionStart) ||
          parseUnsupportedOptionAndErr(optionStart)) {
        // Intentionally left empty.
      } else if (acceptString("renamesourcefileattribute")) {
        skipWhitespace();
        if (isOptionalArgumentGiven()) {
          configurationBuilder.setRenameSourceFileAttribute(acceptString());
        } else {
          configurationBuilder.setRenameSourceFileAttribute("");
        }
      } else if (acceptString("keepattributes")) {
        parseKeepAttributes();
      } else if (acceptString("keeppackagenames")) {
        com.debughelper.tools.r8.shaking.ProguardKeepPackageNamesRule rule = parseKeepPackageNamesRule();
        configurationBuilder.addRule(rule);
      } else if (acceptString("keepparameternames")) {
        configurationBuilder.setKeepParameterNames(true, origin, getPosition(optionStart));
      } else if (acceptString("checkdiscard")) {
        com.debughelper.tools.r8.shaking.ProguardCheckDiscardRule rule = parseCheckDiscardRule();
        configurationBuilder.addRule(rule);
      } else if (acceptString("keepdirectories")) {
        // TODO(74279367): Report an error until it's fully supported.
        if (failOnPartiallyImplementedOptions) {
          failPartiallyImplementedOption("-keepdirectories", optionStart);
        }
        parsePathFilter(configurationBuilder::addKeepDirectories);
      } else if (acceptString("keep")) {
        ProguardKeepRule rule = parseKeepRule();
        configurationBuilder.addRule(rule);
      } else if (acceptString("whyareyoukeeping")) {
        com.debughelper.tools.r8.shaking.ProguardWhyAreYouKeepingRule rule = parseWhyAreYouKeepingRule();
        configurationBuilder.addRule(rule);
      } else if (acceptString("dontoptimize")) {
        configurationBuilder.disableOptimization();
      } else if (acceptString("optimizationpasses")) {
        skipWhitespace();
        Integer expectedOptimizationPasses = acceptInteger();
        if (expectedOptimizationPasses == null) {
          throw reporter.fatalError(new StringDiagnostic(
              "Missing n of \"-optimizationpasses n\"", origin, getPosition(optionStart)));
        }
        warnIgnoringOptions("optimizationpasses", optionStart);
      } else if (acceptString("dontobfuscate")) {
        configurationBuilder.disableObfuscation();
      } else if (acceptString("dontshrink")) {
        configurationBuilder.disableShrinking();
      } else if (acceptString("printusage")) {
        configurationBuilder.setPrintUsage(true);
        skipWhitespace();
        if (isOptionalArgumentGiven()) {
          configurationBuilder.setPrintUsageFile(parseFileName());
        }
      } else if (acceptString("verbose")) {
        configurationBuilder.setVerbose(true);
      } else if (acceptString("ignorewarnings")) {
        configurationBuilder.setIgnoreWarnings(true);
      } else if (acceptString("dontwarn")) {
        parseClassFilter(configurationBuilder::addDontWarnPattern);
      } else if (acceptString("dontnote")) {
        parseClassFilter(configurationBuilder::addDontNotePattern);
      } else if (acceptString("repackageclasses")) {
        if (configurationBuilder.getPackageObfuscationMode() == PackageObfuscationMode.FLATTEN) {
          warnOverridingOptions("repackageclasses", "flattenpackagehierarchy", optionStart);
        }
        skipWhitespace();
        if (acceptChar('\'')) {
          configurationBuilder.setPackagePrefix(parsePackageNameOrEmptyString());
          expectChar('\'');
        } else {
          configurationBuilder.setPackagePrefix("");
        }
      } else if (acceptString("flattenpackagehierarchy")) {
        if (configurationBuilder.getPackageObfuscationMode() == PackageObfuscationMode.REPACKAGE) {
          warnOverridingOptions("repackageclasses", "flattenpackagehierarchy", optionStart);
          skipWhitespace();
          if (isOptionalArgumentGiven()) {
            skipSingleArgument();
          }
        } else {
          skipWhitespace();
          if (acceptChar('\'')) {
            configurationBuilder.setFlattenPackagePrefix(parsePackageNameOrEmptyString());
            expectChar('\'');
          } else {
            configurationBuilder.setFlattenPackagePrefix("");
          }
        }
      } else if (acceptString("overloadaggressively")) {
        configurationBuilder.setOverloadAggressively(true);
      } else if (acceptString("allowaccessmodification")) {
        configurationBuilder.setAllowAccessModification(true);
      } else if (acceptString("printconfiguration")) {
        configurationBuilder.setPrintConfiguration(true);
        skipWhitespace();
        if (isOptionalArgumentGiven()) {
          configurationBuilder.setPrintConfigurationFile(parseFileName());
        }
      } else if (acceptString("printmapping")) {
        configurationBuilder.setPrintMapping(true);
        skipWhitespace();
        if (isOptionalArgumentGiven()) {
          configurationBuilder.setPrintMappingFile(parseFileName());
        }
      } else if (acceptString("applymapping")) {
        configurationBuilder.setApplyMappingFile(parseFileName());
      } else if (acceptString("assumenosideeffects")) {
        ProguardAssumeNoSideEffectRule rule = parseAssumeNoSideEffectsRule();
        configurationBuilder.addRule(rule);
      } else if (acceptString("assumevalues")) {
        com.debughelper.tools.r8.shaking.ProguardAssumeValuesRule rule = parseAssumeValuesRule();
        configurationBuilder.addRule(rule);
      } else if (acceptString("include")) {
        // Collect the parsed configuration until the include.
        configurationBuilder.addParsedConfiguration(
            contents.substring(positionAfterInclude, position - ("include".length() + 1)));
        skipWhitespace();
        parseInclude();
        positionAfterInclude = position;
      } else if (acceptString("basedirectory")) {
        skipWhitespace();
        baseDirectory = parseFileName();
      } else if (acceptString("injars")) {
        configurationBuilder.addInjars(parseClassPath());
      } else if (acceptString("libraryjars")) {
        configurationBuilder.addLibraryJars(parseClassPath());
      } else if (acceptString("printseeds")) {
        configurationBuilder.setPrintSeeds(true);
        skipWhitespace();
        if (isOptionalArgumentGiven()) {
          configurationBuilder.setSeedFile(parseFileName());
        }
      } else if (acceptString("obfuscationdictionary")) {
        configurationBuilder.setObfuscationDictionary(parseFileName());
      } else if (acceptString("classobfuscationdictionary")) {
        configurationBuilder.setClassObfuscationDictionary(parseFileName());
      } else if (acceptString("packageobfuscationdictionary")) {
        configurationBuilder.setPackageObfuscationDictionary(parseFileName());
      } else if (acceptString("alwaysinline")) {
        com.debughelper.tools.r8.shaking.ProguardAlwaysInlineRule rule = parseAlwaysInlineRule();
        configurationBuilder.addRule(rule);
      } else if (acceptString("useuniqueclassmembernames")) {
        configurationBuilder.setUseUniqueClassMemberNames(true);
      } else if (acceptString("adaptclassstrings")) {
        parseClassFilter(configurationBuilder::addAdaptClassStringsPattern);
      } else if (acceptString("adaptresourcefilenames")) {
        // TODO(76377381): Report an error until it's fully supported.
        if (failOnPartiallyImplementedOptions) {
          failPartiallyImplementedOption("-adaptresourcefilenames", optionStart);
        }
        parsePathFilter(configurationBuilder::addAdaptResourceFilenames);
      } else if (acceptString("adaptresourcefilecontents")) {
        // TODO(36847655): Report an error until it's fully supported.
        if (failOnPartiallyImplementedOptions) {
          failPartiallyImplementedOption("-adaptresourcefilecontents", optionStart);
        }
        parsePathFilter(configurationBuilder::addAdaptResourceFilecontents);
      } else if (acceptString("identifiernamestring")) {
        configurationBuilder.addRule(parseIdentifierNameStringRule());
      } else if (acceptString("if")) {
        configurationBuilder.addRule(parseIfRule(optionStart));
      } else {
        String unknownOption = acceptString();
        reporter.error(new StringDiagnostic(
            "Unknown option \"-" + unknownOption + "\"", origin, getPosition(optionStart)));
      }
      return true;
    }

    private boolean parseUnsupportedOptionAndErr(com.debughelper.tools.r8.position.TextPosition optionStart) {
      String option = Iterables.find(UNSUPPORTED_FLAG_OPTIONS, this::skipFlag, null);
      if (option != null) {
        reporter.error(new StringDiagnostic(
            "Unsupported option: -" + option, origin, getPosition(optionStart)));
        return true;
      }
      return false;
    }

    private boolean parseIgnoredOptionAndWarn(com.debughelper.tools.r8.position.TextPosition optionStart) {
      String option =
          Iterables.find(WARNED_CLASS_DESCRIPTOR_OPTIONS, this::skipOptionWithClassSpec, null);
      if (option == null) {
        option = Iterables.find(WARNED_FLAG_OPTIONS, this::skipFlag, null);
        if (option == null) {
          option = Iterables.find(WARNED_SINGLE_ARG_OPTIONS, this::skipOptionWithSingleArg, null);
          if (option == null) {
            return false;
          }
        }
      }
      warnIgnoringOptions(option, optionStart);
      return true;
    }

    private boolean parseIgnoredOption() {
      return Iterables.any(IGNORED_SINGLE_ARG_OPTIONS, this::skipOptionWithSingleArg)
          || Iterables.any(IGNORED_OPTIONAL_SINGLE_ARG_OPTIONS,
                           this::skipOptionWithOptionalSingleArg)
          || Iterables.any(IGNORED_FLAG_OPTIONS, this::skipFlag)
          || Iterables.any(IGNORED_CLASS_DESCRIPTOR_OPTIONS, this::skipOptionWithClassSpec)
          || parseOptimizationOption();
    }

    private void parseInclude() throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      com.debughelper.tools.r8.position.TextPosition start = getPosition();
      Path included = parseFileName();
      try {
        new ProguardConfigurationSourceParser(new ProguardConfigurationSourceFile(included))
            .parse();
      } catch (FileNotFoundException | NoSuchFileException e) {
        throw parseError("Included file '" + included.toString() + "' not found",
            start, e);
      } catch (IOException e) {
        throw parseError("Failed to read included file '" + included.toString() + "'",
            start, e);
      }
    }

    private boolean acceptArobaseInclude() throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      if (remainingChars() < 2) {
        return false;
      }
      if (!acceptChar('@')) {
        return false;
      }
      parseInclude();
      return true;
    }

    private void parseKeepAttributes() throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      List<String> attributesPatterns = acceptPatternList();
      if (attributesPatterns.isEmpty()) {
        throw parseError("Expected attribute pattern list");
      }
      configurationBuilder.addKeepAttributePatterns(attributesPatterns);
    }

    private boolean skipFlag(String name) {
      if (acceptString(name)) {
        if (Log.ENABLED) {
          Log.debug(ProguardConfigurationParser.class, "Skipping '-%s` flag", name);
        }
        return true;
      }
      return false;
    }

    private boolean skipOptionWithSingleArg(String name) {
      if (acceptString(name)) {
        if (Log.ENABLED) {
          Log.debug(ProguardConfigurationParser.class, "Skipping '-%s` option", name);
        }
        skipSingleArgument();
        return true;
      }
      return false;
    }

    private boolean skipOptionWithOptionalSingleArg(String name) {
      if (acceptString(name)) {
        if (Log.ENABLED) {
          Log.debug(ProguardConfigurationParser.class, "Skipping '-%s` option", name);
        }
        skipWhitespace();
        if (isOptionalArgumentGiven()) {
          skipSingleArgument();
        }
        return true;
      }
      return false;
    }

    private boolean skipOptionWithClassSpec(String name) {
      if (acceptString(name)) {
        if (Log.ENABLED) {
          Log.debug(ProguardConfigurationParser.class, "Skipping '-%s` option", name);
        }
        try {
          ProguardKeepRule.Builder keepRuleBuilder = ProguardKeepRule.builder();
          parseClassSpec(keepRuleBuilder, true);
          return true;
        } catch (com.debughelper.tools.r8.shaking.ProguardRuleParserException e) {
          throw reporter.fatalError(e, MoreObjects.firstNonNull(e.getCause(), e));
        }
      }
      return false;

    }

    private boolean parseOptimizationOption() {
      if (!acceptString("optimizations")) {
        return false;
      }
      skipWhitespace();
      do {
        skipOptimizationName();
        skipWhitespace();
      } while (acceptChar(','));
      return true;
    }

    private void skipOptimizationName() {
      if (acceptChar('!')) {
        skipWhitespace();
      }
      for (char next = peekChar();
          Character.isAlphabetic(next) || next == '/' || next == '*';
          next = peekChar()) {
        readChar();
      }
    }

    private void skipSingleArgument() {
      skipWhitespace();
      while (!eof() && !Character.isWhitespace(peekChar())) {
        readChar();
      }
    }

    private ProguardKeepRule parseKeepRule()
        throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      ProguardKeepRule.Builder keepRuleBuilder = ProguardKeepRule.builder();
      parseRuleTypeAndModifiers(keepRuleBuilder);
      parseClassSpec(keepRuleBuilder, false);
      if (keepRuleBuilder.getMemberRules().isEmpty()) {
        // If there are no member rules, a default rule for the parameterless constructor
        // applies. So we add that here.
        com.debughelper.tools.r8.shaking.ProguardMemberRule.Builder defaultRuleBuilder = com.debughelper.tools.r8.shaking.ProguardMemberRule.builder();
        defaultRuleBuilder.setName(
            IdentifierPatternWithWildcards.withoutWildcards(Constants.INSTANCE_INITIALIZER_NAME));
        defaultRuleBuilder.setRuleType(ProguardMemberType.INIT);
        defaultRuleBuilder.setArguments(Collections.emptyList());
        keepRuleBuilder.getMemberRules().add(defaultRuleBuilder.build());
      }
      return keepRuleBuilder.build();
    }

    private com.debughelper.tools.r8.shaking.ProguardWhyAreYouKeepingRule parseWhyAreYouKeepingRule()
        throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      com.debughelper.tools.r8.shaking.ProguardWhyAreYouKeepingRule.Builder keepRuleBuilder = ProguardWhyAreYouKeepingRule.builder();
      parseClassSpec(keepRuleBuilder, false);
      return keepRuleBuilder.build();
    }

    private com.debughelper.tools.r8.shaking.ProguardKeepPackageNamesRule parseKeepPackageNamesRule()
        throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      com.debughelper.tools.r8.shaking.ProguardKeepPackageNamesRule.Builder keepRuleBuilder = ProguardKeepPackageNamesRule.builder();
      keepRuleBuilder.setClassNames(parseClassNames());
      return keepRuleBuilder.build();
    }

    private com.debughelper.tools.r8.shaking.ProguardCheckDiscardRule parseCheckDiscardRule()
        throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      com.debughelper.tools.r8.shaking.ProguardCheckDiscardRule.Builder keepRuleBuilder = ProguardCheckDiscardRule.builder();
      parseClassSpec(keepRuleBuilder, false);
      return keepRuleBuilder.build();
    }

    private com.debughelper.tools.r8.shaking.ProguardAlwaysInlineRule parseAlwaysInlineRule()
        throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      com.debughelper.tools.r8.shaking.ProguardAlwaysInlineRule.Builder keepRuleBuilder = ProguardAlwaysInlineRule.builder();
      parseClassSpec(keepRuleBuilder, false);
      return keepRuleBuilder.build();
    }

    private com.debughelper.tools.r8.shaking.ProguardIdentifierNameStringRule parseIdentifierNameStringRule()
        throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      com.debughelper.tools.r8.shaking.ProguardIdentifierNameStringRule.Builder keepRuleBuilder =
          ProguardIdentifierNameStringRule.builder();
      parseClassSpec(keepRuleBuilder, false);
      return keepRuleBuilder.build();
    }

    private ProguardIfRule parseIfRule(com.debughelper.tools.r8.position.TextPosition optionStart)
        throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      ProguardIfRule.Builder ifRuleBuilder = ProguardIfRule.builder();
      parseClassSpec(ifRuleBuilder, false);

      // Required a subsequent keep rule.
      skipWhitespace();
      if (acceptString("-keep")) {
        ProguardKeepRule subsequentRule = parseKeepRule();
        ifRuleBuilder.setSubsequentRule(subsequentRule);
        ProguardIfRule ifRule = ifRuleBuilder.build();
        verifyAndLinkBackReferences(ifRule.getWildcards());
        return ifRule;
      }
      throw reporter.fatalError(new StringDiagnostic(
          "Expecting '-keep' option after '-if' option.", origin, getPosition(optionStart)));
    }

    void verifyAndLinkBackReferences(Iterable<ProguardWildcard> wildcards) {
      List<ProguardWildcard.Pattern> patterns = new ArrayList<>();
      for (ProguardWildcard wildcard : wildcards) {
        if (wildcard.isBackReference()) {
          ProguardWildcard.BackReference backReference = wildcard.asBackReference();
          if (patterns.size() < backReference.referenceIndex) {
            throw reporter.fatalError(new StringDiagnostic(
                "Wildcard <" + backReference.referenceIndex + "> is invalid "
                    + "(only seen " + patterns.size() + " at this point).",
                origin, getPosition()));
          }
          backReference.setReference(patterns.get(backReference.referenceIndex - 1));
        } else {
          assert wildcard.isPattern();
          patterns.add(wildcard.asPattern());
        }
      }
    }

    private void parseClassSpec(
        ProguardConfigurationRule.Builder builder, boolean allowValueSpecification)
        throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      parseClassFlagsAndAnnotations(builder);
      parseClassType(builder);
      builder.setClassNames(parseClassNames());
      parseInheritance(builder);
      parseMemberRules(builder, allowValueSpecification);
    }

    private void parseRuleTypeAndModifiers(ProguardKeepRule.Builder builder)
        throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      if (acceptString("names")) {
        builder.setType(ProguardKeepRuleType.KEEP);
        builder.getModifiersBuilder().setAllowsShrinking(true);
      } else if (acceptString("class")) {
        if (acceptString("members")) {
          builder.setType(ProguardKeepRuleType.KEEP_CLASS_MEMBERS);
        } else if (acceptString("eswithmembers")) {
          builder.setType(ProguardKeepRuleType.KEEP_CLASSES_WITH_MEMBERS);
        } else if (acceptString("membernames")) {
          builder.setType(ProguardKeepRuleType.KEEP_CLASS_MEMBERS);
          builder.getModifiersBuilder().setAllowsShrinking(true);
        } else if (acceptString("eswithmembernames")) {
          builder.setType(ProguardKeepRuleType.KEEP_CLASSES_WITH_MEMBERS);
          builder.getModifiersBuilder().setAllowsShrinking(true);
        } else {
          // The only path to here is through "-keep" followed by "class".
          unacceptString("-keepclass");
          com.debughelper.tools.r8.position.TextPosition start = getPosition();
          acceptString("-");
          String unknownOption = acceptString();
          throw reporter.fatalError(new StringDiagnostic(
              "Unknown option \"-" + unknownOption + "\"",
              origin,
              start));
        }
      } else {
        builder.setType(ProguardKeepRuleType.KEEP);
      }
      parseRuleModifiers(builder);
    }

    private void parseRuleModifiers(ProguardKeepRule.Builder builder) {
      skipWhitespace();
      while (acceptChar(',')) {
        skipWhitespace();
        if (acceptString("allow")) {
          if (acceptString("shrinking")) {
            builder.getModifiersBuilder().setAllowsShrinking(true);
          } else if (acceptString("optimization")) {
            builder.getModifiersBuilder().setAllowsOptimization(true);
          } else if (acceptString("obfuscation")) {
            builder.getModifiersBuilder().setAllowsObfuscation(true);
          }
        } else if (acceptString("includedescriptorclasses")) {
          builder.getModifiersBuilder().setIncludeDescriptorClasses(true);
        }
        skipWhitespace();
      }
    }

    private ProguardTypeMatcher parseAnnotation()
      throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {

      skipWhitespace();
      int startPosition = position;
      if (acceptChar('@')) {
        IdentifierPatternWithWildcards identifierPatternWithWildcards = parseClassName();
        String className = identifierPatternWithWildcards.pattern;
        if (className.equals("interface")) {
          // Not an annotation after all but a class type. Move position back to start
          // so this can be dealt with as a class type instead.
          position = startPosition;
          return null;
        }
        return ProguardTypeMatcher.create(
            identifierPatternWithWildcards, ProguardTypeMatcher.ClassOrType.CLASS, dexItemFactory);
      }
      return null;
    }

    private boolean parseNegation() {
      skipWhitespace();
      return acceptChar('!');
    }

    private void parseClassFlagsAndAnnotations(com.debughelper.tools.r8.shaking.ProguardClassSpecification.Builder builder)
        throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      while (true) {
        skipWhitespace();
        ProguardTypeMatcher annotation = parseAnnotation();
        if (annotation != null) {
          // TODO(ager): Should we only allow one annotation? It looks that way from the
          // proguard keep rule description, but that seems like a strange restriction?
          assert builder.getClassAnnotation() == null;
          builder.setClassAnnotation(annotation);
        } else {
          int start = position;
          ProguardAccessFlags flags =
              parseNegation()
                  ? builder.getNegatedClassAccessFlags()
                  : builder.getClassAccessFlags();
          skipWhitespace();
          if (acceptString("public")) {
            flags.setPublic();
          } else if (acceptString("final")) {
            flags.setFinal();
          } else if (acceptString("abstract")) {
            flags.setAbstract();
          } else {
            // Undo reading the ! in case there is no modifier following.
            position = start;
            break;
          }
        }
      }
    }

    private StringDiagnostic parseClassTypeUnexpected(Origin origin, com.debughelper.tools.r8.position.TextPosition start) {
      return new StringDiagnostic(
          "Expected [!]interface|@interface|class|enum", origin, getPosition(start));
    }

    private void parseClassType(
        com.debughelper.tools.r8.shaking.ProguardClassSpecification.Builder builder) throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      skipWhitespace();
      com.debughelper.tools.r8.position.TextPosition start = getPosition();
      if (acceptChar('!')) {
        builder.setClassTypeNegated(true);
      }
      if (acceptChar('@')) {
        skipWhitespace();
        if (acceptString("interface")) {
          builder.setClassType(ProguardClassType.ANNOTATION_INTERFACE);
        } else {
          throw reporter.fatalError(parseClassTypeUnexpected(origin, start));
        }
      } else if (acceptString("interface")) {
        builder.setClassType(ProguardClassType.INTERFACE);
      } else if (acceptString("class")) {
        builder.setClassType(ProguardClassType.CLASS);
      } else if (acceptString("enum")) {
        builder.setClassType(ProguardClassType.ENUM);
      } else {
        throw reporter.fatalError(parseClassTypeUnexpected(origin, start));
      }
    }

    private void parseInheritance(com.debughelper.tools.r8.shaking.ProguardClassSpecification.Builder classSpecificationBuilder)
        throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      skipWhitespace();
      if (acceptString("implements")) {
        classSpecificationBuilder.setInheritanceIsExtends(false);
      } else if (acceptString("extends")) {
        classSpecificationBuilder.setInheritanceIsExtends(true);
      } else {
        return;
      }
      classSpecificationBuilder.setInheritanceAnnotation(parseAnnotation());
      classSpecificationBuilder.setInheritanceClassName(ProguardTypeMatcher.create(parseClassName(),
          ProguardTypeMatcher.ClassOrType.CLASS, dexItemFactory));
    }

    private void parseMemberRules(ProguardClassSpecification.Builder classSpecificationBuilder,
                                  boolean allowValueSpecification)
        throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      skipWhitespace();
      if (!eof() && acceptChar('{')) {
        com.debughelper.tools.r8.shaking.ProguardMemberRule rule = null;
        while ((rule = parseMemberRule(allowValueSpecification)) != null) {
          classSpecificationBuilder.getMemberRules().add(rule);
        }
        skipWhitespace();
        expectChar('}');
      }
    }

    private com.debughelper.tools.r8.shaking.ProguardMemberRule parseMemberRule(boolean allowValueSpecification)
        throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      com.debughelper.tools.r8.shaking.ProguardMemberRule.Builder ruleBuilder = com.debughelper.tools.r8.shaking.ProguardMemberRule.builder();
      skipWhitespace();
      ruleBuilder.setAnnotation(parseAnnotation());
      parseMemberAccessFlags(ruleBuilder);
      parseMemberPattern(ruleBuilder, allowValueSpecification);
      return ruleBuilder.isValid() ? ruleBuilder.build() : null;
    }

    private void parseMemberAccessFlags(com.debughelper.tools.r8.shaking.ProguardMemberRule.Builder ruleBuilder) {
      boolean found = true;
      while (found && !eof()) {
        found = false;
        ProguardAccessFlags flags =
            parseNegation() ? ruleBuilder.getNegatedAccessFlags() : ruleBuilder.getAccessFlags();
        skipWhitespace();
        switch (peekChar()) {
          case 'a':
            if ((found = acceptString("abstract"))) {
              flags.setAbstract();
            }
            break;
          case 'f':
            if ((found = acceptString("final"))) {
              flags.setFinal();
            }
            break;
          case 'n':
            if ((found = acceptString("native"))) {
              flags.setNative();
            }
            break;
          case 'p':
            if ((found = acceptString("public"))) {
              flags.setPublic();
            } else if ((found = acceptString("private"))) {
              flags.setPrivate();
            } else if ((found = acceptString("protected"))) {
              flags.setProtected();
            }
            break;
          case 's':
            if ((found = acceptString("synchronized"))) {
              flags.setSynchronized();
            } else if ((found = acceptString("static"))) {
              flags.setStatic();
            } else if ((found = acceptString("strictfp"))) {
              flags.setStrict();
            }
            break;
          case 't':
            if ((found = acceptString("transient"))) {
              flags.setTransient();
            }
            break;
          case 'v':
            if ((found = acceptString("volatile"))) {
              flags.setVolatile();
            }
            break;
          default:
            // Intentionally left empty.
        }
      }
    }

    private void parseMemberPattern(
            ProguardMemberRule.Builder ruleBuilder, boolean allowValueSpecification)
        throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      skipWhitespace();
      if (acceptString("<methods>")) {
        ruleBuilder.setRuleType(ProguardMemberType.ALL_METHODS);
      } else if (acceptString("<fields>")) {
        ruleBuilder.setRuleType(ProguardMemberType.ALL_FIELDS);
      } else if (acceptString("<init>")) {
        ruleBuilder.setRuleType(ProguardMemberType.INIT);
        ruleBuilder.setName(IdentifierPatternWithWildcards.withoutWildcards("<init>"));
        ruleBuilder.setArguments(parseArgumentList());
      } else {
        IdentifierPatternWithWildcards first =
            acceptIdentifierWithBackreference(IdentifierType.ANY);
        if (first != null) {
          skipWhitespace();
          if (first.pattern.equals("*") && hasNextChar(';')) {
            ruleBuilder.setRuleType(ProguardMemberType.ALL);
          } else {
            if (hasNextChar('(')) {
              ruleBuilder.setRuleType(ProguardMemberType.CONSTRUCTOR);
              ruleBuilder.setName(first);
              ruleBuilder.setArguments(parseArgumentList());
            } else {
              IdentifierPatternWithWildcards second =
                  acceptIdentifierWithBackreference(IdentifierType.ANY);
              if (second != null) {
                skipWhitespace();
                if (hasNextChar('(')) {
                  ruleBuilder.setRuleType(ProguardMemberType.METHOD);
                  ruleBuilder.setName(second);
                  ruleBuilder
                      .setTypeMatcher(
                          ProguardTypeMatcher.create(first, ProguardTypeMatcher.ClassOrType.TYPE, dexItemFactory));
                  ruleBuilder.setArguments(parseArgumentList());
                } else {
                  ruleBuilder.setRuleType(ProguardMemberType.FIELD);
                  ruleBuilder.setName(second);
                  ruleBuilder
                      .setTypeMatcher(
                          ProguardTypeMatcher.create(first, ProguardTypeMatcher.ClassOrType.TYPE, dexItemFactory));
                }
                skipWhitespace();
                // Parse "return ..." if present.
                if (acceptString("return")) {
                  skipWhitespace();
                  if (acceptString("true")) {
                    ruleBuilder.setReturnValue(new com.debughelper.tools.r8.shaking.ProguardMemberRuleReturnValue(true));
                  } else if (acceptString("false")) {
                    ruleBuilder.setReturnValue(new com.debughelper.tools.r8.shaking.ProguardMemberRuleReturnValue(false));
                  } else {
                    com.debughelper.tools.r8.position.TextPosition fieldOrValueStart = getPosition();
                    String qualifiedFieldNameOrInteger = acceptFieldNameOrIntegerForReturn();
                    if (qualifiedFieldNameOrInteger != null) {
                      if (isInteger(qualifiedFieldNameOrInteger)) {
                        Integer min = Integer.parseInt(qualifiedFieldNameOrInteger);
                        Integer max = min;
                        skipWhitespace();
                        if (acceptString("..")) {
                          max = acceptInteger();
                          if (max == null) {
                            throw parseError("Expected integer value");
                          }
                        }
                        if (!allowValueSpecification) {
                          throw parseError("Unexpected value specification", fieldOrValueStart);
                        }
                        ruleBuilder.setReturnValue(
                            new com.debughelper.tools.r8.shaking.ProguardMemberRuleReturnValue(new LongInterval(min, max)));
                      } else {
                        if (ruleBuilder.getTypeMatcher() instanceof ProguardTypeMatcher.MatchSpecificType) {
                          int lastDotIndex = qualifiedFieldNameOrInteger.lastIndexOf(".");
                          DexType fieldType = ((ProguardTypeMatcher.MatchSpecificType) ruleBuilder
                              .getTypeMatcher()).type;
                          DexType fieldClass =
                              dexItemFactory.createType(
                                  javaTypeToDescriptor(
                                      qualifiedFieldNameOrInteger.substring(0, lastDotIndex)));
                          DexString fieldName =
                              dexItemFactory.createString(
                                  qualifiedFieldNameOrInteger.substring(lastDotIndex + 1));
                          DexField field = dexItemFactory
                              .createField(fieldClass, fieldType, fieldName);
                          ruleBuilder.setReturnValue(new ProguardMemberRuleReturnValue(field));
                        } else {
                          throw parseError("Expected specific type", fieldOrValueStart);
                        }
                      }
                    }
                  }
                }
              } else {
                throw parseError("Expected field or method name");
              }
            }
          }
        }
      }
      // If we found a member pattern eat the terminating ';'.
      if (ruleBuilder.isValid()) {
        skipWhitespace();
        expectChar(';');
      }
    }

    private List<ProguardTypeMatcher> parseArgumentList() throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      List<ProguardTypeMatcher> arguments = new ArrayList<>();
      skipWhitespace();
      expectChar('(');
      skipWhitespace();
      if (acceptChar(')')) {
        return arguments;
      }
      if (acceptString("...")) {
        arguments.add(ProguardTypeMatcher.create(
            IdentifierPatternWithWildcards.withoutWildcards("..."),
            ProguardTypeMatcher.ClassOrType.TYPE,
            dexItemFactory));
      } else {
        for (IdentifierPatternWithWildcards identifierPatternWithWildcards = parseClassName();
            identifierPatternWithWildcards != null;
            identifierPatternWithWildcards = acceptChar(',') ? parseClassName() : null) {
          arguments.add(ProguardTypeMatcher.create(
              identifierPatternWithWildcards, ProguardTypeMatcher.ClassOrType.TYPE, dexItemFactory));
          skipWhitespace();
        }
      }
      skipWhitespace();
      expectChar(')');
      return arguments;
    }

    private Path parseFileName() throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      com.debughelper.tools.r8.position.TextPosition start = getPosition();
      skipWhitespace();

      if (baseDirectory == null) {
        throw parseError("Options with file names are not supported", start);
      }

      String fileName = acceptString(character ->
          character != File.pathSeparatorChar
              && !Character.isWhitespace(character)
              && character != '(');
      if (fileName == null) {
        throw parseError("File name expected", start);
      }
      return baseDirectory.resolve(fileName);
    }

    private List<com.debughelper.tools.r8.shaking.FilteredClassPath> parseClassPath() throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      List<com.debughelper.tools.r8.shaking.FilteredClassPath> classPath = new ArrayList<>();
      skipWhitespace();
      Path file = parseFileName();
      ImmutableList<String> filters = parseClassPathFilters();
      classPath.add(new com.debughelper.tools.r8.shaking.FilteredClassPath(file, filters));
      while (acceptChar(File.pathSeparatorChar)) {
        file = parseFileName();
        filters = parseClassPathFilters();
        classPath.add(new FilteredClassPath(file, filters));
      }
      return classPath;
    }

    private ImmutableList<String> parseClassPathFilters() throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      skipWhitespace();
      if (acceptChar('(')) {
        ImmutableList.Builder<String> filters = new ImmutableList.Builder<>();
        filters.add(parseFileFilter());
        skipWhitespace();
        while (acceptChar(',')) {
          filters.add(parseFileFilter());
          skipWhitespace();
        }
        if (peekChar() == ';') {
          throw parseError("Only class file filters are supported in classpath");
        }
        expectChar(')');
        return filters.build();
      } else {
        return ImmutableList.of();
      }
    }

    private String parseFileFilter() throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      com.debughelper.tools.r8.position.TextPosition start = getPosition();
      skipWhitespace();
      String fileFilter = acceptString(character ->
          character != ',' && character != ';' && character != ')'
              && !Character.isWhitespace(character));
      if (fileFilter == null) {
        throw parseError("file filter expected", start);
      }
      return fileFilter;
    }

    private ProguardAssumeNoSideEffectRule parseAssumeNoSideEffectsRule()
        throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      ProguardAssumeNoSideEffectRule.Builder builder = ProguardAssumeNoSideEffectRule.builder();
      parseClassSpec(builder, true);
      return builder.build();
    }

    private com.debughelper.tools.r8.shaking.ProguardAssumeValuesRule parseAssumeValuesRule() throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      com.debughelper.tools.r8.shaking.ProguardAssumeValuesRule.Builder builder = ProguardAssumeValuesRule.builder();
      parseClassSpec(builder, true);
      return builder.build();
    }

    private void skipWhitespace() {
      while (!eof() && Character.isWhitespace(contents.charAt(position))) {
        if (peekChar() == '\n') {
          line++;
          lineStartPosition = position + 1;
        }
        position++;
      }
      skipComment();
    }

    private void skipComment() {
      if (eof()) {
        return;
      }
      if (peekChar() == '#') {
        while (!eof() && peekChar() != '\n') {
          position++;;
        }
        skipWhitespace();
      }
    }

    private boolean isInteger(String s) {
      for (int i = 0; i < s.length(); i++) {
        if (!Character.isDigit(s.charAt(i))) {
          return false;
        }
      }
      return true;
    }

    private boolean eof() {
      return position == contents.length();
    }

    private boolean eof(int position) {
      return position == contents.length();
    }

    private boolean hasNextChar(char c) {
      if (eof()) {
        return false;
      }
      return peekChar() == c;
    }

    private boolean isOptionalArgumentGiven() {
      return !eof() && !hasNextChar('-');
    }

    private boolean acceptChar(char c) {
      if (hasNextChar(c)) {
        position++;
        return true;
      }
      return false;
    }

    private char peekChar() {
      return contents.charAt(position);
    }

    private char peekCharAt(int position) {
      assert !eof(position);
      return contents.charAt(position);
    }

    private char readChar() {
      return contents.charAt(position++);
    }

    private int remainingChars() {
      return contents.length() - position;
    }

    private void expectChar(char c) throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      if (!acceptChar(c)) {
        throw parseError("Expected char '" + c + "'");
      }
    }

    private boolean acceptString(String expected) {
      if (remainingChars() < expected.length()) {
        return false;
      }
      for (int i = 0; i < expected.length(); i++) {
        if (expected.charAt(i) != contents.charAt(position + i)) {
          return false;
        }
      }
      position += expected.length();
      return true;
    }

    private String acceptString() {
      return acceptString(character -> character != ' ' && character != '\n');
    }

    private Integer acceptInteger() {
      String s = acceptString(Character::isDigit);
      if (s == null) {
        return null;
      }
      return Integer.parseInt(s);
    }

    private final Predicate<Integer> CLASS_NAME_PREDICATE =
        codePoint ->
            IdentifierUtils.isDexIdentifierPart(codePoint)
                || codePoint == '.'
                || codePoint == '*'
                || codePoint == '?'
                || codePoint == '%'
                || codePoint == '['
                || codePoint == ']';

    private String acceptClassName() {
      return acceptString(CLASS_NAME_PREDICATE);
    }

    private IdentifierPatternWithWildcards acceptIdentifierWithBackreference(IdentifierType kind) {
      ImmutableList.Builder<ProguardWildcard> wildcardsCollector = ImmutableList.builder();
      StringBuilder currentAsterisks = null;
      int asteriskCount = 0;
      StringBuilder currentBackreference = null;
      skipWhitespace();
      int start = position;
      int end = position;
      while (!eof(end)) {
        int current = contents.codePointAt(end);
        // Should not be both in asterisk collecting state and back reference collecting state.
        assert currentAsterisks == null || currentBackreference == null;
        if (currentBackreference != null) {
          if (current == '>') {
            try {
              int backreference = Integer.parseUnsignedInt(currentBackreference.toString());
              if (backreference <= 0) {
                throw reporter.fatalError(new StringDiagnostic(
                    "Wildcard <" + backreference + "> is invalid.", origin, getPosition()));
              }
              wildcardsCollector.add(new ProguardWildcard.BackReference(backreference));
              currentBackreference = null;
              end += Character.charCount(current);
              continue;
            } catch (NumberFormatException e) {
              throw reporter.fatalError(new StringDiagnostic(
                  "Wildcard <" + currentBackreference.toString() + "> is invalid.",
                  origin, getPosition()));
            }
          } else if (('0' <= current && current <= '9')
              // Only collect integer literal for the back reference.
              || (current == '-' && currentBackreference.length() == 0)) {
            currentBackreference.append((char) current);
            end += Character.charCount(current);
            continue;
          } else if (kind == IdentifierType.CLASS_NAME) {
            throw reporter.fatalError(new StringDiagnostic(
                "Use of generics not allowed for java type.", origin, getPosition()));
          } else {
            // If not parsing a class name allow identifiers including <'s by canceling the
            // collection of the back reference.
            currentBackreference = null;
          }
        } else if (currentAsterisks != null) {
          if (current == '*') {
            // only '*', '**', and '***' are allowed.
            // E.g., '****' should be regarded as two separate wildcards (e.g., '***' and '*')
            if (asteriskCount >= 3) {
              wildcardsCollector.add(new ProguardWildcard.Pattern(currentAsterisks.toString()));
              currentAsterisks = new StringBuilder();
              asteriskCount = 0;
            }
            currentAsterisks.append((char) current);
            asteriskCount++;
            end += Character.charCount(current);
            continue;
          } else {
            wildcardsCollector.add(new ProguardWildcard.Pattern(currentAsterisks.toString()));
            currentAsterisks = null;
            asteriskCount = 0;
          }
        }
        // From now on, neither in asterisk collecting state nor back reference collecting state.
        assert currentAsterisks == null && currentBackreference == null;
        if (current == '*') {
          if (kind == IdentifierType.CLASS_NAME) {
            // '**' and '***' are only allowed in type name.
            currentAsterisks = new StringBuilder();
            currentAsterisks.append((char) current);
            asteriskCount = 1;
          } else {
            // For member names, regard '**' or '***' as separate single-asterisk wildcards.
            wildcardsCollector.add(new ProguardWildcard.Pattern(String.valueOf((char) current)));
          }
          end += Character.charCount(current);
        } else if (current == '?' || current == '%') {
          wildcardsCollector.add(new ProguardWildcard.Pattern(String.valueOf((char) current)));
          end += Character.charCount(current);
        } else if (CLASS_NAME_PREDICATE.test(current) || current == '>') {
          end += Character.charCount(current);
        } else if (current == '<') {
          currentBackreference = new StringBuilder();
          end += Character.charCount(current);
        } else {
          break;
        }
      }
      if (currentAsterisks != null) {
        wildcardsCollector.add(new ProguardWildcard.Pattern(currentAsterisks.toString()));
      }
      if (kind == IdentifierType.CLASS_NAME && currentBackreference != null) {
        // Proguard 6 reports this error message, so try to be compatible.
        throw reporter.fatalError(
            new StringDiagnostic("Missing closing angular bracket", origin, getPosition()));
      }
      if (start == end) {
        return null;
      }
      position = end;
      return new IdentifierPatternWithWildcards(
          contents.substring(start, end),
          wildcardsCollector.build());
    }

    private String acceptFieldNameOrIntegerForReturn() {
      skipWhitespace();
      int start = position;
      int end = position;
      while (!eof(end)) {
        int current = contents.codePointAt(end);
        if (current == '.' && !eof(end + 1) && peekCharAt(end + 1) == '.') {
          // The grammar is ambiguous. End accepting before .. token used in return ranges.
          break;
        }
        if ((start == end && IdentifierUtils.isDexIdentifierStart(current))
            || ((start < end)
                && (IdentifierUtils.isDexIdentifierPart(current) || current == '.'))) {
          end += Character.charCount(current);
        } else {
          break;
        }
      }
      if (start == end) {
        return null;
      }
      position = end;
      return contents.substring(start, end);
    }

    private List<String> acceptPatternList() throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      List<String> patterns = new ArrayList<>();
      String pattern = acceptPattern();
      while (pattern != null) {
        patterns.add(pattern);
        skipWhitespace();
        com.debughelper.tools.r8.position.TextPosition start = getPosition();
        if (acceptChar(',')) {
          pattern = acceptPattern();
          if (pattern == null) {
            throw parseError("Expected list element", start);
          }
        } else {
          pattern = null;
        }
      }
      return patterns;
    }

    private String acceptPattern() {
      return acceptString(
          codePoint ->
              IdentifierUtils.isDexIdentifierPart(codePoint)
                  || codePoint == '!'
                  || codePoint == '*');
    }

    private String acceptString(Predicate<Integer> codepointAcceptor) {
      skipWhitespace();
      int start = position;
      int end = position;
      while (!eof(end)) {
        int current = contents.codePointAt(end);
        if (codepointAcceptor.test(current)) {
          end += Character.charCount(current);
        } else {
          break;
        }
      }
      if (start == end) {
        return null;
      }
      position = end;
      return contents.substring(start, end);
    }

    private void unacceptString(String expected) {
      assert position >= expected.length();
      position -= expected.length();
      for (int i = 0; i < expected.length(); i++) {
        assert expected.charAt(i) == contents.charAt(position + i);
      }
    }

    private void parseClassFilter(Consumer<com.debughelper.tools.r8.shaking.ProguardClassNameList> consumer)
        throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      skipWhitespace();
      if (isOptionalArgumentGiven()) {
        consumer.accept(parseClassNames());
      } else {
        consumer.accept(
            com.debughelper.tools.r8.shaking.ProguardClassNameList.singletonList(ProguardTypeMatcher.defaultAllMatcher()));
      }
    }

    private com.debughelper.tools.r8.shaking.ProguardClassNameList parseClassNames() throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      com.debughelper.tools.r8.shaking.ProguardClassNameList.Builder builder = ProguardClassNameList.builder();
      skipWhitespace();
      boolean negated = acceptChar('!');
      builder.addClassName(negated,
          ProguardTypeMatcher.create(parseClassName(), ProguardTypeMatcher.ClassOrType.CLASS, dexItemFactory));
      skipWhitespace();
      while (acceptChar(',')) {
        negated = acceptChar('!');
        builder.addClassName(negated,
            ProguardTypeMatcher.create(parseClassName(), ProguardTypeMatcher.ClassOrType.CLASS, dexItemFactory));
        skipWhitespace();
      }
      return builder.build();
    }

    private String parsePackageNameOrEmptyString() {
      String name = acceptClassName();
      return name == null ? "" : name;
    }

    private IdentifierPatternWithWildcards parseClassName() throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      IdentifierPatternWithWildcards name =
          acceptIdentifierWithBackreference(IdentifierType.CLASS_NAME);
      if (name == null) {
        throw parseError("Class name expected");
      }
      return name;
    }

    private boolean pathFilterMatcher(Integer character) {
      return character != ',' && !Character.isWhitespace(character);
    }

    private void parsePathFilter(Consumer<com.debughelper.tools.r8.shaking.ProguardPathList> consumer)
        throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      skipWhitespace();
      if (isOptionalArgumentGiven()) {
        consumer.accept(parsePathFilter());
      } else {
        consumer.accept(com.debughelper.tools.r8.shaking.ProguardPathList.emptyList());
      }
    }

    private com.debughelper.tools.r8.shaking.ProguardPathList parsePathFilter() throws com.debughelper.tools.r8.shaking.ProguardRuleParserException {
      com.debughelper.tools.r8.shaking.ProguardPathList.Builder builder = ProguardPathList.builder();
      skipWhitespace();
      boolean negated = acceptChar('!');
      String fileFilter = acceptString(this::pathFilterMatcher);
      if (fileFilter == null) {
        throw parseError("Path filter expected");
      }
      builder.addFileName(negated, fileFilter);
      skipWhitespace();
      while (acceptChar(',')) {
        skipWhitespace();
        negated = acceptChar('!');
        skipWhitespace();
        fileFilter = acceptString(this::pathFilterMatcher);
        if (fileFilter == null) {
          throw parseError("Path filter expected");
        }
        builder.addFileName(negated, fileFilter);
        skipWhitespace();
      }
      return builder.build();
    }

    private String snippetForPosition() {
      // TODO(ager): really should deal with \r as well to get column right.
      String[] lines = contents.split("\n", -1);  // -1 to get trailing empty lines represented.
      int remaining = position;
      for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
        String line = lines[lineNumber];
        if (remaining <= line.length() || lineNumber == lines.length - 1) {
          String arrow = CharBuffer.allocate(remaining).toString().replace('\0', ' ') + '^';
          return name + ":" + (lineNumber + 1) + ":" + (remaining + 1) + "\n" + line
              + '\n' + arrow;
        }
        remaining -= (line.length() + 1); // Include newline.
      }
      return name;
    }
    private String snippetForPosition(com.debughelper.tools.r8.position.TextPosition start) {
      // TODO(ager): really should deal with \r as well to get column right.
      String[] lines = contents.split("\n", -1);  // -1 to get trailing empty lines represented.
      String line = lines[start.getLine() - 1];
      String arrow = CharBuffer.allocate(start.getColumn() - 1).toString().replace('\0', ' ') + '^';
      return name + ":" + (start.getLine() + 1) + ":" + start.getColumn() + "\n" + line
          + '\n' + arrow;
    }

    private com.debughelper.tools.r8.shaking.ProguardRuleParserException parseError(String message) {
      return new com.debughelper.tools.r8.shaking.ProguardRuleParserException(message, snippetForPosition(), origin, getPosition());
    }

    private com.debughelper.tools.r8.shaking.ProguardRuleParserException parseError(String message, Throwable cause) {
      return new com.debughelper.tools.r8.shaking.ProguardRuleParserException(message, snippetForPosition(), origin, getPosition(),
          cause);
    }

    private com.debughelper.tools.r8.shaking.ProguardRuleParserException parseError(String message, com.debughelper.tools.r8.position.TextPosition start,
                                                                                Throwable cause) {
      return new com.debughelper.tools.r8.shaking.ProguardRuleParserException(message, snippetForPosition(start),
          origin, getPosition(start), cause);
    }

    private com.debughelper.tools.r8.shaking.ProguardRuleParserException parseError(String message, com.debughelper.tools.r8.position.TextPosition start) {
      return new ProguardRuleParserException(message, snippetForPosition(start),
          origin, getPosition(start));
    }

    private void warnIgnoringOptions(String optionName, com.debughelper.tools.r8.position.TextPosition start) {
      reporter.warning(new StringDiagnostic(
          "Ignoring option: -" + optionName, origin, getPosition(start)));
    }

    private void warnOverridingOptions(String optionName, String victim, com.debughelper.tools.r8.position.TextPosition start) {
      reporter.warning(new StringDiagnostic(
          "Option -" + optionName + " overrides -" + victim, origin, getPosition(start)));
    }

    private void failPartiallyImplementedOption(String optionName, com.debughelper.tools.r8.position.TextPosition start) {
      throw reporter.fatalError(new StringDiagnostic(
          "Option " + optionName + " currently not supported", origin, getPosition(start)));
    }

    private Position getPosition(com.debughelper.tools.r8.position.TextPosition start) {
      if (start.getOffset() == position) {
        return start;
      } else {
        return new TextRange(start, getPosition());
      }
    }

    private com.debughelper.tools.r8.position.TextPosition getPosition() {
      return new TextPosition(position, line, getColumn());
    }

    private int getColumn() {
      return position - lineStartPosition + 1 /* column starts at 1 */;
    }
  }

  static class IdentifierPatternWithWildcards {
    final String pattern;
    final List<ProguardWildcard> wildcards;

    IdentifierPatternWithWildcards(String pattern, List<ProguardWildcard> wildcards) {
      this.pattern = pattern;
      this.wildcards = wildcards;
    }

    static IdentifierPatternWithWildcards withoutWildcards(String pattern) {
      return new IdentifierPatternWithWildcards(pattern, ImmutableList.of());
    }

    boolean isMatchAllNames() {
      return pattern.equals("*");
    }
  }
}
