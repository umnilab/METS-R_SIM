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

File: Statistics.java 

 */

package util;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import util.fn.FnIterable;
import util.fn.Lambda;

/**
 * Abstract class for encapsulating statistics collected by the
 * runtime system.
 * 
 *
 */
public abstract class Statistics {
  /**
   * Prints a summary of the statistics. The output should be short and,
   * in most cases, at most one line long.
   * 
   * @param out output stream
   */
  public abstract void dumpSummary(PrintStream out);

  /**
   * Prints the full results for the statistics.
   * 
   * @param out output stream
   */
  public abstract void dumpFull(PrintStream out);

  /**
   * Merge two of the same type of statistics together. Used to aggregate
   * statistics together.
   * 
   * @param other another statistics object of the same type to be merged with
   */
  public abstract void merge(Object other);

  /**
   * Prints out appropriately formatted header for {@link #dumpFull(PrintStream)} output.
   * 
   * @param out    output stream
   * @param header header to print
   */
  protected final void printFullHeader(PrintStream out, String header) {
    out.printf("==== %s ====\n", header);
  }

  /**
   * Prints out appropriately formatted for {@link #dumpSummary(PrintStream)} output.
   * 
   * @param out
   * @param header
   */
  protected final void printSummaryHeader(PrintStream out, String header) {
    out.printf("%s: ", header);
  }

  /**
   * Prints out mean, min, max, and stdev of a collection of integers.
   * 
   * @param out    output stream
   * @param list   collection of integers
   * @param prefix string to prefix the output
   */
  protected final void summarizeInts(PrintStream out, Collection<Integer> list, String prefix) {
    summarizeInts(out, list, 0, prefix);
  }

  /**
   * Prints out mean, min, max, and stdev of a collection of integers.
   * 
   * @param out    output stream
   * @param list   collection of integers
   * @param drop   exclude the first <code>drop</code> integers from the output
   * @param prefix string to prefix the output
   */
  protected final void summarizeInts(PrintStream out, Collection<Integer> list, int drop, String prefix) {
    float[] stats = summarizeInts(list, drop);
    if (stats == null) {
      return;
    }

    if (stats[4] != list.size()) {
      prefix = String.format("%sDrop first %.0f: ", prefix, list.size() - stats[4]);
    }

    out.printf("%smean: %.2f min: %.0f max: %.0f stdev: %.2f\n", prefix, stats[0], stats[1], stats[2], Math
        .sqrt(stats[3]));
  }

  /**
   * Returns mean, min, max, and variance of a collection of integers. This method
   * takes a drop parameter that drops the first <i>N</i> integers before computing
   * the summary statistics.
   * 
   * @param list collection of integers
   * @param drop exclude the first <code>drop</code> integers from the results
   * @return     an array of {mean, min, max, variance, number of dropped integers}
   */
  protected final float[] summarizeInts(Collection<Integer> list, int drop) {
    if (list.isEmpty()) {
      return null;
    }

    if (list.size() <= 1 + drop) {
      drop = 0;
    }

    List<Integer> retain = new ArrayList<Integer>(list).subList(drop, list.size());
    final float mean = CollectionMath.sumInteger(retain) / (float) retain.size();
    int min = Collections.min(retain);
    int max = Collections.max(retain);

    float var = CollectionMath.sumFloat(FnIterable.from(retain).map(new Lambda<Integer, Float>() {
      @Override
      public Float call(Integer x) {
        return (x - mean) * (x - mean);
      }
    })) / (retain.size() - 1);

    float[] retval = new float[] { mean, min, max, var, retain.size() };

    return retval;
  }

  /**
   * Prints out mean, min, max, and stdev of a collection of longs.
   * 
   * @param out    output stream
   * @param list   collection of longs
   * @param prefix string to prefix the output
   */
  protected final void summarizeLongs(PrintStream out, Collection<Long> list, String prefix) {
    summarizeLongs(out, list, 0, prefix);
  }

  /**
   * Prints out mean, min, max, and stdev of a collection of longs.
   * 
   * @param out    output stream
   * @param list   collection of longs
   * @param drop   exclude the first <code>drop</code> longs from the output
   * @param prefix string to prefix the output
   */
  protected final void summarizeLongs(PrintStream out, Collection<Long> list, int drop, String prefix) {
    summarizeLongs(out, list, drop, prefix, false);
  }

  /**
   * Prints out mean, min, max, and stdev of a collection of longs.
   * 
   * @param out     output stream
   * @param list    collection of longs
   * @param drop    exclude the first <code>drop</code> longs from the output
   * @param prefix  string to prefix the output
   * @param newLine suppress trailing newline
   */
  protected final void summarizeLongs(PrintStream out, Collection<Long> list, int drop, String prefix,
      boolean suppressNewline) {
    float[] stats = summarizeLongs(list, drop);
    if (stats == null) {
      return;
    }

    if (stats[4] != list.size()) {
      prefix = String.format("%sDrop first %.0f: ", prefix, list.size() - stats[4]);
    }

    out.printf("%smean: %.2f min: %.0f max: %.0f stdev: %.2f", prefix, stats[0], stats[1], stats[2], Math
        .sqrt(stats[3]));

    if (!suppressNewline)
      out.println();
  }

  /**
   * Returns mean, min, max, and variance of a collection of longs. This method
   * takes a drop parameter that drops the first <i>N</i> longs before computing
   * the summary statistics.
   * 
   * @param list collection of longs
   * @param drop exclude the first <code>drop</code> longs from the results
   * @return     an array of {mean, min, max, variance, number of dropped longs}
   */
  protected final float[] summarizeLongs(Collection<Long> list, int drop) {
    if (list.isEmpty()) {
      return null;
    }

    if (list.size() <= 1 + drop) {
      drop = 0;
    }

    List<Long> retain = new ArrayList<Long>(list).subList(drop, list.size());
    final float mean = CollectionMath.sumLong(retain) / (float) retain.size();
    long min = Collections.min(retain);
    long max = Collections.max(retain);

    float var = CollectionMath.sumFloat(FnIterable.from(retain).map(new Lambda<Long, Float>() {
      @Override
      public Float call(Long x) {
        return (x - mean) * (x - mean);
      }
    })) / (retain.size() - 1);

    float[] retval = new float[5];
    retval[0] = mean;
    retval[1] = min;
    retval[2] = max;
    retval[3] = var;
    retval[4] = retain.size();

    return retval;
  }

  /**
   * Prints out mean, min, max, and stdev of a collection of floats.
   * 
   * @param out    output stream
   * @param list   collection of floats
   * @param prefix string to prefix the output
   */
  protected final void summarizeFloats(PrintStream out, Collection<Float> list, String prefix) {
    summarizeFloats(out, list, 0, prefix);
  }

  /**
   * Prints out mean, min, max, and stdev of a collection of floats.
   * 
   * @param out    output stream
   * @param list   collection of floats
   * @param drop   exclude the first <code>drop</code> floats from the output
   * @param prefix string to prefix the output
   */
  protected final void summarizeFloats(PrintStream out, Collection<Float> list, int drop, String prefix) {
    float[] stats = summarizeFloats(list, drop);
    if (stats == null) {
      return;
    }

    if (stats[4] != list.size()) {
      prefix = String.format("%sDrop first %d: ", prefix, list.size() - stats[4]);
    }

    out.printf("%smean: %.4f min: %.4f max: %.4f stdev: %.3f\n", prefix, stats[0], stats[1], stats[2], Math
        .sqrt(stats[3]));
  }

  /**
   * Returns mean, min, max, and variance of a collection of floats. This method
   * takes a drop parameter that drops the first <i>N</i> floats before computing
   * the summary statistics.
   * 
   * @param list collection of floats
   * @param drop exclude the first <code>drop</code> floats from the results
   * @return     an array of {mean, min, max, variance, number of dropped floats}
   */
  protected final float[] summarizeFloats(Collection<Float> list, int drop) {
    if (list.isEmpty()) {
      return null;
    }

    if (list.size() <= 1 + drop) {
      drop = 0;
    }

    List<Float> retain = new ArrayList<Float>(list).subList(drop, list.size());
    final float mean = CollectionMath.sumFloat(retain) / (float) retain.size();
    float min = Collections.min(retain);
    float max = Collections.max(retain);

    float var = CollectionMath.sumFloat(FnIterable.from(retain).map(new Lambda<Float, Float>() {
      @Override
      public Float call(Float x) {
        return (x - mean) * (x - mean);
      }
    })) / (retain.size() - 1);

    float[] retval = new float[5];
    retval[0] = mean;
    retval[1] = min;
    retval[2] = max;
    retval[3] = var;
    retval[4] = retain.size();

    return retval;
  }
}
