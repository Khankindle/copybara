package com.google.copybara;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.copybara.Origin.Reference;
import com.google.copybara.config.ConfigValidationException;
import com.google.copybara.config.NonReversibleValidationException;
import com.google.copybara.config.skylark.OptionsAwareModule;
import com.google.copybara.transform.Move;
import com.google.copybara.transform.Sequence;
import com.google.copybara.transform.TransformOptions;
import com.google.copybara.transform.Transformation;
import com.google.copybara.util.PathMatcherBuilder;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.skylarkinterface.SkylarkSignature;
import com.google.devtools.build.lib.syntax.BuiltinFunction;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.Runtime.NoneType;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkList.MutableList;
import com.google.devtools.build.lib.syntax.Type;
import java.nio.file.FileSystems;
import java.util.HashMap;
import java.util.Map;

/**
 * Main configuration class for creating workflows.
 *
 * <p>This class is exposed in Skylark configuration as an instance variable
 * called "core". So users can use it as:
 * <pre>
 * core.workspace(
 *   name = "foo",
 *   ...
 * )
 * </pre>
 */
@SkylarkModule(
    name = Core.CORE_VAR,
    doc = "Core functionality for creating workflows, and basic transformations.",
    category = SkylarkModuleCategory.BUILTIN)
public class Core implements OptionsAwareModule {

  public static final String CORE_VAR = "core";

  private final Map<String, Workflow<?>> workflows = new HashMap<>();
  private GeneralOptions generalOptions;
  private TransformOptions transformOptions;
  private WorkflowOptions workflowOptions;
  private String projectName;

  @Override
  public void setOptions(Options options) {
    generalOptions = options.get(GeneralOptions.class);
    transformOptions = options.get(TransformOptions.class);
    workflowOptions = options.get(WorkflowOptions.class);
  }

  public String getProjectName() {
    return projectName;
  }

  public Map<String, Workflow<?>> getWorkflows() {
    return workflows;
  }


  @SkylarkSignature(
      name = "glob",
      returnType = PathMatcherBuilder.class,
      doc = "Glob returns a list of every file in the workdir that matches at least one"
          + " pattern in include and does not match any of the patterns in exclude.",
      parameters = {
          @Param(name = "include", type = SkylarkList.class,
              generic1 = String.class, doc = "The list of glob patterns to include",
              defaultValue = "[]"),
          @Param(name = "exclude", type = SkylarkList.class,
              generic1 = String.class, doc = "The list of glob patterns to exclude",
              defaultValue = "[]"),
      },
      objectType = Object.class)
  public static final BuiltinFunction GLOB = new BuiltinFunction("glob") {
    public PathMatcherBuilder invoke(SkylarkList include, SkylarkList exclude)
        throws EvalException, ConfigValidationException {
      return PathMatcherBuilder.create(FileSystems.getDefault(),
          Type.STRING_LIST.convert(include, "include"),
          Type.STRING_LIST.convert(exclude, "exclude"));
    }
  };

  @SkylarkSignature(name = "project", returnType = NoneType.class,
      doc = "General configuration of the project. Like the name.",
      parameters = {
          @Param(name = "self", type = Core.class, doc = "this object"),
          @Param(name = "name", type = String.class, doc = "The name of the configuration."),
      },
      objectType = Core.class, useLocation = true)
  public static final BuiltinFunction PROJECT = new BuiltinFunction("project") {
    public NoneType invoke(Core self, String name, Location location) throws EvalException {
      if (Strings.isNullOrEmpty(name) || name.trim().equals("")) {
        throw new EvalException(location, "Empty name for the project is not allowed");
      }
      self.projectName = name;
      return Runtime.NONE;
    }
  };

  @SkylarkSignature(name = "reverse", returnType = SkylarkList.class,
      doc = "Given a list of transformations, returns the list of transformations equivalent to"
          + " undoing all the transformations",
      parameters = {
          @Param(name = "self", type = Core.class, doc = "this object"),
          @Param(name = "transformations", type = SkylarkList.class,
              generic1 = Transformation.class, doc = "The transformations to reverse"),
      },
      objectType = Core.class, useLocation = true)
  public static final BuiltinFunction REVERSE =
      new BuiltinFunction("reverse") {
        public SkylarkList<Transformation> invoke(Core self, SkylarkList<Transformation> transforms,
            Location location)
            throws EvalException {

          ImmutableList.Builder<Transformation> builder = ImmutableList.builder();
          for (Transformation t : transforms.getContents(Transformation.class, "transformations")) {
            try {
              builder.add(t.reverse());
            } catch (NonReversibleValidationException e) {
              throw new EvalException(location, e.getMessage());
            }
          }

          return new MutableList<>(builder.build().reverse());
        }
      };

  @SkylarkSignature(name = "workflow", returnType = NoneType.class,
      doc = "Defines a migration pipeline which can be invoked via the Copybara command.",
      parameters = {
          @Param(name = "self", type = Core.class, doc = "this object"),
          @Param(name = "name", type = String.class,
              doc = "The name of the workflow."),
          @Param(name = "origin", type = Origin.class,
              doc = "Where to read the migration code from."),
          @Param(name = "destination", type = Destination.class,
              doc = "Where to read the migration code from."),
          @Param(name = "authoring", type = Authoring.class,
              doc = "The author mapping configuration from origin to destination."),
          @Param(name = "transformations", type = SkylarkList.class,
              generic1 = Transformation.class,
              doc = "Where to read the migration code from."),
          @Param(name = "exclude_in_origin", type = PathMatcherBuilder.class,
              doc = "A globs relative to the workdir that will be excluded from the"
                  + " origin during the import. For example \"**.java\", all java files,"
                  + " recursively.",
              defaultValue = "None", noneable = true),
          @Param(name = "exclude_in_destination", type = PathMatcherBuilder.class,
              doc = "A glob relative to the root of the destination"
                  + " repository that will not be removed even if the file does not"
                  + " exist in the source. For example '**/BUILD', all BUILD files,"
                  + " recursively.",
              defaultValue = "None", noneable = true),
      },
      objectType = Core.class, useLocation = true)
  public static final BuiltinFunction WORKFLOW = new BuiltinFunction("workflow") {
    public NoneType invoke(Core self, String workflowName,
        Origin<Reference> origin, Destination destination, Authoring authoring,
        SkylarkList<Transformation> transformations,
        Object excludeInOrigin,
        Object excludeInDestination,
        Location location)
        throws EvalException {

      // TODO(malcon): map the rest of Workflow parameters
      self.workflows.put(workflowName, new AutoValue_Workflow<>(
          getProjectNameOrFailInternal(self, location),
          workflowName,
          origin,
          destination,
          authoring,
          Sequence.createSequence(ImmutableList.copyOf(transformations)),
          self.workflowOptions.getLastRevision(),
          self.generalOptions.console(),
          PathMatcherBuilder.convertFromNoneable(excludeInOrigin, PathMatcherBuilder.EMPTY),
          PathMatcherBuilder.convertFromNoneable(excludeInDestination, PathMatcherBuilder.EMPTY),
          WorkflowMode.SQUASH, /*includeChangelistNotes=*/true, self.workflowOptions,
          /*reversibleCheck=*/ false, self.generalOptions.isVerbose(), /*askForConfirmation=*/
          false));
      return Runtime.NONE;
    }
  };

  @SkylarkSignature(
      name = "move",
      returnType = Move.class,
      doc = "Moves files between directories and renames files",
      parameters = {
        @Param(name = "self", type = Core.class, doc = "this object"),
        @Param(name = "before", type = String.class, doc = ""
            + "The name of the file or directory before moving. If this is the empty"
            + " string and 'after' is a directory, then all files in the workdir will be moved to"
            + " the sub directory specified by 'after', maintaining the directory tree."),
        @Param(name = "after", type = String.class, doc = ""
            + "The name of the file or directory after moving. If this is the empty"
            + " string and 'before' is a directory, then all files in 'before' will be moved to"
            + " the repo root, maintaining the directory tree inside 'before'.")
      },
      objectType = Core.class, useLocation = true)
  public static final BuiltinFunction MOVE = new BuiltinFunction("move") {
    public Move invoke(Core self, String before, String after, Location location) throws EvalException {
      return Move.fromConfig(before, after, self.transformOptions, location);
    }
  };

  /**
   * Find the project name from the enviroment 'core' object or fail if there was no
   * project( name = 'foo') in the config file before the current call.
   */
  public static String getProjectNameOrFail(Environment env, Location location)
      throws EvalException {
    return getProjectNameOrFailInternal(((Core) env.getGlobals().get(CORE_VAR)), location);
  }

  private static String getProjectNameOrFailInternal(Core self, Location location)
      throws EvalException {
    if (self.projectName == null) {
      throw new EvalException(location, "Project name not defined. Use project() first.");
    }
    return self.projectName;
  }

}
