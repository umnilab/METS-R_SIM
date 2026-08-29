package mets_r.communication;

import java.util.Properties;

import org.apache.kafka.clients.producer.*;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.mobility.Vehicle;


public class KafkaDataStreamProducer{
	private static final String BOOTSTRAP_SERVERS = "localhost:29092";
	private Producer<String, String> myProducer;
	
	public KafkaDataStreamProducer(){
		Properties props = new Properties();
		props.put("bootstrap.servers", BOOTSTRAP_SERVERS);
		props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
		props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
		props.put("buffer.memory", "33554432");
		props.put("max.block.ms", "100");
		props.put("delivery.timeout.ms", "30000");
		
		myProducer = new KafkaProducer<String, String>(props);
	}
	
	public void produceBSM(Vehicle vehicle, Coordinate coordinate, int type) {
		int vid;
	    if (vehicle.getVehicleClass() == Vehicle.EV || vehicle.getVehicleClass() == Vehicle.GV) {
	        vid = ContextCreator.getVehicleContext().getPrivateVID(vehicle.getID());
	    } else {
	        vid = vehicle.getID();
	    }
		BSMDataStream myMessage = new BSMDataStream(vid, vehicle, coordinate, type);
		String key = Integer.toString(myMessage.hashCode());
		String message = myMessage.toString();
		this.send("bsm", key, message);
	}
	
	public void produceLinkEnergy(int vid, int vehType, int roadID, double linkEnergy) {
		LinkEnergyDataStream myMessage = new LinkEnergyDataStream(vid, vehType, roadID, linkEnergy);
		String key = Integer.toString(myMessage.hashCode());
		String message = myMessage.toString();
		this.send("link_energy", key, message);
	}
	
	public void produceLinkTravelTime(int vid, int vehType, int roadID, double linkTravelTime, double length) {
		LinkTravelTimeDataStream myMessage = new LinkTravelTimeDataStream(vid, vehType, roadID, linkTravelTime, length);
		String key = Integer.toString(myMessage.hashCode());
		String message = myMessage.toString();
		this.send("link_tt", key, message);
	}

	private void send(String topic, String key, String message) {
		try {
			this.myProducer.send(new ProducerRecord<String, String>(topic, key, message),
					(metadata, failure) -> {
						if (failure != null) {
							ContextCreator.logger.warn(
									"Kafka send failed for topic " + topic, failure);
						}
					});
		} catch (RuntimeException failure) {
			ContextCreator.logger.warn(
					"Kafka send rejected for topic " + topic, failure);
		}
	}

	public void close() {
		myProducer.flush();
		myProducer.close();
	}
}
