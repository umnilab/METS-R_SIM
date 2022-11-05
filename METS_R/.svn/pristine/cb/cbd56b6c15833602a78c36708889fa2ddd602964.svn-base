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

File: Utility.java 

*/



package evacSim.partition;

import galois.objects.graph.GNode;
import galois.objects.graph.IntGraph;
import galois.objects.graph.MorphGraph;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.zip.GZIPInputStream;

import org.jgrapht.WeightedGraph;

import repast.simphony.context.space.graph.ContextJungNetwork;
import repast.simphony.space.projection.ProjectionEvent;
import repast.simphony.space.projection.ProjectionListener;
import repast.simphony.space.graph.EdgeCreator;
import repast.simphony.space.graph.JungNetwork;
import repast.simphony.space.graph.Network;
import repast.simphony.space.graph.RepastEdge;
import edu.uci.ics.jung.graph.Graph;

import evacSim.citycontext.Junction;
import evacSim.ContextCreator;

public class Utility {

  public static IntGraph<MetisNode> getAllNodes(IntGraph<MetisNode> graph) {
    return graph;
  }

  private static Scanner getScanner(String filename) throws Exception {
    try {
      return new Scanner(new GZIPInputStream(new FileInputStream(filename + ".gz")));
    } catch (FileNotFoundException _) {
      return new Scanner(new FileInputStream(filename));
    }
  }
  
  /**
   * read in the graph from a file and create a graph 
   * @param file the input graph file
   * @param useSerial create serial graph or parallel graph
   */
  public static MetisGraph readGraph(String file) {

    try {
      Scanner scanner = getScanner(file);
      String line = scanner.nextLine().trim();
      String[] segs = line.split(" ");
      int nodeNum = Integer.valueOf(segs[0]);
      IntGraph<MetisNode> graph = new MorphGraph.IntGraphBuilder().backedByVector(true).directed(true).create();     
      assert graph != null;
      ArrayList<GNode<MetisNode>> nodes = new ArrayList<GNode<MetisNode>>();
      for (int i = 0; i < nodeNum; i++) {
    	// TODO:Zhan: Change this part to set the integer weight on the nodes
        GNode<MetisNode> n = graph.createNode(new MetisNode(i, 1)); 
        nodes.add(n);
        graph.add(n);
      }
      int numEdges = 0;
      for (int i = 0; i < nodeNum; i++) {
        line = scanner.nextLine().trim();
        GNode<MetisNode> n1 = nodes.get(i);
        segs = line.split(" ");
        for (int j = 0; j < segs.length; j++) {
          GNode<MetisNode> n2 = nodes.get(Integer.valueOf(segs[j]) - 1);
          graph.addEdge(n1, n2, 1);
          // TODO:Zhan: Change this part to set the integer weight on the edges
          n1.getData().addEdgeWeight(1);
          n1.getData().incNumEdges(); // This one is necessary, the number of edge will not automatically updated
          numEdges++;
        }
      }
      MetisGraph metisGraph = new MetisGraph();
      metisGraph.setNumEdges(numEdges / 2); // Convert to an undirected graph
      metisGraph.setGraph(graph);
      System.out.println("finshied reading graph " + graph.size() + " " + metisGraph.getNumEdges());
      scanner.close();
      return metisGraph;

    } catch (IOException e) {
      e.printStackTrace();
    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }

/*    public static void check(int i, int j, String name){
    	if(i!=j){
    		System.out.println(name+" not equal "+i+" "+j);
    		System.exit(-1);
    	} else {
    		System.out.println(name+" check ok "+i);
    	}
    }*/
}
