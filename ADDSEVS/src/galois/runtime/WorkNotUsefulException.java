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
 * This exception is thrown when a piece of work should not be counted as
 * "useful." This can be because it is no longer necessary to process but
 * remains in the worklist until it can be lazily cleaned up.
 * 
 * For example, a bad triangle in Delaunay mesh refinement that is removed from the
 * mesh due to the fixing of another triangle will remain in the worklist
 * until it is encountered. This should not count against the amount of useful
 * work to be done.
 */
public class WorkNotUsefulException extends RuntimeException {
  private static final long serialVersionUID = 4278675165695977083L;
  private static final WorkNotUsefulException instance = new WorkNotUsefulException();
  
  private WorkNotUsefulException() {
    
  }
  
  /**
   * Throws {@link WorkNotUsefulException}
   */
  public static void throwException() {
    throw instance;
  }
}
