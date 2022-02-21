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

File: ProductMapper.java 

*/





package galois.objects;

import galois.runtime.GaloisRuntime;
import galois.runtime.MapInternalContext;

import java.util.ArrayList;
import java.util.List;

import util.MutablePair;
import util.Pair;
import util.fn.Lambda;
import util.fn.Lambda2Void;
import util.fn.Lambda3Void;
import util.fn.LambdaVoid;

// TODO(ddn): Broken for replay
class ProductMapper<A, B> implements Mappable<Pair<A, B>> {
  private Mappable<A> rows;
  private Lambda<A, Mappable<B>> cols;

  public ProductMapper(Mappable<A> rows, Lambda<A, Mappable<B>> cols) {
    this.rows = rows;
    this.cols = cols;
  }

  @Override
  public void mapInternal(LambdaVoid<Pair<A, B>> body, MapInternalContext ctx) {
    final int tid = ctx.getThreadId();
    final List<Adapter<A, B, Object, Object>> bodies = makeBodies(body);
    final MapInternalContext innerCallbacks = new InnerCallbacks(ctx);

    rows.mapInternal(new LambdaVoid<A>() {
      @Override
      public void call(A arg0) {
        Mappable<B> col = cols.call(arg0);
        col.mapInternal(bodies.get(tid), innerCallbacks);
        col.mapInternalDone();
      }
    }, new OuterCallbacks(ctx));
    rows.mapInternalDone();
  }

  @Override
  public <A1> void mapInternal(Lambda2Void<Pair<A, B>, A1> body, MapInternalContext ctx, final A1 arg1) {
    final int tid = ctx.getThreadId();
    final List<Adapter<A, B, A1, Object>> bodies = makeBodies(body);
    final MapInternalContext innerCallbacks = new InnerCallbacks(ctx);

    rows.mapInternal(new LambdaVoid<A>() {
      @Override
      public void call(A arg0) {
        Mappable<B> col = cols.call(arg0);
        col.mapInternal(bodies.get(tid), innerCallbacks, arg1);
        col.mapInternalDone();
      }
    }, new OuterCallbacks(ctx));
    rows.mapInternalDone();
  }

  @Override
  public <A1, A2> void mapInternal(Lambda3Void<Pair<A, B>, A1, A2> body, MapInternalContext ctx, final A1 arg1,
      final A2 arg2) {
    final int tid = ctx.getThreadId();
    final List<Adapter<A, B, A1, A2>> bodies = makeBodies(body);
    final MapInternalContext innerCallbacks = new InnerCallbacks(ctx);

    rows.mapInternal(new LambdaVoid<A>() {
      @Override
      public void call(A arg0) {
        Mappable<B> col = cols.call(arg0);
        col.mapInternal(bodies.get(tid), innerCallbacks, arg1, arg2);
        col.mapInternalDone();
      }
    }, new OuterCallbacks(ctx));
    rows.mapInternalDone();
  }

  private <A1, A2> List<Adapter<A, B, A1, A2>> makeBodies(LambdaVoid<Pair<A, B>> body) {
    int numThreads = GaloisRuntime.getRuntime().getMaxThreads();

    List<Adapter<A, B, A1, A2>> retval = new ArrayList<Adapter<A, B, A1, A2>>(numThreads);
    for (int i = 0; i < numThreads; i++) {
      retval.add(new Adapter<A, B, A1, A2>(body));
    }

    return retval;
  }

  private <A1, A2> List<Adapter<A, B, A1, A2>> makeBodies(Lambda2Void<Pair<A, B>, A1> body) {
    int numThreads = GaloisRuntime.getRuntime().getMaxThreads();

    List<Adapter<A, B, A1, A2>> retval = new ArrayList<Adapter<A, B, A1, A2>>(numThreads);
    for (int i = 0; i < numThreads; i++) {
      retval.add(new Adapter<A, B, A1, A2>(body));
    }

    return retval;
  }

  private <A1, A2> List<Adapter<A, B, A1, A2>> makeBodies(Lambda3Void<Pair<A, B>, A1, A2> body) {
    int numThreads = GaloisRuntime.getRuntime().getMaxThreads();

    List<Adapter<A, B, A1, A2>> retval = new ArrayList<Adapter<A, B, A1, A2>>(numThreads);
    for (int i = 0; i < numThreads; i++) {
      retval.add(new Adapter<A, B, A1, A2>(body));
    }

    return retval;
  }

  @Override
  public final void map(LambdaVoid<Pair<A, B>> body) {
    map(body, MethodFlag.ALL);
  }

  @Override
  public void map(LambdaVoid<Pair<A, B>> body, byte flags) {
    final Adapter<A, B, ?, ?> adaptor = new Adapter<A, B, Object, Object>(body);
    rows.map(new LambdaVoid<A>() {
      @Override
      public void call(A arg0) {
        adaptor.setA(arg0);
        cols.call(arg0).map(adaptor);
      }
    });
  }

  @Override
  public final <A1> void map(Lambda2Void<Pair<A, B>, A1> body, A1 arg1) {
    map(body, arg1, MethodFlag.ALL);
  }

  @Override
  public <A1> void map(Lambda2Void<Pair<A, B>, A1> body, final A1 arg1, byte flags) {
    final Adapter<A, B, A1, ?> adaptor = new Adapter<A, B, A1, Object>(body);
    rows.map(new LambdaVoid<A>() {
      @Override
      public void call(A arg0) {
        adaptor.setA(arg0);
        cols.call(arg0).map(adaptor, arg1);
      }
    });
  }

  @Override
  public final <A1, A2> void map(Lambda3Void<Pair<A, B>, A1, A2> body, A1 arg1, A2 arg2) {
    map(body, arg1, arg2, MethodFlag.ALL);
  }

  @Override
  public <A1, A2> void map(Lambda3Void<Pair<A, B>, A1, A2> body, final A1 arg1, final A2 arg2, byte flags) {
    final Adapter<A, B, A1, A2> adaptor = new Adapter<A, B, A1, A2>(body);
    rows.map(new LambdaVoid<A>() {
      @Override
      public void call(A arg0) {
        adaptor.setA(arg0);
        cols.call(arg0).map(adaptor, arg1, arg2);
      }
    });
  }

  @Override
  public void mapInternalDone() {

  }

  private static class InnerCallbacks implements MapInternalContext {
    private MapInternalContext delegate;

    public InnerCallbacks(MapInternalContext delegate) {
      this.delegate = delegate;
    }

    @Override
    public void abort() {
      delegate.abort();
    }

    @Override
    public void begin() {
      delegate.begin();
    }

    @Override
    public void commit(Object obj) {
      delegate.commit(obj);
    }

    @Override
    public int getThreadId() {
      return 0;
    }
  }

  private static class OuterCallbacks implements MapInternalContext {
    private MapInternalContext delegate;

    public OuterCallbacks(MapInternalContext delegate) {
      this.delegate = delegate;
    }

    @Override
    public void abort() {
      throw new Error("Abort should be processed by inner ctx");
    }

    @Override
    public void begin() {

    }

    @Override
    public void commit(Object obj) {

    }

    @Override
    public int getThreadId() {
      return delegate.getThreadId();
    }
  }

  private static class Adapter<A, B, A1, A2> implements LambdaVoid<B>, Lambda2Void<B, A1>, Lambda3Void<B, A1, A2> {
    private LambdaVoid<Pair<A, B>> body1;
    private Lambda2Void<Pair<A, B>, A1> body2;
    private Lambda3Void<Pair<A, B>, A1, A2> body3;
    private final MutablePair<A, B> pair;

    public Adapter() {
      pair = new MutablePair<A, B>();
    }

    public Adapter(LambdaVoid<Pair<A, B>> body) {
      this();
      this.body1 = body;
    }

    public Adapter(Lambda2Void<Pair<A, B>, A1> body) {
      this();
      this.body2 = body;
    }

    public Adapter(Lambda3Void<Pair<A, B>, A1, A2> body) {
      this();
      this.body3 = body;
    }

    public void setA(A arg0) {
      pair.setFirst(arg0);
    }

    @Override
    public void call(B arg0) {
      pair.setSecond(arg0);
      body1.call(pair);
    }

    @Override
    public void call(B arg0, A1 arg1) {
      pair.setSecond(arg0);
      body2.call(pair, arg1);
    }

    @Override
    public void call(B arg0, A1 arg1, A2 arg2) {
      pair.setSecond(arg0);
      body3.call(pair, arg1, arg2);
    }
  }
}
