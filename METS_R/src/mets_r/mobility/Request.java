package mets_r.mobility;

import java.util.LinkedList;
import java.util.Queue;
import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;

/**
 * Trip request
 * 
 * @author: Zengxiang Lei
 **/

public class Request {
	private int ID;
	private int origin;
	private int destination;
	private int originRoad;
	private int destRoad;
	private int maxWaitingTime;
	private int currentWaitingTime;
	private int numPeople;
	
	private Boolean willingToShare = false; 
	private int busRouteID = -1; // transit route ID, default value: -1 not using transit
	
	private Queue<Plan> activityPlan;
	
	// public variables for data collection
	public int generationTime;
	public int matchedTime;
	public int pickupTime;
	public int arriveTIme;
	
	public Request(int origin, int destination, int originRoad, int destRoad, int numPeople){
		this.ID = ContextCreator.generateAgentID();
		this.activityPlan = new LinkedList<Plan>();
		this.origin = origin;
		this.destination = destination;
		this.originRoad = originRoad;
		this.destRoad = destRoad;
		this.maxWaitingTime = GlobalVariables.SIMULATION_STOP_TIME; // The passenger will not leave by default
		this.currentWaitingTime = 0;
		this.numPeople = numPeople;
		
		this.generationTime = ContextCreator.getCurrentTick();
	}
	
	public Request(int origin, int originRoad, int numPeople, Queue<Plan> activityPlan){
		this.ID = ContextCreator.generateAgentID();
		this.activityPlan = activityPlan;
		this.maxWaitingTime = GlobalVariables.SIMULATION_STOP_TIME;;
		this.currentWaitingTime = 0;	
		this.origin = origin;
		this.originRoad = originRoad;
		this.destination = activityPlan.peek().getDestZoneID();
		this.destRoad = activityPlan.peek().getDestRoadID();
		this.numPeople = numPeople;
		this.generationTime = ContextCreator.getCurrentTick();
	}
	
	public void waitNextTime(int waitingTime){
		this.currentWaitingTime+=waitingTime;
	}
	
	// Condition for passenger to left the service
	public boolean check(){
		if(this.currentWaitingTime > this.maxWaitingTime){
			return true;
		}
		else{
			return false;
		}
	}
	
	public int getCurrentWaitingTime(){
		return this.currentWaitingTime;
	}
	
	public int getMaxWaitingTime() {
		return this.maxWaitingTime;
	}
	
	public void setMaxWaitingTime(int waiting_time) {
		this.maxWaitingTime = waiting_time;
	}
	
	public void setOrigin(int origin){
		this.origin = origin;
	}
	
	public void setDestination(int destination){
		this.destination = destination;
	}
	
	public int getOriginZone(){
		return this.origin;
	}
	
	public int getDestZone(){
		return this.destination;
	}
	
	public int getOriginRoad(){
		return this.originRoad;
	}
	
	public int getDestRoad(){
		return this.destRoad;
	}
	public Coordinate getOriginCoord(){
		return ContextCreator.getRoadContext().get(this.originRoad).getStartCoord();
	}
	
	public Coordinate getDestCoord(){
		return ContextCreator.getRoadContext().get(this.destRoad).getEndCoord();
	}
	
	public Queue<Plan> getActivityPlan(){
		return this.activityPlan;
	}
	
	public void moveToNextActivity() {
		this.currentWaitingTime = 0; // reset the waiting time
		Plan prevPlan = this.activityPlan.poll();
		this.origin = prevPlan.getDestZoneID();
		this.originRoad = prevPlan.getDestRoadID();
		Plan currPlan = this.activityPlan.peek();
		this.destination = currPlan.getDestZoneID();
		this.destRoad = currPlan.getDestRoadID();
	}
	
	public int lenOfActivity(){
		if(this.activityPlan!=null){
			return this.activityPlan.size();
		}
		else {
			return 0;
		}
	}
	
	public void setWillingToShare(boolean wts) {
		this.willingToShare = wts;
	}
	
	public boolean isShareable(){
		return this.willingToShare;
	}
	
	public void setBusRoute(int routeID) {
		this.busRouteID = routeID;
	}
	
	public int getBusRoute() {
		return this.busRouteID;
	}
	
	public void clearActivityPlan() {
		this.activityPlan.clear();
	}

	public int getID() {
		return ID;
	}
	
	public int getNumPeople() {
		return numPeople;
	}
}
