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


*/





package galois.runtime;

/**
 * This exception is thrown when a piece of work is useful, but does not help
 * an algorithm progress towards completion. In this case, we track its
 * execution separately (so as to separate work that actually helps an
 * algorithm terminate from work that takes execution resources but doesn't
 * actually move the algorithm forward).
 * 
 * We use this to distinguish between algorithms that genuinely have a lot of
 * parallelism and algorithms that appear to have a lot of parallelism because
 * they are doing a lot of extra work.
 * 
 * For example, in agglomerative clustering, if a point cannot be clustered with its
 * nearest neighbor yet (because its nearest neighbor thinks a different point
 * is closer), we have performed some work, but doing so doesn't actually help
 * the algorithm converge. Because these are read only operations, a schedule
 * which only chooses these "useless" points to process will be able to do
 * them all in parallel and appear to have significant parallelism, but will
 * never actually terminate.
 */
public class WorkNotProgressiveException extends RuntimeException {
  private static final long serialVersionUID = 2303257253864746238L;
  private static final WorkNotProgressiveException instance = new WorkNotProgressiveException();
  
  private WorkNotProgressiveException() {
    
  }
  
  /**
   * Throws {@link WorkNotProgressiveException}
   */
  public static void throwException() {
    throw instance;
  }
}
