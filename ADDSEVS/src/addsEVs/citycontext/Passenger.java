package addsEVs.citycontext;

import java.util.LinkedList;
import java.util.Queue;

import addsEVs.GlobalVariables;

/**
 * Passenger class
 * 
 * @author: Zengxiang Lei
 * 
 **/

public class Passenger {
	private Integer origin;
	private Integer destination;
	private Integer maxWaitingTime;
	private Integer currentWaitingTime;
//	private Passenger nextPassengerInQueue_ = null; // deprecated since a linked-list is not thread safe!
	private Boolean willingToShare;
	private Queue<Plan> activityPlan;
	
	public Passenger(Integer origin, Integer destination, Integer maxWaitingTime){
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
	
	public Passenger(Integer origin, Queue<Plan> activityPlan, Integer maxWaitingTime){
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
	
//	public Passenger nextPassengerInQueue(){
//		return this.nextPassengerInQueue_;
//	}
//	
//	public void setNextPassengerInQueue(Passenger nextPass){
//		this.nextPassengerInQueue_ = nextPass;
//	}
}
