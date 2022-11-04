package mets_r.mobility;

import java.util.List;
import java.util.Random;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.GlobalVariables;
import mets_r.facility.Road;
import mets_r.routing.RouteV;

/**
 * Request class
 * 
 * @author: Xiaowei Chen
 **/

public class RequestWithValuation extends Request {
	private double valuationRequest;//p's valuation of the trip
	private double tripDistance; // The estimate trip distance based on shortest path
	private double tripTime; // The estimate trip time based on shortest path 
	
	public RequestWithValuation(Integer originid, Integer destinationid, Coordinate origin, Coordinate destination, Random rs){
		super(originid, destinationid, false);
		this.tripDistance = 0;
		this.tripTime = 0;
		List<Road> path = RouteV.shortestPathRoute(origin, destination);
		if (path != null) {
			for (Road r : path) {
				this.tripDistance += r.getLength();
				this.tripTime += r.getTravelTime();
			}
		}
		this.valuationRequest = GlobalVariables.VALUATION_PASS_MEAN + rs.nextGaussian() * GlobalVariables.VALUATION_PASS_STD;
		GlobalVariables.Random_ID_PASS +=1;
		
		if(this.tripDistance <= 0) {
			this.tripDistance = 1;
		}
	}
	
	public double getTripDistance(){ // added by xiaowei on 09/29
		return this.tripDistance;
	}
	
	public double getTripTime(){ // added by xiaowei on 09/29
		return this.tripTime;
	}
	
	public double getValuationRequest(){ // added by xiaowei on 09/29
		return this.valuationRequest;
	}
}
