package com.google.copybara.folder;

import static com.google.copybara.testing.FileSubjects.assertThatPath;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.copybara.Destination;
import com.google.copybara.RepoException;
import com.google.copybara.config.ConfigValidationException;
import com.google.copybara.testing.DummyReference;
import com.google.copybara.testing.OptionsBuilder;
import com.google.copybara.testing.SkylarkTestExecutor;
import com.google.copybara.testing.TransformResults;
import com.google.copybara.util.PathMatcherBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class FolderDestinationTest {

  private static final String CONFIG_NAME = "copybara_project";

  private OptionsBuilder options;
  private ImmutableList<String> excludedPathsForDeletion;

  @Rule
  public ExpectedException thrown = ExpectedException.none();
  private Path workdir;
  private SkylarkTestExecutor skylark;

  @Before
  public void setup() throws IOException, RepoException {
    workdir = Files.createTempDirectory("workdir");
    options = new OptionsBuilder().setWorkdirToRealTempDir();
    Files.createDirectory(workdir.resolve("dir"));
    Files.write(workdir.resolve("test.txt"), new byte[]{});
    Files.write(workdir.resolve("dir/file.txt"), new byte[]{});
    excludedPathsForDeletion = ImmutableList.of();
    skylark = new SkylarkTestExecutor(options, FolderDestination.Module.class);
  }

  private void write() throws ConfigValidationException, RepoException, IOException {
    skylark.<Destination>eval("dest", String.format(""
        + "core.project( name = '%s')\n"
        + "dest = folder.destination()", CONFIG_NAME))
        .newWriter()
        .write(
            TransformResults.of(
                workdir,
                new DummyReference("origin_ref"),
                PathMatcherBuilder.create(workdir.getFileSystem(), excludedPathsForDeletion,
                    ImmutableList.<String>of())),
            options.general.console());
  }

  @Test
  public void testDeleteWithEmptyExcludes()
      throws IOException, ConfigValidationException, RepoException {
    workdir = Files.createTempDirectory("workdir");
    Path localFolder = Files.createTempDirectory("local_folder");

    Files.write(workdir.resolve("file1.txt"), new byte[]{});
    Files.write(localFolder.resolve("file2.txt"), new byte[]{});

    options.localDestination.localFolder = localFolder.toString();

    write();

    assertThatPath(localFolder)
        .containsFiles("file1.txt")
        .containsNoMoreFiles();
  }

  @Test
  public void testCopyWithExcludes() throws Exception {
    Path localFolder = Files.createTempDirectory("local_folder");

    Files.createDirectory(localFolder.resolve("one"));
    Files.createDirectory(localFolder.resolve("two"));
    Files.write(localFolder.resolve("root_file"), new byte[]{});
    Files.write(localFolder.resolve("root_file2"), new byte[]{});
    Files.write(localFolder.resolve("one/file.txt"), new byte[]{});
    Files.write(localFolder.resolve("one/file.java"), new byte[]{});
    Files.write(localFolder.resolve("two/file.java"), new byte[]{});

    options.localDestination.localFolder = localFolder.toString();
    excludedPathsForDeletion = ImmutableList.of("root_file", "**\\.java");

    write();

    assertThatPath(localFolder)
        .containsFiles("one", "two", "root_file",
            "one/file.java", "two/file.java", "test.txt", "dir/file.txt")
        .containsNoMoreFiles();
  }

  @Test
  public void testDefaultRoot() throws Exception {
    Path defaultRootPath = Files.createTempDirectory("defaultRoot");
    options.setCurrentWorkDir(defaultRootPath.toString());

    write();

    Path outputPath = Iterables.getOnlyElement(
        Files.newDirectoryStream(defaultRootPath.resolve("copybara/out/copybaraproject")));

    assertThatPath(outputPath)
        .containsFiles("test.txt", "dir/file.txt")
        .containsNoMoreFiles();
  }
}
