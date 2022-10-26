package addsEVs.citycontext;

import java.util.List;
import com.vividsolutions.jts.geom.Coordinate;

import addsEVs.ContextCreator;
import addsEVs.GlobalVariables;
import addsEVs.routing.RouteV;

/**
 * Request class
 * 
 * @author: Xiaowei Chen
 **/

public class RequestWithValuation extends Request {
	private double getValuationRequest;//p's valuation of the trip
	private double tripDistance; // The estimate trip distance based on shortest path
	private double tripTime; // The estimate trip time based on shortest path 
	
	public RequestWithValuation(Integer originid, Integer destinationid, Coordinate origin, Coordinate destination){
		super(originid, destinationid, 4000);
		this.willingToShare = false;
		this.tripDistance = 0;
		this.tripTime = 0;
		List<Road> path = RouteV.shortestPathRoute(origin, destination);
		if (path != null) {
			for (Road r : path) {
				this.tripDistance += r.getLength();
				this.tripTime += r.getTravelTime();
			}
		}
		this.getValuationRequest = ContextCreator.cachedRandomValue.valuatioRequest.get(GlobalVariables.Random_ID_PASS);
		GlobalVariables.Random_ID_PASS +=1;
	}
	
	public double getTripDistance(){ // added by xiaowei on 09/29
		return this.tripDistance;
	}
	
	public double getTripTime(){ // added by xiaowei on 09/29
		return this.tripTime;
	}
	
	public double getValuationRequest(){ // added by xiaowei on 09/29
		return this.getValuationRequest;
	}
}
