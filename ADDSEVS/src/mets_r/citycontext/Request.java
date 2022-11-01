package mets_r.citycontext;

import java.util.LinkedList;
import java.util.Queue;

import mets_r.GlobalVariables;

/**
 * Request class
 * 
 * @author: Zengxiang Lei
 * 
 **/

public class Request {
	private Integer origin;
	private Integer destination;
	private Integer maxWaitingTime;
	private Integer currentWaitingTime;
	private Boolean willingToShare;
	private Queue<Plan> activityPlan;
	
	public Request(Integer origin, Integer destination){
		this.activityPlan = new LinkedList<Plan>();
		this.origin = origin;
		this.destination = destination;
		this.maxWaitingTime = GlobalVariables.SIMULATION_STOP_TIME; // The passenger will not leave by default
		this.currentWaitingTime = 0;	
		if(Math.random()<GlobalVariables.PASSENGER_SHARE_PERCENTAGE) {
			this.willingToShare = true;
		}
		else {
			this.willingToShare = false;
		}
	}
	
	public Request(Integer origin, Queue<Plan> activityPlan){
		this.activityPlan = activityPlan;
		this.maxWaitingTime = GlobalVariables.SIMULATION_STOP_TIME;;
		this.currentWaitingTime = 0;	
		this.willingToShare = false;
		this.origin = origin;
		this.destination = activityPlan.peek().getDestID();
	}
	
	public void waitNextTime(Integer waitingTime){
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
	
	public void setCurrentWaitingTime(int waiting_time) {
		this.currentWaitingTime = waiting_time;
	}
	
	public int getMaxWaitingTime() {
		return this.maxWaitingTime;
	}
	
	public void setMaxWaitingTime(int waiting_time) {
		this.maxWaitingTime = waiting_time;
	}
	
	public void setOrigin(Integer origin){
		this.origin = origin;
	}
	
	public void setDestination(Integer destination){
		this.destination = destination;
	}
	
	public int getOrigin(){
		return this.origin;
	}
	
	public int getDestination(){
		return this.destination;
	}
	
	public Queue<Plan> getActivityPlan(){
		return this.activityPlan;
	}
	
	public void moveToNextActivity() {
		this.currentWaitingTime = 0; // reset the waiting time
		this.origin = this.activityPlan.poll().getDestID();
		this.destination = this.activityPlan.peek().getDestID();
	}
	
	public int lenOfActivity(){
		if(this.activityPlan!=null){
			return this.activityPlan.size();
		}
		else {
			return 0;
		}
	}
	
	public boolean isShareable(){
		return this.willingToShare;
	}
	
	public void clearActivityPlan() {
		this.activityPlan.clear();
	}
}
