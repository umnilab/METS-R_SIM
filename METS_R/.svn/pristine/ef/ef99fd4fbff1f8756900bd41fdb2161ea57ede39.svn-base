/*
Copyright (c) 2008 Evan Jones, Yang Zhang

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
 */

package util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal pure Java sampling profiler.
 * 
 * Due to biased placement of yield points. Samples will be
 * biased towards code that contains many such points [1]. Can't
 * do much about this problem with a pure Java sampler. 
 * 
 * [1] Mytkowicz, Diwan, Hauswirth, Sweeney. "Evaluating the Accuracy of Java Profilers." PLDI 2010
 */
public class StackSampler extends Sampler {
  private final Deque<Map<Thread, StackTraceElement[]>> samples;

  /**
   * Only include stack traces that contain this method name.
   */
  public static final String includeMethodName = "__stackSamplerRecordMe";

  protected StackSampler(int intervalMillis) {
    super(intervalMillis);

    samples = new ArrayDeque<Map<Thread, StackTraceElement[]>>();
  }

  @Override
  protected void sample() {
    samples.add(Thread.getAllStackTraces());
  }

  private Map<Thread, List<StackTraceElement[]>> getSamples() {
    Map<Thread, List<StackTraceElement[]>> retval = new HashMap<Thread, List<StackTraceElement[]>>();
    for (Map<Thread, StackTraceElement[]> sample : samples) {
      for (Map.Entry<Thread, StackTraceElement[]> entry : sample.entrySet()) {
        Thread t = entry.getKey();

        StackTraceElement[] stack = entry.getValue();
        boolean keep = false;
        for (int i = 0; i < stack.length; i++) {
          if (stack[i].getMethodName().startsWith(includeMethodName)) {
            keep = true;
          }
        }

        if (!keep)
          continue;

        List<StackTraceElement[]> list = retval.get(t);
        if (list == null) {
          list = new ArrayList<StackTraceElement[]>();
          retval.put(t, list);
        }

        list.add(stack);
      }
    }
    return retval;
  }

  @Override
  protected StackStatistics dumpSamples() {
    return new StackStatistics(getSamples());
  }

  /**
   * Start sampling with this sampler.
   * 
   * @param interval  sample interval in milliseconds
   * @return          a sampler
   */
  public static Sampler start(int interval) {
    return start(new StackSampler(interval));
  }
}
