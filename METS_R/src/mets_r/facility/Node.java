package mets_r.facility;

public class Node {
	private int ID; // From shape file
	private Junction junction;
	private Road road;
	
	public Node(int id) {
		this.setID(id);
	}

	public Road getRoad() {
		return this.road;
	}

	public void setRoad(Road road) {
		this.road = road;
	}

	public Junction getJunction() {
		return junction;
	}

	public void setJunction(Junction junction) {
		this.junction = junction;
	}

	public int getID() {
		return ID;
	}

	public void setID(int iD) {
		ID = iD;
	}
}
