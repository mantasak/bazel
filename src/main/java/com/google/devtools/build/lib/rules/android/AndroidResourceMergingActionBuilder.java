// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.android;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import javax.annotation.Nullable;

/**
 * Builder for creating $android_resource_merger action. The action merges resources and generates
 * the merged R classes for an android_library to hand off to java compilation of the library
 * sources. It also generates a merged resources zip file to pass on to the
 * $android_resource_validator action. For android_binary, see {@link
 * AndroidResourcesProcessorBuilder}.
 */
public class AndroidResourceMergingActionBuilder {

  // Inputs
  private ParsedAndroidResources primary;
  private ResourceDependencies dependencies;

  // Outputs
  private Artifact mergedResourcesOut;
  private Artifact classJarOut;
  private Artifact manifestOut;
  private @Nullable Artifact dataBindingInfoZip;

  // Flags
  private String customJavaPackage;
  private boolean throwOnResourceConflict;
  private boolean useCompiledMerge;
  private boolean annotateRFieldsFromTransitiveDeps;
  private boolean omitTransitiveDependencies;

  /**
   * The primary resource for merging. This resource will overwrite any resource or data value in
   * the transitive closure.
   */
  private AndroidResourceMergingActionBuilder withPrimary(ParsedAndroidResources primary) {
    this.primary = primary;
    return this;
  }

  public AndroidResourceMergingActionBuilder withDependencies(ResourceDependencies resourceDeps) {
    this.dependencies = resourceDeps;
    return this;
  }

  public AndroidResourceMergingActionBuilder setMergedResourcesOut(Artifact mergedResourcesOut) {
    this.mergedResourcesOut = mergedResourcesOut;
    return this;
  }

  public AndroidResourceMergingActionBuilder setClassJarOut(Artifact classJarOut) {
    this.classJarOut = classJarOut;
    return this;
  }

  public AndroidResourceMergingActionBuilder setManifestOut(Artifact manifestOut) {
    this.manifestOut = manifestOut;
    return this;
  }

  /**
   * The output zip for resource-processed data binding expressions (i.e. a zip of .xml files).
   *
   * <p>If null, data binding processing is skipped (and data binding expressions aren't allowed in
   * layout resources).
   */
  public AndroidResourceMergingActionBuilder setDataBindingInfoZip(Artifact zip) {
    this.dataBindingInfoZip = zip;
    return this;
  }

  public AndroidResourceMergingActionBuilder setJavaPackage(String customJavaPackage) {
    this.customJavaPackage = customJavaPackage;
    return this;
  }

  public AndroidResourceMergingActionBuilder setThrowOnResourceConflict(
      boolean throwOnResourceConflict) {
    this.throwOnResourceConflict = throwOnResourceConflict;
    return this;
  }

  public AndroidResourceMergingActionBuilder setUseCompiledMerge(boolean useCompiledMerge) {
    this.useCompiledMerge = useCompiledMerge;
    return this;
  }

  public AndroidResourceMergingActionBuilder setAnnotateRFieldsFromTransitiveDeps(
      boolean annotateRFieldsFromTransitiveDeps) {
    this.annotateRFieldsFromTransitiveDeps = annotateRFieldsFromTransitiveDeps;
    return this;
  }

  public AndroidResourceMergingActionBuilder setOmitTransitiveDependencies(
      boolean omitTransitiveDependencies) {
    this.omitTransitiveDependencies = omitTransitiveDependencies;
    return this;
  }

  private BusyBoxActionBuilder createInputsForBuilder(BusyBoxActionBuilder builder) {
    return builder
        .addAndroidJar()
        .addInput("--primaryManifest", primary.getManifest())
        .maybeAddFlag("--packageForR", customJavaPackage)
        .maybeAddFlag("--throwOnResourceConflict", throwOnResourceConflict);
  }

  private void buildCompiledResourceMergingAction(BusyBoxActionBuilder builder) {
    Preconditions.checkNotNull(primary);

    createInputsForBuilder(builder)
        .addInput(
            "--primaryData",
            AndroidDataConverter.COMPILED_RESOURCE_CONVERTER.map(primary),
            Iterables.concat(
                primary.getArtifacts(), ImmutableList.of(primary.getCompiledSymbols())));

    if (dependencies != null) {
      builder.addTransitiveFlag(
          "--directData",
          dependencies.getDirectResourceContainers(),
          AndroidDataConverter.COMPILED_RESOURCE_CONVERTER);

      if (omitTransitiveDependencies) {
        for (ValidatedAndroidResources resources : dependencies.getDirectResourceContainers()) {
          builder.addInputs(resources.getResources());
          builder.maybeAddInput(resources.getCompiledSymbols());
        }
      } else {
        builder
            .addTransitiveFlag(
                "--data",
                dependencies.getTransitiveResourceContainers(),
                AndroidDataConverter.COMPILED_RESOURCE_CONVERTER)
            .addTransitiveInputValues(dependencies.getTransitiveResources())
            .addTransitiveInputValues(dependencies.getTransitiveCompiledSymbols());
      }
    }

    builder.maybeAddFlag(
        "--annotate_r_fields_from_transitive_deps", annotateRFieldsFromTransitiveDeps);
    builder.buildAndRegister("Merging compiled Android resources", "AndroidCompiledResourceMerger");
  }

  private void buildParsedResourceMergingAction(BusyBoxActionBuilder builder) {
    Preconditions.checkNotNull(primary);

    createInputsForBuilder(builder)
        .addInput(
            "--primaryData",
            AndroidDataConverter.PARSED_RESOURCE_CONVERTER.map(primary),
            Iterables.concat(primary.getArtifacts(), ImmutableList.of(primary.getSymbols())));

    if (dependencies != null) {
      builder.addTransitiveFlag(
          "--directData",
          dependencies.getDirectResourceContainers(),
          AndroidDataConverter.PARSED_RESOURCE_CONVERTER);

      if (omitTransitiveDependencies) {
        for (ValidatedAndroidResources resources : dependencies.getDirectResourceContainers()) {
          builder.addInputs(resources.getResources());
          builder.maybeAddInput(resources.getSymbols());
        }
      } else {
        builder
            .addTransitiveFlag(
                "--data",
                dependencies.getTransitiveResourceContainers(),
                AndroidDataConverter.PARSED_RESOURCE_CONVERTER)
            .addTransitiveInputValues(dependencies.getTransitiveResources())
            .addTransitiveInputValues(dependencies.getTransitiveSymbolsBin());
      }
    }

    builder.buildAndRegister("Merging Android resources", "AndroidResourceMerger");
  }

  private void build(AndroidDataContext dataContext) {
    BusyBoxActionBuilder parsedMergeBuilder = BusyBoxActionBuilder.create(dataContext, "MERGE");
    BusyBoxActionBuilder compiledMergeBuilder =
        BusyBoxActionBuilder.create(dataContext, "MERGE_COMPILED");

    parsedMergeBuilder.addOutput("--resourcesOutput", mergedResourcesOut);

    // TODO(corysmith): Move the data binding parsing out of the merging pass to enable faster
    // aapt2 builds.
    parsedMergeBuilder.maybeAddOutput("--dataBindingInfoOut", dataBindingInfoZip);

    (useCompiledMerge ? compiledMergeBuilder : parsedMergeBuilder)
        .addOutput("--classJarOutput", classJarOut)
        .addLabelFlag("--targetLabel")

        // For now, do manifest processing to remove placeholders that aren't handled by the legacy
        // manifest merger. Remove this once enough users migrate over to the new manifest merger.
        .maybeAddOutput("--manifestOutput", manifestOut);

    if (useCompiledMerge) {
      buildCompiledResourceMergingAction(compiledMergeBuilder);
    }

    // Always make an action for merging parsed resources - the merged resources are still created
    // this way.
    buildParsedResourceMergingAction(parsedMergeBuilder);
  }

  public MergedAndroidResources build(
      AndroidDataContext dataContext, ParsedAndroidResources parsed) {
    withPrimary(parsed).build(dataContext);

    return MergedAndroidResources.of(
        parsed,
        mergedResourcesOut,
        classJarOut,
        dataBindingInfoZip,
        dependencies,
        parsed.getStampedManifest().withProcessedManifest(manifestOut));
  }
}
