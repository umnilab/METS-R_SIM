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

File: StackStatistics.java 

*/



package util;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Statistics from stack samples. Computes hot methods from stack samples.
 * 
 *
 */
public class StackStatistics extends Statistics {
  private final Map<Thread, List<StackTraceElement[]>> threadStacks;

  /**
   * Creates stack statistics from a list of stack traces.
   * 
   * @param threadStacks  lists of stack traces for each thread
   */
  public StackStatistics(Map<Thread, List<StackTraceElement[]>> threadStacks) {
    this.threadStacks = threadStacks;
  }

  @Override
  public void dumpFull(PrintStream out) {
    printFullHeader(out, "Stacks (top ten)");
    printTop(out);

    printFullHeader(out, "Stacks");

    for (List<StackTraceElement[]> stacks : threadStacks.values()) {
      for (StackTraceElement[] stack : stacks) {
        for (int i = 0; i < stack.length; i++) {
          out.println(stack[i]);
        }
        out.println();
      }
    }
  }

  @Override
  public void dumpSummary(PrintStream out) {
    printSummaryHeader(out, "Stacks (top ten)");
    out.println();
    printTop(out);
  }

  private void printTop(PrintStream out) {
    // TODO(ddn): Collate by threads and with statistics
    final Map<String, Integer> selfCounts = new HashMap<String, Integer>();
    // self + children
    final Map<String, Integer> totalCounts = new HashMap<String, Integer>();
    int totalSamples = 0;
    Set<String> seen = new HashSet<String>();

    for (List<StackTraceElement[]> stacks : threadStacks.values()) {
      totalSamples += stacks.size();

      for (StackTraceElement[] stack : stacks) {
        int stopIndex = -1;
        for (int i = stack.length - 1; i >= 0; i--) {
          String methodName = stack[i].getMethodName();

          if (methodName.startsWith(StackSampler.includeMethodName)) {
            stopIndex = i;
            break;
          }
        }

        if (stopIndex < 0)
          continue;

        for (int i = 0; i < stopIndex; i++) {
          String key = String.format("%s.%s", stack[i].getClassName(), stack[i].getMethodName());
          // Skip subsequent recursive call frames
          if (seen.contains(key)) {
            continue;
          }

          if (i == 0) {
            addOne(selfCounts, key);
          }
          addOne(totalCounts, key);

          seen.add(key);
        }
        seen.clear();
      }
    }

    out.println("  Self:");
    dumpTop(out, selfCounts, totalSamples, "    ");

    out.println("  Total:");
    dumpTop(out, totalCounts, totalSamples, "    ");
  }

  @Override
  public void merge(Object obj) {
    StackStatistics other = (StackStatistics) obj;
    for (Map.Entry<Thread, List<StackTraceElement[]>> entry : other.threadStacks.entrySet()) {
      List<StackTraceElement[]> value = threadStacks.get(entry.getKey());
      if (value == null) {
        value = new ArrayList<StackTraceElement[]>();
        threadStacks.put(entry.getKey(), value);
      }
      value.addAll(entry.getValue());
    }
  }

  private void dumpTop(PrintStream out, final Map<String, Integer> counts, int totalSamples, String prefix) {
    int count = 0;
    for (String method : descendingOrder(counts)) {
      float ratio = counts.get(method) / (float) totalSamples;
      if (count++ > 10)
        break;
      else if (ratio > 0.05)
        out.printf("%s%s %.3f\n", prefix, method, ratio);
      else
        break;
    }
  }

  private static void addOne(Map<String, Integer> map, String key) {
    Integer value = map.get(key);
    if (value == null) {
      map.put(key, 1);
    } else {
      map.put(key, value + 1);
    }
  }

  private static List<String> descendingOrder(final Map<String, Integer> map) {
    List<String> methods = new ArrayList<String>(map.keySet());
    Collections.sort(methods, new Comparator<String>() {
      @Override
      public int compare(String arg0, String arg1) {
        return map.get(arg1) - map.get(arg0);
      }
    });
    return methods;
  }
}
