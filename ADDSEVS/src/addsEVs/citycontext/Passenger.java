package addsEVs.citycontext;

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
	private Passenger nextPassengerInQueue_ = null;
	private Boolean willingToShare;
	
	public Passenger(Integer origin, Integer destination, Integer maxWaitingTime){
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
	
	public boolean isShareable(){
		return this.willingToShare;
	}
	
	public Passenger nextPassengerInQueue(){
		return this.nextPassengerInQueue_;
	}
	
	public void setNextPassengerInQueue(Passenger nextPass){
		this.nextPassengerInQueue_ = nextPass;
	}
}
