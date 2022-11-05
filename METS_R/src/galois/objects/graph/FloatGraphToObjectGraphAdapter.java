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

File: FloatGraphToObjectGraphAdapter.java 

 */

package galois.objects.graph;

import galois.objects.GObject;
import galois.runtime.MapInternalContext;
import util.fn.Lambda2Void;
import util.fn.Lambda3Void;
import util.fn.LambdaVoid;

final class FloatGraphToObjectGraphAdapter<N extends GObject> implements ObjectGraph<N, Float> {

  private final FloatGraph<N> g;

  FloatGraphToObjectGraphAdapter(FloatGraph<N> g) {
    this.g = g;
  }

  @Override
  public GNode<N> createNode(N n) {
    return g.createNode(n);
  }

  @Override
  public GNode<N> createNode(N n, byte flags) {
    return g.createNode(n, flags);
  }

  @Override
  public boolean add(GNode<N> n) {
    return g.add(n);
  }

  @Override
  public boolean add(GNode<N> n, byte flags) {
    return g.add(n, flags);
  }

  @Override
  public boolean remove(GNode<N> n) {
    return g.remove(n);
  }

  @Override
  public boolean remove(GNode<N> n, byte flags) {
    return g.remove(n, flags);
  }

  @Override
  public boolean contains(GNode<N> n) {
    return g.contains(n);
  }

  @Override
  public boolean contains(GNode<N> n, byte flags) {
    return g.contains(n, flags);
  }

  @Override
  public int size() {
    return g.size();
  }

  @Override
  public int size(byte flags) {
    return g.size(flags);
  }

  @Override
  public boolean addNeighbor(GNode<N> src, GNode<N> dst) {
    return g.addNeighbor(src, dst);
  }

  @Override
  public boolean addNeighbor(GNode<N> src, GNode<N> dst, byte flags) {
    return g.addNeighbor(src, dst, flags);
  }

  @Override
  public boolean removeNeighbor(GNode<N> src, GNode<N> dst) {
    return g.removeNeighbor(src, dst);
  }

  @Override
  public boolean removeNeighbor(GNode<N> src, GNode<N> dst, byte flags) {
    return g.removeNeighbor(src, dst, flags);
  }

  @Override
  public boolean hasNeighbor(GNode<N> src, GNode<N> dst) {
    return g.hasNeighbor(src, dst);
  }

  @Override
  public boolean hasNeighbor(GNode<N> src, GNode<N> dst, byte flags) {
    return g.hasNeighbor(src, dst, flags);
  }

  @Override
  public void mapInNeighbors(GNode<N> src, LambdaVoid<GNode<N>> body) {
    g.mapInNeighbors(src, body);
  }

  @Override
  public void mapInNeighbors(GNode<N> src, LambdaVoid<GNode<N>> body, byte flags) {
    g.mapInNeighbors(src, body, flags);
  }

  @Override
  public int inNeighborsSize(GNode<N> src) {
    return g.inNeighborsSize(src);
  }

  @Override
  public int inNeighborsSize(GNode<N> src, byte flags) {
    return g.inNeighborsSize(src, flags);
  }

  @Override
  public int outNeighborsSize(GNode<N> src) {
    return g.outNeighborsSize(src);
  }

  @Override
  public int outNeighborsSize(GNode<N> src, byte flags) {
    return g.outNeighborsSize(src, flags);
  }

  @Override
  public boolean isDirected() {
    return g.isDirected();
  }

  @Override
  public void mapInternal(LambdaVoid<GNode<N>> body, MapInternalContext ctx) {
    g.mapInternal(body, ctx);
  }

  @Override
  public <A1> void mapInternal(Lambda2Void<GNode<N>, A1> body, MapInternalContext ctx, A1 arg1) {
    g.mapInternal(body, ctx, arg1);
  }

  @Override
  public <A1, A2> void mapInternal(Lambda3Void<GNode<N>, A1, A2> body, MapInternalContext ctx, A1 arg1, A2 arg2) {
    g.mapInternal(body, ctx, arg1, arg2);
  }

  @Override
  public void mapInternalDone() {
    g.mapInternalDone();
  }

  @Override
  public void map(LambdaVoid<GNode<N>> body) {
    g.map(body);
  }

  @Override
  public void map(LambdaVoid<GNode<N>> body, byte flags) {
    g.map(body, flags);
  }

  @Override
  public <A1> void map(Lambda2Void<GNode<N>, A1> body, A1 arg1) {
    g.map(body, arg1);
  }

  @Override
  public <A1> void map(Lambda2Void<GNode<N>, A1> body, A1 arg1, byte flags) {
    g.map(body, arg1, flags);
  }

  @Override
  public <A1, A2> void map(Lambda3Void<GNode<N>, A1, A2> body, A1 arg1, A2 arg2) {
    g.map(body, arg1, arg2);
  }

  @Override
  public <A1, A2> void map(Lambda3Void<GNode<N>, A1, A2> body, A1 arg1, A2 arg2, byte flags) {
    g.map(body, arg1, arg2, flags);
  }

  @Override
  public boolean addEdge(GNode<N> src, GNode<N> dst, Float data) {
    return g.addEdge(src, dst, data);
  }

  @Override
  public boolean addEdge(GNode<N> src, GNode<N> dst, Float data, byte flags) {
    return g.addEdge(src, dst, data, flags);
  }

  @Override
  public Float getEdgeData(GNode<N> src, GNode<N> dst) {
    return g.getEdgeData(src, dst);
  }

  @Override
  public Float getEdgeData(GNode<N> src, GNode<N> dst, byte flags) {
    return g.getEdgeData(src, dst, flags);
  }

  @Override
  public Float getEdgeData(GNode<N> src, GNode<N> dst, byte nodeFlags, byte dataFlags) {
    return g.getEdgeData(src, dst, nodeFlags);
  }

  @Override
  public Float setEdgeData(GNode<N> src, GNode<N> dst, Float d) {
    return g.setEdgeData(src, dst, d);
  }

  @Override
  public Float setEdgeData(GNode<N> src, GNode<N> dst, Float d, byte flags) {
    return g.setEdgeData(src, dst, d, flags);
  }

  @Override
  public void access(byte flags) {
    g.access(flags);
  }
}
