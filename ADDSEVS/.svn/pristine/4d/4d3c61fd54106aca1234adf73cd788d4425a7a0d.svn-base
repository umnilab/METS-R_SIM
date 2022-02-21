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

File: PQueue.java 

*/



package evacSim.partition;

import galois.objects.graph.GNode;
import java.util.Arrays;

/**
 * A priority queue which combines two implementations: 
 * 1. heap based
 * 2. array(for priority values) + linked-list (saving the elemements of the queue having the same priority)
 * Which one to use depends on the max number of nodes of queue
 */
public class PQueue {

	private int nnodes;
	private int type;
	private int maxgain;
	private int pgainspan;
	private int ngainspan;
	private ListNode[] nodes;
	private ListNode[] buckets;
	private int bucketIndex;
	//heap version
	private KeyValue[] heap;
	private int[] locator;

	private final int PLUS_GAINSPAN = 500;
	private final int NEG_GAINSPAN = 500;

	public PQueue(int maxnodes, int maxgain) {

		nnodes = 0;
		if (maxgain > PLUS_GAINSPAN || maxnodes < 500) {
			type = 2;
		} else {
			type = 1;
		}
		if (type == 1) {
			pgainspan = Math.min(PLUS_GAINSPAN, maxgain);
			ngainspan = Math.min(NEG_GAINSPAN, maxgain);
			int j = ngainspan + pgainspan + 1;
			nodes = new ListNode[maxnodes];
			buckets = new ListNode[j];
			bucketIndex = ngainspan;
			this.maxgain -= ngainspan;
		} else {
			heap = new KeyValue[maxnodes];
			for (int i = 0; i < maxnodes; i++) {
				heap[i] = new KeyValue();
			}
			locator = new int[maxnodes];
			Arrays.fill(locator, -1);
		}
	}

	/**
	 * insert a node with its gain
	 */
	public void insert(GNode<MetisNode> node, int gain) {
		if (type == 1) {
			nnodes++;
			int id = node.getData().getNodeId();
			nodes[id] = new ListNode(node);
			ListNode newNode = nodes[id];
			//			System.out.println(gain+" "+bucketIndex+" "+PLUS_GAINSPAN+" "+buckets.length+" ");
			newNode.next = buckets[gain + bucketIndex];
			if (newNode.next != null)
				newNode.next.prev = newNode;

			buckets[gain + bucketIndex] = newNode;
			if (maxgain < gain)
				maxgain = gain;
		} else {
			//heap
			int i = nnodes;
			nnodes++;
			while (i > 0) {
				int j = (i - 1) / 2;
				if (heap[j].key < gain) {
					heap[i].key = heap[j].key;
					heap[i].value = heap[j].value;
					locator[heap[i].value.getData().getNodeId()] = i;
					i = j;
				} else {
					break;
				}
			}
			heap[i].key = gain;
			heap[i].value = node;
			locator[node.getData().getNodeId()] = i;
		}
	}

	/**
	 * delete a node from the queue
	 */
	public void delete(GNode<MetisNode> value, int gain) {
		if (type == 1) {
			nnodes--;
			ListNode node = nodes[value.getData().getNodeId()];
			if (node.prev != null)
				node.prev.next = node.next;
			else
				buckets[gain + bucketIndex] = node.next;
			if (node.next != null)
				node.next.prev = node.prev;

			if (buckets[gain + bucketIndex] == null && gain == maxgain) {
				if (nnodes == 0)
					maxgain = -ngainspan;
				else
					for (; buckets[maxgain + bucketIndex] == null; maxgain--)
						;
			}
		} else {
			int id = value.getData().getNodeId();
			int i = locator[id];
			locator[id] = -1;
			if (--nnodes > 0 && heap[nnodes].value.getData().getNodeId() != id) {
				value = heap[nnodes].value;
				int newgain = heap[nnodes].key;
				int oldgain = heap[i].key;

				if (oldgain < newgain) {
					while (i > 0) {
						int j = (i - 1) >> 1;
			if (heap[j].key < newgain) {
				heap[i].value = heap[j].value;
				heap[i].key = heap[j].key;
				locator[heap[i].value.getData().getNodeId()] = i;
				i = j;
			} else
				break;
					}
				} else {
					int j = 0;
					while ((j = 2 * i + 1) < nnodes) {
						if (heap[j].key > newgain) {
							if (j + 1 < nnodes && heap[j + 1].key > heap[j].key)
								j = j + 1;
							heap[i].value = heap[j].value;
							heap[i].key = heap[j].key;
							locator[heap[i].value.getData().getNodeId()] = i;
							i = j;
						} else if (j + 1 < nnodes && heap[j + 1].key > newgain) {
							j = j + 1;
							heap[i].value = heap[j].value;
							heap[i].key = heap[j].key;
							locator[heap[i].value.getData().getNodeId()] = i;
							i = j;
						} else
							break;
					}
				}
				heap[i].key = newgain;
				heap[i].value = value;
				locator[value.getData().getNodeId()] = i;
			}
		}
	}

	/**
	 * after changing the gain of a node, its position in the queue has to be updated  
	 */
	public void update(GNode<MetisNode> value, int oldgain, int newgain) {
		if (type == 1) {
			delete(value, oldgain);
			insert(value, newgain);
		} else {
			int i = locator[value.getData().getNodeId()];
			if (oldgain < newgain) {
				while (i > 0) {
					int j = (i - 1) >> 1;
			if (heap[j].key < newgain) {
				heap[i].value = heap[j].value;
				heap[i].key = heap[j].key;
				locator[heap[i].value.getData().getNodeId()] = i;
				i = j;
			} else
				break;
				}
			} else {
				int j = 0;
				while ((j = 2 * i + 1) < nnodes) {
					if (heap[j].key > newgain) {
						if (j + 1 < nnodes && heap[j + 1].key > heap[j].key)
							j = j + 1;
						heap[i].value = heap[j].value;
						heap[i].key = heap[j].key;
						locator[heap[i].value.getData().getNodeId()] = i;
						i = j;
					} else if (j + 1 < nnodes && heap[j + 1].key > newgain) {
						j = j + 1;
						heap[i].value = heap[j].value;
						heap[i].key = heap[j].key;
						locator[heap[i].value.getData().getNodeId()] = i;
						i = j;
					} else
						break;
				}
			}
			heap[i].key = newgain;
			heap[i].value = value;
			locator[value.getData().getNodeId()] = i;
		}
	}

	/**
	 * return the node with the max gain in the queue
	 */
	public GNode<MetisNode> getMax() {

		if (nnodes == 0)
			return null;
		ListNode tptr = null;
		nnodes--;
		if (type == 1) {
			tptr = buckets[maxgain + bucketIndex];
			buckets[maxgain + bucketIndex] = tptr.next;
			if (tptr.next != null) {
				tptr.next.prev = null;
			} else {
				if (nnodes == 0) {
					maxgain = -ngainspan;
				} else {
					for (; buckets[maxgain + bucketIndex] == null; maxgain--)
						;
				}
			}
			return tptr.value;
		} else {
			GNode<MetisNode> vtx = heap[0].value;
			locator[vtx.getData().getNodeId()] = -1;
			int i = nnodes;
			if (i > 0) {
				int gain = heap[i].key;
				GNode<MetisNode> node = heap[i].value;
				i = 0;
				int j = 0;
				while ((j = 2 * i + 1) < nnodes) {
					if (heap[j].key > gain) {
						if (j + 1 < nnodes && heap[j + 1].key > heap[j].key)
							j = j + 1;
						heap[i].value = heap[j].value;
						heap[i].key = heap[j].key;
						locator[heap[i].value.getData().getNodeId()] = i;
						i = j;
					} else if (j + 1 < nnodes && heap[j + 1].key > gain) {
						j = j + 1;
						heap[i].value = heap[j].value;
						heap[i].key = heap[j].key;
						locator[heap[i].value.getData().getNodeId()] = i;
						i = j;
					} else
						break;
				}
				heap[i].key = gain;
				heap[i].value = node;
				locator[node.getData().getNodeId()] = i;
			}
			return vtx;
		}

	}

	/**
	 * reset the queue
	 */
	public void reset() {
		nnodes = 0;
		if (type == 1) {
			maxgain = -ngainspan;
			int j = ngainspan + pgainspan + 1;

			for (int i = 0; i < j; i++) {
				buckets[i] = null;
			}
			this.bucketIndex = ngainspan;
		} else {
			Arrays.fill(locator, -1);
		}
	}
}

class ListNode {
	public ListNode(GNode<MetisNode> node) {
		value = node;
	}
	GNode<MetisNode> value;
	ListNode prev;
	ListNode next;
}

class KeyValue {
	int key;
	GNode<MetisNode> value;
}
