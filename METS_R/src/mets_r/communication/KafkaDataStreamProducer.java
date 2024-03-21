package mets_r.communication;

import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.kafka.clients.producer.*;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.mobility.Vehicle;


public class KafkaDataStreamProducer{
	private static final String BOOTSTRAP_SERVERS = "localhost:29092";
	private Producer<String, String> myProducer;
	private ExecutorService executorService;
	private CompletionService<String> compService;
	
	public KafkaDataStreamProducer(){
		Properties props = new Properties();
		props.put("bootstrap.servers", BOOTSTRAP_SERVERS);
		props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
		props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
		
		myProducer = new KafkaProducer<String, String>(props);
		executorService = Executors.newFixedThreadPool(1); // Use one thread to send Kafka message
		compService = new ExecutorCompletionService<String>(executorService);
	}
	
	public void cv2xProduce(Vehicle vehicle, Coordinate coordinate) {
		CV2XDataStream myMessage = new CV2XDataStream(vehicle, coordinate);
		String key = Integer.toString(myMessage.hashCode());
		String message = myMessage.toString();
		Callable<String> sender = ()->{
			myProducer.send(new ProducerRecord<String, String>("cv2x", key, message));
			return "sent cv2x";
		};
		compService.submit(sender);
	}
	
	public void linkEnergyProduce(int vid, int vehType, int roadID, double linkEnergy) {
		LinkEnergyDataStream myMessage = new LinkEnergyDataStream(vid, vehType, roadID, linkEnergy);
		String key = Integer.toString(myMessage.hashCode());
		String message = myMessage.toString();
		Callable<String> sender = () -> {
			myProducer.send(new ProducerRecord<String, String>("link_energy", key, message));
			return "sent link_energy";
		};
		compService.submit(sender);
	}
	
	public void linkTravelTimeProduce(int vid, int vehType, int roadID, double linkEnergy) {
		LinkTravelTimeDataStream myMessage = new LinkTravelTimeDataStream(vid, vehType, roadID, linkEnergy);
		String key = Integer.toString(myMessage.hashCode());
		String message = myMessage.toString();
		Callable<String> sender = () -> {
			myProducer.send(new ProducerRecord<String, String>("link_speed", key, message));
			return "sent link_speed";
		};
		compService.submit(sender);
	}
}
