/*
 * The MIT License
 *
 * Copyright 2015 Emily Mabrey (emabrey@users.noreply.github.com).
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.github.emabrey.maven.plugins.basedir;

import java.io.File;
import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;


/**
 * Determines the directory closest to the filesystem root from all available project directories within the current
 * reactor and outputs that directory according to the user configurable parameters.
 * 
 * @author Emily Mabrey (emabrey@users.noreply.github.com)
 */
public class RootProjectBasedirGoal extends AbstractOutputPropertyMojo {
  
  /**
   * A {@link Path} {@link Comparator} which returns the directory with the "highest level" (higher level directories
   * are closer to the root of the filesystem) via a platform specific lexicographical comparison.
   */
  private static final Comparator<Path> ROOT_DIRECTORY_COMPARATOR = (Path a, Path b) -> a.compareTo(b);
  
  /**
   * A {@link List} containing all the {@link MavenProject} references of the current reactor.
   */
  @Getter(AccessLevel.PROTECTED)
  @Parameter(defaultValue = "${reactorProjects}", readonly = true)
  private List<MavenProject> reactorProjects;
  
  /**
   * If true than {@link #stringifyPath(Path)} modifies the input {@link Path} by following symbolic links; if this
   * value is false, the symbolic links are not followed and the input links are evaluated literally without being
   * followed.
   */
  @Getter(AccessLevel.PROTECTED)
  @Parameter(defaultValue = "true", alias = "followSymbolicLinks")
  private boolean followingSymbolicLinks;
  
  /**
   * Entry point for Maven plugin execution.
   * 
   * @throws MojoExecutionException
   *         Standard Maven exception indicating a seemingly non-fatal error has occurred.
   * @throws MojoFailureException
   *         Standard Maven exception indicating a fatal error has occurred.
   */
  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
  
    if (isExecutionSkipped()) {
      skipPluginExecution();
    }
    else {
      generatePluginOutput();
    }
    
  }
  
  /**
   * If the execution of the plugin is configured as skipped, this is the plugin's main execution branch.
   */
  private void skipPluginExecution() {
  
    writeDebugLogMessage("Execution skipped");
  }
  
  /**
   * If the execution of the plugin is not configured as skipped, this is the plugin's main execution branch.
   * 
   * @throws MojoFailureException
   *         If generation of the plugin output fails.
   */
  private void generatePluginOutput() throws MojoFailureException {
  
    setOutputValue(generateRootDirectoryStringOutput());
  }
  
  /**
   * Generate the {@link String} equivalent {@link Path} representing the root directory which meets all criteria; the
   * generated String may vary based upon the configuration of {@link #stringifyRootDirectoryPath(Path)}.
   * 
   * @return A {@link String} containing a system dependent textual representation of the highest level root directory
   *         {@link Path}.
   * @throws MojoFailureException
   *         If the {@link Path} cannot be determined due to an underlying failure of
   *         {@link #selectValidProjectDirectoryClosestToFilesystemRoot(List)}.
   */
  private String generateRootDirectoryStringOutput() throws MojoFailureException {
  
    final Path validRootDirectory = selectValidProjectDirectoryClosestToFilesystemRoot(reactorProjects);
    
    return stringifyRootDirectoryPath(validRootDirectory);
  }
  
  /**
   * Select the {@link Path} representing the root directory which meets all validation criteria and which is closest to
   * the root of the filesystem shared across the reactor projects.
   * 
   * @param projectsWithDirectories
   *        A {@link List} of projects which share a common filesystem for their project base directories.
   * @return A {@link Path} which has been validated and selected as the closest to the filesystem root.
   * @throws MojoFailureException
   *         If the {@link Path} cannot be determined due to an underlying {@link IOException}.
   */
  private Path selectValidProjectDirectoryClosestToFilesystemRoot(final List<MavenProject> projectsWithDirectories)
    throws MojoFailureException {
  
    try {
      
      final List<File> rootDirectoryCandidates = getNonullProjectBaseDirectories(projectsWithDirectories);
      final List<Path> validRootDirectories = convertValidDirectoryFilesToPaths(rootDirectoryCandidates);
      final Path rootDirectory = selectPathClosestToFilesystemRoot(validRootDirectories);
      
      return rootDirectory;
    }
    catch (IOException ex) {
      throw new MojoFailureException("Unable to generate root directory output due to IOException", ex);
    }
  }
  
  /**
   * Generates a {@link String} representation of a given {@link Path} by first resolving or ignoring symbolic links as
   * directed by the value of {@link #isFollowingSymbolicLinks()} and then subsequently returning the normalized and
   * system dependent {@link String} representation of that result.
   * 
   * @param givenPath
   *        A {@link Path} instance
   * @return A normalized {@link String} representation of the {@link Path} result of the aforementioned process.
   * @throws MojoFailureException
   *         If the {@link Path} cannot generate a {@link String} representation due to an underlying
   *         {@link IOException}.
   */
  private String stringifyRootDirectoryPath(final Path givenPath) throws MojoFailureException {
  
    try {
      if (isFollowingSymbolicLinks()) {
        return convertPathToStringWithSymbolicLinks(givenPath);
      }
      else {
        return convertPathToStringWithoutSymbolicLinks(givenPath);
      }
    }
    catch (IOException ex) {
      throw new MojoFailureException("Unable to convert Path to String", ex);
    }
  }
  
  /**
   * Generates a {@link List} of {@link Path} instances via transforming the provided {@link List} of {@link File}
   * instances; an included validation that each {@link File} instance is a non-null reference to a directory which
   * exists is a critical part of the transformation of the list elements. If an input {@link File} instance is invalid
   * it is discarded and not used to populate the list of {@link Path} conversions. The underlying filesystem data must
   * remain invariant during this methods execution for the result to be valid.
   *
   * @param files
   *        The {@link List} of {@link File} instances which will be evaluated to generate the {@link List} of
   *        {@link Path} instances.
   * @return A {@link List} of {@link Path} instances generated from the transformation of the validated {@link File}
   *         instances.
   */
  private List<Path> convertValidDirectoryFilesToPaths(final List<File> files) {
  
    final List<Path> paths = new ArrayList<>(files.size());
    
    writeDebugLogMessage("Validating root directory files");
    
    for (File f : files) {
      if (isValidRootDirectoryCandidate(f)) {
        writeTraceLogMessage(() -> String.format("Valid: %s", f));
        paths.add(f.toPath());
      }
      else {
        writeTraceLogMessage(() -> String.format("Invalid: %s", f));
      }
    }
    
    return paths;
  }
  
  /**
   * Generates a {@link List} populated with all non-null base directory values acquired from querying the provided
   * {@link MavenProject} instances.
   *
   * @param projects
   *        The {@link List} of {@link MavenProject} instances from which base directory information should be
   *        collected.
   * @return A {@link List} of {@link File} instances generated using the base directory data of the given
   *         {@link MavenProject} instances.
   */
  private List<File> getNonullProjectBaseDirectories(final List<MavenProject> projects) {
  
    final List<File> candidates = new ArrayList<>(projects.size());
    
    for (MavenProject project : projects) {
      final File baseDir = project.getBasedir();
      if (baseDir != null) {
        candidates.add(baseDir);
      }
    }
    
    return candidates;
  }
  
  /**
   * Evaluates each of the given {@link Path} instances for being closest to the root of the filesystem and returns the
   * {@link Path} instance which most closely matches that directory level expectations (it must be the common single
   * root directory from all given {@link Path} instances); for a more nuanced description of the actual process of
   * evaluating the {@link Path} instances examine the {@link #rootDirectoryComparator()} method, which generates the
   * {@link Comparator} used to formally evaluate the {@link Path} instances.
   *
   * @param paths
   *        A {@link List} of {@link Path} instances which must share enough directory structure to have a common single
   *        root directory within a common filesystem.
   * @return The {@link Path} instance which is the root directory of all those provided.
   * @throws IOException
   *         If the determination of a root directory for the given {@link Path} instances is indeterminable (for
   *         instance, if the given {@link List} of {@link Path} instances contains a {@link Path} instance from a
   *         filesystem unconnected to the other provided instances then there is not a common single root directory).
   */
  private Path selectPathClosestToFilesystemRoot(final List<Path> paths) throws IOException {
  
    try {
      return Collections.min(paths, ROOT_DIRECTORY_COMPARATOR);
    }
    catch (ClassCastException ex) {
      throw new IOException("The root directory path is indeterminable");
    }
  }
  
  /**
   * Converts the given {@link Path} to a normalized real path which has all symbolic links resolved.
   * 
   * @param givenPath
   *        A {@link Path} instance
   * @return A normalized {@link String} representation of the given {@link Path}
   * @throws IOException
   *         If the {@link Path} cannot be converted to a {@link String} representation due to an underlying
   *         {@link IOException}.
   */
  private String convertPathToStringWithSymbolicLinks(final Path givenPath) throws IOException {
  
    writeTraceLogMessage("Path converted to String with symbolic links");
    return givenPath.toRealPath().normalize().toString();
  }
  
  /**
   * Converts the given {@link Path} to a normalized real path which has all symbolic links unresolved.
   * 
   * @param givenPath
   *        A {@link Path} instance
   * @return A normalized {@link String} representation of the given {@link Path}
   * @throws IOException
   *         If the {@link Path} cannot be converted to a {@link String} representation due to an underlying
   *         {@link IOException}.
   */
  private String convertPathToStringWithoutSymbolicLinks(final Path givenPath) throws IOException {
  
    writeTraceLogMessage("Path converted to String without symbolic links");
    return givenPath.toRealPath(LinkOption.NOFOLLOW_LINKS).toString();
  }
  
  /**
   * Determines the validity of an input {@link File} instance according to criteria required for
   * {@link #convertValidDirectoryFilesToPaths(List)}.
   * 
   * @param f
   *        A {@link File} instance
   * @return True if the given {@link File} instance is valid or false if the instance is not valid.
   */
  private boolean isValidRootDirectoryCandidate(final File f) {
  
    return f != null && f.isDirectory() && f.exists();
  }
  
}
