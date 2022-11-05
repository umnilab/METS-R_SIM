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

File: Mappable.java 

*/



package galois.objects;

import galois.runtime.MapInternalContext;
import util.fn.Lambda2Void;
import util.fn.Lambda3Void;
import util.fn.LambdaVoid;

/**
 * Alternative to Java {@link Iterable} pattern. Mappables have lower
 * overhead because they don't need to create iterator objects in many
 * cases. They also support both concurrent and serial iteration.
 * 
 * <p>
 * The <code>mapInternal</code> methods are for concurrent iteration.
 * These are not intended to be called directly by application code, but
 * rather through {@link galois.runtime.GaloisRuntime#foreach(Mappable, LambdaVoid)}
 * and its variants, which set up the appropriate {@link MapInternalContext}
 * objects.
 * 
 * <p>
 * The <code>map</code> methods are for serial iteration. These can be used
 * directly by application code.
 * 
 *
 * @param <T>  type of elements being iterated over
 */
public interface Mappable<T> {
  /**
   * Applies a function to each element of this mappable instance concurrently.
   * Not intended to be called directly.
   * 
   * @see galois.runtime.GaloisRuntime#foreach(Mappable, LambdaVoid)
   * @param body  function to apply to each element
   */
  public void mapInternal(LambdaVoid<T> body, MapInternalContext ctx);

  /**
   * Applies a function to each element of this mappable instance concurrently.
   * Not intended to be called directly.
   * 
   * @see galois.runtime.GaloisRuntime#foreach(Mappable, Lambda2Void, Object)
   * @param body  function to apply to each element
   * @param arg1  additional argument to function
   */
  public <A1> void mapInternal(Lambda2Void<T, A1> body, MapInternalContext ctx, A1 arg1);

  /**
   * Applies a function to each element of this mappable instance concurrently.
   * Not intended to be called directly.
   * 
   * @see galois.runtime.GaloisRuntime#foreach(Mappable, Lambda3Void, Object, Object)
   * @param body  function to apply to each element
   * @param arg1  additional argument to function
   * @param arg2  additional argument to function
   */
  public <A1, A2> void mapInternal(Lambda3Void<T, A1, A2> body, MapInternalContext ctx, A1 arg1, A2 arg2);

  /**
   * Signals to instance that concurrent iteration is complete.
   * Not intended to be called directly.
   */
  public void mapInternalDone();

  /**
   * Applies a function to each element of this mappable instance serially.
   * 
   * @param body  function to apply to each element
   */
  public void map(LambdaVoid<T> body);

  /**
   * Applies a function to each element of this mappable instance serially.
   * 
   * @param body  function to apply to each element
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   */
  public void map(LambdaVoid<T> body, byte flags);
  
  /**
   * Applies a function to each element of this mappable instance serially.
   * 
   * @param body  function to apply to each element
   * @param arg1  additional argument to function
   */
  public <A1> void map(Lambda2Void<T, A1> body, A1 arg1);
  
  /**
   * Applies a function to each element of this mappable instance serially.
   * 
   * @param body  function to apply to each element
   * @param arg1  argument to function
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   */
  public <A1> void map(Lambda2Void<T, A1> body, A1 arg1, byte flags);
  
  /**
   * Applies a function to each element of this mappable instance serially.
   * 
   * @param body  function to apply to each element
   * @param arg1  additional argument to function
   * @param arg2  additional argument to function
   */
  public <A1, A2> void map(Lambda3Void<T, A1, A2> body, A1 arg1, A2 arg2);
  
  /**
   * Applies a function to each element of this mappable instance serially.
   * 
   * @param body  function to apply to each element
   * @param arg1  additional argument to function
   * @param arg2  additional argument to function
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   */
  public <A1, A2> void map(Lambda3Void<T, A1, A2> body, A1 arg1, A2 arg2, byte flags);
}
