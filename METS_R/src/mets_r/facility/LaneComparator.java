package mets_r.facility;

import java.util.Comparator;

// Rightmost lane has largest ID
class LaneComparator implements Comparator<Lane> {
	public int compare(Lane l1, Lane l2) {
		int LaneID1, LaneID2;

		LaneID1 = Math.abs(l1.getID());
		LaneID2 = Math.abs(l2.getID());

		if (LaneID1 < LaneID2)
			return -1;
		else if (LaneID1 > LaneID2)
			return 1;
		else
			return 0;
	}

}
