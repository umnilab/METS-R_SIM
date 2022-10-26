package addsEVs.citycontext;

import java.util.LinkedList;
import java.util.Queue;

import addsEVs.GlobalVariables;

/**
 * Request class
 * 
 * @author: Zengxiang Lei
 **/

public class Request {
	protected Integer origin;
	protected Integer destination;
	protected Integer maxWaitingTime;
	protected Integer currentWaitingTime;
	protected Boolean willingToShare;
	protected Queue<Plan> activityPlan;
	
	public Request(Integer origin, Integer destination, Integer maxWaitingTime){
		this.activityPlan = new LinkedList<Plan>();
		this.origin = origin;
		this.destination = destination;
		this.maxWaitingTime = maxWaitingTime;
		this.currentWaitingTime = 0;	
		if(Math.random()<GlobalVariables.PASSENGER_SHARE_PERCENTAGE) {
			this.willingToShare = true;
		}
		else {
			this.willingToShare = false;
		}
	}
	
	public Request(Integer origin, Queue<Plan> activityPlan, Integer maxWaitingTime){
		this.activityPlan = activityPlan;
		this.maxWaitingTime = maxWaitingTime;
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
	
	public int getWaitingTime(){
		return this.currentWaitingTime;
	}
	
	public void setWaitingTime(int waiting_time) {
		this.currentWaitingTime = waiting_time;
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
