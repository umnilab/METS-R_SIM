/*
Galois, a framework to exploit amorphous data-parallelism in irregular
programs.

Copyright (C) 2010, The University of Texas at Austin. All rights reserved.
UNIVERSITY EXPRESSLY DISCLAIMS ANY AND ALL WARRANTIES CONCERNING THIS SOFTWARE
AND DOCUMENTATION, INCLUDING ANY WARRANTIES OF MERCHANTABILITY, FITNESS FOR ANY
PARTICULAR PURPOSE, NON-INFRINGEMENT AND WARRANTIES OF PERFORMANCE, AND ANY
WARRANTY THAT MIGHT OTHERWISE ARISE FROM COURSE OF DEALING OR USAGE OF TRADE.
NO WARRANTY IS EITHER EXPRESS OR IMPLIED WITH RESPECT TO THE USE OF THE
SOFTWARE OR DOCUMENTATION. Under no circumstances shall University be liable
for incidental, special, indirect, direct or consequential damages or loss of
profits, interruption of business, or related expenses which may arise from use
of Software or Documentation, including but not limited to those resulting from
defects in Software and/or Documentation, or loss or inaccuracy of data of any
kind.

File: InputOutput.java 

*/



package util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Helper methods related to the filesystem, but not included in 
 * {@link java.nio} or {@link java.io}
 */

public final class InputOutput {

  /**
   * File separator, OS-dependent
   */
  public static final String FILE_SEPARATOR = System.getProperty("file.separator");
  /**
   * Line separator, OS-dependent
   */
  public static final String LINE_SEPARATOR = System.getProperty("line.separator");

  /**
   * Returns a list containing the absolute path of every file that is contained
   * in the given directory and matches the given regular expression.
   *
   * @param directory absolute path representing a directory
   * @param suffix    regular expression
   * @return  full paths of all files matching the given criteria
   */
  public static Collection<String> getFilePathsMatching(String directory, final String suffix) {
    FilenameFilter filter = new FilenameFilter() {
      public boolean accept(File dir, String name) {
        return name.matches(suffix);
      }
    };
    return getFilePaths(directory, filter);
  }

  /**
   * Returns a list containing the absolute path of every file that is contained
   * in the given directory and has the given suffix
   *
   * @param directory absolute path representing a directory.
   * @param suffix    suffix we are looking for.
   * @return  full paths of all files matching the given criteria.
   */
  public static Collection<String> getFilePathsEndingWith(String directory, final String suffix) {
    FilenameFilter filter = new FilenameFilter() {
      public boolean accept(File dir, String name) {
        return name.endsWith(suffix);
      }
    };
    return getFilePaths(directory, filter);
  }

  /**
   * Returns a list containing the absolute path of every file that is contained
   * in the given directory and has the given prefix
   *
   * @param directory absolute path representing a directory
   * @param prefix    prefix we are looking for
   * @return  full paths of all files matching the given criteria
   */
  public static Collection<String> getFilePathsStartingWith(String directory, final String prefix) {
    FilenameFilter filter = new FilenameFilter() {
      public boolean accept(File dir, String name) {
        return name.startsWith(prefix);
      }
    };
    return getFilePaths(directory, filter);
  }

  private static Collection<String> getFilePaths(String directory, FilenameFilter filter) {
    File inputDirectory = new File(directory);
    String[] children = inputDirectory.list(filter);
    if (children == null) {
      throw new RuntimeException("Input directory: " + inputDirectory + " does not exist or is not a directory.");
    }
    List<String> result = Arrays.asList(children);
    Collections.sort(result);
    for (int i = 0; i < result.size(); i++) {
      String fullPath = directory + "/" + result.get(i);
      result.set(i, fullPath);
    }
    return result;
  }

  /**
   * Appends the given text into the designated file
   *
   * @param filePath full path to a file; if the file does not exist, it is created.
   * @param text     text to write in the file
   * @throws IOException  if there is an error while writing or closing the file
   */
  public static void write(final String filePath, final String text) throws IOException {
    write(filePath, text, true);
  }

  /**
   * Write the given text into the designated file.
   *
   * @param filePath full path to a file; if the file does not exist, it is created.
   * @param text     text to write in the file
   * @param append   whether to append (true) to or overwrite (false) the contents of
   *                 the file
   * @throws IOException if there is an error while writing or closing the file
   */
  public static void write(final String filePath, final String text, boolean append) throws IOException {
    BufferedWriter out = new BufferedWriter(new FileWriter(filePath, append));
    out.write(text);
    out.close();
  }
}
